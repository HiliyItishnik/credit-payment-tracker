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

test('debt details stay visible when the recorded payment leaves no valid forecast', async () => {
  const loan = {id: 'micro-1', debtType: 'MICROLOAN'};
  const page = browser(async url => url.endsWith('/forecast')
    ? {ok: false, status: 422, json: async () => ({detail: 'Платёж меньше процентов'})}
    : {ok: true, status: 200, json: async () => loan});
  await vm.runInContext("render = () => {}; selectLoan('micro-1')", page.context);
  assert.equal(vm.runInContext('state.loan.id', page.context), loan.id);
  assert.equal(page.element('workspace').hidden, false);
  assert.equal(page.element('forecast-submit').disabled, true);
});

for (const type of ['LOAN', 'CREDIT_CARD', 'MICROLOAN']) {
  test('creation form sends only relevant fields for ' + type, async () => {
    let body;
    const page = browser(async (url, options) => {
      body = JSON.parse(options.body);
      throw new Error('Stop after inspecting request');
    });
    page.element('new-type').value = type;
    page.element('new-type').listeners.change();
    assert.equal(page.element('new-limit').required, type === 'CREDIT_CARD');
    assert.equal(page.element('new-limit').disabled, type !== 'CREDIT_CARD');
    assert.equal(page.element('new-start').required, type === 'MICROLOAN');
    assert.equal(page.element('new-start').disabled, type !== 'MICROLOAN');
    for (const [id, value] of Object.entries({'new-name': 'Test debt', 'new-principal': '10000',
      'new-rate': '0,8', 'new-monthly': '12400', 'new-date': '2026-02-01',
      'new-start': '2026-01-02', 'new-limit': '50000'})) page.element(id).value = value;
    await page.element('create-form').listeners.submit({preventDefault() {}});
    assert.equal(body.debtType, type);
    assert.equal(body.principal, '10000.00');
    assert.equal(body.dailyRate, type === 'MICROLOAN' ? '0.8000' : undefined);
    assert.equal(body.annualRate, type === 'MICROLOAN' ? undefined : '0.8000');
    assert.equal(body.creditLimit, type === 'CREDIT_CARD' ? '50000.00' : undefined);
    assert.equal(body.interestStartDate, type === 'MICROLOAN' ? '2026-01-02' : undefined);
  });
}
