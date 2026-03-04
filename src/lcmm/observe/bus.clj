(ns lcmm.observe.bus
  (:require [lcmm.observe :as obs]))

(defn- event-type-str
  [envelope event-type-fn]
  (let [raw (if event-type-fn
              (event-type-fn envelope)
              (:event-type envelope))]
    (if (keyword? raw)
      (subs (str raw) 1)
      (str raw))))

(defn- ensure-counter
  [registry metric-id opts]
  (try
    (obs/get-counter registry metric-id)
    (catch clojure.lang.ExceptionInfo ex
      (if (= :counter (:actual-type (ex-data ex)))
        (throw ex)
        (obs/register-counter! registry metric-id opts)))))

(defn- ensure-histogram
  [registry metric-id opts]
  (try
    (obs/get-histogram registry metric-id)
    (catch clojure.lang.ExceptionInfo ex
      (if (= :histogram (:actual-type (ex-data ex)))
        (throw ex)
        (obs/register-histogram! registry metric-id opts)))))

(defn- ensure-gauge
  [registry metric-id opts]
  (try
    (obs/get-gauge registry metric-id)
    (catch clojure.lang.ExceptionInfo ex
      (if (= :gauge (:actual-type (ex-data ex)))
        (throw ex)
        (obs/register-gauge! registry metric-id opts)))))

(defn wrap-bus-handler
  "Wraps event-bus handler and records latency/failure metrics.

  Required options:
  - :registry
  - :module
  - :handler-name

  Optional options:
  - :event-type-fn (fn [envelope] event-type)"
  [handler {:keys [registry module handler-name event-type-fn]}]
  (when-not registry
    (throw (ex-info "Missing :registry in wrap-bus-handler options." {})))
  (when-not module
    (throw (ex-info "Missing :module in wrap-bus-handler options." {})))
  (when-not handler-name
    (throw (ex-info "Missing :handler-name in wrap-bus-handler options." {})))
  (let [failures (ensure-counter registry :event-bus/handler-failures-total
                                 {:help "Event bus handler failures"
                                  :labels [:module :event_type :handler]})
        latency (ensure-histogram registry :event-bus/handler-latency-ms
                                  {:help "Event bus handler latency in ms"
                                   :labels [:module :event_type :handler]
                                   :buckets [1.0 2.0 5.0 10.0 25.0 50.0 100.0 250.0 500.0 1000.0]})]
    (fn [bus envelope]
      (let [labels {:module (name module)
                    :event_type (event-type-str envelope event-type-fn)
                    :handler (str handler-name)}
            start-ns (System/nanoTime)]
        (try
          (handler bus envelope)
          (catch Throwable t
            (obs/inc! failures 1.0 labels)
            (throw t))
          (finally
            (obs/observe! latency
                          (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
                          labels)))))))

(defn set-queue-depth!
  "Updates queue depth gauge for buffered bus mode."
  [registry {:keys [module]} depth]
  (when-not registry
    (throw (ex-info "Missing registry in set-queue-depth!." {})))
  (when-not module
    (throw (ex-info "Missing :module in set-queue-depth! options." {})))
  (let [gauge (ensure-gauge registry :event-bus/queue-depth
                            {:help "Event bus queue depth"
                             :labels [:module]})]
    (obs/set! gauge (double depth) {:module (name module)})))
