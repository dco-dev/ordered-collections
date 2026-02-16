(ns com.dean.ordered-collections.range-map-test
  "Rigorous tests for RangeMap - non-overlapping range mappings with half-open intervals."
  (:require [clojure.test :refer [deftest testing is]]
            [com.dean.ordered-collections.core :as oc]))


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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Large Map Tests
;;
;; Verify RangeMap scales correctly with large numbers of ranges. Tests
;; construction and lookup performance with 1000, 5000, and 10000 non-overlapping
;; ranges. Also tests incremental construction (building via repeated assoc in
;; random order) and heavy overlap scenarios where many small ranges are inserted
;; into one large base range, creating complex fragmentation patterns.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest large-map-construction
  (testing "1000 ranges"
    (let [rm (oc/range-map (for [i (range 1000)]
                             [[(* i 100) (+ (* i 100) 50)] i]))]
      (is (= 1000 (count rm)))
      ;; Spot check
      (is (= 0 (rm 25)))
      (is (= 500 (rm 50025)))
      (is (= 999 (rm 99925)))))

  (testing "5000 ranges"
    (let [rm (oc/range-map (for [i (range 5000)]
                             [[(* i 20) (+ (* i 20) 10)] i]))]
      (is (= 5000 (count rm)))
      ;; Random lookups
      (dotimes [_ 1000]
        (let [i (rand-int 5000)
              x (+ (* i 20) (rand-int 10))]
          (is (= i (rm x)))))))

  (testing "10000 ranges"
    (let [rm (oc/range-map (for [i (range 10000)]
                             [[(* i 10) (+ (* i 10) 5)] i]))]
      (is (= 10000 (count rm)))
      ;; Verify spanning range
      (is (= [0 99995] (oc/spanning-range rm))))))

(deftest large-map-incremental-construction
  (testing "Build 2000 ranges incrementally in random order"
    (let [indices (shuffle (range 2000))
          rm (reduce
               (fn [m i]
                 (assoc m [(* i 50) (+ (* i 50) 25)] i))
               (oc/range-map)
               indices)]
      (is (= 2000 (count rm)))
      ;; All values accessible
      (doseq [i (range 2000)]
        (is (= i (rm (+ (* i 50) 12))))))))

