# Логирование и Наблюдаемость (Observability)

Этот документ описывает стандарт логирования в системе.
Ключевой принцип: структурированные логи в формате map.
Имплементация шины событий доступна в [GitHub-репозитории](https://github.com/algebrain/lcmm-event-bus).

## 1. Стандартный интерфейс логгера

Логгер — функция `(fn [level data])`:
1. `level` — keyword (`:info`, `:warn`, `:error`, `:debug`, `:trace`);
2. `data` — map с контекстом события.

Пример:

```clojure
(require '[clojure.tools.logging :as log])

(defn make-app-logger []
  (fn [level data]
    (case level
      :info  (log/info data)
      :warn  (log/warn data)
      :error (log/error data)
      :debug (log/debug data)
      :trace (log/trace data)
      (log/info data))))
```

## 2. Интеграция логгера

1. Компоненты получают logger как зависимость.
2. Для `lcmm-observe` logger передается через `make-registry :logger`.

## 3. Структура лог-сообщений

Обязательные поля:
1. `:component`
2. `:event`

Рекомендуемые поля:
1. `:exception`
2. `:metric-id`
3. `:labels`
4. `:reason`
5. `:path`

## 4. Связка с `lcmm-observe`

Граница ответственности:
1. метрики — агрегаты;
2. логи — контекст и причинность;
3. `correlation-id` остается в логах, не в labels метрик.

Служебные события `lcmm-observe`:
1. `:dropped-sample`
Минимальный контекст: `:component :lcmm-observe`, `:metric-id`, `:reason`.
2. `:series-limit-hit`
Минимальный контекст: `:component :lcmm-observe`, `:metric-id`, `:label-key`, `:max-series-per-metric`.

См. полный API-контракт и операционные сценарии в [`./OBSERVABILITY.md`](./OBSERVABILITY.md).
