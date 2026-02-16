(ns com.dean.ordered-collections.equivalence-test
  "Rigorous equivalence tests comparing ordered-collections to sorted-set,
   sorted-map, and clojure.data.avl across multiple scales.

   Test categories:
   - Equivalence tests: Verify identical behavior with reference implementations
   - Correctness tests: Verify invariants hold at high cardinality (1M+)
   - Property tests: Generative testing via test.check"
  (:require [clojure.data.avl :as avl]
            [clojure.set :as cset]
            [clojure.test :refer [deftest testing is are]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [com.dean.ordered-collections.core :as oc]
            [com.dean.ordered-collections.tree.protocol :as proto]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Scales
;;
;; Equivalence tests (comparison with sorted-set/sorted-map):
;;   small    = 100      (fast, many iterations)
;;   medium   = 1,000    (still fast enough for sorted-set)
;;   large    = 10,000   (sorted-set slows down here)
;;
;; Correctness tests (ordered-set only, invariant verification):
;;   huge     = 100,000  (stress tests)
;;   massive  = 1,000,000 (high cardinality)
;;   extreme  = 5,000,000 (stress ceiling)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const small   100)
(def ^:const medium  1000)
(def ^:const large   10000)
(def ^:const huge    100000)
(def ^:const massive 1000000)
(def ^:const extreme 5000000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rand-longs
  "Generate n unique random longs in [0, max-val)"
  [n max-val]
  (loop [s (transient #{})]
    (if (>= (count s) n)
      (vec (persistent! s))
      (recur (conj! s (long (rand max-val)))))))

(defn rand-ints
  "Generate n unique random integers in [-range/2, range/2)"
  [n range-size]
  (let [half (quot range-size 2)]
    (loop [s (transient #{})]
      (if (>= (count s) n)
        (vec (persistent! s))
        (recur (conj! s (- (rand-int range-size) half)))))))

(defn rand-strings
  "Generate n unique random strings"
  [n]
  (let [chars "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"]
    (loop [s (transient #{})]
      (if (>= (count s) n)
        (vec (persistent! s))
        (recur (conj! s (apply str (repeatedly (+ 5 (rand-int 20)) #(rand-nth chars)))))))))

(defn rand-map-entries
  "Generate n unique [k v] pairs with integer keys and values"
  [n max-key]
  (let [keys (rand-longs n max-key)]
    (mapv #(vector % (rand-int 1000000)) keys)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Abstraction: Equivalence Assertions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn same-seq?
  "True if all collections have identical element sequences"
  [& colls]
  (apply = (map vec colls)))

(defn assert-eq
  "Assert that all results are equal, with descriptive message"
  [msg & vals]
  (is (apply = vals) msg))

(defmacro with-iterations
  "Run body n times"
  [n & body]
  `(dotimes [_# ~n] ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collection Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ->ss [xs] (into (sorted-set) xs))
(defn ->as [xs] (into (avl/sorted-set) xs))
(defn ->os [xs] (oc/ordered-set xs))
(defn ->los [xs] (oc/long-ordered-set xs))

(defn ->sm [xs] (into (sorted-map) xs))
(defn ->am [xs] (into (avl/sorted-map) xs))
(defn ->om [xs] (oc/ordered-map xs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 1: SET EQUIVALENCE TESTS
;;
;; These compare ordered-set behavior to sorted-set and data.avl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest set-construction-equivalence
  (testing "Sets contain same elements in same order"
    (with-iterations 20
      (doseq [[label n] [[:small small] [:medium medium] [:large large]]]
        (let [xs (rand-longs n (* n 10))
              ss (->ss xs), as (->as xs), os (->os xs)]
          (assert-eq (str "construction " label) (vec ss) (vec as) (vec os))
          (assert-eq (str "count " label) (count ss) (count as) (count os)))))))

(deftest set-construction-with-negatives
  (testing "Negative numbers sort correctly"
    (with-iterations 20
      (let [xs (rand-ints medium (* medium 10))
            ss (->ss xs), as (->as xs), os (->os xs)]
        (assert-eq "negative ints" (vec ss) (vec as) (vec os))
        (is (apply < (vec ss)) "ascending order")))))

(deftest set-construction-strings
  (testing "String sets are equivalent"
    (with-iterations 20
      (let [xs (rand-strings medium)
            ss (->ss xs), as (->as xs), os (oc/string-ordered-set xs)]
        (assert-eq "string set" (vec ss) (vec as) (vec os))))))

(deftest set-mutation-conj
  (testing "Incremental conj produces same result"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))]
        (loop [ss (sorted-set), as (avl/sorted-set), os (oc/ordered-set), xs xs]
          (if (empty? xs)
            (assert-eq "incremental conj" (vec ss) (vec as) (vec os))
            (let [x (first xs)]
              (recur (conj ss x) (conj as x) (conj os x) (rest xs)))))))))

(deftest set-mutation-disj
  (testing "Deletion produces same result"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            del (take (quot medium 2) (shuffle xs))
            ss (reduce disj (->ss xs) del)
            as (reduce disj (->as xs) del)
            os (reduce disj (->os xs) del)]
        (assert-eq "deletion" (vec ss) (vec as) (vec os))))))

(deftest set-mutation-interleaved
  (testing "Interleaved insert/delete matches"
    (with-iterations 20
      (let [ops (for [_ (range (* 2 medium))]
                  (if (< (rand) 0.65)
                    [:conj (rand-int (* medium 10))]
                    [:disj (rand-int (* medium 10))]))]
        (loop [ss (sorted-set), as (avl/sorted-set), os (oc/ordered-set), ops ops]
          (if (empty? ops)
            (assert-eq "interleaved ops" (vec ss) (vec as) (vec os))
            (let [[op v] (first ops)]
              (case op
                :conj (recur (conj ss v) (conj as v) (conj os v) (rest ops))
                :disj (recur (disj ss v) (disj as v) (disj os v) (rest ops))))))))))

(deftest set-lookup
  (testing "Lookups produce identical results"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            ss (->ss xs), as (->as xs), os (->os xs)
            ks (concat (take 100 xs) (rand-longs 100 (* medium 10)))]
        (doseq [k ks]
          (assert-eq (str "contains? " k) (contains? ss k) (contains? as k) (contains? os k))
          (assert-eq (str "get " k) (get ss k) (get as k) (get os k)))))))

(deftest set-iteration
  (testing "Iteration order matches"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            ss (->ss xs), as (->as xs), os (->os xs)]
        (assert-eq "forward" (vec ss) (vec as) (vec os))
        (assert-eq "reverse" (vec (rseq ss)) (vec (rseq as)) (vec (rseq os)))))))

(deftest set-reduce
  (testing "Reduce produces identical results"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            ss (->ss xs), as (->as xs), os (->os xs)]
        (assert-eq "sum" (reduce + 0 ss) (reduce + 0 as) (reduce + 0 os))
        (assert-eq "count via reduce"
                   (reduce (fn [n _] (inc n)) 0 ss)
                   (reduce (fn [n _] (inc n)) 0 as)
                   (reduce (fn [n _] (inc n)) 0 os))))))

(deftest set-reduce-no-init
  (testing "Reduce without init matches"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            ss (->ss xs), as (->as xs), os (->os xs)]
        (assert-eq "sum no init" (reduce + ss) (reduce + as) (reduce + os))
        (assert-eq "max no init" (reduce max ss) (reduce max as) (reduce max os))
        (assert-eq "min no init" (reduce min ss) (reduce min as) (reduce min os))))))

(deftest set-algebra-union
  (testing "Union produces identical results"
    (with-iterations 20
      (let [xs1 (rand-longs medium (* medium 10))
            xs2 (rand-longs medium (* medium 10))
            ss-u (cset/union (->ss xs1) (->ss xs2))
            os-u (proto/union (->os xs1) (->os xs2))]
        (assert-eq "union" (vec ss-u) (vec os-u))))))

(deftest set-algebra-intersection
  (testing "Intersection produces identical results"
    (with-iterations 20
      (let [base (rand-longs (quot medium 3) (* medium 5))
            xs1 (concat base (rand-longs (quot medium 3) (* medium 5)))
            xs2 (concat base (rand-longs (quot medium 3) (* medium 5)))
            ss-i (cset/intersection (->ss xs1) (->ss xs2))
            os-i (proto/intersection (->os xs1) (->os xs2))]
        (assert-eq "intersection" (vec ss-i) (vec os-i))
        (is (>= (count os-i) (count (set base))) "intersection contains base")))))

(deftest set-algebra-difference
  (testing "Difference produces identical results"
    (with-iterations 20
      (let [base (rand-longs (quot medium 3) (* medium 5))
            xs1 (concat base (rand-longs (quot medium 3) (* medium 5)))
            xs2 (concat base (rand-longs (quot medium 3) (* medium 5)))
            ss-d (cset/difference (->ss xs1) (->ss xs2))
            os-d (proto/difference (->os xs1) (->os xs2))]
        (assert-eq "difference" (vec ss-d) (vec os-d))))))

(deftest set-algebra-subset
  (testing "subset?/superset? match"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            half (take (quot medium 2) xs)
            ss-full (->ss xs), ss-half (->ss half)
            os-full (->os xs), os-half (->os half)]
        (assert-eq "subset?" (cset/subset? ss-half ss-full) (proto/subset? os-half os-full))
        (assert-eq "superset?" (cset/superset? ss-full ss-half) (proto/superset? os-full os-half))
        (assert-eq "subset? self" (cset/subset? ss-full ss-full) (proto/subset? os-full os-full))))))

(deftest set-sorted-interface
  (testing "subseq/rsubseq match"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            ss (->ss xs), os (->os xs)
            sorted (vec (sort xs))
            lo (nth sorted (quot (count sorted) 4))
            hi (nth sorted (* 3 (quot (count sorted) 4)))]
        (assert-eq "subseq >=" (vec (subseq ss >= lo)) (vec (subseq os >= lo)))
        (assert-eq "subseq >"  (vec (subseq ss > lo))  (vec (subseq os > lo)))
        (assert-eq "subseq <"  (vec (subseq ss < hi))  (vec (subseq os < hi)))
        (assert-eq "subseq <=" (vec (subseq ss <= hi)) (vec (subseq os <= hi)))
        (assert-eq "subseq >= <" (vec (subseq ss >= lo < hi)) (vec (subseq os >= lo < hi)))
        (assert-eq "rsubseq >=" (vec (rsubseq ss >= lo)) (vec (rsubseq os >= lo)))
        (assert-eq "rsubseq <=" (vec (rsubseq ss <= hi)) (vec (rsubseq os <= hi)))))))

(deftest set-first-last
  (testing "first/last match"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            ss (->ss xs), as (->as xs), os (->os xs)]
        (assert-eq "first" (first ss) (first as) (first os))
        (assert-eq "last" (last ss) (last as) (last os))
        ;; Java SortedSet interface
        (let [^java.util.SortedSet jos os]
          (assert-eq ".first" (first ss) (.first jos))
          (assert-eq ".last" (last ss) (.last jos)))))))

(deftest set-java-sorted-interface
  (testing "headSet/tailSet/subSet match"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            ss (->ss xs)
            os ^java.util.SortedSet (->os xs)
            sorted (vec (sort xs))
            lo (nth sorted (quot (count sorted) 4))
            hi (nth sorted (* 3 (quot (count sorted) 4)))]
        (assert-eq ".headSet" (vec (take-while #(< % hi) ss)) (vec (.headSet os hi)))
        (assert-eq ".tailSet" (vec (drop-while #(< % lo) ss)) (vec (.tailSet os lo)))
        (assert-eq ".subSet" (vec (filter #(and (>= % lo) (< % hi)) ss)) (vec (.subSet os lo hi)))))))

(deftest set-nth-equivalence
  (testing "nth matches data.avl"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            as (->as xs), os (->os xs)
            idxs (repeatedly 100 #(rand-int (count xs)))]
        (doseq [i idxs]
          (assert-eq (str "nth " i) (nth as i) (nth os i)))))))

(deftest set-rank-equivalence
  (testing "rank matches data.avl"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            as (->as xs), os (->os xs)]
        (doseq [i (range 0 (count xs) (max 1 (quot (count xs) 50)))]
          (let [k (nth as i)]
            (assert-eq (str "rank " k) (avl/rank-of as k) (.indexOf ^java.util.List os k))))))))

(deftest set-hash-consistency
  (testing "Hash is consistent for equal sets"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            os1 (->os xs)
            os2 (->os (shuffle xs))]
        (assert-eq "hash of same data" (hash os1) (hash os2))
        (assert-eq "hash is stable" (hash os1) (hash os1))))))

(deftest set-equality
  (testing "Equality semantics"
    (with-iterations 20
      (let [xs (rand-longs medium (* medium 10))
            ss (->ss xs), os (->os xs), hs (set xs)]
        (is (= ss os) "sorted-set = ordered-set")
        (is (= os ss) "ordered-set = sorted-set")
        (is (= ss hs) "sorted-set = hash-set")
        (is (= os hs) "ordered-set = hash-set")
        (is (= os (->os (shuffle xs))) "ordered-set = shuffled ordered-set")))))

(deftest set-empty
  (testing "Empty set operations"
    (let [ss (sorted-set), as (avl/sorted-set), os (oc/ordered-set)]
      (assert-eq "count" 0 (count ss) (count as) (count os))
      (assert-eq "seq" nil (seq ss) (seq as) (seq os))
      (assert-eq "first" nil (first ss) (first as) (first os))
      (assert-eq "disj empty" [] (vec (disj ss 1)) (vec (disj as 1)) (vec (disj os 1))))))

(deftest set-single-element
  (testing "Single element operations"
    (let [ss (sorted-set 42), as (avl/sorted-set 42), os (oc/ordered-set [42])]
      (assert-eq "count" 1 (count ss) (count as) (count os))
      (assert-eq "first" 42 (first ss) (first as) (first os))
      (assert-eq "contains?" true (contains? ss 42) (contains? as 42) (contains? os 42))
      (assert-eq "disj" [] (vec (disj ss 42)) (vec (disj as 42)) (vec (disj os 42))))))

(deftest set-duplicates
  (testing "Duplicates are ignored"
    (let [xs (concat (range 100) (range 50) (range 25))
          ss (into (sorted-set) xs)
          as (into (avl/sorted-set) xs)
          os (oc/ordered-set xs)]
      (assert-eq "count" 100 (count ss) (count as) (count os))
      (assert-eq "seq" (vec ss) (vec as) (vec os)))))

(deftest set-boundary-values
  (testing "Long boundary values"
    (let [xs [Long/MIN_VALUE -1 0 1 Long/MAX_VALUE]
          ss (->ss xs), os (->los xs)]
      (assert-eq "boundary values" (vec ss) (vec os))
      (is (= Long/MIN_VALUE (first os)))
      (is (= Long/MAX_VALUE (last os))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 2: MAP EQUIVALENCE TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest map-construction-equivalence
  (testing "Maps contain same entries in same order"
    (with-iterations 20
      (let [xs (rand-map-entries medium (* medium 10))
            sm (->sm xs), am (->am xs), om (->om xs)]
        (assert-eq "construction" (vec sm) (vec am) (vec om))
        (assert-eq "count" (count sm) (count am) (count om))))))

(deftest map-mutation
  (testing "Map mutations match"
    (with-iterations 20
      (let [xs (rand-map-entries medium (* medium 10))
            sm (->sm xs), om (->om xs)
            new-k (+ (* medium 10) (rand-int 1000))
            new-v (rand-int 1000000)]
        ;; assoc
        (assert-eq "assoc" (vec (assoc sm new-k new-v)) (vec (assoc om new-k new-v)))
        ;; dissoc
        (let [k (ffirst sm)]
          (assert-eq "dissoc" (vec (dissoc sm k)) (vec (dissoc om k))))
        ;; update
        (let [k (ffirst sm)]
          (assert-eq "update" (vec (update sm k inc)) (vec (update om k inc))))))))

(deftest map-lookup
  (testing "Map lookups match"
    (with-iterations 20
      (let [xs (rand-map-entries medium (* medium 10))
            sm (->sm xs), om (->om xs)
            ks (concat (take 100 (keys sm)) (rand-longs 100 (* medium 20)))]
        (doseq [k ks]
          (assert-eq (str "get " k) (get sm k) (get om k))
          (assert-eq (str "get default " k) (get sm k ::not-found) (get om k ::not-found))
          (assert-eq (str "contains? " k) (contains? sm k) (contains? om k))
          (assert-eq (str "find " k) (find sm k) (find om k)))))))

(deftest map-iteration
  (testing "Map iteration matches"
    (with-iterations 20
      (let [xs (rand-map-entries medium (* medium 10))
            sm (->sm xs), om (->om xs)]
        (assert-eq "keys" (vec (keys sm)) (vec (keys om)))
        (assert-eq "vals" (vec (vals sm)) (vec (vals om)))
        (assert-eq "seq" (vec sm) (vec om))
        (assert-eq "rseq" (vec (rseq sm)) (vec (rseq om)))))))

(deftest map-reduce
  (testing "Map reduce matches"
    (with-iterations 20
      (let [xs (rand-map-entries medium (* medium 10))
            sm (->sm xs), om (->om xs)]
        (assert-eq "reduce-kv"
                   (reduce-kv (fn [acc k v] (+ acc k v)) 0 sm)
                   (reduce-kv (fn [acc k v] (+ acc k v)) 0 om))))))

(deftest map-hash-consistency
  (testing "Map hash is consistent"
    (with-iterations 20
      (let [xs (rand-map-entries medium (* medium 10))
            om1 (->om xs)
            om2 (->om (shuffle xs))]
        (assert-eq "hash of same data" (hash om1) (hash om2))))))

(deftest map-equality
  (testing "Map equality semantics"
    (with-iterations 20
      (let [xs (rand-map-entries medium (* medium 10))
            sm (->sm xs), om (->om xs), hm (into {} xs)]
        (is (= sm om) "sorted-map = ordered-map")
        (is (= om sm) "ordered-map = sorted-map")
        (is (= sm hm) "sorted-map = hash-map")
        (is (= om hm) "ordered-map = hash-map")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 3: HIGH CARDINALITY CORRECTNESS TESTS
;;
;; These test ordered-set invariants at extreme scales (1M, 5M elements)
;; without comparing to sorted-set (which would be prohibitively slow).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest high-cardinality-construction
  (testing "Construction at 1M elements"
    (let [xs (rand-longs massive (* massive 2))
          os (oc/long-ordered-set xs)]
      (is (= (count (set xs)) (count os)) "count matches unique elements")
      (is (= (first os) (apply min xs)) "first is minimum")
      (is (= (last os) (apply max xs)) "last is maximum"))))

(deftest high-cardinality-construction-5m
  (testing "Construction at 5M elements"
    (let [xs (rand-longs extreme (* extreme 2))
          os (oc/long-ordered-set xs)]
      (is (= (count (set xs)) (count os)) "count matches unique elements")
      (is (= (first os) (apply min xs)) "first is minimum")
      (is (= (last os) (apply max xs)) "last is maximum"))))

(deftest high-cardinality-sorted-invariant
  (testing "Sorted invariant holds at 1M elements"
    (let [xs (rand-longs massive (* massive 2))
          os (oc/long-ordered-set xs)
          sample-indices (repeatedly 1000 #(rand-int (count os)))]
      ;; Sample pairs of adjacent indices to verify order
      (doseq [i sample-indices]
        (when (< (inc i) (count os))
          (is (< (long (nth os i)) (long (nth os (inc i))))
              (str "element at " i " < element at " (inc i))))))))

(deftest high-cardinality-nth
  (testing "nth at 1M elements"
    (let [xs (rand-longs massive (* massive 2))
          os (oc/long-ordered-set xs)
          sorted-vec (vec (sort (distinct xs)))]
      ;; Test specific indices
      (is (= (nth sorted-vec 0) (nth os 0)) "nth 0")
      (is (= (nth sorted-vec (dec (count os))) (nth os (dec (count os)))) "nth last")
      (is (= (nth sorted-vec (quot (count os) 2)) (nth os (quot (count os) 2))) "nth middle")
      ;; Sample random indices
      (doseq [i (repeatedly 100 #(rand-int (count os)))]
        (is (= (nth sorted-vec i) (nth os i)) (str "nth " i))))))

(deftest high-cardinality-reduce
  (testing "Reduce at 1M elements"
    (let [xs (rand-longs massive (* massive 2))
          os (oc/long-ordered-set xs)
          expected-sum (reduce + 0 (distinct xs))]
      (is (= expected-sum (reduce + 0 os)) "reduce sum"))))

(deftest high-cardinality-contains
  (testing "contains? at 1M elements"
    (let [xs (rand-longs massive (* massive 2))
          xs-set (set xs)
          os (oc/long-ordered-set xs)
          ;; Test with known members
          members (take 1000 xs)
          ;; Test with known non-members
          non-members (take 1000 (filter #(not (xs-set %)) (rand-longs 2000 (* massive 3))))]
      (doseq [x members]
        (is (contains? os x) (str "contains member " x)))
      (doseq [x non-members]
        (is (not (contains? os x)) (str "not contains non-member " x))))))

(deftest high-cardinality-subseq
  (testing "subseq at 1M elements"
    (let [xs (rand-longs massive (* massive 2))
          os (oc/long-ordered-set xs)
          sorted-vec (vec (sort (distinct xs)))
          lo (nth sorted-vec (quot (count os) 4))
          hi (nth sorted-vec (* 3 (quot (count os) 4)))]
      ;; Just verify the subseq produces correct bounds
      (let [sub (vec (subseq os >= lo < hi))]
        (is (every? #(and (>= % lo) (< % hi)) sub) "subseq bounds correct")
        (is (> (count sub) 0) "subseq not empty")))))

(deftest high-cardinality-set-algebra
  (testing "Set algebra at 100K elements"
    (let [xs1 (rand-longs huge (* huge 5))
          xs2 (rand-longs huge (* huge 5))
          os1 (oc/long-ordered-set xs1)
          os2 (oc/long-ordered-set xs2)
          ss1 (set xs1)
          ss2 (set xs2)]
      ;; Union
      (let [os-u (proto/union os1 os2)]
        (is (= (count (cset/union ss1 ss2)) (count os-u)) "union count"))
      ;; Intersection
      (let [os-i (proto/intersection os1 os2)]
        (is (= (count (cset/intersection ss1 ss2)) (count os-i)) "intersection count"))
      ;; Difference
      (let [os-d (proto/difference os1 os2)]
        (is (= (count (cset/difference ss1 ss2)) (count os-d)) "difference count")))))

(deftest high-cardinality-map
  (testing "Map at 1M entries"
    (let [xs (rand-map-entries massive (* massive 2))
          om (oc/ordered-map xs)]
      (is (= (count (into {} xs)) (count om)) "count matches")
      ;; Sample lookups
      (doseq [[k v] (take 1000 xs)]
        (is (= v (get om k)) (str "get " k))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 4: PROPERTY-BASED TESTS (test.check)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn distinct-by [f coll]
  (vals (reduce (fn [m x] (assoc m (f x) x)) {} coll)))

(def gen-int-set
  (gen/fmap #(vec (distinct %)) (gen/vector gen/small-integer 0 500)))

(def gen-int-map-entries
  (gen/fmap #(vec (distinct-by first %))
            (gen/vector (gen/tuple gen/small-integer gen/small-integer) 0 500)))

(defspec prop-set-construction 100
  (prop/for-all [xs gen-int-set]
    (= (vec (->ss xs)) (vec (->os xs)))))

(defspec prop-set-conj 100
  (prop/for-all [xs gen-int-set, x gen/small-integer]
    (= (vec (conj (->ss xs) x)) (vec (conj (->os xs) x)))))

(defspec prop-set-disj 100
  (prop/for-all [xs gen-int-set]
    (or (empty? xs)
        (let [x (first xs)]
          (= (vec (disj (->ss xs) x)) (vec (disj (->os xs) x)))))))

(defspec prop-set-contains 100
  (prop/for-all [xs gen-int-set, x gen/small-integer]
    (= (contains? (->ss xs) x) (contains? (->os xs) x))))

(defspec prop-set-count 100
  (prop/for-all [xs gen-int-set]
    (= (count (->ss xs)) (count (->os xs)))))

(defspec prop-set-reduce 100
  (prop/for-all [xs gen-int-set]
    (= (reduce + 0 (->ss xs)) (reduce + 0 (->os xs)))))

(defspec prop-set-hash-consistent 100
  (prop/for-all [xs gen-int-set]
    (= (hash (->os xs)) (hash (->os (shuffle xs))))))

(defspec prop-set-equality 100
  (prop/for-all [xs gen-int-set]
    (= (->ss xs) (->os xs))))

(defspec prop-map-construction 100
  (prop/for-all [xs gen-int-map-entries]
    (= (vec (->sm xs)) (vec (->om xs)))))

(defspec prop-map-assoc 100
  (prop/for-all [xs gen-int-map-entries, k gen/small-integer, v gen/small-integer]
    (= (vec (assoc (->sm xs) k v)) (vec (assoc (->om xs) k v)))))

(defspec prop-map-dissoc 100
  (prop/for-all [xs gen-int-map-entries]
    (or (empty? xs)
        (let [k (ffirst xs)]
          (= (vec (dissoc (->sm xs) k)) (vec (dissoc (->om xs) k)))))))

(defspec prop-map-get 100
  (prop/for-all [xs gen-int-map-entries, k gen/small-integer]
    (= (get (->sm xs) k ::nf) (get (->om xs) k ::nf))))

(defspec prop-map-hash-consistent 100
  (prop/for-all [xs gen-int-map-entries]
    (= (hash (->om xs)) (hash (->om (shuffle xs))))))

(defspec prop-map-equality 100
  (prop/for-all [xs gen-int-map-entries]
    (= (->sm xs) (->om xs))))
