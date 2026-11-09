'use strict';

const $ = id => document.getElementById(id);
const currency = new Intl.NumberFormat('ru-RU', {style: 'currency', currency: 'RUB'});
const monthFormat = new Intl.DateTimeFormat('ru-RU', {month: 'long', year: 'numeric'});
const money = amount => currency.format(amount);
const monthName = month => monthFormat.format(new Date(month + '-01T12:00:00')).replace(/\s*г\.$/u, '');
const groups = ['incomes', 'expenses'];
let months = [];
let shown = 0;
let overview;
let openedMonth;
const now = new Date();
const currentMonth = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
const dayFormat = new Intl.DateTimeFormat('ru-RU', {day: 'numeric', month: 'long'});
const dayName = date => dayFormat.format(new Date(date + 'T12:00:00'));

function element(tag, content, className) {
  const node = document.createElement(tag);
  if (content) node.textContent = content;
  if (className) node.className = className;
  return node;
}

async function budgetApi(options, path = '') {
  const response = await fetch('/api/budget' + path, options);
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail || 'Не удалось загрузить или сохранить бюджет.');
  }
  return response.json();
}

function markEdited() {
  $('budget-status').textContent = 'Есть несохранённые изменения.';
  $('budget-result').hidden = true;
  $('budget-tools').hidden = true;
}

function addEntry(group, entry = {}, focus = false) {
  const row = element('div', '', 'budget-entry');
  const nameLabel = element('label', 'Название');
  const name = element('input');
  name.name = 'name';
  name.required = true;
  name.maxLength = 80;
  name.placeholder = group === 'incomes' ? 'Зарплата' : 'Например, аренда';
  name.value = entry.name ?? '';
  nameLabel.append(name);
  const amountLabel = element('label', 'В месяц, ₽');
  const amount = element('input');
  amount.name = 'amount';
  amount.inputMode = 'decimal';
  amount.required = true;
  amount.value = entry.amount ?? '';
  amountLabel.append(amount);
  const dayLabel = element('label', 'День');
  const day = element('input');
  day.type = 'number';
  day.name = 'day';
  day.min = '1';
  day.max = '31';
  day.step = '1';
  day.placeholder = '—';
  day.value = entry.day ?? '';
  dayLabel.append(day);
  const remove = element('button', '×', 'button danger budget-remove');
  remove.type = 'button';
  remove.setAttribute('aria-label', group === 'incomes' ? 'Убрать доход' : 'Убрать расход');
  remove.addEventListener('click', () => {
    row.remove();
    markEdited();
    $(group === 'incomes' ? 'add-income' : 'add-expense').focus();
  });
  row.append(nameLabel, amountLabel, dayLabel, remove);
  $(group).append(row);
  if (focus) name.focus();
}

function readEntries(group) {
  return [...$(group).children].map(row => {
    const name = row.querySelector('[name=name]').value.trim();
    const amount = row.querySelector('[name=amount]').value.trim().replace(',', '.');
    if (!name || !/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0 || Number(amount) > 1e9) {
      throw new Error('Укажи название и сумму от 0,01 до 1 млрд ₽ с точностью до копеек.');
    }
    const day = row.querySelector('[name=day]').value;
    return {name, amount, day: day ? Number(day) : null};
  });
}

function renderNextMonths() {
  for (const month of months.slice(shown, shown + 12)) {
    const detail = element('details', '', 'budget-month');
    detail.open = shown === 0 && month === months[0];
    const title = element('summary', monthName(month.month) + ' · ' + money(month.total));
    const summary = element('p', 'Плановые платежи: ' + money(month.minimum)
      + ' · Доплата: ' + money(month.total - month.minimum) + ' · Свободно: ' + money(month.remaining), 'subtle');
    const wrap = element('div', '', 'table-wrap');
    wrap.tabIndex = 0;
    wrap.setAttribute('role', 'region');
    wrap.setAttribute('aria-label', 'Платежи за ' + monthName(month.month));
    const table = element('table');
    const heading = table.createTHead().insertRow();
    for (const text of ['Долг', 'Платёж', 'Из них доплата', 'Проценты', 'Остаток долга']) {
      const th = element('th', text);
      th.scope = 'col';
      heading.append(th);
    }
    const body = table.createTBody();
    for (const payment of month.payments) {
      const row = body.insertRow();
      for (const value of [payment.name, money(payment.amount), money(payment.extra), money(payment.interest), money(payment.balance)]) {
        row.insertCell().textContent = value;
      }
    }
    wrap.append(table);
    detail.append(title, summary, wrap);
    $('budget-months').append(detail);
  }
  shown += 12;
  $('budget-more').hidden = shown >= months.length;
}

