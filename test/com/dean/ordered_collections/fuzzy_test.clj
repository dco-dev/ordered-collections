(ns com.dean.ordered-collections.fuzzy-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.core.reducers :as r]
            [com.dean.ordered-collections.core :as oc]))

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

  (testing "Fuzzy set with string length - sorted by length"
    ;; When using a custom distance, sort by the same criterion
    ;; fuzzy-set-by takes a predicate (like <), not a comparator
    (let [len-distance (fn [a b] (Math/abs (- (count (str a)) (count (str b)))))
          ;; Predicate: a < b by length, tie-break alphabetically
          len-less? (fn [a b]
                      (let [len-a (count (str a))
                            len-b (count (str b))]
                        (or (< len-a len-b)
                            (and (= len-a len-b) (neg? (compare (str a) (str b)))))))
          fs (oc/fuzzy-set-by len-less?
                              ["a" "bb" "ccc" "dddd" "eeeee"]
                              :distance len-distance)]
      ;; "xx" has length 2, closest to "bb" (both length 2)
      (is (= "bb" (fs "xx")))
      ;; "xxxx" has length 4, closest to "dddd" (both length 4)
      (is (= "dddd" (fs "xxxx")))))

  (testing "Fuzzy map with linear distance - standard case"
    ;; Standard numeric distance works with default comparator
    (let [fm (oc/fuzzy-map {0 :zero 3 :three 6 :six 9 :nine})]
      ;; 1 is closest to 0 (distance 1)
      (is (= :zero (fm 1)))
      ;; 4 is closest to 3 (distance 1)
      (is (= :three (fm 4)))
      ;; 7 is closest to 6 (distance 1)
      (is (= :six (fm 7)))
      ;; 8 is equidistant from 6 and 9, tiebreak :< prefers smaller
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
