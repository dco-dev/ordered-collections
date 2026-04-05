(ns ordered-collections.rope-test
  (:require [clojure.test :refer :all]
            [ordered-collections.types.rope :as rope]))


(deftest rope-basic-semantics
  (let [r (rope/rope [0 1 2 3 4])]
    (is (vector? r))
    (is (= 5 (count r)))
    (is (= [0 1 2 3 4] (vec r)))
    (is (= 0 (nth r 0)))
    (is (= 4 (nth r 4)))
    (is (= :nope (nth r 9 :nope)))
    (is (= 2 (get r 2)))
    (is (= :nope (get r 9 :nope)))
    (is (= [4 3 2 1 0] (vec (rseq r))))
    (is (= 10 (reduce + r)))
    (is (= [0 1 2 3 4 5] (vec (conj r 5))))
    (is (= [0 1 9 3 4] (vec (assoc r 2 9))))
    (is (= 4 (peek r)))
    (is (= [0 1 2 3] (vec (pop r))))
    (is (= [0 1 2 3 4] (seq r)))
    (is (= r [0 1 2 3 4]))
    (is (= [0 1 2 3 4] r))))

(deftest rope-indexed-associative-semantics
  (let [r (rope/rope [:a :b :c])]
    (is (contains? r 0))
    (is (contains? r 2))
    (is (not (contains? r 3)))
    (let [e (.entryAt ^clojure.lang.Associative r 1)]
      (is (= 1 (key e)))
      (is (= :b (val e))))
    (is (= [:a :b :c :d] (vec (assoc r 3 :d))))
    (is (thrown? IndexOutOfBoundsException (assoc r 4 :e)))))

(deftest rope-metadata-and-structural-ops
  (let [r (with-meta (rope/rope [0 1 2 3 4 5]) {:tag :rope})]
    (is (= {:tag :rope} (meta r)))
    (is (= {:tag :rope} (meta (empty r))))
    (let [[l rr] (rope/split-rope-at r 3)]
      (is (= [0 1 2] (vec l)))
      (is (= [3 4 5] (vec rr)))
      (is (= {:tag :rope} (meta l)))
      (is (= {:tag :rope} (meta rr))))
    (let [sv (rope/subrope r 2 5)]
      (is (= [2 3 4] (vec sv)))
      (is (= {:tag :rope} (meta sv))))
    (let [c (rope/concat-rope (rope/rope [0 1 2]) (rope/rope [3 4 5]))]
      (is (= [0 1 2 3 4 5] (vec c))))))

(deftest rope-empty-edge-cases
  (let [r (rope/rope)]
    (is (= [] (vec r)))
    (is (nil? (seq r)))
    (is (nil? (rseq r)))
    (is (nil? (peek r)))
    (is (thrown? IllegalStateException (pop r)))
    (is (= :nope (get r 0 :nope)))
    (is (thrown? IndexOutOfBoundsException (assoc r 1 :x)))))

(deftest rope-large-structural-operations
  (let [r (rope/rope (range 100000))]
    (is (= :x (nth (assoc r 75000 :x) 75000)))
    (let [[l rr] (rope/split-rope-at r 50000)]
      (is (= 50000 (count l)))
      (is (= 50000 (count rr)))
      (is (= 0 (nth l 0)))
      (is (= 50000 (nth rr 0))))
    (is (= 99999 (peek r)))
    (is (= 99998 (peek (pop r))))
    (is (= 12345 (nth r 12345)))
    (is (= :y (nth (assoc r 32768 :y) 32768)))
    (is (= (range 49990 50010) (vec (rope/subrope r 49990 50010))))))

(deftest rope-chunk-boundary-behavior
  (let [r (rope/rope (range 130))]
    (is (= 130 (count r)))
    (is (= (range 130) (vec r)))
    (is (= [(vec (range 64))
            (vec (range 64 128))
            (vec (range 128 130))]
          (vec (rope/rope-chunks r))))
    (is (= 3 (rope/chunk-count r)))
    (is (= (range 60 70) (vec (rope/subrope r 60 70))))
    (let [[l rr] (rope/split-rope-at r 64)]
      (is (= (range 64) (vec l)))
      (is (= (range 64 130) (vec rr))))
    (let [[l rr] (rope/split-rope-at r 65)]
      (is (= (range 65) (vec l)))
      (is (= (range 65 130) (vec rr))))
    (is (= (range 140)
          (vec (reduce conj r (range 130 140)))))))

(deftest rope-editing-operations
  (let [r (rope/rope (range 10))]
    (is (= [0 1 :a :b 2 3 4 5 6 7 8 9]
          (vec (rope/insert-rope-at r 2 [:a :b]))))
    (is (= [:a :b 0 1 2 3 4 5 6 7 8 9]
          (vec (rope/insert-rope-at r 0 (rope/rope [:a :b])))))
    (is (= [0 1 2 3 7 8 9]
          (vec (rope/remove-rope-range r 4 7))))
    (is (= [0 1 :x :y 5 6 7 8 9]
          (vec (rope/splice-rope r 2 5 [:x :y]))))
    (is (= (range 10) (vec (rope/remove-rope-range r 3 3))))
    (is (thrown? IndexOutOfBoundsException (rope/insert-rope-at r 11 [:x])))
    (is (thrown? IndexOutOfBoundsException (rope/remove-rope-range r -1 2)))
    (is (thrown? IndexOutOfBoundsException (rope/splice-rope r 4 2 [:x])))))

(deftest rope-normalization-and-chunk-iteration
  (let [tiny (reduce rope/concat-rope (map #(rope/rope [%]) (range 130)))]
    (is (= (range 130) (vec tiny)))
    (is (= [64 64 2] (mapv count (rope/rope-chunks tiny))))
    (is (= [2 64 64] (mapv count (rope/rope-chunks-reverse tiny))))))

(deftest rope-slice-view
  (let [r  (rope/rope (range 20))
        sv (rope/subrope r 5 15)]
    (is (= 10 (count sv)))
    (is (= (range 5 15) (vec sv)))
    (is (= 5 (nth sv 0)))
    (is (= 14 (nth sv 9)))
    (is (= :nope (nth sv 20 :nope)))
    (is (= [14 13 12 11 10 9 8 7 6 5] (vec (rseq sv))))
    (is (= 95 (reduce + sv)))
    (is (= {:tag :slice} (meta (with-meta sv {:tag :slice}))))
    (is (= [5 6 :x 8 9 10 11 12 13 14]
          (vec (assoc (rope/rope (vec sv)) 2 :x))))
    (is (= (range 5 15)
          (vec (rope/concat-rope sv (rope/rope [])))))
    (is (= (range 7 12)
          (vec (rope/subrope sv 2 7)))))) 
