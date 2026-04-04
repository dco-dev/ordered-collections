(ns ordered-collections.coverage-test
  "Additional tests to improve code coverage."
  (:refer-clojure :exclude [split-at])
  (:require [clojure.core.reducers :as r]
            [clojure.test :refer :all]
            [ordered-collections.core :refer :all])
  (:import [java.util Collection Set SortedSet]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OrderedSet Coverage Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-set-java-collection-interface
  (let [os (ordered-set [3 1 4 1 5 9 2 6])]
    ;; isEmpty
    (is (false? (.isEmpty ^Collection os)))
    (is (true? (.isEmpty ^Collection (ordered-set))))

    ;; size
    (is (= 7 (.size ^Collection os)))

    ;; toArray
    (is (= [1 2 3 4 5 6 9] (vec (.toArray ^Collection os))))

    ;; iterator
    (let [iter (.iterator ^Collection os)]
      (is (= 1 (.next iter))))

    ;; contains
    (is (true? (.contains ^Collection os 5)))
    (is (false? (.contains ^Collection os 100)))

    ;; containsAll
    (is (true? (.containsAll ^Set os [1 2 3])))
    (is (false? (.containsAll ^Set os [1 2 100])))

    ;; Unsupported mutating operations
    (is (thrown? UnsupportedOperationException (.add ^Collection os 10)))
    (is (thrown? UnsupportedOperationException (.addAll ^Collection os [10 11])))
    (is (thrown? UnsupportedOperationException (.removeAll ^Collection os [1 2])))
    (is (thrown? UnsupportedOperationException (.retainAll ^Collection os [1 2])))))

(deftest ordered-set-java-sorted-set-interface
  (let [os (ordered-set [3 1 4 1 5 9 2 6])]
    ;; comparator
    (is (some? (.comparator ^SortedSet os)))

    ;; first
    (is (= 1 (.first ^SortedSet os)))

    ;; last
    (is (= 9 (.last ^SortedSet os)))

    ;; headSet - elements < x
    (is (= #{1 2 3} (.headSet ^SortedSet os 4)))

    ;; tailSet - elements >= x
    (is (= #{4 5 6 9} (.tailSet ^SortedSet os 4)))

    ;; subSet - elements >= from and < to
    (is (= #{3 4 5} (.subSet ^SortedSet os 3 6)))))

(deftest ordered-set-clojure-sorted-interface
  (let [os (ordered-set [3 1 4 1 5 9 2 6])]
    ;; subseq >= 3
    (is (= [3 4 5 6 9] (subseq os >= 3)))

    ;; subseq > 3
    (is (= [4 5 6 9] (subseq os > 3)))

    ;; subseq >= 3 < 6
    (is (= [3 4 5] (subseq os >= 3 < 6)))

    ;; rsubseq <= 5
    (is (= [5 4 3 2 1] (rsubseq os <= 5)))))

(deftest ordered-set-meta-and-equiv
  (let [os1 (ordered-set [1 2 3])
        os2 (with-meta os1 {:foo :bar})]
    ;; meta (empty map by default)
    (is (= {} (meta os1)))
    (is (= {:foo :bar} (meta os2)))

    ;; equiv
    (is (= os1 os2))
    (is (= os1 #{1 2 3}))))

(deftest ordered-set-reduce
  (let [os (ordered-set [1 2 3 4 5])]
    ;; IReduce
    (is (= 15 (reduce + os)))
    ;; IReduceInit
    (is (= 115 (reduce + 100 os)))
    ;; empty
    (is (= 0 (reduce + (ordered-set))))))

(deftest ordered-set-empty-edge-cases
  (let [os (ordered-set)]
    (is (= os (empty (ordered-set [1 2 3]))))
    (is (= 0 (count os)))
    ;; seq/rseq on empty returns empty list
    (is (empty? (seq os)))
    (is (empty? (rseq os)))))

(deftest ordered-set-reverse-comparator
  (let [os (ordered-set-by > [3 1 4 1 5 9 2 6])]
    (is (= [9 6 5 4 3 2 1] (seq os)))
    (is (= 9 (first os)))
    (is (= 1 (last os)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OrderedMap Coverage Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-map-basic-interface
  (let [om (ordered-map [[3 :c] [1 :a] [4 :d] [1 :A] [5 :e]])]
    ;; count
    (is (= 4 (count om)))
    (is (= 0 (count (ordered-map))))

    ;; containsKey
    (is (contains? om 3))
    (is (not (contains? om 100)))))

(deftest ordered-map-clojure-sorted-interface
  (let [om (ordered-map [[3 :c] [1 :a] [4 :d] [5 :e]])]
    ;; subseq
    (is (= [[3 :c] [4 :d] [5 :e]] (subseq om >= 3)))
    (is (= [[4 :d] [5 :e]] (subseq om > 3)))

    ;; rsubseq
    (is (= [[4 :d] [3 :c] [1 :a]] (rsubseq om <= 4)))))

(deftest ordered-map-meta-and-equiv
  (let [om1 (ordered-map [[1 :a] [2 :b]])
        om2 (with-meta om1 {:foo :bar})]
    ;; meta (empty map by default)
    (is (= {} (meta om1)))
    (is (= {:foo :bar} (meta om2)))

    ;; equiv
    (is (= om1 om2))
    (is (= om1 {1 :a 2 :b}))))

(deftest ordered-map-reduce
  (let [om (ordered-map [[1 :a] [2 :b] [3 :c]])]
    ;; IReduceInit
    (is (= 6 (reduce (fn [acc [k _]] (+ acc k)) 0 om)))
    ;; empty
    (is (= 100 (reduce (fn [acc [k _]] (+ acc k)) 100 (ordered-map))))))

(deftest ordered-map-entry-at
  (let [om (ordered-map [[1 :a] [2 :b] [3 :c]])]
    (let [entry (.entryAt ^clojure.lang.Associative om 2)]
      (is (= 2 (key entry)))
      (is (= :b (val entry))))
    (is (nil? (.entryAt ^clojure.lang.Associative om 100)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IntervalSet Coverage Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest interval-set-basic-coverage
  (let [iset (interval-set [[1 5] [10 15] [20 25]])]
    ;; Basic operations
    (is (= 3 (count iset)))

    ;; Interval queries - returns matching intervals or nil
    (is (= [[1 5]] (iset 3)))
    (is (= [[10 15]] (iset 12)))
    (is (nil? (iset 7)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IntervalMap Coverage Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest interval-map-basic-coverage
  (let [im (interval-map {[1 5] :a [10 15] :b [20 25] :c})]
    ;; Basic operations
    (is (= 3 (count im)))

    ;; Interval queries - returns values or empty vec or nil
    (is (= [:a] (im 3)))
    (is (= [:b] (im 12)))
    ;; No matching interval returns empty vec or nil depending on implementation
    (is (or (= [] (im 7)) (nil? (im 7))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FuzzySet Coverage Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fuzzy-set-basic-coverage
  (let [fs (fuzzy-set [1 5 10 20 50])]
    ;; Exact matches
    (is (= 1 (fs 1)))
    (is (= 10 (fs 10)))

    ;; Fuzzy matches
    (is (= 5 (fs 7)))
    (is (= 10 (fs 13)))))

(deftest fuzzy-set-tiebreak
  (let [fs-lo (fuzzy-set [0 10 20] :tiebreak :<)
        fs-hi (fuzzy-set [0 10 20] :tiebreak :>)]
    ;; At equidistant point
    (is (= 0 (fs-lo 5)))
    (is (= 10 (fs-hi 5)))))

(deftest fuzzy-set-empty
  (let [fs (fuzzy-set [])]
    (is (nil? (fs 5)))))

(deftest fuzzy-set-reduce
  (let [fs (fuzzy-set [1 2 3 4 5])]
    (is (= 15 (reduce + fs)))
    (is (= 115 (reduce + 100 fs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FuzzyMap Coverage Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fuzzy-map-basic-coverage
  (let [fm (fuzzy-map {0 :zero 10 :ten 100 :hundred})]
    ;; Exact matches
    (is (= :zero (fm 0)))
    (is (= :ten (fm 10)))

    ;; Fuzzy matches
    (is (= :ten (fm 7)))
    (is (= :ten (fm 50)))
    (is (= :hundred (fm 60)))))

(deftest fuzzy-map-exact-get
  (let [fm (fuzzy-map {0 :zero 10 :ten 100 :hundred})]
    (is (= :ten (fuzzy-exact-get fm 10)))
    (is (nil? (fuzzy-exact-get fm 11)))
    (is (= :nope (fuzzy-exact-get fm 11 :nope)))))

(deftest fuzzy-map-empty
  (let [fm (fuzzy-map {})]
    (is (nil? (fm 5)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PriorityQueue Coverage Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest priority-queue-basic-coverage
  (let [pq (priority-queue [[5 :e] [3 :c] [8 :h] [1 :a] [9 :i] [2 :b] [7 :g]])]
    (is (= [1 :a] (peek pq)))
    (is (= [2 :b] (peek (pop pq))))
    (is (= 7 (count pq)))))

(deftest priority-queue-push
  (let [pq (priority-queue [[5 :e] [3 :c] [8 :h]])]
    ;; push adds value with given priority
    (let [pq2 (push pq 0 :zero)]
      (is (= [0 :zero] (peek pq2)))
      (is (= 4 (count pq2))))))

(deftest priority-queue-empty
  (let [pq (priority-queue [])]
    (is (nil? (peek pq)))
    (is (thrown? IllegalStateException (pop pq)))
    (is (= 0 (count pq)))))

(deftest priority-queue-reduce
  (let [pq (priority-queue [[1 10] [2 20] [3 30] [4 40] [5 50]])]
    ;; reduce over [priority value] pairs
    (is (= 15 (reduce (fn [acc [p _]] (+ acc p)) 0 pq)))
    (is (= 150 (reduce (fn [acc [_ v]] (+ acc v)) 0 pq)))))

(deftest priority-queue-fold
  (let [pairs (vec (for [i (range 1000)] [i i]))
        pq (priority-queue pairs)]
    (is (= (reduce + (range 1000))
           (r/fold + (fn [acc [p _]] (+ acc p)) pq)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OrderedMultiset Coverage Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-multiset-basic-coverage
  (let [ms (ordered-multiset [3 1 4 1 5 9 2 6 5 3 5])]
    (is (= 11 (count ms)))
    (is (= 3 (multiplicity ms 5)))
    (is (= 2 (multiplicity ms 1)))
    (is (= 0 (multiplicity ms 100)))
    (is (= [1 1 2 3 3 4 5 5 5 6 9] (seq ms)))))

(deftest ordered-multiset-disj-one
  (let [ms (ordered-multiset [1 1 1 2 2 3])]
    (is (= [1 1 2 2 3] (seq (disj-one ms 1))))
    (is (= [1 1 1 2 3] (seq (disj-one ms 2))))
    (is (= [1 1 1 2 2 3] (seq (disj-one ms 100))))))

(deftest ordered-multiset-disj-all
  (let [ms (ordered-multiset [1 1 1 2 2 3])]
    (is (= [2 2 3] (seq (disj-all ms 1))))
    (is (= [1 1 1 3] (seq (disj-all ms 2))))))

(deftest ordered-multiset-empty
  (let [ms (ordered-multiset [])]
    (is (= 0 (count ms)))
    (is (nil? (seq ms)))))

(deftest ordered-multiset-reduce
  (let [ms (ordered-multiset [1 2 2 3 3 3])]
    (is (= 14 (reduce + ms)))
    (is (= 114 (reduce + 100 ms)))))

(deftest ordered-multiset-fold
  (let [ms (ordered-multiset (range 1000))]
    (is (= (reduce + (range 1000)) (r/fold + ms)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core namespace coverage
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest core-constructors-coverage
  (is (some? (ordered-set)))
  (is (some? (ordered-set-by < [])))
  (is (some? (ordered-map)))
  (is (some? (ordered-map-by < [])))
  (is (some? (interval-set)))
  (is (some? (interval-map)))
  (is (some? (priority-queue [])))
  (is (some? (priority-queue [] :comparator >)))
  (is (some? (ordered-multiset [])))
  (is (some? (ordered-multiset-by < [])))
  (is (some? (fuzzy-set [])))
  (is (some? (fuzzy-set-by < [])))
  (is (some? (fuzzy-map {})))
  (is (some? (fuzzy-map-by < {}))))

(deftest core-protocol-functions
  (let [os (ordered-set [1 2 3])]
    (is (= #{1 2} (intersection os (ordered-set [1 2 4]))))
    (is (= #{1 2 3 4} (union os (ordered-set [2 3 4]))))
    (is (= #{3} (difference os (ordered-set [1 2]))))
    (is (subset? os (ordered-set [1 2 3 4 5])))
    (is (superset? (ordered-set [1 2 3 4 5]) os))))
