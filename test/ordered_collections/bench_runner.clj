(ns ordered-collections.bench-runner
  "Benchmark runner with EDN output for permanent record keeping.

   Usage:
     lein bench                  # Default: N=100K (~5 min)
     lein bench --full           # N=10K,100K,500K (~20-30 min)
     lein bench --sizes 50000    # Custom sizes

   Output is written to bench-results/<timestamp>.edn"
  (:require [criterium.core :as crit]
            [clojure.core.reducers :as r]
            [clojure.data.avl :as avl]
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
            [ordered-collections.tree.order :as order]
            [ordered-collections.tree.rope :as ropetree])
  (:import [java.lang.management ManagementFactory]
           [java.net InetAddress]
           [java.time Duration Instant LocalDateTime]
           [java.time.format DateTimeFormatter])
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sizes-default [100000])
(def sizes-full    [10000 100000 500000])

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
      {:rope   #(apply core/rope-concat-all rope-parts)
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
   [:rope-repeated-edits bench-rope-repeated-edits]])

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
   [:rope-concat bench-rope-concat]
   [:rope-splice bench-rope-splice]
   [:rope-repeated-edits bench-rope-repeated-edits]])

(defn run-wins-benchmarks
  "Run benchmarks focused on where ordered-collections wins."
  [sizes]
  (let [results (atom {})]
    (doseq [n sizes]
      (run-benchmarks-for-size! results n wins-benchmark-specs))

    @results))

(defn run-readme-benchmarks
  "Run only the benchmarks used in README tables (~5 min for 3 sizes)."
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
  (println "  --readme           README table benchmarks only (~5 min for --full)")
  (println "  --full             N=10K,100K,500K")
  (println "  --sizes N,N,...    Custom sizes (comma-separated)")
  (println "  --help             Show this help")
  (println)
  (println "Default: N=100K (~3 min)")
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
          (write-results results output-file opts started-at))

        (println)
        (println "Benchmark suite complete.")))
    (shutdown-agents)))
