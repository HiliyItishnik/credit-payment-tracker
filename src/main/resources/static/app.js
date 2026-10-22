'use strict';
const $ = (id) => document.getElementById(id);
const money = (value) => new Intl.NumberFormat('ru-RU', {style: 'currency', currency: 'RUB', maximumFractionDigits: 2}).format(value);
const date = (value) => value ? new Intl.DateTimeFormat('ru-RU', {day: 'numeric', month: 'short', year: 'numeric'}).format(new Date(value + 'T12:00:00')) : '—';
const state = {id: null, loan: null, forecast: null, generation: 0, busy: false, tab: 'schedule', attempt: null};
const fieldNames = {name: 'Название', principal: 'Сумма кредита', annualRate: 'Годовая ставка', monthlyPayment: 'Платёж в месяц', firstPaymentDate: 'Дата первого платежа', amount: 'Сумма платежа', requestId: 'Идентификатор запроса'};

async function api(path, options = {}) {
  let response;
  try { response = await fetch('/api/loans' + path, {headers: {'Content-Type': 'application/json'}, ...options}); }
  catch { throw new Error('Нет связи с сервером. Проверь, запущено ли приложение, и попробуй снова.'); }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    let message = error.detail || 'Не удалось выполнить запрос. Попробуй обновить страницу.';
    if (error.errors) message += ' Поля: ' + Object.keys(error.errors).map(key => fieldNames[key] || key).join(', ') + '.';
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json();
}

function showError(id, error) { $(id).textContent = error.message; $(id).hidden = false; }
function clearError(id) { $(id).hidden = true; $(id).textContent = ''; }
function setBusy(busy) {
  state.busy = busy;
  document.querySelectorAll('.mutating, .loan-nav, .open-create, #fill-example').forEach(el => { el.disabled = busy; });
}
function decimal(value, allowZero = false, fraction = 2) {
  const normalized = value.trim().replace(',', '.');
  const pattern = new RegExp('^\\d+(?:\\.\\d{1,' + fraction + '})?$');
  if (!pattern.test(normalized) || !Number.isFinite(Number(normalized)) || Number(normalized) > 1000000000 || (!allowZero && Number(normalized) <= 0)) {
    throw new Error('Введи сумму ' + (allowZero ? 'от 0' : 'больше 0') + ', не больше ' + fraction + ' знаков после запятой.');
  }
  return normalized;
}

async function refresh(preferredId = state.id) {
  const loans = await api('');
  const nav = $('loan-list');
  nav.replaceChildren();
  for (const loan of loans) {
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'loan-nav'; button.dataset.id = loan.id;
    const name = document.createElement('strong'); name.textContent = loan.name;
    const balance = document.createElement('small'); balance.textContent = Number(loan.balance) === 0 ? 'Погашен ✓' : money(loan.balance);
    button.append(name, balance); button.disabled = state.busy;
    button.addEventListener('click', () => selectLoan(loan.id));
    nav.append(button);
  }
  if (!loans.length) {
    $('loading').hidden = true; $('empty').hidden = false; $('workspace').hidden = true;
    return;
  }
  const id = loans.some(loan => loan.id === preferredId) ? preferredId : loans[0].id;
  await loadLoan(id);
}

async function loadLoan(id) {
  const generation = ++state.generation;
  state.id = id;
  $('workspace').hidden = true; $('empty').hidden = true; $('loading').hidden = false;
  const [loan, forecast] = await Promise.all([api('/' + id), api('/' + id + '/forecast')]);
  if (generation !== state.generation) return;
  state.loan = loan; state.forecast = forecast;
  $('extra-monthly').value = '0'; $('one-time-extra').value = '0';
  ['global-error', 'payment-error', 'forecast-error', 'history-error'].forEach(clearError);
  document.querySelectorAll('.loan-nav').forEach(button => {
    button.classList.toggle('active', button.dataset.id === id);
    if (button.dataset.id === id) button.setAttribute('aria-current', 'true');
    else button.removeAttribute('aria-current');
  });
  render(); $('loading').hidden = true; $('workspace').hidden = false;
}

