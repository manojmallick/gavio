/**
 * License fingerprint corpus (F-QUA-10).
 *
 * Maps SPDX license id → the SHA-256 (first 16 hex chars) of each distinctive
 * 8-word shingle of that license's canonical marker text. Only hashes are
 * shipped — never the license text itself. Shingles shared by more than one
 * license are dropped so a hit is discriminative (e.g. GPL-2.0 vs GPL-3.0).
 *
 * Shared, byte-identical across the Python, Java and JavaScript SDKs; parity is
 * enforced by test-vectors/license/. See scripts/gen-license-fingerprints.mjs.
 */

export const LICENSE_FINGERPRINT_ALGORITHM = {
  normalize: 'ascii-lower-alnum',
  shingle: 8,
  hash: 'sha256-16hex',
  uniqueOnly: true,
} as const

export const LICENSE_FINGERPRINTS: Record<string, string[]> = {
  'Apache-2.0': [
    '03bdad865c745335', '03fc2f063108b0eb', '04154ca1653ade66', '090078da01f3dd7d',
    '0a64304481c6d52d', '1498d7a7510e9d82', '1a938ab09de2dbd8', '21d55e1291918fdd',
    '28de6b6f31af692f', '2ad7d5137e1c5c36', '31ad82b37d2122e1', '320ba2a1c0cb2312',
    '34297782b3f2fa71', '37ca8f26da05eb05', '3b5ddde18d45f9d7', '4e15520a3c17afe0',
    '559f7255c7ef2e3b', '560ec6a82aee1aa5', '575d2ac4d0bfd439', '645557768d4b4b7c',
    '7d19a33048d5b446', '832964cda5a345d8', '854582f6ac6d1c7a', '87d54649cf81f972',
    '899cdbc5a4be9d6d', '8cd2d3b747fbbed4', '94147f90a3e70a6f', '974ce1be48b3897f',
    '9be5fd9b4a9efc61', '9cb8a511ed5d5759', 'a1d8a20a7d33b6d7', 'a73c0618688ffb0e',
    'b36de5bc6625c98f', 'c0610ca4550f70a4', 'c1bcd843ae817298', 'c295f57509778400',
    'c3b40f047813307e', 'c651be14ab4c9894', 'cf7eddcd6bf717c0', 'd2b15a7b0a90f523',
    'd888da720ca42a4d', 'd938b8c6618bc99a', 'd9bec61c1f550067', 'f5aed373f52b7953',
    'f5ec11d135da80c2', 'f88cb91ed1393055',
  ],
  'BSD-3-Clause': [
    '04dfea8447f2f850', '0e6b8adf37c7e7ae', '1da64f2235c11c3e', '2df2ae69c533b42a',
    '2f46f6d70c8496e2', '3429b6c08faad262', '38a8645e9491e6db', '3e55cf8856d0e6e1',
    '4219226ad5f527c4', '4ad48f16d3936b80', '4ef24cd724348d46', '588083b6c6bdae18',
    '5fe344525ffd8e06', '63276b99d5e00fed', '66d5f77cdff76eac', '6d2aac6251c51e05',
    '6e5bc239b5e99ddc', '730cfb73658a853d', '7322f25804a06fd7', '7b43776badb37dfc',
    '8b5f7bac24f26a59', '95a2ac3294859481', '9c22de9415ae0676', '9dc781fe257e4df9',
    'a02e6d9b7271a7c8', 'a1988753e6711519', 'ad6a01c9972dd1b4', 'af44711844c49727',
    'b31c4143a265a763', 'c092849052170a15', 'cf6cf3ff1b297cbf', 'd34a6cd6c693a94b',
    'd6092469e114cd52', 'd77aadb7df98497e', 'd94ab77e07d10f28', 'db3cadd000bc360d',
    'dd97593da9854513', 'e5d06f523ee103f6', 'e85b9bb56002042c', 'ed707b61198e7a51',
    'edd167060d6c46fe', 'f129dcb0e7913e64', 'f1f1e95b693ad464', 'fd47dae09e948578',
  ],
  'GPL-2.0': [
    '008093fcd2a06b90', '00dd78e74e99e661', '158dde3e53b50bc5', '2d696e6d39ebc630',
    '99d8e90ead45d8e1', 'abfe592b91b0b624', 'bcce1bfd5b86efa9', 'f8deb8146d3ce23e',
  ],
  'GPL-3.0': [
    '01eaa34e27f76309', '2bb3a73024ce5830', '2c095d82b84cecce', '376b29fb836a045c',
    '47b6193852dccfa4', '87df3df56d684a3f', 'cbf9392b2d72bcb9', 'ddc4b85f195ea9d1',
  ],
  'MIT': [
    '0001c6ccfa812f98', '00df8cb120150f71', '012e2cc244392290', '02405a9fb58c0191',
    '03fa5ee71c22b302', '04a8f58a8f510dc5', '0c6eb6a355c354cc', '0ca680856db42d76',
    '165902f70a668e74', '16d72506a275c6e2', '1d83d239551b26bc', '1d9f8e329fa7597e',
    '1df65bf4fe51d3c8', '2cd63a3f917c41f7', '3a810ae9aa80f0d3', '44cfed76a08e4a08',
    '4579d4645b78f52f', '46e80dec7199ce7c', '521be0d7e6d752f6', '59babff919622e8f',
    '5b103d4f53a48769', '5b8de4e1e2acbc17', '5c95883f35b42fe6', '67f295b7215833f6',
    '693fe627825eeea4', '747a42eca891dc0b', '7a32a0bc069b3a40', '8c52b48ce21e5b56',
    '921d9b0e3da52755', '9e85d463f440ec06', '9fba6af118af8c19', 'a97270cbb4108ec3',
    'ae13f63ada435bdf', 'b00a88355d48f03a', 'b201b745bf186158', 'b4346488792dca96',
    'b4d4f0e020858e57', 'b662089c220bb6c4', 'be9d216721c7cf54', 'caf1a059e3e18b27',
    'cbbccad18a3aac30', 'ccef2e1a44eec039', 'd0d3de62ac350c67', 'dc850d40a45f6c65',
    'dd29109c16d0698b', 'de2a2baefe0c63c4', 'dfec87a5ffd90c37', 'e13f7fe1f6447315',
    'e79a45a2358eabff', 'ef0c32a63dab178c', 'f3b28691d9ca4c80', 'f43265e16b768abe',
    'f9fa5ab4b6527d8e', 'fe8a43876b1f4837', 'ff1abe7e9d8582e1',
  ],
  'MPL-2.0': [
    '0a2197799f84bbe5', '0a787b3b60bb416a', '18d27046d9083ca1', '1dc56a6710a658cf',
    '1edba4c75fc218b5', '2220ace213cb8b21', '2971f1257d0620ff', '3c890b1b32740c91',
    '3d50157a5ed52605', '41b591003267f621', '4ce1aa9877604779', '50ccbd4ddd69a8fc',
    '67282fbdadc62abd', '67a7efbdd5cf7ba0', '6bfd25e61c33eecd', '796d9c593cd63c5c',
    '79be75dafa17fc1d', '818b8b6baa858bc0', '92b3098c7600fa26', 'a263f2fdaf3addc3',
    'ac2563bcc4899395', 'af3a316d43d28818', 'b3e394f3390ab7b4', 'b6d4444a9a8dc195',
    'baa945984fbaa103', 'c2fdf254095546cc', 'c9006da562da1ada', 'd396c1a72fa5b1ab',
    'dd5eb35284094da7', 'eb6528e418a9e44f', 'f46c2d5359f2add1', 'f8694052e5ef2ecb',
    'f8aca36b5b082136',
  ],
}
