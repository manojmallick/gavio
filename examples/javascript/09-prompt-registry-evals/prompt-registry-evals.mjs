// Gavio Prompt Registry + Evals - versioned templates and safe reports.

import { EvalSuite, PromptRegistry, PromptTemplate } from 'gavio/prompts'

const registry = new PromptRegistry([
  new PromptTemplate({
    id: 'support.reply',
    version: '2026-07-12',
    messages: [
      { role: 'system', content: 'You are a concise support assistant.' },
      { role: 'user', content: 'Reply to {{ customer }} about {{ topic }}.' },
    ],
    requiredVariables: ['customer', 'topic'],
  }),
])

const rendered = registry.render('support.reply', { customer: 'Avery', topic: 'refund' })
const suite = new EvalSuite({
  id: 'support-smoke',
  cases: [
    {
      id: 'refund-pass',
      templateId: 'support.reply',
      variables: { customer: 'Avery', topic: 'refund' },
      assertions: [{ type: 'contains', value: 'refund' }],
    },
    {
      id: 'refund-fail',
      templateId: 'support.reply',
      variables: { customer: 'Avery', topic: 'refund' },
      assertions: [{ type: 'not_contains', value: 'card number' }],
    },
  ],
})
const failureOutput = 'Hello Avery, please send your card number.'
const outputs = new Map([
  ['refund-pass', 'Hello Avery, your refund is approved.'],
  ['refund-fail', failureOutput],
])

const report = await suite.run(registry, (_prompt, testCase) => outputs.get(testCase.id) ?? '')
const serialized = JSON.stringify(report)

console.log(`template=${rendered.lineage.templateId}@${rendered.lineage.templateVersion}`)
console.log(`score=${report.score}`)
console.log(`passed=${report.passedCases}/${report.totalCases}`)
console.log(`output_hash_len=${report.cases[0].outputHash.length}`)
console.log(`raw_output_stored=${serialized.includes(failureOutput)}`)
