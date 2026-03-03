# OBSERVE_APP: применение `lcmm-observe` на уровне приложения

Этот документ объясняет, как использовать `lcmm-observe` на **уровне приложения**.
Подход специально оптимизирован для небольших и средних backend-проектов.

Канонический API-контракт описан в [`OBSERVABILITY.md`](./OBSERVABILITY.md).

## 1. Зона ответственности (уровень приложения)

Приложение владеет:
1. единым общим `registry`;
2. endpoint-ом `/metrics`;
3. глобальными политиками (`max-series`, strictness, поведение при invalid numbers, cache TTL);
4. местом подключения wrappers в runtime-пайплайне.

Модули не должны владеть этими аспектами.

## 2. Уровни внедрения

## Level 1 (обязательный минимум)

Реализуйте только это:
1. один `registry`;
2. один endpoint `/metrics`;
3. HTTP-wrapper на основном входном handler;
4. опционально: ноль или одна критичная бизнес-метрика на модуль.

Для большинства small/medium сервисов этого достаточно.

## Level 2 (по необходимости)

Добавляйте только по реальным сигналам (инциденты, повторяющиеся latency/errors):
1. bus-wrapper на критичных handlers;
2. дополнительные latency-histograms для проблемных потоков;
3. простые алерты на `lcmm_observe_*` метрики.

## Level 3 (продвинутый, opt-in)

Включайте только при доказанной необходимости:
1. whitelist через `allowed-label-values`;
2. строгие runtime-политики;
3. более глубокое покрытие domain-метриками.

## 3. Рекомендуемый baseline для Level 1

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

## 4. Минимальная wiring-схема приложения (Level 1)

```clojure
(ns app.main
  (:require [lcmm.observe :as obs]
            [lcmm.observe.http :as observe.http]
            [org.httpkit.server :as http-kit]))

(def registry
  (obs/make-registry
    :strict-labels? false
    :max-series-per-metric 5000
    :on-series-limit :drop-and-log
    :on-invalid-number :drop-and-log
    :render-cache-ttl-ms 1000))

(def metrics-handler (obs/metrics-handler registry))

(def app
  (observe.http/wrap-observe-http
   (fn [req]
     (if (= "/metrics" (:uri req))
       (metrics-handler req)
       {:status 200 :body "ok"}))
   {:registry registry
    :module :app
    :route-fn (fn [req]
                (or (get-in req [:reitit.core/match :template])
                    "unknown"))}))

(defn -main []
  (http-kit/run-server app {:port 8080}))
```

## 5. Что мониторить в первую очередь

Всегда включайте:
1. `http_server_requests_total`
2. `http_server_request_errors_total`
3. `http_server_request_latency_ms`
4. `lcmm_observe_dropped_samples_total`
5. `lcmm_observe_series_limit_hits_total`

## 6. Do / Don't

Do:
1. используйте один общий registry;
2. нормализуйте route labels (`template`, а не raw id paths);
3. держите низкую cardinality;
4. начинайте с Level 1 и расширяйте только при необходимости.

Don't:
1. не создавайте per-module registry по умолчанию;
2. не инструментируйте все endpoint-ы «на всякий случай»;
3. не включайте строгие политики везде с первого дня;
4. не используйте high-cardinality labels (`user-id`, payload fields, random ids).

## 7. Минимальный release-checklist

1. `/metrics` доступен.
2. Метрики Level 1 для HTTP присутствуют.
3. `lcmm_observe_dropped_samples_total` и `lcmm_observe_series_limit_hits_total` видны.
4. В baseline-инструментировании нет raw high-cardinality labels.
