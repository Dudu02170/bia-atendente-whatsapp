// Runs the exported sender with an HTTP stub. No n8n runtime or external request.
const fs = require('node:fs');
const vm = require('node:vm');
const request = JSON.parse(fs.readFileSync(0, 'utf8'));
if (typeof request.code !== 'string') throw new Error('Sender code is required');
const context = vm.createContext({ fixtureJSON: JSON.stringify(request) },
  { codeGeneration: { strings: false, wasm: false } });
vm.runInContext(`
  const fixture = JSON.parse(fixtureJSON);
  delete globalThis.fixtureJSON;
  const $json = fixture.input;
  const $env = Object.freeze({ EVOLUTION_INSTANCE_NAME: 'qa-isolated', EVOLUTION_API_KEY: 'qa-fake-key', ANTHROPIC_API_KEY: 'qa-fake-anthropic-key' });
  const calls = [];
  const helpers = Object.freeze({
    async httpRequest(options) {
      calls.push(options);
      if (fixture.http.error) {
        const error = new Error(fixture.http.error);
        if (fixture.http.status) error.statusCode = fixture.http.status;
        throw error;
      }
      return fixture.http.response;
    }
  });
`, context, { timeout: 1000 });
(async () => {
  try {
    const result = await new vm.Script('(async function(){\n' + request.code + '\n}).call({ helpers })')
      .runInContext(context, { timeout: 1000 });
    context.resultJSON = JSON.stringify(result);
    process.stdout.write(vm.runInContext('JSON.stringify({ result: JSON.parse(resultJSON), calls })', context));
  } catch (error) {
    context.errorJSON = JSON.stringify({ message: error.message });
    process.stdout.write(vm.runInContext('JSON.stringify({ error: JSON.parse(errorJSON), calls })', context));
  }
})().catch(error => { process.stderr.write(error.message); process.exitCode = 1; });
