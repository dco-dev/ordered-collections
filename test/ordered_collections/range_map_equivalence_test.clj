(ns ordered-collections.range-map-equivalence-test
  "Randomized equivalence tests comparing our range-map implementation
   against Google Guava's TreeRangeMap.

   These tests verify that our range-map has identical semantics to Guava's
   TreeRangeMap for all operations:
   - assoc (put): insert range, carving out overlaps
   - assoc-coalescing (putCoalescing): insert and merge adjacent same-value ranges
   - get: point lookup
   - get-entry (getEntry): point lookup returning [range value]
   - range-remove (remove): remove all mappings in a range

   Reference: https://guava.dev/releases/33.0.0-jre/api/docs/com/google/common/collect/TreeRangeMap.html"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ordered-collections.core :as oc]
            [ordered-collections.test-utils :as tu])
  (:import [com.google.common.collect TreeRangeMap Range]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Guava Interop Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn guava-range-map
  "Create a Guava TreeRangeMap."
  ^TreeRangeMap []
  (TreeRangeMap/create))

(defn guava-put!
  "Put a range into a Guava TreeRangeMap (mutates in place).
   Range is half-open [lo, hi)."
  [^TreeRangeMap grm lo hi v]
  (.put grm (Range/closedOpen (long lo) (long hi)) v)
  grm)

(defn guava-put-coalescing!
  "Put a range with coalescing into a Guava TreeRangeMap (mutates in place)."
  [^TreeRangeMap grm lo hi v]
  (.putCoalescing grm (Range/closedOpen (long lo) (long hi)) v)
  grm)

(defn guava-remove!
  "Remove a range from a Guava TreeRangeMap (mutates in place)."
  [^TreeRangeMap grm lo hi]
  (.remove grm (Range/closedOpen (long lo) (long hi)))
  grm)

(defn guava-get
  "Get the value for a point in a Guava TreeRangeMap."
  [^TreeRangeMap grm x]
  (.get grm (long x)))

(defn guava-get-entry
  "Get [range value] for a point in a Guava TreeRangeMap.
   Returns nil if no mapping exists."
  [^TreeRangeMap grm x]
  (when-let [entry (.getEntry grm (long x))]
    (let [^Range range (.getKey entry)
          value (.getValue entry)]
      ;; Convert Guava Range to our [lo hi] format
      [[(.. range lowerEndpoint) (.. range upperEndpoint)] value])))

(defn guava->seq
  "Convert Guava TreeRangeMap to seq of [[lo hi] value] pairs."
  [^TreeRangeMap grm]
  (for [entry (.asMapOfRanges grm)]
    (let [^Range range (key entry)
          value (val entry)]
      [[(.. range lowerEndpoint) (.. range upperEndpoint)] value])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def gen-range
  "Generate a valid range [lo hi) where lo < hi."
  tu/gen-range)

(def gen-range-value-pair
  "Generate a [[lo hi] value] pair."
  (gen/tuple gen-range gen/small-integer))

(def gen-range-value-pairs
  "Generate a vector of [[lo hi] value] pairs."
  (gen/vector gen-range-value-pair 0 20))

(def gen-point
  "Generate a point for lookup."
  gen/small-integer)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparison Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn apply-ops-to-guava
  "Apply a sequence of range-value pairs to a Guava TreeRangeMap."
  [pairs]
  (reduce (fn [grm [[lo hi] v]]
            (guava-put! grm lo hi v))
          (guava-range-map)
          pairs))

(defn apply-ops-to-ours
  "Apply a sequence of range-value pairs to our range-map."
  [pairs]
  (reduce (fn [rm [[lo hi] v]]
            (assoc rm [lo hi] v))
          (oc/range-map)
          pairs))

(defn apply-coalescing-ops-to-guava
  "Apply a sequence of range-value pairs to Guava with coalescing."
  [pairs]
  (reduce (fn [grm [[lo hi] v]]
            (guava-put-coalescing! grm lo hi v))
          (guava-range-map)
          pairs))

(defn apply-coalescing-ops-to-ours
  "Apply a sequence of range-value pairs to our range-map with coalescing."
  [pairs]
  (reduce (fn [rm [[lo hi] v]]
            (oc/assoc-coalescing rm [lo hi] v))
          (oc/range-map)
          pairs))

