/**
 * Regenerate the license fingerprint corpus (F-QUA-10).
 *
 * Derives discriminative shingle hashes from canonical license marker text and
 * prints the canonical corpus JSON to stdout. Only hashes are emitted — the
 * marker text below never ships in any package. The output must be embedded
 * byte-identically in all three SDKs:
 *
 *   - packages/gavio-js/src/interceptors/guardrails/data/license-fingerprints.ts
 *   - packages/gavio-py/gavio/interceptors/guardrails/validators/_license_fingerprints.py
 *   - packages/gavio-java/gavio-interceptor-guardrails/src/main/resources/license-fingerprints.json
 *
 * Parity across SDKs is enforced by test-vectors/license/. To regenerate:
 *   node scripts/gen-license-fingerprints.mjs > /tmp/corpus.json
 */

import { createHash } from 'node:crypto'

// Canonical marker text per SPDX license — authoring input only, never shipped.
const MARKERS = {
  'MIT':
    'Permission is hereby granted, free of charge, to any person obtaining a copy ' +
    'of this software and associated documentation files (the "Software"), to deal ' +
    'in the Software without restriction, including without limitation the rights ' +
    'to use, copy, modify, merge, publish, distribute, sublicense, and/or sell ' +
    'copies of the Software, and to permit persons to whom the Software is furnished to do so',
  'Apache-2.0':
    'Licensed under the Apache License, Version 2.0 (the "License"); you may not ' +
    'use this file except in compliance with the License. You may obtain a copy of ' +
    'the License at. Unless required by applicable law or agreed to in writing, software ' +
    'distributed under the License is distributed on an "AS IS" BASIS',
  'GPL-3.0':
    'This program is free software: you can redistribute it and/or modify it under ' +
    'the terms of the GNU General Public License as published by the Free Software ' +
    'Foundation, either version 3 of the License, or (at your option) any later version. ' +
    'This program is distributed in the hope that it will be useful',
  'GPL-2.0':
    'This program is free software; you can redistribute it and/or modify it under ' +
    'the terms of the GNU General Public License as published by the Free Software ' +
    'Foundation; either version 2 of the License, or (at your option) any later version. ' +
    'This program is distributed in the hope that it will be useful',
  'BSD-3-Clause':
    'Redistribution and use in source and binary forms, with or without modification, ' +
    'are permitted provided that the following conditions are met. Neither the name of ' +
    'the copyright holder nor the names of its contributors may be used to endorse or ' +
    'promote products derived from this software without specific prior written permission',
  'MPL-2.0':
    'This Source Code Form is subject to the terms of the Mozilla Public License, ' +
    'v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain ' +
    'one at http://mozilla.org/MPL/2.0/',
}

const SHINGLE_N = 8

function normalizeTokens(text) {
  const out = []
  let cur = ''
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i)
    if (c >= 65 && c <= 90) cur += String.fromCharCode(c + 32)
    else if ((c >= 97 && c <= 122) || (c >= 48 && c <= 57)) cur += String.fromCharCode(c)
    else if (cur) {
      out.push(cur)
      cur = ''
    }
  }
  if (cur) out.push(cur)
  return out
}

function shingleHashes(tokens) {
  const hashes = []
  for (let i = 0; i + SHINGLE_N <= tokens.length; i++) {
    const gram = tokens.slice(i, i + SHINGLE_N).join(' ')
    hashes.push(createHash('sha256').update(gram, 'utf8').digest('hex').slice(0, 16))
  }
  return hashes
}

const raw = {}
for (const [id, text] of Object.entries(MARKERS)) {
  raw[id] = new Set(shingleHashes(normalizeTokens(text)))
}

// Drop shingles shared by >1 license so every retained hash is discriminative.
const counts = new Map()
for (const set of Object.values(raw)) {
  for (const h of set) counts.set(h, (counts.get(h) ?? 0) + 1)
}

const licenses = {}
for (const id of Object.keys(MARKERS).sort()) {
  const unique = [...raw[id]].filter((h) => counts.get(h) === 1).sort()
  if (unique.length === 0) throw new Error(`no discriminative shingles left for ${id}`)
  licenses[id] = unique
  process.stderr.write(`${id}: ${unique.length} unique shingles (of ${raw[id].size})\n`)
}

process.stdout.write(
  JSON.stringify(
    {
      schemaVersion: '1.0',
      algorithm: { normalize: 'ascii-lower-alnum', shingle: SHINGLE_N, hash: 'sha256-16hex', uniqueOnly: true },
      licenses,
    },
    null,
    2,
  ) + '\n',
)
