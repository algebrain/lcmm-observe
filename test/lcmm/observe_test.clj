(ns lcmm.observe-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [lcmm.observe :as obs]))

(defn- line-for
  [rendered prefix]
  (some #(when (str/starts-with? % prefix) %)
        (str/split-lines rendered)))

(deftest counter-and-gauge-render-test
  (let [registry (obs/make-registry)
        reqs (obs/counter! registry :http/server-requests-total
                           {:help "Total requests"
                            :labels [:module :method]})
        qdepth (obs/gauge! registry :event-bus/queue-depth
                           {:help "Queue depth"
                            :labels [:module]})]
    (obs/inc! reqs 2 {:module "users" :method "get"})
    (obs/inc! reqs 3 {:module "users" :method "get"})
    (obs/set! qdepth 7 {:module "bus"})
    (let [text (obs/render-prometheus registry)
          req-line (format "http_server_requests_total{module=%s,method=%s} 5.0" (pr-str "users") (pr-str "get"))
          gauge-line (format "event_bus_queue_depth{module=%s} 7.0" (pr-str "bus"))]
      (is (str/includes? text "# TYPE http_server_requests_total counter"))
      (is (str/includes? text req-line))
      (is (str/includes? text gauge-line)))))

(deftest histogram-render-test
  (let [registry (obs/make-registry)
        latency (obs/histogram! registry :http/server-request-latency-ms
                                {:help "Latency in milliseconds"
                                 :labels [:route]
                                 :buckets [10.0 50.0 100.0]})
        route-quoted (pr-str "/ping")]
    (obs/observe! latency 5 {:route "/ping"})
    (obs/observe! latency 10 {:route "/ping"})
    (obs/observe! latency 80 {:route "/ping"})
    (let [text (obs/render-prometheus registry)]
      (is (str/includes? text (format "http_server_request_latency_ms_bucket{route=%s,le=%s} 2" route-quoted (pr-str "10.0"))))
      (is (str/includes? text (format "http_server_request_latency_ms_bucket{route=%s,le=%s} 2" route-quoted (pr-str "50.0"))))
      (is (str/includes? text (format "http_server_request_latency_ms_bucket{route=%s,le=%s} 3" route-quoted (pr-str "100.0"))))
      (is (str/includes? text (format "http_server_request_latency_ms_bucket{route=%s,le=%s} 3" route-quoted (pr-str "+Inf"))))
      (is (str/includes? text (format "http_server_request_latency_ms_count{route=%s} 3" route-quoted))))))

(deftest invalid-labels-non-strict-test
  (let [logged (atom [])
        logger (fn [level data] (swap! logged conj [level data]))
        registry (obs/make-registry :logger logger)
        reqs (obs/counter! registry :http/requests-total {:labels [:module]})]
    (obs/inc! reqs 1 {:module "users" :extra "ignored"})
    (let [text (obs/render-prometheus registry)
          [level data] (first @logged)]
      (is (nil? (line-for text "http_requests_total")))
      (is (= :warn level))
      (is (= :dropped-sample (:event data)))
      (is (str/includes? text "lcmm_observe_dropped_samples_total 1")))))

(deftest strict-labels-throws-test
  (let [registry (obs/make-registry :strict-labels? true)
        reqs (obs/counter! registry :http/requests-total {:labels [:module]})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (obs/inc! reqs 1 {:module "users" :extra "x"})))))

(deftest duplicate-metric-conflict-test
  (let [registry (obs/make-registry)]
    (obs/counter! registry :demo/value {:labels [:module]})
    (is (thrown? clojure.lang.ExceptionInfo
                 (obs/histogram! registry :demo/value {:labels [:module]})))))

(deftest with-timing-test
  (let [registry (obs/make-registry)
        h (obs/histogram! registry :demo/work-latency-ms
                          {:labels [:module]
                           :buckets [1.0 10.0 100.0]})]
    (obs/with-timing h {:module "demo"}
      (Thread/sleep 2)
      :ok)
    (let [text (obs/render-prometheus registry)]
      (is (str/includes? text (format "demo_work_latency_ms_count{module=%s} 1" (pr-str "demo")))))))

