(ns com.dean.ordered-collections.priority-queue-test
  (:require [clojure.test :refer :all]
            [clojure.core.reducers :as r]
            [com.dean.ordered-collections.core :as oc]))

(deftest priority-queue-basic
  (testing "Empty queue"
    (let [pq (oc/priority-queue [])]
      (is (= 0 (count pq)))
      (is (nil? (peek pq)))
      (is (thrown? IllegalStateException (pop pq)))))

  (testing "Single element"
    (let [pq (oc/priority-queue [[42 :val]])]
      (is (= 1 (count pq)))
      (is (= [42 :val] (peek pq)))
      (is (= :val (oc/peek-min-val pq)))
      (is (= 0 (count (pop pq))))))

  (testing "Multiple elements - min heap"
    (let [pq (oc/priority-queue [[3 :c] [1 :a] [4 :d] [1 :a2] [5 :e]])]
      (is (= 5 (count pq)))
      (is (= [1 :a] (peek pq)))
      (is (= :a (oc/peek-min-val pq)))
      ;; seq returns [priority value] pairs in order
      (is (= [[1 :a] [1 :a2] [3 :c] [4 :d] [5 :e]] (vec (seq pq))))))

  (testing "Multiple elements - max heap"
    (let [pq (oc/priority-queue [[3 :c] [1 :a] [5 :e]] :comparator >)]
      (is (= [5 :e] (peek pq)))
      (is (= [[5 :e] [3 :c] [1 :a]] (vec (seq pq)))))))

(deftest priority-queue-push-pop
  (testing "Push with priority"
    (let [pq (-> (oc/priority-queue [])
                 (oc/push 5 :five)
                 (oc/push 2 :two)
                 (oc/push 8 :eight)
                 (oc/push 1 :one))]
      (is (= 4 (count pq)))
      (is (= [1 :one] (peek pq)))
      (is (= :one (oc/peek-min-val pq)))))

  (testing "Pop sequence"
    (let [pq (oc/priority-queue [[5 :e] [2 :b] [8 :h] [1 :a] [3 :c]])]
      (is (= [1 :a] (peek pq)))
      (let [pq2 (pop pq)]
        (is (= [2 :b] (peek pq2)))
        (let [pq3 (pop pq2)]
          (is (= [3 :c] (peek pq3)))))))

  (testing "Push-all"
    (let [pq (oc/push-all (oc/priority-queue [])
                          [[3 :c] [1 :a] [2 :b]])]
      (is (= 3 (count pq)))
      (is (= [1 :a] (peek pq)))
      (is (= :a (oc/peek-min-val pq))))))

(deftest priority-queue-max-operations
  (testing "peek-max and pop-max"
    (let [pq (oc/priority-queue [[3 :c] [1 :a] [9 :i] [6 :f]])]
      (is (= [9 :i] (oc/peek-max pq)))
      (is (= :i (oc/peek-max-val pq)))
      (let [pq2 (oc/pop-max pq)]
        (is (= 3 (count pq2)))
        (is (= [6 :f] (oc/peek-max pq2)))))))

(deftest priority-queue-reduce
  (testing "reduce over [priority value] pairs"
    (let [pq (oc/priority-queue [[1 10] [2 20] [3 30]])]
      ;; reduce receives [priority value] pairs
      (is (= 60 (reduce (fn [acc [_ v]] (+ acc v)) 0 pq)))
      (is (= 6 (reduce (fn [acc [p _]] (+ acc p)) 0 pq)))))

  (testing "reduce with r/fold"
    (let [pairs (vec (for [i (range 100)] [i (* i 10)]))
          pq (oc/priority-queue pairs)]
      (is (= (reduce + (map second pairs))
             (r/fold + (fn [acc [_ v]] (+ acc v)) pq))))))

(deftest priority-queue-nth
  (testing "nth access returns [priority value]"
    (let [pq (oc/priority-queue [[5 :e] [2 :b] [8 :h] [1 :a] [3 :c]])]
      (is (= [1 :a] (nth pq 0)))
      (is (= [2 :b] (nth pq 1)))
      (is (= [3 :c] (nth pq 2)))
      (is (= [5 :e] (nth pq 3)))
      (is (= [8 :h] (nth pq 4))))))

(deftest priority-queue-conj
  (testing "conj takes [priority value] pair"
    (let [pq (-> (oc/priority-queue [])
                 (conj [3 :c])
                 (conj [1 :a])
                 (conj [4 :d]))]
      (is (= 3 (count pq)))
      (is (= [1 :a] (peek pq))))))

(deftest priority-queue-equality
  (testing "equality"
    (let [pq1 (oc/priority-queue [[1 :a] [2 :b] [3 :c]])
          pq2 (oc/priority-queue [[3 :c] [1 :a] [2 :b]])]
      (is (= pq1 pq2)))))

(deftest priority-queue-stability
  (testing "stable ordering for equal priorities"
    (let [pq (oc/priority-queue [[1 :first] [1 :second] [1 :third]])]
      ;; Elements with same priority should maintain insertion order
      (is (= [[1 :first] [1 :second] [1 :third]] (vec (seq pq)))))))