(deftest large-map-heavy-overlap
  (testing "Insert 1000 overlapping ranges"
    (let [;; Start with one big range
          rm0 (oc/range-map {[0 100000] :base})
          ;; Insert 1000 small overlapping ranges
          rm (reduce
               (fn [m i]
                 (let [lo (* i 100)
                       hi (+ lo 50)]
                   (assoc m [lo hi] i)))
               rm0
               (range 1000))]
      ;; Should have many fragments
      (is (> (count rm) 1000))
      ;; Check some overlaid values
      (is (= 0 (rm 25)))
      (is (= 500 (rm 50025)))
      ;; Check base values in gaps
      (is (= :base (rm 75)))     ; gap between [0,50) and [100,150)
      (is (= :base (rm 175))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge Cases
;;
;; Exercise boundary conditions and unusual range configurations:
;; - Single-point ranges (width 1, e.g. [100,101))
;; - Very wide ranges spanning millions of units
;; - Negative range values and ranges spanning negative to positive
;; - Floating-point boundaries with precise boundary behavior
;; - Exact boundary touching (adjacent vs overlapping)
;; - Minimal overlap (single unit)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest edge-case-single-point-range
  (testing "Range of width 1"
    (let [rm (oc/range-map {[100 101] :point})]
      (is (= 1 (count rm)))
      (is (nil? (rm 99)))
      (is (= :point (rm 100)))
      (is (nil? (rm 101))))))

(deftest edge-case-very-wide-range
  (testing "Range spanning millions"
    (let [rm (oc/range-map {[0 10000000] :wide})]
      (is (= 1 (count rm)))
      (is (= :wide (rm 0)))
      (is (= :wide (rm 5000000)))
      (is (= :wide (rm 9999999)))
      (is (nil? (rm 10000000))))))

(deftest edge-case-negative-ranges
  (testing "Negative range values"
    (let [rm (oc/range-map {[-100 -50] :neg1
                            [-25 25] :span
                            [50 100] :pos})]
      (is (= 3 (count rm)))
      (is (= :neg1 (rm -75)))
      (is (= :span (rm 0)))
      (is (= :pos (rm 75)))
      (is (nil? (rm -30)))  ; gap
      (is (nil? (rm 30))))) ; gap

  (testing "Negative to positive spanning"
    (let [rm (oc/range-map {[-1000 1000] :all})]
      (is (= :all (rm -500)))
      (is (= :all (rm 0)))
      (is (= :all (rm 500))))))

(deftest edge-case-floating-point-ranges
  (testing "Floating point boundaries"
    (let [rm (oc/range-map {[0.0 1.5] :a
                            [2.5 3.5] :b
                            [4.0 5.0] :c})]
      (is (= 3 (count rm)))
      (is (= :a (rm 0.0)))
      (is (= :a (rm 0.75)))
      (is (= :a (rm 1.499)))
      (is (nil? (rm 1.5)))
      (is (nil? (rm 2.0)))
      (is (= :b (rm 2.5)))
      (is (= :b (rm 3.0)))))

  (testing "Floating point overlap"
    (let [rm0 (oc/range-map {[0.0 10.0] :base})
          rm1 (assoc rm0 [2.5 7.5] :inner)]
      (is (= 3 (count rm1)))
      (is (= :base (rm1 1.0)))
      (is (= :inner (rm1 5.0)))
      (is (= :base (rm1 8.0))))))

(deftest edge-case-boundary-precision
  (testing "Adjacent ranges at exact boundaries"
    (let [rm (oc/range-map {[0 100] :a
                            [100 200] :b
                            [200 300] :c})]
      (is (= :a (rm 99)))
      (is (= :b (rm 100)))
      (is (= :b (rm 199)))
      (is (= :c (rm 200)))
      (is (= :c (rm 299)))
      (is (nil? (rm 300))))))

(deftest edge-case-minimal-overlap
  (testing "Overlap by single unit"
    (let [rm0 (oc/range-map {[0 100] :a})
          rm1 (assoc rm0 [99 150] :b)]
      ;; [0,99) :a, [99,150) :b
      (is (= 2 (count rm1)))
      (is (= :a (rm1 98)))
      (is (= :b (rm1 99)))
      (is (= :b (rm1 100)))))

  (testing "Exact boundary touch (no overlap)"
    (let [rm0 (oc/range-map {[0 100] :a})
          rm1 (assoc rm0 [100 200] :b)]
      ;; Should have both ranges intact
      (is (= 2 (count rm1)))
      (is (= :a (rm1 99)))
      (is (= :b (rm1 100))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multiple Successive Operations
;;
;; Test sequences of operations that build on each other:
;; - Repeated binary splitting (subdividing ranges recursively)
;; - Cascading overlaps (wave patterns where each insert splits multiple ranges)
;; - Consolidation (overlaying fragmented ranges to merge them)
;; - Build-up then tear-down (construct many ranges, then consolidate to few)
;; These tests verify the range-map maintains consistency through complex
;; sequences of mutations.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest successive-split-operations
  (testing "Repeated binary splitting"
    (let [;; Start with [0, 1024)
          rm0 (oc/range-map {[0 1024] 0})
          ;; Split into [0,512), [512,1024)
          rm1 (assoc rm0 [512 1024] 1)
          ;; Split [0,512) into [0,256), [256,512)
          rm2 (assoc rm1 [256 512] 2)
          ;; Split [512,1024) into [512,768), [768,1024)
          rm3 (assoc rm2 [768 1024] 3)
          ;; Continue splitting
          rm4 (assoc rm3 [128 256] 4)
          rm5 (assoc rm4 [640 768] 5)]
      (is (= 6 (count rm5)))
      (is (= 0 (rm5 64)))    ; [0, 128)
      (is (= 4 (rm5 192)))   ; [128, 256)
      (is (= 2 (rm5 384)))   ; [256, 512)
      (is (= 1 (rm5 576)))   ; [512, 640)
      (is (= 5 (rm5 704)))   ; [640, 768)
      (is (= 3 (rm5 896))))) ; [768, 1024)

  (testing "Cascading overlaps - wave pattern"
    (let [;; Start with alternating ranges
          rm0 (oc/range-map (for [i (range 10)]
                              [[(* i 100) (+ (* i 100) 50)] i]))
          ;; Wave: each new range overlaps two old ones
          rm1 (reduce
                (fn [m i]
                  (assoc m [(+ (* i 100) 25) (+ (* i 100) 125)] (+ i 100)))
                rm0
                (range 9))]
      ;; Each wave splits existing ranges
      (is (> (count rm1) 10))
      ;; Verify structure by sampling
      (is (= 0 (rm1 10)))      ; untouched start of first range
      (is (= 100 (rm1 50)))    ; first wave covers this
      (is (= 100 (rm1 100)))   ; first wave continues
      )))

(deftest successive-merge-operations
  (testing "Build fragmented then consolidate"
    (let [;; Create 20 small ranges with gaps
          rm0 (oc/range-map (for [i (range 20)]
                              [[(* i 50) (+ (* i 50) 25)] i]))
          ;; Now overlay one big range
          rm1 (assoc rm0 [0 1000] :all)]
      (is (= 1 (count rm1)))
      (is (= :all (rm1 500)))))

  (testing "Incremental consolidation"
    (let [;; Start with 10 separate ranges
          rm0 (oc/range-map (for [i (range 10)]
                              [[(* i 20) (+ (* i 20) 10)] i]))
          ;; Gradually fill gaps
          rm1 (assoc rm0 [10 20] :gap1)   ; fill first gap
          rm2 (assoc rm1 [30 40] :gap2)   ; fill second gap
          rm3 (assoc rm2 [50 60] :gap3)]  ; fill third gap
      (is (= 13 (count rm3)))
      (is (= :gap1 (rm3 15)))
      (is (= :gap2 (rm3 35)))
      (is (= :gap3 (rm3 55))))))

(deftest build-up-tear-down
  (testing "Build 100 ranges then overlay to reduce"
    (let [;; Build up
          rm0 (oc/range-map (for [i (range 100)]
                              [[(* i 10) (+ (* i 10) 5)] i]))
          _ (is (= 100 (count rm0)))
          ;; Overlay to reduce - cover first half
          rm1 (assoc rm0 [0 500] :first-half)
          _ (is (< (count rm1) 100))
          ;; Overlay second half
          rm2 (assoc rm1 [500 1000] :second-half)
          _ (is (= 2 (count rm2)))
          ;; Finally, one big range
          rm3 (assoc rm2 [0 1000] :all)]
      (is (= 1 (count rm3)))
      (is (= :all (rm3 500))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Gaps Function Tests
;;
;; Verify the `gaps` function correctly identifies unmapped regions between
;; mapped ranges. Tests simple gaps between ranges, adjacent ranges (no gaps),
;; single-range maps, empty maps, and complex scenarios with many small gaps
;; or variable gap sizes.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest gaps-function-basic
  (testing "Simple gaps"
    (let [rm (oc/range-map {[0 10] :a [20 30] :b [40 50] :c})
          gs (oc/gaps rm)]
      (is (= [[10 20] [30 40]] gs))))

  (testing "No gaps (adjacent ranges)"
    (let [rm (oc/range-map {[0 10] :a [10 20] :b [20 30] :c})
          gs (oc/gaps rm)]
      (is (empty? gs))))

  (testing "Single range has no gaps"
    (let [rm (oc/range-map {[0 100] :only})
          gs (oc/gaps rm)]
      (is (empty? gs))))

  (testing "Empty map has no gaps"
    (is (nil? (oc/gaps (oc/range-map))))))

(deftest gaps-function-complex
  (testing "Many small gaps"
    (let [;; Ranges of width 5 with gaps of 5
          rm (oc/range-map (for [i (range 100)]
                             [[(* i 10) (+ (* i 10) 5)] i]))
          gs (oc/gaps rm)]
      (is (= 99 (count gs)))
      ;; Each gap should be [i*10+5, (i+1)*10)
      (doseq [[i [lo hi]] (map-indexed vector gs)]
        (is (= (+ (* i 10) 5) lo))
        (is (= (* (inc i) 10) hi)))))

  (testing "Variable gap sizes"
    (let [rm (oc/range-map {[0 10] :a
                            [100 110] :b
                            [200 210] :c
                            [205 215] :d})  ; overlaps with :c
          gs (oc/gaps rm)]
      ;; After overlap resolution, gaps should be consistent
      (is (every? (fn [[lo hi]] (< lo hi)) gs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Use Case Scenarios
;;
;; Real-world use cases demonstrating RangeMap applicability:
;; - IP address block allocation (private network ranges, subnet allocation)
;; - Time slot scheduling (non-overlapping calendar bookings with gaps)
;; - Memory region management (allocation, fragmentation, defragmentation)
;; - Version range resolution (semantic versioning with deprecation markers)
;; - Coverage-then-fragment (systematic subdivision of complete coverage)
;; Each scenario tests multiple operations in a coherent domain context.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest scenario-ip-address-ranges
  (testing "IP address block allocation simulation"
    (let [;; Simulate IP blocks as integers
          ;; 10.0.0.0/8 = 167772160 to 184549375
          block-10 [167772160 184549376]
          ;; 192.168.0.0/16 = 3232235520 to 3232301055
          block-192 [3232235520 3232301056]
          ;; 172.16.0.0/12 = 2886729728 to 2887778303
          block-172 [2886729728 2887778304]

          rm0 (oc/range-map {block-10 :private-a
                             block-172 :private-b
                             block-192 :private-c})

          ;; Allocate a subnet within 10.x.x.x
          ;; 10.1.0.0/16 = 167837696 to 167903231
          subnet-10-1 [167837696 167903232]
          rm1 (assoc rm0 subnet-10-1 :allocated-subnet)]

      ;; Should have: [10.0.0.0 to 10.1.0.0), [10.1.0.0 to 10.1.255.255), [10.2.0.0 to end), plus 172 and 192 blocks
      (is (= 5 (count rm1)))
      (is (= :private-a (rm1 167772160)))  ; start of 10.0.0.0
      (is (= :allocated-subnet (rm1 167837700)))  ; in 10.1.x.x
      (is (= :private-a (rm1 167903300)))  ; after 10.1.x.x but still in 10.x
      (is (= :private-b (rm1 2886729730)))
      (is (= :private-c (rm1 3232235525))))))

(deftest scenario-time-slot-scheduling
  (testing "Non-overlapping time slot booking"
    (let [;; Times as minutes from midnight
          rm0 (oc/range-map)
          ;; Book meeting 9:00-10:00 (540-600)
          rm1 (assoc rm0 [540 600] {:event "standup"})
          ;; Book 10:30-12:00 (630-720)
          rm2 (assoc rm1 [630 720] {:event "planning"})
          ;; Book 14:00-15:30 (840-930)
          rm3 (assoc rm2 [840 930] {:event "review"})
          ;; Try to book 9:30-10:15 - should split standup, extends past it
          rm4 (assoc rm3 [570 615] {:event "urgent"})]

      ;; standup [540-570), urgent [570-615), planning [630-720), review [840-930)
      (is (= 4 (count rm4)))
      (is (= {:event "standup"} (rm4 545)))
      (is (= {:event "urgent"} (rm4 580)))
      (is (= {:event "urgent"} (rm4 610)))
      (is (= {:event "planning"} (rm4 650)))

      ;; Verify gaps show free time
      (let [gs (oc/gaps rm4)]
        (is (some #(= [615 630] %) gs))   ; gap between urgent and planning
        (is (some #(= [720 840] %) gs)))))) ; gap between planning and review

(deftest scenario-memory-regions
  (testing "Memory allocation simulation"
    (let [;; Start with entire address space free
          rm0 (oc/range-map {[0 65536] :free})
          ;; Allocate kernel: 0-4096
          rm1 (assoc rm0 [0 4096] :kernel)
          ;; Allocate heap: 8192-32768
          rm2 (assoc rm1 [8192 32768] :heap)
          ;; Allocate stack: 61440-65536
          rm3 (assoc rm2 [61440 65536] :stack)]

      (is (= 5 (count rm3)))  ; kernel, free, heap, free, stack
      (is (= :kernel (rm3 1000)))
      (is (= :free (rm3 5000)))
      (is (= :heap (rm3 20000)))
      (is (= :free (rm3 40000)))
      (is (= :stack (rm3 63000)))

      ;; Allocate more from free regions
      (let [rm4 (assoc rm3 [4096 6144] :bss)
            rm5 (assoc rm4 [50000 60000] :shared)]
        ;; kernel, bss, free [6144-8192), heap, free [32768-50000), shared, free [60000-61440), stack
        (is (= 8 (count rm5)))
        (is (= :bss (rm5 5000)))
        (is (= :shared (rm5 55000))))))

  (testing "Fragmentation and defragmentation"
    (let [;; Create fragmented memory
          rm0 (oc/range-map (for [i (range 64)]
                              [[(* i 1024) (+ (* i 1024) 512)]
                               (if (even? i) :used :free)]))
          ;; Count used vs free
          used-count (count (filter #(= :used (second %)) (oc/ranges rm0)))
          free-count (count (filter #(= :free (second %)) (oc/ranges rm0)))
          _ (is (= 32 used-count))
          _ (is (= 32 free-count))

          ;; "Defragment" by consolidating all used to beginning
          rm1 (assoc rm0 [0 32768] :used)
          rm2 (assoc rm1 [32768 65536] :free)]
      (is (= 2 (count rm2))))))

(deftest scenario-version-ranges
  (testing "Dependency version resolution"
    (let [;; Version numbers as integers (major * 10000 + minor * 100 + patch)
          ;; e.g., 2.3.4 = 20304
          rm0 (oc/range-map {[10000 20000] :v1  ; 1.x.x
                             [20000 30000] :v2  ; 2.x.x
                             [30000 40000] :v3}) ; 3.x.x

          ;; Mark 2.5.0+ as deprecated
          rm1 (assoc rm0 [20500 30000] :deprecated)

          ;; Mark 1.9.x as security-patched
          rm2 (assoc rm1 [10900 11000] :security-patch)]

      ;; v1 [10000-10900), security-patch [10900-11000), v1 [11000-20000), v2 [20000-20500), deprecated [20500-30000), v3
      (is (= 6 (count rm2)))
      (is (= :v1 (rm2 10500)))           ; 1.5.0
      (is (= :security-patch (rm2 10950))) ; 1.9.50
      (is (= :v2 (rm2 20300)))           ; 2.3.0
      (is (= :deprecated (rm2 20800)))   ; 2.8.0
      (is (= :v3 (rm2 30500))))))        ; 3.5.0

(deftest scenario-coverage-then-fragment
  (testing "Full coverage then systematic fragmentation"
    (let [;; Start with complete coverage
          rm0 (oc/range-map {[0 10000] :base})
          _ (is (= 1 (count rm0)))

          ;; Fragment by inserting every 100th range
          rm1 (reduce
                (fn [m i]
                  (assoc m [(* i 100) (+ (* i 100) 50)] i))
                rm0
                (range 100))
          ;; 100 new ranges + 100 :base fragments between them (including one at end)
          _ (is (= 200 (count rm1)))

          ;; Fragment further
          rm2 (reduce
                (fn [m i]
                  (assoc m [(+ (* i 100) 25) (+ (* i 100) 75)] (+ i 1000)))
                rm1
                (range 99))]
      (is (> (count rm2) 200))
      ;; Verify some samples
      (is (= 0 (rm2 10)))        ; start of first inserted range
      (is (= 1000 (rm2 50)))     ; first sub-fragment
      (is (= :base (rm2 85))))))  ; gap filled by base

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stress Tests
;;
;; Push RangeMap to its limits with adversarial patterns:
;; - 1000 random insert/lookup operations with varying range sizes
;; - Worst-case patterns: all ranges overlapping at single point, reverse-order
;;   insertion (worst for some tree structures)
;; - 5000 single-unit ranges (minimal width)
;; - Deep nesting (matryoshka pattern: each range contains the next)
;; - Alternating overlap patterns
;; These tests verify structural integrity and correct behavior under stress.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest stress-random-operations
  (testing "1000 random insert/lookup operations"
    (let [rm (atom (oc/range-map))
          ref (atom {})  ; reference as sorted map of ranges
          ops (for [i (range 1000)]
                (let [lo (rand-int 50000)
                      width (+ 1 (rand-int 500))
                      hi (+ lo width)]
                  {:lo lo :hi hi :val i}))]
      ;; Apply operations
      (doseq [{:keys [lo hi val]} ops]
        (swap! rm assoc [lo hi] val))

      ;; Verify no overlaps
      (let [ranges (oc/ranges @rm)]
        (doseq [[[[_ h1] _] [[l2 _] _]] (partition 2 1 ranges)]
          (is (<= h1 l2) "No overlaps allowed")))

      ;; Random lookups should return values from inserted ranges
      (dotimes [_ 500]
        (let [x (rand-int 50000)
              result (@rm x)]
          ;; Result should be either nil (in gap) or a value from our ops
          (when result
            (is (integer? result))))))))

(deftest stress-worst-case-patterns
  (testing "All ranges overlap at same point"
    (let [;; Every range includes point 500, each one completely contains the next
          ;; Range 0: [499, 501), Range 1: [498, 502), ..., Range 99: [400, 600)
          rm (oc/range-map (for [i (range 100)]
                             [[(- 500 i 1) (+ 501 i)] i]))]
      ;; Last insertion (i=99) covers [400, 600) and overwrites everything
      (is (= 99 (rm 500)))
      ;; Due to complete overlap, only 1 range survives
      (is (= 1 (count rm)))))

  (testing "Reverse order insertion (worst for some tree structures)"
    (let [rm (reduce
               (fn [m i]
                 (assoc m [(- 10000 (* i 10) 10) (- 10000 (* i 10))] i))
               (oc/range-map)
               (range 1000))]
      (is (= 1000 (count rm)))
      ;; Verify ordering
      (let [ranges (oc/ranges rm)]
        (is (apply < (map (comp first first) ranges)))))))

(deftest stress-tiny-ranges
  (testing "Many single-unit ranges"
    (let [rm (oc/range-map (for [i (range 5000)]
                             [[i (inc i)] i]))]
      (is (= 5000 (count rm)))
      (doseq [i (range 5000)]
        (is (= i (rm i))))))

  (testing "Interleaved tiny and large ranges"
    (let [;; Tiny ranges at even positions
          tiny (for [i (range 0 1000 2)]
                 [[i (inc i)] :tiny])
          ;; Large ranges that span odd gaps
          rm0 (oc/range-map tiny)
          rm1 (reduce
                (fn [m i]
                  (assoc m [(dec i) (+ i 2)] :large))
                rm0
                (range 1 1000 2))]
      ;; Large ranges should dominate
      (doseq [i (range 1 999 2)]
        (is (= :large (rm1 i)))))))

(deftest stress-deep-nesting
  (testing "Deeply nested ranges (matryoshka pattern)"
    (let [;; Each range contains the next
          rm (reduce
               (fn [m i]
                 (assoc m [i (- 1000 i)] i))
               (oc/range-map)
               (range 500))]
      ;; Innermost range wins
      (is (= 499 (rm 500)))
      ;; Outermost layer at boundaries
      (is (= 0 (rm 0)))
      (is (= 0 (rm 999)))))

  (testing "Alternating overlap pattern"
    (let [;; Odd ranges overlap with neighbors
          rm (reduce
               (fn [m i]
                 (if (even? i)
                   (assoc m [(* i 10) (+ (* i 10) 10)] i)
                   (assoc m [(- (* i 10) 5) (+ (* i 10) 15)] i)))
               (oc/range-map)
               (range 100))]
      ;; Verify structure is valid
      (let [ranges (oc/ranges rm)]
        (doseq [[[[_ h1] _] [[l2 _] _]] (partition 2 1 ranges)]
          (is (<= h1 l2))))
      ;; Odd numbers should dominate due to overlap
      (is (= 1 (rm 10))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coalescing Tests
;;
;; Verify that adjacent ranges with the same value are automatically merged:
;; - Basic left/right coalescing when inserting adjacent ranges
;; - Three-way coalescing when a range bridges two same-value neighbors
;; - Coalescing during overlap resolution (trimmed portions merge with new range)
;; - Non-coalescing when values differ
;; - Coalescing with various value types (keywords, maps, vectors)
;; - Coalescing preserves correct boundaries after complex operations
;; - Stress tests with many coalescing operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest coalesce-basic-right
  (testing "New range coalesces with existing range on right"
    (let [rm0 (oc/range-map {[100 200] :a})
          rm1 (oc/assoc-coalescing rm0 [50 100] :a)]
      (is (= 1 (count rm1)))
      (is (= [[50 200]] (map first (oc/ranges rm1))))
      (is (= :a (rm1 75)))
      (is (= :a (rm1 150))))))

(deftest coalesce-basic-left
  (testing "New range coalesces with existing range on left"
    (let [rm0 (oc/range-map {[0 100] :a})
          rm1 (oc/assoc-coalescing rm0 [100 200] :a)]
      (is (= 1 (count rm1)))
      (is (= [[0 200]] (map first (oc/ranges rm1))))
      (is (= :a (rm1 50)))
      (is (= :a (rm1 150))))))

(deftest coalesce-three-way
  (testing "New range bridges two same-value ranges, coalesces all three"
    (let [rm0 (oc/range-map {[0 100] :a [200 300] :a})
          rm1 (oc/assoc-coalescing rm0 [100 200] :a)]
      (is (= 1 (count rm1)))
      (is (= [[0 300]] (map first (oc/ranges rm1))))
      (is (= :a (rm1 50)))
      (is (= :a (rm1 150)))
      (is (= :a (rm1 250))))))

(deftest coalesce-no-merge-different-values
  (testing "Adjacent ranges with different values do NOT coalesce"
    (let [rm0 (oc/range-map {[0 100] :a})
          rm1 (oc/assoc-coalescing rm0 [100 200] :b)]
      (is (= 2 (count rm1)))
      (is (= :a (rm1 50)))
      (is (= :b (rm1 150)))))

  (testing "Three adjacent ranges with different values stay separate"
    (let [rm (oc/range-map {[0 100] :a [100 200] :b [200 300] :c})]
      (is (= 3 (count rm))))))

(deftest coalesce-only-matching-side
  (testing "Coalesce left but not right (different right value)"
    ;; Start: [0, 100) :a, gap, [200, 300) :b
    ;; Insert: [100, 200) :a (fills gap)
    ;; [100, 200) :a is adjacent to [0, 100) :a → coalesce to [0, 200) :a
    ;; [100, 200) :a is adjacent to [200, 300) :b → no coalesce (different values)
    ;; Result: [0, 200) :a, [200, 300) :b
    (let [rm0 (oc/range-map {[0 100] :a [200 300] :b})
          rm1 (oc/assoc-coalescing rm0 [100 200] :a)]
      (is (= 2 (count rm1)))
      (is (= [[0 200] [200 300]] (map first (oc/ranges rm1))))
      (is (= :a (rm1 150)))
      (is (= :b (rm1 250)))))

  (testing "Coalesce right but not left (different left value)"
    ;; Start: [0, 100) :a, gap, [200, 300) :b
    ;; Insert: [100, 200) :b (fills gap)
    ;; [100, 200) :b is adjacent to [0, 100) :a → no coalesce (different values)
    ;; [100, 200) :b is adjacent to [200, 300) :b → coalesce to [100, 300) :b
    ;; Result: [0, 100) :a, [100, 300) :b
    (let [rm0 (oc/range-map {[0 100] :a [200 300] :b})
          rm1 (oc/assoc-coalescing rm0 [100 200] :b)]
      (is (= 2 (count rm1)))
      (is (= [[0 100] [100 300]] (map first (oc/ranges rm1))))
      (is (= :a (rm1 50)))
      (is (= :b (rm1 150))))))

(deftest coalesce-with-overlap-resolution
  (testing "Overlap creates trimmed portion that coalesces with new range"
    ;; Start: [0, 100) :a, [100, 200) :b
    ;; Insert: [50, 150) :a
    ;; Step 1: Remove overlapping portions, add back trimmed:
    ;;         [0, 50) :a (trimmed left of [0,100))
    ;;         [150, 200) :b (trimmed right of [100,200))
    ;; Step 2: Add [50, 150) :a
    ;; Step 3: Coalesce [0, 50) :a with [50, 150) :a → [0, 150) :a
    ;; Result: [0, 150) :a, [150, 200) :b
    (let [rm0 (oc/range-map {[0 100] :a [100 200] :b})
          rm1 (oc/assoc-coalescing rm0 [50 150] :a)]
      (is (= 2 (count rm1)))
      (is (= :a (rm1 25)))   ; in [0, 50) originally, now part of [0, 150)
      (is (= :a (rm1 75)))   ; in overlap [50, 100), now part of [0, 150)
      (is (= :a (rm1 125)))  ; in new [50, 150), now part of [0, 150)
      (is (= :b (rm1 175)))))  ; in trimmed [150, 200)

  (testing "Split creates two fragments that both coalesce"
    ;; Start: [0, 300) :a
    ;; Insert: [100, 200) :a (same value)
    ;; Result: [0, 300) :a (everything merges back together)
    (let [rm0 (oc/range-map {[0 300] :a})
          rm1 (oc/assoc-coalescing rm0 [100 200] :a)]
      (is (= 1 (count rm1)))
      (is (= [[0 300]] (map first (oc/ranges rm1)))))))

(deftest coalesce-chain-building
  (testing "Build chain of coalescing ranges left to right"
    (let [rm (reduce
               (fn [m i]
                 (oc/assoc-coalescing m [(* i 100) (* (inc i) 100)] :chain))
               (oc/range-map)
               (range 10))]
      (is (= 1 (count rm)))
      (is (= [[0 1000]] (map first (oc/ranges rm))))))

  (testing "Build chain of coalescing ranges right to left"
    (let [rm (reduce
               (fn [m i]
                 (oc/assoc-coalescing m [(* i 100) (* (inc i) 100)] :chain))
               (oc/range-map)
               (reverse (range 10)))]
      (is (= 1 (count rm)))
      (is (= [[0 1000]] (map first (oc/ranges rm))))))

  (testing "Build chain in random order"
    (let [rm (reduce
               (fn [m i]
                 (oc/assoc-coalescing m [(* i 100) (* (inc i) 100)] :chain))
               (oc/range-map)
               (shuffle (range 10)))]
      (is (= 1 (count rm)))
      (is (= [[0 1000]] (map first (oc/ranges rm)))))))

(deftest coalesce-with-various-value-types
  (testing "Coalesce with keyword values"
    (let [rm (-> (oc/range-map)
                 (oc/assoc-coalescing [0 100] :same)
                 (oc/assoc-coalescing [100 200] :same))]
      (is (= 1 (count rm)))))

  (testing "Coalesce with map values"
    (let [v {:type :config :id 42}
          rm (-> (oc/range-map)
                 (oc/assoc-coalescing [0 100] v)
                 (oc/assoc-coalescing [100 200] v))]
      (is (= 1 (count rm)))
      (is (= v (rm 50)))
      (is (= v (rm 150)))))

  (testing "Coalesce with vector values"
    (let [v [1 2 3]
          rm (-> (oc/range-map)
                 (oc/assoc-coalescing [0 100] v)
                 (oc/assoc-coalescing [100 200] v))]
      (is (= 1 (count rm)))))

  (testing "Coalesce with integer values"
    (let [rm (-> (oc/range-map)
                 (oc/assoc-coalescing [0 100] 42)
                 (oc/assoc-coalescing [100 200] 42))]
      (is (= 1 (count rm)))))

  (testing "Equal but not identical maps still coalesce"
    ;; Equal maps SHOULD coalesce (= returns true)
    (let [rm (-> (oc/range-map)
                 (oc/assoc-coalescing [0 100] {:a 1})
                 (oc/assoc-coalescing [100 200] {:a 1}))]
      (is (= 1 (count rm))))))

(deftest coalesce-interleaved-values
  (testing "Alternating values don't coalesce"
    (let [rm (reduce
               (fn [m i]
                 (oc/assoc-coalescing m [(* i 100) (* (inc i) 100)]
                        (if (even? i) :even :odd)))
               (oc/range-map)
               (range 10))]
      (is (= 10 (count rm)))))

  (testing "Same values at both ends, different in middle"
    ;; [0,100):a [100,200):b [200,300):a - should stay as 3 ranges
    (let [rm (oc/range-map {[0 100] :a [100 200] :b [200 300] :a})]
      (is (= 3 (count rm))))

    ;; Now insert [100,200):a - should coalesce all three
    (let [rm0 (oc/range-map {[0 100] :a [100 200] :b [200 300] :a})
          rm1 (oc/assoc-coalescing rm0 [100 200] :a)]
      (is (= 1 (count rm1)))
      (is (= [[0 300]] (map first (oc/ranges rm1)))))))

(deftest coalesce-stress-random
  (testing "Random coalescing operations maintain invariants"
    (dotimes [_ 20]
      (let [;; Create ranges with only 3 possible values to encourage coalescing
            values [:a :b :c]
            ops (for [i (range 100)]
                  {:lo (* i 10)
                   :hi (+ (* i 10) 10)
                   :val (rand-nth values)})
            rm (reduce
                 (fn [m {:keys [lo hi val]}]
                   (oc/assoc-coalescing m [lo hi] val))
                 (oc/range-map)
                 ops)]
        ;; Verify no overlaps
        (let [ranges (oc/ranges rm)]
          (doseq [[[[_ h1] _] [[l2 _] _]] (partition 2 1 ranges)]
            (is (<= h1 l2))))
        ;; Verify no adjacent same-value ranges (coalescing worked)
        (let [ranges (oc/ranges rm)]
          (doseq [[[_ v1] [_ v2]] (partition 2 1 ranges)]
            (is (not= v1 v2) "Adjacent ranges should have different values")))))))

(deftest coalesce-stress-many-same-value
  (testing "1000 adjacent ranges with same value coalesce to one"
    (let [rm (reduce
               (fn [m i]
                 (oc/assoc-coalescing m [(* i 10) (* (inc i) 10)] :unified))
               (oc/range-map)
               (shuffle (range 1000)))]
      (is (= 1 (count rm)))
      (is (= [[0 10000]] (map first (oc/ranges rm))))))

  (testing "Insert in worst-case order (reverse) still coalesces"
    (let [rm (reduce
               (fn [m i]
                 (oc/assoc-coalescing m [(* i 10) (* (inc i) 10)] :unified))
               (oc/range-map)
               (reverse (range 500)))]
      (is (= 1 (count rm))))))

(deftest coalesce-with-gaps
  (testing "Coalescing doesn't bridge gaps"
    ;; [0,100):a gap [200,300):a - should stay as 2 ranges
    (let [rm (oc/range-map {[0 100] :a [200 300] :a})]
      (is (= 2 (count rm))))

    ;; Fill the gap with same value - now coalesces
    (let [rm0 (oc/range-map {[0 100] :a [200 300] :a})
          rm1 (oc/assoc-coalescing rm0 [100 200] :a)]
      (is (= 1 (count rm1)))))

  (testing "Coalescing doesn't bridge gaps with different values"
    (let [rm0 (oc/range-map {[0 100] :a [200 300] :a})
          rm1 (oc/assoc-coalescing rm0 [100 200] :b)]
      (is (= 3 (count rm1))))))

(deftest coalesce-ip-address-scenario
  (testing "IP allocation coalescing (from zorp-example)"
    (let [;; Helper: IP to int (simplified)
          ip (fn [a b c d] (+ (* a 16777216) (* b 65536) (* c 256) d))

          ;; Start with kevin-iot block
          rm0 (oc/range-map {[(ip 10 10 4 0) (ip 10 10 8 0)] :kevin-iot})

          ;; Add adjacent block with same owner - should coalesce
          rm1 (oc/assoc-coalescing rm0 [(ip 10 10 8 0) (ip 10 10 12 0)] :kevin-iot)]

      (is (= 1 (count rm1)))
      (let [[[lo hi] v] (first (oc/ranges rm1))]
        (is (= (ip 10 10 4 0) lo))
        (is (= (ip 10 10 12 0) hi))
        (is (= :kevin-iot v))))))

(deftest coalesce-time-slot-scenario
  (testing "Time slot coalescing"
    (let [;; Book consecutive hours with same event
          rm (-> (oc/range-map)
                 (oc/assoc-coalescing [540 600] {:event "workshop"})   ; 9:00-10:00
                 (oc/assoc-coalescing [600 660] {:event "workshop"})   ; 10:00-11:00
                 (oc/assoc-coalescing [660 720] {:event "workshop"}))] ; 11:00-12:00
      ;; Should coalesce into one 3-hour block
      (is (= 1 (count rm)))
      (is (= [[540 720]] (map first (oc/ranges rm)))))))

(deftest coalesce-preserves-correct-value
  (testing "Coalesced range has correct value"
    (let [rm (-> (oc/range-map)
                 (oc/assoc-coalescing [0 100] :value)
                 (oc/assoc-coalescing [100 200] :value)
                 (oc/assoc-coalescing [200 300] :value))]
      (is (= 1 (count rm)))
      (doseq [x (range 0 300 10)]
        (is (= :value (rm x))))))

  (testing "Non-coalescing preserves distinct values"
    (let [rm (-> (oc/range-map)
                 (assoc [0 100] :a)
                 (assoc [100 200] :b)
                 (assoc [200 300] :c))]
      (is (= :a (rm 50)))
      (is (= :b (rm 150)))
      (is (= :c (rm 250))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Regression Tests
;;
;; Guard against specific edge cases that could cause bugs:
;; - Inserting a range with exact same boundaries (complete replacement)
;; - Inserting identical range multiple times (should collapse to one)
;; - Overlaying entire content (reduces to single range)
;; - Sequential complete overlays (last one wins)
;; These tests prevent regressions in overlap resolution logic.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest regression-exact-boundary-overlap
  (testing "Inserting range with exact same boundaries"
    (let [rm0 (oc/range-map {[0 100] :old})
          rm1 (assoc rm0 [0 100] :new)]
      (is (= 1 (count rm1)))
      (is (= :new (rm1 50)))))

  (testing "Inserting identical range multiple times"
    (let [rm (reduce
               (fn [m _] (assoc m [0 100] :value))
               (oc/range-map)
               (range 100))]
      (is (= 1 (count rm))))))

(deftest regression-empty-result
  (testing "Overlaying entire content"
    (let [rm0 (oc/range-map {[0 50] :a [50 100] :b})
          rm1 (assoc rm0 [0 100] :c)]
      (is (= 1 (count rm1)))
      (is (= :c (rm1 25)))
      (is (= :c (rm1 75)))))

  (testing "Sequential complete overlays"
    (let [rm (reduce
               (fn [m i]
                 (assoc m [0 1000] i))
               (oc/range-map)
               (range 100))]
      (is (= 1 (count rm)))
      (is (= 99 (rm 500))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; get-entry Tests (Guava getEntry equivalent)
;;
;; Return [range value] for the range containing a point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest get-entry-basic
  (testing "Returns [range value] for point in range"
    (let [rm (oc/range-map {[0 100] :a [200 300] :b})]
      (is (= [[0 100] :a] (oc/get-entry rm 50)))
      (is (= [[0 100] :a] (oc/get-entry rm 0)))
      (is (= [[0 100] :a] (oc/get-entry rm 99)))
      (is (= [[200 300] :b] (oc/get-entry rm 250)))))

  (testing "Returns nil for point in gap"
    (let [rm (oc/range-map {[0 100] :a [200 300] :b})]
      (is (nil? (oc/get-entry rm 150)))
      (is (nil? (oc/get-entry rm 100)))  ; exclusive upper bound
      (is (nil? (oc/get-entry rm -10)))))

  (testing "Empty range-map returns nil"
    (is (nil? (oc/get-entry (oc/range-map) 50)))))

(deftest get-entry-complex-values
  (testing "Works with complex map values"
    (let [rm (oc/range-map {[0 100] {:id 1 :name "first"}
                             [100 200] {:id 2 :name "second"}})]
      (is (= [[0 100] {:id 1 :name "first"}] (oc/get-entry rm 50)))
      (is (= [[100 200] {:id 2 :name "second"}] (oc/get-entry rm 150))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; range-remove Tests (Guava remove equivalent)
;;
;; Remove all mappings in a range, trimming overlapping ranges
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest range-remove-basic
  (testing "Remove entire range"
    (let [rm0 (oc/range-map {[0 100] :a [200 300] :b})
          rm1 (oc/range-remove rm0 [0 100])]
      (is (= 1 (count rm1)))
      (is (nil? (rm1 50)))
      (is (= :b (rm1 250)))))

  (testing "Remove middle of range - splits it"
    (let [rm0 (oc/range-map {[0 100] :a})
          rm1 (oc/range-remove rm0 [25 75])]
      (is (= 2 (count rm1)))
      (is (= :a (rm1 10)))
      (is (nil? (rm1 50)))
      (is (= :a (rm1 80)))))

  (testing "Remove left portion of range"
    (let [rm0 (oc/range-map {[0 100] :a})
          rm1 (oc/range-remove rm0 [0 50])]
      (is (= 1 (count rm1)))
      (is (nil? (rm1 25)))
      (is (= :a (rm1 75)))))

  (testing "Remove right portion of range"
    (let [rm0 (oc/range-map {[0 100] :a})
          rm1 (oc/range-remove rm0 [50 100])]
      (is (= 1 (count rm1)))
      (is (= :a (rm1 25)))
      (is (nil? (rm1 75))))))

(deftest range-remove-spanning-multiple
  (testing "Remove spanning multiple ranges"
    (let [rm0 (oc/range-map {[0 100] :a [100 200] :b [200 300] :c})
          rm1 (oc/range-remove rm0 [50 250])]
      (is (= 2 (count rm1)))
      (is (= :a (rm1 25)))
      (is (nil? (rm1 150)))
      (is (= :c (rm1 275)))))

  (testing "Remove all ranges"
    (let [rm0 (oc/range-map {[0 100] :a [100 200] :b})
          rm1 (oc/range-remove rm0 [0 200])]
      (is (= 0 (count rm1))))))

(deftest range-remove-no-overlap
  (testing "Remove range in gap - no effect"
    (let [rm0 (oc/range-map {[0 100] :a [200 300] :b})
          rm1 (oc/range-remove rm0 [120 180])]
      (is (= 2 (count rm1)))
      (is (= :a (rm1 50)))
      (is (= :b (rm1 250)))))

  (testing "Remove range before all ranges"
    (let [rm0 (oc/range-map {[100 200] :a})
          rm1 (oc/range-remove rm0 [0 50])]
      (is (= 1 (count rm1)))
      (is (= :a (rm1 150)))))

  (testing "Remove range after all ranges"
    (let [rm0 (oc/range-map {[0 100] :a})
          rm1 (oc/range-remove rm0 [200 300])]
      (is (= 1 (count rm1)))
      (is (= :a (rm1 50))))))

(deftest range-remove-stress
  (testing "Remove many small ranges from large range"
    (let [rm0 (oc/range-map {[0 1000] :base})
          ;; Remove every other segment
          rm1 (reduce
                (fn [rm i]
                  (oc/range-remove rm [(* i 20) (+ (* i 20) 10)]))
                rm0
                (range 50))]
      ;; Should have 50 fragments of :base
      (is (= 50 (count rm1)))
      ;; Check some fragments
      (is (= :base (rm1 15)))   ; in [10, 20)
      (is (nil? (rm1 5)))       ; removed [0, 10)
      (is (= :base (rm1 35)))))); in [30, 40)
