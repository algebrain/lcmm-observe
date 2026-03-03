# Наблюдаемость в `lcmm-observe`

Документ построен по принципу `interface-first`:
1. сначала канонический API-контракт;
2. затем рекомендуемые конфигурации;
3. затем quickstart, recipes и troubleshooting.

Если пример противоречит контракту ниже, верным считается API-контракт.

Официальный репозиторий библиотеки: https://github.com/algebrain/lcmm-observe

Практические рекомендации по применению:
1. Для уровня приложения: [OBSERVE_APP.md](./OBSERVE_APP.md).
2. Для уровня модулей: [OBSERVE_MODULE.md](./OBSERVE_MODULE.md).

## 1. API Reference (канонический контракт)

## 1.1 `make-registry`

Назначение: создать реестр метрик и политики поведения библиотеки.

Сигнатура:
```clojure
(obs/make-registry & {:keys [logger
                             strict-labels?
                             max-series-per-metric
                             on-series-limit
                             allowed-label-values
                             render-cache-ttl-ms
                             storage-mode
                             on-invalid-number]})
```

Аргументы:
1. `:logger` (`(fn [level data])`, optional)
2. `:strict-labels?` (`boolean`, default `false`)
3. `:max-series-per-metric` (`pos-int` или `nil`, default `5000`)
4. `:on-series-limit` (`:drop-and-log` | `:throw`, default `:drop-and-log`)
5. `:allowed-label-values` (`map`, default `{}`)
Формат: `{label-key #{"allowed-value" ...}}`.
Сравнение выполняется по строковому значению label.
6. `:render-cache-ttl-ms` (`int`, default `0`)
`0` означает отключенный кэш рендера.
7. `:storage-mode` (`:single-atom` | `:per-metric-atom`, default `:single-atom`)
8. `:on-invalid-number` (`:throw` | `:drop-and-log`, default `:throw`)

Возвращаемое значение:
1. Карта реестра, которую нужно передавать во все API-функции библиотеки.

Ошибки:
1. Unsupported policy/mode.
2. Невалидный `:max-series-per-metric`.

Пример:
```clojure
(def registry
  (obs/make-registry
    :strict-labels? false
    :max-series-per-metric 5000
    :on-series-limit :drop-and-log
    :render-cache-ttl-ms 1000))
```

## 1.2 `counter!`

Назначение: зарегистрировать (или вернуть) counter-метрику.

Сигнатура:
```clojure
(obs/counter! registry metric-id opts)
```

Аргументы:
1. `registry` (обязательный)
2. `metric-id` (`keyword`, обязательный)
3. `opts` (`map` или `nil`, обязательный аргумент по позиции)
Поддерживаемые keys: `:help`, `:labels`, `:name`.

Возвращаемое значение:
1. Handle метрики (используется в `inc!`).

Ошибки:
1. `metric-id` не keyword.
2. labels не keywords.
3. конфликт регистрации с другим типом/контрактом.

Пример:
```clojure
(def req-total
  (obs/counter! registry :http/server-requests-total
                {:help "Total HTTP requests"
                 :labels [:module :method :route :status_class]}))
```

## 1.3 `gauge!`

Назначение: зарегистрировать (или вернуть) gauge-метрику.

Сигнатура:
```clojure
(obs/gauge! registry metric-id opts)
```

Аргументы/ошибки: аналогично `counter!`.
Возвращаемый handle используется в `set!`.

Пример:
```clojure
(def queue-depth
  (obs/gauge! registry :event-bus/queue-depth {:labels [:module]}))
```

## 1.4 `histogram!`

Назначение: зарегистрировать (или вернуть) histogram-метрику.

Сигнатура:
```clojure
(obs/histogram! registry metric-id opts)
```

Аргументы:
1. `registry`
2. `metric-id` (`keyword`)
3. `opts` (`map` или `nil`): `:help`, `:labels`, `:name`, `:buckets`

