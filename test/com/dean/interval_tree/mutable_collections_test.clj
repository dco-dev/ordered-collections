(ns com.dean.interval-tree.mutable-collections-test
  (:require [clojure.test                :refer :all]
            [com.dean.interval-tree.core :refer :all]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MutableOrderedSet Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-ordered-set-basic-check
  (let [x (mutable-ordered-set (shuffle (range 8)))]
    (is (= 8 (count x)))
    (is (= (range 8) (seq x)))
    (is (= 0 (x 0)))
    (is (= nil (x 99)))
    (is (= ::nope (x 99 ::nope)))
    (is (= 3 (nth x 3)))
    (is (.contains x 5))
    (is (not (.contains x 99)))
    (is (= 5 (.get x 5)))
    (is (= nil (.get x 99)))))

(deftest mutable-ordered-set-conj-disj-check
  (let [x (mutable-ordered-set)]
    (conj! x 3)
    (conj! x 1)
    (conj! x 4)
    (conj! x 1)
    (conj! x 5)
    (is (= [1 3 4 5] (seq x)))
    (is (= 4 (count x)))
    (disj! x 3)
    (is (= [1 4 5] (seq x)))
    (is (= 3 (count x)))))

(deftest mutable-ordered-set-persistent-check
  (doseq [size [1 10 100 1000 10000 100000]]
    (let [data  (shuffle (range size))
          mut-s (mutable-ordered-set data)
          per-s (persistent! mut-s)]
      (is (set? per-s))
      (is (= (range size) (seq per-s)))
      (is (= size (count per-s)))
      (is (= (ordered-set data) per-s)))))

(deftest mutable-ordered-set-equivalence-check
  (doseq [size [1 10 100 1000 10000 100000]]
    (let [data (shuffle (range size))
          x    (ordered-set data)
          y    (persistent! (mutable-ordered-set data))]
      (is (= x y))
      (is (= (seq x) (seq y)))
      (is (= (count x) (count y))))))

(deftest mutable-ordered-set-by-check
  (let [x (mutable-ordered-set-by > (shuffle (range 10)))]
    (is (= (reverse (range 10)) (seq x)))
    (let [p (persistent! x)]
      (is (= (reverse (range 10)) (seq p))))))

(deftest mutable-ordered-set-rseq-check
  (let [x (mutable-ordered-set (shuffle (range 10)))]
    (is (= (reverse (range 10)) (rseq x)))))

(deftest mutable-ordered-set-various-types-check
  (doseq [size [10 100 1000 10000]
          f    [identity str]]
    (let [data  (mapv f (shuffle (range size)))
          mut-s (mutable-ordered-set data)
          per-s (persistent! mut-s)
          std-s (apply sorted-set data)]
      (is (= std-s per-s))
      (is (= (seq std-s) (seq per-s))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MutableOrderedMap Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-ordered-map-basic-check
  (let [x (mutable-ordered-map {:x 1 :y 2 :z 3 :a 4 :b 5})]
    (is (= 5 (count x)))
    (is (= [[:a 4] [:b 5] [:x 1] [:y 2] [:z 3]] (seq x)))
    (is (= 1 (x :x)))
    (is (= nil (x :q)))
    (is (= ::nope (x :q ::nope)))
    (is (= [:b 5] (nth x 1)))))

(deftest mutable-ordered-map-assoc-dissoc-check
  (let [x (mutable-ordered-map)]
    (assoc! x :b "b")
    (assoc! x :a "a")
    (assoc! x :c "c")
    (is (= [[:a "a"] [:b "b"] [:c "c"]] (seq x)))
    (is (= 3 (count x)))
    (dissoc! x :a)
    (is (= [[:b "b"] [:c "c"]] (seq x)))
    (is (= 2 (count x)))))

(deftest mutable-ordered-map-persistent-check
  (doseq [size [1 10 100 1000 10000 100000]]
    (let [ks    (shuffle (range size))
          vs    (map str ks)
          pairs (map vector ks vs)
          mut-m (mutable-ordered-map pairs)
          per-m (persistent! mut-m)]
      (is (map? per-m))
      (is (= size (count per-m)))
      (is (= (ordered-map pairs) per-m)))))

(deftest mutable-ordered-map-equivalence-check
  (doseq [size [1 10 100 1000 10000 100000]]
    (let [ks    (shuffle (range size))
          vs    (map str ks)
          pairs (map vector ks vs)
          x     (ordered-map pairs)
          y     (persistent! (mutable-ordered-map pairs))]
      (is (= x y))
      (is (= (seq x) (seq y)))
      (is (= (count x) (count y))))))

(deftest mutable-ordered-map-conj-check
  (let [x (mutable-ordered-map)]
    (conj! x [:a 1])
    (conj! x [:b 2])
    (is (= [[:a 1] [:b 2]] (seq x)))))

(deftest mutable-ordered-map-rseq-check
  (let [x (mutable-ordered-map (map #(vector % (str %)) (range 5)))]
    (is (= (reverse (map #(vector % (str %)) (range 5))) (rseq x)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MutableIntervalSet Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-interval-set-basic-check
  (let [x (mutable-interval-set [[1 3] [2 4] [5 9] [3 6]])]
    (is (= 4 (count x)))
    (is (= [[1 3] [2 4] [3 6] [5 9]] (seq x)))
    (is (= nil (x 0)))
    (is (= [[1 3]] (x 1)))
    (is (= [[1 3] [2 4]] (x [1 2])))
    (is (= [[1 3] [2 4] [3 6]] (x [1 3])))
    (is (= [[5 9]] (x 7)))))

(deftest mutable-interval-set-conj-disj-check
  (let [x (mutable-interval-set)]
    (conj! x [1 3])
    (conj! x [5 9])
    (is (= 2 (count x)))
    (is (= [[1 3] [5 9]] (seq x)))
    (disj! x [1 3])
    (is (= 1 (count x)))
    (is (= [[5 9]] (seq x)))))

(deftest mutable-interval-set-persistent-check
  (let [data [[1 3] [2 4] [5 9] [3 6]]
        x    (mutable-interval-set data)
        p    (persistent! x)]
    (is (set? p))
    (is (= (interval-set data) p))
    (is (= (seq (interval-set data)) (seq p)))))

(deftest mutable-interval-set-scalar-check
  (let [x (mutable-interval-set (range 5))]
    (is (= [[0 0] [1 1] [2 2] [3 3] [4 4]] (seq x)))
    (is (= [[0 0] [1 1] [2 2] [3 3]] (x [0 3.1415926])))
    (is (= nil (x 1.5)))
    (is (= [[1 1]] (x 1)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MutableIntervalMap Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-interval-map-basic-check
  (let [x (mutable-interval-map {[1 3] :x1
                                  [4 7] :x2
                                  [8 9] :x3
                                  [0 5] :x4
                                  [6 8] :x5
                                  [9 9] :x6
                                  [3 9] :x7
                                  [4 5] :x8})]
    (is (= 8 (count x)))
    ;; pointwise queries - same as interval_map_test
    (is (empty?               (x -1.00000000)))
    (is (= [:x4]              (x  0.00000000)))
    (is (= [:x4 :x1]          (x  1)))
    (is (= [:x4 :x1 :x7]      (x  3)))
    (is (= [:x4 :x7 :x8 :x2]  (x  4)))
    (is (= [:x7 :x3 :x6]      (x  9)))
    (is (empty?               (x  9.00000001)))))

(deftest mutable-interval-map-assoc-dissoc-check
  (let [x (mutable-interval-map)]
    (assoc! x [1 3] :a)
    (assoc! x [5 9] :b)
    (is (= 2 (count x)))
    (is (= [[[1 3] :a] [[5 9] :b]] (seq x)))
    (dissoc! x [1 3])
    (is (= 1 (count x)))
    (is (= [[[5 9] :b]] (seq x)))))

(deftest mutable-interval-map-persistent-check
  (let [data {[1 3] :x1 [4 7] :x2 [8 9] :x3}
        x    (mutable-interval-map data)
        p    (persistent! x)]
    (is (map? p))
    (is (= (interval-map data) p))
    (is (= (seq (interval-map data)) (seq p)))))

(deftest mutable-interval-map-conj-check
  (let [x (mutable-interval-map)]
    (conj! x [[1 3] :a])
    (conj! x [[5 9] :b])
    (is (= [[[1 3] :a] [[5 9] :b]] (seq x)))))

(deftest mutable-interval-map-rseq-check
  (let [x (mutable-interval-map {[1 3] :a [5 9] :b [2 4] :c})]
    (is (= [[[5 9] :b] [[2 4] :c] [[1 3] :a]] (rseq x)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cross-type Equivalence Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-set-round-trip-check
  (doseq [size [10 100 1000 10000]]
    (let [data   (shuffle (range size))
          per-s  (ordered-set data)
          mut-s  (mutable-ordered-set data)
          round  (persistent! mut-s)]
      (is (= per-s round))
      (is (= (seq per-s) (seq round))))))

(deftest mutable-map-round-trip-check
  (doseq [size [10 100 1000 10000]]
    (let [pairs  (map #(vector % (str %)) (shuffle (range size)))
          per-m  (ordered-map pairs)
          mut-m  (mutable-ordered-map pairs)
          round  (persistent! mut-m)]
      (is (= per-m round))
      (is (= (seq per-m) (seq round))))))
