(ns com.dean.ordered-collections.ordered-map-test
  (:require [clojure.test                :refer :all]
            [com.dean.ordered-collections.core :refer [ordered-map ordered-map-by]])
  (:import  [java.util UUID]))

(set! *warn-on-reflection* true)


;; TODO: more

(deftest smoke-check
  (is (= {} (ordered-map)))
  (is (map? (ordered-map))) ;; => true
  (is (= {:a 4, :b 5, :x 1, :y 2, :z 3}
          (ordered-map {:x 1 :y 2 :z 3 :a 4 :b 5})))
  (is (= [[:a 4] [:b 5] [:x 1] [:y 2] [:z 3]]
         (seq (ordered-map {:x 1 :y 2 :z 3 :a 4 :b 5}))))
  (is (= {:a 4, :b 5, :x 1, :y 2, :z 3}
         (assoc (ordered-map) :x 1 :y 2 :z 3 :a 4 :b 5)))
  (is (= [[1 "a"] [2 "b"] [3 "c"] [4 "d"]]
         (seq (ordered-map [[2 "b"] [3 "c"] [1 "a"] [4 "d"]]))))
  (is (= {:a "a", :b "b", :c "c"}
         (-> (ordered-map) (assoc :b "b") (assoc :a "a") (assoc :c "c"))))
  (is (= {:b "b", :c "c"}
         (-> (ordered-map) (assoc :b "b") (assoc :a "a") (assoc :c "c")
             (dissoc :a))))
  (is (= "c" ((ordered-map {:a "a", :b "b", :c "c", :d "d"}) :c)))
  (is (= ::not-found
         ((ordered-map {:a "a", :b "b", :c "c", :d "d"}) :z ::not-found))))

(defn random-entry []
  (vector (UUID/randomUUID) (UUID/randomUUID)))

(defn random-map
  ([size]
   (random-map (sorted-map) size))
  ([this size]
   (into this (repeatedly size random-entry))))

(deftest map-equivalence-check
  (doseq [size [10 100 1000 10000 100000 500000]]
    (let [s     (random-map size)
          t     (random-map 1000)
          x     (ordered-map s)
          [k v] (random-entry)]
      (is (= s x))
      (is (= (count s) (count x)))
      (is (= (reverse s) (reverse x)))
      (is (= (seq s) (seq x)))
      (is (= (keys s) (keys x)))
      (is (= (vals s) (vals x)))
      (is (= (vals s) (map x (keys s))))
      (is (= nil (x k)))
      (is (= ::nope (x k ::nope)))
      (is (= v ((assoc x k v) k)))
      (is (= (assoc s k v) (assoc x k v)))
      (is (= s (-> x (assoc k v) (dissoc k))))
      (is (= (into s t) (into x t)))
      (is (= (into s t) (-> x (into t) (into t)))))))
