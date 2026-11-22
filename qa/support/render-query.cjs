// Minimal adapter for exported SQL expressions using fictitious $json and named node fixtures.
// Does not run n8n nodes, scheduling, item linking or network clients.
const fs = require('node:fs');
const vm = require('node:vm');
const request = JSON.parse(fs.readFileSync(0, 'utf8'));
if (typeof request.query !== 'string') throw new Error('Query is required');
const context = vm.createContext({ payloadJSON: JSON.stringify(request.input), nodesJSON: JSON.stringify(request.nodes || {}) },
  { codeGeneration: { strings: false, wasm: false } });
vm.runInContext(`
  const $json = JSON.parse(payloadJSON);
  const nodes = JSON.parse(nodesJSON);
  delete globalThis.payloadJSON; delete globalThis.nodesJSON;
  const $ = name => {
    if (!Object.prototype.hasOwnProperty.call(nodes, name)) throw new Error('Missing node fixture: ' + name);
    return Object.freeze({ first: () => ({ json: nodes[name] }) });
  };
`,
  context, { timeout: 1000 });
const query = request.query.replace(/\{\{([\s\S]*?)\}\}/g, (_, expression) =>
  String(new vm.Script('(' + expression + ')').runInContext(context, { timeout: 1000 })));
process.stdout.write(query);
