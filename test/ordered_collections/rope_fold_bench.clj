(ns ordered-collections.rope-fold-bench
  "Dedicated benchmark for rope fold strategies.

   Compares:
   - rope reduce
   - current tree-native rope fold
   - prior split-based rope fold
   - vector reduce
   - vector fold

   Usage:
     lein bench-rope-fold
     lein bench-rope-fold --quick"
  (:require [clojure.core.reducers :as r]
            [ordered-collections.bench-utils :as bu :refer [has-flag?]]
            [ordered-collections.core :as oc]
            [ordered-collections.parallel :as par]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Infrastructure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-op
  [f warmup-iters bench-iters]
  (dotimes [_ warmup-iters] (f))
  (let [start (System/nanoTime)]
    (dotimes [_ bench-iters] (f))
    (/ (- (System/nanoTime) start) (* bench-iters 1000.0))))

(defn bench-op-samples
  [f warmup-iters bench-iters sample-runs]
  (let [samples (vec (repeatedly sample-runs #(bench-op f warmup-iters bench-iters)))
        sorted  (sort samples)
        n       (count sorted)]
    {:median  (nth sorted (quot n 2))
     :mean    (/ (reduce + sorted) n)
     :low     (first sorted)
     :high    (last sorted)
     :samples samples}))

(defn- fmt-us [stats]
  (bu/format-ns (* 1000 (:median stats))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workloads
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- split-based-fold
  "Previous Rope CollFold strategy, retained here for benchmark comparison."
  [coll ^long n combinef reducef]
  (letfn [(fold* [child]
            (let [csz (count child)]
              (if (<= csz n)
                (reduce reducef (combinef) child)
                (let [cmid   (quot csz 2)
                      [cl cr] (oc/rope-split child cmid)]
                  (par/fork-join
                    [lv (fold* cl) rv (fold* cr)]
                    (combinef lv rv))))))]
    (if (par/in-fork-join-pool?)
      (fold* coll)
      (par/invoke-root #(fold* coll)))))

(defn- freq-combine
  ([] {})
  ([m1 m2] (merge-with + m1 m2)))

(defn- freq-reduce [m x]
  (update m (mod (long x) 100) (fnil inc 0)))

(def workloads
  [{:key :sum
    :label "sum"
    :combinef +
    :reducef +}
   {:key :into
    :label "into"
    :combinef into
    :reducef conj}
   {:key :freq
    :label "freq"
    :combinef freq-combine
    :reducef freq-reduce}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bench
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-sizes [100000 500000 1000000])
(def quick-sizes [100000 500000])
(def default-thresholds [1024 4096 16384 65536])
(def quick-thresholds [1024 16384])

(defn bench-fold-workload
  [n threshold {:keys [combinef reducef]} opts]
  (let [data (range n)
        r    (oc/rope data)
        v    (vec data)]
    {:rope-reduce    (bench-op-samples
                       #(reduce reducef (combinef) r)
                       (:warmup opts) (:iters opts) (:samples opts))
     :rope-fold      (bench-op-samples
                       #(r/fold threshold combinef reducef r)
                       (:warmup opts) (:iters opts) (:samples opts))
     :rope-fold-old  (bench-op-samples
                       #(split-based-fold r threshold combinef reducef)
                       (:warmup opts) (:iters opts) (:samples opts))
     :vector-reduce  (bench-op-samples
                       #(reduce reducef (combinef) v)
                       (:warmup opts) (:iters opts) (:samples opts))
     :vector-fold    (bench-op-samples
                       #(r/fold threshold combinef reducef v)
                       (:warmup opts) (:iters opts) (:samples opts))}))

(defn- print-results
  [results]
  (println)
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (println "  ROPE FOLD BENCHMARK")
  (println "  Median time for each workload / threshold")
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (doseq [[wk rs] (sort-by key (group-by :workload results))]
    (println)
    (println (format "  Workload: %s" (name wk)))
    (doseq [[n size-results] (sort-by key (group-by :n rs))]
      (println)
      (printf "  N = %,d%n" n)
      (println "  ┌───────────┬────────────┬────────────┬──────────────┬──────────────┬─────────────┐")
      (println "  │ threshold │ rope fold  │ rope old   │ rope reduce  │ vector fold  │ vector red. │")
      (println "  ├───────────┼────────────┼────────────┼──────────────┼──────────────┼─────────────┤")
      (doseq [{:keys [threshold bench]} (sort-by :threshold size-results)]
        (printf "  │ %7d   │ %10s │ %10s │ %12s │ %12s │ %11s │%n"
                threshold
                (fmt-us (:rope-fold bench))
                (fmt-us (:rope-fold-old bench))
                (fmt-us (:rope-reduce bench))
                (fmt-us (:vector-fold bench))
                (fmt-us (:vector-reduce bench))))
      (println "  └───────────┴────────────┴────────────┴──────────────┴──────────────┴─────────────┘"))))

(defn run-benchmark
  [& {:keys [sizes thresholds warmup iters samples]
      :or {sizes default-sizes
           thresholds default-thresholds
           warmup 10
           iters 30
           samples 7}}]
  (let [opts {:warmup warmup :iters iters :samples samples}]
    (println "Rope fold benchmark")
    (println "Sizes:" sizes)
    (println "Thresholds:" thresholds)
    (println "Workloads:" (mapv :label workloads))
    (println (format "Per-measurement: %d warmup, %d iters, %d samples" warmup iters samples))
    (println)
    (let [results
          (vec
            (for [{:keys [key] :as workload} workloads
                  n sizes
                  threshold thresholds]
              (do
                (print (format "  workload=%s N=%,d threshold=%d " (name key) n threshold))
                (flush)
                (let [bench (bench-fold-workload n threshold workload opts)]
                  (println)
                  {:workload key
                   :n n
                   :threshold threshold
                   :bench bench}))))]
      (print-results results)
      results)))

(defn quick-bench []
  (run-benchmark :sizes quick-sizes
                 :thresholds quick-thresholds
                 :warmup 5 :iters 15 :samples 5))

(defn -main [& args]
  (if (has-flag? args "--quick" "-q")
    (quick-bench)
    (run-benchmark))
  (shutdown-agents))
