(ns lcmm.observe-integration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [lcmm.observe :as obs]
            [lcmm.observe.bus :as observe.bus]
            [lcmm.observe.http :as observe.http]))

(deftest http-wrapper-success-and-errors-test
  (let [registry (obs/make-registry)
        app (observe.http/wrap-observe-http
             (fn [req]
               (case (:uri req)
                 "/ok" {:status 200 :body "ok"}
                 "/bad" {:status 500 :body "bad"}
                 {:status 404 :body "missing"}))
             {:registry registry
              :module :gateway
              :route-fn (fn [req] (:uri req))})]
    (app {:request-method :get :uri "/ok"})
    (app {:request-method :post :uri "/bad"})
    (let [text (obs/render-prometheus registry)
          base1 {:module (pr-str "gateway") :method (pr-str "get") :route (pr-str "/ok") :status (pr-str "2xx")}
          base2 {:module (pr-str "gateway") :method (pr-str "post") :route (pr-str "/bad") :status (pr-str "5xx")}]
      (is (str/includes? text
                         (format "http_server_requests_total{module=%s,method=%s,route=%s,status_class=%s} 1.0"
                                 (:module base1) (:method base1) (:route base1) (:status base1))))
      (is (str/includes? text
                         (format "http_server_requests_total{module=%s,method=%s,route=%s,status_class=%s} 1.0"
                                 (:module base2) (:method base2) (:route base2) (:status base2))))
      (is (str/includes? text
                         (format "http_server_request_errors_total{module=%s,method=%s,route=%s,status_class=%s} 1.0"
                                 (:module base2) (:method base2) (:route base2) (:status base2))))
      (is (str/includes? text
                         (format "http_server_request_latency_ms_count{module=%s,method=%s,route=%s} 1"
                                 (:module base1) (:method base1) (:route base1))))
      (is (str/includes? text
                         (format "http_server_request_latency_ms_count{module=%s,method=%s,route=%s} 1"
                                 (:module base2) (:method base2) (:route base2)))))))

(deftest http-wrapper-exception-test
  (let [registry (obs/make-registry)
        app (observe.http/wrap-observe-http
             (fn [_] (throw (ex-info "boom" {})))
             {:registry registry :module :gateway :route-fn (fn [req] (:uri req))})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (app {:request-method :get :uri "/explode"})))
    (let [text (obs/render-prometheus registry)
          module (pr-str "gateway")
          method (pr-str "get")
          route (pr-str "/explode")
          sclass (pr-str "5xx")]
      (is (str/includes? text
                         (format "http_server_requests_total{module=%s,method=%s,route=%s,status_class=%s} 1.0"
                                 module method route sclass)))
      (is (str/includes? text
                         (format "http_server_request_errors_total{module=%s,method=%s,route=%s,status_class=%s} 1.0"
                                 module method route sclass))))))

(deftest http-wrapper-default-route-and-method-test
  (let [registry (obs/make-registry)
        app (observe.http/wrap-observe-http
             (fn [_] {:status 200 :body "ok"})
             {:registry registry :module :gateway})]
    (app {:uri "/raw/123"})
    (let [text (obs/render-prometheus registry)]
      (is (str/includes? text
                         (format "http_server_requests_total{module=%s,method=%s,route=%s,status_class=%s} 1.0"
                                 (pr-str "gateway")
                                 (pr-str "unknown")
                                 (pr-str "unknown")
                                 (pr-str "2xx")))))))

(deftest bus-wrapper-test
  (let [registry (obs/make-registry)
        ok-handler (observe.bus/wrap-bus-handler
                    (fn [_bus _envelope] :ok)
                    {:registry registry
                     :module :billing
                     :handler-name "emit-invoice"})
        fail-handler (observe.bus/wrap-bus-handler
                      (fn [_bus _envelope] (throw (ex-info "handler-fail" {})))
                      {:registry registry
                       :module :billing
                       :handler-name "emit-invoice"})
        envelope {:event-type :order/paid}
        module (pr-str "billing")
        event-type (pr-str "order/paid")
        handler-name (pr-str "emit-invoice")]
    (ok-handler nil envelope)
    (is (thrown? clojure.lang.ExceptionInfo
                 (fail-handler nil envelope)))
    (observe.bus/set-queue-depth! registry {:module :billing} 3)
    (let [text (obs/render-prometheus registry)]
      (is (str/includes? text
                         (format "event_bus_handler_failures_total{module=%s,event_type=%s,handler=%s} 1.0"
                                 module event-type handler-name)))
      (is (str/includes? text
                         (format "event_bus_handler_latency_ms_count{module=%s,event_type=%s,handler=%s} 2"
                                 module event-type handler-name)))
      (is (str/includes? text
                         (format "event_bus_queue_depth{module=%s} 3.0" module))))))
