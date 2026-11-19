// Executes only the exported synchronous input-filter Code node with a minimal input adapter.
const fs = require('node:fs');
const vm = require('node:vm');
const request = JSON.parse(fs.readFileSync(0, 'utf8'));
if (typeof request.code !== 'string') throw new Error('Filter code is required');
const context = vm.createContext(
  { payloadJSON: JSON.stringify(request.input) },
  { codeGeneration: { strings: false, wasm: false } }
);
vm.runInContext(`
  const fixture = JSON.parse(payloadJSON);
  delete globalThis.payloadJSON;
  const $input = Object.freeze({ first: () => ({ json: fixture }) });
  const console = Object.freeze({ log() {} });
`, context, { timeout: 1000 });
const result = new vm.Script('(function(){\n' + request.code + '\n})()', {
  filename: 'exported-input-filter.js'
}).runInContext(context, { timeout: 1000 });
if (!Array.isArray(result)) throw new Error('Filter must return an array');
process.stdout.write(JSON.stringify(result));