function renderPlan(plan) {
  $('total-income').textContent = money(plan.income);
  $('total-expenses').textContent = money(plan.expenses);
  $('total-available').textContent = money(plan.available);
  const status = $('plan-status');
  status.classList.toggle('error', Boolean(plan.shortfall) || plan.available < 0);
  if (plan.shortfall) {
    const {month, required, missing} = plan.shortfall;
    status.textContent = 'Месяц с дефицитом: ' + monthName(month) + '. Не хватит ' + money(missing)
      + '. На платежи нужно ' + money(required) + '. План показан до месяца с дефицитом.';
  } else if (plan.status === 'COMPLETE') {
    status.textContent = 'Погашение всех долгов: ' + monthName(plan.payoffMonth)
      + '. Проценты за весь план: ' + money(plan.interest) + '.';
  } else if (plan.status === 'NO_DEBTS') {
    status.textContent = plan.available < 0 ? 'Доходов не хватает на обязательные расходы.' : 'Активных долгов нет.';
  } else {
    status.textContent = 'Полный план не помещается в расчётный горизонт в 600 месяцев.';
  }
  months = plan.months;
  shown = 0;
  $('budget-months').replaceChildren();
  renderNextMonths();
  $('budget-result').hidden = false;
}

function tableInto(id, columns, rows) {
  const table = element('table');
  const head = table.createTHead().insertRow();
  for (const title of columns) {
    const cell = element('th', title);
    cell.scope = 'col';
    head.append(cell);
  }
  const body = table.createTBody();
  for (const values of rows) {
    const row = body.insertRow();
    for (const value of values) row.insertCell().textContent = value;
  }
  $(id).replaceChildren(table);
  $(id).hidden = false;
}

function describeForecast(plan) {
  if (plan.payoffMonth) return monthName(plan.payoffMonth);
  if (plan.shortfall) return 'Дефицит ' + money(plan.shortfall.missing) + ' в месяце ' + monthName(plan.shortfall.month);
  return plan.status === 'NO_DEBTS' ? 'Долгов нет' : 'За пределами расчёта';
}

function renderOverview(result) {
  overview = result;
  const strategies = [['Сначала дорогие', result.plan], ['Сначала маленькие', result.alternative]];
  tableInto('strategy-comparison', ['Стратегия', 'Погашение всех долгов', 'Всего процентов'],
    strategies.map(([name, plan]) => [name, describeForecast(plan), plan.payoffMonth ? money(plan.interest) : '—']));
  renderPlan(result[$('budget-strategy').value]);
  $('budget-tools').hidden = false;
  $('calendar-results').hidden = true;
  $('calendar-message').textContent = '';
  resetReview();
}

function amountInput(id) {
  const amount = $(id).value.trim().replace(',', '.');
  if (!/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) > 5e10) {
    throw new Error('Укажи сумму от 0 до 50 млрд ₽ с точностью до копеек.');
  }
  return amount;
}

function jsonPut(body) {
  return {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)};
}

function resetReview() {
  openedMonth = null;
  $('actual-form').hidden = true;
  $('actual-results').hidden = true;
  $('actual-message').textContent = '';
  $('actual-forecast').textContent = '';
}

function renderReview(review) {
  const {planned, actual, paid, baseline, adjusted} = review;
  openedMonth = review.month;
  $('actual-income').value = actual?.income ?? '';
  $('actual-expenses').value = actual?.expenses ?? '';
  $('actual-form').hidden = false;
  $('actual-message').textContent = monthName(review.month);
  const rows = [
    ['Доходы', planned.income, actual?.income],
    ['Расходы без долгов', planned.expenses, actual?.expenses],
    ['Платежи по долгам', planned.payments, paid],
    ['Остаток', planned.payments == null ? null : planned.income - planned.expenses - planned.payments,
      actual == null ? null : actual.income - actual.expenses - paid]
  ];
  const format = value => value == null ? '—' : money(value);
  tableInto('actual-results', ['Показатель', 'План', 'Факт', 'Разница'], rows.map(([name, plan, fact]) => [
    name, format(plan), format(fact), plan == null || fact == null ? '—' : money(fact - plan)
  ]));
  $('actual-forecast').textContent = adjusted
    ? 'По обычному бюджету: ' + describeForecast(baseline) + '. С учётом факта: ' + describeForecast(adjusted)
      + '. Расчёт предполагает, что введённые суммы — итог текущего месяца. Со следующего месяца действует обычный бюджет.'
    : '';
}

function setToolBusy(form, busy) {
  for (const control of form.elements) control.disabled = busy;
}

