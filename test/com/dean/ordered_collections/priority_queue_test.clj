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
    (let [pq (oc/priority-queue [42])]
      (is (= 1 (count pq)))
      (is (= 42 (peek pq)))
      (is (= 0 (count (pop pq))))))

  (testing "Multiple elements - min heap"
    (let [pq (oc/priority-queue [3 1 4 1 5 9 2 6])]
      (is (= 8 (count pq)))
      (is (= 1 (peek pq)))
      (is (= [1 1 2 3 4 5 6 9] (seq pq)))))

  (testing "Multiple elements - max heap"
    (let [pq (oc/priority-queue [3 1 4 1 5] :comparator >)]
      (is (= 5 (peek pq)))
      (is (= [5 4 3 1 1] (seq pq))))))

(deftest priority-queue-push-pop
  (testing "Push with priority"
    (let [pq (-> (oc/priority-queue [])
                 (oc/push 5 :five)
                 (oc/push 2 :two)
                 (oc/push 8 :eight)
                 (oc/push 1 :one))]
      (is (= 4 (count pq)))
      (is (= :one (peek pq)))
      (is (= [1 :one] (oc/peek-with-priority pq)))))

  (testing "Pop sequence"
    (let [pq (oc/priority-queue [5 2 8 1 3])]
      (is (= 1 (peek pq)))
      (let [pq2 (pop pq)]
        (is (= 2 (peek pq2)))
        (let [pq3 (pop pq2)]
          (is (= 3 (peek pq3)))))))

  (testing "Push-all"
    (let [pq (oc/push-all (oc/priority-queue [])
                          [[3 :c] [1 :a] [2 :b]])]
      (is (= 3 (count pq)))
      (is (= :a (peek pq))))))

(deftest priority-queue-max-operations
  (testing "peek-max and pop-max"
    (let [pq (oc/priority-queue [3 1 4 1 5 9 2 6])]
      (is (= 9 (oc/peek-max pq)))
      (let [pq2 (oc/pop-max pq)]
        (is (= 7 (count pq2)))
        (is (= 6 (oc/peek-max pq2)))))))

(deftest priority-queue-reduce
  (testing "reduce"
    (let [pq (oc/priority-queue [1 2 3 4 5])]
      (is (= 15 (reduce + pq)))
      (is (= 120 (reduce * pq)))))

  (testing "reduce with r/fold"
    (let [pq (oc/priority-queue (range 1000))]
      (is (= (reduce + (range 1000)) (r/fold + pq))))))

(deftest priority-queue-nth
  (testing "nth access"
    (let [pq (oc/priority-queue [5 2 8 1 3])]
      (is (= 1 (nth pq 0)))
      (is (= 2 (nth pq 1)))
      (is (= 3 (nth pq 2)))
      (is (= 5 (nth pq 3)))
      (is (= 8 (nth pq 4))))))

(deftest priority-queue-conj
  (testing "conj (uses value as priority)"
    (let [pq (-> (oc/priority-queue [])
                 (conj 3)
                 (conj 1)
                 (conj 4))]
      (is (= 3 (count pq)))
      (is (= 1 (peek pq))))))

(deftest priority-queue-equality
  (testing "equality"
    (let [pq1 (oc/priority-queue [1 2 3])
          pq2 (oc/priority-queue [3 1 2])]
      (is (= pq1 pq2)))))
