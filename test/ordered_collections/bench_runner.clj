(ns ordered-collections.bench-runner
  "Benchmark runner with EDN output for permanent record keeping.

   Usage:
     lein bench                  # Default: N=100K (~5 min)
     lein bench --full           # N=1K,5K,10K,100K,500K (~60 min)
     lein bench --sizes 50000    # Custom sizes

   Output is written to bench-results/<timestamp>.edn"
  (:require [criterium.core :as crit]
            [clojure.core.reducers :as r]
            [clojure.data.avl :as avl]
            [clojure.edn :as edn]
            [clojure.set :as cset]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [ordered-collections.bench-utils :as bu
             :refer [generate-pairs generate-elements generate-lookup-keys
                     generate-string-keys build-map-variants build-set-variants
                     build-string-map-variants overlapping-set-variants
                     split-workload fold-frequency-workload set-comparison-workload format-ns
                     parse-standard-args]]
            [ordered-collections.core :as core]
            [ordered-collections.kernel.order :as order]
            [ordered-collections.kernel.rope :as ropetree])
  (:import [java.lang.management ManagementFactory]
           [java.net InetAddress]
           [java.time Duration Instant LocalDateTime]
           [java.time.format DateTimeFormatter]
           [com.google.common.collect TreeRangeMap Range])
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sizes-default [100000])
(def sizes-full    [1000 5000 10000 100000 500000])