$('calendar-month').value = $('actual-month').value = currentMonth;
$('calendar-month').min = currentMonth;
$('actual-month').max = currentMonth;
$('actual-month').min = '1900-01';
$('calendar-month').max = '2200-12';
$('budget-strategy').addEventListener('change', event => renderPlan(overview[event.target.value]));

$('calendar-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const message = $('calendar-message');
  $('calendar-results').hidden = true;
  message.classList.remove('error');
  message.textContent = 'Рассчитываем календарь…';
  setToolBusy(form, true);
  try {
    const month = $('calendar-month').value;
    const query = new URLSearchParams({openingBalance: amountInput('calendar-opening')});
    const calendar = await budgetApi(undefined, '/calendar/' + month + '?' + query);
    const {rows, firstGap, undatedEntries, incompleteDebtSchedule} = calendar;
    const warnings = [];
    if (firstGap) warnings.push('К ' + dayName(firstGap.date) + ' не хватит ' + money(firstGap.missing) + '.');
    if (undatedEntries) warnings.push('Строк без даты: ' + undatedEntries + '. Укажи день, чтобы учесть их в календаре.');
    if (incompleteDebtSchedule) warnings.push('Месяц за пределами расчёта обязательных платежей.');
    message.classList.toggle('error', Boolean(firstGap));
    message.textContent = warnings.join(' ') || (rows.length ? 'По указанным датам денег хватает.' : 'На этот месяц событий нет.');
    if (rows.length) {
      const kinds = {INCOME: 'Доход', EXPENSE: 'Расход', PAYMENT: 'Платёж по графику', RECORDED_PAYMENT: 'Записанный платёж'};
      tableInto('calendar-results', ['Дата', 'Операция', 'Сумма', 'Остаток'], rows.map(({event, balance}) => [
        dayName(event.date), kinds[event.kind] + ' · ' + event.name,
        money(event.kind === 'INCOME' ? event.amount : -event.amount), money(balance)
      ]));
    }
  } catch (error) {
    message.classList.add('error');
    message.textContent = error.message;
  } finally {
    setToolBusy(form, false);
  }
});

$('actual-month').addEventListener('input', resetReview);
$('actual-month-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  resetReview();
  setToolBusy(form, true);
  try {
    renderReview(await budgetApi(undefined, '/months/' + $('actual-month').value));
  } catch (error) {
    $('actual-message').textContent = error.message;
  } finally {
    setToolBusy(form, false);
  }
});

$('actual-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (!openedMonth) return;
  const form = event.currentTarget;
  const picker = $('actual-month-form');
  setToolBusy(form, true);
  setToolBusy(picker, true);
  try {
    const body = {income: amountInput('actual-income'), expenses: amountInput('actual-expenses')};
    renderReview(await budgetApi(jsonPut(body), '/months/' + openedMonth));
    $('actual-message').textContent += ' · Факт сохранён';
  } catch (error) {
    $('actual-message').textContent = error.message;
  } finally {
    setToolBusy(form, false);
    setToolBusy(picker, false);
  }
});

function setBusy(busy) {
  for (const fieldset of $('budget-form').querySelectorAll('fieldset')) fieldset.disabled = busy;
  $('budget-save').disabled = busy;
}

function showError(error) {
  $('budget-error').textContent = error.message;
  $('budget-error').hidden = false;
}

for (const [id, group] of [['add-income', 'incomes'], ['add-expense', 'expenses']]) {
  $(id).addEventListener('click', () => {
    if ($(group).children.length >= 50) {
      showError(new Error('В каждом списке может быть до 50 строк.'));
      return;
    }
    addEntry(group, {}, true);
    markEdited();
  });
}
$('budget-form').addEventListener('input', markEdited);
$('budget-more').addEventListener('click', renderNextMonths);
$('budget-form').addEventListener('submit', async event => {
  event.preventDefault();
  $('budget-error').hidden = true;
  setBusy(true);
  try {
    const budget = {incomes: readEntries('incomes'), expenses: readEntries('expenses')};
    renderOverview(await budgetApi(jsonPut(budget)));
    $('budget-status').textContent = 'Бюджет сохранён.';
  } catch (error) {
    showError(error);
  } finally {
    setBusy(false);
  }
});

async function loadBudget() {
  try {
    const result = await budgetApi();
    const {budget} = result;
    for (const group of groups) for (const entry of budget[group]) addEntry(group, entry);
    $('budget-form').hidden = false;
    renderOverview(result);
  } catch (error) {
    showError(error);
  } finally {
    $('budget-loading').hidden = true;
  }
}

loadBudget();
