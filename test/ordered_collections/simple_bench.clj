(ns ordered-collections.simple-bench
  "Simple benchmark suite without Criterium dependency.

   For quick iteration during development. Uses basic timing with
   manual warmup. For rigorous benchmarks with EDN output, use
   lein bench instead.

   Usage:
     lein bench-simple                    ; Default (100 to 100K)
     lein bench-simple --quick            ; Fast iteration (100 to 10K)
     lein bench-simple --full             ; Full suite (100 to 1M)
     lein bench-simple --sizes 1000,10000 ; Custom sizes
     lein bench-simple --only sets        ; Run only set benchmarks
     lein bench-simple --only maps,sets   ; Run maps and sets

   Categories for --only:
     maps, sets, set-ops, intervals, specialty, strings, parallel, memory,
     rope, string-rope, byte-rope"
  (:require [clojure.core.reducers :as r]
            [clojure.data.avl :as avl]
            [clojure.string :as str]
            [ordered-collections.bench-utils :as bu
             :refer [bench format-ns format-result print-header print-row
                     has-flag? get-arg-value parse-sizes parse-standard-args
                     build-map-variants build-set-variants overlapping-set-variants
                     split-workload fold-frequency-workload
                     set-comparison-workload]]
            [ordered-collections.core :as core]
            [ordered-collections.kernel.node :as node]
            [ordered-collections.kernel.tree :as tree]
            [ordered-collections.kernel.order :as order]
            [ordered-collections.kernel.interval :as interval]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Map Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-map-construction
  "Benchmark building a map from N random key-value pairs."
  [sizes]
  (print-header "MAP CONSTRUCTION: Build from N random key-value pairs"
                ["sorted-map" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [pairs (mapv (fn [k] [k (str k)]) (shuffle (range n)))]
      (print-row n
        [(bench 20 10 (into (sorted-map) pairs))
         (bench 20 10 (into (avl/sorted-map) pairs))
         (bench 20 10 (core/ordered-map pairs))]))))

(defn bench-map-incremental-insert
  "Benchmark assoc one element at a time from empty."
  [sizes]
  (print-header "MAP INSERT: assoc one element at a time from empty"
                ["sorted-map" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [ks (shuffle (range n))]
      (print-row n
        [(bench 20 10
           (loop [m (sorted-map) xs (seq ks)]
             (if xs (recur (assoc m (first xs) true) (next xs)) m)))
         (bench 20 10
           (loop [m (avl/sorted-map) xs (seq ks)]
             (if xs (recur (assoc m (first xs) true) (next xs)) m)))
         (bench 20 10
           (loop [m (core/ordered-map) xs (seq ks)]
             (if xs (recur (assoc m (first xs) true) (next xs)) m)))]))))

(defn bench-map-incremental-delete
  "Benchmark dissoc half the elements one at a time."
  [sizes]
  (print-header "MAP DELETE: dissoc half the elements one at a time"
                ["sorted-map" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [pairs  (map #(vector % true) (range n))
          to-del (vec (take (quot n 2) (shuffle (range n))))
          sm     (into (sorted-map) pairs)
          am     (into (avl/sorted-map) pairs)
          om     (core/ordered-map pairs)]
      (print-row n
        [(bench 20 10 (reduce (fn [m k] (dissoc m k)) sm to-del))
         (bench 20 10 (reduce (fn [m k] (dissoc m k)) am to-del))
         (bench 20 10 (reduce (fn [m k] (dissoc m k)) om to-del))]))))

(defn bench-map-lookup
  "Benchmark 10,000 random lookups on a map of size N."
  [sizes]
  (print-header "MAP LOOKUP: 10,000 random lookups on map of size N"
                ["sorted-map" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [pairs (mapv (fn [k] [k (str k)]) (shuffle (range n)))
          {:keys [sorted-map data-avl ordered-map]} (build-map-variants pairs)
          ks    (int-array (repeatedly 10000 #(rand-int n)))]
      (print-row n
        [(bench 20 10 (dotimes [i 10000] (get sorted-map (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (get data-avl (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (ordered-map (aget ks i))))]))))

(defn bench-map-iteration
  "Benchmark traversing all N entries via reduce."
  [sizes]
  (print-header "MAP ITERATION: reduce over all N entries"
                ["sorted-map" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [pairs (mapv (fn [k] [k (str k)]) (shuffle (range n)))
          sm    (into (sorted-map) pairs)
          am    (into (avl/sorted-map) pairs)
          om    (core/ordered-map pairs)]
      (print-row n
        [(bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 sm))
         (bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 am))
         (bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 om))]))))

(defn bench-map-seq-iteration
  "Benchmark traversing all N entries via seq (lazy)."
  [sizes]
  (print-header "MAP SEQ ITERATION: traverse via (seq m)"
                ["sorted-map" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [pairs (mapv (fn [k] [k (str k)]) (shuffle (range n)))
          sm    (into (sorted-map) pairs)
          am    (into (avl/sorted-map) pairs)
          om    (core/ordered-map pairs)]
      (print-row n
        [(bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 (seq sm)))
         (bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 (seq am)))
         (bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 (seq om)))]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-set-construction
  "Benchmark building a set from N random elements."
  [sizes]
  (print-header "SET CONSTRUCTION: Build from N random elements"
                ["sorted-set" "data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))]
      (print-row n
        [(bench 20 10 (into (sorted-set) elems))
         (bench 20 10 (into (avl/sorted-set) elems))
         (bench 20 10 (core/ordered-set elems))]))))

(defn bench-set-incremental-insert
  "Benchmark conj one element at a time from empty."
  [sizes]
  (print-header "SET INSERT: conj one element at a time from empty"
                ["sorted-set" "data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))]
      (print-row n
        [(bench 20 10
           (loop [s (sorted-set) xs (seq elems)]
             (if xs (recur (conj s (first xs)) (next xs)) s)))
         (bench 20 10
           (loop [s (avl/sorted-set) xs (seq elems)]
             (if xs (recur (conj s (first xs)) (next xs)) s)))
         (bench 20 10
           (loop [s (core/ordered-set) xs (seq elems)]
             (if xs (recur (conj s (first xs)) (next xs)) s)))]))))

(defn bench-set-incremental-delete
  "Benchmark disj half the elements one at a time."
  [sizes]
  (print-header "SET DELETE: disj half the elements one at a time"
                ["sorted-set" "data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems  (range n)
          to-del (take (quot n 2) (shuffle (range n)))
          ss     (into (sorted-set) elems)
          as     (into (avl/sorted-set) elems)
          os     (core/ordered-set elems)]
      (print-row n
        [(bench 20 10 (reduce (fn [s x] (disj s x)) ss to-del))
         (bench 20 10 (reduce (fn [s x] (disj s x)) as to-del))
         (bench 20 10 (reduce (fn [s x] (disj s x)) os to-del))]))))

(defn bench-set-lookup
  "Benchmark 10,000 random contains? checks on a set of size N."
  [sizes]
  (print-header "SET LOOKUP: 10,000 random contains? checks"
                ["sorted-set" "data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          {:keys [sorted-set data-avl ordered-set]} (build-set-variants elems)
          ks    (int-array (repeatedly 10000 #(rand-int n)))]
      (print-row n
        [(bench 20 10 (dotimes [i 10000] (contains? sorted-set (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (contains? data-avl (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (contains? ordered-set (aget ks i))))]))))

(defn bench-set-iteration
  "Benchmark traversing all N elements via reduce."
  [sizes]
  (print-header "SET ITERATION: reduce over all N elements"
                ["sorted-set" "data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          ss    (into (sorted-set) elems)
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)]
      (print-row n
        [(bench 20 10 (reduce (fn [^long acc x] (+ acc (long x))) 0 ss))
         (bench 20 10 (reduce (fn [^long acc x] (+ acc (long x))) 0 as))
         (bench 20 10 (reduce (fn [^long acc x] (+ acc (long x))) 0 os))]))))

(defn bench-set-comparison
  "Benchmark set equality on same-content and near-miss workloads."
  [sizes]
  (doseq [[case-label workload-key title]
          [["equal" :equal "SET EQUALITY: compare equal randomized sets"]
           ["one-different" :different "SET EQUALITY: compare same-size sets differing in one element"]
           ["size-different" :size-different "SET EQUALITY: compare sets differing in cardinality by one"]]]
    (print-header title ["hash-set" "sorted-set" "data.avl" "ordered-set"])
    (doseq [n sizes]
      (let [{left :left right :right} (workload-key (set-comparison-workload n))
            hs1 (:hash-set left), hs2 (:hash-set right)
            ss1 (:sorted-set left), ss2 (:sorted-set right)
            as1 (:data-avl left), as2 (:data-avl right)
            os1 (:ordered-set left), os2 (:ordered-set right)]
        (print-row n
          [(bench 20 10 (= hs1 hs2))
           (bench 20 10 (= ss1 ss2))
           (bench 20 10 (= as1 as2))
           (bench 20 10 (= os1 os2))])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ranked Access Benchmarks (data.avl specialty)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-rank-access
  "Benchmark rank (index) access - a specialty of both data.avl and ordered-*."
  [sizes]
  (print-header "RANK ACCESS: nth element by index (10,000 lookups)"
                ["data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)
          idxs  (int-array (repeatedly 10000 #(rand-int n)))]
      (print-row n
        [(bench 20 10 (dotimes [i 10000] (nth as (aget idxs i))))
         (bench 20 10 (dotimes [i 10000] (nth os (aget idxs i))))]))))

(defn bench-rank-lookup
  "Benchmark finding the rank of an element."
  [sizes]
  (print-header "RANK LOOKUP: rank-of element (10,000 lookups)"
                ["data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)
          ks    (int-array (repeatedly 10000 #(rand-int n)))]
      (print-row n
        [(bench 20 10 (dotimes [i 10000] (avl/rank-of as (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (core/rank os (aget ks i))))]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Split Operations (data.avl specialty)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-split-operations
  "Benchmark split-key operations."
  [sizes]
  (print-header "SPLIT-KEY: split set at random key (100 ops)"
                ["data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [{:keys [data-avl ordered-set keys]} (split-workload n 100)
          ^ints ks keys]
      (print-row n
        [(bench 2 5 (dotimes [i 100] (avl/split-key (aget ks i) data-avl)))
         (bench 2 5 (dotimes [i 100] (core/split-key (aget ks i) ordered-set)))]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parallel Fold Benchmarks (clojure.core.reducers/fold)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-set-parallel-fold
  "Benchmark r/fold performance across implementations.
   ordered-set implements CollFold for efficient tree-aware parallel reduction.
   sorted-set and data.avl use the default reducers behavior."
  [sizes]
  (print-header "SET r/fold: Chunked fold performance comparison"
                ["sorted-set" "data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          {:keys [sorted-set data-avl ordered-set]} (build-set-variants elems)
          fold-time (fn [coll]
                      (first (bench 20 10
                               (r/fold + (fn [^long acc x] (+ acc (long x)))
                                       coll))))
          ss-fold (fold-time sorted-set)
          as-fold (fold-time data-avl)
          os-fold (fold-time ordered-set)
          speedup (if (pos? os-fold) (format "%.1fx" (/ (double ss-fold) os-fold)) "N/A")]
      (print-row n
        [[ss-fold 0] [as-fold 0] [os-fold 0]])
      (println (format "           ordered-set is %s faster than sorted-set" speedup)))))

(defn bench-fold-comparison
  "Direct comparison of reduce vs fold for ordered-set."
  [sizes]
  (println)
  (println "=== FOLD vs REDUCE: Direct comparison on ordered-set ===")
  (println (format "%-12s %-18s %-18s %-12s"
                   "N" "reduce" "fold" "speedup"))
  (println (apply str (repeat 62 "-")))
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          os    (core/ordered-set elems)
          [os-reduce _] (bench 20 10 (reduce (fn [^long acc x] (+ acc (long x))) 0 os))
          [os-fold _]   (bench 20 10 (r/fold + (fn [^long acc x] (+ acc (long x))) os))
          os-speedup (if (pos? os-fold) (/ (double os-reduce) os-fold) 0.0)]
      (println (format "%-12d %-18s %-18s %-12.1fx"
                       n
                       (format-ns os-reduce)
                       (format-ns os-fold)
                       os-speedup)))))

(defn bench-fold-freq-comparison
  "Compare reduce vs fold on a non-trivial frequency-map workload."
  [sizes]
  (println)
  (println "=== FOLD vs REDUCE: Frequency-map workload ===")
  (println (format "%-10s %-20s %-20s %-20s %-20s %-20s %-20s %-20s"
                   "N"
                   "hs reduce"
                   "ss reduce"
                   "ss fold"
                   "avl reduce"
                   "avl fold"
                   "os reduce"
                   "os fold"))
  (println (apply str (repeat 150 "-")))
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          {:keys [sets combinef reducef]} (fold-frequency-workload elems)
          {:keys [hash-set sorted-set data-avl ordered-set]} sets
          [hs-reduce _] (bench 20 10 (reduce reducef {} hash-set))
          [ss-reduce _] (bench 20 10 (reduce reducef {} sorted-set))
          [ss-fold _]   (bench 20 10 (r/fold combinef reducef sorted-set))
          [as-reduce _] (bench 20 10 (reduce reducef {} data-avl))
          [as-fold _]   (bench 20 10 (r/fold combinef reducef data-avl))
          [os-reduce _] (bench 20 10 (reduce reducef {} ordered-set))
          [os-fold _]   (bench 20 10 (r/fold combinef reducef ordered-set))]
      (println (format "%-10d %-20s %-20s %-20s %-20s %-20s %-20s %-20s"
                       n
                       (format-ns hs-reduce)
                       (format-ns ss-reduce)
                       (format-ns ss-fold)
                       (format-ns as-reduce)
                       (format-ns as-fold)
                       (format-ns os-reduce)
                       (format-ns os-fold))))))

(defn run-parallel-benchmarks
  "Run parallel fold benchmarks."
  [sizes]
  (bench-set-parallel-fold sizes)
  (bench-fold-comparison sizes)
  (bench-fold-freq-comparison sizes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Key Benchmarks (Custom Comparator)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private string-cmp (order/compare-by #(neg? (compare (str %1) (str %2)))))

(defn- make-string-keys [n]
  (mapv #(format "key-%08d" %) (shuffle (range n))))

(defn bench-string-map-construction
  "Benchmark map construction with string keys."
  [sizes]
  (print-header "STRING MAP CONSTRUCTION: Build from N string key-value pairs"
                ["sorted-map-by" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [ks    (make-string-keys n)
          pairs (mapv (fn [k] [k k]) ks)
          cmp   #(compare (str %1) (str %2))]
      (print-row n
        [(bench 20 10 (into (sorted-map-by cmp) pairs))
         (bench 20 10 (into (avl/sorted-map-by cmp) pairs))
         (bench 20 10 (core/ordered-map-with string-cmp pairs))]))))

(defn bench-string-map-lookup
  "Benchmark lookups with string keys."
  [sizes]
  (print-header "STRING MAP LOOKUP: 10,000 random lookups, string keys"
                ["sorted-map-by" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [ks    (make-string-keys n)
          pairs (mapv (fn [k] [k k]) ks)
          cmp   #(compare (str %1) (str %2))
          sm    (into (sorted-map-by cmp) pairs)
          am    (into (avl/sorted-map-by cmp) pairs)
          om    (core/ordered-map-with string-cmp pairs)
          look  (object-array (repeatedly 10000 #(nth ks (rand-int n))))]
      (print-row n
        [(bench 20 10 (dotimes [i 10000] (get sm (aget look i))))
         (bench 20 10 (dotimes [i 10000] (get am (aget look i))))
         (bench 20 10 (dotimes [i 10000] (om (aget look i))))]))))

(defn bench-string-map-iteration
  "Benchmark iteration with string keys."
  [sizes]
  (print-header "STRING MAP ITERATION: reduce over N entries, string keys"
                ["sorted-map-by" "data.avl" "ordered-map"])
  (doseq [n sizes]
    (let [ks    (make-string-keys n)
          pairs (mapv (fn [k] [k k]) ks)
          cmp   #(compare (str %1) (str %2))
          sm    (into (sorted-map-by cmp) pairs)
          am    (into (avl/sorted-map-by cmp) pairs)
          om    (core/ordered-map-with string-cmp pairs)]
      (print-row n
        [(bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 sm))
         (bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 am))
         (bench 20 10 (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 om))]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Memory Footprint (approximate)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn estimate-memory-footprint
  "Estimate memory footprint by forcing GC and measuring heap delta."
  [sizes]
  (println)
  (println "=== MEMORY FOOTPRINT: Approximate bytes per entry ===")
  (println (format "%-10s %-20s %-20s %-20s %-20s" "N" "sorted-map" "data.avl" "ordered-map" "ordered-set"))
  (println (apply str (repeat 94 "-")))
  (doseq [n sizes]
    (let [pairs (mapv (fn [k] [k (str k)]) (range n))
          elems (range n)
          measure (fn [create-fn]
                    (let [rt    (Runtime/getRuntime)
                          holder (volatile! nil)]
                      ;; Measure baseline
                      (System/gc) (Thread/sleep 200)
                      (System/gc) (Thread/sleep 100)
                      (let [used0 (- (.totalMemory rt) (.freeMemory rt))]
                        ;; Create and retain collection
                        (vreset! holder (create-fn))
                        ;; Measure with collection alive — do NOT GC here
                        ;; as that could collect internal structure
                        (System/gc) (Thread/sleep 100)
                        (let [used1 (- (.totalMemory rt) (.freeMemory rt))
                              bpe   (/ (double (- used1 used0)) n)]
                          ;; Keep strong reference alive past measurement
                          (assert (some? @holder))
                          bpe))))
          sm-bpe  (measure #(into (sorted-map) pairs))
          avl-bpe (measure #(into (avl/sorted-map) pairs))
          om-bpe  (measure #(core/ordered-map pairs))
          os-bpe  (measure #(core/ordered-set elems))]
      (println (format "%-10d %-20.1f %-20.1f %-20.1f %-20.1f"
                       n sm-bpe avl-bpe om-bpe os-bpe)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main Entry Points
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-map-benchmarks
  "Run all map-related benchmarks."
  [sizes]
  (bench-map-construction sizes)
  (bench-map-incremental-insert sizes)
  (bench-map-incremental-delete sizes)
  (bench-map-lookup sizes)
  (bench-map-iteration sizes)
  (bench-map-seq-iteration sizes))

(defn run-set-benchmarks
  "Run all set-related benchmarks."
  [sizes]
  (bench-set-construction sizes)
  (bench-set-incremental-insert sizes)
  (bench-set-incremental-delete sizes)
  (bench-set-lookup sizes)
  (bench-set-iteration sizes)
  (bench-set-comparison sizes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Operations Benchmarks (union, intersection, difference)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-set-union
  "Benchmark set union operations."
  [sizes]
  (print-header "SET UNION: union of two sets of size N (overlapping 50%)"
                ["clojure.set/hash-set" "clojure.set/sorted-set" "ordered-set"])
  (doseq [n sizes]
    (let [{left :left right :right} (overlapping-set-variants n)
          hs1 (:hash-set left)
          hs2 (:hash-set right)
          cs1 (:sorted-set left)
          cs2 (:sorted-set right)
          os1 (:ordered-set left)
          os2 (:ordered-set right)]
      (print-row n
        [(bench 2 5 (clojure.set/union hs1 hs2))
         (bench 2 5 (clojure.set/union cs1 cs2))
         (bench 2 5 (core/union os1 os2))]))))

(defn bench-set-intersection
  "Benchmark set intersection operations."
  [sizes]
  (print-header "SET INTERSECTION: intersection of two sets of size N"
                ["clojure.set/hash-set" "clojure.set/sorted-set" "ordered-set"])
  (doseq [n sizes]
    (let [{left :left right :right} (overlapping-set-variants n)
          hs1 (:hash-set left)
          hs2 (:hash-set right)
          cs1 (:sorted-set left)
          cs2 (:sorted-set right)
          os1 (:ordered-set left)
          os2 (:ordered-set right)]
      (print-row n
        [(bench 2 5 (clojure.set/intersection hs1 hs2))
         (bench 2 5 (clojure.set/intersection cs1 cs2))
         (bench 2 5 (core/intersection os1 os2))]))))

(defn bench-set-difference
  "Benchmark set difference operations."
  [sizes]
  (print-header "SET DIFFERENCE: difference of two sets of size N"
                ["clojure.set/hash-set" "clojure.set/sorted-set" "ordered-set"])
  (doseq [n sizes]
    (let [{left :left right :right} (overlapping-set-variants n)
          hs1 (:hash-set left)
          hs2 (:hash-set right)
          cs1 (:sorted-set left)
          cs2 (:sorted-set right)
          os1 (:ordered-set left)
          os2 (:ordered-set right)]
      (print-row n
        [(bench 2 5 (clojure.set/difference hs1 hs2))
         (bench 2 5 (clojure.set/difference cs1 cs2))
         (bench 2 5 (core/difference os1 os2))]))))

(defn run-set-operations-benchmarks
  "Run set operation benchmarks (union, intersection, difference)."
  [sizes]
  (bench-set-union sizes)
  (bench-set-intersection sizes)
  (bench-set-difference sizes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Set/Map Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-interval-set-construction
  "Benchmark building an interval set from N random intervals."
  [sizes]
  (print-header "INTERVAL SET CONSTRUCTION: Build from N random intervals"
                ["interval-set"])
  (doseq [n sizes]
    (let [intervals (mapv (fn [_]
                            (let [a (rand-int 1000000)
                                  b (+ a (rand-int 1000))]
                              [a b]))
                          (range n))]
      (print-row n
        [(bench 20 10 (core/interval-set intervals))]))))

(defn bench-interval-set-query
  "Benchmark interval overlap queries via get (returns overlapping intervals)."
  [sizes]
  (print-header "INTERVAL SET QUERY: 1,000 overlap queries on set of N intervals"
                ["interval-set"])
  (doseq [n sizes]
    (let [intervals (mapv (fn [_]
                            (let [a (rand-int 1000000)
                                  b (+ a (rand-int 1000))]
                              [a b]))
                          (range n))
          iset      (core/interval-set intervals)
          ;; Create valid intervals for queries (a <= b)
          queries   (vec (repeatedly 1000
                           (fn [] (let [a (rand-int 1000000)] [a (+ a (rand-int 100))]))))]
      (print-row n
        [(bench 20 10 (doseq [q queries] (get iset q)))]))))

(defn bench-interval-map-construction
  "Benchmark building an interval map from N random intervals."
  [sizes]
  (print-header "INTERVAL MAP CONSTRUCTION: Build from N random interval key-value pairs"
                ["interval-map"])
  (doseq [n sizes]
    (let [pairs (mapv (fn [i]
                        (let [a (rand-int 1000000)
                              b (+ a (rand-int 1000))]
                          [[a b] i]))
                      (range n))]
      (print-row n
        [(bench 20 10 (core/interval-map pairs))]))))

(defn bench-interval-map-query
  "Benchmark interval map overlap queries."
  [sizes]
  (print-header "INTERVAL MAP QUERY: 1,000 overlap queries on map of N intervals"
                ["interval-map"])
  (doseq [n sizes]
    (let [pairs (mapv (fn [i]
                        (let [a (rand-int 1000000)
                              b (+ a (rand-int 1000))]
                          [[a b] i]))
                      (range n))
          imap    (core/interval-map pairs)
          ;; Create valid intervals for queries (a <= b)
          queries (vec (repeatedly 1000
                         (fn [] (let [a (rand-int 1000000)] [a (+ a (rand-int 100))]))))]
      (print-row n
        [(bench 20 10 (doseq [q queries] (get imap q)))]))))

(defn run-interval-benchmarks
  "Run interval set and map benchmarks."
  [sizes]
  (bench-interval-set-construction sizes)
  (bench-interval-set-query sizes)
  (bench-interval-map-construction sizes)
  (bench-interval-map-query sizes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; First/Last Element Access Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-first-last-access
  "Benchmark accessing first and last elements via seq (clojure first/last)."
  [sizes]
  (print-header "FIRST/LAST ACCESS: 1,000 first/last calls"
                ["sorted-set" "data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          ss    (into (sorted-set) elems)
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)]
      (print-row n
        [(bench 2 5 (dotimes [_ 1000] (first ss) (last ss)))
         (bench 2 5 (dotimes [_ 1000] (first as) (last as)))
         ;; Use SortedSet interface for ordered-set (optimized path)
         (bench 2 5 (dotimes [_ 1000]
                      (.first ^java.util.SortedSet os)
                      (.last ^java.util.SortedSet os)))]))))

(defn run-specialty-benchmarks
  "Run benchmarks for specialty operations (rank, split)."
  [sizes]
  (bench-rank-access sizes)
  (bench-rank-lookup sizes)
  (bench-split-operations sizes))

(defn run-string-benchmarks
  "Run benchmarks with string keys (custom comparator)."
  [sizes]
  (bench-string-map-construction sizes)
  (bench-string-map-lookup sizes)
  (bench-string-map-iteration sizes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope (Vector) Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private rope-sizes [1000 5000 10000 100000])

(defn bench-rope-concat
  "Benchmark concatenating rope pieces vs into vector."
  [sizes]
  (print-header "ROPE CONCAT: Concat 4 pieces of N/4 elements"
                ["vector" "rope"])
  (doseq [n sizes]
    (let [quarter (quot n 4)
          v1 (vec (range 0 quarter))
          v2 (vec (range quarter (* 2 quarter)))
          v3 (vec (range (* 2 quarter) (* 3 quarter)))
          v4 (vec (range (* 3 quarter) n))
          r1 (core/rope v1) r2 (core/rope v2)
          r3 (core/rope v3) r4 (core/rope v4)]
      (print-row n
        [(bench 5 10 (vec (concat v1 v2 v3 v4)))
         (bench 5 10 (core/rope-concat r1 r2 r3 r4))]))))

(defn bench-rope-splice
  "Benchmark replacing 32 elements at midpoint."
  [sizes]
  (print-header "ROPE SPLICE: Replace 32 elements at midpoint"
                ["vector" "rope"])
  (doseq [n sizes]
    (let [v   (vec (range n))
          r   (core/rope (range n))
          mid (quot n 2)
          lo  (max 0 (- mid 16))
          hi  (min n (+ mid 16))
          ins (vec (range 32))]
      (print-row n
        [(bench 5 10 (vec (concat (subvec v 0 lo) ins (subvec v hi))))
         (bench 5 10 (core/rope-splice r lo hi ins))]))))

(defn bench-rope-repeated-edits
  "Benchmark 200 random splice edits."
  [sizes]
  (print-header "ROPE REPEATED EDITS: 200 random splice edits"
                ["vector" "rope"])
  (doseq [n sizes]
    (let [v    (vec (range n))
          r    (core/rope (range n))
          rng  (java.util.Random. 42)
          nops 200
          idxs (vec (repeatedly nops #(.nextInt rng (max 1 n))))
          ins  (vec (range nops))]
      (print-row n
        [(bench 2 5
           (loop [v v, i 0]
             (if (< i nops)
               (let [pos (rem (nth idxs i) (count v))]
                 (recur (vec (concat (subvec v 0 pos) [(nth ins i)]
                                     (subvec v (min (+ pos 5) (count v)))))
                        (inc i)))
               v)))
         (bench 2 5
           (loop [r r, i 0]
             (if (< i nops)
               (let [pos (rem (nth idxs i) (count r))]
                 (recur (core/rope-splice r pos (min (+ pos 5) (count r)) [(nth ins i)])
                        (inc i)))
               r)))]))))

(defn bench-rope-reduce
  "Benchmark reduce over all elements."
  [sizes]
  (print-header "ROPE REDUCE: reduce + over all N elements"
                ["vector" "rope"])
  (doseq [n sizes]
    (let [v (vec (range n))
          r (core/rope (range n))]
      (print-row n
        [(bench 20 10 (reduce + 0 v))
         (bench 20 10 (reduce + 0 r))]))))

(defn bench-rope-nth
  "Benchmark 1000 random nth lookups."
  [sizes]
  (print-header "ROPE NTH: 1,000 random nth lookups"
                ["vector" "rope"])
  (doseq [n sizes]
    (let [v    (vec (range n))
          r    (core/rope (range n))
          idxs (int-array (repeatedly 1000 #(rand-int n)))]
      (print-row n
        [(bench 20 10 (areduce idxs i acc nil (nth v (aget idxs i))))
         (bench 20 10 (areduce idxs i acc nil (nth r (aget idxs i))))]))))

(defn bench-rope-fold
  "Benchmark r/fold parallel sum."
  [sizes]
  (print-header "ROPE FOLD: r/fold parallel sum"
                ["vector" "rope"])
  (doseq [n sizes]
    (let [v (vec (range n))
          r (core/rope (range n))]
      (print-row n
        [(bench 20 10 (r/fold + v))
         (bench 20 10 (r/fold + r))]))))

(defn run-rope-benchmarks
  "Run all generic rope vs vector benchmarks."
  [sizes]
  (let [sizes (or (seq (filter #(<= % 100000) sizes)) rope-sizes)]
    (bench-rope-concat sizes)
    (bench-rope-splice sizes)
    (bench-rope-repeated-edits sizes)
    (bench-rope-reduce sizes)
    (bench-rope-nth sizes)
    (bench-rope-fold sizes)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Byte Rope Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private byte-rope-sizes [1000 5000 10000 100000])

(defn- bench-ba-random
  ^bytes [^long n]
  (let [rng (java.util.Random. 42)
        b (byte-array n)]
    (.nextBytes rng b)
    b))

(defn- bench-ba-splice
  ^bytes [^bytes s ^long start ^long end ^bytes rep]
  (let [si (int start)
        ei (int end)
        sl (alength s)
        rl (int (if rep (alength rep) 0))
        result (byte-array (+ (- sl (- ei si)) rl))]
    (System/arraycopy s 0 result 0 si)
    (when (pos? rl)
      (System/arraycopy rep 0 result si rl))
    (System/arraycopy s ei result (+ si rl) (- sl ei))
    result))

(defn bench-byte-rope-concat
  "Benchmark concatenating byte rope pieces vs byte[] arraycopy."
  [sizes]
  (print-header "BYTE ROPE CONCAT: Concat 4 pieces of N/4 bytes"
                ["byte[]" "byte-rope"])
  (doseq [n sizes]
    (let [quarter (quot n 4)
          ^bytes b1 (bench-ba-random quarter)
          ^bytes b2 (bench-ba-random quarter)
          ^bytes b3 (bench-ba-random quarter)
          ^bytes b4 (bench-ba-random (- n (* 3 quarter)))
          r1 (core/byte-rope b1) r2 (core/byte-rope b2)
          r3 (core/byte-rope b3) r4 (core/byte-rope b4)]
      (print-row n
        [(bench 5 10 (let [a (byte-array (+ (alength b1) (alength b2)
                                            (alength b3) (alength b4)))]
                       (System/arraycopy b1 0 a 0 (alength b1))
                       (System/arraycopy b2 0 a (alength b1) (alength b2))
                       (System/arraycopy b3 0 a (+ (alength b1) (alength b2))
                                         (alength b3))
                       (System/arraycopy b4 0 a (+ (alength b1) (alength b2)
                                                   (alength b3))
                                         (alength b4))
                       a))
         (bench 5 10 (core/byte-rope-concat r1 r2 r3 r4))]))))

(defn bench-byte-rope-splice
  "Benchmark replacing 32 bytes at midpoint."
  [sizes]
  (print-header "BYTE ROPE SPLICE: Replace 32 bytes at midpoint"
                ["byte[]" "byte-rope"])
  (doseq [n sizes]
    (let [^bytes data (bench-ba-random n)
          r   (core/byte-rope data)
          mid (quot n 2)
          lo  (max 0 (- mid 16))
          hi  (min n (+ mid 16))
          ^bytes rep (bench-ba-random 32)]
      (print-row n
        [(bench 5 10 (bench-ba-splice data lo hi rep))
         (bench 5 10 (core/rope-splice r lo hi rep))]))))

(defn bench-byte-rope-repeated-edits
  "Benchmark 200 random splice edits."
  [sizes]
  (print-header "BYTE ROPE REPEATED EDITS: 200 random splice edits"
                ["byte[]" "byte-rope"])
  (doseq [n sizes]
    (let [^bytes data (bench-ba-random n)
          r    (core/byte-rope data)
          rng  (java.util.Random. 42)
          nops 200
          idxs (vec (repeatedly nops #(.nextInt rng (max 1 n))))
          ^bytes ins (bench-ba-random 5)]
      (print-row n
        [(bench 2 5
           (loop [^bytes s data, i 0]
             (if (< i nops)
               (let [pos (rem (long (nth idxs i)) (long (alength s)))
                     end (min (alength s) (+ pos 5))]
                 (recur (bench-ba-splice s pos end ins) (inc i)))
               s)))
         (bench 2 5
           (loop [r r, i 0]
             (if (< i nops)
               (let [pos (rem (long (nth idxs i)) (long (count r)))
                     end (min (count r) (+ pos 5))]
                 (recur (core/rope-splice r pos end ins) (inc i)))
               r)))]))))

(defn bench-byte-rope-reduce
  "Benchmark reduce over all bytes."
  [sizes]
  (print-header "BYTE ROPE REDUCE: sum all N bytes"
                ["byte[]" "byte-rope"])
  (doseq [n sizes]
    (let [^bytes data (bench-ba-random n)
          r (core/byte-rope data)]
      (print-row n
        [(bench 20 10 (let [len (alength data)]
                        (loop [i (int 0), acc (long 0)]
                          (if (< i len)
                            (recur (unchecked-inc-int i)
                                   (+ acc (bit-and (long (aget data i)) 0xff)))
                            acc))))
         (bench 20 10 (reduce + 0 r))]))))

(defn bench-byte-rope-digest
  "Benchmark SHA-256 digest."
  [sizes]
  (print-header "BYTE ROPE DIGEST: SHA-256 over N bytes"
                ["byte[]" "byte-rope"])
  (doseq [n sizes]
    (let [^bytes data (bench-ba-random n)
          r (core/byte-rope data)]
      (print-row n
        [(bench 5 10 (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                       (.digest md data)))
         (bench 5 10 (core/byte-rope-digest r "SHA-256"))]))))

(defn run-byte-rope-benchmarks
  "Run all byte rope vs byte[] benchmarks."
  [sizes]
  (let [sizes (or (seq (filter #(<= % 100000) sizes)) byte-rope-sizes)]
    (bench-byte-rope-concat sizes)
    (bench-byte-rope-splice sizes)
    (bench-byte-rope-repeated-edits sizes)
    (bench-byte-rope-reduce sizes)
    (bench-byte-rope-digest sizes)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Rope vs String Structural Editing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private string-rope-sizes [1000 5000 10000])

(defn- random-text
  "Generate a random ASCII text of length n."
  ^String [^long n]
  (let [sb (StringBuilder. n)
        chars "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ\n0123456789"
        nchars (count chars)]
    (dotimes [_ n]
      (.append sb (.charAt chars (rand-int nchars))))
    (.toString sb)))

(defn- string-splice
  "String equivalent of rope-splice via StringBuilder — fair baseline."
  ^String [^String s ^long start ^long end ^String ins]
  (let [si (int start)
        ei (int end)
        sb (StringBuilder. (+ (.length s) (.length ins) (- si) ei))]
    (.append sb s 0 si)
    (.append sb ins)
    (.append sb s ei (.length s))
    (.toString sb)))

(defn- string-insert
  "String equivalent of rope-insert via StringBuilder — fair baseline."
  ^String [^String s ^long i ^String ins]
  (string-splice s i i ins))

(defn- string-remove
  "String equivalent of rope-remove via StringBuilder — fair baseline."
  ^String [^String s ^long start ^long end]
  (let [si (int start)
        ei (int end)
        sb (StringBuilder. (- (.length s) (- ei si)))]
    (.append sb s 0 si)
    (.append sb s ei (.length s))
    (.toString sb)))

(defn bench-string-rope-construction
  "Benchmark building a string-rope from a String of length N."
  [sizes]
  (print-header "STRING ROPE CONSTRUCTION: Build from String of length N"
                ["String (id)" "string-rope"])
  (doseq [n sizes]
    (let [text (random-text n)]
      (print-row n
        [(bench 20 10 (identity text))
         (bench 20 10 (core/string-rope text))]))))

(defn bench-string-rope-concat
  "Benchmark concatenating two equal halves."
  [sizes]
  (print-header "STRING ROPE CONCAT: Join two halves of length N/2"
                ["String SB" "string-rope-concat"])
  (doseq [n sizes]
    (let [^String text (random-text n)
          half (int (quot n 2))
          ^String s1 (.substring text 0 half)
          ^String s2 (.substring text half)
          sr1  (core/string-rope s1)
          sr2  (core/string-rope s2)]
      (print-row n
        [(bench 20 10
           (let [sb (StringBuilder. (.length text))]
             (.append sb s1)
             (.append sb s2)
             (.toString sb)))
         (bench 20 10 (core/string-rope-concat sr1 sr2))]))))

(defn bench-string-rope-split
  "Benchmark splitting at midpoint."
  [sizes]
  (print-header "STRING ROPE SPLIT: Split at midpoint"
                ["String subs" "rope-split"])
  (doseq [n sizes]
    (let [text (random-text n)
          sr   (core/string-rope text)
          mid  (quot n 2)]
      (print-row n
        [(bench 20 10 [(subs text 0 mid) (subs text mid)])
         (bench 20 10 (core/rope-split sr mid))]))))

(defn bench-string-rope-insert
  "Benchmark inserting a short string at the midpoint, 100 times."
  [sizes]
  (print-header "STRING ROPE INSERT: 100 inserts of 10-char string at midpoint"
                ["String SB" "rope-insert"])
  (doseq [n sizes]
    (let [text (random-text n)
          sr   (core/string-rope text)
          ins  "XXXXXXXXXX"]
      (print-row n
        [(bench 5 10
           (loop [^String s text, i 0]
             (if (< i 100)
               (recur (string-insert s (quot (count s) 2) ins)
                      (unchecked-inc i))
               s)))
         (bench 5 10
           (loop [r sr, i 0]
             (if (< i 100)
               (recur (core/rope-insert r (quot (count r) 2) ins)
                      (unchecked-inc i))
               r)))]))))

(defn bench-string-rope-remove
  "Benchmark removing 10 chars from the midpoint, 100 times."
  [sizes]
  (print-header "STRING ROPE REMOVE: 100 removals of 10 chars at midpoint"
                ["String SB" "rope-remove"])
  (doseq [n sizes]
    (let [text (random-text (+ n 1000))  ;; extra room for 100 removals
          sr   (core/string-rope text)]
      (print-row n
        [(bench 5 10
           (loop [^String s text, i 0]
             (if (and (< i 100) (>= (count s) 10))
               (let [mid (quot (count s) 2)
                     lo  (max 0 (- mid 5))]
                 (recur (string-remove s lo (+ lo 10))
                        (unchecked-inc i)))
               s)))
         (bench 5 10
           (loop [r sr, i 0]
             (if (and (< i 100) (>= (count r) 10))
               (let [mid (quot (count r) 2)
                     lo  (max 0 (- mid 5))]
                 (recur (core/rope-remove r lo (+ lo 10))
                        (unchecked-inc i)))
               r)))]))))

(defn bench-string-rope-splice
  "Benchmark replacing 10 chars with 10 new chars at midpoint, 100 times."
  [sizes]
  (print-header "STRING ROPE SPLICE: 100 replace-10 ops at midpoint"
                ["String SB" "rope-splice"])
  (doseq [n sizes]
    (let [text (random-text n)
          sr   (core/string-rope text)
          rep  "YYYYYYYYYY"]
      (print-row n
        [(bench 5 10
           (loop [^String s text, i 0]
             (if (< i 100)
               (let [mid (quot (count s) 2)
                     lo  (max 0 (- mid 5))
                     hi  (min (count s) (+ lo 10))]
                 (recur (string-splice s lo hi rep) (unchecked-inc i)))
               s)))
         (bench 5 10
           (loop [r sr, i 0]
             (if (< i 100)
               (let [mid (quot (count r) 2)
                     lo  (max 0 (- mid 5))
                     hi  (min (count r) (+ lo 10))]
                 (recur (core/rope-splice r lo hi rep) (unchecked-inc i)))
               r)))]))))

(defn bench-string-rope-random-access
  "Benchmark 10,000 random charAt lookups."
  [sizes]
  (print-header "STRING ROPE RANDOM ACCESS: 10,000 random charAt"
                ["String .charAt" "string-rope nth"])
  (doseq [n sizes]
    (let [^String text (random-text n)
          sr   (core/string-rope text)
          idxs (int-array (repeatedly 10000 #(rand-int n)))]
      (print-row n
        [(bench 20 10 (dotimes [i 10000] (.charAt text (aget idxs i))))
         (bench 20 10 (dotimes [i 10000] (nth sr (aget idxs i))))]))))

(defn bench-string-rope-iteration
  "Benchmark reduce over all characters."
  [sizes]
  (print-header "STRING ROPE ITERATION: reduce over all N chars"
                ["String reduce" "string-rope reduce"])
  (doseq [n sizes]
    (let [text (random-text n)
          sr   (core/string-rope text)]
      (print-row n
        [(bench 20 10 (reduce (fn [^long acc c] (+ acc (long (char c)))) 0 text))
         (bench 20 10 (reduce (fn [^long acc c] (+ acc (long (char c)))) 0 sr))]))))

(defn bench-string-rope-materialization
  "Benchmark materializing back to String (toString)."
  [sizes]
  (print-header "STRING ROPE MATERIALIZE: toString"
                ["String (id)" "string-rope str"])
  (doseq [n sizes]
    (let [text (random-text n)
          sr   (core/string-rope text)]
      (print-row n
        [(bench 20 10 (identity text))
         (bench 20 10 (str sr))]))))

(defn bench-string-rope-editor-simulation
  "Simulate a text editor session: interleaved inserts, deletes, and replacements.
  50 edits of mixed operations at random positions."
  [sizes]
  (print-header "STRING ROPE EDITOR SIM: 50 mixed edits at random positions"
                ["String" "string-rope"])
  (doseq [n sizes]
    (let [text     (random-text n)
          sr       (core/string-rope text)
          ;; Pre-generate a sequence of edit operations
          edit-ops (vec (repeatedly 50
                          (fn []
                            (let [op (rand-int 3)]
                              {:op op :ins (random-text (+ 1 (rand-int 20)))}))))]
      (print-row n
        [(bench 5 10
           (reduce
             (fn [^String s {:keys [op ^String ins]}]
               (let [len (count s)]
                 (if (< len 5) s
                   (let [pos (rand-int (max 1 (- len 3)))]
                     (case (int op)
                       0 (string-insert s pos ins)
                       1 (string-remove s pos (min len (+ pos (min 20 (rand-int len)))))
                       2 (string-splice s pos (min len (+ pos 10)) ins))))))
             text edit-ops))
         (bench 5 10
           (reduce
             (fn [r {:keys [op ins]}]
               (let [len (count r)]
                 (if (< len 5) r
                   (let [pos (rand-int (max 1 (- len 3)))]
                     (case (int op)
                       0 (core/rope-insert r pos ins)
                       1 (core/rope-remove r pos (min len (+ pos (min 20 (rand-int len)))))
                       2 (core/rope-splice r pos (min len (+ pos 10)) ins))))))
             sr edit-ops))]))))

(defn bench-string-rope-repeated-concat
  "Benchmark building a large string by repeatedly concatenating small pieces."
  [sizes]
  (print-header "STRING ROPE REPEATED CONCAT: Append 100 x 10-char chunks"
                ["String SB" "string-rope-concat" "string-rope transient"])
  (doseq [n sizes]
    (let [^String base-text (random-text n)
          sr        (core/string-rope base-text)
          chunks    (vec (repeatedly 100 #(random-text 10)))]
      (print-row n
        [(bench 5 10
           (reduce (fn [^String acc ^String c]
                     (let [sb (StringBuilder. (+ (.length acc) (.length c)))]
                       (.append sb acc)
                       (.append sb c)
                       (.toString sb)))
                   base-text chunks))
         (bench 5 10
           (reduce (fn [acc c] (core/string-rope-concat acc c)) sr chunks))
         (bench 5 10
           (let [t (transient sr)]
             (persistent!
               (reduce (fn [t c]
                         (reduce conj! t c))
                 t chunks))))]))))

(defn run-string-rope-benchmarks
  "Run all string-rope vs String structural editing benchmarks."
  [sizes]
  (let [sizes (or (seq (filter #(<= % 8000) sizes)) string-rope-sizes)]
    (bench-string-rope-construction sizes)
    (bench-string-rope-concat sizes)
    (bench-string-rope-split sizes)
    (bench-string-rope-insert sizes)
    (bench-string-rope-remove sizes)
    (bench-string-rope-splice sizes)
    (bench-string-rope-random-access sizes)
    (bench-string-rope-iteration sizes)
    (bench-string-rope-materialization sizes)
    (bench-string-rope-repeated-concat sizes)
    (bench-string-rope-editor-simulation sizes)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Size Presets
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sizes-quick   [100 1000 5000 10000])
(def sizes-default [100 1000 5000 10000 100000])
(def sizes-full    [100 1000 5000 10000 100000 1000000])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Categories
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def categories
  {:maps      {:title "MAP BENCHMARKS"
               :fn    run-map-benchmarks}
   :sets      {:title "SET BENCHMARKS"
               :fn    run-set-benchmarks}
   :set-ops   {:title "SET OPERATIONS (union, intersection, difference)"
               :fn    run-set-operations-benchmarks}
   :intervals {:title "INTERVAL TREE OPERATIONS"
               :fn    run-interval-benchmarks}
   :specialty {:title "SPECIALTY OPERATIONS (rank, split, first/last)"
               :fn    (fn [sizes]
                        (run-specialty-benchmarks sizes)
                        (bench-first-last-access sizes))}
   :strings   {:title "STRING KEYS (Custom Comparator)"
               :fn    run-string-benchmarks}
   :parallel  {:title "PARALLEL FOLD (r/fold)"
               :fn    run-parallel-benchmarks}
   :memory    {:title "MEMORY FOOTPRINT"
               :fn    estimate-memory-footprint}
   :rope        {:title "ROPE vs VECTOR (Structural Editing)"
                 :fn    run-rope-benchmarks}
   :string-rope {:title "STRING ROPE vs STRING (Structural Editing)"
                 :fn    run-string-rope-benchmarks}
   :byte-rope   {:title "BYTE ROPE vs byte[] (Structural Editing)"
                 :fn    run-byte-rope-benchmarks}})

(def category-order [:maps :sets :set-ops :intervals :specialty :strings :parallel :memory :rope :string-rope :byte-rope])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main Entry Points
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn print-header-banner [sizes]
  (println "========================================================================")
  (println "  Performance Comparison: sorted-map vs data.avl vs ordered-map")
  (println (str "  JVM: " (System/getProperty "java.version")
                "  Clojure: " (clojure-version)))
  (println (str "  " (java.util.Date.)))
  (println (str "  Sizes: " (str/join ", " (map #(format "%,d" %) sizes))))
  (println "========================================================================"))

(defn run-categories
  "Run specified benchmark categories with given sizes."
  [sizes cats]
  (print-header-banner sizes)
  (doseq [cat-key cats
          :let [{:keys [title fn]} (get categories cat-key)]
          :when fn]
    (println)
    (println "------------------------------------------------------------------------")
    (println (str "                    " title))
    (println "------------------------------------------------------------------------")
    (fn sizes))
  (println)
  (println "========================================================================")
  (println "  Benchmark complete.")
  (println "========================================================================"))

(defn run-all
  "Run the complete benchmark suite."
  ([] (run-all sizes-default))
  ([sizes] (run-categories sizes category-order)))

(defn run-quick
  "Run a quick benchmark with smaller sizes for development."
  []
  (run-all sizes-quick))

(defn run-full
  "Run full benchmark suite including 1M cardinality."
  []
  (run-all sizes-full))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLI
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn print-usage []
  (println "Usage: lein bench-simple [options]")
  (println)
  (println "Options:")
  (println "  --quick            Fast iteration (100 to 10K)")
  (println "  --full             Full suite including 1M")
  (println "  --sizes N,N,...    Custom sizes (comma-separated)")
  (println "  --only CATS        Run only specified categories")
  (println "  --help             Show this help")
  (println)
  (println "Categories for --only:")
  (println "  maps, sets, set-ops, intervals, specialty, strings, parallel, memory,")
  (println "  rope, string-rope, byte-rope")
  (println)
  (println "Examples:")
  (println "  lein bench-simple --quick --only sets")
  (println "  lein bench-simple --sizes 10000,100000 --only maps,sets")
  (println "  lein bench-simple --full"))

(defn- parse-categories [s]
  (mapv keyword (str/split (str/lower-case s) #",")))

(defn parse-args [args]
  (let [base   (parse-standard-args args sizes-quick sizes-default sizes-full)
        only   (get-arg-value (vec args) "--only")
        cats   (if only (parse-categories only) category-order)]
    (assoc base :categories cats)))

(defn -main [& args]
  (let [{:keys [sizes categories help]} (parse-args args)]
    (if help
      (print-usage)
      (run-categories sizes categories)))
  (shutdown-agents))
