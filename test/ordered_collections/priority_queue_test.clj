(ns ordered-collections.priority-queue-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.core.reducers :as r]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [ordered-collections.test-utils :as tu]
            [ordered-collections.core :as oc]))

(defn- pop-all
  [pq]
  (loop [pq pq, acc []]
    (if-let [x (peek pq)]
      (recur (pop pq) (conj acc x))
      acc)))

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

  (testing "Multiple elements - max heap via priority-queue-by"
    (let [pq (oc/priority-queue-by > [[3 :c] [1 :a] [5 :e]])]
      (is (= [5 :e] (oc/peek-min pq)))
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

(deftest priority-queue-comparator-semantics
  (testing "peek-min/peek-max are relative to queue order, not numeric names"
    (let [pq (oc/priority-queue-by > [[1 :low] [3 :high] [2 :mid]])]
      (is (= [3 :high] (peek pq)))
      (is (= [3 :high] (oc/peek-min pq)))
      (is (= [1 :low] (oc/peek-max pq)))
      (is (= [[3 :high] [2 :mid] [1 :low]] (vec (seq pq)))))))

(deftest priority-queue-equal-priority-endpoints
  (testing "equal priorities are stable in forward queue order"
    (let [pq (oc/priority-queue [[1 :first] [1 :second] [1 :third]])]
      (is (= [1 :first] (peek pq)))
      (is (= [1 :first] (oc/peek-min pq)))
      (is (= [1 :third] (oc/peek-max pq)))
      (is (= [[1 :first] [1 :second] [1 :third]] (vec (seq pq))))
      (is (= [[1 :third] [1 :second] [1 :first]] (vec (rseq pq)))))))

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
      (is (= [8 :h] (nth pq 4)))
      (is (= :nf (nth pq 5 :nf))))))

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
      (is (= pq1 pq2))
      (is (= pq1 [[1 :a] [2 :b] [3 :c]])))))

(deftest priority-queue-stability
  (testing "stable ordering for equal priorities"
    (let [pq (oc/priority-queue [[1 :first] [1 :second] [1 :third]])]
      ;; Elements with same priority should maintain insertion order
      (is (= [[1 :first] [1 :second] [1 :third]] (vec (seq pq)))))))

(deftest priority-queue-ordering-through-pop
  (testing "repeated pop returns elements in queue order"
    (doseq [pairs [[[3 :c] [1 :a] [4 :d] [1 :a2] [2 :b]]
                   [[5 :e] [5 :e2] [1 :a] [3 :c] [3 :c2] [2 :b]]
                   [[9 :i] [7 :g] [8 :h] [1 :a] [2 :b] [6 :f]]]]
      (let [pq (oc/priority-queue pairs)
            expected (tu/priority-queue-order pairs)]
        (is (= expected (pop-all pq)))))))

(deftest priority-queue-meta
  (testing "metadata round-trips through with-meta and empty"
    (let [pq (with-meta (oc/priority-queue [[1 :a] [2 :b]]) {:tag :pq})]
      (is (= {:tag :pq} (meta pq)))
      (is (= {:tag :pq} (meta (empty pq)))))))

(defspec prop-priority-queue-pop-order 100
  (prop/for-all [pairs tu/gen-priority-pairs]
    (let [pq (oc/priority-queue pairs)
          expected (tu/priority-queue-order pairs)]
      (= expected (pop-all pq)))))

