(ns com.dean.ordered-collections.ordered-set-test
  (:require [clojure.core.reducers        :as r]
            [clojure.math.combinatorics   :as combo]
            [clojure.set                  :as set]
            [clojure.test                 :refer :all]
            [com.dean.ordered-collections.core  :refer :all]))

(set! *warn-on-reflection* true)

;; TODO: more coverage

(deftest simple-checks
  (let [x (ordered-set (shuffle (range 8)))
        y (ordered-set (shuffle (range 20)))
        z (ordered-set (shuffle (range 0 20 2)))]
    (is (= #{} (ordered-set)))
    (is (= #{} (disj (ordered-set) 1)))
    (is (= #{} (disj (ordered-set [1]) 1)))
    (is (set? (ordered-set)))
    (is (= #{0 1 2 3 4 5 6 7}     (conj x 5)))
    (is (= #{0 1 2 3 4 5 6 7 9}   (conj x 9)))
    (is (= #{-1 0 1 2 3 4 5 6 7}  (conj x -1)))
    (is (= #{1 2 3 4 5 6 7}       (disj x 0)))
    (is (= [9 0 1 2 3 4 5 6 7]    (cons 9 x)))
    (is (= 0 (first x)))
    (is (= 7 (last x)))
    (doseq [i (range 20)]
      (is (= i (nth y i)))
      (is (= i (y i)))
      (is (= i (get y i)))
      (is (= ::nope (get y (+ 100 i) ::nope)))
      (is (= i (.ceiling ^java.util.NavigableSet y i)))
      (is (= i (.floor ^java.util.NavigableSet y i)))
      (is (= (if (even? i) i (dec i)) (.floor ^java.util.NavigableSet z i)))
      (is (= i (->> y (drop i) first))))
    ;; subSet(from, to) returns elements >= from and < to (standard SortedSet semantics)
    (is (= #{3 4 5 6}  (.subSet ^java.util.SortedSet x 3 7)))))

(deftest set-algebra-checks
  (doseq [size [10 100 1000 10000 100000]]
    (let [x   (ordered-set (rest (shuffle (range size))))    ;; rest randomizes among runs
          y   (ordered-set (rest (shuffle (range (* 2 size)))))
          v   (ordered-set (rest (shuffle (range 0 (* 2 size) 7))))
          w   (ordered-set (rest (shuffle (range 0 size 3))))
          z   (ordered-set (rest (shuffle (range 0 size 2))))
          chk (fn [x y]
                (doseq [[theirs ours] [[set/intersection intersection]
                                       [set/union        union]
                                       [set/difference   difference]
                                       [set/subset?      subset]
                                       [set/superset?    superset]]]
                  (is (= (theirs (set x) (set y)) (ours x y)))
                  (is (= (theirs (set y) (set x)) (ours y x)))
                  (is (= (theirs (set x) (set y)) (ours x (set y))))
                  (is (= (theirs (set y) (set x)) (ours y (set x))))))]
      (doseq [xy (combo/combinations [x y v w z] 2)]
        (apply chk xy)))))

(deftest set-equivalence-checks
  (doseq [size [1 10 100 1000 10000 100000]]
    (is (= (range size)
           (seq (ordered-set (shuffle (range size))))))
    (is (= (range size)
           (seq (ordered-set-by < (shuffle (range size))))))
    (is (= (reverse (range size))
           (seq (ordered-set-by > (shuffle (range size))))))
    (is (not= (range (inc size))
              (seq (ordered-set (shuffle (range size))))))
    (is (= (range size)
           (ordered-set (shuffle (range size)))))
    (is (not= (range size)
              (ordered-set (shuffle (range (inc size))))))
    (is (= (ordered-set (shuffle (range size)))
           (set (range size))))
    (is (= (set (range size))
           (ordered-set (shuffle (range size)))))
    (is (not= (set (range 100000))
              (ordered-set (shuffle (range (inc size))))))
    (is (= (ordered-set (shuffle (range size)))
           (ordered-set (shuffle (range size)))))
    (is (not= (ordered-set (shuffle (range 1 (inc size))))
              (ordered-set (shuffle (range size)))))))

(deftest sets-of-various-size-and-element-types
  (doseq [size [1 10 100 1000 10000 100000 250000 500000]
          f    [identity str gensym
                #(java.util.Date. (long %))
                (fn [_] (java.util.UUID/randomUUID))]]
    (let [data (mapv f (shuffle (range size)))
          this (ordered-set data)
          that (apply sorted-set data)
          afew (take 1000 data)]
      (is (= that this))
      (is (= that (into this afew)))
      (is (= (apply disj that afew) (apply disj this afew)))
      (is (= (seq this) (seq that)))
      (is (= (count this) (count that)))
      (is (every? #(= (nth this %) (->> that (drop %) first))
                  (take 10 (repeatedly #(rand-int size)))))
      (is (every? #(= (this %) (that %)) afew)))))

(deftest foldable-reducible-collection-check
  (doseq [size  [1 10 100 1000 10000 100000 250000 500000 1000000]
          chunk [1 10 100 1000]]
    (let [data (shuffle (range size))
          sum  (reduce + data)
          this (ordered-set data)]
      (is (= sum (r/fold chunk + + this)))
      (is (= sum (reduce + this))))))
