(ns com.dean.ordered-collections.criterium-bench
  "Rigorous benchmark suite using Criterium for statistically valid measurements.

   Criterium provides:
   - JIT warmup with automatic detection of steady-state
   - Multiple samples with statistical analysis (mean, std dev, percentiles)
   - Outlier detection and reporting
   - GC overhead estimation and correction

   Usage:
     ;; Run full suite (takes 30-60 minutes)
     (require '[com.dean.ordered-collections.criterium-bench :as cb])
     (cb/run-all)

     ;; Run quick suite (takes ~10 minutes)
     (cb/run-quick)

     ;; Run specific benchmarks
     (cb/bench-map-lookup 100000)
     (cb/bench-set-iteration 500000)

     ;; Compare implementations
     (cb/compare-lookup 100000)
     (cb/compare-iteration 500000)
     (cb/compare-fold 1000000)

   Results are printed in Criterium's standard format with:
   - Execution time mean +/- std deviation
   - Lower/upper quantiles (2.5%, 97.5%)
   - Overhead estimation
   - Outlier analysis"
  (:require [criterium.core :as crit]
            [clojure.core.reducers :as r]
            [clojure.data.avl :as avl]
            [clojure.set :as cset]
            [clojure.string :as str]
            [com.dean.ordered-collections.core :as core]
            [com.dean.ordered-collections.tree.order :as order]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *quick-bench*
  "When true, use quick-bench (fewer samples) instead of bench."
  false)

(defmacro run-bench
  "Run benchmark using either bench or quick-bench based on *quick-bench*."
  [& body]
  `(if *quick-bench*
     (crit/quick-bench ~@body)
     (crit/bench ~@body)))

(defmacro with-quick-bench
  "Execute body with quick benchmarking enabled."
  [& body]
  `(binding [*quick-bench* true]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Data Generation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate-pairs
  "Generate n random key-value pairs."
  [n]
  (mapv (fn [k] [k (str "value-" k)]) (shuffle (range n))))

(defn generate-elements
  "Generate n random elements (shuffled range)."
  [n]
  (vec (shuffle (range n))))

(defn generate-lookup-keys
  "Generate array of random lookup keys for a collection of size n."
  ^ints [n num-lookups]
  (int-array (repeatedly num-lookups #(rand-int n))))

(defn generate-string-keys
  "Generate n random string keys."
  [n]
  (mapv #(format "key-%08d" %) (shuffle (range n))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Printing Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn print-header [title]
  (println)
  (println (str/join (repeat 72 "=")))
  (println (str "  " title))
  (println (str/join (repeat 72 "=")))
  (println))

(defn print-section [title]
  (println)
  (println (str "--- " title " ---"))
  (println))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Map Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-map-construction
  "Benchmark map construction from pairs."
  [n]
  (let [pairs (generate-pairs n)]
    (print-header (str "MAP CONSTRUCTION: N=" n))

    (print-section "sorted-map (Clojure built-in)")
    (run-bench (into (sorted-map) pairs))

    (print-section "data.avl/sorted-map")
    (run-bench (into (avl/sorted-map) pairs))

    (print-section "ordered-map")
    (run-bench (core/ordered-map pairs))))

(defn bench-map-insert
  "Benchmark sequential map insertion (assoc one at a time)."
  [n]
  (let [ks (generate-elements n)]
    (print-header (str "MAP INSERT (sequential assoc): N=" n))

    (print-section "sorted-map")
    (run-bench
      (loop [m (sorted-map), xs (seq ks)]
        (if xs (recur (assoc m (first xs) true) (next xs)) m)))

    (print-section "data.avl/sorted-map")
    (run-bench
      (loop [m (avl/sorted-map), xs (seq ks)]
        (if xs (recur (assoc m (first xs) true) (next xs)) m)))

    (print-section "ordered-map")
    (run-bench
      (loop [m (core/ordered-map), xs (seq ks)]
        (if xs (recur (assoc m (first xs) true) (next xs)) m)))))

(defn bench-map-delete
  "Benchmark map deletion (dissoc half the elements)."
  [n]
  (let [pairs   (map #(vector % true) (range n))
        to-del  (vec (take (quot n 2) (shuffle (range n))))
        sm      (into (sorted-map) pairs)
        am      (into (avl/sorted-map) pairs)
        om      (core/ordered-map pairs)]
    (print-header (str "MAP DELETE (dissoc N/2 elements): N=" n))

    (print-section "sorted-map")
    (run-bench (reduce (fn [m k] (dissoc m k)) sm to-del))

    (print-section "data.avl/sorted-map")
    (run-bench (reduce (fn [m k] (dissoc m k)) am to-del))

    (print-section "ordered-map")
    (run-bench (reduce (fn [m k] (dissoc m k)) om to-del))))

(defn bench-map-lookup
  "Benchmark map lookup (get)."
  [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [pairs (generate-pairs n)
        sm    (into (sorted-map) pairs)
        am    (into (avl/sorted-map) pairs)
        om    (core/ordered-map pairs)
        ^ints ks (generate-lookup-keys n num-lookups)]
    (print-header (str "MAP LOOKUP (" num-lookups " gets): N=" n))

    (print-section "sorted-map")
    (run-bench (dotimes [i num-lookups] (get sm (aget ks i))))

    (print-section "data.avl/sorted-map")
    (run-bench (dotimes [i num-lookups] (get am (aget ks i))))

    (print-section "ordered-map")
    (run-bench (dotimes [i num-lookups] (om (aget ks i))))))

(defn bench-map-iteration
  "Benchmark map iteration via reduce."
  [n]
  (let [pairs (generate-pairs n)
        sm    (into (sorted-map) pairs)
        am    (into (avl/sorted-map) pairs)
        om    (core/ordered-map pairs)]
    (print-header (str "MAP ITERATION (reduce): N=" n))

    (print-section "sorted-map")
    (run-bench (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 sm))

    (print-section "data.avl/sorted-map")
    (run-bench (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 am))

    (print-section "ordered-map")
    (run-bench (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 om))))

(defn bench-map-fold
  "Benchmark map parallel fold via r/fold.
   Note: sorted-map and data.avl are compared via reduce since they don't
   implement CollFold and their r/fold fallback has compatibility issues."
  [n]
  (let [pairs (generate-pairs n)
        sm    (into (sorted-map) pairs)
        am    (into (avl/sorted-map) pairs)
        om    (core/ordered-map pairs)
        ;; Helper fn that extracts key from map entry
        sum-keys (fn [^long acc entry] (+ acc (long (key entry))))]
    (print-header (str "MAP FOLD: N=" n))

    (print-section "sorted-map (reduce baseline)")
    (run-bench (reduce sum-keys 0 sm))

    (print-section "data.avl/sorted-map (reduce baseline)")
    (run-bench (reduce sum-keys 0 am))

    (print-section "ordered-map (reduce)")
    (run-bench (reduce sum-keys 0 om))

    (print-section "ordered-map (r/fold parallel)")
    (run-bench (r/fold + sum-keys om))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-set-construction
  "Benchmark set construction."
  [n]
  (let [elems (generate-elements n)]
    (print-header (str "SET CONSTRUCTION: N=" n))

    (print-section "sorted-set (Clojure built-in)")
    (run-bench (into (sorted-set) elems))

    (print-section "data.avl/sorted-set")
    (run-bench (into (avl/sorted-set) elems))

    (print-section "ordered-set")
    (run-bench (core/ordered-set elems))))

(defn bench-set-insert
  "Benchmark sequential set insertion (conj one at a time)."
  [n]
  (let [elems (generate-elements n)]
    (print-header (str "SET INSERT (sequential conj): N=" n))

    (print-section "sorted-set")
    (run-bench
      (loop [s (sorted-set), xs (seq elems)]
        (if xs (recur (conj s (first xs)) (next xs)) s)))

    (print-section "data.avl/sorted-set")
    (run-bench
      (loop [s (avl/sorted-set), xs (seq elems)]
        (if xs (recur (conj s (first xs)) (next xs)) s)))

    (print-section "ordered-set")
    (run-bench
      (loop [s (core/ordered-set), xs (seq elems)]
        (if xs (recur (conj s (first xs)) (next xs)) s)))))

(defn bench-set-delete
  "Benchmark set deletion (disj half the elements)."
  [n]
  (let [elems  (range n)
        to-del (vec (take (quot n 2) (shuffle (range n))))
        ss     (into (sorted-set) elems)
        as     (into (avl/sorted-set) elems)
        os     (core/ordered-set elems)]
    (print-header (str "SET DELETE (disj N/2 elements): N=" n))

    (print-section "sorted-set")
    (run-bench (reduce (fn [s x] (disj s x)) ss to-del))

    (print-section "data.avl/sorted-set")
    (run-bench (reduce (fn [s x] (disj s x)) as to-del))

    (print-section "ordered-set")
    (run-bench (reduce (fn [s x] (disj s x)) os to-del))))

(defn bench-set-lookup
  "Benchmark set lookup (contains?)."
  [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints ks (generate-lookup-keys n num-lookups)]
    (print-header (str "SET LOOKUP (" num-lookups " contains?): N=" n))

    (print-section "sorted-set")
    (run-bench (dotimes [i num-lookups] (contains? ss (aget ks i))))

    (print-section "data.avl/sorted-set")
    (run-bench (dotimes [i num-lookups] (contains? as (aget ks i))))

    (print-section "ordered-set")
    (run-bench (dotimes [i num-lookups] (contains? os (aget ks i))))))

(defn bench-set-iteration
  "Benchmark set iteration via reduce."
  [n]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)]
    (print-header (str "SET ITERATION (reduce): N=" n))

    (print-section "sorted-set")
    (run-bench (reduce (fn [^long acc x] (+ acc (long x))) 0 ss))

    (print-section "data.avl/sorted-set")
    (run-bench (reduce (fn [^long acc x] (+ acc (long x))) 0 as))

    (print-section "ordered-set")
    (run-bench (reduce (fn [^long acc x] (+ acc (long x))) 0 os))))

(defn bench-set-fold
  "Benchmark set parallel fold via r/fold."
  [n]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        sum-elems (fn [^long acc x] (+ acc (long x)))]
    (print-header (str "SET PARALLEL FOLD (r/fold): N=" n))

    (print-section "sorted-set (falls back to sequential)")
    (run-bench (r/fold + sum-elems ss))

    (print-section "data.avl/sorted-set (falls back to sequential)")
    (run-bench (r/fold + sum-elems as))

    (print-section "ordered-set (true parallel)")
    (run-bench (r/fold + sum-elems os))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Operations (union, intersection, difference)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-set-union
  "Benchmark set union. Tests merging two sets with ~50% overlap."
  [n]
  (let [;; Create two sets with 50% overlap: [0, n) and [n/2, 3n/2)
        elems1 (range n)
        elems2 (range (quot n 2) (+ n (quot n 2)))
        ss1    (into (sorted-set) elems1)
        ss2    (into (sorted-set) elems2)
        as1    (into (avl/sorted-set) elems1)
        as2    (into (avl/sorted-set) elems2)
        os1    (core/ordered-set elems1)
        os2    (core/ordered-set elems2)]
    (print-header (str "SET UNION: Two sets of N=" n " with 50% overlap"))

    (print-section "sorted-set (clojure.set/union)")
    (run-bench (cset/union ss1 ss2))

    (print-section "data.avl/sorted-set (clojure.set/union)")
    (run-bench (cset/union as1 as2))

    (print-section "ordered-set (parallel union)")
    (run-bench (core/union os1 os2))))

(defn bench-set-intersection
  "Benchmark set intersection. Tests intersecting two sets with ~50% overlap."
  [n]
  (let [elems1 (range n)
        elems2 (range (quot n 2) (+ n (quot n 2)))
        ss1    (into (sorted-set) elems1)
        ss2    (into (sorted-set) elems2)
        as1    (into (avl/sorted-set) elems1)
        as2    (into (avl/sorted-set) elems2)
        os1    (core/ordered-set elems1)
        os2    (core/ordered-set elems2)]
    (print-header (str "SET INTERSECTION: Two sets of N=" n " with 50% overlap"))

    (print-section "sorted-set (clojure.set/intersection)")
    (run-bench (cset/intersection ss1 ss2))

    (print-section "data.avl/sorted-set (clojure.set/intersection)")
    (run-bench (cset/intersection as1 as2))

    (print-section "ordered-set (parallel intersection)")
    (run-bench (core/intersection os1 os2))))

(defn bench-set-difference
  "Benchmark set difference. Tests differing two sets with ~50% overlap."
  [n]
  (let [elems1 (range n)
        elems2 (range (quot n 2) (+ n (quot n 2)))
        ss1    (into (sorted-set) elems1)
        ss2    (into (sorted-set) elems2)
        as1    (into (avl/sorted-set) elems1)
        as2    (into (avl/sorted-set) elems2)
        os1    (core/ordered-set elems1)
        os2    (core/ordered-set elems2)]
    (print-header (str "SET DIFFERENCE: Two sets of N=" n " with 50% overlap"))

    (print-section "sorted-set (clojure.set/difference)")
    (run-bench (cset/difference ss1 ss2))

    (print-section "data.avl/sorted-set (clojure.set/difference)")
    (run-bench (cset/difference as1 as2))

    (print-section "ordered-set (parallel difference)")
    (run-bench (core/difference os1 os2))))

(defn run-set-operations-benchmarks
  "Run all set operation benchmarks at given size."
  [n]
  (bench-set-union n)
  (bench-set-intersection n)
  (bench-set-difference n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; First/Last Access
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-first-last
  "Benchmark first/last element access.
   This demonstrates the dramatic difference between O(log n) direct access
   and O(n) sequence traversal for `last`."
  [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [elems (range n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)]
    (print-header (str "FIRST/LAST ACCESS: " num-ops " operations, N=" n))

    (print-section "sorted-set first")
    (run-bench (dotimes [_ num-ops] (first ss)))

    (print-section "sorted-set last (O(n) - traverses entire seq)")
    (run-bench (dotimes [_ num-ops] (last ss)))

    (print-section "data.avl/sorted-set first")
    (run-bench (dotimes [_ num-ops] (first as)))

    (print-section "data.avl/sorted-set last (O(n) - traverses entire seq)")
    (run-bench (dotimes [_ num-ops] (last as)))

    (print-section "ordered-set first (O(log n) - direct tree access)")
    (run-bench (dotimes [_ num-ops] (.first ^java.util.SortedSet os)))

    (print-section "ordered-set last (O(log n) - direct tree access)")
    (run-bench (dotimes [_ num-ops] (.last ^java.util.SortedSet os)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specialty Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-rank-access
  "Benchmark nth (rank) access."
  [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints idxs (generate-lookup-keys n num-lookups)]
    (print-header (str "RANK ACCESS (nth): " num-lookups " lookups, N=" n))

    (print-section "data.avl/sorted-set")
    (run-bench (dotimes [i num-lookups] (nth as (aget idxs i))))

    (print-section "ordered-set")
    (run-bench (dotimes [i num-lookups] (nth os (aget idxs i))))))

(defn bench-rank-lookup
  "Benchmark rank-of (indexOf) operations."
  [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints ks (generate-lookup-keys n num-lookups)]
    (print-header (str "RANK LOOKUP (indexOf/rank-of): " num-lookups " lookups, N=" n))

    (print-section "data.avl/sorted-set (rank-of)")
    (run-bench (dotimes [i num-lookups] (avl/rank-of as (aget ks i))))

    (print-section "ordered-set (.indexOf)")
    (run-bench (dotimes [i num-lookups] (.indexOf ^java.util.List os (aget ks i))))))

(defn bench-split
  "Benchmark split operations."
  [n & {:keys [num-ops] :or {num-ops 100}}]
  (let [elems (generate-elements n)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints ks (generate-lookup-keys n num-ops)]
    (print-header (str "SPLIT: " num-ops " operations, N=" n))

    (print-section "data.avl/sorted-set (split-key)")
    (run-bench
      (dotimes [i num-ops]
        (avl/split-key (aget ks i) as)))

    (print-section "ordered-set (headSet + tailSet)")
    (run-bench
      (dotimes [i num-ops]
        (let [k (aget ks i)]
          [(.headSet ^java.util.SortedSet os k)
           (contains? os k)
           (.tailSet ^java.util.SortedSet os k)])))))

(defn bench-subseq
  "Benchmark subseq operations (clojure.lang.Sorted)."
  [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        os    (core/ordered-set elems)
        ;; Generate random ranges [lo, hi) where lo < hi
        ranges (vec (repeatedly num-ops
                      (fn []
                        (let [a (rand-int n)
                              b (rand-int n)]
                          [(min a b) (max a b)]))))]
    (print-header (str "SUBSEQ: " num-ops " range queries, N=" n))

    (print-section "sorted-set")
    (run-bench
      (doseq [[lo hi] ranges]
        (dorun (subseq ss >= lo < hi))))

    (print-section "ordered-set")
    (run-bench
      (doseq [[lo hi] ranges]
        (dorun (subseq os >= lo < hi))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Key Benchmarks (Custom Comparator)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private string-cmp
  (order/compare-by #(neg? (compare (str %1) (str %2)))))

(defn bench-string-map-construction
  "Benchmark map construction with string keys."
  [n]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))]
    (print-header (str "STRING MAP CONSTRUCTION: N=" n))

    (print-section "sorted-map-by")
    (run-bench (into (sorted-map-by cmp) pairs))

    (print-section "data.avl/sorted-map-by")
    (run-bench (into (avl/sorted-map-by cmp) pairs))

    (print-section "ordered-map (custom comparator)")
    (run-bench (core/ordered-map string-cmp pairs))))

(defn bench-string-map-lookup
  "Benchmark map lookup with string keys."
  [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))
        sm    (into (sorted-map-by cmp) pairs)
        am    (into (avl/sorted-map-by cmp) pairs)
        om    (core/ordered-map string-cmp pairs)
        ^objects look (object-array (repeatedly num-lookups #(nth ks (rand-int n))))]
    (print-header (str "STRING MAP LOOKUP: " num-lookups " gets, N=" n))

    (print-section "sorted-map-by")
    (run-bench (dotimes [i num-lookups] (get sm (aget look i))))

    (print-section "data.avl/sorted-map-by")
    (run-bench (dotimes [i num-lookups] (get am (aget look i))))

    (print-section "ordered-map")
    (run-bench (dotimes [i num-lookups] (om (aget look i))))))

(defn bench-string-map-iteration
  "Benchmark map iteration with string keys."
  [n]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))
        sm    (into (sorted-map-by cmp) pairs)
        am    (into (avl/sorted-map-by cmp) pairs)
        om    (core/ordered-map string-cmp pairs)]
    (print-header (str "STRING MAP ITERATION: N=" n))

    (print-section "sorted-map-by")
    (run-bench (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 sm))

    (print-section "data.avl/sorted-map-by")
    (run-bench (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 am))

    (print-section "ordered-map")
    (run-bench (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 om))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-interval-set-construction
  "Benchmark interval set construction."
  [n]
  (let [;; Generate n non-overlapping intervals [i, i+1]
        intervals (mapv (fn [i] [(* i 2) (inc (* i 2))]) (shuffle (range n)))]
    (print-header (str "INTERVAL SET CONSTRUCTION: N=" n))

    (print-section "interval-set")
    (run-bench (core/interval-set intervals))))

(defn bench-interval-map-construction
  "Benchmark interval map construction."
  [n]
  (let [;; Generate n non-overlapping intervals [i, i+1] -> value
        intervals (mapv (fn [i] [[(* i 2) (inc (* i 2))] (str "val-" i)])
                        (shuffle (range n)))]
    (print-header (str "INTERVAL MAP CONSTRUCTION: N=" n))

    (print-section "interval-map")
    (run-bench (core/interval-map (into {} intervals)))))

(defn bench-interval-lookup
  "Benchmark interval overlap lookup."
  [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [intervals (mapv (fn [i] [[(* i 2) (inc (* i 2))] (str "val-" i)])
                        (range n))
        im        (core/interval-map (into {} intervals))
        ;; Query points spread across the range
        max-point (* 2 n)
        ^ints points (int-array (repeatedly num-lookups #(rand-int max-point)))]
    (print-header (str "INTERVAL LOOKUP: " num-lookups " point queries, N=" n " intervals"))

    (print-section "interval-map")
    (run-bench (dotimes [i num-lookups] (im (aget points i))))))

(defn bench-interval-fold
  "Benchmark interval collection parallel fold."
  [n]
  (let [intervals (mapv (fn [i] [(* i 2) (inc (* i 2))]) (range n))
        is        (core/interval-set intervals)
        sum-intervals (fn [^long acc interval] (+ acc (long (first interval))))]
    (print-header (str "INTERVAL SET FOLD: N=" n))

    (print-section "interval-set reduce")
    (run-bench (reduce sum-intervals 0 is))

    (print-section "interval-set r/fold (parallel)")
    (run-bench (r/fold + sum-intervals is))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparison Benchmarks (Direct Head-to-Head)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn compare-lookup
  "Direct comparison of lookup performance."
  [n]
  (bench-map-lookup n)
  (bench-set-lookup n))

(defn compare-iteration
  "Direct comparison of iteration performance."
  [n]
  (bench-map-iteration n)
  (bench-set-iteration n))

(defn compare-fold
  "Direct comparison of parallel fold performance."
  [n]
  (bench-map-fold n)
  (bench-set-fold n))

(defn compare-construction
  "Direct comparison of construction performance."
  [n]
  (bench-map-construction n)
  (bench-set-construction n))

(defn compare-set-operations
  "Direct comparison of set operations (union, intersection, difference)."
  [n]
  (bench-set-union n)
  (bench-set-intersection n)
  (bench-set-difference n))

(defn compare-first-last
  "Direct comparison of first/last access."
  [n]
  (bench-first-last n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Suite Runners
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-map-benchmarks
  "Run all map benchmarks at given size."
  [n]
  (bench-map-construction n)
  (bench-map-insert n)
  (bench-map-delete n)
  (bench-map-lookup n)
  (bench-map-iteration n)
  (bench-map-fold n))

(defn run-set-benchmarks
  "Run all set benchmarks at given size."
  [n]
  (bench-set-construction n)
  (bench-set-insert n)
  (bench-set-delete n)
  (bench-set-lookup n)
  (bench-set-iteration n)
  (bench-set-fold n))

(defn run-specialty-benchmarks
  "Run specialty operation benchmarks at given size."
  [n]
  (bench-rank-access n)
  (bench-rank-lookup n)
  (bench-split n)
  (bench-subseq n)
  (bench-first-last n))

(defn run-string-benchmarks
  "Run string key benchmarks at given size."
  [n]
  (bench-string-map-construction n)
  (bench-string-map-lookup n)
  (bench-string-map-iteration n))

(defn run-interval-benchmarks
  "Run interval collection benchmarks at given size."
  [n]
  (bench-interval-set-construction n)
  (bench-interval-map-construction n)
  (bench-interval-lookup n)
  (bench-interval-fold n))

(defn run-all
  "Run the complete benchmark suite.

   Options:
     :sizes - vector of collection sizes to test (default [10000 100000])
     :quick - if true, use quick-bench for faster but less accurate results

   Note: Full benchmarks with default settings take 30-60 minutes."
  [& {:keys [sizes quick] :or {sizes [10000 100000] quick false}}]
  (binding [*quick-bench* quick]
    (println)
    (println "========================================================================")
    (println "  Criterium Benchmark Suite: ordered-collections")
    (println (str "  JVM: " (System/getProperty "java.version")
                  "  Clojure: " (clojure-version)))
    (println (str "  Mode: " (if quick "quick-bench" "bench (full statistical analysis)")))
    (println (str "  Sizes: " (pr-str sizes)))
    (println (str "  " (java.util.Date.)))
    (println "========================================================================")

    (doseq [n sizes]
      (println)
      (println "########################################################################")
      (println (str "                           N = " n))
      (println "########################################################################")

      (run-map-benchmarks n)
      (run-set-benchmarks n)
      (run-set-operations-benchmarks n)
      (run-specialty-benchmarks n)
      (run-string-benchmarks n)
      (run-interval-benchmarks n))

    (println)
    (println "========================================================================")
    (println "  Benchmark suite complete.")
    (println "========================================================================")))

(defn run-quick
  "Run a quick benchmark suite with reduced samples and smaller sizes.
   Takes approximately 10 minutes."
  []
  (run-all :sizes [1000 10000] :quick true))

(defn run-medium
  "Run a medium benchmark suite.
   Takes approximately 20-30 minutes."
  []
  (run-all :sizes [10000 100000] :quick true))

(defn run-full
  "Run the full benchmark suite with complete statistical analysis.
   Takes approximately 45-60 minutes."
  []
  (run-all :sizes [10000 100000 500000] :quick false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Individual Benchmark Helpers (for REPL use)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-single
  "Run a single benchmark with full Criterium analysis.

   Example:
     (bench-single 'sorted-map-lookup
       (let [m (into (sorted-map) (map #(vector % %) (range 10000)))
             ks (int-array (repeatedly 1000 #(rand-int 10000)))]
         (dotimes [i 1000] (get m (aget ks i)))))"
  [name & body]
  (print-header (str name))
  (crit/bench (do ~@body)))

(comment
  ;; Usage examples:

  ;; Quick comparison at N=10000
  (with-quick-bench
    (compare-lookup 10000))

  ;; Full analysis of iteration at N=100000
  (bench-set-iteration 100000)

  ;; Run medium suite
  (run-medium)

  ;; Run full suite
  (run-full)

  ;; Individual benchmarks
  (bench-map-fold 500000)
  (bench-set-fold 1000000)
  (bench-subseq 100000)

  ;; Set operations (major performance win)
  (with-quick-bench
    (compare-set-operations 100000))

  ;; First/last access (dramatic difference)
  (with-quick-bench
    (bench-first-last 100000))

  ;; Quick sanity check
  (with-quick-bench
    (bench-map-lookup 10000))
  )
