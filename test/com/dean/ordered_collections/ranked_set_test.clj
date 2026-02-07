(ns com.dean.ordered-collections.ranked-set-test
  "Rigorous tests for RankedSet - a sorted set with O(log n) positional access."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.reducers :as r]
            [com.dean.ordered-collections.core :as oc]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Construction at various sizes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest construction-various-sizes
  (doseq [size [0 1 2 10 100 1000 10000 100000]]
    (testing (str "Size " size)
      (let [data (shuffle (range size))
            rs   (oc/ranked-set data)
            ss   (apply sorted-set data)]
        (is (= size (count rs)))
        (is (= (vec (seq ss)) (vec (seq rs))))
        (is (= rs ss))))))

(deftest construction-with-duplicates
  (doseq [size [10 100 1000 10000]]
    (testing (str "Size " size " with duplicates")
      (let [;; Create data with ~50% duplicates
            data (shuffle (concat (range size) (take (quot size 2) (shuffle (range size)))))
            rs   (oc/ranked-set data)
            ss   (apply sorted-set data)]
        (is (= size (count rs)))
        (is (= (seq ss) (seq rs)))))))

(deftest construction-with-comparator
  (doseq [size [10 100 1000 10000]]
    (testing (str "Descending order, size " size)
      (let [data (shuffle (range size))
            rs   (oc/ranked-set-by > data)]
        (is (= (reverse (range size)) (seq rs)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; nth-element: positional access
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest nth-element-correctness
  (doseq [size [10 100 1000 10000 100000]]
    (testing (str "Size " size)
      (let [data   (shuffle (range size))
            rs     (oc/ranked-set data)
            sorted (vec (sort data))]
        ;; Check all elements match
        (doseq [i (range size)]
          (is (= (sorted i) (oc/nth-element rs i))
              (str "Mismatch at index " i)))))))

(deftest nth-element-random-access
  (doseq [size [1000 10000 100000 500000]]
    (testing (str "Random access, size " size)
      (let [data   (shuffle (range size))
            rs     (oc/ranked-set data)
            sorted (vec (sort data))
            ;; Test 1000 random indices
            indices (repeatedly 1000 #(rand-int size))]
        (is (every? #(= (sorted %) (oc/nth-element rs %)) indices))))))

(deftest nth-element-boundaries
  (doseq [size [1 10 100 1000]]
    (testing (str "Boundary cases, size " size)
      (let [rs (oc/ranked-set (shuffle (range size)))]
        ;; First and last
        (is (= 0 (oc/nth-element rs 0)))
        (is (= (dec size) (oc/nth-element rs (dec size))))
        ;; Out of bounds with not-found
        (is (= :nope (oc/nth-element rs -1 :nope)))
        (is (= :nope (oc/nth-element rs size :nope)))
        (is (= :nope (oc/nth-element rs (* size 10) :nope)))))))

(deftest nth-element-with-comparator
  (doseq [size [100 1000 10000]]
    (testing (str "Descending, size " size)
      (let [rs     (oc/ranked-set-by > (shuffle (range size)))
            sorted (vec (reverse (range size)))]
        (doseq [i (take 100 (repeatedly #(rand-int size)))]
          (is (= (sorted i) (oc/nth-element rs i))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rank: inverse of nth-element
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rank-correctness
  (doseq [size [10 100 1000 10000 100000]]
    (testing (str "Size " size)
      (let [data (shuffle (range size))
            rs   (oc/ranked-set data)]
        ;; Rank of each element equals its sorted position
        (doseq [x (range size)]
          (is (= x (oc/rank rs x))
              (str "Rank mismatch for element " x)))))))

(deftest rank-is-inverse-of-nth-element
  (doseq [size [100 1000 10000 100000]]
    (testing (str "Inverse property, size " size)
      (let [rs (oc/ranked-set (shuffle (range size)))]
        ;; For all i: rank(nth-element(i)) == i
        (doseq [i (take 500 (repeatedly #(rand-int size)))]
          (is (= i (oc/rank rs (oc/nth-element rs i)))))
        ;; For all x in set: nth-element(rank(x)) == x
        (doseq [x (take 500 (repeatedly #(rand-int size)))]
          (is (= x (oc/nth-element rs (oc/rank rs x)))))))))

(deftest rank-non-existent
  (doseq [size [100 1000 10000]]
    (testing (str "Non-existent elements, size " size)
      (let [;; Only even numbers
            rs (oc/ranked-set (range 0 size 2))]
        ;; Odd numbers should have nil rank
        (doseq [x (range 1 size 2)]
          (is (nil? (oc/rank rs x))))
        ;; Elements outside range
        (is (nil? (oc/rank rs -1)))
        (is (nil? (oc/rank rs size)))
        (is (nil? (oc/rank rs (* size 10))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; slice: range extraction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest slice-correctness
  (doseq [size [100 1000 10000]]
    (testing (str "Size " size)
      (let [rs     (oc/ranked-set (shuffle (range size)))
            sorted (vec (range size))]
        ;; Random slices
        (dotimes [_ 100]
          (let [start (rand-int size)
                end   (+ start (rand-int (- size start)))]
            (is (= (subvec sorted start end)
                   (vec (oc/slice rs start end))))))))))

(deftest slice-edge-cases
  (doseq [size [10 100 1000]]
    (testing (str "Edge cases, size " size)
      (let [rs (oc/ranked-set (shuffle (range size)))]
        ;; Empty slice
        (is (empty? (oc/slice rs 0 0)))
        (is (empty? (oc/slice rs 5 5)))
        ;; Full slice
        (is (= (range size) (oc/slice rs 0 size)))
        ;; Single element slices
        (doseq [i (range (min 10 size))]
          (is (= (list i) (oc/slice rs i (inc i)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; median: middle element
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest median-correctness
  (doseq [size [1 2 3 10 11 100 101 1000 1001 10000 10001]]
    (testing (str "Size " size)
      (let [rs       (oc/ranked-set (shuffle (range size)))
            expected (quot (dec size) 2)]
        (is (= expected (oc/median rs)))))))

(deftest median-empty
  (is (nil? (oc/median (oc/ranked-set)))))

(deftest median-random-data
  (dotimes [_ 100]
    (let [size (+ 1 (rand-int 1000))
          data (repeatedly size #(rand-int 10000))
          rs   (oc/ranked-set data)
          n    (count rs)
          expected (oc/nth-element rs (quot (dec n) 2))]
      (is (= expected (oc/median rs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; percentile: position by percentage
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest percentile-boundaries
  (doseq [size [10 100 1000 10000]]
    (testing (str "Size " size)
      (let [rs (oc/ranked-set (shuffle (range size)))]
        ;; 0th percentile is minimum
        (is (= 0 (oc/percentile rs 0)))
        ;; 100th percentile is maximum
        (is (= (dec size) (oc/percentile rs 100)))))))

(deftest percentile-various
  (let [rs (oc/ranked-set (range 100))]
    ;; For 100 elements: percentile p should give index close to p
    (doseq [p [0 10 25 50 75 90 100]]
      (let [result (oc/percentile rs p)]
        (is (<= (- p 1) result (+ p 1))
            (str "Percentile " p " gave " result))))))

(deftest percentile-empty
  (is (nil? (oc/percentile (oc/ranked-set) 50))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Underlying set operations still work
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest set-operations-integrity
  (doseq [size [100 1000 10000]]
    (testing (str "Size " size)
      (let [data (shuffle (range size))
            rs   (oc/ranked-set data)
            ss   (apply sorted-set data)]
        ;; contains?
        (doseq [x (take 100 (repeatedly #(rand-int (* 2 size))))]
          (is (= (contains? ss x) (contains? rs x))))
        ;; subseq
        (dotimes [_ 20]
          (let [lo (rand-int size)
                hi (+ lo (rand-int (- size lo)))]
            (is (= (vec (subseq ss >= lo < hi))
                   (vec (subseq rs >= lo < hi))))))))))

(deftest set-mutation-operations
  (doseq [size [100 1000 10000]]
    (testing (str "Size " size)
      (let [rs (oc/ranked-set (shuffle (range size)))]
        ;; conj new element
        (let [rs' (conj rs size)]
          (is (= (inc size) (count rs')))
          (is (contains? rs' size))
          (is (= size (oc/nth-element rs' size))))
        ;; disj existing element
        (let [to-remove (rand-int size)
              rs'       (disj rs to-remove)]
          (is (= (dec size) (count rs')))
          (is (not (contains? rs' to-remove))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stress tests with various element types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest various-element-types
  (doseq [size [100 1000 10000]
          [name f] [["integers" identity]
                    ["strings" str]
                    ["keywords" #(keyword (str "k" %))]]]
    (testing (str name ", size " size)
      (let [data   (mapv f (shuffle (range size)))
            rs     (oc/ranked-set data)
            sorted (vec (sort data))]
        (is (= size (count rs)))
        ;; Random nth-element checks
        (doseq [i (take 50 (repeatedly #(rand-int size)))]
          (is (= (sorted i) (oc/nth-element rs i))))
        ;; Random rank checks
        (doseq [i (take 50 (repeatedly #(rand-int size)))]
          (let [elem (sorted i)]
            (is (= i (oc/rank rs elem)))))))))

(deftest reducible-and-foldable
  (doseq [size [100 1000 10000 100000 500000]]
    (testing (str "Size " size)
      (let [data (shuffle (range size))
            rs   (oc/ranked-set data)
            expected (reduce + (range size))]
        ;; reduce
        (is (= expected (reduce + rs)))
        ;; r/fold
        (is (= expected (r/fold + rs)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Randomized property tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest randomized-properties
  (dotimes [_ 50]
    (let [size (+ 10 (rand-int 10000))
          ;; Random data with possible duplicates and gaps
          data (repeatedly size #(rand-int (* size 2)))
          rs   (oc/ranked-set data)
          n    (count rs)]
      (testing (str "Random data, n=" n)
        ;; Property: seq is sorted
        (let [s (seq rs)]
          (is (= s (sort s))))
        ;; Property: all indices valid
        (doseq [i (take 20 (repeatedly #(rand-int n)))]
          (is (some? (oc/nth-element rs i))))
        ;; Property: rank/nth-element are inverses
        (doseq [i (take 20 (repeatedly #(rand-int n)))]
          (let [elem (oc/nth-element rs i)]
            (is (= i (oc/rank rs elem)))))
        ;; Property: median is in the middle
        (when (pos? n)
          (let [med (oc/median rs)
                idx (oc/rank rs med)]
            (is (= (quot (dec n) 2) idx))))))))

(deftest randomized-slice-properties
  (dotimes [_ 50]
    (let [size (+ 10 (rand-int 5000))
          rs   (oc/ranked-set (shuffle (range size)))]
      ;; Property: slice(i, j) has length j - i
      (dotimes [_ 10]
        (let [i (rand-int size)
              j (+ i (rand-int (- size i)))]
          (is (= (- j i) (count (oc/slice rs i j))))))
      ;; Property: slice elements are consecutive in rank
      (dotimes [_ 10]
        (let [i     (rand-int size)
              j     (+ i 1 (rand-int (min 100 (- size i))))
              slice (vec (oc/slice rs i j))]
          (doseq [k (range (count slice))]
            (is (= (+ i k) (oc/rank rs (slice k))))))))))
