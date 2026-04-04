(ns ordered-collections.test-utils
  "Shared test utilities: generators, constructors, assertion helpers.

   Usage:
     (:require [ordered-collections.test-utils :as tu])"
  (:require [clojure.data.avl :as avl]
            [clojure.test :refer [is]]
            [clojure.test.check.generators :as gen]
            [ordered-collections.core :as oc]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Scales
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const small   100)
(def ^:const medium  1000)
(def ^:const large   10000)
(def ^:const huge    100000)
(def ^:const massive 1000000)
(def ^:const extreme 5000000)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Random Data Generators (imperative)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rand-longs
  "Generate n unique random longs in [0, max-val)."
  [n max-val]
  (loop [s (transient #{})]
    (if (>= (count s) n)
      (vec (persistent! s))
      (recur (conj! s (long (rand max-val)))))))

(defn rand-ints
  "Generate n unique random integers in [-range/2, range/2)."
  [n range-size]
  (let [half (quot range-size 2)]
    (loop [s (transient #{})]
      (if (>= (count s) n)
        (vec (persistent! s))
        (recur (conj! s (- (rand-int range-size) half)))))))

(defn rand-strings
  "Generate n unique random strings."
  [n]
  (let [chars "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"]
    (loop [s (transient #{})]
      (if (>= (count s) n)
        (vec (persistent! s))
        (recur (conj! s (apply str (repeatedly (+ 5 (rand-int 20)) #(rand-nth chars)))))))))

(defn rand-map-entries
  "Generate n unique [k v] pairs with integer keys and values."
  [n max-key]
  (let [keys (rand-longs n max-key)]
    (mapv #(vector % (rand-int 1000000)) keys)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; test.check Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn distinct-by
  "Return values from coll, deduplicated by (f x)."
  [f coll]
  (vals (reduce (fn [m x] (assoc m (f x) x)) {} coll)))

(def gen-int-set
  "Generator for vectors of distinct integers."
  (gen/fmap #(vec (distinct %)) (gen/vector gen/small-integer 0 500)))

(def gen-non-empty-int-set
  "Generator for non-empty vectors of distinct integers."
  (gen/fmap #(vec (distinct %))
            (gen/such-that not-empty (gen/vector gen/small-integer 1 200))))

(def gen-int-map-entries
  "Generator for vectors of distinct [k v] pairs."
  (gen/fmap #(vec (distinct-by first %))
            (gen/vector (gen/tuple gen/small-integer gen/small-integer) 0 500)))

(def gen-non-empty-int-map-entries
  "Generator for non-empty vectors of distinct [k v] pairs."
  (gen/fmap #(vec (distinct-by first %))
            (gen/such-that not-empty
                           (gen/vector (gen/tuple gen/small-integer gen/small-integer) 1 200))))

(def gen-test-symbol
  "Generator for nearest/subrange test symbols."
  (gen/elements [:< :<= :> :>=]))

(def gen-range
  "Generate a valid range [lo hi) where lo < hi."
  (gen/bind (gen/tuple gen/small-integer gen/small-integer)
            (fn [[a b]]
              (let [lo (min a b)
                    hi (max a b)]
                (if (= lo hi)
                  (gen/return [lo (inc hi)])
                  (gen/return [lo hi]))))))

(def gen-query-range
  "Generator for inclusive query ranges [lo hi] with lo <= hi."
  (gen/let [a gen/small-integer
            b gen/small-integer]
    [(min a b) (max a b)]))

(def gen-priority-pairs
  "Generator for [priority value] pairs, allowing duplicate priorities."
  (gen/vector (gen/tuple gen/small-integer gen/small-integer) 0 200))

(def gen-multiset-elems
  "Generator for duplicate-heavy multiset inputs."
  (gen/vector gen/small-integer 0 300))

(def gen-segment-updates
  "Generator for segment-tree [k v] updates, allowing repeated keys."
  (gen/vector (gen/tuple gen/small-integer gen/small-integer) 0 50))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reference Models for Property Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn priority-queue-order
  "Reference queue order for [priority value] pairs.

   Equal priorities are FIFO in forward queue order."
  ([pairs]
   (priority-queue-order pairs false))
  ([pairs descending?]
   (->> pairs
        (map-indexed (fn [idx [p v]] [p idx v]))
        (sort-by (juxt (if descending? (comp - first) first) second))
        (mapv (fn [[p _ v]] [p v])))))

(defn multiset-disj-one-frequencies
  "Reference frequencies after removing one occurrence of x."
  [xs x]
  (let [freqs (frequencies xs)]
    (if (pos? (get freqs x 0))
      (let [next-n (dec (get freqs x))]
        (if (zero? next-n)
          (dissoc freqs x)
          (assoc freqs x next-n)))
      freqs)))

(defn fuzzy-nearest-ref
  "Reference nearest value for integer fuzzy structures."
  [xs q tiebreak]
  (when (seq xs)
    (let [dist (fn [x] (Math/abs ^long (- (long q) (long x))))]
      (first
        (sort-by (fn [x]
                   [(dist x)
                    (case tiebreak
                      :< (long x)
                      :> (- (long x)))])
                 xs)))))

(defn fuzzy-nearest-map-ref
  "Reference nearest [key value] pair for integer fuzzy maps."
  [m q tiebreak]
  (when (seq m)
    (let [k (fuzzy-nearest-ref (keys m) q tiebreak)]
      [k (get m k)])))

(defn ref-range-sum
  "Reference: sum values for keys in [lo, hi]."
  [m lo hi]
  (reduce + 0 (for [[k v] m :when (<= lo k hi)] v)))

(defn absent-small-int
  "Return a small integer not present in coll, or nil if none is found."
  [coll]
  (first (filter #(not (contains? coll %)) (range -1000 1000))))

(defn nearest-ref
  "Reference nearest result for ordered collections."
  [coll test k]
  (case test
    :<  (first (rsubseq coll < k))
    :<= (first (rsubseq coll <= k))
    :>  (first (subseq coll > k))
    :>= (first (subseq coll >= k))))

(defn subrange-ref
  "Reference subrange result for ordered collections."
  [coll test k]
  (case test
    :<  (subseq coll < k)
    :<= (subseq coll <= k)
    :>  (subseq coll > k)
    :>= (subseq coll >= k)))

(defn slice-ref
  "Reference slice by index over an ordered collection."
  [coll start end]
  (->> coll seq (drop start) (take (- end start))))

(defn split-key-ref
  "Reference split-key result over an ordered collection."
  [coll k]
  [(into (empty coll) (subrange-ref coll :< k))
   (when-let [v (get coll k)]
     (if (map? coll) [k v] v))
   (into (empty coll) (subrange-ref coll :> k))])

(defn split-at-ref
  "Reference split-at result over an ordered collection."
  [coll i]
  [(into (empty coll) (slice-ref coll 0 i))
   (into (empty coll) (slice-ref coll i (count coll)))])

(defn percentile-index
  "Reference percentile index used by ranked collection tests."
  [n pct]
  (min (dec n) (long (* (/ (double pct) 100.0) n))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collection Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Sets
(defn ->ss  [xs] (into (sorted-set) xs))
(defn ->hs  [xs] (into #{} xs))
(defn ->as  [xs] (into (avl/sorted-set) xs))
(defn ->os  [xs] (oc/ordered-set xs))
(defn ->los [xs] (oc/long-ordered-set xs))

;; Maps
(defn ->sm [xs] (into (sorted-map) xs))
(defn ->am [xs] (into (avl/sorted-map) xs))
(defn ->om [xs] (oc/ordered-map xs))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Assertion Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn same-seq?
  "True if all collections have identical element sequences."
  [& colls]
  (apply = (map vec colls)))

(defn assert-eq
  "Assert that all results are equal, with descriptive message."
  [msg & vals]
  (is (apply = vals) msg))

(defmacro with-iterations
  "Run body n times."
  [n & body]
  `(dotimes [_# ~n] ~@body))