async function selectLoan(id) {
  if (state.busy) return;
  setBusy(true);
  try { await loadLoan(id); } catch (error) { showError('global-error', error); $('loading').hidden = true; }
  finally { setBusy(false); }
}

function render() {
  const loan = state.loan;
  $('loan-name').textContent = loan.name;
  $('loan-terms').textContent = Number(loan.annualRate).toLocaleString('ru-RU', {maximumFractionDigits: 4}) + '% годовых · ' + money(loan.monthlyPayment) + ' в месяц';
  $('loan-status').textContent = loan.paidOff ? 'Погашен ✓' : 'Активный кредит';
  $('balance').textContent = money(loan.balance);
  $('principal-paid').textContent = money(loan.principalPaid);
  $('interest-paid').textContent = money(loan.interestPaid);
  ['balance', 'principal-paid', 'interest-paid'].forEach(id => $(id).classList.toggle('long-number', $(id).textContent.length > 14));
  const percent = Math.min(100, Number(loan.principalPaid) / Number(loan.principal) * 100);
  $('progress').style.width = percent + '%';
  $('progress-text').textContent = 'Погашено ' + percent.toLocaleString('ru-RU', {maximumFractionDigits: 1}) + '%';
  $('months-left').textContent = state.forecast.baseline.months + ' мес.';
  $('payoff-date').textContent = (loan.paidOff ? 'Закрыт ' : 'По графику — ') + date(state.forecast.baseline.payoffDate);
  $('payment-form').hidden = loan.paidOff; $('paid-off-message').hidden = !loan.paidOff;
  $('payment-period').textContent = loan.paidOff ? 'Цель достигнута' : 'Расчётная дата: ' + date(loan.nextPaymentDate);
  $('payment-amount').value = Number(loan.suggestedPayment).toFixed(2);
  $('payment-hint').textContent = 'Проценты за месяц: ' + money(loan.nextInterest) + '. Полное погашение: ' + money(loan.maximumPayment) + '.';
  $('history-count').textContent = loan.payments.length;
  $('undo-payment').hidden = loan.payments.length === 0;
  $('future-interest').textContent = 'Будущие проценты: ' + money(state.forecast.baseline.totalInterest);
  renderTable('schedule-table', state.forecast.baseline.rows, false);
  renderTable('history-table', loan.payments, true);
  renderForecast();
}

function renderTable(id, rows, history) {
  const container = $(id); container.replaceChildren();
  if (!rows.length) {
    const empty = document.createElement('p'); empty.className = 'table-empty';
    empty.textContent = history ? 'Пока нет платежей. Запиши первый — здесь появится история.' : 'Будущих платежей нет. Кредит погашен!';
    container.append(empty); return;
  }
  const wrap = document.createElement('div'); wrap.className = 'table-wrap'; wrap.tabIndex = 0;
  wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', history ? 'История платежей' : 'График платежей');
  const table = document.createElement('table');
  const head = table.createTHead().insertRow();
  ['№', 'Расчётная дата', 'Платёж', 'Проценты', 'Основной долг', 'Остаток'].forEach(label => {
    const th = document.createElement('th'); th.scope = 'col'; th.textContent = label; head.append(th);
  });
  const body = table.createTBody();
  rows.forEach(row => {
    const tr = body.insertRow();
    [row.number, date(row.date), money(history ? row.amount : row.payment), money(row.interest), money(row.principal), money(row.balance)]
      .forEach(value => { tr.insertCell().textContent = value; });
    if (history) tr.cells[1].title = 'Запись создана: ' + new Date(row.recordedAt).toLocaleString('ru-RU');
  });
  wrap.append(table); container.append(wrap);
}

