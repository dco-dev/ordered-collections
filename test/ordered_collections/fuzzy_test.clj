(ns ordered-collections.fuzzy-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.core.reducers :as r]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ordered-collections.test-utils :as tu]
            [ordered-collections.core :as oc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Set Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fuzzy-set-basic-test
  (testing "Basic fuzzy set operations"
    (let [fs (oc/fuzzy-set [1 5 10 20 50 100])]
      ;; Exact matches
      (is (= 1 (fs 1)))
      (is (= 10 (fs 10)))
      (is (= 100 (fs 100)))

      ;; Closest matches
      (is (= 1 (fs 0)))     ; 0 is closest to 1
      (is (= 1 (fs 2)))     ; 2 is closest to 1
      (is (= 5 (fs 4)))     ; 4 is closest to 5
      (is (= 5 (fs 6)))     ; 6 is closest to 5
      (is (= 5 (fs 7)))     ; 7 is closest to 5
      (is (= 10 (fs 8)))    ; 8 is closest to 10
      (is (= 10 (fs 12)))   ; 12 is closest to 10
      (is (= 20 (fs 16)))   ; 16 is closest to 20
      (is (= 100 (fs 200))) ; 200 is closest to 100
      )))

(deftest fuzzy-set-tiebreak-test
  (testing "Tiebreaker with :< (prefer smaller)"
    (let [fs (oc/fuzzy-set [0 10 20] :tiebreak :<)]
      (is (= 0 (fs 5)))     ; 5 is equidistant from 0 and 10, prefer smaller
      (is (= 10 (fs 15))))) ; 15 is equidistant from 10 and 20, prefer smaller

  (testing "Tiebreaker with :> (prefer larger)"
    (let [fs (oc/fuzzy-set [0 10 20] :tiebreak :>)]
      (is (= 10 (fs 5)))    ; 5 is equidistant from 0 and 10, prefer larger
      (is (= 20 (fs 15))))) ; 15 is equidistant from 10 and 20, prefer larger
  )

(deftest fuzzy-set-edge-cases-test
  (testing "Empty fuzzy set"
    (let [fs (oc/fuzzy-set [])]
      (is (nil? (fs 5)))
      (is (= :not-found (fs 5 :not-found)))))

  (testing "Single element fuzzy set"
    (let [fs (oc/fuzzy-set [42])]
      (is (= 42 (fs 0)))
      (is (= 42 (fs 100)))
      (is (= 42 (fs 42)))))

  (testing "Query at extremes"
    (let [fs (oc/fuzzy-set [10 20 30])]
      (is (= 10 (fs -1000)))   ; far below range
      (is (= 30 (fs 1000))))) ; far above range
  )

(deftest fuzzy-set-exact-contains-test
  (testing "exact-contains? for precise membership"
    (let [fs (oc/fuzzy-set [1 5 10])]
      (is (oc/fuzzy-exact-contains? fs 5))
      (is (not (oc/fuzzy-exact-contains? fs 6)))
      (is (not (oc/fuzzy-exact-contains? fs 7))))))

(deftest fuzzy-set-nearest-test
  (testing "fuzzy-nearest returns element and distance"
    (let [fs (oc/fuzzy-set [0 10 20])]
      (is (= [10 0.0] (oc/fuzzy-nearest fs 10)))  ; exact match
      (is (= [10 3.0] (oc/fuzzy-nearest fs 7)))   ; closest with distance
      (is (= [0 5.0] (oc/fuzzy-nearest fs -5)))))); negative query

