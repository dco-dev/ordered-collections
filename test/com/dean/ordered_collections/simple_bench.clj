(ns com.dean.ordered-collections.simple-bench
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
     maps, sets, set-ops, intervals, specialty, strings, parallel, memory"
  (:require [clojure.core.reducers :as r]
            [clojure.data.avl :as avl]
            [clojure.string :as str]
            [com.dean.ordered-collections.bench-utils :as bu
             :refer [bench format-ns format-result print-header print-row
                     has-flag? get-arg-value parse-sizes parse-standard-args]]
            [com.dean.ordered-collections.core :as core]
            [com.dean.ordered-collections.tree.node :as node]
            [com.dean.ordered-collections.tree.tree :as tree]
            [com.dean.ordered-collections.tree.order :as order]
            [com.dean.ordered-collections.tree.interval :as interval]))

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
          sm    (into (sorted-map) pairs)
          am    (into (avl/sorted-map) pairs)
          om    (core/ordered-map pairs)
          ks    (int-array (repeatedly 10000 #(rand-int n)))]
      (print-row n
        [(bench 20 10 (dotimes [i 10000] (get sm (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (get am (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (om (aget ks i))))]))))

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
          ss    (into (sorted-set) elems)
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)
          ks    (int-array (repeatedly 10000 #(rand-int n)))]
      (print-row n
        [(bench 20 10 (dotimes [i 10000] (contains? ss (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (contains? as (aget ks i))))
         (bench 20 10 (dotimes [i 10000] (contains? os (aget ks i))))]))))

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
         (bench 20 10 (dotimes [i 10000] (.indexOf ^java.util.List os (aget ks i))))]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Split Operations (data.avl specialty)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-split-operations
  "Benchmark split-key operations."
  [sizes]
  (print-header "SPLIT-KEY: split set at random key (100 ops)"
                ["data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)
          ks    (int-array (repeatedly 100 #(rand-int n)))]
      (print-row n
        [(bench 2 5 (dotimes [i 100] (avl/split-key (aget ks i) as)))
         (bench 2 5 (dotimes [i 100]
                      (let [k (aget ks i)]
                        [(.headSet ^java.util.SortedSet os k)
                         (contains? os k)
                         (.tailSet ^java.util.SortedSet os k)])))]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parallel Fold Benchmarks (clojure.core.reducers/fold)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-set-parallel-fold
  "Benchmark r/fold performance across implementations.
   ordered-set implements CollFold for efficient chunked/parallel reduction.
   sorted-set and data.avl use default sequential fallback."
  [sizes]
  (print-header "SET r/fold: Chunked fold performance comparison"
                ["sorted-set" "data.avl" "ordered-set"])
  (doseq [n sizes]
    (let [elems (shuffle (range n))
          ss    (into (sorted-set) elems)
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)
          ;; Parallel fold with chunk size
          fold-time (fn [coll]
                      (first (bench 20 10
                               (r/fold 512  ;; chunk size
                                       +   ;; combinef
                                       (fn [^long acc x] (+ acc (long x)))
                                       coll))))
          ss-fold (fold-time ss)
          as-fold (fold-time as)
          os-fold (fold-time os)
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
          [os-fold _]   (bench 20 10 (r/fold 512 + (fn [^long acc x] (+ acc (long x))) os))
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
          hs    (set elems)
          ss    (into (sorted-set) elems)
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)
          combinef (fn ([] {}) ([m1 m2] (merge-with + m1 m2)))
          reducef  (fn [m x] (update m (mod (long x) 100) (fnil inc 0)))
          [hs-reduce _] (bench 20 10 (reduce reducef {} hs))
          [ss-reduce _] (bench 20 10 (reduce reducef {} ss))
          [ss-fold _]   (bench 20 10 (r/fold combinef reducef ss))
          [as-reduce _] (bench 20 10 (reduce reducef {} as))
          [as-fold _]   (bench 20 10 (r/fold combinef reducef as))
          [os-reduce _] (bench 20 10 (reduce reducef {} os))
          [os-fold _]   (bench 20 10 (r/fold combinef reducef os))]
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
  (bench-set-iteration sizes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Operations Benchmarks (union, intersection, difference)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-set-union
  "Benchmark set union operations."
  [sizes]
  (print-header "SET UNION: union of two sets of size N (overlapping 50%)"
                ["clojure.set/hash-set" "clojure.set/sorted-set" "ordered-set"])
  (doseq [n sizes]
    (let [;; Create two sets with 50% overlap
          s1-elems (range 0 n)
          s2-elems (range (quot n 2) (+ n (quot n 2)))
          hs1 (set s1-elems)
          hs2 (set s2-elems)
          cs1 (into (sorted-set) s1-elems)
          cs2 (into (sorted-set) s2-elems)
          os1 (core/ordered-set s1-elems)
          os2 (core/ordered-set s2-elems)]
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
    (let [s1-elems (range 0 n)
          s2-elems (range (quot n 2) (+ n (quot n 2)))
          hs1 (set s1-elems)
          hs2 (set s2-elems)
          cs1 (into (sorted-set) s1-elems)
          cs2 (into (sorted-set) s2-elems)
          os1 (core/ordered-set s1-elems)
          os2 (core/ordered-set s2-elems)]
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
    (let [s1-elems (range 0 n)
          s2-elems (range (quot n 2) (+ n (quot n 2)))
          hs1 (set s1-elems)
          hs2 (set s2-elems)
          cs1 (into (sorted-set) s1-elems)
          cs2 (into (sorted-set) s2-elems)
          os1 (core/ordered-set s1-elems)
          os2 (core/ordered-set s2-elems)]
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
;; Size Presets
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sizes-quick   [100 1000 10000])
(def sizes-default [100 1000 10000 100000])
(def sizes-full    [100 1000 10000 100000 1000000])

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
               :fn    estimate-memory-footprint}})

(def category-order [:maps :sets :set-ops :intervals :specialty :strings :parallel :memory])

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
  (println "  maps, sets, set-ops, intervals, specialty, strings, parallel, memory")
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
