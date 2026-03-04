(ns lcmm.observe-bench
  (:require [clojure.string :as str]
            [lcmm.observe :as obs]))

(def mode-config
  {:quick {:warmup-ms 1000
           :measure-ms 2500
           :repeats 2
           :sample-rate 200
           :threads [1 4]
           :render-series [100 1000]
           :render-ttls [0 1000]
           :memory-series [1000 10000]
           :soak-ms 15000
           :soak-threads 4
           :soak-render-interval-ms 200}
   :full {:warmup-ms 3000
          :measure-ms 8000
          :repeats 5
          :sample-rate 500
          :threads [1 2 4 8 16]
          :render-series [100 1000 10000]
          :render-ttls [0 1000]
          :memory-series [1000 10000 100000]
          :soak-ms 120000
          :soak-threads 8
          :soak-render-interval-ms 100}})

(defn- parse-args
  [args]
  (reduce (fn [acc arg]
            (if (str/starts-with? arg "--")
              (let [[k v] (str/split (subs arg 2) #"=" 2)
                    keyw (keyword k)]
                (assoc acc keyw (or v true)))
              acc))
          {}
          args))

(defn- now-ns []
  (System/nanoTime))

(defn- median
  [xs]
  (when (seq xs)
    (let [sorted (vec (sort xs))
          n (count sorted)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0)))))

(defn- percentile
  [sorted-xs p]
  (when (seq sorted-xs)
    (let [n (count sorted-xs)
          idx (int (Math/ceil (* (/ p 100.0) n)))
          idx (max 1 (min n idx))]
      (nth sorted-xs (dec idx)))))

(defn- ns->us [n]
  (/ (double n) 1000.0))

(defn- run-workers
  [{:keys [threads duration-ms sample-rate op-fn]}]
  (let [deadline (+ (now-ns) (* (long duration-ms) 1000000))
        errors (atom 0)
        futures (doall
                 (for [_ (range threads)]
                   (future
                     (loop [ops 0
                            lats (transient [])]
                       (if (< (now-ns) deadline)
                         (let [t0 (now-ns)
                               _ignored (try
                                          (op-fn)
                                          (catch Throwable _
                                            (swap! errors inc)))
                               t1 (now-ns)
                               ops2 (inc ops)
                               lats2 (if (zero? (mod ops2 sample-rate))
                                       (conj! lats (- t1 t0))
                                       lats)]
                           (recur ops2 lats2))
                         {:ops ops :latencies (persistent! lats)})))))
        results (mapv deref futures)]
    {:ops (reduce + (map :ops results))
     :latencies (vec (mapcat :latencies results))
     :errors @errors}))

(defn- summarize-run
  [{:keys [ops latencies errors duration-ms]}]
  (let [sorted (vec (sort latencies))]
    {:ops ops
     :errors errors
     :throughput-ops-sec (/ (double ops) (/ duration-ms 1000.0))
     :latency-p50-us (some-> (percentile sorted 50) ns->us)
     :latency-p95-us (some-> (percentile sorted 95) ns->us)
     :latency-p99-us (some-> (percentile sorted 99) ns->us)}))

(defn- aggregate-runs
  [runs]
  {:runs (count runs)
   :ops-total (reduce + (map :ops runs))
   :errors-total (reduce + (map :errors runs))
   :throughput-median-ops-sec (median (map :throughput-ops-sec runs))
   :latency-p50-median-us (median (keep :latency-p50-us runs))
   :latency-p95-median-us (median (keep :latency-p95-us runs))
   :latency-p99-median-us (median (keep :latency-p99-us runs))})

(defn- measure-case
  [{:keys [warmup-ms measure-ms repeats sample-rate threads op-fn]}]
  (run-workers {:threads threads
                :duration-ms warmup-ms
                :sample-rate 1000000
                :op-fn op-fn})
  (let [runs (for [_ (range repeats)]
               (-> (run-workers {:threads threads
                                 :duration-ms measure-ms
                                 :sample-rate sample-rate
                                 :op-fn op-fn})
                   (assoc :duration-ms measure-ms)
                   summarize-run))]
    (aggregate-runs runs)))

