const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const vm = require('node:vm');

function page(requestJson) {
  const elements = new Map();
  const document = {getElementById(id) {
    if (!elements.has(id)) elements.set(id, {
      elements: [], listeners: {}, checked: false, disabled: false,
      classList: {toggle() {}}, addEventListener(type, listener) { this.listeners[type] = listener; }
    });
    return elements.get(id);
  }};
  const context = vm.createContext({document, requestJson, sessionStorage: {removeItem() {}}, location: {href: ''}});
  vm.runInContext(readFileSync(join(__dirname, '../../main/resources/static/backup.js'), 'utf8'), context);
  vm.runInContext("selectedBackup = {formatVersion: 1}; currentChecksum = 'verified';", context);
  return {element: id => document.getElementById(id), context};
}

test('restoration requires explicit acknowledgement even if submit is triggered directly', async () => {
  let calls = 0;
  const browser = page(async () => { calls++; return {}; });
  await browser.element('backup-restore').listeners.submit({preventDefault() {}});
  assert.equal(calls, 0);
  browser.element('backup-acknowledge').checked = true;
  await browser.element('backup-restore').listeners.submit({preventDefault() {}});
  assert.equal(calls, 1);
  assert.equal(vm.runInContext('location.href', browser.context), '/');
});

test('a failed restoration invalidates the preview and prevents a blind retry', async () => {
  let calls = 0;
  const browser = page(async () => { calls++; throw new Error('Данные изменились'); });
  browser.element('backup-acknowledge').checked = true;
  await browser.element('backup-restore').listeners.submit({preventDefault() {}});
  assert.equal(browser.element('backup-restore').hidden, true);
  assert.equal(browser.element('backup-acknowledge').checked, false);
  assert.equal(browser.element('backup-restore-button').disabled, true);
  await browser.element('backup-restore').listeners.submit({preventDefault() {}});
  assert.equal(calls, 1);
});
