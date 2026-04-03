(ns com.dean.ordered-collections.segment-tree-test
  "Rigorous tests for SegmentTree - range aggregate queries with O(log n) updates."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [com.dean.ordered-collections.test-utils :as tu]
            [com.dean.ordered-collections.core :as oc])
  (:import [java.time Instant]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reference implementations for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ref-range-sum
  "Reference: sum values for keys in [lo, hi]."
  [m lo hi]
  (tu/ref-range-sum m lo hi))

(defn- ref-range-min
  "Reference: min value for keys in [lo, hi]."
  [m lo hi]
  (reduce min Long/MAX_VALUE (for [[k v] m :when (<= lo k hi)] v)))

(defn- ref-range-max
  "Reference: max value for keys in [lo, hi]."
  [m lo hi]
  (reduce max Long/MIN_VALUE (for [[k v] m :when (<= lo k hi)] v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest construction-empty
  (let [st (oc/segment-tree + 0)]
    (is (zero? (count st)))
    (is (nil? (seq st)))
    (is (= 0 (oc/aggregate st)))))

(deftest construction-various-sizes
  (doseq [n [1 2 10 100 1000 10000]]
    (testing (str "N=" n)
      (let [data (into {} (for [i (range n)] [i i]))
            st   (oc/sum-tree data)]
        (is (= n (count st)))
        (is (= (reduce + (range n)) (oc/aggregate st)))))))

(deftest construction-from-seq
  (doseq [n [10 100 1000]]
    (let [pairs (for [i (range n)] [i (* i 10)])
          st    (oc/sum-tree pairs)]
      (is (= n (count st)))
      (is (= (* 10 (reduce + (range n))) (oc/aggregate st))))))

(deftest construction-non-contiguous-indices
  (doseq [n [10 100 1000]]
    (testing (str "N=" n " sparse indices")
      (let [;; Random indices with gaps
            indices (sort (distinct (repeatedly n #(rand-int (* n 10)))))
            data    (into {} (for [i indices] [i 1]))
            st      (oc/sum-tree data)]
        (is (= (count indices) (count st)))
        (is (= (count indices) (oc/aggregate st)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sum tree: range sum queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest sum-tree-aggregate
  (doseq [n [10 100 1000 10000 100000]]
    (testing (str "N=" n)
      (let [data (into {} (for [i (range n)] [i i]))
            st   (oc/sum-tree data)]
        (is (= (reduce + (range n)) (oc/aggregate st)))))))

(deftest sum-tree-range-queries
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n " random range queries")
      (let [data (into {} (for [i (range n)] [i i]))
            st   (oc/sum-tree data)]
        ;; Random range queries
        (dotimes [_ 500]
          (let [lo (rand-int n)
                hi (+ lo (rand-int (- n lo)))]
            (is (= (ref-range-sum data lo hi)
                   (oc/query st lo hi))
                (str "Range [" lo ", " hi "]"))))))))

(deftest sum-tree-single-element-queries
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n " single element queries")
      (let [data (into {} (for [i (range n)] [i (* i 100)]))
            st   (oc/sum-tree data)]
        ;; Each single-element query should return the element
        (doseq [i (take 100 (repeatedly #(rand-int n)))]
          (is (= (* i 100) (oc/query st i i))))))))

(deftest sum-tree-full-range
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n " full range")
      (let [data (into {} (for [i (range n)] [i 1]))
            st   (oc/sum-tree data)]
        (is (= n (oc/query st 0 (dec n))))))))

(deftest sum-tree-arithmetic-sequences
  ;; Sum of i from a to b = (b-a+1)(a+b)/2
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n " arithmetic sequence")
      (let [st (oc/sum-tree (into {} (for [i (range n)] [i i])))]
        (dotimes [_ 100]
          (let [lo (rand-int n)
                hi (+ lo (rand-int (- n lo)))
                expected (quot (* (- hi lo -1) (+ lo hi)) 2)]
            (is (= expected (oc/query st lo hi)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Min tree: range minimum queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest min-tree-aggregate
  (doseq [n [10 100 1000 10000]]
    (testing (str "N=" n)
      (let [data (into {} (for [i (range n)] [i (rand-int 10000)]))
            st   (oc/min-tree data)]
        (is (= (apply min (vals data)) (oc/aggregate st)))))))

(deftest min-tree-range-queries
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n " random range queries")
      (let [data (into {} (for [i (range n)] [i (rand-int 10000)]))
            st   (oc/min-tree data)]
        (dotimes [_ 500]
          (let [lo (rand-int n)
                hi (+ lo (rand-int (- n lo)))]
            (is (= (ref-range-min data lo hi)
                   (oc/query st lo hi))
                (str "Range [" lo ", " hi "]"))))))))

(deftest min-tree-with-known-minimum
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n " with known minimum location")
      (let [min-idx (rand-int n)
            data    (into {} (for [i (range n)]
                               [i (if (= i min-idx) 0 1000)]))
            st      (oc/min-tree data)]
        ;; Full range should find 0
        (is (= 0 (oc/query st 0 (dec n))))
        ;; Range containing min-idx should find 0
        (is (= 0 (oc/query st (max 0 (- min-idx 10)) (min (dec n) (+ min-idx 10)))))
        ;; Range not containing min-idx should find 1000
        (when (> min-idx 10)
          (is (= 1000 (oc/query st 0 (- min-idx 5)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Max tree: range maximum queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest max-tree-aggregate
  (doseq [n [10 100 1000 10000]]
    (testing (str "N=" n)
      (let [data (into {} (for [i (range n)] [i (rand-int 10000)]))
            st   (oc/max-tree data)]
        (is (= (apply max (vals data)) (oc/aggregate st)))))))

(deftest max-tree-range-queries
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n " random range queries")
      (let [data (into {} (for [i (range n)] [i (rand-int 10000)]))
            st   (oc/max-tree data)]
        (dotimes [_ 500]
          (let [lo (rand-int n)
                hi (+ lo (rand-int (- n lo)))]
            (is (= (ref-range-max data lo hi)
                   (oc/query st lo hi))
                (str "Range [" lo ", " hi "]"))))))))

(deftest max-tree-with-known-maximum
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n " with known maximum location")
      (let [max-idx (rand-int n)
            data    (into {} (for [i (range n)]
                               [i (if (= i max-idx) 10000 0)]))
            st      (oc/max-tree data)]
        ;; Full range should find 10000
        (is (= 10000 (oc/query st 0 (dec n))))
        ;; Range containing max-idx should find 10000
        (is (= 10000 (oc/query st (max 0 (- max-idx 10)) (min (dec n) (+ max-idx 10)))))
        ;; Range not containing max-idx should find 0
        (when (> max-idx 10)
          (is (= 0 (oc/query st 0 (- max-idx 5)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Update operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest update-val-preserves-immutability
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n)
      (let [data (into {} (for [i (range n)] [i i]))
            st   (oc/sum-tree data)
            orig-agg (oc/aggregate st)]
        ;; Update random elements
        (dotimes [_ 50]
          (let [idx (rand-int n)
                new-val (rand-int 10000)
                st' (oc/update-val st idx new-val)]
            ;; Original unchanged
            (is (= orig-agg (oc/aggregate st)))
            (is (= idx (st idx)))
            ;; New tree updated
            (is (= new-val (st' idx)))))))))

(deftest update-val-aggregate-consistency
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n)
      (let [st (oc/sum-tree (into {} (for [i (range n)] [i 1])))]
        ;; Update one element and verify aggregate changes correctly
        (dotimes [_ 50]
          (let [idx     (rand-int n)
                new-val (rand-int 100)
                st'     (oc/update-val st idx new-val)]
            ;; New aggregate = old aggregate - 1 + new-val
            (is (= (+ (- n 1) new-val) (oc/aggregate st')))))))))

(deftest update-val-range-query-consistency
  (doseq [n [100 1000]]
    (testing (str "N=" n)
      (let [data (into {} (for [i (range n)] [i i]))
            st   (oc/sum-tree data)]
        (dotimes [_ 20]
          (let [idx     (rand-int n)
                new-val (rand-int 10000)
                st'     (oc/update-val st idx new-val)
                data'   (assoc data idx new-val)]
            ;; Random range queries should match reference
            (dotimes [_ 50]
              (let [lo (rand-int n)
                    hi (+ lo (rand-int (- n lo)))]
                (is (= (ref-range-sum data' lo hi)
                       (oc/query st' lo hi)))))))))))

(deftest update-fn-test
  (doseq [n [100 1000 10000]]
    (testing (str "N=" n)
      (let [st (oc/sum-tree (into {} (for [i (range n)] [i 1])))]
        ;; Double a random element
        (dotimes [_ 50]
          (let [idx (rand-int n)
                st' (oc/update-fn st idx #(* 2 %))]
            (is (= 2 (st' idx)))
            (is (= (inc n) (oc/aggregate st')))))))))

(deftest multiple-updates
  (doseq [n [100 1000]]
    (testing (str "N=" n " sequential updates")
      (let [st (oc/sum-tree (into {} (for [i (range n)] [i 0])))]
        ;; Set all elements to 1
        (let [st' (reduce #(oc/update-val %1 %2 1) st (range n))]
          (is (= n (oc/aggregate st'))))
        ;; Set random subset to 10
        (let [indices (take 100 (shuffle (range n)))
              st'     (reduce #(oc/update-val %1 %2 10) st indices)]
          (is (= (* 10 100) (oc/aggregate st'))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collection operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest collection-operations
  (doseq [n [10 100 1000]]
    (let [data (into {} (for [i (range n)] [i i]))
          st   (oc/sum-tree data)]

      (testing "count"
        (is (= n (count st))))

      (testing "seq"
        (let [pairs (seq st)]
          (is (= n (count pairs)))
          (is (= (sort (keys data)) (map first pairs)))))

      (testing "get/lookup"
        (doseq [i (take 50 (repeatedly #(rand-int n)))]
          (is (= i (get st i)))
          (is (= i (st i))))
        (is (nil? (st (+ n 100))))
        (is (= :default (get st (+ n 100) :default))))

      (testing "assoc (same as update-val)"
        (let [st' (assoc st 0 999)]
          (is (= 999 (st' 0)))))

      (testing "empty"
        (let [e (empty st)]
          (is (zero? (count e)))
          (is (= 0 (oc/aggregate e)))))

      (testing "cons/conj"
        (let [st' (conj st [(+ n 1) 100])]
          (is (= (inc n) (count st')))
          (is (= 100 (st' (+ n 1)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom monoid operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest custom-product-monoid
  ;; Only test small n to avoid overflow (n! overflows long at n=21)
  (doseq [n [5 10 15]]
    (testing (str "N=" n " product monoid")
      (let [;; Values 1-n to avoid zeros
            data (into {} (for [i (range n)] [i (inc i)]))
            st   (oc/segment-tree *' 1 data)]  ; use *' for arbitrary precision
        ;; Just verify some range queries work
        (is (= 1 (oc/query st 0 0)))     ; 1
        (is (= 2 (oc/query st 0 1)))     ; 1 * 2
        (is (= 6 (oc/query st 0 2)))     ; 1 * 2 * 3
        (is (= 24 (oc/query st 0 3)))))) ; 1 * 2 * 3 * 4

  (testing "Product with updates"
    (let [st  (oc/segment-tree * 1 {0 2, 1 3, 2 5})
          st' (oc/update-val st 1 7)]
      (is (= 30 (oc/aggregate st)))     ; 2 * 3 * 5
      (is (= 70 (oc/aggregate st'))))) ; 2 * 7 * 5
)

(deftest custom-bitwise-or-monoid
  (testing "Bitwise OR"
    (let [st (oc/segment-tree bit-or 0 {0 1, 1 2, 2 4, 3 8})]
      (is (= 15 (oc/aggregate st)))     ; 1|2|4|8
      (is (= 3 (oc/query st 0 1)))      ; 1|2
      (is (= 12 (oc/query st 2 3)))     ; 4|8
      (is (= 7 (oc/query st 0 2)))))    ; 1|2|4

  (testing "Bitwise OR with random data"
    (doseq [n [10 50 100]]
      (let [data (into {} (for [i (range n)] [i (bit-shift-left 1 (mod i 20))]))
            st   (oc/segment-tree bit-or 0 data)]
        ;; Aggregate should have all bits set that appear in any element
        (is (= (reduce bit-or (vals data)) (oc/aggregate st)))))))

(deftest custom-gcd-monoid
  (let [gcd (fn gcd [a b]
              (if (zero? b) a (gcd b (mod a b))))]
    (testing "GCD monoid"
      (let [st (oc/segment-tree gcd 0 {0 12, 1 18, 2 24, 3 30})]
        (is (= 6 (oc/aggregate st)))    ; gcd(12,18,24,30) = 6
        (is (= 6 (oc/query st 0 1)))    ; gcd(12,18) = 6
        (is (= 6 (oc/query st 1 2)))    ; gcd(18,24) = 6
        (is (= 6 (oc/query st 2 3))))) ; gcd(24,30) = 6

    (testing "GCD with primes"
      (let [primes [2 3 5 7 11 13 17 19 23 29]
            st     (oc/segment-tree gcd 0 (zipmap (range) primes))]
        ;; All primes, so GCD of any range with >1 element is 1
        (is (= 1 (oc/aggregate st)))
        (doseq [i (range 9)]
          (is (= 1 (oc/query st i (inc i)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest edge-case-single-element
  (let [st (oc/sum-tree {42 100})]
    (is (= 1 (count st)))
    (is (= 100 (oc/aggregate st)))
    (is (= 100 (oc/query st 42 42)))
    (is (= 100 (st 42)))))

(deftest edge-case-two-elements
  (let [st (oc/sum-tree {0 10, 100 20})]
    (is (= 2 (count st)))
    (is (= 30 (oc/aggregate st)))
    (is (= 10 (oc/query st 0 0)))
    (is (= 20 (oc/query st 100 100)))
    (is (= 30 (oc/query st 0 100)))
    ;; Range with no elements
    (is (= 0 (oc/query st 1 99)))))

(deftest edge-case-sparse-indices
  (doseq [n [10 100 1000]]
    (testing (str "N=" n " very sparse")
      (let [;; Indices spaced far apart
            data (into {} (for [i (range n)] [(* i 1000) i]))
            st   (oc/sum-tree data)]
        (is (= n (count st)))
        (is (= (reduce + (range n)) (oc/aggregate st)))
        ;; Query between indices should return 0
        (is (= 0 (oc/query st 1 999)))
        ;; Query spanning multiple indices
        (is (= (+ 0 1 2) (oc/query st 0 2500)))))))

(deftest edge-case-negative-indices
  (let [st (oc/sum-tree {-100 10, -50 20, 0 30, 50 40, 100 50})]
    (is (= 5 (count st)))
    (is (= 150 (oc/aggregate st)))
    (is (= 10 (oc/query st -100 -100)))
    (is (= 30 (oc/query st -100 -50)))   ; 10 + 20
    (is (= 50 (oc/query st -50 0)))      ; 20 + 30
    (is (= 150 (oc/query st -100 100))))) ; all

(deftest segment-tree-supports-generic-ordered-keys
  (testing "string keys"
    (let [st (oc/segment-tree + 0 {"a" 10, "b" 20, "c" 30, "d" 40})]
      (is (= 100 (oc/aggregate st)))
      (is (= 50 (oc/query st "b" "c")))
      (is (= 90 (oc/query st "b" "z")))
      (is (= 0 (oc/query st "x" "z")))
      (is (= 15 (-> st
                    (oc/update-val "a" 15)
                    (get "a"))))))

  (testing "long string keys with sparse misses"
      (let [k1 "alpha/warehouse/zone/0001"
          k2 "alpha/warehouse/zone/0100"
          k3 "beta/warehouse/zone/0200"
          st (oc/segment-tree + 0 {k1 5, k2 7, k3 11})]
      (is (= 12 (oc/query st k1 k2)))
      (is (= 23 (oc/query st "alpha" "beta/warehouse/zone/9999")))
      (is (= 0 (oc/query st "gamma" "omega")))))

  (testing "decimal keys"
    (let [st (oc/segment-tree + 0 {1.5 10, 2.0 20, 2.5 30, 4.0 40})]
      (is (= 60 (oc/query st 1.5 2.5)))
      (is (= 50 (oc/query st 1.7 3.0)))
      (is (= 0 (oc/query st 4.1 5.0)))))

  (testing "Instant keys"
    (let [t0 (Instant/parse "2026-01-01T00:00:00Z")
          t1 (Instant/parse "2026-01-01T01:00:00Z")
          t2 (Instant/parse "2026-01-01T02:00:00Z")
          t3 (Instant/parse "2026-01-01T03:00:00Z")
          t4 (Instant/parse "2026-01-01T04:00:00Z")
          st (oc/segment-tree + 0 {t0 10, t1 20, t2 30, t3 40})]
      (is (= 60 (oc/query st t0 t2)))
      (is (= 90 (oc/query st t1 t4)))
      (is (= 0 (oc/query st (Instant/parse "2026-01-02T00:00:00Z")
                         (Instant/parse "2026-01-02T01:00:00Z"))))
      (is (= 25 (-> st
                    (oc/update-val t1 25)
                    (get t1))))))

  (testing "custom comparator via segment-tree-by"
    (let [st (oc/segment-tree-by > + 0 [[4 40] [3 30] [2 20] [1 10]])]
      (is (= 70 (oc/query st 4 3)))
      (is (= 100 (oc/query st 4 1)))
      (is (= 0 (oc/query st 0 -1)))))

  (testing "empty range when lo > hi"
    (let [st (oc/sum-tree {0 10, 1 20, 2 30})]
      (is (= 0 (oc/query st 2 1))))))

(deftest segment-tree-ordered-map-parity-on-generic-keys
  (let [t0 (Instant/parse "2026-01-01T00:00:00Z")
        t1 (Instant/parse "2026-01-01T01:00:00Z")
        t2 (Instant/parse "2026-01-01T02:00:00Z")
        t3 (Instant/parse "2026-01-01T03:00:00Z")
        st (oc/segment-tree + 0 {t0 10, t1 20, t2 30, t3 40})]
    (testing "ranked operations"
      (is (= 0 (oc/rank st t0)))
      (is (= 2 (oc/rank st t2)))
      (is (nil? (oc/rank st (Instant/parse "2026-01-01T00:30:00Z"))))
      (is (= [[t1 20] [t2 30]]
             (mapv vec (oc/slice st 1 3))))
      (is (= [t1 20] (vec (oc/median st))))
      (is (= [t2 30] (vec (oc/percentile st 50)))))

    (testing "split operations"
      (let [[left entry right] (oc/split-key t1 st)
            [front back]       (oc/split-at 2 st)]
        (is (= [[t0 10]] (mapv vec left)))
        (is (= [t1 20] (vec entry)))
        (is (= [[t2 30] [t3 40]] (mapv vec right)))
        (is (= [[t0 10] [t1 20]] (mapv vec front)))
        (is (= [[t2 30] [t3 40]] (mapv vec back)))))

    (testing "nearest and subrange"
      (let [probe (Instant/parse "2026-01-01T01:30:00Z")]
        (is (= [t1 20] (vec (oc/nearest st :<= probe))))
        (is (= [t2 30] (vec (oc/nearest st :>= probe))))
        (is (= [[t0 10] [t1 20]]
               (mapv vec (oc/subrange st :<= t1))))
        (is (= [[t2 30] [t3 40]]
               (mapv vec (oc/subrange st :> t1))))))

    (testing "sorted map behavior preserves aggregates"
      (let [tail (.tailMap ^java.util.SortedMap st t1)
            trimmed (dissoc st t1)
            inserted (assoc st (Instant/parse "2026-01-01T04:00:00Z") 50)]
        (is (= t0 (.firstKey ^java.util.SortedMap st)))
        (is (= t3 (.lastKey ^java.util.SortedMap st)))
        (is (= 90 (oc/aggregate tail)))
        (is (= 80 (oc/aggregate trimmed)))
        (is (= 150 (oc/aggregate inserted)))
        (is (= 90 (oc/query tail t1 t3)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stress tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest stress-large-tree
  (doseq [n [10000 100000 500000]]
    (testing (str "N=" n)
      (let [st (oc/sum-tree (into {} (for [i (range n)] [i 1])))]
        (is (= n (count st)))
        (is (= n (oc/aggregate st)))
        ;; Random range queries
        (dotimes [_ 100]
          (let [lo (rand-int n)
                hi (+ lo (rand-int (- n lo)))]
            (is (= (- hi lo -1) (oc/query st lo hi)))))))))

(deftest stress-many-updates
  (doseq [n [1000 10000]]
    (testing (str "N=" n " with many updates")
      (let [st (oc/sum-tree (into {} (for [i (range n)] [i 0])))]
        ;; Apply 1000 random updates
        (let [updates (for [_ (range 1000)]
                        [(rand-int n) (rand-int 100)])
              final-st (reduce (fn [t [i v]] (oc/update-val t i v)) st updates)
              final-data (reduce (fn [m [i v]] (assoc m i v))
                                 (into {} (for [i (range n)] [i 0]))
                                 updates)]
          ;; Aggregate should match
          (is (= (reduce + (vals final-data)) (oc/aggregate final-st)))
          ;; Random range queries should match
          (dotimes [_ 100]
            (let [lo (rand-int n)
                  hi (+ lo (rand-int (- n lo)))]
              (is (= (ref-range-sum final-data lo hi)
                     (oc/query final-st lo hi))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Randomized property tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest property-aggregate-equals-full-range-query
  (dotimes [_ 50]
    (let [n    (+ 10 (rand-int 1000))
          data (into {} (for [i (range n)] [i (rand-int 1000)]))
          st   (oc/sum-tree data)
          lo   (apply min (keys data))
          hi   (apply max (keys data))]
      (is (= (oc/aggregate st) (oc/query st lo hi))))))

(deftest property-update-then-query
  (dotimes [_ 50]
    (let [n    (+ 10 (rand-int 500))
          data (into {} (for [i (range n)] [i (rand-int 100)]))
          st   (oc/sum-tree data)
          idx  (rand-int n)
          new-val (rand-int 1000)
          st'  (oc/update-val st idx new-val)
          data' (assoc data idx new-val)]
      ;; Aggregate should be correct
      (is (= (reduce + (vals data')) (oc/aggregate st')))
      ;; Query containing idx should reflect new value
      (is (= (ref-range-sum data' 0 (dec n))
             (oc/query st' 0 (dec n)))))))

(deftest property-sum-tree-vs-reduce
  (dotimes [_ 30]
    (let [n    (+ 100 (rand-int 5000))
          data (into {} (for [i (range n)] [i (rand-int 100)]))
          st   (oc/sum-tree data)]
      ;; Aggregate should equal reduce
      (is (= (reduce + (vals data)) (oc/aggregate st)))
      ;; Full range query should equal reduce
      (is (= (reduce + (vals data)) (oc/query st 0 (dec n)))))))

(defspec prop-segment-tree-query-equivalence 100
  (prop/for-all [entries tu/gen-int-map-entries
                 ranges  (gen/vector tu/gen-query-range 0 30)]
    (let [m  (into (sorted-map) entries)
          st (oc/sum-tree entries)]
      (every? (fn [[lo hi]]
                (= (tu/ref-range-sum m lo hi)
                   (oc/query st lo hi)))
              ranges))))

(defspec prop-segment-tree-update-equivalence 100
  (prop/for-all [entries  tu/gen-int-map-entries
                 updates  tu/gen-segment-updates
                 ranges   (gen/vector tu/gen-query-range 0 20)]
    (let [m'  (reduce (fn [m [k v]] (assoc m k v)) (into (sorted-map) entries) updates)
          st' (reduce (fn [st [k v]] (oc/update-val st k v)) (oc/sum-tree entries) updates)]
      (and (= (reduce + 0 (vals m')) (oc/aggregate st'))
           (every? (fn [[lo hi]]
                     (= (tu/ref-range-sum m' lo hi)
                        (oc/query st' lo hi)))
                   ranges)))))
