# Публикация проекта на GitHub

1. Создай на GitHub пустой репозиторий `credit-payment-tracker`. Для портфолио можно выбрать Public. Не добавляй на этом шаге README или `.gitignore`: они уже есть в проекте.
2. Открой Terminal в распакованной папке проекта.
3. Замени `YOUR_USERNAME` своим GitHub-именем:

```bash
git init -b main
git add .
git commit -m "Build credit payment tracker with Spring Boot and PostgreSQL"
git remote add origin https://github.com/YOUR_USERNAME/credit-payment-tracker.git
git push -u origin main
```

Если Git просит имя и email автора, задай их для этого репозитория и повтори commit:

```bash
git config user.name "Твоё имя"
git config user.email "Твой email или GitHub noreply email"
```

Для push используй настроенную авторизацию GitHub в IntelliJ IDEA, Git Credential Manager или SSH. Пароли и токены не вставляй в исходники, README или URL репозитория.

## Оформление

Описание репозитория:

> Java loan payment tracker with payment history, amortization schedules and early repayment forecasts. Spring Boot, PostgreSQL, Flyway and automated tests.

Topics: `java`, `spring-boot`, `postgresql`, `spring-data-jpa`, `flyway`, `finance`, `portfolio-project`.

После push открой **Actions** и дождись результата workflow **Java and PostgreSQL checks**. Он создаёт PostgreSQL 17, применяет миграцию и запускает оба уровня тестов. Успех локальной unit-сборки не заменяет этот прогон.

Перед публикацией просмотри `git status`: `target`, `.idea` и `.env` исключены. Используй только придуманные примеры кредитов. Данные локального Docker volume не входят в Git.

Открой проект в браузере, создай демонстрационный кредит и при желании самостоятельно добавь снимок интерфейса в README. Для публичного размещения самого приложения потребуется авторизация; текущая версия предназначена для локального запуска.

Лицензия не выбрана автоматически. Если хочешь разрешить другим использовать код на определённых условиях, добавь подходящий LICENSE осознанно.
