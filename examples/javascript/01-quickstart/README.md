# 01 · Quickstart (JavaScript)

The smallest Gavio program, in **plain JavaScript** — no TypeScript, no build
step. Dev mode runs everything in-process (mock provider + stdout audit), so
there's **no API key and no network**.

```bash
npm install
node quickstart.mjs      # ESM
node quickstart.cjs      # CommonJS — require() works too
```

Gavio ships a **dual ESM + CJS build**, so you can use it either way:

```js
import { Gateway } from 'gavio'          // ESM
const { Gateway } = require('gavio')     // CommonJS
```

You'll see the email + IBAN redacted before the mock provider, restored in the
reply, and an audit line showing `pii=EMAIL,IBAN`.

Next: [02 · production-gateway](../02-production-gateway/) ·
[JavaScript guide](../../../docs/packages/javascript.md)
