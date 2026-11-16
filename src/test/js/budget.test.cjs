const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const vm = require('node:vm');

function browser(fetch) {
  const elements = new Map();
  const storage = new Map();
  const document = {getElementById(id) {
    if (!elements.has(id)) elements.set(id, {
      hidden: true, value: '0', textContent: '', children: [], listeners: {},
      addEventListener(type, handler) { this.listeners[type] = handler; },
      querySelectorAll() { return []; },
      querySelector() { return {focus() {}}; }
    });
    return elements.get(id);
  }};
  const sessionStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  };
  const context = vm.createContext({document, sessionStorage, fetch, Intl, Date, URLSearchParams,
    AbortController, setTimeout, clearTimeout});
  const root = join(__dirname, '../../main/resources/static');
  vm.runInContext(readFileSync(join(root, 'request.js'), 'utf8'), context);
  vm.runInContext(readFileSync(join(root, 'budget.js'), 'utf8').replace(/loadBudget\(\);\s*$/, ''), context);
  return {context, storage, element: id => document.getElementById(id)};
}

test('calendar filters keep the complete calendar balances and exclude recorded payments', () => {
  const page = browser();
  const rows = [
    {event: {name: 'Past', date: '2026-11-01', kind: 'EXPENSE'}, balance: 900, completed: false},
    {event: {name: 'Paid', date: '2026-11-20', kind: 'RECORDED_PAYMENT'}, balance: 800, completed: false},
    {event: {name: 'Received', date: '2026-11-20', kind: 'INCOME'}, balance: 1800, completed: true},
    {event: {name: 'Rent', date: '2026-11-21', kind: 'EXPENSE'}, balance: 1200, completed: false}
  ];
  const pending = page.context.visibleCalendarRows(rows, 'pending', '2026-11-16');
  assert.deepEqual(Array.from(pending, row => row.event.name), ['Past', 'Rent']);
  const upcoming = page.context.visibleCalendarRows(rows, 'upcoming', '2026-11-16');
  assert.deepEqual(Array.from(upcoming, row => row.event.name), ['Rent']);
  assert.equal(upcoming[0].balance, 1200);
  assert.equal(rows.length, 4);
});

test('a conflicting budget survives reload and can be restored against the latest version', async () => {
  const requests = [];
  const saved = {version: 2, incomes: [], expenses: [], oneOffs: [], reserve: 200, openingReserve: 0};
  const page = browser(async (url, options) => {
    if (options.method === 'PUT') {
      requests.push(JSON.parse(options.body));
      if (requests.length === 1) return {ok: false, status: 409, json: async () => ({detail: 'Конфликт'})};
    }
    return {ok: true, status: 200, json: async () => ({budget: saved})};
  });
  vm.runInContext(`
    overview = {budget: {version: 1, incomes: [], expenses: [], oneOffs: [], reserve: 0, openingReserve: 0}};
    budgetVersion = 1;
    readEntries = group => overview.budget[group];
    tableInto = () => {};
    renderOverview = result => { overview = result; budgetVersion = result.budget.version; };
    renderBudget = result => {
      renderOverview(result);
      document.getElementById('budget-reserve').value = String(result.budget.reserve);
      document.getElementById('budget-opening-reserve').value = String(result.budget.openingReserve);
    };
  `, page.context);
  page.element('budget-reserve').value = '500';
  const submit = page.element('budget-form').listeners.submit;
  await submit({preventDefault() {}});
  assert.equal(page.element('budget-recovery').hidden, false);
  assert.equal(JSON.parse(page.storage.get('credit-tracker-budget-draft')).reserve, '500');
  await page.element('budget-reload').listeners.click();
  assert.equal(page.element('budget-reserve').value, '200');
  page.element('budget-restore-draft').listeners.click();
  assert.equal(page.element('budget-reserve').value, '500');
  await submit({preventDefault() {}});
  assert.equal(requests[1].version, 2);
  assert.equal(requests[1].reserve, '500');
  assert.equal(page.storage.has('credit-tracker-budget-draft'), false);
});
