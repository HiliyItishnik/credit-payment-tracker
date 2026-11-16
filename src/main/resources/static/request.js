'use strict';

async function requestJson(url, options = {}, timeoutMs = 15000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {...options, signal: controller.signal});
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}));
      const error = new Error(problem.detail || 'Не удалось выполнить запрос. Попробуй обновить данные.');
      error.status = response.status;
      error.errors = problem.errors;
      throw error;
    }
    return response.status === 204 ? null : await response.json();
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error('Сервер долго не отвечает. Обнови данные перед повторной отправкой.');
    }
    if (error.status) throw error;
    throw new Error('Нет связи с сервером. Проверь, запущено ли приложение, и попробуй снова.');
  } finally {
    clearTimeout(timer);
  }
}
