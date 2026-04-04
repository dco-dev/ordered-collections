(ns ordered-collections.interval-tree-test
  "Comprehensive property-based tests for interval-set and interval-map.

   Addresses gaps in interval tree testing:
   1. Property-based tests with test.check
   2. Equivalence against naive reference implementation
   3. Mutation sequence testing (insert, delete, query)
   4. Max-endpoint invariant verification
   5. Edge cases (empty, touching, nested, identical intervals)"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ordered-collections.core :as oc]
            [ordered-collections.tree.node :as node]
            [ordered-collections.tree.interval :as interval])
  (:import [ordered_collections.tree.root INodeCollection]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Naive Reference Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn naive-overlaps?
  "Do intervals [a1,b1] and [a2,b2] overlap? (closed intervals)"
  [[a1 b1] [a2 b2]]
  (and (<= a1 b2) (<= a2 b1)))

(defn naive-point-overlaps?
  "Does interval [a,b] contain point p?"
  [[a b] p]
  (and (<= a p) (<= p b)))

(defn naive-query-set
  "Brute-force O(n) query: find all intervals overlapping query."
  [intervals query]
  (let [pred (if (number? query)
               #(naive-point-overlaps? % query)
               #(naive-overlaps? % query))]
    (set (filter pred intervals))))

(defn naive-query-map
  "Brute-force O(n) query for interval-map: find all values for overlapping intervals."
  [entries query]
  (let [pred (if (number? query)
               #(naive-point-overlaps? (first %) query)
               #(naive-overlaps? (first %) query))]
    (set (map second (filter pred entries)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Max-Endpoint Invariant Verification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn node-max-endpoint
  "Compute what the max-endpoint should be for a node."
  [n]
  (when-not (node/leaf? n)
    (let [interval (node/-k n)
          b        (second interval)
          left-z   (node-max-endpoint (node/-l n))
          right-z  (node-max-endpoint (node/-r n))]
      (cond
        (and left-z right-z) (max b left-z right-z)
        left-z               (max b left-z)
        right-z              (max b right-z)
        :else                b))))

(defn verify-max-endpoint-invariant
  "Verify that every node's z field equals max(b, left-z, right-z)."
  [n]
  (if (node/leaf? n)
    true
    (let [expected (node-max-endpoint n)
          actual   (node/-z n)]
      (and (= expected actual)
           (verify-max-endpoint-invariant (node/-l n))
           (verify-max-endpoint-invariant (node/-r n))))))

(defn get-root [coll]
  (.getRoot ^INodeCollection coll))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def gen-point
  "Generate a point (integer for simplicity)."
  (gen/choose -1000 1000))

(def gen-interval
  "Generate a valid interval [a, b] where a <= b."
  (gen/fmap (fn [[a b]] [(min a b) (max a b)])
            (gen/tuple gen-point gen-point)))

(def gen-interval-list
  "Generate a list of intervals."
  (gen/vector gen-interval 0 100))

(def gen-non-empty-interval-list
  "Generate a non-empty list of intervals."
  (gen/vector gen-interval 1 100))

(def gen-interval-map-entries
  "Generate interval-map entries [[interval value] ...]."
  (gen/fmap (fn [intervals]
              (map-indexed (fn [i iv] [iv (keyword (str "v" i))]) intervals))
            gen-interval-list))

(def gen-query
  "Generate either a point or interval query."
  (gen/one-of [gen-point gen-interval]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property: Query Equivalence
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-interval-set-query-equivalence 100
  (prop/for-all [intervals gen-interval-list
                 query     gen-query]
    (let [iset   (oc/interval-set intervals)
          result (set (iset query))
          expected (naive-query-set intervals query)]
      (= result expected))))

(defspec prop-interval-map-query-equivalence 100
  (prop/for-all [intervals gen-interval-list
                 query     gen-query]
    (let [entries  (map-indexed (fn [i iv] [iv (keyword (str "v" i))]) intervals)
          imap     (oc/interval-map entries)
          result   (set (imap query))
          expected (naive-query-map entries query)]
      (= result expected))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property: Max-Endpoint Invariant
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-interval-set-max-endpoint-invariant 100
  (prop/for-all [intervals gen-non-empty-interval-list]
    (let [iset (oc/interval-set intervals)
          root (get-root iset)]
      (verify-max-endpoint-invariant root))))

(defspec prop-interval-map-max-endpoint-invariant 100
  (prop/for-all [intervals gen-non-empty-interval-list]
    (let [entries (map-indexed (fn [i iv] [iv (keyword (str "v" i))]) intervals)
          imap    (oc/interval-map entries)
          root    (get-root imap)]
      (verify-max-endpoint-invariant root))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property: Mutation Sequences
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-interval-set-mutation-invariant 100
  (prop/for-all [initial-intervals gen-interval-list
                 add-intervals     gen-interval-list
                 remove-intervals  gen-interval-list
                 query             gen-query]
    (let [;; Build set with initial intervals
          iset0 (oc/interval-set initial-intervals)
          ;; Add more intervals
          iset1 (reduce conj iset0 add-intervals)
          ;; Remove some intervals (only those that exist)
          to-remove (filter #(contains? iset1 %) remove-intervals)
          iset2 (reduce disj iset1 to-remove)
          ;; Compute expected state
          expected-intervals (-> (set initial-intervals)
                                 (into add-intervals)
                                 (clojure.set/difference (set to-remove)))
          ;; Verify
          result   (set (iset2 query))
          expected (naive-query-set expected-intervals query)]
      (and (= result expected)
           (or (empty? iset2)
               (verify-max-endpoint-invariant (get-root iset2)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property: Count and Membership
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-interval-set-count 100
  (prop/for-all [intervals gen-interval-list]
    (let [unique-intervals (set intervals)
          iset (oc/interval-set intervals)]
      (= (count iset) (count unique-intervals)))))

(defspec prop-interval-set-membership 100
  (prop/for-all [intervals gen-interval-list]
    (let [iset (oc/interval-set intervals)]
      (every? #(contains? iset %) intervals))))

(defspec prop-interval-map-count 100
  (prop/for-all [intervals gen-interval-list]
    (let [entries (map-indexed (fn [i iv] [iv (keyword (str "v" i))]) intervals)
          ;; For maps, later entries overwrite earlier ones with same key
          unique-keys (set intervals)
          imap (oc/interval-map entries)]
      (= (count imap) (count unique-keys)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property: Iteration Order
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-interval-set-sorted-order 100
  (prop/for-all [intervals gen-interval-list]
    (let [iset (oc/interval-set intervals)
          result (vec iset)]
      ;; Should be sorted by interval start, then by interval end
      (= result (sort-by (fn [[a b]] [a b]) (set intervals))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge Case Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest edge-case-empty-interval-set
  (let [iset (oc/interval-set [])]
    (is (= 0 (count iset)))
    (is (empty? (seq iset)))
    (is (nil? (iset 5)))
    (is (nil? (iset [0 10])))))

(deftest edge-case-single-interval
  (let [iset (oc/interval-set [[5 10]])]
    (is (= 1 (count iset)))
    (is (= [[5 10]] (vec iset)))
    (is (nil? (iset 4)))
    (is (= [[5 10]] (iset 5)))
    (is (= [[5 10]] (iset 7)))
    (is (= [[5 10]] (iset 10)))
    (is (nil? (iset 11)))))

(deftest edge-case-point-intervals
  (testing "Degenerate intervals [x, x]"
    (let [iset (oc/interval-set [[5 5] [10 10] [15 15]])]
      (is (= 3 (count iset)))
      (is (nil? (iset 4)))
      (is (= [[5 5]] (iset 5)))
      (is (nil? (iset 6)))
      (is (= [[10 10]] (iset 10)))
      (is (= [[5 5] [10 10]] (iset [5 10]))))))

(deftest edge-case-touching-intervals
  (testing "Intervals that touch but don't overlap: [0,5] and [5,10]"
    (let [iset (oc/interval-set [[0 5] [5 10]])]
      (is (= 2 (count iset)))
      ;; Point 5 is in both intervals (closed intervals)
      (is (= [[0 5] [5 10]] (sort (iset 5))))
      ;; Query [0,5] overlaps both (they touch at 5)
      (is (= [[0 5] [5 10]] (sort (iset [0 5])))))))

(deftest edge-case-nested-intervals
  (testing "Nested intervals: [0,10] contains [3,7] contains [4,6]"
    (let [iset (oc/interval-set [[0 10] [3 7] [4 6]])]
      (is (= 3 (count iset)))
      (is (= [[0 10] [3 7] [4 6]] (sort (iset 5))))
      (is (= [[0 10]] (iset 1)))
      (is (= [[0 10] [3 7]] (sort (iset 3)))))))

(deftest edge-case-identical-intervals
  (testing "Duplicate intervals are deduplicated"
    (let [iset (oc/interval-set [[1 5] [1 5] [1 5]])]
      (is (= 1 (count iset)))
      (is (= [[1 5]] (vec iset))))))

(deftest edge-case-large-intervals
  (testing "Very large intervals"
    (let [iset (oc/interval-set [[Long/MIN_VALUE Long/MAX_VALUE]])]
      (is (= 1 (count iset)))
      (is (= [[Long/MIN_VALUE Long/MAX_VALUE]] (iset 0)))
      (is (= [[Long/MIN_VALUE Long/MAX_VALUE]] (iset Long/MIN_VALUE)))
      (is (= [[Long/MIN_VALUE Long/MAX_VALUE]] (iset Long/MAX_VALUE))))))

(deftest edge-case-negative-intervals
  (testing "Negative coordinate intervals"
    (let [iset (oc/interval-set [[-10 -5] [-3 3] [5 10]])]
      (is (= 3 (count iset)))
      (is (= [[-10 -5]] (iset -7)))
      (is (= [[-3 3]] (iset 0)))
      (is (= [[5 10]] (iset 7)))
      (is (= [[-10 -5] [-3 3]] (sort (iset [-10 0])))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutation-conj-maintains-invariant
  (testing "conj maintains max-endpoint invariant"
    (let [iset0 (oc/interval-set [[0 10] [20 30]])
          iset1 (conj iset0 [5 50])  ; extends max-endpoint significantly
          iset2 (conj iset1 [100 200])]
      (is (verify-max-endpoint-invariant (get-root iset1)))
      (is (verify-max-endpoint-invariant (get-root iset2)))
      ;; Query should find the new interval
      (is (= [[5 50]] (iset1 40)))
      (is (= [[100 200]] (iset2 150))))))

(deftest mutation-disj-maintains-invariant
  (testing "disj maintains max-endpoint invariant"
    (let [iset0 (oc/interval-set [[0 10] [20 100] [30 40]])
          ;; Remove the interval with max endpoint
          iset1 (disj iset0 [20 100])]
      (is (verify-max-endpoint-invariant (get-root iset1)))
      ;; The max endpoint should now be 40
      (is (nil? (iset1 50))))))

(deftest mutation-sequence-stress
  (testing "Many mutations maintain invariant"
    (let [initial (mapv (fn [i] [(* i 10) (+ (* i 10) 5)]) (range 100))
          iset0   (oc/interval-set initial)
          ;; Add 50 more
          adds    (mapv (fn [i] [(+ 1000 (* i 10)) (+ 1005 (* i 10))]) (range 50))
          iset1   (reduce conj iset0 adds)
          ;; Remove first 30
          removes (take 30 initial)
          iset2   (reduce disj iset1 removes)]
      (is (= (+ 100 50 (- 30)) (count iset2)))
      (is (verify-max-endpoint-invariant (get-root iset2))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scale Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest scale-test-large-interval-set
  (testing "Large interval set maintains correctness"
    (let [n         10000
          intervals (mapv (fn [i] [i (+ i (rand-int 100))]) (range n))
          iset      (oc/interval-set intervals)]
      (is (= n (count iset)))
      (is (verify-max-endpoint-invariant (get-root iset)))
      ;; Sample queries
      (dotimes [_ 100]
        (let [q (rand-int n)
              result (set (iset q))
              expected (naive-query-set intervals q)]
          (is (= result expected)))))))
