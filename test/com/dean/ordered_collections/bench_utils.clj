(ns com.dean.ordered-collections.bench-utils
  "Shared benchmarking infrastructure.

   Provides:
   1. Simple bench macro for quick iteration (no external deps)
   2. Test data generators
   3. Shared benchmark workload/build helpers
   4. Table printing utilities
   5. CLI argument parsing helpers

   Usage:
     (:require [com.dean.ordered-collections.bench-utils :as bu])"
  (:require [clojure.data.avl :as avl]
            [clojure.string :as str]
            [com.dean.ordered-collections.core :as oc]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Time Formatting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-ns
  "Format nanoseconds as human-readable string."
  [ns]
  (cond
    (>= ns 1e9) (format "%.2fs" (/ ns 1e9))
    (>= ns 1e6) (format "%.2fms" (/ ns 1e6))
    (>= ns 1e3) (format "%.1fµs" (/ ns 1e3))
    :else       (format "%dns" (long ns))))

(defn format-result
  "Format [mean std] pair as string."
  [[mean std]]
  (str (format-ns mean) " ± " (format-ns std)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Simple Benchmarking (no external deps)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro bench
  "Run body warmup-n times, then measure-n times, return [mean-ns std-ns].

   For accurate results, warmup-n should be at least 15-20 to allow
   JIT compilation. Lower values will show artificially slow times."
  [warmup-n measure-n & body]
  `(do
     (dotimes [_# ~warmup-n] ~@body)
     (System/gc)
     (Thread/sleep 100)
     (let [times# (long-array ~measure-n)]
       (dotimes [i# ~measure-n]
         (let [t0# (System/nanoTime)
               _#  ~@body
               t1# (System/nanoTime)]
           (aset times# i# (- t1# t0#))))
       (let [n#    (alength times#)
             sum#  (areduce times# i# acc# 0.0 (+ acc# (aget times# i#)))
             mean# (/ sum# n#)
             var#  (areduce times# i# acc# 0.0
                     (let [d# (- (aget times# i#) mean#)]
                       (+ acc# (* d# d#))))
             std#  (Math/sqrt (/ var# n#))]
         [(long mean#) (long std#)]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Data Generation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate-pairs
  "Generate n random [k v] pairs with integer keys."
  [n]
  (mapv (fn [k] [k (str "value-" k)]) (shuffle (range n))))

(defn generate-elements
  "Generate n shuffled integers [0, n)."
  [n]
  (vec (shuffle (range n))))

(defn generate-lookup-keys
  "Generate int-array of num-lookups random keys in [0, n)."
  ^ints [n num-lookups]
  (int-array (repeatedly num-lookups #(rand-int n))))

(defn generate-string-keys
  "Generate n formatted string keys."
  [n]
  (mapv #(format "key-%08d" %) (shuffle (range n))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared Benchmark Workloads
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn build-map-variants
  "Build the standard map competitors for the given key/value pairs."
  [pairs]
  {:sorted-map  (into (sorted-map) pairs)
   :data-avl    (into (avl/sorted-map) pairs)
   :ordered-map (oc/ordered-map pairs)})

(defn build-set-variants
  "Build the standard set competitors for the given elements."
  [elems]
  {:hash-set    (set elems)
   :sorted-set  (into (sorted-set) elems)
   :data-avl    (into (avl/sorted-set) elems)
   :ordered-set (oc/ordered-set elems)})

(defn build-string-map-variants
  "Build the standard string-key map competitors for [k v] pairs."
  [pairs cmp ordered-cmp]
  {:sorted-map-by (into (sorted-map-by cmp) pairs)
   :data-avl      (into (avl/sorted-map-by cmp) pairs)
   :ordered-map   (oc/ordered-map-with ordered-cmp pairs)})

(defn overlapping-set-elements
  "Generate two element ranges of size n with 50% overlap."
  [n]
  [(range 0 n)
   (range (quot n 2) (+ n (quot n 2)))])

(defn overlapping-set-variants
  "Build the standard set competitors for the 50%-overlap set-op workload."
  [n]
  (let [[elems1 elems2] (overlapping-set-elements n)]
    {:left  (build-set-variants elems1)
     :right (build-set-variants elems2)}))

(defn split-workload
  "Build the standard split benchmark workload."
  [n num-ops]
  (let [elems (generate-elements n)
        sets  (build-set-variants elems)]
    {:data-avl (:data-avl sets)
     :ordered-set (:ordered-set sets)
     :keys (generate-lookup-keys n num-ops)}))

(defn fold-frequency-workload
  "Build the nontrivial frequency-map fold workload and its reducers."
  [elems]
  {:sets     (build-set-variants elems)
   :combinef (fn ([] {}) ([m1 m2] (merge-with + m1 m2)))
   :reducef  (fn [m x] (update m (mod (long x) 100) (fnil inc 0)))})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Table Printing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn print-header
  "Print benchmark header with title and column names."
  [title cols]
  (println)
  (println (str "=== " title " ==="))
  (println (apply format (str "%-10s" (apply str (repeat (count cols) " %-20s"))) "N" cols))
  (println (apply str (repeat (+ 10 (* 21 (count cols))) "-"))))

(defn print-row
  "Print benchmark row with N and results."
  [n results]
  (println (apply format (str "%-10d" (apply str (repeat (count results) " %-20s")))
                  n (map format-result results))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLI Argument Parsing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn has-flag?
  "Check if args contains any of the given flags."
  [args & flags]
  (boolean (some (set flags) args)))

(defn parse-sizes
  "Parse comma-separated sizes string into vector of longs."
  [s]
  (mapv #(Long/parseLong (str/trim %)) (str/split s #",")))

(defn get-arg-value
  "Get the value following an argument flag, or nil if not present."
  [args flag]
  (when-let [idx (some #(when (= (nth args %) flag) %) (range (dec (count args))))]
    (nth args (inc idx))))

(defn parse-standard-args
  "Parse standard benchmark arguments: --quick, --full, -q, --sizes, --help.
   Returns map with :sizes (vector), :quick (bool), :help (bool).

   Arguments:
     args         - command line arguments
     sizes-quick  - sizes for --quick mode
     sizes-default - default sizes
     sizes-full   - sizes for --full mode"
  [args sizes-quick sizes-default sizes-full]
  (let [help?  (has-flag? args "--help" "-h")
        quick? (has-flag? args "--quick" "-q")
        full?  (has-flag? args "--full")
        custom (get-arg-value (vec args) "--sizes")]
    {:help  help?
     :quick quick?
     :sizes (cond
              custom (parse-sizes custom)
              quick? sizes-quick
              full?  sizes-full
              :else  sizes-default)}))
