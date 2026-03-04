# OBSERVE_MODULE: применение `lcmm-observe` на уровне модуля

Этот документ объясняет, как авторам модулей использовать `lcmm-observe`.
Подход намеренно облегченный для небольших и средних backend-проектов.

Канонический API-контракт описан в [`OBSERVABILITY.md`](./OBSERVABILITY.md).
Распределение ответственности и baseline приложения описаны в [`OBSERVE_APP.md`](./OBSERVE_APP.md).

## 1. Зона ответственности (уровень модуля)

Модуль должен:
1. инструментировать только важное поведение модуля;
2. следовать app-level соглашениям по именам и labels;
3. использовать зависимости, переданные приложением.

Модуль не должен:
1. определять глобальную observability-политику;
2. поднимать собственный endpoint `/metrics`;
3. владеть жизненным циклом глобального registry.

## 2. Уровни внедрения для модулей

## Level 1 (обязательный минимум)

Для каждого модуля выберите один из вариантов:
1. без кастомной метрики (допустимо, если ценность низкая);
2. одна ключевая метрика для high-value use-case.

Это базовое ожидание.

## Level 2 (по необходимости)

Добавляйте только при подтвержденных проблемах:
1. одну latency-метрику вокруг проблемного пути (`with-timing`);
2. bus-wrapper для критичного handler.

## Level 3 (продвинутый)

Только при наличии доказанной необходимости:
1. более широкий набор метрик для subflows;
2. более жесткие label-ограничения совместно с app-level политикой.

## 3. Минимальные шаблоны для модулей

## Pattern A: один ключевой counter в потоке модуля

```clojure
(ns my.module.core
  (:require [lcmm.observe :as obs]))

(defn handle-important-op!
  [registry payload]
  (let [op-total (obs/register-counter! registry :my-module/important-op-total
                               {:labels [:module :result]})]
    (try
      ;; business logic
      (obs/inc! op-total 1.0 {:module "my-module" :result "ok"})
      {:status :ok}
      (catch Throwable e
        (obs/inc! op-total 1.0 {:module "my-module" :result "error"})
        (throw e)))))
```

## Pattern B: измерение критичного bus handler

```clojure
(ns my.module.bus
  (:require [lcmm.observe.bus :as observe.bus]))

(defn instrumented-handler
  [registry]
  (observe.bus/wrap-bus-handler
   (fn [bus envelope]
     ;; critical handler logic
     true)
   {:registry registry
    :module :my-module
    :handler-name "critical-handler"}))
```

## 4. Правила labels для модулей

1. Используйте только low-cardinality labels.
2. Предпочитайте стабильные значения (`module`, `result`, `event_type`, нормализованный `route`).
3. Никогда не используйте user identifiers, payload blobs или random ids как labels.

## 5. Do / Don't

Do:
1. держите инструментирование модуля минимальным;
2. добавляйте только действительно полезные бизнес-сигналы;
3. выравнивайте metric names/labels с app-level соглашениями.

Don't:
1. не создавайте много метрик на каждую функцию;
2. не дублируйте одну и ту же метрику на нескольких слоях;
3. не добавляйте метрики, которые никто не использует.

## 6. Минимальный pre-merge checklist (модуль)

1. Добавленная метрика реально полезна для эксплуатации/отладки.
2. Labels имеют низкую cardinality.
3. Нет module-level публикации `/metrics`.
4. Реализация корректно работает с app-level shared registry.
