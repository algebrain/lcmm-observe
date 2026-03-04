(ns lcmm.observe.http
  (:require [lcmm.observe :as obs]))

(defn- status-class
  [status]
  (str (quot (long status) 100) "xx"))

(defn- normalize-method
  [request-method]
  (cond
    (keyword? request-method) (name request-method)
    (string? request-method) request-method
    :else "unknown"))

(defn- route-template
  [req route-fn]
  (or (when route-fn (route-fn req))
      (get-in req [:reitit.core/match :template])
      "unknown"))

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

(defn wrap-observe-http
  "Wraps Ring handler and records HTTP metrics.

  Required options:
  - :registry
  - :module

  Optional options:
  - :route-fn (fn [req] route-template)"
  [handler {:keys [registry module route-fn]}]
  (when-not registry
    (throw (ex-info "Missing :registry in wrap-observe-http options." {})))
  (when-not module
    (throw (ex-info "Missing :module in wrap-observe-http options." {})))
  (let [req-total (ensure-counter registry :http/server-requests-total
                                  {:help "Total HTTP requests"
                                   :labels [:module :method :route :status_class]})
        req-errors (ensure-counter registry :http/server-request-errors-total
                                   {:help "Total HTTP error responses"
                                    :labels [:module :method :route :status_class]})
        req-latency (ensure-histogram registry :http/server-request-latency-ms
                                      {:help "HTTP request latency in ms"
                                       :labels [:module :method :route]
                                       :buckets [5.0 10.0 25.0 50.0 100.0 250.0 500.0 1000.0 2500.0]})]
    (fn [req]
      (let [route (str (route-template req route-fn))
            method (normalize-method (:request-method req))
            labels {:module (name module)
                    :method method
                    :route route}
            start-ns (System/nanoTime)]
        (try
          (let [resp (handler req)
                status (long (or (:status resp) 500))
                sclass (status-class status)]
            (obs/inc! req-total 1.0 (assoc labels :status_class sclass))
            (when (>= status 400)
              (obs/inc! req-errors 1.0 (assoc labels :status_class sclass)))
            resp)
          (catch Throwable t
            (obs/inc! req-total 1.0 (assoc labels :status_class "5xx"))
            (obs/inc! req-errors 1.0 (assoc labels :status_class "5xx"))
            (throw t))
          (finally
            (obs/observe! req-latency
                          (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
                          labels)))))))
