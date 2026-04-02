(ns com.dean.ordered-collections.interop-test
  "Property-based tests verifying protocol extensions on standard Clojure collections.

   Tests that protocol operations on PersistentTreeSet/PersistentTreeMap produce
   results equivalent to:
   1. Reference implementations (clojure.set, subseq/rsubseq, etc.)
   2. ordered-set/ordered-map implementations"
  (:require [clojure.set :as cset]
            [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [com.dean.ordered-collections.test-utils :as tu
             :refer [gen-int-set gen-non-empty-int-set
                     gen-int-map-entries gen-non-empty-int-map-entries
                     gen-test-symbol
                     ->ss ->hs ->os ->sm ->om]]
            [com.dean.ordered-collections.types.interop]
            [com.dean.ordered-collections.protocol :as proto]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PExtensibleSet - Set algebra operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-tree-set-union 100
  (prop/for-all [xs1 gen-int-set, xs2 gen-int-set]
    (let [ts1 (->ss xs1), ts2 (->ss xs2)
          proto-result (proto/union ts1 ts2)
          ref-result (cset/union ts1 ts2)]
      (= (set proto-result) (set ref-result)))))

(defspec prop-tree-set-intersection 100
  (prop/for-all [xs1 gen-int-set, xs2 gen-int-set]
    (let [ts1 (->ss xs1), ts2 (->ss xs2)
          proto-result (proto/intersection ts1 ts2)
          ref-result (cset/intersection ts1 ts2)]
      (= (set proto-result) (set ref-result)))))

(defspec prop-tree-set-difference 100
  (prop/for-all [xs1 gen-int-set, xs2 gen-int-set]
    (let [ts1 (->ss xs1), ts2 (->ss xs2)
          proto-result (proto/difference ts1 ts2)
          ref-result (cset/difference ts1 ts2)]
      (= (set proto-result) (set ref-result)))))

(defspec prop-tree-set-subset 100
  (prop/for-all [xs gen-int-set]
    (let [ts-full (->ss xs)
          ts-half (->ss (take (quot (count xs) 2) xs))]
      (and (= (proto/subset? ts-half ts-full) (cset/subset? ts-half ts-full))
           (= (proto/subset? ts-full ts-full) (cset/subset? ts-full ts-full))
           (= (proto/subset? ts-full ts-half) (cset/subset? ts-full ts-half))))))

(defspec prop-tree-set-superset 100
  (prop/for-all [xs gen-int-set]
    (let [ts-full (->ss xs)
          ts-half (->ss (take (quot (count xs) 2) xs))]
      (and (= (proto/superset? ts-full ts-half) (cset/superset? ts-full ts-half))
           (= (proto/superset? ts-full ts-full) (cset/superset? ts-full ts-full))
           (= (proto/superset? ts-half ts-full) (cset/superset? ts-half ts-full))))))

(defspec prop-hash-set-union 100
  (prop/for-all [xs1 gen-int-set, xs2 gen-int-set]
    (let [hs1 (->hs xs1), hs2 (->hs xs2)
          proto-result (proto/union hs1 hs2)
          ref-result (cset/union hs1 hs2)]
      (= proto-result ref-result))))

(defspec prop-hash-set-intersection 100
  (prop/for-all [xs1 gen-int-set, xs2 gen-int-set]
    (let [hs1 (->hs xs1), hs2 (->hs xs2)
          proto-result (proto/intersection hs1 hs2)
          ref-result (cset/intersection hs1 hs2)]
      (= proto-result ref-result))))

(defspec prop-hash-set-difference 100
  (prop/for-all [xs1 gen-int-set, xs2 gen-int-set]
    (let [hs1 (->hs xs1), hs2 (->hs xs2)
          proto-result (proto/difference hs1 hs2)
          ref-result (cset/difference hs1 hs2)]
      (= proto-result ref-result))))

;; Cross-implementation compatibility: tree-set and ordered-set produce same results
(defspec prop-set-algebra-cross-compatible 100
  (prop/for-all [xs1 gen-int-set, xs2 gen-int-set]
    (let [ts1 (->ss xs1), ts2 (->ss xs2)
          os1 (->os xs1), os2 (->os xs2)]
      (and (= (set (proto/union ts1 ts2)) (set (proto/union os1 os2)))
           (= (set (proto/intersection ts1 ts2)) (set (proto/intersection os1 os2)))
           (= (set (proto/difference ts1 ts2)) (set (proto/difference os1 os2)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PNearest - Floor/ceiling/predecessor/successor operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-tree-set-nearest-floor 100
  (prop/for-all [xs gen-non-empty-int-set, k gen/small-integer]
    (let [ts (->ss xs)
          proto-result (proto/nearest ts :<= k)
          ref-result (first (rsubseq ts <= k))]
      (= proto-result ref-result))))

(defspec prop-tree-set-nearest-ceiling 100
  (prop/for-all [xs gen-non-empty-int-set, k gen/small-integer]
    (let [ts (->ss xs)
          proto-result (proto/nearest ts :>= k)
          ref-result (first (subseq ts >= k))]
      (= proto-result ref-result))))

(defspec prop-tree-set-nearest-predecessor 100
  (prop/for-all [xs gen-non-empty-int-set, k gen/small-integer]
    (let [ts (->ss xs)
          proto-result (proto/nearest ts :< k)
          ref-result (first (rsubseq ts < k))]
      (= proto-result ref-result))))

(defspec prop-tree-set-nearest-successor 100
  (prop/for-all [xs gen-non-empty-int-set, k gen/small-integer]
    (let [ts (->ss xs)
          proto-result (proto/nearest ts :> k)
          ref-result (first (subseq ts > k))]
      (= proto-result ref-result))))

(defspec prop-tree-set-subrange 100
  (prop/for-all [xs gen-non-empty-int-set, k gen/small-integer, test gen-test-symbol]
    (let [ts (->ss xs)
          proto-result (proto/subrange ts test k)
          ref-result (into (sorted-set)
                           (case test
                             :<  (subseq ts < k)
                             :<= (subseq ts <= k)
                             :>  (subseq ts > k)
                             :>= (subseq ts >= k)))]
      (= (vec proto-result) (vec ref-result)))))

(defspec prop-tree-map-nearest-floor 100
  (prop/for-all [xs gen-non-empty-int-map-entries, k gen/small-integer]
    (let [tm (->sm xs)
          proto-result (proto/nearest tm :<= k)
          ref-entry (first (rsubseq tm <= k))]
      (= proto-result (when ref-entry [(key ref-entry) (val ref-entry)])))))

(defspec prop-tree-map-nearest-ceiling 100
  (prop/for-all [xs gen-non-empty-int-map-entries, k gen/small-integer]
    (let [tm (->sm xs)
          proto-result (proto/nearest tm :>= k)
          ref-entry (first (subseq tm >= k))]
      (= proto-result (when ref-entry [(key ref-entry) (val ref-entry)])))))

(defspec prop-tree-map-subrange 100
  (prop/for-all [xs gen-non-empty-int-map-entries, k gen/small-integer, test gen-test-symbol]
    (let [tm (->sm xs)
          proto-result (proto/subrange tm test k)
          ref-result (into (sorted-map)
                           (case test
                             :<  (subseq tm < k)
                             :<= (subseq tm <= k)
                             :>  (subseq tm > k)
                             :>= (subseq tm >= k)))]
      (= (vec proto-result) (vec ref-result)))))

;; Cross-implementation: tree-set/map and ordered-set/map produce same nearest results
(defspec prop-nearest-cross-compatible-set 100
  (prop/for-all [xs gen-non-empty-int-set, k gen/small-integer, test gen-test-symbol]
    (let [ts (->ss xs)
          os (->os xs)]
      (= (proto/nearest ts test k) (proto/nearest os test k)))))

(defspec prop-nearest-cross-compatible-map 100
  (prop/for-all [xs gen-non-empty-int-map-entries, k gen/small-integer, test gen-test-symbol]
    (let [tm (->sm xs)
          om (->om xs)]
      (= (proto/nearest tm test k) (proto/nearest om test k)))))

(defspec prop-subrange-cross-compatible-set 100
  (prop/for-all [xs gen-non-empty-int-set, k gen/small-integer, test gen-test-symbol]
    (let [ts (->ss xs)
          os (->os xs)]
      (= (vec (proto/subrange ts test k)) (vec (proto/subrange os test k))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRanked - Index-based operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-tree-set-rank-of-present 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          sorted-vec (vec ts)
          x (rand-nth sorted-vec)
          proto-rank (proto/rank-of ts x)
          expected-rank (.indexOf ^java.util.List sorted-vec x)]
      (= proto-rank expected-rank))))

(defspec prop-tree-set-rank-of-absent 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          ;; Find a value not in the set
          absent (first (filter #(not (contains? ts %)) (range -1000 1000)))]
      (when absent
        (= (proto/rank-of ts absent) -1)))))

(defspec prop-tree-set-slice 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          n (count ts)
          start (rand-int n)
          end (+ start (rand-int (inc (- n start))))]
      (let [proto-result (proto/slice ts start end)
            expected (->> ts seq (drop start) (take (- end start)))]
        (= (vec proto-result) (vec expected))))))

(defspec prop-tree-set-median 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          n (count ts)
          proto-result (proto/median ts)
          expected (nth (seq ts) (quot (dec n) 2))]
      (= proto-result expected))))

(defspec prop-tree-set-percentile 100
  (prop/for-all [xs gen-non-empty-int-set, pct (gen/choose 0 100)]
    (let [ts (->ss xs)
          n (count ts)
          proto-result (proto/percentile ts pct)
          idx (min (dec n) (long (* (/ (double pct) 100.0) n)))
          expected (nth (seq ts) idx)]
      (= proto-result expected))))

(defspec prop-tree-map-rank-of 100
  (prop/for-all [xs gen-non-empty-int-map-entries]
    (let [tm (->sm xs)
          k (key (rand-nth (vec tm)))
          proto-rank (proto/rank-of tm k)
          expected-rank (count (subseq tm < k))]
      (= proto-rank expected-rank))))

(defspec prop-tree-map-slice 100
  (prop/for-all [xs gen-non-empty-int-map-entries]
    (let [tm (->sm xs)
          n (count tm)
          start (rand-int n)
          end (+ start (rand-int (inc (- n start))))]
      (let [proto-result (proto/slice tm start end)
            expected (->> tm seq (drop start) (take (- end start)))]
        (= (vec proto-result) (vec expected))))))

;; Cross-implementation: rank-of produces same results
(defspec prop-rank-cross-compatible-set 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          os (->os xs)
          sorted-vec (vec ts)
          x (rand-nth sorted-vec)]
      (= (proto/rank-of ts x) (proto/rank-of os x)))))

