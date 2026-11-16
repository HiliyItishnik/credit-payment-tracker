'use strict';

const $ = id => document.getElementById(id);
let selectedBackup = null;
let currentChecksum = null;
let busy = false;

function setBusy(value) {
  busy = value;
  for (const control of $('backup-preview').elements) control.disabled = busy;
  $('backup-acknowledge').disabled = busy;
  $('backup-restore-button').disabled = busy || !currentChecksum || !$('backup-acknowledge').checked;
}

function resetPreview() {
  currentChecksum = null;
  $('backup-summary').hidden = true;
  $('backup-restore').hidden = true;
  $('backup-acknowledge').checked = false;
  setBusy(false);
}

function showMessage(text, error = false) {
  $('backup-message').textContent = text;
  $('backup-message').classList.toggle('error', error);
}

$('backup-file').addEventListener('change', () => {
  selectedBackup = null;
  resetPreview();
  showMessage('');
});
$('backup-acknowledge').addEventListener('change', () => setBusy(busy));

$('backup-preview').addEventListener('submit', async event => {
  event.preventDefault();
  resetPreview();
  setBusy(true);
  showMessage('Проверяем файл и сравниваем с текущими данными…');
  try {
    const file = $('backup-file').files[0];
    if (!file || file.size > 25 * 1024 * 1024) throw new Error('Выбери файл резервной копии размером до 25 МБ.');
    try { selectedBackup = JSON.parse(await file.text()); }
    catch { throw new Error('Не удалось прочитать файл резервной копии.'); }
    const preview = await requestJson('/api/backup/preview', {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(selectedBackup)
    }, 60000);
    currentChecksum = preview.currentChecksum;
    const labels = {loans: 'Долги', payments: 'Платежи', monthly_budget: 'Бюджет', budget_actuals: 'План и факт',
      budget_calendar_marks: 'Отметки', budget_calendar_dates: 'Переносы дат', budget_calendar_amounts: 'Частичные суммы'};
    const table = document.createElement('table');
    const heading = table.createTHead().insertRow();
    for (const title of ['Данные', 'Сейчас', 'После восстановления']) {
      const cell = document.createElement('th');
      cell.scope = 'col';
      cell.textContent = title;
      heading.append(cell);
    }
    const body = table.createTBody();
    for (const [key, label] of Object.entries(labels)) {
      const row = body.insertRow();
      for (const value of [label, preview.current[key], preview.imported[key]]) row.insertCell().textContent = value;
    }
    $('backup-summary').replaceChildren(table);
    $('backup-summary').hidden = false;
    $('backup-restore').hidden = false;
    showMessage('Файл проверен. Копия создана ' + new Intl.DateTimeFormat('ru-RU', {
      dateStyle: 'medium', timeStyle: 'short'
    }).format(new Date(preview.createdAt)) + '. Проверь состав данных перед заменой.');
  } catch (error) {
    showMessage(error.message, true);
  } finally {
    setBusy(false);
  }
});

$('backup-restore').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || !currentChecksum || !$('backup-acknowledge').checked) return;
  setBusy(true);
  showMessage('Восстанавливаем копию…');
  try {
    await requestJson('/api/backup/restore', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({backup: selectedBackup, currentChecksum})}, 60000);
    try { sessionStorage.removeItem('credit-tracker-budget-draft'); } catch {}
    location.href = '/';
  } catch (error) {
    resetPreview();
    showMessage(error.message + ' Повтори проверку файла перед новой попыткой.', true);
  }
});
