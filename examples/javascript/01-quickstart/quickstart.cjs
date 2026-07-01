// Same quickstart in CommonJS (plain JS, no ESM, no TypeScript).
// Gavio ships a dual build, so `require()` works exactly like `import`.
//
//   npm install
//   node quickstart.cjs

const { Gateway } = require('gavio')
const { piiGuard } = require('gavio/interceptors/pii')

async function main() {
  const gw = new Gateway({ devMode: true }).use(piiGuard())

  const r = await gw.complete({
    messages: [
      { role: 'user', content: 'Email jan@example.com about IBAN NL91ABNA0417164300' },
    ],
    agentId: 'quickstart-cjs',
  })

  console.log('\nReply    :', r.content)
  console.log('PII found:', r.audit.piiEntityTypes)
  console.log('Cost     : $' + r.costUsd.toFixed(6))
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
