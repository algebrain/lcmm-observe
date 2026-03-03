# lcmm-observe

Минимальная библиотека наблюдаемости для проектов LCMM.

## Структура

1. `src/` — исходный код библиотеки.
2. `test/` — тесты (Kaocha).
3. `docs/` — документация по API и интеграции.

## Документация

Порядок чтения:
1. [API Reference и интеграция `lcmm-observe`](./docs/OBSERVABILITY.md)
2. [Логирование и связка с observability](./docs/LOGGING.md)
3. [Архитектурные принципы](./docs/ARCH.md)
4. [API шины событий](./docs/BUS.md)
5. [API роутера](./docs/ROUTER.md)
6. [Модули системы](./docs/MODULE.md)

## Тестирование

1. `bb test.bb`
2. `clj -M:test`