function renderForecast() {
  const forecast = state.forecast;
  $('interest-saved').textContent = money(forecast.interestSaved);
  $('months-saved').textContent = forecast.monthsSaved + ' мес.';
  $('scenario-date').textContent = date(forecast.scenario.payoffDate);
  renderTable('scenario-table', forecast.scenario.rows, false);
  renderChart();
}

function renderChart() {
  const container = $('chart'); container.replaceChildren();
  const {baseline, scenario} = state.forecast;
  const hasScenario = Number(state.forecast.interestSaved) > 0 || state.forecast.monthsSaved > 0 ||
    JSON.stringify(baseline.rows) !== JSON.stringify(scenario.rows);
  $('scenario-legend').hidden = !hasScenario;
  if (!baseline.months) {
    const p = document.createElement('p'); p.className = 'subtle'; p.textContent = 'Остаток — 0 ₽. Можно выдохнуть.'; container.append(p); return;
  }
  const ns = 'http://www.w3.org/2000/svg';
  const element = (name, attrs, text) => {
    const el = document.createElementNS(ns, name);
    Object.entries(attrs).forEach(([key, value]) => el.setAttribute(key, value));
    if (text !== undefined) el.textContent = text;
    return el;
  };
  const svg = element('svg', {viewBox: '0 0 600 220', role: 'img', 'aria-label': 'Снижение остатка долга от текущей суммы до нуля. Точные значения доступны в таблицах.'});
  const maxBalance = Number(state.loan.balance), left = 73, right = 580, top = 14, bottom = 184;
  for (let i = 0; i <= 3; i++) {
    const y = top + (bottom - top) * i / 3;
    svg.append(element('line', {x1: left, x2: right, y1: y, y2: y, stroke: '#e8ecdf', 'stroke-dasharray': '3 5'}));
    const value = maxBalance * (3 - i) / 3;
    const label = new Intl.NumberFormat('ru-RU', {notation: 'compact', maximumFractionDigits: 1}).format(value);
    svg.append(element('text', {x: left - 12, y: y + 4, 'text-anchor': 'end', fill: '#8c9884', 'font-size': 10}, label));
  }
  const points = rows => [[left, top], ...rows.map((row, i) => [left + (right - left) * (i + 1) / baseline.months, bottom - Number(row.balance) / maxBalance * (bottom - top)])];
  const basePoints = points(baseline.rows);
  svg.append(element('polygon', {points: [...basePoints, [right, bottom], [left, bottom]].map(p => p.join(',')).join(' '), fill: '#f1f6e8'}));
  svg.append(element('polyline', {points: basePoints.map(p => p.join(',')).join(' '), fill: 'none', stroke: '#4c7348', 'stroke-width': 2.5, 'stroke-linejoin': 'round'}));
  if (hasScenario) svg.append(element('polyline', {points: points(scenario.rows).map(p => p.join(',')).join(' '), fill: 'none', stroke: '#d98b54', 'stroke-width': 2.5, 'stroke-linejoin': 'round'}));
  svg.append(element('text', {x: left, y: 211, fill: '#8c9884', 'font-size': 10}, 'Сейчас'));
  svg.append(element('text', {x: right, y: 211, 'text-anchor': 'end', fill: '#8c9884', 'font-size': 10}, baseline.months + ' мес.'));
  container.append(svg);
}

function activateTab(name) {
  state.tab = name;
  document.querySelectorAll('[role=tab]').forEach(button => {
    const selected = button.dataset.tab === name;
    button.setAttribute('aria-selected', selected); button.tabIndex = selected ? 0 : -1;
    $('panel-' + button.dataset.tab).hidden = !selected;
  });
}
document.querySelectorAll('[role=tab]').forEach((button, index, tabs) => {
  button.addEventListener('click', () => activateTab(button.dataset.tab));
  button.addEventListener('keydown', event => {
    const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : -1;
    if (next >= 0) { event.preventDefault(); activateTab(tabs[next].dataset.tab); tabs[next].focus(); }
  });
});