(defn- benchmark-update
  [cfg]
  (for [storage [:single-atom :per-metric-atom]
        op [:inc :set :observe]
        threads (:threads cfg)]
    (let [registry (obs/make-registry :storage-mode storage
                                      :on-invalid-number :throw
                                      :strict-labels? false)
          metric (case op
                   :inc (obs/register-counter! registry :bench/inc {:labels [:k]})
                   :set (obs/register-gauge! registry :bench/set {:labels [:k]})
                   :observe (obs/register-histogram! registry :bench/obs {:labels [:k]
                                                                          :buckets [1.0 5.0 10.0 50.0]}))
          op-fn (case op
                  :inc #(obs/inc! metric 1.0 {:k "v"})
                  :set #(obs/set! metric 42.0 {:k "v"})
                  :observe #(obs/observe! metric 7.0 {:k "v"}))
          summary (measure-case (merge cfg
                                       {:threads threads
                                        :op-fn op-fn}))]
      (merge {:bench :update
              :operation op
              :storage-mode storage
              :threads threads}
             summary))))

(defn- parse-metric
  [rendered metric-name]
  (when-let [[_ value] (re-find (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote metric-name) " ([0-9.Ee+-]+)$"))
                                rendered)]
    (Double/parseDouble value)))

(defn- benchmark-policy
  [cfg]
  (let [base (for [strict [false true]
                   invalid-policy [:throw :drop-and-log]]
               (let [registry (obs/make-registry :strict-labels? strict
                                                 :on-invalid-number invalid-policy)
                     metric (obs/register-counter! registry :bench/policy {:labels [:k]})
                     summary (measure-case (merge cfg
                                                  {:threads 4
                                                   :op-fn #(obs/inc! metric 1.0 {:k "v"})}))
                     rendered (obs/render-prometheus registry)]
                 (merge {:bench :policy
                         :scenario :valid-input-overhead
                         :strict-labels? strict
                         :on-invalid-number invalid-policy
                         :dropped-samples (or (parse-metric rendered "lcmm_observe_dropped_samples_total") 0.0)}
                        summary)))
        limit-cases (for [limit-policy [:throw :drop-and-log]]
                      (let [registry (obs/make-registry :max-series-per-metric 1
                                                        :on-series-limit limit-policy
                                                        :strict-labels? false)
                            metric (obs/register-counter! registry :bench/limit {:labels [:k]})
                            toggler (atom false)
                            summary (measure-case (merge cfg
                                                         {:threads 2
                                                          :op-fn (fn []
                                                                   (let [k (if (swap! toggler not) "a" "b")]
                                                                     (obs/inc! metric 1.0 {:k k})))}))
                            rendered (obs/render-prometheus registry)]
                        (merge {:bench :policy
                                :scenario :series-limit-overhead
                                :on-series-limit limit-policy
                                :dropped-samples (or (parse-metric rendered "lcmm_observe_dropped_samples_total") 0.0)
                                :series-limit-hits (or (parse-metric rendered "lcmm_observe_series_limit_hits_total") 0.0)}
                               summary)))]
    (concat base limit-cases)))

(defn- prepare-render-registry
  [series-count ttl-ms]
  (let [registry (obs/make-registry :render-cache-ttl-ms ttl-ms
                                    :max-series-per-metric nil)
        metric (obs/register-counter! registry :bench/render-total {:labels [:series]})]
    (doseq [i (range series-count)]
      (obs/inc! metric 1.0 {:series (str i)}))
    registry))

(defn- benchmark-render
  [cfg]
  (for [series-count (:render-series cfg)
        ttl-ms (:render-ttls cfg)]
    (let [registry (prepare-render-registry series-count ttl-ms)
          summary (measure-case (merge cfg
                                       {:threads 1
                                        :sample-rate 1
                                        :op-fn #(obs/render-prometheus registry)}))]
      (merge {:bench :render
              :series-count series-count
              :render-cache-ttl-ms ttl-ms}
             summary))))

(defn- force-gc! []
  (System/gc)
  (Thread/sleep 1200))

(defn- heap-used-bytes []
  (let [rt (Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

(defn- benchmark-memory
  [cfg]
  (for [storage [:single-atom :per-metric-atom]
        series-count (:memory-series cfg)]
    (do
      (force-gc!)
      (let [before (heap-used-bytes)
            registry (obs/make-registry :storage-mode storage
                                        :max-series-per-metric nil)
            metric (obs/register-counter! registry :bench/memory-total {:labels [:id]})]
        (doseq [i (range series-count)]
          (obs/inc! metric 1.0 {:id (str i)}))
        (let [after-fill (heap-used-bytes)]
          (force-gc!)
          (let [after-gc (heap-used-bytes)
                delta-fill (- after-fill before)
                delta-retained (- after-gc before)
                per-1k (if (pos? series-count)
                         (/ (double delta-retained) (/ series-count 1000.0))
                         0.0)]
            {:bench :memory
             :storage-mode storage
             :series-count series-count
             :heap-before-mb (/ before 1048576.0)
             :heap-after-fill-mb (/ after-fill 1048576.0)
             :heap-after-gc-mb (/ after-gc 1048576.0)
             :delta-fill-mb (/ delta-fill 1048576.0)
             :delta-retained-mb (/ delta-retained 1048576.0)
             :retained-bytes-per-1k-series per-1k}))))))

(defn- benchmark-soak
  [cfg]
  (let [registry (obs/make-registry :strict-labels? false
                                    :on-series-limit :drop-and-log
                                    :max-series-per-metric 5000
                                    :on-invalid-number :drop-and-log
                                    :render-cache-ttl-ms 1000)
        c (obs/register-counter! registry :bench/soak-total {:labels [:k]})
        h (obs/register-histogram! registry :bench/soak-latency {:labels [:k]
                                                                 :buckets [1.0 2.0 5.0 10.0 25.0]})
        stop-at (+ (now-ns) (* (long (:soak-ms cfg)) 1000000))
        errors (atom 0)
        ops (atom 0)
        workers (doall
                 (for [i (range (:soak-threads cfg))]
                   (future
                     (loop [n 0]
                       (when (< (now-ns) stop-at)
                         (try
                           (obs/inc! c 1.0 {:k (str (mod (+ i n) 5))})
                           (obs/observe! h (double (inc (mod n 7))) {:k (str (mod (+ i n) 5))})
                           (swap! ops + 2)
                           (catch Throwable _
                             (swap! errors inc)))
                         (recur (inc n)))))))
        renderer (future
                   (loop []
                     (when (< (now-ns) stop-at)
                       (try
                         (obs/render-prometheus registry)
                         (catch Throwable _
                           (swap! errors inc)))
                       (Thread/sleep (:soak-render-interval-ms cfg))
                       (recur))))]
    (doseq [f workers] @f)
    @renderer
    (let [rendered (obs/render-prometheus registry)]
      {:bench :soak
       :duration-ms (:soak-ms cfg)
       :threads (:soak-threads cfg)
       :ops-total @ops
       :throughput-ops-sec (/ (double @ops) (/ (:soak-ms cfg) 1000.0))
       :errors @errors
       :dropped-samples (or (parse-metric rendered "lcmm_observe_dropped_samples_total") 0.0)
       :series-limit-hits (or (parse-metric rendered "lcmm_observe_series_limit_hits_total") 0.0)})))

(defn- print-section
  [title rows]
  (println)
  (println (str "=== " title " ==="))
  (doseq [row rows]
    (println (pr-str row))))

(defn- run-all
  [{:keys [scenario out] :as opts}]
  (let [mode-key (keyword (or (:mode opts) "quick"))
        cfg (merge (get mode-config mode-key (:quick mode-config))
                   {:scenario scenario})
        update-results (when (or (= scenario :all) (= scenario :update))
                         (vec (benchmark-update cfg)))
        policy-results (when (or (= scenario :all) (= scenario :policy))
                         (vec (benchmark-policy cfg)))
        render-results (when (or (= scenario :all) (= scenario :render))
                         (vec (benchmark-render cfg)))
        memory-results (when (or (= scenario :all) (= scenario :memory))
                         (vec (benchmark-memory cfg)))
        soak-result (when (or (= scenario :all) (= scenario :soak))
                      (benchmark-soak cfg))
        result {:mode mode-key
                :config cfg
                :update update-results
                :policy policy-results
                :render render-results
                :memory memory-results
                :soak soak-result}]
    (when update-results (print-section "Update" update-results))
    (when policy-results (print-section "Policy" policy-results))
    (when render-results (print-section "Render" render-results))
    (when memory-results (print-section "Memory" memory-results))
    (when soak-result (print-section "Soak" [soak-result]))
    (when out
      (spit out (pr-str result)))
    result))

(defn -main
  [& args]
  (let [opts0 (parse-args args)
        scenario (keyword (or (:scenario opts0) "all"))
        out (:out opts0)
        mode (or (:mode opts0) "quick")
        opts {:mode mode
              :scenario scenario
              :out out}]
    (println (str "RUNNING BENCHMARKS mode=" mode " scenario=" (name scenario)))
    (run-all opts)
    (shutdown-agents)))