(deftest metrics-handler-test
  (let [registry (obs/make-registry)
        reqs (obs/counter! registry :demo/requests-total {})
        handler (obs/metrics-handler registry)]
    (obs/inc! reqs)
    (let [resp (handler {:uri "/metrics"})]
      (is (= 200 (:status resp)))
      (is (= "text/plain; version=0.0.4; charset=utf-8"
             (get-in resp [:headers "content-type"])))
      (is (str/includes? (:body resp) "demo_requests_total 1.0")))))

(deftest concurrent-counter-updates-test
  (let [registry (obs/make-registry)
        reqs (obs/counter! registry :demo/concurrent-total {})
        workers (doall
                 (for [_ (range 20)]
                   (future
                     (dotimes [_ 1000]
                       (obs/inc! reqs)))))]
    (doseq [w workers] @w)
    (let [text (obs/render-prometheus registry)]
      (is (str/includes? text "demo_concurrent_total 20000.0")))))

(deftest series-limit-drop-and-log-test
  (let [registry (obs/make-registry :max-series-per-metric 1
                                    :on-series-limit :drop-and-log)
        reqs (obs/counter! registry :demo/limited-total {:labels [:route]})]
    (obs/inc! reqs 1.0 {:route "/a"})
    (obs/inc! reqs 1.0 {:route "/b"})
    (let [text (obs/render-prometheus registry)]
      (is (str/includes? text (format "demo_limited_total{route=%s} 1.0" (pr-str "/a"))))
      (is (nil? (line-for text (format "demo_limited_total{route=%s}" (pr-str "/b")))))
      (is (str/includes? text "lcmm_observe_series_limit_hits_total 1"))
      (is (str/includes? text "lcmm_observe_dropped_samples_total 1")))))

(deftest series-limit-throw-test
  (let [registry (obs/make-registry :max-series-per-metric 1
                                    :on-series-limit :throw)
        reqs (obs/counter! registry :demo/limited-total {:labels [:route]})]
    (obs/inc! reqs 1.0 {:route "/a"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (obs/inc! reqs 1.0 {:route "/b"})))))

(deftest finite-number-validation-test
  (let [registry (obs/make-registry)
        c (obs/counter! registry :demo/c {:labels [:m]})
        g (obs/gauge! registry :demo/g {:labels [:m]})
        h (obs/histogram! registry :demo/h {:labels [:m] :buckets [1.0 2.0]})]
    (is (thrown? clojure.lang.ExceptionInfo (obs/inc! c Double/NaN {:m "x"})))
    (is (thrown? clojure.lang.ExceptionInfo (obs/set! g Double/POSITIVE_INFINITY {:m "x"})))
    (is (thrown? clojure.lang.ExceptionInfo (obs/observe! h Double/NEGATIVE_INFINITY {:m "x"})))))

(deftest invalid-number-drop-policy-test
  (let [logged (atom [])
        logger (fn [level data] (swap! logged conj [level data]))
        registry (obs/make-registry :logger logger
                                    :on-invalid-number :drop-and-log)
        c (obs/counter! registry :demo/c {:labels [:m]})]
    (obs/inc! c Double/NaN {:m "x"})
    (let [text (obs/render-prometheus registry)]
      (is (nil? (line-for text "demo_c{")))
      (is (str/includes? text "lcmm_observe_dropped_samples_total 1"))
      (is (= :dropped-sample (-> @logged first second :event))))))

(deftest per-metric-atom-mode-test
  (let [registry (obs/make-registry :storage-mode :per-metric-atom)
        c (obs/counter! registry :demo/per-metric-total {:labels [:k]})
        workers (doall
                 (for [_ (range 10)]
                   (future
                     (dotimes [_ 1000]
                       (obs/inc! c 1.0 {:k "v"})))))]
    (doseq [w workers] @w)
    (let [text (obs/render-prometheus registry)]
      (is (str/includes? text (format "demo_per_metric_total{k=%s} 10000.0" (pr-str "v")))))))

(deftest render-cache-ttl-test
  (let [registry (obs/make-registry :render-cache-ttl-ms 1000)
        c (obs/counter! registry :demo/cache-total {})]
    (obs/inc! c)
    (let [a (obs/render-prometheus registry)
          b (obs/render-prometheus registry)]
      (is (= a b))
      (is (str/includes? a "demo_cache_total 1.0")))))
