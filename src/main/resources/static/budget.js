'use strict';

const $ = id => document.getElementById(id);
const currency = new Intl.NumberFormat('ru-RU', {style: 'currency', currency: 'RUB'});
const monthFormat = new Intl.DateTimeFormat('ru-RU', {month: 'long', year: 'numeric'});
const money = amount => currency.format(amount);
const monthName = month => monthFormat.format(new Date(month + '-01T12:00:00')).replace(/\s*г\.$/u, '');
const groups = ['incomes', 'expenses'];
let months = [];
let shown = 0;

function element(tag, content, className) {
  const node = document.createElement(tag);
  if (content) node.textContent = content;
  if (className) node.className = className;
  return node;
}

async function budgetApi(options) {
  const response = await fetch('/api/budget', options);
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail || 'Не удалось загрузить или сохранить бюджет.');
  }
  return response.json();
}

function markEdited() {
  $('budget-status').textContent = 'Есть несохранённые изменения.';
  $('budget-result').hidden = true;
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
  const remove = element('button', '×', 'button danger budget-remove');
  remove.type = 'button';
  remove.setAttribute('aria-label', group === 'incomes' ? 'Убрать доход' : 'Убрать расход');
  remove.addEventListener('click', () => {
    row.remove();
    markEdited();
    $(group === 'incomes' ? 'add-income' : 'add-expense').focus();
  });
  row.append(nameLabel, amountLabel, remove);
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
    return {name, amount};
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
    const result = await budgetApi({
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(budget)
    });
    renderPlan(result.plan);
    $('budget-status').textContent = 'Бюджет сохранён.';
  } catch (error) {
    showError(error);
  } finally {
    setBusy(false);
  }
});

async function loadBudget() {
  try {
    const {budget, plan} = await budgetApi();
    for (const group of groups) for (const entry of budget[group]) addEntry(group, entry);
    $('budget-form').hidden = false;
    if (budget.incomes.length || budget.expenses.length) renderPlan(plan);
  } catch (error) {
    showError(error);
  } finally {
    $('budget-loading').hidden = true;
  }
}

loadBudget();