Требования к `:buckets`:
1. непустой vector;
2. только числа;
3. строго возрастающая последовательность.

Возвращаемое значение:
1. Handle метрики (используется в `observe!`, `with-timing`).

Пример:
```clojure
(def req-latency
  (obs/histogram! registry :http/server-request-latency-ms
                  {:labels [:module :method :route]
                   :buckets [5.0 10.0 25.0 50.0 100.0 250.0 500.0 1000.0]}))
```

## 1.5 `inc!`

Назначение: увеличить counter.

Сигнатуры:
```clojure
(obs/inc! metric)
(obs/inc! metric n labels)
```

Аргументы:
1. `metric` (counter-handle)
2. `n` (`number`, default `1.0`, должен быть `>= 0`)
3. `labels` (`map`, должен совпадать со спецификацией `:labels` метрики)

Возвращаемое значение:
1. Тот же `metric` handle.

Ошибки:
1. handle не counter.
2. `n < 0`.
3. labels mismatch в strict-режиме.
4. series-limit при `:on-series-limit :throw`.
5. non-finite `n` при `:on-invalid-number :throw`.

Пример:
```clojure
(obs/inc! req-total 1.0
          {:module "gateway"
           :method "get"
           :route "/health"
           :status_class "2xx"})
```

## 1.6 `set!`

Назначение: установить значение gauge.

Сигнатура:
```clojure
(obs/set! metric value labels)
```

Аргументы:
1. `metric` (gauge-handle)
2. `value` (конечное число)
3. `labels` (map по контракту метрики)

Возвращаемое значение:
1. Тот же handle.

Ошибки: аналогично `inc!` (с учетом типа операции `set`).

Пример:
```clojure
(obs/set! queue-depth 5.0 {:module "billing"})
```

## 1.7 `observe!`

Назначение: добавить наблюдение в histogram.

Сигнатура:
```clojure
(obs/observe! metric value labels)
```

Аргументы:
1. `metric` (histogram-handle)
2. `value` (конечное число)
3. `labels` (map по контракту метрики)

Возвращаемое значение:
1. Тот же handle.

Ошибки: аналогично `inc!` (с учетом типа операции `observe`).

Пример:
```clojure
(obs/observe! req-latency 12.4
              {:module "gateway" :method "get" :route "/health"})
```

## 1.8 `with-timing`

Назначение: измерить длительность выполнения блока и записать в histogram (мс). Это macro.

Сигнатура:
```clojure
(obs/with-timing histogram labels & body)
```

Аргументы:
1. `histogram` (histogram-handle)
2. `labels` (map)
3. `body` (код)

Возвращаемое значение:
1. Результат `body`.

Побочный эффект:
1. В `finally` вызывает `observe!` с latency в миллисекундах.

Пример:
```clojure
(obs/with-timing req-latency {:module "gateway" :method "get" :route "/health"}
  (Thread/sleep 7)
  :ok)
```

## 1.9 `render-prometheus`

Назначение: отрендерить реестр в Prometheus text exposition format.

Сигнатура:
```clojure
(obs/render-prometheus registry)
```

Аргументы:
1. `registry`

Возвращаемое значение:
1. `string` с метриками.

Особенности:
1. учитывает `:render-cache-ttl-ms`;
2. всегда включает служебные метрики:
   1. `lcmm_observe_dropped_samples_total`
   2. `lcmm_observe_series_limit_hits_total`

Пример:
```clojure
(def payload (obs/render-prometheus registry))
```

## 1.10 `metrics-handler`

Назначение: создать Ring-handler для экспорта `/metrics`.

Сигнатура:
```clojure
(obs/metrics-handler registry)
```

Возвращаемое значение:
1. `(fn [request] response-map)`
2. response:
   1. `:status 200`
   2. `:headers {"content-type" "text/plain; version=0.0.4; charset=utf-8"}`
   3. `:body` = `render-prometheus`