(deftest fuzzy-set-collection-ops-test
  (testing "Standard collection operations"
    (let [fs (oc/fuzzy-set [3 1 4 1 5 9 2 6])]
      (is (= 7 (count fs)))  ; duplicates removed (1 appears twice)
      (is (= [1 2 3 4 5 6 9] (vec (seq fs))))
      (is (= [9 6 5 4 3 2 1] (vec (rseq fs))))
      (is (= 1 (first fs)))
      (is (= 9 (last fs)))))

  (testing "conj and disj"
    (let [fs (oc/fuzzy-set [1 5 10])]
      (is (= 4 (count (conj fs 7))))
      (is (= [1 5 7 10] (vec (seq (conj fs 7)))))
      (is (= 2 (count (disj fs 5))))
      (is (= [1 10] (vec (seq (disj fs 5)))))))

  (testing "reduce"
    (let [fs (oc/fuzzy-set [1 2 3 4 5])]
      (is (= 15 (reduce + fs)))
      (is (= 15 (reduce + 0 fs))))))

(deftest fuzzy-set-fold-test
  (testing "Parallel fold"
    (let [fs (oc/fuzzy-set (range 1000))]
      (is (= (reduce + (range 1000))
             (r/fold + fs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Map Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fuzzy-map-basic-test
  (testing "Basic fuzzy map operations"
    (let [fm (oc/fuzzy-map {0 :zero 10 :ten 100 :hundred})]
      ;; Exact matches
      (is (= :zero (fm 0)))
      (is (= :ten (fm 10)))
      (is (= :hundred (fm 100)))

      ;; Closest matches
      (is (= :zero (fm -5)))    ; -5 is closest to 0
      (is (= :zero (fm 4)))     ; 4 is closest to 0
      (is (= :ten (fm 6)))      ; 6 is closest to 10
      (is (= :ten (fm 50)))     ; 50 is closer to 10 than 100
      (is (= :hundred (fm 56))) ; 56 is closer to 100 than 10
      (is (= :hundred (fm 200))))))

(deftest fuzzy-map-tiebreak-test
  (testing "Tiebreaker with :< (prefer smaller key)"
    (let [fm (oc/fuzzy-map {0 :a 10 :b 20 :c} :tiebreak :<)]
      (is (= :a (fm 5)))    ; 5 is equidistant from 0 and 10
      (is (= :b (fm 15))))) ; 15 is equidistant from 10 and 20

  (testing "Tiebreaker with :> (prefer larger key)"
    (let [fm (oc/fuzzy-map {0 :a 10 :b 20 :c} :tiebreak :>)]
      (is (= :b (fm 5)))    ; 5 is equidistant from 0 and 10
      (is (= :c (fm 15))))) ; 15 is equidistant from 10 and 20
  )

(deftest fuzzy-map-edge-cases-test
  (testing "Empty fuzzy map"
    (let [fm (oc/fuzzy-map {})]
      (is (nil? (fm 5)))
      (is (= :not-found (fm 5 :not-found)))))

  (testing "Single entry fuzzy map"
    (let [fm (oc/fuzzy-map {50 :middle})]
      (is (= :middle (fm 0)))
      (is (= :middle (fm 100)))
      (is (= :middle (fm 50))))))

(deftest fuzzy-map-exact-ops-test
  (testing "exact-contains? for precise key membership"
    (let [fm (oc/fuzzy-map {1 :a 5 :b 10 :c})]
      (is (oc/fuzzy-exact-contains? fm 5))
      (is (not (oc/fuzzy-exact-contains? fm 6)))))

  (testing "exact-get for non-fuzzy lookup"
    (let [fm (oc/fuzzy-map {1 :a 5 :b 10 :c})]
      (is (= :b (oc/fuzzy-exact-get fm 5)))
      (is (nil? (oc/fuzzy-exact-get fm 6)))
      (is (= :default (oc/fuzzy-exact-get fm 6 :default))))))

(deftest fuzzy-map-nearest-test
  (testing "fuzzy-nearest returns key, value, and distance"
    (let [fm (oc/fuzzy-map {0 :a 10 :b 20 :c})]
      (is (= [10 :b 0.0] (oc/fuzzy-nearest fm 10)))  ; exact match
      (is (= [10 :b 3.0] (oc/fuzzy-nearest fm 7)))   ; closest with distance
      (is (= [0 :a 5.0] (oc/fuzzy-nearest fm -5))))))

(deftest fuzzy-map-collection-ops-test
  (testing "Standard map operations"
    (let [fm (oc/fuzzy-map {3 :c 1 :a 4 :d 2 :b})]
      (is (= 4 (count fm)))
      (is (= [[1 :a] [2 :b] [3 :c] [4 :d]] (vec (seq fm))))
      (is (= [[4 :d] [3 :c] [2 :b] [1 :a]] (vec (rseq fm))))
      (is (= 1 (.firstKey ^java.util.SortedMap fm)))
      (is (= 4 (.lastKey ^java.util.SortedMap fm)))))

  (testing "assoc and dissoc"
    (let [fm (oc/fuzzy-map {1 :a 5 :b 10 :c})]
      (is (= 4 (count (assoc fm 7 :d))))
      (is (= :d ((assoc fm 7 :d) 7)))  ; exact lookup of new key
      (is (= 2 (count (dissoc fm 5))))
      (is (= [[1 :a] [10 :c]] (vec (seq (dissoc fm 5)))))))

  (testing "reduce"
    (let [fm (oc/fuzzy-map {1 10 2 20 3 30})]
      (is (= 60 (reduce (fn [acc [k v]] (+ acc v)) 0 fm))))))

(deftest fuzzy-map-fold-test
  (testing "Parallel fold"
    (let [fm (oc/fuzzy-map (zipmap (range 1000) (range 1000)))]
      (is (= (reduce + (range 1000))
             (r/fold + (fn [acc [k v]] (+ acc v)) fm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom Distance Function Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest custom-distance-test
  ;; Note: The fuzzy algorithm finds floor/ceiling neighbors in sort order,
  ;; then compares by distance. This works correctly when the distance
  ;; function correlates with the sort order (i.e., closest by distance
  ;; is always a sort-order neighbor).

  (testing "Fuzzy map with linear distance - standard case"
    ;; Standard numeric distance works with default comparator
    (let [fm (oc/fuzzy-map {0 :zero 3 :three 6 :six 9 :nine})]
      ;; 1 is closest to 0 (distance 1)
      (is (= :zero (fm 1)))
      ;; 4 is closest to 3 (distance 1)
      (is (= :three (fm 4)))
      ;; 7 is closest to 6 (distance 1)
      (is (= :six (fm 7)))
      ;; 7.5 is closest to 6 (distance 1.5 vs 1.5 to 9, tiebreak :< prefers smaller)
      (is (= :six (fm 7.5))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Floating Point Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest floating-point-test
  (testing "Fuzzy set with floating point values"
    (let [fs (oc/fuzzy-set [0.0 0.5 1.0 1.5 2.0])]
      (is (= 0.5 (fs 0.4)))
      (is (= 1.0 (fs 0.9)))
      (is (= 1.5 (fs 1.4)))))

  (testing "Fuzzy map with floating point keys"
    (let [fm (oc/fuzzy-map {0.0 :a 1.0 :b 2.0 :c})]
      (is (= :a (fm 0.3)))
      (is (= :b (fm 0.7)))
      (is (= :b (fm 1.4)))
      (is (= :c (fm 1.6))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Set: Navigation, Split, Rank
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fuzzy-set-nearest-protocol-test
  (testing "PNearest on fuzzy-set (ordered-set style navigation)"
    (let [fs (oc/fuzzy-set [1 3 5 7 9])]
      (is (= 5 (oc/nearest fs :< 6)))
      (is (= 3 (oc/nearest fs :< 5)))
      (is (= 5 (oc/nearest fs :<= 5)))
      (is (= 7 (oc/nearest fs :> 6)))
      (is (= 7 (oc/nearest fs :> 5)))
      (is (= 5 (oc/nearest fs :>= 5)))
      (is (nil? (oc/nearest fs :< 1)))
      (is (nil? (oc/nearest fs :> 9))))))

(deftest fuzzy-set-subrange-test
  (testing "subrange on fuzzy-set"
    (let [fs (oc/fuzzy-set (range 10))]
      (is (= #{3 4 5 6} (set (seq (oc/subrange fs :>= 3 :< 7)))))
      (is (= #{0 1 2 3 4} (set (seq (oc/subrange fs :<= 4)))))
      (is (= #{7 8 9} (set (seq (oc/subrange fs :> 6))))))))

(deftest fuzzy-set-split-test
  (testing "split-key on fuzzy-set"
    (let [fs (oc/fuzzy-set [1 2 3 4 5])]
      (let [[left entry right] (oc/split-key 3 fs)]
        (is (= #{1 2} (set (seq left))))
        (is (= 3 entry))
        (is (= #{4 5} (set (seq right)))))
      (let [[left entry right] (oc/split-key 2.5 fs)]
        (is (= #{1 2} (set (seq left))))
        (is (nil? entry))
        (is (= #{3 4 5} (set (seq right)))))))
  (testing "split-at on fuzzy-set"
    (let [fs (oc/fuzzy-set [1 2 3 4 5])]
      (let [[left right] (oc/split-at 2 fs)]
        (is (= #{1 2} (set (seq left))))
        (is (= #{3 4 5} (set (seq right))))))))

(deftest fuzzy-set-navigable-test
  (testing "ceiling and floor via NavigableSet"
    (let [^java.util.NavigableSet fs (oc/fuzzy-set [1 3 5 7 9])]
      (is (= 5 (.ceiling fs 5)))
      (is (= 5 (.ceiling fs 4)))
      (is (= 5 (.floor fs 5)))
      (is (= 3 (.floor fs 4)))
      (is (= 1 (.ceiling fs 0)))
      (is (nil? (.floor fs 0))))))

(deftest fuzzy-set-rank-test
  (testing "rank/slice/median/percentile"
    (let [fs (oc/fuzzy-set [10 20 30 40 50])]
      (is (= 0 (oc/rank fs 10)))
      (is (= 2 (oc/rank fs 30)))
      (is (= 4 (oc/rank fs 50)))
      (is (nil? (oc/rank fs 25)))
      (is (= (list 20 30 40) (oc/slice fs 1 4)))
      (is (= 30 (oc/median fs)))
      (is (= 50 (oc/percentile fs 90))))))

(deftest fuzzy-set-subseq-test
  (testing "subseq/rsubseq via Sorted interface"
    (let [fs (oc/fuzzy-set [1 3 5 7 9])]
      (is (= [5 7 9] (subseq fs >= 5)))
      (is (= [5 7 9] (subseq fs > 4)))
      (is (= [1 3 5] (subseq fs <= 5)))
      (is (= [5 3 1] (rsubseq fs <= 5))))))

(deftest fuzzy-set-comparable-test
  (testing "compareTo"
    (let [fs1 (oc/fuzzy-set [1 2 3])
          fs2 (oc/fuzzy-set [1 2 3])
          fs3 (oc/fuzzy-set [1 2 4])]
      (is (zero? (compare fs1 fs2)))
      (is (neg? (compare fs1 fs3))))))

(deftest fuzzy-set-larger-data-test
  (testing "fuzzy set at 10K elements"
    (let [elems (vec (shuffle (range 10000)))
          fs    (oc/fuzzy-set elems)]
      (is (= 10000 (count fs)))
      (is (= 0 (first fs)))
      (is (= 9999 (last fs)))
      ;; fuzzy lookup snaps to nearest
      (is (= 5000 (fs 5000)))
      (is (= 5000 (fs 5000.3)))
      ;; reduce
      (is (= (reduce + (range 10000)) (reduce + fs)))
      ;; fold
      (is (= (reduce + (range 10000)) (r/fold + fs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Map: Navigation, Rank, Larger Data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fuzzy-map-rank-test
  (testing "rank/slice/median/percentile on fuzzy-map"
    (let [fm (oc/fuzzy-map {10 :a 20 :b 30 :c 40 :d 50 :e})]
      (is (= 0 (oc/rank fm 10)))
      (is (= 2 (oc/rank fm 30)))
      (is (nil? (oc/rank fm 25)))
      (is (= [[20 :b] [30 :c] [40 :d]] (oc/slice fm 1 4)))
      (is (= [30 :c] (oc/median fm))))))

(deftest fuzzy-map-subseq-test
  (testing "subseq/rsubseq via Sorted interface"
    (let [fm (oc/fuzzy-map {1 :a 3 :b 5 :c 7 :d 9 :e})]
      (is (= [[5 :c] [7 :d] [9 :e]] (subseq fm >= 5)))
      (is (= [[5 :c] [3 :b] [1 :a]] (rsubseq fm <= 5))))))

(deftest fuzzy-map-larger-data-test
  (testing "fuzzy map at 10K entries"
    (let [pairs (zipmap (range 10000) (range 10000))
          fm    (oc/fuzzy-map pairs)]
      (is (= 10000 (count fm)))
      (is (= 0 (.firstKey ^java.util.SortedMap fm)))
      (is (= 9999 (.lastKey ^java.util.SortedMap fm)))
      ;; fuzzy lookup
      (is (= 5000 (fm 5000)))
      (is (= 5000 (fm 5000.3)))
      ;; reduce-kv
      (is (= (reduce + (range 10000))
             (reduce-kv (fn [acc _ v] (+ acc v)) 0 fm))))))

(deftest fuzzy-set-string-keys-test
  (testing "fuzzy set with string elements"
    (let [fs (oc/fuzzy-set ["apple" "banana" "cherry" "date" "elderberry"]
               :distance (fn [a b] (Math/abs (- (count (str a)) (count (str b))))))]
      (is (= 5 (count fs)))
      (is (= "apple" (first fs)))
      ;; exact membership
      (is (oc/fuzzy-exact-contains? fs "cherry"))
      (is (not (oc/fuzzy-exact-contains? fs "coconut"))))))

(deftest fuzzy-map-string-keys-test
  (testing "fuzzy map with string keys"
    (let [fm (oc/fuzzy-map {"alpha" 1 "beta" 2 "gamma" 3 "delta" 4}
               :distance (fn [a b] (Math/abs (- (count (str a)) (count (str b))))))]
      (is (= 4 (count fm)))
      (is (oc/fuzzy-exact-contains? fm "beta"))
      (is (not (oc/fuzzy-exact-contains? fm "epsilon"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-fuzzy-set-nearest-bruteforce 100
  (prop/for-all [xs tu/gen-non-empty-int-set
                 q  gen/small-integer
                 tiebreak (gen/elements [:< :>])]
    (let [fs (oc/fuzzy-set xs :tiebreak tiebreak)
          expected (tu/fuzzy-nearest-ref (set xs) q tiebreak)]
      (= expected (fs q)))))

(defspec prop-fuzzy-map-nearest-bruteforce 100
  (prop/for-all [entries tu/gen-non-empty-int-map-entries
                 q       gen/small-integer
                 tiebreak (gen/elements [:< :>])]
    (let [m (into {} entries)
          fm (oc/fuzzy-map m :tiebreak tiebreak)
          [expected-k expected-v] (tu/fuzzy-nearest-map-ref m q tiebreak)]
      (= expected-v (fm q)))))

(defspec prop-fuzzy-map-nearest-triplet-bruteforce 100
  (prop/for-all [entries tu/gen-non-empty-int-map-entries
                 q       gen/small-integer
                 tiebreak (gen/elements [:< :>])]
    (let [m (into {} entries)
          fm (oc/fuzzy-map m :tiebreak tiebreak)
          [expected-k expected-v] (tu/fuzzy-nearest-map-ref m q tiebreak)
          expected-dist (double (Math/abs ^long (- (long q) (long expected-k))))]
      (= [expected-k expected-v expected-dist]
         (oc/fuzzy-nearest fm q)))))
