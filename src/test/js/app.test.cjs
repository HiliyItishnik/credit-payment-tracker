const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

function browser(fetch) {
  const elements = new Map();
  const document = {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, {
        hidden: false, textContent: '', value: '', listeners: {},
        addEventListener(type, handler) { this.listeners[type] = handler; }
      });
      return elements.get(id);
    },
    querySelectorAll() { return []; }
  };
  let requests = 0;
  const context = vm.createContext({document, fetch, Intl, URLSearchParams,
    crypto: {randomUUID: () => 'request-' + ++requests}});
  const source = readFileSync(require('node:path').join(__dirname, '../../main/resources/static/app.js'), 'utf8');
  // Initialize handlers without the automatic initial HTTP request.
  vm.runInContext(source.replace(/start\(\);\s*$/, ''), context);
  return {context, element: id => document.getElementById(id)};
}

test('retrying the same payment with different formatting reuses the request ID', async () => {
  const bodies = [];
  const page = browser(async (url, options) => {
    bodies.push(JSON.parse(options.body));
    throw new Error('Connection lost after sending payment');
  });
  vm.runInContext("state.id = 'loan-1'", page.context);
  const submit = page.element('payment-form').listeners.submit;
  page.element('payment-amount').value = '10000';
  await submit({preventDefault() {}});
  page.element('payment-amount').value = '010000,00';
  await submit({preventDefault() {}});
  assert.equal(bodies.length, 2);
  assert.equal(bodies[0].requestId, bodies[1].requestId);
});

test('failed refresh after a saved payment hides the stale payment form', async () => {
  let calls = 0;
  const page = browser(async () => {
    if (++calls === 1) return {ok: true, status: 201, json: async () => ({})};
    throw new Error('Connection lost while refreshing');
  });
  vm.runInContext("state.id = 'loan-1'", page.context);
  page.element('payment-amount').value = '10000';
  await page.element('payment-form').listeners.submit({preventDefault() {}});
  assert.equal(calls, 2);
  assert.equal(page.element('workspace').hidden, true);
  assert.match(page.element('global-error').textContent, /Платёж сохранён/);
});