Пример:
```clojure
(def metrics (obs/metrics-handler registry))
(metrics {:uri "/metrics"})
```

## 1.11 `wrap-observe-http`

Namespace: `lcmm.observe.http`

Назначение: обернуть Ring-handler и автоматически писать HTTP-метрики.

Сигнатура:
```clojure
(observe.http/wrap-observe-http handler {:keys [registry module route-fn]})
```

Аргументы:
1. `handler` (Ring handler, обязательный)
2. `:registry` (обязательный)
3. `:module` (обязательный, обычно `keyword`; также допускается строка)
4. `:route-fn` (optional, `(fn [req] route-template)`)

Поведение:
1. method: `keyword -> name`, `string -> string`, иначе `"unknown"`;
2. route: `route-fn`, иначе `reitit template`, иначе `"unknown"`;
3. exception в handler учитывается как `5xx`, исключение пробрасывается дальше.

Метрики:
1. `http_server_requests_total`
2. `http_server_request_errors_total`
3. `http_server_request_latency_ms`

Ошибки:
1. отсутствует `:registry` или `:module`.

Пример:
```clojure
(def app
  (observe.http/wrap-observe-http
   my-handler
   {:registry registry
    :module :gateway
    :route-fn (fn [req] (get-in req [:reitit.core/match :template]))}))
```

## 1.12 `wrap-bus-handler`

Namespace: `lcmm.observe.bus`

Назначение: обернуть event-bus handler и писать latency/failure метрики.

Сигнатура:
```clojure
(observe.bus/wrap-bus-handler
  handler
  {:keys [registry module handler-name event-type-fn]})
```

Аргументы:
1. `handler` (обязательный)
2. `:registry` (обязательный)
3. `:module` (обязательный, обычно `keyword`; также допускается строка)
4. `:handler-name` (обязательный)
5. `:event-type-fn` (optional, `(fn [envelope] event-type)`)

Поведение:
1. latency пишется в `finally`;
2. при exception увеличивается failures counter, исключение пробрасывается;
3. event-type keyword нормализуется в строку без ведущего `:`.

Метрики:
1. `event_bus_handler_failures_total`
2. `event_bus_handler_latency_ms`

Ошибки:
1. отсутствует `:registry`, `:module`, `:handler-name`.

Пример:
```clojure
(def wrapped
  (observe.bus/wrap-bus-handler
   my-bus-handler
   {:registry registry
    :module :billing
    :handler-name "emit-invoice"}))
```

## 1.13 `set-queue-depth!`

Namespace: `lcmm.observe.bus`

Назначение: обновить gauge глубины очереди bus.

Сигнатура:
```clojure
(observe.bus/set-queue-depth! registry {:keys [module]} depth)
```

Аргументы:
1. `registry` (обязательный)
2. `module` (обязательный, обычно `keyword`; также допускается строка)
3. `depth` (число)

Возвращаемое значение:
1. Результат `obs/set!` (тот же handle gauge).

Ошибки:
1. отсутствует `registry`/`module`;
2. невалидное числовое значение по правилам `set!`.

Пример:
```clojure
(observe.bus/set-queue-depth! registry {:module :billing} 3)
```

## 2. Матрица политик поведения

| Сценарий | Конфиг | Результат |
|---|---|---|
| labels mismatch | `:strict-labels? true` | exception |
| labels mismatch | `:strict-labels? false` | drop sample + log `:dropped-sample` + `lcmm_observe_dropped_samples_total` |
| series limit exceeded | `:on-series-limit :throw` | exception |
| series limit exceeded | `:on-series-limit :drop-and-log` | drop sample + logs `:series-limit-hit` и `:dropped-sample` + оба служебных счетчика |
| value = NaN/Infinity | `:on-invalid-number :throw` | exception |
| value = NaN/Infinity | `:on-invalid-number :drop-and-log` | drop sample + log `:dropped-sample` + `lcmm_observe_dropped_samples_total` |

