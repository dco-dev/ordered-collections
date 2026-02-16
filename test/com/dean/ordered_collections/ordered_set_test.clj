(ns com.dean.ordered-collections.ordered-set-test
  (:refer-clojure :exclude [split-at])
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
                                       [set/subset?      subset?]
                                       [set/superset?    superset?]]]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Split and Range Operations (data.avl compatible)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest split-key-test
  (testing "split-key on ordered-set"
    (let [s (ordered-set [1 2 3 4 5])]
      ;; Split at existing key
      (let [[left entry right] (split-key 3 s)]
        (is (= #{1 2} left))
        (is (= 3 entry))
        (is (= #{4 5} right)))
      ;; Split at non-existing key
      (let [[left entry right] (split-key 2.5 s)]
        (is (= #{1 2} left))
        (is (nil? entry))
        (is (= #{3 4 5} right)))
      ;; Split at first element
      (let [[left entry right] (split-key 1 s)]
        (is (= #{} left))
        (is (= 1 entry))
        (is (= #{2 3 4 5} right)))
      ;; Split at last element
      (let [[left entry right] (split-key 5 s)]
        (is (= #{1 2 3 4} left))
        (is (= 5 entry))
        (is (= #{} right)))))

  (testing "split-key on ordered-map"
    (let [m (ordered-map [[1 :a] [2 :b] [3 :c] [4 :d] [5 :e]])]
      ;; Split at existing key
      (let [[left entry right] (split-key 3 m)]
        (is (= {1 :a 2 :b} left))
        (is (= [3 :c] entry))
        (is (= {4 :d 5 :e} right)))
      ;; Split at non-existing key
      (let [[left entry right] (split-key 2.5 m)]
        (is (= {1 :a 2 :b} left))
        (is (nil? entry))
        (is (= {3 :c 4 :d 5 :e} right))))))

(deftest split-at-test
  (testing "split-at on ordered-set"
    (let [s (ordered-set [1 2 3 4 5])]
      ;; Split at middle
      (let [[left right] (split-at 2 s)]
        (is (= #{1 2} left))
        (is (= #{3 4 5} right)))
      ;; Split at 0
      (let [[left right] (split-at 0 s)]
        (is (= #{} left))
        (is (= #{1 2 3 4 5} right)))
      ;; Split at end
      (let [[left right] (split-at 5 s)]
        (is (= #{1 2 3 4 5} left))
        (is (= #{} right)))
      ;; Split at 1
      (let [[left right] (split-at 1 s)]
        (is (= #{1} left))
        (is (= #{2 3 4 5} right)))))

  (testing "split-at on ordered-map"
    (let [m (ordered-map [[1 :a] [2 :b] [3 :c] [4 :d] [5 :e]])]
      (let [[left right] (split-at 2 m)]
        (is (= {1 :a 2 :b} left))
        (is (= {3 :c 4 :d 5 :e} right))))))

(deftest subrange-test
  (testing "subrange with single test"
    (let [s (ordered-set (range 10))]
      (is (= #{0 1 2 3 4} (subrange s :< 5)))
      (is (= #{0 1 2 3 4 5} (subrange s :<= 5)))
      (is (= #{6 7 8 9} (subrange s :> 5)))
      (is (= #{5 6 7 8 9} (subrange s :>= 5)))))

  (testing "subrange with two tests"
    (let [s (ordered-set (range 10))]
      (is (= #{3 4 5 6} (subrange s :>= 3 :< 7)))
      (is (= #{3 4 5 6 7} (subrange s :>= 3 :<= 7)))
      (is (= #{4 5 6} (subrange s :> 3 :< 7)))
      (is (= #{4 5 6 7} (subrange s :> 3 :<= 7)))))

  (testing "subrange on ordered-map"
    (let [m (ordered-map (for [i (range 10)] [i (keyword (str i))]))]
      (is (= {3 :3 4 :4 5 :5 6 :6} (subrange m :>= 3 :< 7)))))

  (testing "subrange with strings"
    (let [s (ordered-set ["apple" "banana" "cherry" "date" "elderberry" "fig"])]
      (is (= #{"cherry" "date"} (subrange s :>= "cherry" :< "elderberry")))
      (is (= #{"apple" "banana"} (subrange s :< "cherry")))
      (is (= #{"elderberry" "fig"} (subrange s :> "date")))))

  (testing "subrange with keywords"
    (let [s (ordered-set [:a :b :c :d :e :f])]
      (is (= #{:b :c :d} (subrange s :> :a :<= :d)))
      (is (= #{:e :f} (subrange s :>= :e)))))

  (testing "subrange with java.time.LocalDate"
    (let [dates (ordered-set [(java.time.LocalDate/of 2024 1 1)
                              (java.time.LocalDate/of 2024 3 15)
                              (java.time.LocalDate/of 2024 6 30)
                              (java.time.LocalDate/of 2024 9 15)
                              (java.time.LocalDate/of 2024 12 31)])
          q2-start (java.time.LocalDate/of 2024 4 1)
          q3-end (java.time.LocalDate/of 2024 9 30)]
      (is (= #{(java.time.LocalDate/of 2024 6 30)
               (java.time.LocalDate/of 2024 9 15)}
             (subrange dates :>= q2-start :<= q3-end))))))

(deftest nearest-test
  (testing "nearest on ordered-set"
    (let [s (ordered-set [1 3 5 7 9])]
      ;; :< - greatest less than
      (is (= 5 (nearest s :< 6)))
      (is (= 5 (nearest s :< 5.5)))
      (is (nil? (nearest s :< 1)))
      ;; :< when key exists (predecessor test)
      (is (= 3 (nearest s :< 5)))   ; predecessor of 5 is 3
      (is (= 7 (nearest s :< 9)))   ; predecessor of 9 is 7
      ;; :<= - greatest less than or equal
      (is (= 5 (nearest s :<= 5)))
      (is (= 5 (nearest s :<= 6)))
      (is (= 1 (nearest s :<= 1)))
      ;; :> - least greater than
      (is (= 7 (nearest s :> 6)))
      (is (nil? (nearest s :> 9)))
      ;; :> when key exists (successor test)
      (is (= 7 (nearest s :> 5)))   ; successor of 5 is 7
      (is (= 3 (nearest s :> 1)))   ; successor of 1 is 3
      ;; :>= - least greater than or equal
      (is (= 5 (nearest s :>= 5)))
      (is (= 7 (nearest s :>= 6)))
      (is (= 9 (nearest s :>= 9)))))

  (testing "nearest on ordered-map"
    (let [m (ordered-map [[1 :a] [3 :b] [5 :c] [7 :d] [9 :e]])]
      (is (= [5 :c] (nearest m :< 6)))
      (is (= [3 :b] (nearest m :< 5)))   ; predecessor test
      (is (= [5 :c] (nearest m :<= 5)))
      (is (= [7 :d] (nearest m :> 6)))
      (is (= [7 :d] (nearest m :> 5)))   ; successor test
      (is (= [5 :c] (nearest m :>= 5)))))

  (testing "nearest with strings"
    (let [s (ordered-set ["apple" "banana" "cherry" "date" "elderberry"])]
      (is (= "cherry" (nearest s :< "coconut")))
      (is (= "cherry" (nearest s :<= "cherry")))
      (is (= "date" (nearest s :> "cherry")))
      (is (= "cherry" (nearest s :>= "cherry")))
      (is (= "banana" (nearest s :<= "blueberry")))
      (is (nil? (nearest s :< "apple")))
      (is (nil? (nearest s :> "elderberry")))))

  (testing "nearest with keywords"
    (let [s (ordered-set [:alpha :beta :gamma :delta :epsilon])]
      (is (= :delta (nearest s :< :epsilon)))
      (is (= :beta (nearest s :<= :beta)))
      (is (= :gamma (nearest s :>= :gamma)))))

  (testing "nearest with java.time.LocalDate"
    (let [dates (ordered-set [(java.time.LocalDate/of 2024 1 1)
                              (java.time.LocalDate/of 2024 3 15)
                              (java.time.LocalDate/of 2024 6 30)
                              (java.time.LocalDate/of 2024 12 31)])]
      (is (= (java.time.LocalDate/of 2024 3 15)
             (nearest dates :<= (java.time.LocalDate/of 2024 4 1))))
      (is (= (java.time.LocalDate/of 2024 6 30)
             (nearest dates :>= (java.time.LocalDate/of 2024 4 1))))))

  (testing "nearest with vectors (lexicographic)"
    (let [s (ordered-set [[1 1] [1 2] [2 1] [2 2] [3 1]])]
      (is (= [1 2] (nearest s :< [2 1])))
      (is (= [2 1] (nearest s :<= [2 1])))
      (is (= [2 2] (nearest s :> [2 1]))))))

(deftest rank-of-test
  (testing "rank-of on ordered-set"
    (let [s (ordered-set [10 20 30 40 50])]
      (is (= 0 (rank-of s 10)))
      (is (= 2 (rank-of s 30)))
      (is (= 4 (rank-of s 50)))
      (is (= -1 (rank-of s 25)))
      (is (= -1 (rank-of s 5)))
      (is (= -1 (rank-of s 100)))))

  (testing "rank-of on ordered-map"
    (let [m (ordered-map [[1 :a] [3 :b] [5 :c] [7 :d] [9 :e]])]
      (is (= 0 (rank-of m 1)))
      (is (= 2 (rank-of m 5)))
      (is (= 4 (rank-of m 9)))
      (is (= -1 (rank-of m 2)))
      (is (= -1 (rank-of m 10))))))