(defn timestamp []
  (.format (LocalDateTime/now)
           (DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Execution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- nanos [seconds]
  (long (* 1e9 (double seconds))))

(defn- estimate-point [[point _]]
  point)

(defn- estimate-interval [[_ interval]]
  (when interval
    (mapv nanos interval)))

(defn- estimate-nanos [estimate]
  (when estimate
    (nanos (estimate-point estimate))))

(defn- estimate-stdev-nanos [estimate]
  (when estimate
    (nanos (Math/sqrt (double (estimate-point estimate))))))

(defn- normalize-criterium-result [results]
  {:mean-ns           (estimate-nanos (:mean results))
   :mean-ci-ns        (estimate-interval (:mean results))
   :sample-mean-ns    (estimate-nanos (:sample-mean results))
   :sample-mean-ci-ns (estimate-interval (:sample-mean results))
   :stdev-ns          (estimate-stdev-nanos (:variance results))
   :sample-stdev-ns   (estimate-stdev-nanos (:sample-variance results))
   :lower-q-ns        (estimate-nanos (:lower-q results))
   :lower-q-ci-ns     (estimate-interval (:lower-q results))
   :upper-q-ns        (estimate-nanos (:upper-q results))
   :upper-q-ci-ns     (estimate-interval (:upper-q results))
   :sample-count      (:sample-count results)
   :execution-count   (:execution-count results)
   :warmup-jit-period (:warmup-jit-period results)
   :samples           (:sample-count results)
   :outliers          (:outliers results)})

(defn- bench-thunk
  "Run benchmark thunk using Criterium quick-benchmark* and return normalized results."
  [thunk]
  (normalize-criterium-result (crit/quick-benchmark* thunk {})))

(defn- progress! []
  (print ".")
  (flush))

(defn- run-case [thunk]
  (progress!)
  (bench-thunk thunk))

(defn- run-cases
  "Run a map of benchmark label -> zero-arg thunk."
  [cases]
  (reduce-kv (fn [m k thunk]
               (assoc m k (run-case thunk)))
             {}
             cases))

(def format-time format-ns)

(defn- run-benchmark!
  [results n bench-key bench-fn]
  (print (str "  " (name bench-key)))
  (swap! results assoc-in [n bench-key] (bench-fn n))
  (println))

(defn- run-benchmarks-for-size!
  [results n bench-specs]
  (println)
  (println (str "===== N = " n " ====="))
  (doseq [spec bench-specs
          :let [{:keys [key fn when]} (if (map? spec)
                                        spec
                                        {:key (first spec) :fn (second spec)})]
          :when (if when (when n) true)]
    (run-benchmark! results n key fn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Individual Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-map-construction [n]
  (let [pairs (generate-pairs n)]
    (run-cases
      {:sorted-map  #(into (sorted-map) pairs)
       :data-avl    #(into (avl/sorted-map) pairs)
       :ordered-map #(core/ordered-map pairs)})))

(defn bench-map-insert [n]
  (let [ks (generate-elements n)]
    (run-cases
      {:sorted-map  #(loop [m (sorted-map), xs (seq ks)]
                       (if xs (recur (assoc m (first xs) true) (next xs)) m))
       :data-avl    #(loop [m (avl/sorted-map), xs (seq ks)]
                       (if xs (recur (assoc m (first xs) true) (next xs)) m))
       :ordered-map #(loop [m (core/ordered-map), xs (seq ks)]
                       (if xs (recur (assoc m (first xs) true) (next xs)) m))})))

(defn bench-map-delete [n]
  (let [pairs   (map #(vector % true) (range n))
        to-del  (vec (take (quot n 2) (shuffle (range n))))
        sm      (into (sorted-map) pairs)
        am      (into (avl/sorted-map) pairs)
        om      (core/ordered-map pairs)]
    (run-cases
      {:sorted-map  #(reduce (fn [m k] (dissoc m k)) sm to-del)
       :data-avl    #(reduce (fn [m k] (dissoc m k)) am to-del)
       :ordered-map #(reduce (fn [m k] (dissoc m k)) om to-del)})))

(defn bench-map-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [pairs (generate-pairs n)
        {:keys [sorted-map data-avl ordered-map]} (build-map-variants pairs)
        ^ints ks (generate-lookup-keys n num-lookups)]
    (run-cases
      {:sorted-map  #(dotimes [i num-lookups] (get sorted-map (aget ks i)))
       :data-avl    #(dotimes [i num-lookups] (get data-avl (aget ks i)))
       :ordered-map #(dotimes [i num-lookups] (ordered-map (aget ks i)))})))

(defn bench-map-iteration [n]
  (let [pairs (generate-pairs n)
        sm    (into (sorted-map) pairs)
        am    (into (avl/sorted-map) pairs)
        om    (core/ordered-map pairs)]
    (run-cases
      {:sorted-map  #(reduce (fn [^long acc [k _]] (+ acc (long k))) 0 sm)
       :data-avl    #(reduce (fn [^long acc [k _]] (+ acc (long k))) 0 am)
       :ordered-map #(reduce (fn [^long acc [k _]] (+ acc (long k))) 0 om)})))

(defn bench-map-fold [n]
  (let [pairs (generate-pairs n)
        {:keys [sorted-map data-avl ordered-map]} (build-map-variants pairs)
        sum-keys (fn [^long acc entry] (+ acc (long (key entry))))]
    (run-cases
      {:sorted-map-reduce  #(reduce sum-keys 0 sorted-map)
       :data-avl-reduce    #(reduce sum-keys 0 data-avl)
       :ordered-map-reduce #(reduce sum-keys 0 ordered-map)
       :ordered-map-fold   #(r/fold + sum-keys ordered-map)})))

(defn bench-set-construction [n]
  (let [elems (generate-elements n)]
    (run-cases
      {:sorted-set  #(into (sorted-set) elems)
       :data-avl    #(into (avl/sorted-set) elems)
       :ordered-set #(core/ordered-set elems)})))

(defn bench-set-insert [n]
  (let [elems (generate-elements n)]
    (run-cases
      {:sorted-set  #(loop [s (sorted-set), xs (seq elems)]
                       (if xs (recur (conj s (first xs)) (next xs)) s))
       :data-avl    #(loop [s (avl/sorted-set), xs (seq elems)]
                       (if xs (recur (conj s (first xs)) (next xs)) s))
       :ordered-set #(loop [s (core/ordered-set), xs (seq elems)]
                       (if xs (recur (conj s (first xs)) (next xs)) s))})))

(defn bench-set-delete [n]
  (let [elems  (range n)
        to-del (vec (take (quot n 2) (shuffle (range n))))
        ss     (into (sorted-set) elems)
        as     (into (avl/sorted-set) elems)
        os     (core/ordered-set elems)]
    (run-cases
      {:sorted-set  #(reduce (fn [s x] (disj s x)) ss to-del)
       :data-avl    #(reduce (fn [s x] (disj s x)) as to-del)
       :ordered-set #(reduce (fn [s x] (disj s x)) os to-del)})))

(defn bench-set-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        {:keys [sorted-set data-avl ordered-set]} (build-set-variants elems)
        ^ints ks (generate-lookup-keys n num-lookups)]
    (run-cases
      {:sorted-set  #(dotimes [i num-lookups] (contains? sorted-set (aget ks i)))
       :data-avl    #(dotimes [i num-lookups] (contains? data-avl (aget ks i)))
       :ordered-set #(dotimes [i num-lookups] (contains? ordered-set (aget ks i)))})))

(defn bench-set-iteration [n]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)]
    (run-cases
      {:sorted-set  #(reduce (fn [^long acc x] (+ acc (long x))) 0 ss)
       :data-avl    #(reduce (fn [^long acc x] (+ acc (long x))) 0 as)
       :ordered-set #(reduce (fn [^long acc x] (+ acc (long x))) 0 os)})))

(defn- iterate-sum-longs
  "Sum a Java Iterable of Long values via its .iterator(). Exercises the
   collection's iterator path rather than its CollReduce path."
  ^long [^Iterable coll]
  (let [it (.iterator coll)]
    (loop [acc (long 0)]
      (if (.hasNext it)
        (recur (unchecked-add acc (long (.next it))))
        acc))))

(defn bench-set-iteration-iterator [n]
  (let [elems (generate-elements n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)]
    (run-cases
      {:sorted-set  #(iterate-sum-longs ss)
       :data-avl    #(iterate-sum-longs as)
       :ordered-set #(iterate-sum-longs os)})))

(defn bench-set-equality [n]
  (let [{equal :equal
         different :different
         size-different :size-different} (set-comparison-workload n)
        cases {:equal equal
               :different different
               :size-different size-different}]
    (into {}
          (for [[case-key {:keys [left right]}] cases
                :let [hs1 (:hash-set left), hs2 (:hash-set right)
                      as1 (:data-avl left), as2 (:data-avl right)
                      os1 (:ordered-set left), os2 (:ordered-set right)]]
            [case-key
             (run-cases
               {:hash-set    #(= hs1 hs2)
                :sorted-set  #(= (:sorted-set left) (:sorted-set right))
                :data-avl    #(= as1 as2)
                :ordered-set #(= os1 os2)})]))))

(defn bench-set-fold [n]
  (let [elems (generate-elements n)
        {:keys [sorted-set data-avl ordered-set]} (build-set-variants elems)
        sum-elems (fn [^long acc x] (+ acc (long x)))]
    (run-cases
      {:sorted-set-fold  #(r/fold + sum-elems sorted-set)
       :data-avl-fold    #(r/fold + sum-elems data-avl)
       :ordered-set-fold #(r/fold + sum-elems ordered-set)})))

(defn bench-set-fold-freq [n]
  (let [elems (generate-elements n)
        {:keys [sets combinef reducef]} (fold-frequency-workload elems)
        {:keys [sorted-set data-avl ordered-set]} sets]
    (run-cases
      {:sorted-set-fold-freq  #(r/fold combinef reducef sorted-set)
       :data-avl-fold-freq    #(r/fold combinef reducef data-avl)
       :ordered-set-fold-freq #(r/fold combinef reducef ordered-set)})))

(defn bench-set-reduce-vs-fold-freq [n]
  (let [elems (generate-elements n)
        {:keys [sets combinef reducef]} (fold-frequency-workload elems)
        {:keys [sorted-set data-avl ordered-set]} sets]
    (run-cases
      {:sorted-set-reduce-freq  #(reduce reducef {} sorted-set)
       :sorted-set-fold-freq    #(r/fold combinef reducef sorted-set)
       :data-avl-reduce-freq    #(reduce reducef {} data-avl)
       :data-avl-fold-freq      #(r/fold combinef reducef data-avl)
       :ordered-set-reduce-freq #(reduce reducef {} ordered-set)
       :ordered-set-fold-freq   #(r/fold combinef reducef ordered-set)})))

(defn bench-set-union [n]
  (let [{left :left right :right} (overlapping-set-variants n)
        hs1 (:hash-set left), hs2 (:hash-set right)
        ss1 (:sorted-set left), ss2 (:sorted-set right)
        as1 (:data-avl left), as2 (:data-avl right)
        os1 (:ordered-set left), os2 (:ordered-set right)]
    (run-cases
      {:clojure-set #(cset/union hs1 hs2)
       :sorted-set  #(cset/union ss1 ss2)
       :data-avl    #(cset/union as1 as2)
       :ordered-set #(core/union os1 os2)})))

(defn bench-set-intersection [n]
  (let [{left :left right :right} (overlapping-set-variants n)
        hs1 (:hash-set left), hs2 (:hash-set right)
        ss1 (:sorted-set left), ss2 (:sorted-set right)
        as1 (:data-avl left), as2 (:data-avl right)
        os1 (:ordered-set left), os2 (:ordered-set right)]
    (run-cases
      {:clojure-set #(cset/intersection hs1 hs2)
       :sorted-set  #(cset/intersection ss1 ss2)
       :data-avl    #(cset/intersection as1 as2)
       :ordered-set #(core/intersection os1 os2)})))

(defn bench-set-difference [n]
  (let [{left :left right :right} (overlapping-set-variants n)
        hs1 (:hash-set left), hs2 (:hash-set right)
        ss1 (:sorted-set left), ss2 (:sorted-set right)
        as1 (:data-avl left), as2 (:data-avl right)
        os1 (:ordered-set left), os2 (:ordered-set right)]
    (run-cases
      {:clojure-set #(cset/difference hs1 hs2)
       :sorted-set  #(cset/difference ss1 ss2)
       :data-avl    #(cset/difference as1 as2)
       :ordered-set #(core/difference os1 os2)})))

(defn bench-first-last [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [elems (range n)
        ss    (into (sorted-set) elems)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)]
    (run-cases
      {:sorted-set-first  #(dotimes [_ num-ops] (first ss))
       :sorted-set-last   #(dotimes [_ num-ops] (last ss))
       :data-avl-first    #(dotimes [_ num-ops] (first as))
       :data-avl-last     #(dotimes [_ num-ops] (last as))
       :ordered-set-first #(dotimes [_ num-ops] (.first ^java.util.SortedSet os))
       :ordered-set-last  #(dotimes [_ num-ops] (.last ^java.util.SortedSet os))})))

(defn bench-rank-access [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints idxs (generate-lookup-keys n num-lookups)]
    (run-cases
      {:data-avl    #(dotimes [i num-lookups] (nth as (aget idxs i)))
       :ordered-set #(dotimes [i num-lookups] (nth os (aget idxs i)))})))

(defn bench-rank-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [elems (generate-elements n)
        as    (into (avl/sorted-set) elems)
        os    (core/ordered-set elems)
        ^ints ks (generate-lookup-keys n num-lookups)]
    (run-cases
      {:data-avl    #(dotimes [i num-lookups] (avl/rank-of as (aget ks i)))
       :ordered-set #(dotimes [i num-lookups] (core/rank os (aget ks i)))})))

(defn bench-split [n & {:keys [num-ops] :or {num-ops 100}}]
  (let [{:keys [data-avl ordered-set keys]} (split-workload n num-ops)
        ^ints ks keys]
    (run-cases
      {:data-avl    #(dotimes [i num-ops] (avl/split-key (aget ks i) data-avl))
       :ordered-set #(dotimes [i num-ops] (core/split-key (aget ks i) ordered-set))})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Long-Specialized Set Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-long-sets
  "Build test sets with Long elements for all four contenders."
  [n]
  (let [elems (vec (shuffle (range n)))]
    {:hash-set     (into #{} elems)
     :sorted-set   (into (sorted-set) elems)
     :data-avl     (into (avl/sorted-set) elems)
     :long-ordered (core/long-ordered-set elems)}))

(defn- make-long-set-pair
  "Build overlapping Long set pairs for set algebra benchmarks.
  Matches the standard overlapping-set-elements workload: two sets of
  size n with 50% overlap."
  [n]
  (let [elems1 (range 0 n)
        elems2 (range (quot n 2) (+ n (quot n 2)))]
    {:left  {:hash-set     (into #{} elems1)
             :sorted-set   (into (sorted-set) elems1)
             :data-avl     (into (avl/sorted-set) elems1)
             :long-ordered (core/long-ordered-set elems1)}
     :right {:hash-set     (into #{} elems2)
             :sorted-set   (into (sorted-set) elems2)
             :data-avl     (into (avl/sorted-set) elems2)
             :long-ordered (core/long-ordered-set elems2)}}))

(defn bench-long-construction [n]
  (let [elems (vec (shuffle (range n)))]
    (run-cases
      {:hash-set     #(into #{} elems)
       :sorted-set   #(into (sorted-set) elems)
       :data-avl     #(into (avl/sorted-set) elems)
       :long-ordered #(core/long-ordered-set elems)})))

(defn bench-long-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [{:keys [hash-set sorted-set data-avl long-ordered]} (make-long-sets n)
        ^longs ks (long-array (repeatedly num-lookups #(rand-int n)))]
    (run-cases
      {:hash-set     #(dotimes [i num-lookups] (contains? hash-set (aget ks i)))
       :sorted-set   #(dotimes [i num-lookups] (contains? sorted-set (aget ks i)))
       :data-avl     #(dotimes [i num-lookups] (contains? data-avl (aget ks i)))
       :long-ordered #(dotimes [i num-lookups] (contains? long-ordered (aget ks i)))})))

(defn bench-long-insert [n]
  (let [elems (vec (shuffle (range n)))]
    (run-cases
      {:sorted-set   #(reduce conj (sorted-set) elems)
       :data-avl     #(reduce conj (avl/sorted-set) elems)
       :long-ordered #(reduce conj (core/long-ordered-set) elems)})))

(defn bench-long-delete [n]
  (let [{:keys [sorted-set data-avl long-ordered]} (make-long-sets n)
        to-del (vec (take (quot n 2) (shuffle (range n))))]
    (run-cases
      {:sorted-set   #(reduce disj sorted-set to-del)
       :data-avl     #(reduce disj data-avl to-del)
       :long-ordered #(reduce disj long-ordered to-del)})))

(defn bench-long-iteration [n]
  (let [{:keys [sorted-set data-avl long-ordered]} (make-long-sets n)]
    (run-cases
      {:sorted-set   #(reduce (fn [^long acc x] (+ acc (long x))) 0 sorted-set)
       :data-avl     #(reduce (fn [^long acc x] (+ acc (long x))) 0 data-avl)
       :long-ordered #(reduce (fn [^long acc x] (+ acc (long x))) 0 long-ordered)})))

(defn bench-long-rank-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [{:keys [data-avl long-ordered]} (make-long-sets n)
        ^longs ks (long-array (repeatedly num-lookups #(rand-int n)))]
    (run-cases
      {:data-avl     #(dotimes [i num-lookups] (avl/rank-of data-avl (aget ks i)))
       :long-ordered #(dotimes [i num-lookups] (core/rank long-ordered (aget ks i)))})))

(defn bench-long-fold [n]
  (let [{:keys [sorted-set data-avl long-ordered]} (make-long-sets n)
        sum-fn (fn [^long acc x] (+ acc (long x)))]
    (run-cases
      {:sorted-set-fold   #(r/fold + sum-fn sorted-set)
       :data-avl-fold     #(r/fold + sum-fn data-avl)
       :long-ordered-fold #(r/fold + sum-fn long-ordered)})))

(defn bench-long-union [n]
  (let [{left :left right :right} (make-long-set-pair n)]
    (run-cases
      {:clojure-set  #(cset/union (:hash-set left) (:hash-set right))
       :sorted-set   #(cset/union (:sorted-set left) (:sorted-set right))
       :data-avl     #(cset/union (:data-avl left) (:data-avl right))
       :long-ordered #(core/union (:long-ordered left) (:long-ordered right))})))

(defn bench-long-intersection [n]
  (let [{left :left right :right} (make-long-set-pair n)]
    (run-cases
      {:clojure-set  #(cset/intersection (:hash-set left) (:hash-set right))
       :sorted-set   #(cset/intersection (:sorted-set left) (:sorted-set right))
       :data-avl     #(cset/intersection (:data-avl left) (:data-avl right))
       :long-ordered #(core/intersection (:long-ordered left) (:long-ordered right))})))

(defn bench-long-difference [n]
  (let [{left :left right :right} (make-long-set-pair n)]
    (run-cases
      {:clojure-set  #(cset/difference (:hash-set left) (:hash-set right))
       :sorted-set   #(cset/difference (:sorted-set left) (:sorted-set right))
       :data-avl     #(cset/difference (:data-avl left) (:data-avl right))
       :long-ordered #(core/difference (:long-ordered left) (:long-ordered right))})))

(defn bench-long-split [n & {:keys [num-ops] :or {num-ops 100}}]
  (let [as (into (avl/sorted-set) (range n))
        ls (core/long-ordered-set (range n))
        ^longs ks (long-array (repeatedly num-ops #(rand-int n)))]
    (run-cases
      {:data-avl     #(dotimes [i num-ops] (avl/split-key (aget ks i) as))
       :long-ordered #(dotimes [i num-ops] (core/split-key (aget ks i) ls))})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private string-cmp
  (order/compare-by #(neg? (compare (str %1) (str %2)))))

(defn bench-string-construction [n]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))]
    (run-cases
      {:sorted-map-by #(into (sorted-map-by cmp) pairs)
       :data-avl      #(into (avl/sorted-map-by cmp) pairs)
       :ordered-map   #(core/ordered-map-with string-cmp pairs)})))

(defn bench-string-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))
        {:keys [sorted-map-by data-avl ordered-map]} (build-string-map-variants pairs cmp string-cmp)
        ^objects look (object-array (repeatedly num-lookups #(nth ks (rand-int n))))]
    (run-cases
      {:sorted-map-by #(dotimes [i num-lookups] (get sorted-map-by (aget look i)))
       :data-avl      #(dotimes [i num-lookups] (get data-avl (aget look i)))
       :ordered-map   #(dotimes [i num-lookups] (ordered-map (aget look i)))})))

(defn bench-string-iteration [n]
  (let [ks    (generate-string-keys n)
        pairs (mapv (fn [k] [k k]) ks)
        cmp   #(compare (str %1) (str %2))
        sm    (into (sorted-map-by cmp) pairs)
        am    (into (avl/sorted-map-by cmp) pairs)
        om    (core/ordered-map-with string-cmp pairs)]
    (run-cases
      {:sorted-map-by #(reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 sm)
       :data-avl      #(reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 am)
       :ordered-map   #(reduce (fn [^long acc [k _]] (+ acc (long (hash k)))) 0 om)})))

(defn- make-string-set-pair
  "Build overlapping string set pairs for set algebra benchmarks.
  Two sets of size n with 50% overlap, matching the standard workload."
  [n]
  (let [ks1 (generate-string-keys n)
        half (quot n 2)
        ks2 (into (subvec ks1 half) (generate-string-keys half))
        cmp #(compare (str %1) (str %2))]
    {:left  {:sorted-set   (into (sorted-set-by cmp) ks1)
             :data-avl     (into (avl/sorted-set-by cmp) ks1)
             :string-ordered (core/string-ordered-set ks1)}
     :right {:sorted-set   (into (sorted-set-by cmp) ks2)
             :data-avl     (into (avl/sorted-set-by cmp) ks2)
             :string-ordered (core/string-ordered-set ks2)}}))

(defn bench-string-set-construction [n]
  (let [ks  (generate-string-keys n)
        cmp #(compare (str %1) (str %2))]
    (run-cases
      {:sorted-set-by   #(into (sorted-set-by cmp) ks)
       :data-avl        #(into (avl/sorted-set-by cmp) ks)
       :string-ordered  #(core/string-ordered-set ks)})))

(defn bench-string-set-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [ks   (generate-string-keys n)
        cmp  #(compare (str %1) (str %2))
        ss   (into (sorted-set-by cmp) ks)
        as   (into (avl/sorted-set-by cmp) ks)
        os   (core/string-ordered-set ks)
        ^objects look (object-array (repeatedly num-lookups #(nth ks (rand-int n))))]
    (run-cases
      {:sorted-set-by   #(dotimes [i num-lookups] (contains? ss (aget look i)))
       :data-avl        #(dotimes [i num-lookups] (contains? as (aget look i)))
       :string-ordered  #(dotimes [i num-lookups] (contains? os (aget look i)))})))

(defn bench-string-rank-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [ks   (generate-string-keys n)
        cmp  #(compare (str %1) (str %2))
        as   (into (avl/sorted-set-by cmp) ks)
        os   (core/string-ordered-set ks)
        ^objects look (object-array (repeatedly num-lookups #(nth ks (rand-int n))))]
    (run-cases
      {:data-avl       #(dotimes [i num-lookups] (avl/rank-of as (aget look i)))
       :string-ordered #(dotimes [i num-lookups] (core/rank os (aget look i)))})))

(defn bench-string-set-union [n]
  (let [{left :left right :right} (make-string-set-pair n)]
    (run-cases
      {:sorted-set-by   #(cset/union (:sorted-set left) (:sorted-set right))
       :data-avl        #(cset/union (:data-avl left) (:data-avl right))
       :string-ordered  #(core/union (:string-ordered left) (:string-ordered right))})))

(defn bench-string-set-intersection [n]
  (let [{left :left right :right} (make-string-set-pair n)]
    (run-cases
      {:sorted-set-by   #(cset/intersection (:sorted-set left) (:sorted-set right))
       :data-avl        #(cset/intersection (:data-avl left) (:data-avl right))
       :string-ordered  #(core/intersection (:string-ordered left) (:string-ordered right))})))

(defn bench-string-set-difference [n]
  (let [{left :left right :right} (make-string-set-pair n)]
    (run-cases
      {:sorted-set-by   #(cset/difference (:sorted-set left) (:sorted-set right))
       :data-avl        #(cset/difference (:data-avl left) (:data-avl right))
       :string-ordered  #(core/difference (:string-ordered left) (:string-ordered right))})))

(defn bench-interval-construction [n]
  (let [intervals (mapv (fn [i] [(* i 2) (inc (* i 2))]) (shuffle (range n)))]
    (run-cases
      {:interval-set #(core/interval-set intervals)})))

(defn bench-interval-map-construction [n]
  (let [intervals (mapv (fn [i] [[(* i 2) (inc (* i 2))] (str "val-" i)])
                        (shuffle (range n)))]
    (run-cases
      {:interval-map #(core/interval-map (into {} intervals))})))

(defn bench-interval-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [intervals (mapv (fn [i] [[(* i 2) (inc (* i 2))] (str "val-" i)])
                        (range n))
        im        (core/interval-map (into {} intervals))
        max-point (* 2 n)
        ^ints points (int-array (repeatedly num-lookups #(rand-int max-point)))]
    (run-cases
      {:interval-map #(dotimes [i num-lookups] (im (aget points i)))})))

(defn bench-interval-fold [n]
  (let [intervals (mapv (fn [i] [(* i 2) (inc (* i 2))]) (range n))
        is        (core/interval-set intervals)
        sum-intervals (fn [^long acc interval] (+ acc (long (first interval))))]
    (run-cases
      {:interval-set-reduce #(reduce sum-intervals 0 is)
       :interval-set-fold   #(r/fold + sum-intervals is)})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Map Benchmarks — vs Guava TreeRangeMap
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- gen-range-entries [n]
  (let [spacing 100]
    (vec (for [i (range n)]
           [(* i spacing) (+ (* i spacing) (quot spacing 2)) (keyword (str "v" i))]))))

(defn- build-oc-range-map [entries]
  (reduce (fn [rm [lo hi v]] (assoc rm [lo hi] v)) (core/range-map) entries))

(defn- ^TreeRangeMap build-guava-range-map [entries]
  (let [m (TreeRangeMap/create)]
    (doseq [[lo hi v] entries]
      (.put m (Range/closedOpen (long lo) (long hi)) v))
    m))

(defn bench-range-map-construction [n]
  (let [entries (gen-range-entries n)]
    (run-cases
      {:guava-range-map #(build-guava-range-map entries)
       :range-map       #(build-oc-range-map entries)})))

(defn bench-range-map-bulk-construction
  "Exercises the single-argument (core/range-map coll) constructor's O(n)
   balanced-build path for pre-sorted disjoint input. Distinct from
   bench-range-map-construction, which measures per-entry assoc."
  [n]
  (let [entries (gen-range-entries n)
        pairs   (mapv (fn [[lo hi v]] [[lo hi] v]) entries)]
    (run-cases
      {:guava-range-map #(build-guava-range-map entries)
       :range-map       #(core/range-map pairs)})))

(defn bench-range-map-lookup [n & {:keys [num-lookups] :or {num-lookups 10000}}]
  (let [entries  (gen-range-entries n)
        rm       (build-oc-range-map entries)
        grm      (build-guava-range-map entries)
        max-p    (long (* n 100))
        ^ints pts (int-array (repeatedly num-lookups #(rand-int max-p)))]
    (run-cases
      {:guava-range-map #(dotimes [i num-lookups] (.get grm (long (aget pts i))))
       :range-map       #(dotimes [i num-lookups] (rm (aget pts i)))})))

(defn bench-range-map-carve-out [n]
  (let [entries (gen-range-entries n)
        rm      (build-oc-range-map entries)
        grm     (build-guava-range-map entries)
        mid-lo  (long (* (quot n 4) 100))
        mid-hi  (long (* (* 3 (quot n 4)) 100))]
    (run-cases
      {:guava-range-map #(let [copy (TreeRangeMap/create)]
                           (.putAll copy grm)
                           (.put copy (Range/closedOpen mid-lo mid-hi) :carved)
                           copy)
       :range-map       #(assoc rm [mid-lo mid-hi] :carved)})))

(defn bench-range-map-iteration [n]
  (let [entries (gen-range-entries n)
        rm      (build-oc-range-map entries)
        grm     (build-guava-range-map entries)]
    (run-cases
      {:guava-range-map #(reduce (fn [^long c _] (unchecked-inc c)) 0
                                 (.asMapOfRanges grm))
       :range-map       #(reduce (fn [^long c _] (unchecked-inc c)) 0
                                 (core/ranges rm))})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Segment Tree Benchmarks — vs sorted-map subseq for range queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-segment-tree-construction [n]
  (let [data (zipmap (shuffle (range n)) (map long (range n)))]
    (run-cases
      {:sorted-map   #(into (sorted-map) data)
       :segment-tree #(core/sum-tree data)})))

(defn bench-segment-tree-query
  [n & {:keys [num-queries window-frac] :or {num-queries 1000 window-frac 10}}]
  (let [data   (zipmap (range n) (map long (range n)))
        st     (core/sum-tree data)
        sm     (into (sorted-map) data)
        window (max 1 (quot n window-frac))
        rng    (java.util.Random. 42)
        ^ints los (int-array (repeatedly num-queries
                                         #(.nextInt rng (max 1 (- n window)))))]
    (run-cases
      ;; sorted-map baseline: walk the subseq and sum values
      {:sorted-map   #(dotimes [i num-queries]
                        (let [lo (aget los i)
                              hi (+ lo window)]
                          (reduce-kv (fn [^long acc _ v] (+ acc (long v)))
                                     0
                                     (into {} (subseq sm >= lo <= hi)))))
       :segment-tree #(dotimes [i num-queries]
                        (let [lo (aget los i)
                              hi (+ lo window)]
                          (core/query st lo hi)))})))

(defn bench-segment-tree-update
  [n & {:keys [num-updates] :or {num-updates 1000}}]
  (let [data   (zipmap (range n) (map long (range n)))
        st     (core/sum-tree data)
        sm     (into (sorted-map) data)
        rng    (java.util.Random. 42)
        ^ints ks (int-array (repeatedly num-updates #(.nextInt rng n)))
        ^longs vs (long-array (repeatedly num-updates #(.nextInt rng 1000)))]
    (run-cases
      {:sorted-map   #(loop [m sm, i 0]
                        (if (< i num-updates)
                          (recur (assoc m (aget ks i) (aget vs i)) (unchecked-inc i))
                          m))
       :segment-tree #(loop [m st, i 0]
                        (if (< i num-updates)
                          (recur (assoc m (aget ks i) (aget vs i)) (unchecked-inc i))
                          m))})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue Benchmarks — vs sorted-set-by [priority seqnum value]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- gen-priority-pairs [n]
  (let [rng (java.util.Random. 42)]
    (vec (for [i (range n)]
           [(.nextInt rng 1000) (keyword (str "task-" i))]))))

(def ^:private pq-tuple-cmp
  (fn [[p1 s1] [p2 s2]]
    (let [c (compare p1 p2)]
      (if (zero? c) (compare s1 s2) c))))

(defn- build-sorted-pq-baseline [pairs]
  (into (sorted-set-by pq-tuple-cmp)
        (map-indexed (fn [i [p v]] [p i v]) pairs)))

(defn bench-priority-queue-construction [n]
  (let [pairs (gen-priority-pairs n)]
    (run-cases
      {:sorted-set-by  #(build-sorted-pq-baseline pairs)
       :priority-queue #(core/priority-queue pairs)})))

(defn bench-priority-queue-push
  [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [pairs      (gen-priority-pairs n)
        base-pq    (core/priority-queue pairs)
        base-ss    (build-sorted-pq-baseline pairs)
        rng        (java.util.Random. 42)
        extra      (vec (for [i (range num-ops)]
                          [(.nextInt rng 1000) (keyword (str "new-" i))]))]
    (run-cases
      {:sorted-set-by  #(loop [s base-ss, i 0]
                          (if (< i num-ops)
                            (let [[p v] (nth extra i)]
                              (recur (conj s [p (+ n i) v]) (unchecked-inc i)))
                            s))
       :priority-queue #(loop [q base-pq, i 0]
                          (if (< i num-ops)
                            (let [[p v] (nth extra i)]
                              (recur (core/push q p v) (unchecked-inc i)))
                            q))})))

(defn bench-priority-queue-pop-min
  [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [num-ops (min num-ops n)
        pairs   (gen-priority-pairs n)
        base-pq (core/priority-queue pairs)
        base-ss (build-sorted-pq-baseline pairs)]
    (run-cases
      {:sorted-set-by  #(loop [s base-ss, i 0]
                          (if (< i num-ops)
                            (recur (disj s (first s)) (unchecked-inc i))
                            s))
       :priority-queue #(loop [q base-pq, i 0]
                          (if (< i num-ops)
                            (recur (core/pop-min q) (unchecked-inc i))
                            q))})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Multiset Benchmarks — vs sorted-map counts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- gen-multiset-elements [n]
  ;; Repeat each element 3x to exercise the multiplicity path
  (vec (mapcat #(repeat 3 %) (range (quot n 3)))))

(defn bench-multiset-construction [n]
  (let [elems (gen-multiset-elements n)]
    (run-cases
      {:sorted-map-counts #(reduce (fn [m x] (update m x (fnil inc 0)))
                                   (sorted-map) elems)
       :ordered-multiset  #(core/ordered-multiset elems)})))

(defn bench-multiset-multiplicity
  [n & {:keys [num-ops] :or {num-ops 10000}}]
  (let [elems   (gen-multiset-elements n)
        mset    (core/ordered-multiset elems)
        counts  (reduce (fn [m x] (update m x (fnil inc 0))) (sorted-map) elems)
        max-key (quot n 3)
        rng     (java.util.Random. 42)
        ^ints ks (int-array (repeatedly num-ops #(.nextInt rng (max 1 max-key))))]
    (run-cases
      {:sorted-map-counts #(dotimes [i num-ops]
                             (get counts (aget ks i) 0))
       :ordered-multiset  #(dotimes [i num-ops]
                             (core/multiplicity mset (aget ks i)))})))

(defn bench-multiset-iteration [n]
  (let [elems  (gen-multiset-elements n)
        mset   (core/ordered-multiset elems)
        counts (reduce (fn [m x] (update m x (fnil inc 0))) (sorted-map) elems)]
    (run-cases
      ;; Baseline: walk the sorted-map, expanding each entry into
      ;; `count` repeated elements — the same observable content.
      {:sorted-map-counts #(reduce (fn [^long acc [k c]]
                                     (+ acc (* (long k) (long c))))
                                   0 counts)
       :ordered-multiset  #(reduce + 0 mset)})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Set / Fuzzy Map Benchmarks — vs sorted-set / sorted-map nearest
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sorted-set-nearest
  "Find the element in sorted-set `s` closest to `query` by absolute
  difference. O(log n) floor + ceiling via `subseq`/`rsubseq`."
  [s query]
  (let [floor   (first (rsubseq s <= query))
        ceiling (first (subseq s >= query))]
    (cond
      (and floor ceiling) (if (<= (Math/abs ^long (- (long query) (long floor)))
                                  (Math/abs ^long (- (long query) (long ceiling))))
                            floor
                            ceiling)
      floor   floor
      ceiling ceiling
      :else   nil)))

(defn- sorted-map-nearest
  [m query]
  (let [floor   (first (rsubseq m <= query))
        ceiling (first (subseq m >= query))]
    (cond
      (and floor ceiling) (if (<= (Math/abs ^long (- (long query) (long (key floor))))
                                  (Math/abs ^long (- (long query) (long (key ceiling)))))
                            floor
                            ceiling)
      floor   floor
      ceiling ceiling
      :else   nil)))

(defn bench-fuzzy-set-construction [n]
  (let [elems (vec (shuffle (range n)))]
    (run-cases
      {:sorted-set #(into (sorted-set) elems)
       :fuzzy-set  #(core/fuzzy-set elems)})))

(defn bench-fuzzy-set-nearest
  [n & {:keys [num-ops] :or {num-ops 10000}}]
  (let [elems  (vec (range n))
        ss     (into (sorted-set) elems)
        fs     (core/fuzzy-set elems)
        max-q  (long (* 2 n))
        rng    (java.util.Random. 42)
        ^ints qs (int-array (repeatedly num-ops #(.nextInt rng (max 1 max-q))))]
    (run-cases
      {:sorted-set #(dotimes [i num-ops]
                      (sorted-set-nearest ss (aget qs i)))
       :fuzzy-set  #(dotimes [i num-ops]
                      (fs (aget qs i)))})))

(defn bench-fuzzy-map-construction [n]
  (let [pairs (map (fn [i] [i (str "v-" i)]) (shuffle (range n)))]
    (run-cases
      {:sorted-map #(into (sorted-map) pairs)
       :fuzzy-map  #(core/fuzzy-map pairs)})))

(defn bench-fuzzy-map-nearest
  [n & {:keys [num-ops] :or {num-ops 10000}}]
  (let [pairs (map (fn [i] [i (str "v-" i)]) (range n))
        sm    (into (sorted-map) pairs)
        fm    (core/fuzzy-map pairs)
        max-q (long (* 2 n))
        rng   (java.util.Random. 42)
        ^ints qs (int-array (repeatedly num-ops #(.nextInt rng (max 1 max-q))))]
    (run-cases
      {:sorted-map #(dotimes [i num-ops]
                      (sorted-map-nearest sm (aget qs i)))
       :fuzzy-map  #(dotimes [i num-ops]
                      (fm (aget qs i)))})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-rope-concat [n]
  (let [piece-size ropetree/+target-chunk-size+
        pieces     (->> (range n)
                     (partition-all piece-size)
                     (mapv vec))
        rope-parts (mapv core/rope pieces)
        vec-parts  pieces]
    (run-cases
      {:rope   #(apply core/rope-concat rope-parts)
       :vector #(reduce into [] vec-parts)})))

(defn bench-rope-splice [n]
  (let [r     (core/rope (range n))
        v     (vec (range n))
        mid   (quot n 2)
        start (max 0 (- mid 16))
        end   (min n (+ mid 16))
        ins   (vec (range 32))]
    (run-cases
      {:rope   #(core/rope-splice r start end ins)
       :vector #(vec (concat (subvec v 0 start) ins (subvec v end)))})))

(defn bench-rope-repeated-edits [n]
  (let [r     (core/rope (range n))
        v     (vec (range n))
        rng   (java.util.Random. 42)
        nops  200
        idxs  (vec (repeatedly nops #(.nextInt rng (max 1 n))))
        ins   (vec (range nops))]
    (run-cases
      {:rope   #(loop [r r, i 0]
                  (if (< i nops)
                    (let [pos (rem (nth idxs i) (count r))]
                      (recur (core/rope-splice r pos (min (+ pos 5) (count r)) [(nth ins i)])
                        (inc i)))
                    r))
       :vector #(loop [v v, i 0]
                  (if (< i nops)
                    (let [pos (rem (nth idxs i) (count v))]
                      (recur (vec (concat (subvec v 0 pos) [(nth ins i)] (subvec v (min (+ pos 5) (count v)))))
                        (inc i)))
                    v))})))

(defn bench-rope-reduce [n]
  (let [r (core/rope (range n))
        v (vec (range n))]
    (run-cases
      {:rope   #(reduce + 0 r)
       :vector #(reduce + 0 v)})))

(defn bench-rope-nth [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [r    (core/rope (range n))
        v    (vec (range n))
        rng  (java.util.Random. 42)
        idxs (int-array (repeatedly num-ops #(.nextInt rng (max 1 n))))]
    (run-cases
      {:rope   #(areduce idxs i acc nil (nth r (aget idxs i)))
       :vector #(areduce idxs i acc nil (nth v (aget idxs i)))})))

(defn bench-rope-chunk-iteration [n]
  (let [r (core/rope (range n))
        v (vec (range n))]
    (run-cases
      {:rope   #(reduce (fn [_ chunk] (reduce (fn [acc x] x) nil chunk))
                         nil (core/rope-chunks r))
       :vector #(reduce (fn [acc x] x) nil v)})))

(defn bench-rope-fold-sum [n]
  (let [r (core/rope (range n))
        v (vec (range n))]
    (run-cases
      {:rope   #(r/fold + r)
       :vector #(r/fold + v)})))

(defn bench-rope-fold-freq [n]
  (let [r         (core/rope (range n))
        v         (vec (range n))
        combinef  (fn ([] {}) ([m1 m2] (merge-with + m1 m2)))
        reducef   (fn [m x] (update m (rem (long x) 100) (fnil inc 0)))]
    (run-cases
      {:rope   #(r/fold combinef reducef r)
       :vector #(r/fold combinef reducef v)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Rope vs String vs StringBuilder Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Three competitors:
;;   :string        — idiomatic Clojure: (str (subs ...) ...) — what most people write
;;   :string-builder — optimal mutable: pre-sized StringBuilder with append(CharSequence,int,int)
;;   :string-rope   — persistent rope: O(log n) structural edits with structural sharing
;;
;; StringRope's architectural advantages over StringBuilder:
;;   • Persistent — old versions survive edits (free undo, no defensive copying)
;;   • Structural sharing — concat/split are O(log n), no bulk copying
;;   • Thread-safe by construction — immutable, no locking needed
;;   • O(log n) splice/insert/remove vs StringBuilder's O(n) arraycopy

(defn- sb-splice
  "Optimal String splice via StringBuilder."
  ^String [^String s ^long start ^long end ^String rep]
  (let [si (int start)
        ei (int end)
        sb (StringBuilder. (+ (.length s) (.length rep) (- si) ei))]
    (.append sb s 0 si)
    (.append sb rep)
    (.append sb s ei (.length s))
    (.toString sb)))

(defn- sb-insert
  "Optimal String insert via StringBuilder."
  ^String [^String s ^long i ^String ins]
  (sb-splice s i i ins))

(defn- sb-remove
  "Optimal String remove via StringBuilder."
  ^String [^String s ^long start ^long end]
  (let [si (int start)
        ei (int end)
        sb (StringBuilder. (- (.length s) (- ei si)))]
    (.append sb s 0 si)
    (.append sb s ei (.length s))
    (.toString sb)))

(defn- random-text
  "Generate a random ASCII text of length n."
  ^String [^long n]
  (let [sb (StringBuilder. n)
        ^String chars "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ\n0123456789"
        nchars (.length chars)]
    (dotimes [_ n]
      (.append sb (.charAt chars (rand-int nchars))))
    (.toString sb)))

(defn bench-string-rope-construction [n]
  (let [^String text (random-text n)]
    (run-cases
      {:string         #(String. text)
       :string-builder #(let [sb (StringBuilder. (count text))]
                          (.append sb text)
                          (.toString sb))
       :string-rope    #(core/string-rope text)})))

(defn bench-string-rope-concat [n]
  (let [^String text (random-text n)
        half   (int (quot n 2))
        ^String s1 (.substring text 0 half)
        ^String s2 (.substring text half)
        sr1    (core/string-rope s1)
        sr2    (core/string-rope s2)]
    (run-cases
      {:string         #(str s1 s2)
       :string-builder #(let [sb (StringBuilder. (.length text))]
                          (.append sb s1)
                          (.append sb s2)
                          (.toString sb))
       :string-rope    #(core/string-rope-concat sr1 sr2)})))

(defn bench-string-rope-split [n]
  (let [^String text (random-text n)
        sr  (core/string-rope text)
        mid (int (quot n 2))]
    (run-cases
      {:string         #(vector (subs text 0 mid) (subs text mid))
       :string-builder #(vector (.substring text 0 mid) (.substring text mid))
       :string-rope    #(core/rope-split sr mid)})))

(defn bench-string-rope-splice [n]
  (let [^String text (random-text n)
        sr  (core/string-rope text)
        mid (quot n 2)
        lo  (max 0 (- mid 5))
        hi  (min n (+ mid 5))
        ^String rep "YYYYYYYYYY"]
    (run-cases
      {:string         #(str (subs text 0 lo) rep (subs text hi))
       :string-builder #(sb-splice text lo hi rep)
       :string-rope    #(core/rope-splice sr lo hi rep)})))

(defn bench-string-rope-insert [n]
  (let [^String text (random-text n)
        sr  (core/string-rope text)
        mid (quot n 2)
        ^String ins "Y"]
    (run-cases
      {:string         #(str (subs text 0 mid) ins (subs text mid))
       :string-builder #(sb-insert text mid ins)
       :string-rope    #(core/rope-insert sr mid ins)})))

(defn bench-string-rope-remove [n]
  (let [^String text (random-text n)
        sr  (core/string-rope text)
        mid (quot n 2)
        lo  (max 0 (- mid 5))
        hi  (min n (+ mid 5))]
    (run-cases
      {:string         #(str (subs text 0 lo) (subs text hi))
       :string-builder #(sb-remove text lo hi)
       :string-rope    #(core/rope-remove sr lo hi)})))

(defn bench-string-rope-nth [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [^String text (random-text n)
        sr   (core/string-rope text)
        rng  (java.util.Random. 42)
        idxs (int-array (repeatedly num-ops #(.nextInt rng (max 1 n))))]
    (run-cases
      {:string         #(areduce idxs i acc (char 0) (.charAt text (aget idxs i)))
       :string-rope    #(areduce idxs i acc nil (nth sr (aget idxs i)))})))

(defn bench-string-rope-reduce [n]
  (let [^String text (random-text n)
        sr  (core/string-rope text)
        f   (fn [^long acc c] (+ acc (long (int (char c)))))]
    (run-cases
      {:string         #(let [len (.length text)]
                          (loop [i (int 0), acc (long 0)]
                            (if (< i len)
                              (recur (unchecked-inc-int i) (+ acc (long (int (.charAt text i)))))
                              acc)))
       :string-rope    #(reduce f 0 sr)})))

(defn bench-string-rope-str [n]
  (let [^String text (random-text n)
        sr  (core/string-rope text)]
    (run-cases
      {:string         #(String. text)
       :string-rope    #(str sr)})))

(defn bench-string-rope-repeated-edits [n]
  (let [^String text (random-text n)
        sr   (core/string-rope text)
        rng  (java.util.Random. 42)
        nops 200
        idxs (vec (repeatedly nops #(.nextInt rng (max 1 n))))
        ^String ins "XXXXX"]
    (run-cases
      {:string         #(loop [^String s text, i 0]
                          (if (< i nops)
                            (let [pos (rem (long (nth idxs i)) (long (.length s)))
                                  end (min (.length s) (+ pos 5))]
                              (recur (str (subs s 0 pos) ins (subs s end)) (unchecked-inc i)))
                            s))
       :string-builder #(loop [^String s text, i 0]
                          (if (< i nops)
                            (let [pos (rem (long (nth idxs i)) (long (.length s)))
                                  end (min (.length s) (+ pos 5))]
                              (recur (sb-splice s pos end ins) (unchecked-inc i)))
                            s))
       :string-rope    #(loop [r sr, i 0]
                          (if (< i nops)
                            (let [pos (rem (long (nth idxs i)) (long (count r)))
                                  end (min (count r) (+ pos 5))]
                              (recur (core/rope-splice r pos end ins) (unchecked-inc i)))
                            r))})))

(defn bench-string-rope-re-find [n]
  (let [^String text (random-text n)
        sr   (core/string-rope text)
        pat  #"[A-Z]{2,}"]
    (run-cases
      {:string      #(re-find pat text)
       :string-rope #(re-find pat sr)})))

(defn bench-string-rope-re-seq [n]
  (let [^String text (random-text n)
        sr   (core/string-rope text)
        pat  #"\w+"]
    (run-cases
      {:string      #(doall (re-seq pat text))
       :string-rope #(doall (re-seq pat sr))})))

(defn bench-string-rope-re-replace [n]
  (let [^String text (random-text n)
        sr   (core/string-rope text)
        pat  #"[aeiou]"]
    (run-cases
      {:string      #(clojure.string/replace text pat "*")
       :string-rope #(clojure.string/replace sr pat "*")})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Byte Rope vs byte[] Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; ByteRope is a persistent, immutable byte sequence backed by a chunked
;; weight-balanced tree. The mutable baseline is the raw byte[] with
;; System.arraycopy — the fastest possible same-operation comparison on JVM.
;; ByteRope's architectural advantages: O(log n) structural edits,
;; persistent snapshots, structural sharing, thread safety by construction.

(defn- ba-splice
  "Optimal byte[] splice via System.arraycopy."
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

(defn- ba-concat
  ^bytes [^bytes a ^bytes b]
  (let [al (alength a)
        bl (alength b)
        result (byte-array (+ al bl))]
    (System/arraycopy a 0 result 0 al)
    (System/arraycopy b 0 result al bl)
    result))

(defn- random-bytes
  "Generate a random byte array of length n."
  ^bytes [^long n]
  (let [rng (java.util.Random. 42)
        b (byte-array n)]
    (.nextBytes rng b)
    b))

(defn bench-byte-rope-construction [n]
  (let [^bytes data (random-bytes n)]
    (run-cases
      {:byte-array #(java.util.Arrays/copyOf data n)
       :byte-rope  #(core/byte-rope data)})))

(defn bench-byte-rope-concat [n]
  (let [^bytes b1 (random-bytes (quot n 4))
        ^bytes b2 (random-bytes (quot n 4))
        ^bytes b3 (random-bytes (quot n 4))
        ^bytes b4 (random-bytes (- n (* 3 (quot n 4))))
        br1 (core/byte-rope b1) br2 (core/byte-rope b2)
        br3 (core/byte-rope b3) br4 (core/byte-rope b4)]
    (run-cases
      {:byte-array #(ba-concat (ba-concat (ba-concat b1 b2) b3) b4)
       :byte-rope  #(core/byte-rope-concat br1 br2 br3 br4)})))

(defn bench-byte-rope-splice [n]
  (let [^bytes data (random-bytes n)
        br  (core/byte-rope data)
        mid (quot n 2)
        lo  (max 0 (- mid 16))
        hi  (min n (+ mid 16))
        ^bytes rep (random-bytes 32)]
    (run-cases
      {:byte-array #(ba-splice data lo hi rep)
       :byte-rope  #(core/rope-splice br lo hi rep)})))

(defn bench-byte-rope-insert [n]
  (let [^bytes data (random-bytes n)
        br  (core/byte-rope data)
        mid (quot n 2)
        ^bytes ins (random-bytes 16)]
    (run-cases
      {:byte-array #(ba-splice data mid mid ins)
       :byte-rope  #(core/rope-insert br mid ins)})))

(defn bench-byte-rope-remove [n]
  (let [^bytes data (random-bytes n)
        br  (core/byte-rope data)
        mid (quot n 2)
        lo  (max 0 (- mid 16))
        hi  (min n (+ mid 16))]
    (run-cases
      {:byte-array #(ba-splice data lo hi nil)
       :byte-rope  #(core/rope-remove br lo hi)})))

(defn bench-byte-rope-split [n]
  (let [^bytes data (random-bytes n)
        br  (core/byte-rope data)
        mid (int (quot n 2))]
    (run-cases
      {:byte-array #(vector (java.util.Arrays/copyOfRange data 0 mid)
                            (java.util.Arrays/copyOfRange data mid (alength data)))
       :byte-rope  #(core/rope-split br mid)})))

(defn bench-byte-rope-repeated-edits [n]
  (let [^bytes data (random-bytes n)
        br   (core/byte-rope data)
        rng  (java.util.Random. 42)
        nops 200
        idxs (vec (repeatedly nops #(.nextInt rng (max 1 n))))
        ^bytes ins (random-bytes 5)]
    (run-cases
      {:byte-array
       #(loop [^bytes s data, i 0]
          (if (< i nops)
            (let [pos (rem (long (nth idxs i)) (long (alength s)))
                  end (min (alength s) (+ pos 5))]
              (recur (ba-splice s pos end ins) (unchecked-inc i)))
            s))
       :byte-rope
       #(loop [r br, i 0]
          (if (< i nops)
            (let [pos (rem (long (nth idxs i)) (long (count r)))
                  end (min (count r) (+ pos 5))]
              (recur (core/rope-splice r pos end ins) (unchecked-inc i)))
            r))})))

(defn bench-byte-rope-nth [n & {:keys [num-ops] :or {num-ops 1000}}]
  (let [^bytes data (random-bytes n)
        br   (core/byte-rope data)
        rng  (java.util.Random. 42)
        idxs (int-array (repeatedly num-ops #(.nextInt rng (max 1 n))))]
    (run-cases
      {:byte-array #(areduce idxs i acc (long 0) (+ acc (long (aget data (aget idxs i)))))
       :byte-rope  #(areduce idxs i acc (long 0) (+ acc (long (nth br (aget idxs i)))))})))

(defn bench-byte-rope-reduce [n]
  (let [^bytes data (random-bytes n)
        br  (core/byte-rope data)]
    (run-cases
      {:byte-array #(let [len (alength data)]
                      (loop [i (int 0), acc (long 0)]
                        (if (< i len)
                          (recur (unchecked-inc-int i)
                                 (+ acc (bit-and (long (aget data i)) 0xff)))
                          acc)))
       :byte-rope  #(reduce + 0 br)})))

(defn bench-byte-rope-fold [n]
  (let [^bytes data (random-bytes n)
        br  (core/byte-rope data)]
    (run-cases
      {:byte-array #(let [len (alength data)]
                      (loop [i (int 0), acc (long 0)]
                        (if (< i len)
                          (recur (unchecked-inc-int i)
                                 (+ acc (bit-and (long (aget data i)) 0xff)))
                          acc)))
       :byte-rope  #(r/fold + br)})))

(defn bench-byte-rope-bytes [n]
  (let [^bytes data (random-bytes n)
        br  (core/byte-rope data)]
    (run-cases
      {:byte-array #(java.util.Arrays/copyOf data n)
       :byte-rope  #(core/byte-rope-bytes br)})))

(defn bench-byte-rope-digest [n]
  (let [^bytes data (random-bytes n)
        br  (core/byte-rope data)]
    (run-cases
      {:byte-array #(let [md (java.security.MessageDigest/getInstance "SHA-256")]
                      (.digest md data))
       :byte-rope  #(core/byte-rope-bytes (core/byte-rope-digest br "SHA-256"))})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Suite Runners
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private wins-benchmark-specs
  [[:set-construction bench-set-construction]
   [:map-construction bench-map-construction]
   [:set-fold bench-set-fold]
   [:set-fold-freq bench-set-fold-freq]
   [:set-reduce-vs-fold-freq bench-set-reduce-vs-fold-freq]
   [:map-fold bench-map-fold]
   [:set-union bench-set-union]
   [:set-intersection bench-set-intersection]
   [:set-difference bench-set-difference]
   [:split bench-split]
   {:key :first-last :fn bench-first-last :when #(<= % 100000)}
   [:rank-access bench-rank-access]
   [:rope-concat bench-rope-concat]
   [:rope-splice bench-rope-splice]
   [:rope-repeated-edits bench-rope-repeated-edits]
   [:rope-reduce bench-rope-reduce]
   {:key :rope-nth :fn bench-rope-nth :when #(<= % 500000)}
   [:rope-chunk-iteration bench-rope-chunk-iteration]
   [:rope-fold-sum bench-rope-fold-sum]
   [:rope-fold-freq bench-rope-fold-freq]
   [:long-construction bench-long-construction]
   [:long-lookup bench-long-lookup]
   [:long-union bench-long-union]
   [:long-intersection bench-long-intersection]
   [:long-difference bench-long-difference]
   [:string-rope-splice bench-string-rope-splice]
   [:string-rope-insert bench-string-rope-insert]
   [:string-rope-remove bench-string-rope-remove]
   [:string-rope-repeated-edits bench-string-rope-repeated-edits]
   [:byte-rope-splice bench-byte-rope-splice]
   [:byte-rope-insert bench-byte-rope-insert]
   [:byte-rope-remove bench-byte-rope-remove]
   [:byte-rope-repeated-edits bench-byte-rope-repeated-edits]])

(def ^:private readme-benchmark-specs
  [[:set-construction bench-set-construction]
   [:set-lookup bench-set-lookup]
   [:set-union bench-set-union]
   [:set-intersection bench-set-intersection]
   [:set-difference bench-set-difference]
   [:set-fold bench-set-fold]
   [:set-fold-freq bench-set-fold-freq]
   [:set-reduce-vs-fold-freq bench-set-reduce-vs-fold-freq]
   [:split bench-split]])

(def ^:private all-benchmark-specs
  [[:map-construction bench-map-construction]
   [:map-insert bench-map-insert]
   [:map-delete bench-map-delete]
   [:map-lookup bench-map-lookup]
   [:map-iteration bench-map-iteration]
   [:map-fold bench-map-fold]
   [:set-construction bench-set-construction]
   [:set-insert bench-set-insert]
   [:set-delete bench-set-delete]
   [:set-lookup bench-set-lookup]
   [:set-iteration bench-set-iteration]
   [:set-iteration-iterator bench-set-iteration-iterator]
   [:set-equality bench-set-equality]
   [:set-fold bench-set-fold]
   [:set-fold-freq bench-set-fold-freq]
   [:set-reduce-vs-fold-freq bench-set-reduce-vs-fold-freq]
   [:set-union bench-set-union]
   [:set-intersection bench-set-intersection]
   [:set-difference bench-set-difference]
   [:rank-access bench-rank-access]
   [:rank-lookup bench-rank-lookup]
   [:split bench-split]
   {:key :first-last :fn bench-first-last :when #(<= % 100000)}
   [:string-construction bench-string-construction]
   [:string-lookup bench-string-lookup]
   [:string-iteration bench-string-iteration]
   [:interval-construction bench-interval-construction]
   [:interval-map-construction bench-interval-map-construction]
   [:interval-lookup bench-interval-lookup]
   [:interval-fold bench-interval-fold]
   [:range-map-construction bench-range-map-construction]
   [:range-map-bulk-construction bench-range-map-bulk-construction]
   [:range-map-lookup bench-range-map-lookup]
   [:range-map-carve-out bench-range-map-carve-out]
   [:range-map-iteration bench-range-map-iteration]
   [:segment-tree-construction bench-segment-tree-construction]
   [:segment-tree-query bench-segment-tree-query]
   [:segment-tree-update bench-segment-tree-update]
   [:priority-queue-construction bench-priority-queue-construction]
   [:priority-queue-push bench-priority-queue-push]
   [:priority-queue-pop-min bench-priority-queue-pop-min]
   [:multiset-construction bench-multiset-construction]
   [:multiset-multiplicity bench-multiset-multiplicity]
   [:multiset-iteration bench-multiset-iteration]
   [:fuzzy-set-construction bench-fuzzy-set-construction]
   [:fuzzy-set-nearest bench-fuzzy-set-nearest]
   [:fuzzy-map-construction bench-fuzzy-map-construction]
   [:fuzzy-map-nearest bench-fuzzy-map-nearest]
   [:rope-concat bench-rope-concat]
   [:rope-splice bench-rope-splice]
   [:rope-repeated-edits bench-rope-repeated-edits]
   [:rope-reduce bench-rope-reduce]
   {:key :rope-nth :fn bench-rope-nth :when #(<= % 500000)}
   [:rope-chunk-iteration bench-rope-chunk-iteration]
   [:rope-fold-sum bench-rope-fold-sum]
   [:rope-fold-freq bench-rope-fold-freq]
   [:long-construction bench-long-construction]
   [:long-lookup bench-long-lookup]
   [:long-insert bench-long-insert]
   [:long-delete bench-long-delete]
   [:long-iteration bench-long-iteration]
   [:long-rank-lookup bench-long-rank-lookup]
   [:long-fold bench-long-fold]
   [:long-union bench-long-union]
   [:long-intersection bench-long-intersection]
   [:long-difference bench-long-difference]
   [:long-split bench-long-split]
   [:string-set-construction bench-string-set-construction]
   [:string-set-lookup bench-string-set-lookup]
   [:string-rank-lookup bench-string-rank-lookup]
   [:string-set-union bench-string-set-union]
   [:string-set-intersection bench-string-set-intersection]
   [:string-set-difference bench-string-set-difference]
   [:string-rope-construction bench-string-rope-construction]
   [:string-rope-concat bench-string-rope-concat]
   [:string-rope-split bench-string-rope-split]
   [:string-rope-splice bench-string-rope-splice]
   [:string-rope-insert bench-string-rope-insert]
   [:string-rope-remove bench-string-rope-remove]
   {:key :string-rope-nth :fn bench-string-rope-nth :when #(<= % 500000)}
   [:string-rope-reduce bench-string-rope-reduce]
   [:string-rope-str bench-string-rope-str]
   [:string-rope-repeated-edits bench-string-rope-repeated-edits]
   [:string-rope-re-find bench-string-rope-re-find]
   [:string-rope-re-seq bench-string-rope-re-seq]
   [:string-rope-re-replace bench-string-rope-re-replace]
   [:byte-rope-construction bench-byte-rope-construction]
   [:byte-rope-concat bench-byte-rope-concat]
   [:byte-rope-split bench-byte-rope-split]
   [:byte-rope-splice bench-byte-rope-splice]
   [:byte-rope-insert bench-byte-rope-insert]
   [:byte-rope-remove bench-byte-rope-remove]
   {:key :byte-rope-nth :fn bench-byte-rope-nth :when #(<= % 500000)}
   [:byte-rope-reduce bench-byte-rope-reduce]
   [:byte-rope-fold bench-byte-rope-fold]
   [:byte-rope-repeated-edits bench-byte-rope-repeated-edits]
   [:byte-rope-bytes bench-byte-rope-bytes]
   [:byte-rope-digest bench-byte-rope-digest]])

(defn run-wins-benchmarks
  "Run benchmarks focused on where ordered-collections wins."
  [sizes]
  (let [results (atom {})]
    (doseq [n sizes]
      (run-benchmarks-for-size! results n wins-benchmark-specs))

    @results))

(defn run-readme-benchmarks
  "Run only the benchmarks used in README tables (~10 min for 5 sizes)."
  [sizes]
  (let [results (atom {})]
    (doseq [n sizes]
      (run-benchmarks-for-size! results n readme-benchmark-specs))
    @results))

(defn run-all-benchmarks
  "Run all benchmarks comprehensively."
  [sizes]
  (let [results (atom {})]
    (doseq [n sizes]
      (run-benchmarks-for-size! results n all-benchmark-specs))

    @results))

(defn- safe-command-output [& args]
  (try
    (let [{:keys [exit out]} (apply sh/sh args)]
      (when (zero? exit)
        (str/trim out)))
    (catch Throwable _
      nil)))

(defn- git-info []
  {:git-rev       (safe-command-output "git" "rev-parse" "HEAD")
   :git-branch    (safe-command-output "git" "rev-parse" "--abbrev-ref" "HEAD")
   :git-dirty?    (boolean (seq (safe-command-output "git" "status" "--porcelain")))
   :git-describe  (safe-command-output "git" "describe" "--always" "--dirty" "--tags")})

(defn- project-version []
  (when-let [[_ version] (re-find #"defproject\s+[^\s]+\s+\"([^\"]+)\"" (slurp "project.clj"))]
    version))

(defn- lein-version []
  (safe-command-output "lein" "version"))

(defn- runtime-info []
  (let [rt          (Runtime/getRuntime)
        rt-mx       (ManagementFactory/getRuntimeMXBean)
        mem-mx      (ManagementFactory/getMemoryMXBean)
        heap-usage  (.getHeapMemoryUsage mem-mx)
        non-heap    (.getNonHeapMemoryUsage mem-mx)]
    {:clojure               (clojure-version)
     :project-version       (project-version)
     :lein-version          (lein-version)
     :hostname              (try (.getHostName (InetAddress/getLocalHost))
                                 (catch Throwable _ nil))
     :user-timezone         (.getID (java.util.TimeZone/getDefault))
     :java-version          (System/getProperty "java.version")
     :java-vendor           (System/getProperty "java.vendor")
     :java-vm               (System/getProperty "java.vm.name")
     :java-vm-vendor        (System/getProperty "java.vm.vendor")
     :os-name               (System/getProperty "os.name")
     :os-version            (System/getProperty "os.version")
     :os-arch               (System/getProperty "os.arch")
     :processors            (.availableProcessors rt)
     :max-memory-mb         (quot (.maxMemory rt) (* 1024 1024))
     :total-memory-mb       (quot (.totalMemory rt) (* 1024 1024))
     :free-memory-mb        (quot (.freeMemory rt) (* 1024 1024))
     :heap-init-mb          (quot (.getInit heap-usage) (* 1024 1024))
     :heap-used-mb          (quot (.getUsed heap-usage) (* 1024 1024))
     :heap-committed-mb     (quot (.getCommitted heap-usage) (* 1024 1024))
     :heap-max-mb           (quot (.getMax heap-usage) (* 1024 1024))
     :non-heap-used-mb      (quot (.getUsed non-heap) (* 1024 1024))
     :jvm-input-args        (vec (.getInputArguments rt-mx))
     :start-time-ms         (.getStartTime rt-mx)
     :uptime-ms             (.getUptime rt-mx)}))

(defn system-info []
  (merge (runtime-info) (git-info)))

(defn write-results [results output-file opts started-at]
  (let [finished-at   (Instant/now)
        full-results  {:artifact-version 3
                       :timestamp        (str finished-at)
                       :started-at       (str started-at)
                       :duration-ms      (.toMillis (Duration/between started-at finished-at))
                       :cwd              (.getAbsolutePath (io/file "."))
                       :system           (system-info)
                       :mode             (cond
                                           (:readme opts) :readme
                                           (:quick opts)  :quick
                                           :else          :full)
                       :opts             (dissoc opts :args)
                       :argv             (:args opts)
                       :sizes            (:sizes opts)
                       :benchmarks       results}]
    (io/make-parents output-file)
    (spit output-file (with-out-str (binding [pp/*print-right-margin* 120]
                                      (pp/pprint full-results))))
    (println)
    (println (str "Results written to: " output-file))))

(defn- leaf-bench-result? [x]
  (and (map? x) (contains? x :mean-ns)))

(defn- print-summary-node [indent m]
  (doseq [[k v] (sort-by key m)]
    (if (leaf-bench-result? v)
      (println (str indent (name k) ": " (format-time (:mean-ns v))))
      (do
        (println (str indent (name k) ":"))
        (print-summary-node (str indent "  ") v)))))

(defn print-summary [results]
  (println)
  (println "===== SUMMARY =====")
  (println)
  (doseq [[n benches] (sort-by key results)]
    (println (str "N = " n))
    (doseq [[bench-name bench-results] (sort-by key benches)]
      (println (str "  " (name bench-name) ":"))
      (print-summary-node "    " bench-results))
    (println)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Auto-Compare Against Prior Run
;;
;; After writing the fresh EDN to disk, look for the most-recent
;; existing result file in bench-results/ that predates this run. If
;; one exists, flat-walk both and diff matching (size, group, variant)
;; tuples, printing a compact section with any notable regressions
;; and improvements. Self-contained: no dependency on the bb report
;; tool — runs from the same JVM process that just finished the bench.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ^:const +regression-threshold+ 1.10)
(def ^:private ^:const +improvement-threshold+ 0.90)
(def ^:private ^:const +compare-top-n+ 15)

(defn- prior-edn-file
  "Return the absolute path of the most-recent `.edn` file in
  bench-results/ whose filename sorts before the given `current` file
  (which must also live in bench-results/), or nil if none exists.
  Filenames are timestamped, so lexical sort == chronological sort."
  [current]
  (let [dir (io/file "bench-results")
        current-name (.getName ^java.io.File (io/file current))]
    (when (.isDirectory dir)
      (let [prior (->> (.listFiles dir)
                       (map (fn [^java.io.File f] (.getName f)))
                       (filter #(.endsWith ^String % ".edn"))
                       (filter #(neg? (compare % current-name)))
                       sort
                       last)]
        (when prior
          (.getAbsolutePath ^java.io.File (io/file dir prior)))))))

(defn- walk-leaves
  "Walk a nested benchmark-result map and yield a flat seq of
  {:size :group :variant :mean-ns} entries, one per leaf measurement."
  [benchmarks]
  (letfn [(walk [size path node]
            (cond
              (and (map? node) (contains? node :mean-ns))
              [{:size size
                :group (first path)
                :variant (last path)
                :mean-ns (double (:mean-ns node))}]

              (map? node)
              (mapcat (fn [[k v]] (walk size (conj path k) v)) node)

              :else nil))]
    (mapcat (fn [[size groups]]
              (mapcat (fn [[group node]] (walk size [group] node)) groups))
            benchmarks)))

(defn- load-bench-edn [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception _ nil)))

(defn- delta-row [{:keys [size group variant mean-ns]} prior-map]
  (when-let [old (get prior-map [size group variant])]
    (let [new-ns (double mean-ns)
          old-ns (double old)
          ratio  (/ new-ns old-ns)]
      {:size size
       :group group
       :variant variant
       :old-ns old-ns
       :new-ns new-ns
       :ratio ratio
       :delta-pct (* 100.0 (dec ratio))})))

(defn- fmt-time [^double ns]
  (cond
    (>= ns 1e9) (format "%.2fs" (/ ns 1e9))
    (>= ns 1e6) (format "%.2fms" (/ ns 1e6))
    (>= ns 1e3) (format "%.2fµs" (/ ns 1e3))
    :else       (format "%.0fns" ns)))

(defn- print-delta-table [title rows]
  (println title)
  (println "  -------------------------------------------------------------------------------------------------")
  (println (format "  %6s  %-32s %-22s %12s %12s %10s"
                   "N" "group" "variant" "old" "new" "delta"))
  (println "  -------------------------------------------------------------------------------------------------")
  (doseq [{:keys [size group variant old-ns new-ns delta-pct]} rows]
    (println (format "  %6d  %-32s %-22s %12s %12s %+9.1f%%"
                     size (name group) (name variant)
                     (fmt-time old-ns) (fmt-time new-ns) delta-pct)))
  (println))

(defn print-comparison-vs-prior
  "If a prior bench-results EDN exists for comparison, walk both files,
  match leaf measurements by (size, group, variant), and print
  regressions (slower ≥10%) and improvements (faster ≥10%) side-by-side."
  [current-file]
  (if-let [prior-file (prior-edn-file current-file)]
    (let [current (load-bench-edn current-file)
          prior   (load-bench-edn prior-file)]
      (if-not (and current prior (:benchmarks current) (:benchmarks prior))
        (do (println)
            (println "(No prior EDN loaded — skipping comparison.)"))
        (let [current-rows (walk-leaves (:benchmarks current))
              prior-rows   (walk-leaves (:benchmarks prior))
              prior-map    (into {} (map (juxt (juxt :size :group :variant) :mean-ns))
                                 prior-rows)
              deltas       (keep #(delta-row % prior-map) current-rows)
              regressions  (->> deltas
                                (filter #(>= (:ratio %) +regression-threshold+))
                                (sort-by :ratio >)
                                (take +compare-top-n+))
              improvements (->> deltas
                                (filter #(<= (:ratio %) +improvement-threshold+))
                                (sort-by :ratio)
                                (take +compare-top-n+))]
          (println)
          (println "========================================================================")
          (println "  Comparison vs Previous Run")
          (println "========================================================================")
          (println)
          (println (str "  Baseline: " prior-file))
          (println (str "  Compared:  " (count deltas) " matching benchmark cells."))
          (println)
          (if (seq regressions)
            (print-delta-table
              (format "  Regressions (≥ %.0f%% slower), top %d by magnitude:"
                      (* 100.0 (dec +regression-threshold+)) +compare-top-n+)
              regressions)
            (println "  No significant regressions (≥10% slower).\n"))
          (if (seq improvements)
            (print-delta-table
              (format "  Improvements (≥ %.0f%% faster), top %d by magnitude:"
                      (* 100.0 (- 1.0 +improvement-threshold+)) +compare-top-n+)
              improvements)
            (println "  No significant improvements (≥10% faster).\n"))
          (println (str "  Use `lein bench-report --file " current-file
                        " --baseline " prior-file "` for the full"))
          (println (str "  comparison (category breakdown, headline tables, etc.)."))
          (println))))
    (do (println)
        (println "(No prior bench-results EDN found — skipping comparison.)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-args [args]
  (let [base (parse-standard-args args sizes-default sizes-default sizes-full)]
    (assoc base :readme (bu/has-flag? args "--readme")
                :args   (vec args))))

(defn print-usage []
  (println "Usage: lein bench [options]")
  (println)
  (println "Options:")
  (println "  --readme           README table benchmarks only (~10 min for --full)")
  (println "  --full             N=1K,5K,10K,100K,500K (~60 min)")
  (println "  --sizes N,N,...    Custom sizes (comma-separated)")
  (println "  --help             Show this help")
  (println)
  (println "Default: N=100K (~5 min)")
  (println "Output is written to bench-results/<timestamp>.edn"))

(defn -main [& args]
  (let [opts (parse-args args)]
    (if (:help opts)
      (print-usage)
      (let [output-dir "bench-results"
            output-file (str output-dir "/" (timestamp) ".edn")
            runner (if (:readme opts) run-readme-benchmarks run-all-benchmarks)
            started-at (Instant/now)]
        (println)
        (println "========================================================================")
        (println "  Ordered Collections Benchmark Suite")
        (println "========================================================================")
        (println)
        (println "System info:")
        (doseq [[k v] (system-info)]
          (println (str "  " (name k) ": " v)))
        (println)
        (when (:readme opts)
          (println "Mode: README tables only"))
        (println (str "Sizes: " (pr-str (:sizes opts))))
        (println (str "Output: " output-file))
        (println)

        (let [results (runner (:sizes opts))]
          (print-summary results)
          (write-results results output-file opts started-at)
          (print-comparison-vs-prior output-file))

        (println)
        (println "Benchmark suite complete.")))
    (shutdown-agents)))
