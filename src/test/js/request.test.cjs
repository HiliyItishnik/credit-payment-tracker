const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const vm = require('node:vm');

function request(fetch) {
  const context = vm.createContext({fetch, AbortController, setTimeout, clearTimeout});
  vm.runInContext(readFileSync(join(__dirname, '../../main/resources/static/request.js'), 'utf8'), context);
  return context.requestJson;
}

test('a hung mutation times out without automatically retrying the write', async () => {
  let calls = 0;
  const json = request((url, options) => new Promise((resolve, reject) => {
    calls++;
    options.signal.addEventListener('abort', () => reject(new Error('aborted')));
  }));
  await assert.rejects(json('/api/budget', {method: 'PUT', body: '{}'}, 10), /долго не отвечает/);
  assert.equal(calls, 1);
});

test('conflicts retain the HTTP status needed by budget recovery', async () => {
  const json = request(async () => ({ok: false, status: 409, json: async () => ({detail: 'Бюджет изменён'})}));
  await assert.rejects(json('/api/budget'), error => error.status === 409 && error.message === 'Бюджет изменён');
});

test('a successful no-content response does not attempt to parse JSON', async () => {
  const json = request(async () => ({ok: true, status: 204, json: () => { throw new Error('empty body'); }}));
  assert.equal(await json('/api/budget', {method: 'PUT'}), null);
});