(defspec prop-median-cross-compatible-set 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          os (->os xs)]
      (= (proto/median ts) (proto/median os)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PSplittable - Split operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-tree-set-split-key-present 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          k (rand-nth (vec ts))
          [left entry right] (proto/split-key ts k)]
      (and (= entry k)
           (every? #(< % k) left)
           (every? #(> % k) right)
           (= (count ts) (+ (count left) 1 (count right)))))))

(defspec prop-tree-set-split-key-absent 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          absent (first (filter #(not (contains? ts %)) (range -1000 1000)))]
      (when absent
        (let [[left entry right] (proto/split-key ts absent)]
          (and (nil? entry)
               (every? #(< % absent) left)
               (every? #(> % absent) right)
               (= (count ts) (+ (count left) (count right)))))))))

(defspec prop-tree-set-split-at 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          n (count ts)
          i (rand-int (inc n))
          [left right] (proto/split-at ts i)]
      (and (= (count left) i)
           (= (count right) (- n i))
           (= (vec ts) (vec (concat left right)))))))

(defspec prop-tree-map-split-key-present 100
  (prop/for-all [xs gen-non-empty-int-map-entries]
    (let [tm (->sm xs)
          [k v] (rand-nth (vec tm))
          [left entry right] (proto/split-key tm k)]
      (and (= entry [k v])
           (every? #(< (key %) k) left)
           (every? #(> (key %) k) right)
           (= (count tm) (+ (count left) 1 (count right)))))))

(defspec prop-tree-map-split-key-absent 100
  (prop/for-all [xs gen-non-empty-int-map-entries]
    (let [tm (->sm xs)
          absent (first (filter #(not (contains? tm %)) (range -1000 1000)))]
      (when absent
        (let [[left entry right] (proto/split-key tm absent)]
          (and (nil? entry)
               (every? #(< (key %) absent) left)
               (every? #(> (key %) absent) right)
               (= (count tm) (+ (count left) (count right)))))))))

(defspec prop-tree-map-split-at 100
  (prop/for-all [xs gen-non-empty-int-map-entries]
    (let [tm (->sm xs)
          n (count tm)
          i (rand-int (inc n))
          [left right] (proto/split-at tm i)]
      (and (= (count left) i)
           (= (count right) (- n i))
           (= (vec tm) (vec (concat left right)))))))

;; Cross-implementation: split operations produce equivalent results
(defspec prop-split-key-cross-compatible-set 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          os (->os xs)
          k (rand-nth (vec ts))
          [ts-left ts-entry ts-right] (proto/split-key ts k)
          [os-left os-entry os-right] (proto/split-key os k)]
      (and (= ts-entry os-entry)
           (= (vec ts-left) (vec os-left))
           (= (vec ts-right) (vec os-right))))))

(defspec prop-split-at-cross-compatible-set 100
  (prop/for-all [xs gen-non-empty-int-set]
    (let [ts (->ss xs)
          os (->os xs)
          i (rand-int (inc (count ts)))
          [ts-left ts-right] (proto/split-at ts i)
          [os-left os-right] (proto/split-at os i)]
      (and (= (vec ts-left) (vec os-left))
           (= (vec ts-right) (vec os-right))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest empty-set-operations
  (testing "Protocol operations on empty sorted-set"
    (let [ts (sorted-set)]
      (is (= #{} (proto/union ts ts)))
      (is (= #{} (proto/intersection ts ts)))
      (is (= #{} (proto/difference ts ts)))
      (is (true? (proto/subset? ts ts)))
      (is (true? (proto/superset? ts ts)))
      (is (nil? (proto/nearest ts :>= 0)))
      (is (= -1 (proto/rank-of ts 0)))
      (is (nil? (proto/median ts)))
      (let [[left entry right] (proto/split-key ts 0)]
        (is (empty? left))
        (is (nil? entry))
        (is (empty? right))))))

(deftest empty-map-operations
  (testing "Protocol operations on empty sorted-map"
    (let [tm (sorted-map)]
      (is (nil? (proto/nearest tm :>= 0)))
      (is (= -1 (proto/rank-of tm 0)))
      (is (nil? (proto/median tm)))
      (let [[left entry right] (proto/split-key tm 0)]
        (is (empty? left))
        (is (nil? entry))
        (is (empty? right))))))

(deftest single-element-operations
  (testing "Protocol operations on single-element sorted-set"
    (let [ts (sorted-set 42)]
      (is (= 42 (proto/nearest ts :>= 0)))
      (is (= 42 (proto/nearest ts :<= 100)))
      (is (nil? (proto/nearest ts :< 42)))
      (is (nil? (proto/nearest ts :> 42)))
      (is (= 0 (proto/rank-of ts 42)))
      (is (= -1 (proto/rank-of ts 0)))
      (is (= 42 (proto/median ts)))
      (is (= 42 (proto/percentile ts 50)))
      (let [[left entry right] (proto/split-key ts 42)]
        (is (empty? left))
        (is (= 42 entry))
        (is (empty? right))))))

(deftest boundary-values
  (testing "Protocol operations with Long boundary values"
    (let [ts (sorted-set Long/MIN_VALUE -1 0 1 Long/MAX_VALUE)]
      (is (= Long/MIN_VALUE (proto/nearest ts :>= Long/MIN_VALUE)))
      (is (= Long/MAX_VALUE (proto/nearest ts :<= Long/MAX_VALUE)))
      (is (= 0 (proto/median ts)))
      (is (= 2 (proto/rank-of ts 0)))          ;; 0 is at index 2
      (is (= 0 (proto/rank-of ts Long/MIN_VALUE))))))

(deftest subrange-preserves-type
  (testing "subrange returns same collection type"
    (let [ts (sorted-set 1 2 3 4 5)]
      (is (instance? clojure.lang.PersistentTreeSet (proto/subrange ts :> 2)))
      (is (sorted? (proto/subrange ts :> 2))))))

(deftest split-preserves-type
  (testing "split returns same collection type"
    (let [ts (sorted-set 1 2 3 4 5)
          [left _ right] (proto/split-key ts 3)]
      (is (instance? clojure.lang.PersistentTreeSet left))
      (is (instance? clojure.lang.PersistentTreeSet right))
      (is (sorted? left))
      (is (sorted? right)))))