(defn maps-equivalent?
  "Check if our range-map and Guava's TreeRangeMap have identical contents."
  [rm ^TreeRangeMap grm]
  (= (vec (seq rm)) (vec (guava->seq grm))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deterministic Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest basic-put-equivalence
  (testing "Single range insertion"
    (let [grm (-> (guava-range-map) (guava-put! 0 10 :a))
          rm  (assoc (oc/range-map) [0 10] :a)]
      (is (maps-equivalent? rm grm) "Single range")
      (is (= :a (guava-get grm 5) (rm 5)) "Point lookup")
      (is (= nil (guava-get grm 10) (rm 10)) "Point at upper bound (exclusive)")))

  (testing "Non-overlapping ranges"
    (let [grm (-> (guava-range-map)
                  (guava-put! 0 10 :a)
                  (guava-put! 20 30 :b))
          rm  (-> (oc/range-map)
                  (assoc [0 10] :a)
                  (assoc [20 30] :b))]
      (is (maps-equivalent? rm grm) "Non-overlapping")
      (is (= :a (guava-get grm 5) (rm 5)))
      (is (= nil (guava-get grm 15) (rm 15)) "Gap")
      (is (= :b (guava-get grm 25) (rm 25)))))

  (testing "Overlapping ranges - full containment"
    (let [grm (-> (guava-range-map)
                  (guava-put! 0 100 :a)
                  (guava-put! 25 75 :b))
          rm  (-> (oc/range-map)
                  (assoc [0 100] :a)
                  (assoc [25 75] :b))]
      (is (maps-equivalent? rm grm) "Full containment")
      (is (= :a (guava-get grm 10) (rm 10)) "Left portion")
      (is (= :b (guava-get grm 50) (rm 50)) "Middle")
      (is (= :a (guava-get grm 80) (rm 80)) "Right portion")))

  (testing "Overlapping ranges - partial overlap"
    (let [grm (-> (guava-range-map)
                  (guava-put! 0 50 :a)
                  (guava-put! 25 75 :b))
          rm  (-> (oc/range-map)
                  (assoc [0 50] :a)
                  (assoc [25 75] :b))]
      (is (maps-equivalent? rm grm) "Partial overlap")
      (is (= :a (guava-get grm 10) (rm 10)))
      (is (= :b (guava-get grm 30) (rm 30)))
      (is (= :b (guava-get grm 60) (rm 60))))))

(deftest get-entry-equivalence
  (testing "get-entry returns correct range and value"
    (let [grm (-> (guava-range-map)
                  (guava-put! 0 10 :a)
                  (guava-put! 20 30 :b))
          rm  (-> (oc/range-map)
                  (assoc [0 10] :a)
                  (assoc [20 30] :b))]
      (is (= [[0 10] :a] (guava-get-entry grm 5) (oc/get-entry rm 5)))
      (is (= [[20 30] :b] (guava-get-entry grm 25) (oc/get-entry rm 25)))
      (is (= nil (guava-get-entry grm 15) (oc/get-entry rm 15)) "Gap"))))

(deftest range-remove-equivalence
  (testing "Remove middle portion of range"
    (let [grm (-> (guava-range-map)
                  (guava-put! 0 100 :a)
                  (guava-remove! 25 75))
          rm  (-> (oc/range-map)
                  (assoc [0 100] :a)
                  (oc/range-remove [25 75]))]
      (is (maps-equivalent? rm grm) "Remove middle")
      (is (= :a (guava-get grm 10) (rm 10)) "Left intact")
      (is (= nil (guava-get grm 50) (rm 50)) "Removed")
      (is (= :a (guava-get grm 80) (rm 80)) "Right intact")))

  (testing "Remove spanning multiple ranges"
    (let [grm (-> (guava-range-map)
                  (guava-put! 0 20 :a)
                  (guava-put! 30 50 :b)
                  (guava-put! 60 80 :c)
                  (guava-remove! 10 70))
          rm  (-> (oc/range-map)
                  (assoc [0 20] :a)
                  (assoc [30 50] :b)
                  (assoc [60 80] :c)
                  (oc/range-remove [10 70]))]
      (is (maps-equivalent? rm grm) "Remove spanning")
      (is (= :a (guava-get grm 5) (rm 5)) "[0,10) remains")
      (is (= nil (guava-get grm 15) (rm 15)))
      (is (= nil (guava-get grm 40) (rm 40)))
      (is (= nil (guava-get grm 65) (rm 65)))
      (is (= :c (guava-get grm 75) (rm 75)) "[70,80) remains"))))

(deftest coalescing-equivalence
  (testing "Adjacent same-value ranges coalesce"
    (let [grm (-> (guava-range-map)
                  (guava-put-coalescing! 0 50 :a)
                  (guava-put-coalescing! 50 100 :a))
          rm  (-> (oc/range-map)
                  (oc/assoc-coalescing [0 50] :a)
                  (oc/assoc-coalescing [50 100] :a))]
      (is (maps-equivalent? rm grm) "Adjacent coalesce")
      (is (= 1 (count (guava->seq grm)) (count rm)) "Single range")))

  (testing "Adjacent different-value ranges don't coalesce"
    (let [grm (-> (guava-range-map)
                  (guava-put-coalescing! 0 50 :a)
                  (guava-put-coalescing! 50 100 :b))
          rm  (-> (oc/range-map)
                  (oc/assoc-coalescing [0 50] :a)
                  (oc/assoc-coalescing [50 100] :b))]
      (is (maps-equivalent? rm grm) "Different values")
      (is (= 2 (count (guava->seq grm)) (count rm)) "Two ranges")))

  (testing "Coalescing with gap - no coalesce"
    (let [grm (-> (guava-range-map)
                  (guava-put-coalescing! 0 40 :a)
                  (guava-put-coalescing! 60 100 :a))
          rm  (-> (oc/range-map)
                  (oc/assoc-coalescing [0 40] :a)
                  (oc/assoc-coalescing [60 100] :a))]
      (is (maps-equivalent? rm grm) "Gap - no coalesce")
      (is (= 2 (count (guava->seq grm)) (count rm)) "Two ranges"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Randomized Property-Based Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-put-equivalence 100
  (prop/for-all [pairs gen-range-value-pairs]
    (let [grm (apply-ops-to-guava pairs)
          rm  (apply-ops-to-ours pairs)]
      (maps-equivalent? rm grm))))

(defspec prop-coalescing-put-equivalence 100
  (prop/for-all [pairs gen-range-value-pairs]
    (let [grm (apply-coalescing-ops-to-guava pairs)
          rm  (apply-coalescing-ops-to-ours pairs)]
      (maps-equivalent? rm grm))))

(defspec prop-point-lookup-equivalence 100
  (prop/for-all [pairs gen-range-value-pairs
                 points (gen/vector gen-point 0 20)]
    (let [grm (apply-ops-to-guava pairs)
          rm  (apply-ops-to-ours pairs)]
      (every? (fn [x]
                (= (guava-get grm x) (rm x)))
              points))))

(defspec prop-get-entry-equivalence 100
  (prop/for-all [pairs gen-range-value-pairs
                 points (gen/vector gen-point 0 20)]
    (let [grm (apply-ops-to-guava pairs)
          rm  (apply-ops-to-ours pairs)]
      (every? (fn [x]
                (= (guava-get-entry grm x) (oc/get-entry rm x)))
              points))))

(defspec prop-remove-equivalence 100
  (prop/for-all [pairs gen-range-value-pairs
                 remove-range gen-range]
    (let [[lo hi] remove-range
          grm (-> (apply-ops-to-guava pairs)
                  (guava-remove! lo hi))
          rm  (-> (apply-ops-to-ours pairs)
                  (oc/range-remove [lo hi]))]
      (maps-equivalent? rm grm))))

(defspec prop-mixed-operations 100
  (prop/for-all [initial-pairs gen-range-value-pairs
                 more-pairs gen-range-value-pairs
                 remove-ranges (gen/vector gen-range 0 5)]
    (let [;; Apply initial, then more puts, then removes
          grm (reduce (fn [g [lo hi]]
                        (guava-remove! g lo hi))
                      (reduce (fn [g [[lo hi] v]]
                                (guava-put! g lo hi v))
                              (apply-ops-to-guava initial-pairs)
                              more-pairs)
                      remove-ranges)
          rm (reduce (fn [r [lo hi]]
                       (oc/range-remove r [lo hi]))
                     (reduce (fn [r [[lo hi] v]]
                               (assoc r [lo hi] v))
                             (apply-ops-to-ours initial-pairs)
                             more-pairs)
                     remove-ranges)]
      (maps-equivalent? rm grm))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stress Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest stress-many-ranges
  (testing "Many random ranges"
    (dotimes [_ 10]
      (let [n 100
            ranges (for [_ (range n)]
                     (let [a (rand-int 10000)
                           b (rand-int 10000)
                           lo (min a b)
                           hi (max a b)
                           hi (if (= lo hi) (inc hi) hi)]
                       [[lo hi] (rand-int 1000)]))
            grm (reduce (fn [g [[lo hi] v]] (guava-put! g lo hi v))
                        (guava-range-map)
                        ranges)
            rm (reduce (fn [r [[lo hi] v]] (assoc r [lo hi] v))
                       (oc/range-map)
                       ranges)]
        (is (maps-equivalent? rm grm) "Many ranges")
        ;; Spot check some lookups
        (dotimes [_ 50]
          (let [x (rand-int 10000)]
            (is (= (guava-get grm x) (rm x)) (str "Lookup at " x))))))))

(deftest stress-many-removes
  (testing "Many inserts followed by many removes"
    (dotimes [_ 10]
      (let [n 50
            insert-ranges (for [_ (range n)]
                            (let [a (rand-int 1000)
                                  b (rand-int 1000)
                                  lo (min a b)
                                  hi (max a b)
                                  hi (if (= lo hi) (inc hi) hi)]
                              [[lo hi] (rand-int 100)]))
            remove-ranges (for [_ (range (quot n 2))]
                            (let [a (rand-int 1000)
                                  b (rand-int 1000)
                                  lo (min a b)
                                  hi (max a b)
                                  hi (if (= lo hi) (inc hi) hi)]
                              [lo hi]))
            grm (reduce (fn [g [lo hi]] (guava-remove! g lo hi))
                        (reduce (fn [g [[lo hi] v]] (guava-put! g lo hi v))
                                (guava-range-map)
                                insert-ranges)
                        remove-ranges)
            rm (reduce (fn [r [lo hi]] (oc/range-remove r [lo hi]))
                       (reduce (fn [r [[lo hi] v]] (assoc r [lo hi] v))
                               (oc/range-map)
                               insert-ranges)
                       remove-ranges)]
        (is (maps-equivalent? rm grm) "Many removes")))))
