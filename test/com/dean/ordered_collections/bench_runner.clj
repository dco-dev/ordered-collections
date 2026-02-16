(ns com.dean.ordered-collections.bench-runner
  "Benchmark runner with EDN output for permanent record keeping.

   Usage:
     lein bench                  # Default: quick mode, N=100K (~5-10 min)
     lein bench --full           # Full rigor, N=10K,100K,500K (~60 min)
     lein bench --sizes 50000    # Custom sizes

   Output is written to bench-results/<timestamp>.edn"
  (:require [criterium.core :as crit]
            [clojure.core.reducers :as r]
            [clojure.data.avl :as avl]
            [clojure.set :as cset]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [com.dean.ordered-collections.bench-utils :as bu
             :refer [generate-pairs generate-elements generate-lookup-keys
                     generate-string-keys format-ns parse-standard-args]]
            [com.dean.ordered-collections.core :as core]
            [com.dean.ordered-collections.tree.order :as order])
  (:import [java.time Instant LocalDateTime]
           [java.time.format DateTimeFormatter])
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *quick-mode* false)

;; Size presets for Criterium benchmarks (smaller due to longer measurement time)
(def sizes-quick   [100000])
(def sizes-default [100000])
(def sizes-full    [10000 100000 500000])

(defn timestamp []
  (.format (LocalDateTime/now)
           (DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Execution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro bench-expr
  "Run benchmark and return results map. Uses quick-benchmark* or benchmark*
   based on *quick-mode*."
  [& body]
  `(let [results# (if *quick-mode*
                    (crit/quick-benchmark* (fn [] ~@body) {})
                    (crit/benchmark* (fn [] ~@body) {}))]
     {:mean-ns     (long (* 1e9 (first (:mean results#))))
      :stddev-ns   (long (* 1e9 (first (:variance results#))))
      :lower-q-ns  (long (* 1e9 (first (:lower-q results#))))
      :upper-q-ns  (long (* 1e9 (first (:upper-q results#))))
      :samples     (:sample-count results#)
      :outliers    (:outliers results#)}))

(def format-time format-ns)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Individual Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-map-construction [n]
  (let [pairs (generate-pairs n)]
    {:sorted-map  (do (print ".") (flush) (bench-expr (into (sorted-map) pairs)))
     :data-avl    (do (print ".") (flush) (bench-expr (into (avl/sorted-map) pairs)))
     :ordered-map (do (print ".") (flush) (bench-expr (core/ordered-map pairs)))}))

(defn bench-map-insert [n]
  (let [ks (generate-elements n)]
    {:sorted-map  (do (print ".") (flush)
                      (bench-expr
                        (loop [m (sorted-map), xs (seq ks)]
                          (if xs (recur (assoc m (first xs) true) (next xs)) m))))
     :data-avl    (do (print ".") (flush)
                      (bench-expr
                        (loop [m (avl/sorted-map), xs (seq ks)]
                          (if xs (recur (assoc m (first xs) true) (next xs)) m))))
     :ordered-map (do (print ".") (flush)
                      (bench-expr
                        (loop [m (core/ordered-map), xs (seq ks)]
                          (if xs (recur (assoc m (first xs) true) (next xs)) m))))}))

(defn bench-map-delete [n]
  (let [pairs   (map #(vector % true) (range n))
        to-del  (vec (take (quot n 2) (shuffle (range n))))
        sm      (into (sorted-map) pairs)
        am      (into (avl/sorted-map) pairs)
        om      (core/ordered-map pairs)]
    {:sorted-map  (do (print ".") (flush) (bench-expr (reduce (fn [m k] (dissoc m k)) sm to-del)))
     :data-avl    (do (print ".") (flush) (bench-expr (reduce (fn [m k] (dissoc m k)) am to-del)))
     :ordered-map (do (print ".") (flush) (bench-expr (reduce (fn [m k] (dissoc m k)) om to-del)))}))

(defn bench-map-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [pairs (generate-pairs n)
        sm    (into (sorted-map) pairs)
        am    (into (avl/sorted-map) pairs)
        om    (core/ordered-map pairs)
        ^ints ks (generate-lookup-keys n num-lookups)]
    {:sorted-map  (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (get sm (aget ks i)))))
     :data-avl    (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (get am (aget ks i)))))
     :ordered-map (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (om (aget ks i)))))}))

(defn bench-map-iteration [n]
  (let [pairs (generate-pairs n)
        sm    (into (sorted-map) pairs)
        am    (into (avl/sorted-map) pairs)
        om    (core/ordered-map pairs)]
    {:sorted-map  (do (print ".") (flush) (bench-expr (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 sm)))
     :data-avl    (do (print ".") (flush) (bench-expr (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 am)))
     :ordered-map (do (print ".") (flush) (bench-expr (reduce (fn [^long acc [k _]] (+ acc (long k))) 0 om)))}))

(defn bench-map-fold [n]
  (let [pairs (generate-pairs n)
        sm    (into (sorted-map) pairs)
        am    (into (avl/sorted-map) pairs)
        om    (core/ordered-map pairs)
        sum-keys (fn [^long acc entry] (+ acc (long (key entry))))]
    {:sorted-map-reduce  (do (print ".") (flush) (bench-expr (reduce sum-keys 0 sm)))
     :data-avl-reduce    (do (print ".") (flush) (bench-expr (reduce sum-keys 0 am)))
     :ordered-map-reduce (do (print ".") (flush) (bench-expr (reduce sum-keys 0 om)))
     :ordered-map-fold   (do (print ".") (flush) (bench-expr (r/fold + sum-keys om)))}))

(defn bench-set-construction [n]
  (let [elems (generate-elements n)]
    {:sorted-set  (do (print ".") (flush) (bench-expr (into (sorted-set) elems)))
     :data-avl    (do (print ".") (flush) (bench-expr (into (avl/sorted-set) elems)))
     :ordered-set (do (print ".") (flush) (bench-expr (core/ordered-set elems)))}))

(defn bench-set-insert [n]
  (let [elems (generate-elements n)]
    {:sorted-set  (do (print ".") (flush)
                      (bench-expr
                        (loop [s (sorted-set), xs (seq elems)]
                          (if xs (recur (conj s (first xs)) (next xs)) s))))
     :data-avl    (do (print ".") (flush)
                      (bench-expr
                        (loop [s (avl/sorted-set), xs (seq elems)]
                          (if xs (recur (conj s (first xs)) (next xs)) s))))
     :ordered-set (do (print ".") (flush)
                      (bench-expr
                        (loop [s (core/ordered-set), xs (seq elems)]
                          (if xs (recur (conj s (first xs)) (next xs)) s))))}))

(defn bench-set-delete [n]
  (let [elems  (range n)
        to-del (vec (take (quot n 2) (shuffle (range n))))
        ss     (into (sorted-set) elems)
        as     (into (avl/sorted-set) elems)
        os     (core/ordered-set elems)]
    {:sorted-set  (do (print ".") (flush) (bench-expr (reduce (fn [s x] (disj s x)) ss to-del)))
     :data-avl    (do (print ".") (flush) (bench-expr (reduce (fn [s x] (disj s x)) as to-del)))
     :ordered-set (do (print ".") (flush) (bench-expr (reduce (fn [s x] (disj s x)) os to-del)))}))

(defn bench-set-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints ks (generate-lookup-keys n num-lookups)]
    {:sorted-set  (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (contains? ss (aget ks i)))))
     :data-avl    (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (contains? as (aget ks i)))))
     :ordered-set (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (contains? os (aget ks i)))))}))

(defn bench-set-iteration [n]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)]
    {:sorted-set  (do (print ".") (flush) (bench-expr (reduce (fn [^long acc x] (+ acc (long x))) 0 ss)))
     :data-avl    (do (print ".") (flush) (bench-expr (reduce (fn [^long acc x] (+ acc (long x))) 0 as)))
     :ordered-set (do (print ".") (flush) (bench-expr (reduce (fn [^long acc x] (+ acc (long x))) 0 os)))}))

(defn bench-set-fold [n]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        sum-elems (fn [^long acc x] (+ acc (long x)))]
    {:sorted-set-fold  (do (print ".") (flush) (bench-expr (r/fold + sum-elems ss)))
     :data-avl-fold    (do (print ".") (flush) (bench-expr (r/fold + sum-elems as)))
     :ordered-set-fold (do (print ".") (flush) (bench-expr (r/fold + sum-elems os)))}))

(defn bench-set-union [n]
  (let [elems1 (range n)
        elems2 (range (quot n 2) (+ n (quot n 2)))
        ss1    (into (sorted-set) elems1)
        ss2    (into (sorted-set) elems2)
        as1    (into (avl/sorted-set) elems1)
        as2    (into (avl/sorted-set) elems2)
        os1    (core/ordered-set elems1)
        os2    (core/ordered-set elems2)]
    {:sorted-set  (do (print ".") (flush) (bench-expr (cset/union ss1 ss2)))
     :data-avl    (do (print ".") (flush) (bench-expr (cset/union as1 as2)))
     :ordered-set (do (print ".") (flush) (bench-expr (core/union os1 os2)))}))

(defn bench-set-intersection [n]
  (let [elems1 (range n)
        elems2 (range (quot n 2) (+ n (quot n 2)))
        ss1    (into (sorted-set) elems1)
        ss2    (into (sorted-set) elems2)
        as1    (into (avl/sorted-set) elems1)
        as2    (into (avl/sorted-set) elems2)
        os1    (core/ordered-set elems1)
        os2    (core/ordered-set elems2)]
    {:sorted-set  (do (print ".") (flush) (bench-expr (cset/intersection ss1 ss2)))
     :data-avl    (do (print ".") (flush) (bench-expr (cset/intersection as1 as2)))
     :ordered-set (do (print ".") (flush) (bench-expr (core/intersection os1 os2)))}))

(defn bench-set-difference [n]
  (let [elems1 (range n)
        elems2 (range (quot n 2) (+ n (quot n 2)))
        ss1    (into (sorted-set) elems1)
        ss2    (into (sorted-set) elems2)
        as1    (into (avl/sorted-set) elems1)
        as2    (into (avl/sorted-set) elems2)
        os1    (core/ordered-set elems1)
        os2    (core/ordered-set elems2)]
    {:sorted-set  (do (print ".") (flush) (bench-expr (cset/difference ss1 ss2)))
     :data-avl    (do (print ".") (flush) (bench-expr (cset/difference as1 as2)))
     :ordered-set (do (print ".") (flush) (bench-expr (core/difference os1 os2)))}))

(defn bench-first-last [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [elems (range n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)]
    {:sorted-set-first  (do (print ".") (flush) (bench-expr (dotimes [_ num-ops] (first ss))))
     :sorted-set-last   (do (print ".") (flush) (bench-expr (dotimes [_ num-ops] (last ss))))
     :data-avl-first    (do (print ".") (flush) (bench-expr (dotimes [_ num-ops] (first as))))
     :data-avl-last     (do (print ".") (flush) (bench-expr (dotimes [_ num-ops] (last as))))
     :ordered-set-first (do (print ".") (flush) (bench-expr (dotimes [_ num-ops] (.first ^java.util.SortedSet os))))
     :ordered-set-last  (do (print ".") (flush) (bench-expr (dotimes [_ num-ops] (.last ^java.util.SortedSet os))))}))

(defn bench-rank-access [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints idxs (generate-lookup-keys n num-lookups)]
    {:data-avl    (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (nth as (aget idxs i)))))
     :ordered-set (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (nth os (aget idxs i)))))}))

(defn bench-rank-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints ks (generate-lookup-keys n num-lookups)]
    {:data-avl    (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (avl/rank-of as (aget ks i)))))
     :ordered-set (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (.indexOf ^java.util.List os (aget ks i)))))}))

(defn bench-split [n & {:keys [num-ops] :or {num-ops 100}}]
  (let [elems (generate-elements n)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints ks (generate-lookup-keys n num-ops)]
    {:data-avl    (do (print ".") (flush)
                      (bench-expr (dotimes [i num-ops] (avl/split-key (aget ks i) as))))
     :ordered-set (do (print ".") (flush)
                      (bench-expr
                        (dotimes [i num-ops]
                          (let [k (aget ks i)]
                            [(.headSet ^java.util.SortedSet os k)
                             (contains? os k)
                             (.tailSet ^java.util.SortedSet os k)]))))}))

(def ^:private string-cmp
  (order/compare-by #(neg? (compare (str %1) (str %2)))))

(defn bench-string-construction [n]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))]
    {:sorted-map-by (do (print ".") (flush) (bench-expr (into (sorted-map-by cmp) pairs)))
     :data-avl      (do (print ".") (flush) (bench-expr (into (avl/sorted-map-by cmp) pairs)))
     :ordered-map   (do (print ".") (flush) (bench-expr (core/ordered-map string-cmp pairs)))}))

(defn bench-string-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))
        sm    (into (sorted-map-by cmp) pairs)
        am    (into (avl/sorted-map-by cmp) pairs)
        om    (core/ordered-map string-cmp pairs)
        ^objects look (object-array (repeatedly num-lookups #(nth ks (rand-int n))))]
    {:sorted-map-by (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (get sm (aget look i)))))
     :data-avl      (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (get am (aget look i)))))
     :ordered-map   (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (om (aget look i)))))}))

(defn bench-string-iteration [n]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))
        sm    (into (sorted-map-by cmp) pairs)
        am    (into (avl/sorted-map-by cmp) pairs)
        om    (core/ordered-map string-cmp pairs)]
    {:sorted-map-by (do (print ".") (flush) (bench-expr (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 sm)))
     :data-avl      (do (print ".") (flush) (bench-expr (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 am)))
     :ordered-map   (do (print ".") (flush) (bench-expr (reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 om)))}))

(defn bench-interval-construction [n]
  (let [intervals (mapv (fn [i] [(* i 2) (inc (* i 2))]) (shuffle (range n)))]
    {:interval-set (do (print ".") (flush) (bench-expr (core/interval-set intervals)))}))

(defn bench-interval-map-construction [n]
  (let [intervals (mapv (fn [i] [[(* i 2) (inc (* i 2))] (str "val-" i)])
                        (shuffle (range n)))]
    {:interval-map (do (print ".") (flush) (bench-expr (core/interval-map (into {} intervals))))}))

(defn bench-interval-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [intervals (mapv (fn [i] [[(* i 2) (inc (* i 2))] (str "val-" i)])
                        (range n))
        im        (core/interval-map (into {} intervals))
        max-point (* 2 n)
        ^ints points (int-array (repeatedly num-lookups #(rand-int max-point)))]
    {:interval-map (do (print ".") (flush) (bench-expr (dotimes [i num-lookups] (im (aget points i)))))}))

(defn bench-interval-fold [n]
  (let [intervals (mapv (fn [i] [(* i 2) (inc (* i 2))]) (range n))
        is        (core/interval-set intervals)
        sum-intervals (fn [^long acc interval] (+ acc (long (first interval))))]
    {:interval-set-reduce (do (print ".") (flush) (bench-expr (reduce sum-intervals 0 is)))
     :interval-set-fold   (do (print ".") (flush) (bench-expr (r/fold + sum-intervals is)))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Suite Runners
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-wins-benchmarks
  "Run benchmarks focused on where ordered-collections wins."
  [sizes]
  (let [results (atom {})]
    (doseq [n sizes]
      (println)
      (println (str "===== N = " n " ====="))

      ;; Construction (we win via parallel fold)
      (print "  set-construction") (swap! results assoc-in [n :set-construction] (bench-set-construction n)) (println)
      (print "  map-construction") (swap! results assoc-in [n :map-construction] (bench-map-construction n)) (println)

      ;; Parallel fold (we win - true parallelism)
      (print "  set-fold") (swap! results assoc-in [n :set-fold] (bench-set-fold n)) (println)
      (print "  map-fold") (swap! results assoc-in [n :map-fold] (bench-map-fold n)) (println)

      ;; Set operations (we win 5-10x)
      (print "  set-union") (swap! results assoc-in [n :set-union] (bench-set-union n)) (println)
      (print "  set-intersection") (swap! results assoc-in [n :set-intersection] (bench-set-intersection n)) (println)
      (print "  set-difference") (swap! results assoc-in [n :set-difference] (bench-set-difference n)) (println)

      ;; Split (we win 3x)
      (print "  split") (swap! results assoc-in [n :split] (bench-split n)) (println)

      ;; First/last (we win ~100,000x for last) - skip for very large N
      (when (<= n 100000)
        (print "  first-last") (swap! results assoc-in [n :first-last] (bench-first-last n)) (println))

      ;; Rank access (we match data.avl)
      (print "  rank-access") (swap! results assoc-in [n :rank-access] (bench-rank-access n)) (println))

    @results))

(defn run-all-benchmarks
  "Run all benchmarks comprehensively."
  [sizes]
  (let [results (atom {})]
    (doseq [n sizes]
      (println)
      (println (str "===== N = " n " ====="))

      (print "  map-construction") (swap! results assoc-in [n :map-construction] (bench-map-construction n)) (println)
      (print "  map-insert") (swap! results assoc-in [n :map-insert] (bench-map-insert n)) (println)
      (print "  map-delete") (swap! results assoc-in [n :map-delete] (bench-map-delete n)) (println)
      (print "  map-lookup") (swap! results assoc-in [n :map-lookup] (bench-map-lookup n)) (println)
      (print "  map-iteration") (swap! results assoc-in [n :map-iteration] (bench-map-iteration n)) (println)
      (print "  map-fold") (swap! results assoc-in [n :map-fold] (bench-map-fold n)) (println)

      (print "  set-construction") (swap! results assoc-in [n :set-construction] (bench-set-construction n)) (println)
      (print "  set-insert") (swap! results assoc-in [n :set-insert] (bench-set-insert n)) (println)
      (print "  set-delete") (swap! results assoc-in [n :set-delete] (bench-set-delete n)) (println)
      (print "  set-lookup") (swap! results assoc-in [n :set-lookup] (bench-set-lookup n)) (println)
      (print "  set-iteration") (swap! results assoc-in [n :set-iteration] (bench-set-iteration n)) (println)
      (print "  set-fold") (swap! results assoc-in [n :set-fold] (bench-set-fold n)) (println)

      (print "  set-union") (swap! results assoc-in [n :set-union] (bench-set-union n)) (println)
      (print "  set-intersection") (swap! results assoc-in [n :set-intersection] (bench-set-intersection n)) (println)
      (print "  set-difference") (swap! results assoc-in [n :set-difference] (bench-set-difference n)) (println)

      (print "  rank-access") (swap! results assoc-in [n :rank-access] (bench-rank-access n)) (println)
      (print "  rank-lookup") (swap! results assoc-in [n :rank-lookup] (bench-rank-lookup n)) (println)
      (print "  split") (swap! results assoc-in [n :split] (bench-split n)) (println)

      ;; Skip first/last for large N (sorted-set last is O(n) and takes forever)
      (when (<= n 100000)
        (print "  first-last") (swap! results assoc-in [n :first-last] (bench-first-last n)) (println))

      (print "  string-construction") (swap! results assoc-in [n :string-construction] (bench-string-construction n)) (println)
      (print "  string-lookup") (swap! results assoc-in [n :string-lookup] (bench-string-lookup n)) (println)
      (print "  string-iteration") (swap! results assoc-in [n :string-iteration] (bench-string-iteration n)) (println)

      (print "  interval-construction") (swap! results assoc-in [n :interval-construction] (bench-interval-construction n)) (println)
      (print "  interval-map-construction") (swap! results assoc-in [n :interval-map-construction] (bench-interval-map-construction n)) (println)
      (print "  interval-lookup") (swap! results assoc-in [n :interval-lookup] (bench-interval-lookup n)) (println)
      (print "  interval-fold") (swap! results assoc-in [n :interval-fold] (bench-interval-fold n)) (println))

    @results))

(defn system-info []
  {:java-version  (System/getProperty "java.version")
   :java-vm       (System/getProperty "java.vm.name")
   :os-name       (System/getProperty "os.name")
   :os-version    (System/getProperty "os.version")
   :os-arch       (System/getProperty "os.arch")
   :clojure       (clojure-version)
   :processors    (.availableProcessors (Runtime/getRuntime))
   :max-memory-mb (quot (.maxMemory (Runtime/getRuntime)) (* 1024 1024))})

(defn write-results [results output-file opts]
  (let [full-results {:timestamp   (str (Instant/now))
                      :system      (system-info)
                      :mode        (if (:quick opts) :quick :full)
                      :sizes       (:sizes opts)
                      :benchmarks  results}]
    (io/make-parents output-file)
    (spit output-file (with-out-str (pp/pprint full-results)))
    (println)
    (println (str "Results written to: " output-file))))

(defn print-summary [results]
  (println)
  (println "===== SUMMARY =====")
  (println)
  (doseq [[n benches] (sort-by key results)]
    (println (str "N = " n))
    (doseq [[bench-name bench-results] (sort-by key benches)]
      (println (str "  " (name bench-name) ":"))
      (doseq [[impl data] (sort-by key bench-results)]
        (println (str "    " (name impl) ": " (format-time (:mean-ns data))))))
    (println)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-args [args]
  (parse-standard-args args sizes-quick sizes-default sizes-full))

(defn print-usage []
  (println "Usage: lein bench [options]")
  (println)
  (println "Options:")
  (println "  --quick            Quick mode with N=100K (default)")
  (println "  --full             Full rigor with N=10K,100K,500K")
  (println "  --sizes N,N,...    Custom sizes (comma-separated)")
  (println "  --help             Show this help")
  (println)
  (println "Output is written to bench-results/<timestamp>.edn"))

(defn -main [& args]
  (let [opts (parse-args args)]
    (if (:help opts)
      (print-usage)
      (let [output-dir "bench-results"
            output-file (str output-dir "/" (timestamp) ".edn")]
        (println)
        (println "========================================================================")
        (println "  Ordered Collections Benchmark Suite")
        (println "========================================================================")
        (println)
        (println "System info:")
        (doseq [[k v] (system-info)]
          (println (str "  " (name k) ": " v)))
        (println)
        (println (str "Mode: " (if (:quick opts) "quick" "full")))
        (println (str "Sizes: " (pr-str (:sizes opts))))
        (println (str "Output: " output-file))
        (println)

        (binding [*quick-mode* (:quick opts)]
          (let [results (run-all-benchmarks (:sizes opts))]
            (print-summary results)
            (write-results results output-file opts)))

        (println)
        (println "Benchmark suite complete.")))
    (shutdown-agents)))
