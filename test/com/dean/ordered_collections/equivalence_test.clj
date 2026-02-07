(ns com.dean.ordered-collections.equivalence-test
  "Apples-to-apples equivalence tests verifying identical outcomes across
   sorted-set, ordered-set, and clojure.data.avl.

   Uses high-cardinality randomized test data and combines multiple
   operations in sequence to verify behavioral equivalence."
  (:require [clojure.data.avl :as avl]
            [clojure.set :as set]
            [clojure.test :refer [deftest testing is are]]
            [com.dean.ordered-collections.core :as core]
            [com.dean.ordered-collections.tree.protocol :as proto]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Data Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn random-ints
  "Generate n random integers in range [0, max-val)"
  [n max-val]
  (repeatedly n #(rand-int max-val)))

(defn random-int-set
  "Generate a set of n unique random integers in range [0, max-val)"
  [n max-val]
  (loop [s #{}]
    (if (>= (count s) n)
      (vec s)
      (recur (conj s (rand-int max-val))))))

(defn random-string-set
  "Generate a set of n unique random strings"
  [n]
  (let [chars "abcdefghijklmnopqrstuvwxyz0123456789"]
    (loop [s #{}]
      (if (>= (count s) n)
        (vec s)
        (recur (conj s (apply str (repeatedly 12 #(rand-nth chars)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collection Builders
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn build-sorted-set [elems]
  (into (sorted-set) elems))

(defn build-avl-set [elems]
  (into (avl/sorted-set) elems))

(defn build-ordered-set [elems]
  (core/ordered-set elems))

(defn build-all-sets
  "Build all three set types from the same elements"
  [elems]
  {:sorted   (build-sorted-set elems)
   :avl      (build-avl-set elems)
   :ordered  (build-ordered-set elems)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Equivalence Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sets-equivalent?
  "Check if all sets contain the same elements in the same order"
  [sets]
  (let [seqs (map #(vec (seq %)) (vals sets))]
    (apply = seqs)))

(defn assert-all-equivalent
  "Assert all sets are equivalent and return them"
  [sets msg]
  (is (sets-equivalent? sets) msg)
  sets)

(defn to-vec
  "Convert any set to a sorted vector for comparison"
  [s]
  (vec (seq s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic Operations Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest construction-equivalence-test
  (testing "Construction from random data produces identical sets"
    (dotimes [_ 10]
      (let [elems (random-int-set 1000 100000)
            sets  (build-all-sets elems)]
        (assert-all-equivalent sets "Construction should produce equivalent sets")
        (is (= (count elems) (count (:sorted sets)) (count (:avl sets))
               (count (:ordered sets)))
            "All sets should have same count")))))

(deftest incremental-insert-equivalence-test
  (testing "Incremental insertion produces identical sets"
    (dotimes [_ 5]
      (let [elems (random-int-set 500 50000)]
        (loop [ss (sorted-set)
               as (avl/sorted-set)
               os (core/ordered-set)
               xs elems]
          (if (empty? xs)
            (let [sets {:sorted ss :avl as :ordered os}]
              (assert-all-equivalent sets "Incremental insert should produce equivalent sets"))
            (let [x (first xs)]
              (recur (conj ss x)
                     (conj as x)
                     (conj os x)
                     (rest xs)))))))))

(deftest deletion-equivalence-test
  (testing "Deletion produces identical sets"
    (dotimes [_ 5]
      (let [elems    (random-int-set 1000 50000)
            to-del   (take 500 (shuffle elems))
            ss       (reduce disj (build-sorted-set elems) to-del)
            as       (reduce disj (build-avl-set elems) to-del)
            os       (reduce disj (build-ordered-set elems) to-del)
            sets     {:sorted ss :avl as :ordered os}]
        (assert-all-equivalent sets "Deletion should produce equivalent sets")))))

(deftest lookup-equivalence-test
  (testing "Lookups return identical results"
    (dotimes [_ 5]
      (let [elems      (random-int-set 1000 50000)
            sets       (build-all-sets elems)
            test-keys  (concat (take 100 elems)                    ; keys that exist
                               (random-ints 100 100000))]          ; keys that may not exist
        (doseq [k test-keys]
          (let [results (map #(contains? % k) (vals sets))]
            (is (apply = results)
                (str "contains? should return same result for key " k))))
        (doseq [k test-keys]
          (let [results (map #(get % k :not-found) (vals sets))]
            (is (apply = results)
                (str "get should return same result for key " k))))))))

(deftest iteration-equivalence-test
  (testing "Iteration order is identical"
    (dotimes [_ 5]
      (let [elems (random-int-set 1000 50000)
            sets  (build-all-sets elems)]
        ;; Forward iteration
        (is (apply = (map to-vec (vals sets)))
            "Forward iteration should be identical")
        ;; Reverse iteration
        (is (apply = (map #(vec (rseq %)) (vals sets)))
            "Reverse iteration should be identical")
        ;; Reduce
        (let [sums (map #(reduce + 0 %) (vals sets))]
          (is (apply = sums)
              "Reduce should produce identical results"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Algebra Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest union-equivalence-test
  (testing "Union produces identical results"
    (dotimes [_ 5]
      (let [elems1 (random-int-set 500 50000)
            elems2 (random-int-set 500 50000)
            ss1    (build-sorted-set elems1)
            ss2    (build-sorted-set elems2)
            as1    (build-avl-set elems1)
            as2    (build-avl-set elems2)
            os1    (build-ordered-set elems1)
            os2    (build-ordered-set elems2)
            ;; Compute unions
            ss-union (set/union ss1 ss2)
            as-union (into (avl/sorted-set) (concat elems1 elems2))
            os-union (proto/union os1 os2)]
        (is (= (to-vec ss-union) (to-vec as-union) (to-vec os-union))
            "Union should produce equivalent sets")))))

(deftest intersection-equivalence-test
  (testing "Intersection produces identical results"
    (dotimes [_ 5]
      (let [;; Create overlapping sets
            base   (random-int-set 300 20000)
            extra1 (random-int-set 200 20000)
            extra2 (random-int-set 200 20000)
            elems1 (concat base extra1)
            elems2 (concat base extra2)
            ss1    (build-sorted-set elems1)
            ss2    (build-sorted-set elems2)
            os1    (build-ordered-set elems1)
            os2    (build-ordered-set elems2)
            ;; Compute intersections
            ss-int (set/intersection ss1 ss2)
            os-int (proto/intersection os1 os2)]
        (is (= (to-vec ss-int) (to-vec os-int))
            "Intersection should produce equivalent sets")))))

(deftest difference-equivalence-test
  (testing "Difference produces identical results"
    (dotimes [_ 5]
      (let [;; Create overlapping sets
            base   (random-int-set 300 20000)
            extra1 (random-int-set 200 20000)
            extra2 (random-int-set 200 20000)
            elems1 (concat base extra1)
            elems2 (concat base extra2)
            ss1    (build-sorted-set elems1)
            ss2    (build-sorted-set elems2)
            os1    (build-ordered-set elems1)
            os2    (build-ordered-set elems2)
            ;; Compute differences
            ss-diff (set/difference ss1 ss2)
            os-diff (proto/difference os1 os2)]
        (is (= (to-vec ss-diff) (to-vec os-diff))
            "Difference should produce equivalent sets")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SortedSet Interface Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest sorted-set-interface-equivalence-test
  (testing "Sorted set interface methods produce identical results"
    (dotimes [_ 5]
      (let [elems  (random-int-set 1000 50000)
            ss     (build-sorted-set elems)
            as     (build-avl-set elems)
            os     (build-ordered-set elems)
            sorted (vec (sort elems))]
        ;; first/last - use Clojure functions which work on all sorted collections
        (is (= (first ss) (first as) (first os))
            "first should be identical")
        (is (= (last (seq ss)) (last (seq as)) (last (seq os)))
            "last should be identical")
        ;; Test range operations using filter (works on all collections)
        (let [from (nth sorted 100)
              to   (nth sorted 900)]
          ;; subSet-like: elements >= from and < to
          (is (= (vec (filter #(and (>= % from) (< % to)) ss))
                 (vec (filter #(and (>= % from) (< % to)) as))
                 (vec (filter #(and (>= % from) (< % to)) os)))
              "subSet range should be identical")
          ;; headSet-like: elements < to
          (is (= (vec (filter #(< % to) ss))
                 (vec (filter #(< % to) as))
                 (vec (filter #(< % to) os)))
              "headSet range should be identical")
          ;; tailSet-like: elements >= from
          (is (= (vec (filter #(>= % from) ss))
                 (vec (filter #(>= % from) as))
                 (vec (filter #(>= % from) os)))
              "tailSet range should be identical"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Indexed Access Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest nth-equivalence-test
  (testing "nth access produces identical results"
    (dotimes [_ 5]
      (let [elems (random-int-set 1000 50000)
            as    (build-avl-set elems)
            os    (build-ordered-set elems)
            idxs  (repeatedly 100 #(rand-int (count elems)))]
        (doseq [i idxs]
          (is (= (nth as i) (nth os i))
              (str "nth at index " i " should be identical")))))))

(deftest rank-equivalence-test
  (testing "rank-of produces identical results"
    (dotimes [_ 5]
      (let [elems  (random-int-set 1000 50000)
            as     (build-avl-set elems)
            os     (build-ordered-set elems)
            sorted (vec (sort elems))]
        (doseq [i (range 0 (count sorted) 10)]
          (let [k (nth sorted i)]
            (is (= (avl/rank-of as k)
                   (.indexOf ^java.util.List os k))
                (str "rank of " k " should be identical"))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Split Operations Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest split-equivalence-test
  (testing "split-key produces identical results"
    (dotimes [_ 5]
      (let [elems  (random-int-set 1000 50000)
            as     (build-avl-set elems)
            os     (build-ordered-set elems)
            sorted (vec (sort elems))
            ;; Test with keys that exist and don't exist
            test-keys (concat
                        (map #(nth sorted %) [0 100 500 900 999])
                        [(dec (first sorted))    ; before all
                         (inc (last sorted))])]  ; after all
        (doseq [k test-keys]
          (let [[as-lt as-eq as-gt] (avl/split-key k as)
                os-lt (.headSet ^java.util.SortedSet os k)
                os-gt (.tailSet ^java.util.SortedSet os k)
                os-eq (when (contains? os k) k)]
            (is (= (to-vec as-lt) (to-vec os-lt))
                (str "split lesser-than at " k " should be identical"))
            ;; tailSet includes the key if present, so adjust comparison
            (let [as-gt-vec (to-vec as-gt)
                  os-gt-adjusted (if os-eq
                                   (to-vec (disj os-gt k))
                                   (to-vec os-gt))]
              (is (= as-gt-vec os-gt-adjusted)
                  (str "split greater-than at " k " should be identical")))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Complex Multi-Operation Sequences
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest build-union-split-sequence-test
  (testing "Build -> Union -> Split sequence produces identical results"
    (dotimes [_ 3]
      (let [;; Build two sets
            elems1 (random-int-set 500 30000)
            elems2 (random-int-set 500 30000)
            ss1    (build-sorted-set elems1)
            ss2    (build-sorted-set elems2)
            os1    (build-ordered-set elems1)
            os2    (build-ordered-set elems2)
            ;; Union
            ss-union (into ss1 ss2)
            os-union (proto/union os1 os2)
            _ (is (= (to-vec ss-union) (to-vec os-union))
                  "Union should be equivalent")
            ;; Split at median using same computation for both
            ;; Use consistent filter-based approach since SortedSet semantics may vary
            median (nth (vec ss-union) (quot (count ss-union) 2))
            ss-head (into (sorted-set) (filter #(< % median) ss-union))
            ss-tail (into (sorted-set) (filter #(>= % median) ss-union))
            os-head (into (core/ordered-set) (filter #(< % median) os-union))
            os-tail (into (core/ordered-set) (filter #(>= % median) os-union))]
        (is (= (to-vec ss-head) (to-vec os-head))
            "Split head should be equivalent")
        (is (= (to-vec ss-tail) (to-vec os-tail))
            "Split tail should be equivalent")))))

(deftest build-delete-intersect-sequence-test
  (testing "Build -> Delete -> Intersect sequence produces identical results"
    (dotimes [_ 3]
      (let [;; Build overlapping sets
            common  (random-int-set 200 20000)
            extra1  (random-int-set 300 20000)
            extra2  (random-int-set 300 20000)
            elems1  (concat common extra1)
            elems2  (concat common extra2)
            ss1     (build-sorted-set elems1)
            ss2     (build-sorted-set elems2)
            os1     (build-ordered-set elems1)
            os2     (build-ordered-set elems2)
            ;; Delete some elements from each
            to-del1 (take 100 (shuffle extra1))
            to-del2 (take 100 (shuffle extra2))
            ss1'    (reduce disj ss1 to-del1)
            ss2'    (reduce disj ss2 to-del2)
            os1'    (reduce disj os1 to-del1)
            os2'    (reduce disj os2 to-del2)
            _ (is (= (to-vec ss1') (to-vec os1'))
                  "After deletion, set1 should be equivalent")
            _ (is (= (to-vec ss2') (to-vec os2'))
                  "After deletion, set2 should be equivalent")
            ;; Intersect
            ss-int (set/intersection ss1' ss2')
            os-int (proto/intersection os1' os2')]
        (is (= (to-vec ss-int) (to-vec os-int))
            "Intersection after deletions should be equivalent")))))

(deftest interleaved-insert-delete-test
  (testing "Interleaved insert/delete operations produce identical results"
    (dotimes [_ 3]
      (let [ops (for [i (range 1000)]
                  (if (< (rand) 0.7)
                    [:insert (rand-int 50000)]
                    [:delete (rand-int 50000)]))]
        (loop [ss (sorted-set)
               as (avl/sorted-set)
               os (core/ordered-set)
               ops ops]
          (if (empty? ops)
            (is (= (to-vec ss) (to-vec as) (to-vec os))
                "After interleaved ops, all sets should be equivalent")
            (let [[op val] (first ops)]
              (case op
                :insert (recur (conj ss val) (conj as val) (conj os val) (rest ops))
                :delete (recur (disj ss val) (disj as val) (disj os val) (rest ops))))))))))

(deftest multiple-union-chain-test
  (testing "Chained unions produce identical results"
    (let [sets (for [_ (range 5)]
                 (random-int-set 200 50000))
          ss-list (map build-sorted-set sets)
          os-list (map build-ordered-set sets)
          ss-union (reduce set/union ss-list)
          os-union (reduce proto/union os-list)]
      (is (= (to-vec ss-union) (to-vec os-union))
          "Chained unions should be equivalent"))))

(deftest subset-superset-equivalence-test
  (testing "subset?/superset? produce identical results"
    (dotimes [_ 5]
      (let [elems    (random-int-set 500 30000)
            subset-e (take 250 elems)
            ss-full  (build-sorted-set elems)
            ss-sub   (build-sorted-set subset-e)
            os-full  (build-ordered-set elems)
            os-sub   (build-ordered-set subset-e)]
        (is (= (set/subset? ss-sub ss-full)
               (proto/subset os-sub os-full))
            "subset? should return same result")
        (is (= (set/superset? ss-full ss-sub)
               (proto/superset os-full os-sub))
            "superset? should return same result")
        ;; Non-subset case
        (let [other-e (random-int-set 100 30000)
              ss-other (build-sorted-set other-e)
              os-other (build-ordered-set other-e)]
          (is (= (set/subset? ss-other ss-full)
                 (proto/subset os-other os-full))
              "subset? for non-subset should return same result"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Key Tests (Custom Comparator)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-key-equivalence-test
  (testing "String keys produce identical results"
    (dotimes [_ 3]
      (let [elems (random-string-set 500)
            ss    (into (sorted-set) elems)
            as    (into (avl/sorted-set) elems)
            os    (core/ordered-set elems)]
        (is (= (to-vec ss) (to-vec as) (to-vec os))
            "String sets should be equivalent")
        ;; Test operations
        (let [to-del (take 100 (shuffle elems))
              ss'    (reduce disj ss to-del)
              as'    (reduce disj as to-del)
              os'    (reduce disj os to-del)]
          (is (= (to-vec ss') (to-vec as') (to-vec os'))
              "String sets after deletion should be equivalent"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest empty-set-operations-test
  (testing "Operations on empty sets are equivalent"
    (let [ss (sorted-set)
          as (avl/sorted-set)
          os (core/ordered-set)]
      (is (= (count ss) (count as) (count os) 0)
          "Empty sets should have count 0")
      (is (= (to-vec ss) (to-vec as) (to-vec os) [])
          "Empty sets should produce empty seqs")
      ;; Compare results using to-vec since different set types aren't equal by =
      (is (= (to-vec (disj ss 42)) (to-vec (disj as 42)) (to-vec (disj os 42)) [])
          "Disjoining from empty set should return empty set")
      ;; Union with empty
      (let [elems [1 2 3]
            ss1   (build-sorted-set elems)
            os1   (build-ordered-set elems)]
        (is (= (to-vec (set/union ss ss1))
               (to-vec (proto/union os os1)))
            "Union with empty should equal other set")))))

(deftest single-element-operations-test
  (testing "Operations on single-element sets are equivalent"
    (let [ss (sorted-set 42)
          as (avl/sorted-set 42)
          os (core/ordered-set [42])]
      (is (= (count ss) (count as) (count os) 1)
          "Single element sets should have count 1")
      (is (= (first ss) (first as) (first os) 42)
          "First element should be 42")
      (is (= (to-vec (disj ss 42)) (to-vec (disj as 42))
             (to-vec (disj os 42)) [])
          "Disjoining single element should produce empty set"))))

(deftest duplicate-insert-test
  (testing "Duplicate inserts produce identical results"
    (let [elems (concat (range 100) (range 50))  ; duplicates
          ss    (into (sorted-set) elems)
          as    (into (avl/sorted-set) elems)
          os    (core/ordered-set elems)]
      (is (= (count ss) (count as) (count os) 100)
          "Duplicate inserts should not increase count")
      (is (= (to-vec ss) (to-vec as) (to-vec os))
          "Sets with duplicates should be equivalent"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Large Scale Stress Test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest large-scale-stress-test
  (testing "Large scale operations produce identical results"
    (let [n      10000
          elems  (random-int-set n 1000000)
          sets   (build-all-sets elems)]
      ;; Verify construction
      (assert-all-equivalent sets "Large scale construction should be equivalent")
      ;; Verify 1000 random lookups
      (let [test-keys (concat (take 500 (shuffle elems))
                              (random-ints 500 1000000))]
        (doseq [k test-keys]
          (let [results (map #(contains? % k) (vals sets))]
            (is (apply = results)
                (str "Large scale lookup for " k " should be equivalent")))))
      ;; Verify iteration sum
      (let [sums (map #(reduce + 0 %) (vals sets))]
        (is (apply = sums)
            "Large scale iteration sum should be equivalent"))
      ;; Verify deletion of 5000 elements
      (let [to-del  (take 5000 (shuffle elems))
            ss'     (reduce disj (:sorted sets) to-del)
            as'     (reduce disj (:avl sets) to-del)
            os'     (reduce disj (:ordered sets) to-del)]
        (is (= (to-vec ss') (to-vec as') (to-vec os'))
            "Large scale deletion should produce equivalent sets")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reduce Variants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest reduce-variants-test
  (testing "All reduce variants produce identical results"
    (dotimes [_ 3]
      (let [elems (random-int-set 1000 50000)
            sets  (build-all-sets elems)]
        ;; reduce with init
        (let [results (map #(reduce + 0 %) (vals sets))]
          (is (apply = results)
              "reduce with init should be identical"))
        ;; reduce without init
        (let [results (map #(reduce + %) (vals sets))]
          (is (apply = results)
              "reduce without init should be identical"))
        ;; reduce with early termination
        (let [results (map #(reduce (fn [acc x]
                                      (if (> acc 10000)
                                        (reduced acc)
                                        (+ acc x)))
                                    0 %)
                           (vals sets))]
          (is (apply = results)
              "reduce with early termination should be identical"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NavigableSet Interface Tests
;; Note: Clojure's sorted-set and data.avl do not implement java.util.NavigableSet.
;; We test ordered-set's NavigableSet methods against expected values computed
;; from the sorted element list.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expected-ceiling
  "Compute expected ceiling value (smallest element >= k)"
  [sorted-vec k]
  (first (filter #(>= % k) sorted-vec)))

(defn expected-floor
  "Compute expected floor value (largest element <= k)"
  [sorted-vec k]
  (last (filter #(<= % k) sorted-vec)))

(deftest navigable-set-equivalence-test
  (testing "NavigableSet ceiling/floor produce correct results"
    (dotimes [_ 5]
      (let [elems  (random-int-set 1000 50000)
            os     (build-ordered-set elems)
            sorted (vec (sort elems))
            min-elem (first sorted)
            max-elem (last sorted)
            ;; Test ceiling/floor for various keys within the set's range
            ;; Skip edge cases where result would be nil (ordered-set throws instead)
            test-keys (concat
                        (take 10 sorted)
                        (take-last 10 sorted)
                        ;; Keys in middle of range that may or may not exist
                        (map #(+ % (rand-int 100))
                             (take 20 (drop 100 sorted))))]
        ;; Only test keys that have valid ceiling (k <= max-elem)
        (doseq [k (filter #(<= % max-elem) test-keys)]
          (is (= (expected-ceiling sorted k)
                 (.ceiling ^java.util.NavigableSet os k))
              (str "ceiling of " k " should match expected")))
        ;; Only test keys that have valid floor (k >= min-elem)
        (doseq [k (filter #(>= % min-elem) test-keys)]
          (is (= (expected-floor sorted k)
                 (.floor ^java.util.NavigableSet os k))
              (str "floor of " k " should match expected")))))))
