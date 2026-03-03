(ns lcmm.observe
  (:require [clojure.string :as str]))

(defn- metric-name
  [metric-id]
  (-> (str metric-id)
      (subs 1)
      (str/replace #"[\./-]" "_")))

(defn- label-name
  [label-k]
  (-> (name label-k)
      (str/replace #"[\./-]" "_")))

(defn- raise!
  [message data]
  (throw (ex-info message data)))

(defn- log!
  [registry level data]
  (when-let [logger (:logger registry)]
    (try
      (logger level (merge {:component :lcmm-observe} data))
      (catch Throwable _ nil))))

(defn- finite-number?
  [n]
  (and (number? n)
       (Double/isFinite (double n))))

(defn- invalidate-render-cache!
  [registry]
  (reset! (:render-cache registry) {:ts-ms 0 :body nil}))

(defn- inc-internal-counter!
  [registry k]
  (swap! (:internal-counters registry) update k (fnil inc 0))
  (invalidate-render-cache! registry))

(defn- record-drop!
  [registry data]
  (inc-internal-counter! registry :dropped-samples-total)
  (log! registry :warn (merge {:event :dropped-sample} data)))

(defn- record-series-limit!
  [registry data]
  (inc-internal-counter! registry :series-limit-hits-total)
  (log! registry :warn (merge {:event :series-limit-hit} data)))

(defn make-registry
  "Creates a metrics registry.

  Options:
  - :logger - optional logger with (fn [level data]) signature.
  - :strict-labels? - if true, invalid labels throw; otherwise sample is dropped and warning logged.
  - :max-series-per-metric - max number of unique label-series per metric (default 5000).
  - :on-series-limit - :drop-and-log (default) or :throw.
  - :allowed-label-values - optional {label-key #{allowed-values-as-string}}.
  - :render-cache-ttl-ms - cache TTL for render-prometheus (default 0, disabled).
  - :storage-mode - :single-atom (default) or :per-metric-atom.
  - :on-invalid-number - :throw (default) or :drop-and-log."
  [& {:keys [logger
             strict-labels?
             max-series-per-metric
             on-series-limit
             allowed-label-values
             render-cache-ttl-ms
             storage-mode
             on-invalid-number]
      :or {strict-labels? false
           max-series-per-metric 5000
           on-series-limit :drop-and-log
           allowed-label-values {}
           render-cache-ttl-ms 0
           storage-mode :single-atom
           on-invalid-number :throw}}]
  (when-not (#{:single-atom :per-metric-atom} storage-mode)
    (raise! "Unsupported :storage-mode." {:storage-mode storage-mode}))
  (when-not (#{:drop-and-log :throw} on-series-limit)
    (raise! "Unsupported :on-series-limit policy." {:on-series-limit on-series-limit}))
  (when-not (#{:throw :drop-and-log} on-invalid-number)
    (raise! "Unsupported :on-invalid-number policy." {:on-invalid-number on-invalid-number}))
  (when-not (or (nil? max-series-per-metric)
                (and (int? max-series-per-metric) (pos? max-series-per-metric)))
    (raise! ":max-series-per-metric must be nil or positive int."
            {:max-series-per-metric max-series-per-metric}))
  {:state (atom {:metrics {}})
   :internal-counters (atom {:dropped-samples-total 0
                             :series-limit-hits-total 0})
   :single-series (atom {})
   :series-atoms (atom {})
   :render-cache (atom {:ts-ms 0 :body nil})
   :logger logger
   :strict-labels? strict-labels?
   :max-series-per-metric max-series-per-metric
   :on-series-limit on-series-limit
   :allowed-label-values allowed-label-values
   :render-cache-ttl-ms render-cache-ttl-ms
   :storage-mode storage-mode
   :on-invalid-number on-invalid-number})

(defn- normalize-labels
  [registry metric-id labels-spec labels]
  (let [labels (or labels {})
        required (set labels-spec)
        provided (if (map? labels) (set (keys labels)) ::invalid)
        allowed-values (:allowed-label-values registry)]
    (cond
      (not (map? labels))
      (if (:strict-labels? registry)
        (raise! "Labels must be a map." {:metric-id metric-id :labels labels :labels-spec labels-spec})
        (do
          (record-drop! registry {:metric-id metric-id
                                  :reason :labels-not-map
                                  :labels labels
                                  :labels-spec labels-spec})
          ::invalid))

      (not= required provided)
      (if (:strict-labels? registry)
        (raise! "Labels mismatch metric label specification."
                {:metric-id metric-id :labels labels :labels-spec labels-spec})
        (do
          (record-drop! registry {:metric-id metric-id
                                  :reason :labels-spec-mismatch
                                  :labels labels
                                  :labels-spec labels-spec})
          ::invalid))

      :else
      (let [disallowed (some (fn [k]
                               (when-let [allowed (get allowed-values k)]
                                 (let [sv (str (get labels k))]
                                   (when-not (contains? allowed sv)
                                     {:label k :value sv :allowed allowed}))))
                             labels-spec)]
        (if disallowed
          (if (:strict-labels? registry)
            (raise! "Label value is not allowed by whitelist."
                    {:metric-id metric-id
                     :labels labels
                     :labels-spec labels-spec
                     :disallowed disallowed})
            (do
              (record-drop! registry {:metric-id metric-id
                                      :reason :label-value-not-allowed
                                      :labels labels
                                      :labels-spec labels-spec
                                      :disallowed disallowed})
              ::invalid))
          (mapv #(str (get labels %)) labels-spec))))))

(defn- default-buckets
  []
  [5.0 10.0 25.0 50.0 100.0 250.0 500.0 1000.0 2500.0])

(defn- validate-buckets!
  [buckets]
  (when-not (and (vector? buckets) (seq buckets))
    (raise! "Histogram buckets must be a non-empty vector." {:buckets buckets}))
  (when-not (every? number? buckets)
    (raise! "Histogram buckets must contain only numbers." {:buckets buckets}))
  (when-not (apply < buckets)
    (raise! "Histogram buckets must be strictly increasing." {:buckets buckets})))

(defn- metric-handle
  [registry metric-id]
  {:registry registry :metric-id metric-id})

(defn- series-map
  [registry metric-id]
  (case (:storage-mode registry)
    :single-atom (get @(:single-series registry) metric-id {})
    :per-metric-atom (if-let [a (get @(:series-atoms registry) metric-id)]
                       @a
                       {})))

(defn- update-series!
  [registry metric-id label-key update-fn]
  (let [max-series (:max-series-per-metric registry)]
    (case (:storage-mode registry)
      :single-atom
      (let [result (volatile! :updated)]
        (swap! (:single-series registry)
               (fn [all]
                 (let [series (get all metric-id {})
                       exists? (contains? series label-key)
                       limit-hit? (and (not exists?)
                                       (int? max-series)
                                       (>= (count series) max-series))]
                   (if limit-hit?
                     (do
                       (vreset! result :limited)
                       all)
                     (let [new-value (update-fn (get series label-key))
                           new-series (assoc series label-key new-value)]
                       (vreset! result :updated)
                       (assoc all metric-id new-series))))))
        @result)

      :per-metric-atom
      (let [metric-atom (get @(:series-atoms registry) metric-id)]
        (when-not metric-atom
          (raise! "Metric storage atom not found." {:metric-id metric-id}))
        (let [result (volatile! :updated)]
          (swap! metric-atom
                 (fn [series]
                   (let [series (or series {})
                         exists? (contains? series label-key)
                         limit-hit? (and (not exists?)
                                         (int? max-series)
                                         (>= (count series) max-series))]
                     (if limit-hit?
                       (do
                         (vreset! result :limited)
                         series)
                       (let [new-value (update-fn (get series label-key))]
                         (vreset! result :updated)
                         (assoc series label-key new-value))))))
          @result)))))

(defn- handle-series-limit!
  [registry metric-id label-key]
  (case (:on-series-limit registry)
    :throw
    (raise! "Series limit exceeded for metric."
            {:metric-id metric-id
             :label-key label-key
             :max-series-per-metric (:max-series-per-metric registry)})

    :drop-and-log
    (do
      (record-series-limit! registry {:metric-id metric-id
                                      :label-key label-key
                                      :max-series-per-metric (:max-series-per-metric registry)})
      (record-drop! registry {:metric-id metric-id
                              :reason :series-limit
                              :label-key label-key}))

    (raise! "Unsupported :on-series-limit policy." {:on-series-limit (:on-series-limit registry)})))

(defn- ensure-valid-number!
  [registry metric-id op-key value labels]
  (if (finite-number? value)
    true
    (case (:on-invalid-number registry)
      :throw
      (raise! "Metric operation received non-finite number."
              {:metric-id metric-id :op op-key :value value :labels labels})

      :drop-and-log
      (do
        (record-drop! registry {:metric-id metric-id
                                :reason :invalid-number
                                :op op-key
                                :value value
                                :labels labels})
        false)

      (raise! "Unsupported :on-invalid-number policy."
              {:on-invalid-number (:on-invalid-number registry)}))))

(defn- register-metric!
  [registry metric-id metric-type opts]
  (let [name (or (:name opts) (metric-name metric-id))
        labels (vec (or (:labels opts) []))
        help (or (:help opts) (str "Metric " name))
        buckets (or (:buckets opts) (default-buckets))
        metric-def (cond-> {:id metric-id
                            :name name
                            :type metric-type
                            :help help
                            :labels labels}
                     (= metric-type :histogram) (assoc :buckets buckets))]
    (when-not (keyword? metric-id)
      (raise! "metric-id must be a keyword." {:metric-id metric-id}))
    (when-not (every? keyword? labels)
      (raise! "Metric labels must be keywords." {:labels labels :metric-id metric-id}))
    (when (= metric-type :histogram)
      (validate-buckets! buckets))
    (let [new? (atom false)]
      (swap! (:state registry)
             (fn [{:keys [metrics] :as state}]
               (if-let [existing (get metrics metric-id)]
                 (let [existing-cfg (select-keys existing [:name :type :labels :buckets])
                       new-cfg (select-keys metric-def [:name :type :labels :buckets])]
                   (if (= existing-cfg new-cfg)
                     state
                     (raise! "Metric already registered with incompatible definition."
                             {:metric-id metric-id
                              :existing existing-cfg
                              :new new-cfg})))
                 (do
                   (reset! new? true)
                   (assoc state :metrics (assoc metrics metric-id metric-def))))))
      (when (and @new? (= :per-metric-atom (:storage-mode registry)))
        (swap! (:series-atoms registry)
               (fn [m]
                 (if (contains? m metric-id)
                   m
                   (assoc m metric-id (atom {}))))))
      (when @new?
        (invalidate-render-cache! registry)))
    (metric-handle registry metric-id)))

(defn counter!
  "Registers (or returns) counter metric handle."
  [registry metric-id opts]
  (register-metric! registry metric-id :counter opts))

(defn gauge!
  "Registers (or returns) gauge metric handle."
  [registry metric-id opts]
  (register-metric! registry metric-id :gauge opts))

(defn histogram!
  "Registers (or returns) histogram metric handle."
  [registry metric-id opts]
  (register-metric! registry metric-id :histogram opts))

(defn- get-metric
  [metric]
  (let [{:keys [registry metric-id]} metric
        metric-def (get-in @(:state registry) [:metrics metric-id])]
    (when-not metric-def
      (raise! "Metric handle points to unknown metric." {:metric-id metric-id}))
    metric-def))

(defn inc!
  "Increments counter by n (default 1)."
  ([metric]
   (inc! metric 1.0 {}))
  ([metric n labels]
   (let [{:keys [registry metric-id]} metric
         metric-def (get-metric metric)]
     (when-not (= :counter (:type metric-def))
       (raise! "inc! can only be used with counter metrics."
               {:metric-id metric-id :type (:type metric-def)}))
     (when (neg? (double n))
       (raise! "Counter increment must be non-negative." {:metric-id metric-id :n n}))
     (when (ensure-valid-number! registry metric-id :inc n labels)
       (let [label-key (normalize-labels registry metric-id (:labels metric-def) labels)]
         (when-not (= ::invalid label-key)
           (let [outcome (update-series! registry metric-id label-key (fnil #(+ (double %) (double n)) 0.0))]
             (if (= :limited outcome)
               (handle-series-limit! registry metric-id label-key)
               (invalidate-render-cache! registry))))))
     metric)))

(defn set!
  "Sets gauge value."
  [metric value labels]
  (let [{:keys [registry metric-id]} metric
        metric-def (get-metric metric)]
    (when-not (= :gauge (:type metric-def))
      (raise! "set! can only be used with gauge metrics."
              {:metric-id metric-id :type (:type metric-def)}))
    (when (ensure-valid-number! registry metric-id :set value labels)
      (let [label-key (normalize-labels registry metric-id (:labels metric-def) labels)]
        (when-not (= ::invalid label-key)
          (let [outcome (update-series! registry metric-id label-key (constantly (double value)))]
            (if (= :limited outcome)
              (handle-series-limit! registry metric-id label-key)
              (invalidate-render-cache! registry))))))
    metric))

(defn- histogram-series-default
  [bucket-count]
  {:sum 0.0
   :count 0
   :bucket-counts (vec (repeat bucket-count 0))})

(defn- bucket-index
  [buckets value]
  (loop [idx 0]
    (cond
      (= idx (count buckets)) idx
      (<= value (nth buckets idx)) idx
      :else (recur (inc idx)))))

(defn observe!
  "Observes value in histogram."
  [metric value labels]
  (let [{:keys [registry metric-id]} metric
        metric-def (get-metric metric)]
    (when-not (= :histogram (:type metric-def))
      (raise! "observe! can only be used with histogram metrics."
              {:metric-id metric-id :type (:type metric-def)}))
    (when (ensure-valid-number! registry metric-id :observe value labels)
      (let [label-key (normalize-labels registry metric-id (:labels metric-def) labels)]
        (when-not (= ::invalid label-key)
          (let [outcome
                (update-series! registry metric-id label-key
                                (fn [series]
                                  (let [bucket-count (inc (count (:buckets metric-def)))
                                        idx (bucket-index (:buckets metric-def) (double value))
                                        {:keys [sum count bucket-counts]} (or series (histogram-series-default bucket-count))]
                                    {:sum (+ sum (double value))
                                     :count (inc count)
                                     :bucket-counts (update bucket-counts idx inc)})))]
            (if (= :limited outcome)
              (handle-series-limit! registry metric-id label-key)
              (invalidate-render-cache! registry))))))
    metric))

(defmacro with-timing
  "Executes body, records elapsed time in ms into histogram metric."
  [hist labels & body]
  `(let [start-ns# (System/nanoTime)]
     (try
       ~@body
       (finally
         (observe! ~hist (/ (double (- (System/nanoTime) start-ns#)) 1000000.0) ~labels)))))

(defn- render-labels
  [labels-spec label-values]
  (if (empty? labels-spec)
    ""
    (str "{" (str/join ","
                       (map (fn [k v]
                              (str (label-name k) "=" (pr-str (str v))))
                            labels-spec
                            label-values))
         "}")))

(defn- render-number
  [n]
  (if (integer? n)
    (str n)
    (str (double n))))

(defn- append-line!
  [^StringBuilder sb line]
  (.append sb line)
  (.append sb "
")
  sb)

(defn- append-help-and-type!
  [^StringBuilder sb metric-name metric-type help]
  (append-line! sb (str "# HELP " metric-name " " help))
  (append-line! sb (str "# TYPE " metric-name " " metric-type))
  sb)

(defn- append-counter-or-gauge!
  [^StringBuilder sb metric-name labels series]
  (doseq [[label-values value] (sort-by key series)]
    (append-line! sb (str metric-name (render-labels labels label-values) " " (render-number value))))
  sb)

(defn- append-histogram!
  [^StringBuilder sb metric-name labels buckets series]
  (doseq [[label-values {:keys [bucket-counts sum count]}] (sort-by key series)]
    (let [cumulative (reductions + bucket-counts)
          base-labels (map vector labels label-values)]
      (doseq [[idx c] (map-indexed vector cumulative)]
        (let [le (if (= idx (clojure.core/count buckets)) "+Inf" (render-number (nth buckets idx)))
              all-labels (conj (vec base-labels) [:le le])
              names (map first all-labels)
              values (map second all-labels)]
          (append-line! sb (str metric-name "_bucket" (render-labels names values) " " c))))
      (append-line! sb (str metric-name "_sum" (render-labels labels label-values) " " (render-number sum)))
      (append-line! sb (str metric-name "_count" (render-labels labels label-values) " " count))))
  sb)

(defn- render-prometheus-now
  [registry]
  (let [metrics (-> @(:state registry) :metrics vals)
        sorted-metrics (sort-by :name metrics)
        counters @(:internal-counters registry)
        sb (StringBuilder.)]
    (doseq [metric sorted-metrics]
      (let [{:keys [id name type help labels buckets]} metric
            series (series-map registry id)]
        (append-help-and-type! sb name (clojure.core/name type) help)
        (case type
          (:counter :gauge) (append-counter-or-gauge! sb name labels series)
          :histogram (append-histogram! sb name labels buckets series))))

    (append-help-and-type! sb "lcmm_observe_dropped_samples_total" "counter" "Dropped samples in lcmm-observe")
    (append-line! sb (str "lcmm_observe_dropped_samples_total " (get counters :dropped-samples-total 0)))

    (append-help-and-type! sb "lcmm_observe_series_limit_hits_total" "counter" "Series limit hits in lcmm-observe")
    (append-line! sb (str "lcmm_observe_series_limit_hits_total " (get counters :series-limit-hits-total 0)))

    (str sb)))

(defn render-prometheus
  "Renders full registry in Prometheus text exposition format."
  [registry]
  (let [ttl (long (or (:render-cache-ttl-ms registry) 0))]
    (if (<= ttl 0)
      (render-prometheus-now registry)
      (let [{:keys [ts-ms body]} @(:render-cache registry)
            now (System/currentTimeMillis)]
        (if (and body (< (- now ts-ms) ttl))
          body
          (let [rendered (render-prometheus-now registry)]
            (reset! (:render-cache registry) {:ts-ms now :body rendered})
            rendered))))))

(defn metrics-handler
  "Returns Ring handler serving /metrics output."
  [registry]
  (fn [_request]
    {:status 200
     :headers {"content-type" "text/plain; version=0.0.4; charset=utf-8"}
     :body (render-prometheus registry)}))