document.querySelectorAll('.open-create').forEach(button => button.addEventListener('click', () => {
  clearError('create-error'); $('create-dialog').showModal(); $('new-name').focus();
}));
$('close-dialog').addEventListener('click', () => { if (!state.busy) $('create-dialog').close(); });
$('create-dialog').addEventListener('cancel', event => { if (state.busy) event.preventDefault(); });
$('fill-example').addEventListener('click', () => {
  $('new-name').value = 'Кредит на ремонт'; $('new-principal').value = '300000';
  $('new-rate').value = '18'; $('new-monthly').value = '15000';
  const next = new Date(); next.setDate(1); next.setMonth(next.getMonth() + 1);
  $('new-date').value = next.getFullYear() + '-' + String(next.getMonth() + 1).padStart(2, '0') + '-01';
});

$('create-form').addEventListener('submit', async event => {
  event.preventDefault(); if (state.busy) return; clearError('create-error'); setBusy(true);
  try {
    const body = {name: $('new-name').value.trim(), principal: decimal($('new-principal').value), annualRate: decimal($('new-rate').value, true, 4), monthlyPayment: decimal($('new-monthly').value), firstPaymentDate: $('new-date').value};
    const loan = await api('', {method: 'POST', body: JSON.stringify(body)});
    $('create-dialog').close(); $('create-form').reset(); activateTab('schedule');
    try { await refresh(loan.id); } catch (error) { showError('global-error', new Error('Кредит создан. ' + error.message)); $('loading').hidden = true; }
  } catch (error) { showError('create-error', error); }
  finally { setBusy(false); }
});

$('payment-form').addEventListener('submit', async event => {
  event.preventDefault(); if (state.busy) return; clearError('payment-error'); setBusy(true);
  try {
    const id = state.id, amount = decimal($('payment-amount').value);
    // Retain a request ID after a network error, so a retry cannot add the same payment twice.
    if (!state.attempt || state.attempt.id !== id || state.attempt.amount !== amount) state.attempt = {id, amount, requestId: crypto.randomUUID()};
    await api('/' + id + '/payments', {method: 'POST', body: JSON.stringify({requestId: state.attempt.requestId, amount})});
    state.attempt = null;
    try { await refresh(id); } catch (error) { showError('global-error', new Error('Платёж сохранён. ' + error.message)); $('loading').hidden = true; }
  } catch (error) { showError('payment-error', error); }
  finally { setBusy(false); }
});

$('forecast-form').addEventListener('submit', async event => {
  event.preventDefault(); if (state.busy) return; clearError('forecast-error'); setBusy(true);
  try {
    const params = new URLSearchParams({extraMonthly: decimal($('extra-monthly').value, true), oneTimeExtra: decimal($('one-time-extra').value, true)});
    const generation = state.generation;
    const forecast = await api('/' + state.id + '/forecast?' + params);
    if (generation === state.generation) { state.forecast = forecast; renderForecast(); }
  } catch (error) { showError('forecast-error', error); }
  finally { setBusy(false); }
});

$('undo-payment').addEventListener('click', async () => {
  if (state.busy || !state.loan.payments.length) return;
  const last = state.loan.payments.at(-1), id = state.id;
  if (!confirm('Отменить последний платёж ' + money(last.amount) + ' за ' + date(last.date) + '? Остаток долга будет восстановлен.')) return;
  setBusy(true); clearError('history-error');
  try {
    await api('/' + id + '/payments/' + last.id, {method: 'DELETE'});
    state.attempt = null;
    try { await refresh(id); } catch (error) { showError('global-error', new Error('Платёж отменён. ' + error.message)); $('loading').hidden = true; }
  } catch (error) { showError('history-error', error); }
  finally { setBusy(false); }
});

async function start() {
  setBusy(true);
  try { await refresh(); } catch (error) { showError('global-error', error); $('loading').hidden = true; }
  finally { setBusy(false); }
}
start();