(defspec prop-priority-queue-pop-order-descending 100
  (prop/for-all [pairs tu/gen-priority-pairs]
    (let [pq (oc/priority-queue-by > pairs)
          expected (tu/priority-queue-order pairs true)]
      (= expected (pop-all pq)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructor Variants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest priority-queue-by-test
  (testing "priority-queue-by with predicate"
    (let [pq (oc/priority-queue-by > [[1 :a] [3 :c] [2 :b]])]
      (is (= [3 :c] (peek pq)))
      (is (= [[3 :c] [2 :b] [1 :a]] (vec (seq pq))))))
  (testing "priority-queue-by with empty pairs"
    (let [pq (oc/priority-queue-by > [])]
      (is (= 0 (count pq)))
      (is (nil? (peek pq))))))

(deftest priority-queue-with-test
  (testing "priority-queue-with explicit Comparator"
    (let [pq (oc/priority-queue-with oc/long-compare [[3 :c] [1 :a] [2 :b]])]
      (is (= [1 :a] (peek pq)))
      (is (= [[1 :a] [2 :b] [3 :c]] (vec (seq pq))))))
  (testing "empty priority-queue-with"
    (let [pq (oc/priority-queue-with oc/string-compare)]
      (is (= 0 (count pq)))
      (is (nil? (peek pq)))))
  (testing "priority-queue-with prints opaque for non-default comparator"
    (let [cmp (oc/compare-by >)
          pq  (oc/priority-queue-with cmp [[1 :a]])]
      (is (str/starts-with? (pr-str pq) "#<PriorityQueue")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Non-Numeric Priorities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest priority-queue-string-priorities
  (let [pq (oc/priority-queue [["beta" :b] ["alpha" :a] ["gamma" :g]])]
    (testing "construction and seq"
      (is (= [["alpha" :a] ["beta" :b] ["gamma" :g]] (vec (seq pq)))))
    (testing "peek/pop cycle"
      (is (= ["alpha" :a] (peek pq)))
      (is (= ["beta" :b] (peek (pop pq))))
      (is (= ["gamma" :g] (oc/peek-max pq))))
    (testing "rseq"
      (is (= [["gamma" :g] ["beta" :b] ["alpha" :a]] (vec (rseq pq)))))
    (testing "push"
      (let [pq2 (oc/push pq "aardvark" :aa)]
        (is (= ["aardvark" :aa] (peek pq2)))
        (is (= 4 (count pq2)))))
    (testing "reduce"
      (is (= #{"alpha" "beta" "gamma"}
             (reduce (fn [acc [p _]] (conj acc p)) #{} pq)))))
  (testing "duplicate string priorities preserve insertion order"
    (let [pq (oc/priority-queue [["x" :first] ["x" :second] ["x" :third]])]
      (is (= 3 (count pq)))
      (is (= [["x" :first] ["x" :second] ["x" :third]] (vec (seq pq))))
      (is (= ["x" :first] (oc/peek-min pq)))
      (is (= ["x" :third] (oc/peek-max pq)))
      (is (= [["x" :second] ["x" :third]] (vec (seq (oc/pop-min pq))))))))

(deftest priority-queue-keyword-priorities
  (let [pq (oc/priority-queue [[:b 2] [:a 1] [:c 3]])]
    (testing "construction and seq"
      (is (= [[:a 1] [:b 2] [:c 3]] (vec (seq pq)))))
    (testing "pop-min/pop-max"
      (is (= [:b 2] (peek (pop pq))))
      (is (= [:b 2] (oc/peek-max (oc/pop-max pq)))))
    (testing "duplicate keyword priorities"
      (let [pq2 (oc/push-all (oc/priority-queue)
                              [[:p :first] [:p :second] [:q :only]])]
        (is (= 3 (count pq2)))
        (is (= [:p :first] (peek pq2)))
        (is (= [:q :only] (oc/peek-max pq2)))))))

(deftest priority-queue-date-priorities
  (let [t1 (java.time.Instant/parse "2024-01-01T00:00:00Z")
        t2 (java.time.Instant/parse "2024-06-15T00:00:00Z")
        t3 (java.time.Instant/parse "2024-12-31T00:00:00Z")
        pq (oc/priority-queue [[t2 :mid] [t3 :late] [t1 :early]])]
    (testing "construction and ordering"
      (is (= [t1 :early] (peek pq)))
      (is (= [t3 :late] (oc/peek-max pq)))
      (is (= [[t1 :early] [t2 :mid] [t3 :late]] (vec (seq pq)))))
    (testing "pop cycle"
      (is (= [t2 :mid] (peek (pop pq))))
      (is (= [t2 :mid] (oc/peek-max (oc/pop-max pq)))))
    (testing "push and count"
      (let [t0 (java.time.Instant/parse "2023-01-01T00:00:00Z")
            pq2 (oc/push pq t0 :earliest)]
        (is (= 4 (count pq2)))
        (is (= [t0 :earliest] (peek pq2)))))
    (testing "duplicate date priorities"
      (let [pq2 (oc/priority-queue [[t1 :a] [t1 :b] [t2 :c]])]
        (is (= 3 (count pq2)))
        (is (= [t1 :a] (oc/peek-min pq2)))
        (is (= [t1 :b] (peek (pop pq2))))))))

(deftest priority-queue-tostring
  (testing "toString uses pr-str"
    (let [pq (oc/priority-queue [[1 :a] [2 :b]])]
      (is (= (pr-str pq) (str pq))))))

(deftest priority-queue-count-tracks-elements
  (testing "count is total elements, not distinct priorities"
    (let [pq (oc/priority-queue [[1 :a] [1 :b] [1 :c] [2 :d]])]
      (is (= 4 (count pq)))
      (is (= 3 (count (oc/pop-min pq))))
      (is (= 3 (count (oc/pop-max pq))))))
  (testing "count after push to existing priority"
    (let [pq (-> (oc/priority-queue [[1 :a]])
                 (oc/push 1 :b)
                 (oc/push 1 :c))]
      (is (= 3 (count pq))))))
