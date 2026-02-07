(ns com.dean.ordered-collections.range-map-test
  "Rigorous tests for RangeMap - non-overlapping range mappings with half-open intervals."
  (:require [clojure.test :refer [deftest testing is]]
            [com.dean.ordered-collections.core :as oc]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reference implementation for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ref-lookup
  "Reference implementation: linear scan through ranges."
  [ranges x]
  (some (fn [[[lo hi] v]]
          (when (and (<= lo x) (< x hi))
            v))
        ranges))

(defn- ref-range-map
  "Build reference: sorted list of non-overlapping ranges."
  [range-pairs]
  (sort-by (comp first first) range-pairs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest construction-empty
  (let [rm (oc/range-map)]
    (is (zero? (count rm)))
    (is (nil? (seq rm)))
    (is (nil? (rm 0)))
    (is (nil? (oc/spanning-range rm)))))

(deftest construction-various-sizes
  (doseq [n [1 2 10 50 100 500 1000]]
    (testing (str "N=" n " non-overlapping ranges")
      (let [;; Create n non-overlapping ranges of width 5, spaced by 10
            ranges (for [i (range n)]
                     [[(* i 10) (+ (* i 10) 5)] (keyword (str "r" i))])
            rm     (oc/range-map ranges)]
        (is (= n (count rm)))
        ;; Check all ranges present
        (doseq [[[lo hi] v] ranges]
          (is (= v (rm lo)) (str "Failed at lo=" lo))
          (is (= v (rm (dec hi))) (str "Failed at hi-1=" (dec hi))))))))

(deftest construction-from-map
  (doseq [n [10 50 100]]
    (let [m  (into {} (for [i (range n)] [[(* i 10) (+ (* i 10) 5)] i]))
          rm (oc/range-map m)]
      (is (= n (count rm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Half-open interval semantics [lo, hi)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest half-open-semantics
  (doseq [n [10 100 1000]]
    (testing (str "N=" n " ranges")
      (let [ranges (for [i (range n)]
                     [[(* i 10) (+ (* i 10) 5)] i])
            rm     (oc/range-map ranges)]
        ;; Check boundary behavior for each range
        (doseq [i (range n)]
          (let [lo (* i 10)
                hi (+ lo 5)]
            ;; lo is included
            (is (= i (rm lo)) (str "lo=" lo " should be in range"))
            ;; hi-1 is included
            (is (= i (rm (dec hi))) (str "hi-1=" (dec hi) " should be in range"))
            ;; hi is excluded
            (is (nil? (rm hi)) (str "hi=" hi " should be outside range"))
            ;; Just before lo is excluded (except for first range)
            (when (pos? lo)
              (is (nil? (rm (dec lo))) (str "lo-1=" (dec lo) " should be outside")))))))))

(deftest adjacent-ranges
  (doseq [n [10 100 500]]
    (testing (str "N=" n " adjacent ranges")
      (let [;; Ranges [0,10), [10,20), [20,30), ...
            ranges (for [i (range n)]
                     [[(* i 10) (* (inc i) 10)] i])
            rm     (oc/range-map ranges)]
        (is (= n (count rm)))
        ;; Each boundary point belongs to exactly one range
        (doseq [i (range n)]
          (let [boundary (* (inc i) 10)]
            (when (< i (dec n))
              (is (= (inc i) (rm boundary)) (str "Boundary " boundary)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Random point lookups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest random-lookups-vs-reference
  (doseq [n [10 50 100 500]]
    (testing (str "N=" n " ranges, 1000 random lookups")
      (let [;; Random non-overlapping ranges
            ranges (for [i (range n)]
                     [[(* i 10) (+ (* i 10) (+ 1 (rand-int 9)))]
                      (keyword (str "v" i))])
            rm     (oc/range-map ranges)
            ref    (ref-range-map ranges)]
        ;; Random lookups
        (dotimes [_ 1000]
          (let [x (rand-int (* n 10))]
            (is (= (ref-lookup ref x) (rm x))
                (str "Mismatch at x=" x))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Assoc without overlap
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest assoc-disjoint-ranges
  (doseq [n [10 50 100 500]]
    (testing (str "Building N=" n " disjoint ranges via assoc")
      (let [rm (reduce
                 (fn [m i]
                   (assoc m [(* i 20) (+ (* i 20) 10)] i))
                 (oc/range-map)
                 (shuffle (range n)))]
        (is (= n (count rm)))
        ;; All values accessible
        (doseq [i (range n)]
          (is (= i (rm (+ (* i 20) 5)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Assoc with overlap - splitting behavior
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest assoc-complete-override
  (doseq [n [10 50 100]]
    (testing (str "Complete override, N=" n)
      (let [;; Start with ranges [0,10), [20,30), [40,50), ...
            rm (oc/range-map (for [i (range n)]
                               [[(* i 20) (+ (* i 20) 10)] :old]))
            ;; Override with one huge range
            rm' (assoc rm [0 (* n 20)] :new)]
        (is (= 1 (count rm')))
        ;; Everything maps to :new
        (doseq [x (take 100 (repeatedly #(rand-int (* n 20))))]
          (is (= :new (rm' x))))))))

(deftest assoc-partial-overlap-left
  (let [rm  (oc/range-map {[100 200] :a})
        rm' (assoc rm [50 150] :b)]
    ;; Should have [50,150):b, [150,200):a
    (is (= 2 (count rm')))
    (is (= :b (rm' 75)))
    (is (= :b (rm' 100)))
    (is (= :b (rm' 149)))
    (is (= :a (rm' 150)))
    (is (= :a (rm' 175)))))

(deftest assoc-partial-overlap-right
  (let [rm  (oc/range-map {[100 200] :a})
        rm' (assoc rm [150 250] :b)]
    ;; Should have [100,150):a, [150,250):b
    (is (= 2 (count rm')))
    (is (= :a (rm' 100)))
    (is (= :a (rm' 125)))
    (is (= :b (rm' 150)))
    (is (= :b (rm' 200)))
    (is (= :b (rm' 249)))))

(deftest assoc-split-in-middle
  (doseq [outer-size [100 500 1000]]
    (testing (str "Splitting [0," outer-size ") in middle")
      (let [rm    (oc/range-map {[0 outer-size] :outer})
            lo    (quot outer-size 4)
            hi    (* 3 (quot outer-size 4))
            rm'   (assoc rm [lo hi] :inner)]
        ;; Should have 3 ranges: [0,lo), [lo,hi), [hi,outer-size)
        (is (= 3 (count rm')))
        (is (= :outer (rm' 0)))
        (is (= :outer (rm' (dec lo))))
        (is (= :inner (rm' lo)))
        (is (= :inner (rm' (dec hi))))
        (is (= :outer (rm' hi)))
        (is (= :outer (rm' (dec outer-size))))))))

(deftest assoc-spanning-multiple-ranges
  (doseq [n [5 10 20 50]]
    (testing (str "Spanning " n " ranges")
      (let [;; Ranges [0,10), [20,30), [40,50), ...
            ranges (for [i (range n)]
                     [[(* i 20) (+ (* i 20) 10)] (keyword (str "r" i))])
            rm     (oc/range-map ranges)
            ;; Insert range that spans middle portion
            lo     15
            hi     (- (* n 20) 15)
            rm'    (assoc rm [lo hi] :spanning)]
        ;; First range should be untouched (ends at 10, before lo=15)
        (is (= :r0 (rm' 5)))
        ;; Spanning range covers the middle
        (is (= :spanning (rm' lo)))
        (is (= :spanning (rm' (dec hi))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invalid range handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest invalid-range-throws
  (let [rm (oc/range-map)]
    (is (thrown? Exception (assoc rm [10 10] :bad)))
    (is (thrown? Exception (assoc rm [20 10] :bad)))
    (is (thrown? Exception (assoc rm [100 50] :bad)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ranges and spanning-range functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ranges-function
  (doseq [n [1 10 50 100]]
    (testing (str "N=" n " ranges")
      (let [ranges (for [i (range n)]
                     [[(* i 10) (+ (* i 10) 5)] i])
            rm     (oc/range-map ranges)
            result (oc/ranges rm)]
        (is (= n (count result)))
        ;; Ranges are sorted by lower bound
        (is (= (sort-by (comp first first) ranges)
               result))))))

(deftest spanning-range-function
  (doseq [n [1 10 50 100]]
    (testing (str "N=" n " ranges")
      (let [rm     (oc/range-map (for [i (range n)]
                                   [[(* i 10) (+ (* i 10) 5)] i]))
            [lo hi] (oc/spanning-range rm)]
        (is (= 0 lo))
        (is (= (+ (* (dec n) 10) 5) hi))))))

(deftest spanning-range-with-gaps
  (let [rm (oc/range-map {[100 200] :a [500 600] :b [300 400] :c})]
    (is (= [100 600] (oc/spanning-range rm)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collection operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest collection-operations
  (doseq [n [10 50 100]]
    (let [rm (oc/range-map (for [i (range n)]
                             [[(* i 10) (+ (* i 10) 5)] i]))]
      (testing "count"
        (is (= n (count rm))))

      (testing "seq returns sorted range-value pairs"
        (let [pairs (seq rm)]
          (is (= n (count pairs)))
          (is (= (range n) (map second pairs)))))

      (testing "empty"
        (let [e (empty rm)]
          (is (zero? (count e)))))

      (testing "cons/conj"
        (let [rm' (conj rm [[1000 2000] :extra])]
          (is (= (inc n) (count rm'))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Randomized stress tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest randomized-construction-and-lookup
  (dotimes [_ 50]
    (let [n       (+ 5 (rand-int 100))
          ;; Generate random non-overlapping ranges
          bounds  (sort (distinct (repeatedly (* n 3) #(rand-int 10000))))
          pairs   (map vector (partition 2 1 bounds) (range))
          ranges  (take n pairs)
          rm      (oc/range-map ranges)
          ref     (ref-range-map ranges)]
      (testing (str "Random construction, n=" (count rm))
        ;; Random lookups
        (dotimes [_ 500]
          (let [x (rand-int 10000)]
            (is (= (ref-lookup ref x) (rm x))
                (str "Mismatch at x=" x))))))))

(deftest randomized-incremental-construction
  (dotimes [_ 20]
    (let [n (+ 10 (rand-int 50))]
      (testing (str "Incremental construction, n=" n)
        (let [;; Build incrementally in random order
              final-rm (reduce
                         (fn [rm i]
                           (let [lo (* i 20)
                                 hi (+ lo 10)]
                             (assoc rm [lo hi] i)))
                         (oc/range-map)
                         (shuffle (range n)))]
          (is (= n (count final-rm)))
          ;; All values accessible
          (doseq [i (range n)]
            (is (= i (final-rm (+ (* i 20) 5))))))))))

(deftest randomized-overlap-resolution
  (dotimes [_ 30]
    (let [;; Start with a base range
          base-hi (+ 100 (rand-int 900))
          rm0     (oc/range-map {[0 base-hi] :base})
          ;; Insert random overlapping range
          lo      (rand-int (quot base-hi 2))
          hi      (+ lo 10 (rand-int (- base-hi lo 10)))
          rm1     (assoc rm0 [lo hi] :overlay)]
      (testing (str "Overlap [" lo "," hi ") within [0," base-hi ")")
        ;; Points in overlay range should return :overlay
        (dotimes [_ 50]
          (let [x (+ lo (rand-int (- hi lo)))]
            (is (= :overlay (rm1 x)) (str "x=" x " should be :overlay"))))
        ;; Points outside overlay but inside base should return :base
        (when (pos? lo)
          (dotimes [_ 20]
            (let [x (rand-int lo)]
              (is (= :base (rm1 x)) (str "x=" x " should be :base")))))
        (when (< hi base-hi)
          (dotimes [_ 20]
            (let [x (+ hi (rand-int (- base-hi hi)))]
              (is (= :base (rm1 x)) (str "x=" x " should be :base")))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property-based tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest property-no-overlaps-after-construction
  (dotimes [_ 50]
    (let [n      (+ 5 (rand-int 50))
          ranges (for [i (range n)]
                   [[(* i 20) (+ (* i 20) 10 (rand-int 5))] i])
          rm     (oc/range-map ranges)
          result (oc/ranges rm)]
      (testing "No overlapping ranges in result"
        ;; Check consecutive pairs - each range's hi should be <= next range's lo
        (doseq [[[[_ h1] _] [[l2 _] _]] (partition 2 1 result)]
          (is (<= h1 l2)
              (str "Overlap: range ending at " h1 " overlaps range starting at " l2)))))))

(deftest property-lookup-consistency
  (dotimes [_ 30]
    (let [n  (+ 10 (rand-int 100))
          rm (oc/range-map (for [i (range n)]
                             [[(* i 10) (+ (* i 10) 5)] i]))]
      (testing "Lookup is consistent with ranges"
        (doseq [[[lo hi] v] (oc/ranges rm)]
          ;; All points in [lo, hi) should return v
          (doseq [x (range lo hi)]
            (is (= v (rm x)) (str "x=" x " in [" lo "," hi ")"))))))))