## 3. Recommended Defaults (small projects)

## 3.1 Мягкий прод-режим (рекомендован по умолчанию)

```clojure
(def registry
  (obs/make-registry
    :strict-labels? false
    :max-series-per-metric 5000
    :on-series-limit :drop-and-log
    :on-invalid-number :drop-and-log
    :render-cache-ttl-ms 1000
    :storage-mode :single-atom))
```

## 3.2 Строгий режим (для CI/staging)

```clojure
(def registry
  (obs/make-registry
    :strict-labels? true
    :on-series-limit :throw
    :on-invalid-number :throw
    :storage-mode :single-atom))
```

## 4. End-to-End Quickstart

```clojure
(ns app.main
  (:require [lcmm.observe :as obs]
            [lcmm.observe.http :as observe.http]
            [lcmm.observe.bus :as observe.bus]
            [org.httpkit.server :as http-kit]))

(def registry
  (obs/make-registry
    :strict-labels? false
    :max-series-per-metric 5000
    :on-series-limit :drop-and-log
    :on-invalid-number :drop-and-log
    :render-cache-ttl-ms 1000))

(def bus-handler
  (observe.bus/wrap-bus-handler
   (fn [_bus _envelope] :ok)
   {:registry registry :module :billing :handler-name "emit-invoice"}))

(def app
  (observe.http/wrap-observe-http
   (fn [req]
     (case (:uri req)
       "/metrics" ((obs/metrics-handler registry) req)
       "/ping" {:status 200 :body "pong"}
       {:status 404 :body "not-found"}))
   {:registry registry :module :gateway :route-fn (fn [req] (:uri req))}))

(defn -main []
  (http-kit/run-server app {:port 8080}))
```

Проверка:
1. `curl http://localhost:8080/ping`
2. `curl http://localhost:8080/metrics`
3. убедитесь, что есть:
   1. `http_server_requests_total`
   2. `http_server_request_latency_ms`
   3. `lcmm_observe_dropped_samples_total`

## 5. Built-in служебные метрики

1. `lcmm_observe_dropped_samples_total`
Растет при любом policy-based drop sample.
2. `lcmm_observe_series_limit_hits_total`
Растет при срабатывании лимита series.

## 6. Troubleshooting (операционный)

## 6.1 Метрика не появляется

Диагностика:
1. проверьте, что write-path и `/metrics` используют один `registry`;
2. проверьте служебные счетчики:
   1. `grep lcmm_observe_dropped_samples_total`
   2. `grep lcmm_observe_series_limit_hits_total`

## 6.2 Растет `lcmm_observe_dropped_samples_total`

Типовые причины:
1. labels mismatch;
2. значения вне `:allowed-label-values`;
3. non-finite числа при `:on-invalid-number :drop-and-log`;
4. series-limit в режиме drop.

Действия:
1. проверьте события `:dropped-sample` в логах;
2. смотрите поле `:reason`.

## 6.3 Растет `lcmm_observe_series_limit_hits_total`

Причина: превышен `:max-series-per-metric`.

Действия:
1. нормализуйте labels (`route template`, без id);
2. уменьшите cardinality;
3. увеличьте лимит только после анализа.

## 6.4 Exception на labels

Причина: `:strict-labels? true` и ключи labels не совпадают с контрактом метрики.

Действие: передавайте ровно набор keys из `:labels` при регистрации.

## 6.5 Exception на `NaN/Infinity`

Причина: `:on-invalid-number :throw`.

Действия:
1. исправьте источник значений;
2. для мягкой деградации используйте `:on-invalid-number :drop-and-log`.

## 7. Migration Notes

1. HTTP fallback route теперь `"unknown"`, а не raw URI.
2. Для production задавайте `:max-series-per-metric` явно.
3. Мониторьте служебные `lcmm_observe_*` метрики.
