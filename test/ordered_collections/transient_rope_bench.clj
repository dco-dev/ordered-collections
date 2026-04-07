(ns ordered-collections.transient-rope-bench
  "Dedicated benchmark for rope construction strategies.

   Focus:
   - small ropes must not regress
   - large append-heavy builds should improve

   Compares:
   - current TransientRope
   - direct `(oc/rope coll)`
   - old-style transient-like flush-via-concat builder
   - repeated rope-concat assembly from chunks"
  (:require [ordered-collections.bench-utils :as bu :refer [has-flag?]]
            [ordered-collections.core :as oc]
            [ordered-collections.kernel.rope :as ropetree]))


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
;; Builders
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- old-transient-build
  [base coll]
  (let [target ropetree/+target-chunk-size+]
    (loop [root (.-root ^ordered_collections.types.rope.Rope (oc/rope base))
           tail []
           xs   (seq coll)]
      (if-let [s xs]
        (let [tail' (conj tail (first s))]
          (if (= (count tail') target)
            (recur (ropetree/rope-concat root (ropetree/chunks->root [tail']))
                   []
                   (next s))
            (recur root tail' (next s))))
        (if (seq tail)
          (ropetree/rope-concat root (ropetree/chunks->root [tail]))
          root)))))

(defn- concat-build
  [base coll]
  (let [target ropetree/+target-chunk-size+
        parts  (map oc/rope (partition-all target coll))]
    (reduce oc/rope-concat (oc/rope base) parts)))

(defn- transient-build
  [base coll]
  (persistent! (reduce conj! (transient (oc/rope base)) coll)))

(defn- force-old-build
  [base coll]
  (ropetree/rope-size (old-transient-build base coll)))

(defn- force-rope
  [r]
  (count r))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bench
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-sizes [64 256 4096 65536 1000000])
(def quick-sizes [64 256 4096 65536])

(defn- bench-size
  [n opts]
  (let [base-size (quot n 4)
        append-n  n
        base      (range base-size)
        append    (range base-size (+ base-size append-n))]
    {:n n
     :empty
     {:transient (bench-op-samples #(force-rope (transient-build [] (range n)))
                   (:warmup opts) (:iters opts) (:samples opts))
      :direct    (bench-op-samples #(force-rope (oc/rope (range n)))
                   (:warmup opts) (:iters opts) (:samples opts))
      :old       (bench-op-samples #(force-old-build [] (range n))
                   (:warmup opts) (:iters opts) (:samples opts))
      :concat    (bench-op-samples #(force-rope (concat-build [] (range n)))
                   (:warmup opts) (:iters opts) (:samples opts))}
     :append
     {:transient (bench-op-samples #(force-rope (transient-build base append))
                   (:warmup opts) (:iters opts) (:samples opts))
      :direct    (bench-op-samples #(force-rope (oc/rope (concat base append)))
                   (:warmup opts) (:iters opts) (:samples opts))
      :old       (bench-op-samples #(force-old-build base append)
                   (:warmup opts) (:iters opts) (:samples opts))
      :concat    (bench-op-samples #(force-rope (concat-build base append))
                   (:warmup opts) (:iters opts) (:samples opts))}}))

(defn- print-table
  [title rows k]
  (println)
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (println (str "  " title))
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (println "  ┌──────────┬────────────┬────────────┬────────────┬────────────┐")
  (println "  │ N        │ transient  │ old flush  │ direct     │ concat     │")
  (println "  ├──────────┼────────────┼────────────┼────────────┼────────────┤")
  (doseq [{:keys [n] :as row} rows]
    (let [r (k row)]
      (printf "  │ %7d  │ %10s │ %10s │ %10s │ %10s │%n"
              n
              (fmt-us (:transient r))
              (fmt-us (:old r))
              (fmt-us (:direct r))
              (fmt-us (:concat r)))))
  (println "  └──────────┴────────────┴────────────┴────────────┴────────────┘"))

(defn run-benchmark
  [& {:keys [sizes warmup iters samples]
      :or {sizes default-sizes
           warmup 10
           iters 30
           samples 7}}]
  (let [opts {:warmup warmup :iters iters :samples samples}]
    (println "Transient rope benchmark")
    (println "Sizes:" sizes)
    (println (format "Per-measurement: %d warmup, %d iters, %d samples" warmup iters samples))
    (println)
    (let [rows (vec (for [n sizes]
                      (do
                        (print (format "  N=%,d " n))
                        (flush)
                        (let [row (bench-size n opts)]
                          (println)
                          row))))]
      (print-table "Build From Empty" rows :empty)
      (print-table "Append To Existing Rope" rows :append)
      rows)))

(defn quick-bench []
  (run-benchmark :sizes quick-sizes
                 :warmup 5 :iters 15 :samples 5))

(defn -main [& args]
  (if (has-flag? args "--quick" "-q")
    (quick-bench)
    (run-benchmark))
  (shutdown-agents))
