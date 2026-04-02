(ns com.dean.ordered-collections.test-utils
  "Shared test utilities: generators, constructors, assertion helpers.

   Usage:
     (:require [com.dean.ordered-collections.test-utils :as tu])"
  (:require [clojure.data.avl :as avl]
            [clojure.test :refer [is]]
            [clojure.test.check.generators :as gen]
            [com.dean.ordered-collections.core :as oc]))


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
