(ns ordered-collections.ordered-map-test
  (:require [clojure.string              :as str]
            [clojure.test                :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ordered-collections.core :refer [general-compare
                                                       ordered-map ordered-map-by
                                                       ordered-map-with
                                                       long-ordered-map
                                                       ordered-merge-with assoc-new]]
            [ordered-collections.test-utils :as tu])
  (:import  [java.util UUID]))


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

(deftest assoc-new-test
  (testing "assoc-new adds new key"
    (let [m (ordered-map {:a 1 :b 2})]
      (is (= {:a 1 :b 2 :c 3} (assoc-new m :c 3)))))

  (testing "assoc-new returns original on existing key"
    (let [m (ordered-map {:a 1 :b 2})]
      (is (identical? m (assoc-new m :a 99)))
      (is (= {:a 1 :b 2} (assoc-new m :a 99)))))

  (testing "assoc-new works on empty map"
    (let [m (ordered-map)]
      (is (= {:x 1} (assoc-new m :x 1)))))

  (testing "assoc-new preserves ordering"
    (let [m (ordered-map [[1 :a] [3 :c] [5 :e]])]
      (is (= [[1 :a] [2 :b] [3 :c] [5 :e]]
             (seq (assoc-new m 2 :b)))))))

(deftest general-comparator-supports-common-non-comparable-keys
  (require 'clojure.edn 'clojure.string)
  (testing "ordered-map accepts namespace keys"
    (let [pairs [[(the-ns 'clojure.string) :string]
                 [(the-ns 'clojure.core) :core]
                 [(the-ns 'clojure.edn) :edn]]
          om    (ordered-map-with general-compare (reverse pairs))]
      (is (= (into {} pairs) om))
      (is (= (sort-by (comp str first) pairs) (seq om)))))

  (testing "ordered-map-with general-compare accepts var keys"
    (let [pairs [[#'clojure.core/map :map]
                 [#'clojure.core/filter :filter]
                 [#'clojure.core/reduce :reduce]]
          om    (ordered-map-with general-compare (reverse pairs))]
      (is (= (into {} pairs) om))
      (is (= (sort-by (comp str first) pairs) (seq om))))))

(deftest general-compare-map-lookup-and-mutation
  (let [pairs (mapv (fn [ns] [ns (str ns)]) (all-ns))
        om    (ordered-map-with general-compare pairs)]
    (testing "get finds every key"
      (doseq [[k v] pairs]
        (is (= v (get om k)))))
    (testing "get returns not-found for absent key"
      (is (= ::miss (get om :nope ::miss))))
    (testing "assoc adds new entry"
      (let [om' (assoc om :new-key :new-val)]
        (is (= :new-val (get om' :new-key)))))
    (testing "dissoc removes key"
      (let [k (ffirst om)
            om' (dissoc om k)]
        (is (not (contains? om' k)))
        (is (= (dec (count om)) (count om')))))))

(deftest general-compare-map-comparable-match
  (testing "integer-keyed map via general-compare has same order as normal"
    (let [pairs (mapv (fn [i] [i (str i)]) (shuffle (range 500)))
          om    (ordered-map pairs)
          omg   (ordered-map-with general-compare pairs)]
      (is (= (vec om) (vec omg))))))

(deftest general-compare-map-prints-opaque
  (let [om (ordered-map-with general-compare [[1 :a] [2 :b]])]
    (is (str/starts-with? (pr-str om) "#<OrderedMap"))))

(deftest ordered-merge-with-test
  (testing "non-overlapping keys — simple union"
    (let [m1 (ordered-map [[1 :a] [3 :c]])
          m2 (ordered-map [[2 :b] [4 :d]])]
      (is (= {1 :a 2 :b 3 :c 4 :d}
             (ordered-merge-with (fn [k a b] a) m1 m2)))))

  (testing "overlapping keys — merge function called with correct argument order"
    (let [m1     (ordered-map [[1 10] [2 20] [3 30]])
          m2     (ordered-map [[2 200] [3 300] [4 400]])
          calls  (atom [])
          result (ordered-merge-with
                   (fn [k existing incoming]
                     (swap! calls conj [k existing incoming])
                     (+ existing incoming))
                   m1 m2)]
      ;; merge-fn receives (key, val-in-result, val-in-latter)
      (is (= {1 10, 2 220, 3 330, 4 400} result))
      (is (some #(= [2 20 200] %) @calls))
      (is (some #(= [3 30 300] %) @calls))))

  (testing "last-wins semantics"
    (let [m1 (ordered-map [[1 :first] [2 :first]])
          m2 (ordered-map [[2 :second] [3 :second]])
          m3 (ordered-map [[3 :third] [4 :third]])]
      (is (= {1 :first, 2 :second, 3 :third, 4 :third}
             (ordered-merge-with (fn [k a b] b) m1 m2 m3)))))

  (testing "first-wins semantics"
    (let [m1 (ordered-map [[1 :first] [2 :first]])
          m2 (ordered-map [[2 :second] [3 :second]])
          m3 (ordered-map [[3 :third] [4 :third]])]
      (is (= {1 :first, 2 :first, 3 :second, 4 :third}
             (ordered-merge-with (fn [k a b] a) m1 m2 m3)))))

  (testing "empty maps"
    (is (nil? (ordered-merge-with (fn [k a b] b))))
    (is (nil? (ordered-merge-with (fn [k a b] b) nil nil)))
    (let [m (ordered-map [[1 :a]])]
      (is (= m (ordered-merge-with (fn [k a b] b) m nil)))
      (is (= m (ordered-merge-with (fn [k a b] b) nil m)))))

  (testing "single map"
    (let [m (ordered-map [[1 :a] [2 :b]])]
      (is (= m (ordered-merge-with (fn [k a b] b) m)))))

  (testing "long-ordered-map preserves specialization through merge"
    (let [m1 (long-ordered-map {1 :a 2 :b})
          m2 (long-ordered-map {2 :B 3 :c})
          merged (ordered-merge-with (fn [k a b] b) m1 m2)
          root (.-root ^ordered_collections.types.ordered_map.OrderedMap merged)]
      (is (= {1 :a 2 :B 3 :c} merged))
      (is (instance? ordered_collections.kernel.node.LongKeyNode root)
          "merge result should preserve LongKeyNode specialization"))))

(defspec prop-merge-with-addition-is-commutative 100
  (prop/for-all [kvs1 tu/gen-int-map-entries
                 kvs2 tu/gen-int-map-entries]
    (let [m1 (tu/->om kvs1)
          m2 (tu/->om kvs2)
          add (fn [k a b] (+ a b))]
      (= (ordered-merge-with add m1 m2)
         (ordered-merge-with add m2 m1)))))

(defspec prop-merge-with-last-wins-equals-merge 100
  (prop/for-all [kvs1 tu/gen-int-map-entries
                 kvs2 tu/gen-int-map-entries]
    (let [m1 (tu/->om kvs1)
          m2 (tu/->om kvs2)]
      (= (into m1 m2)
         (ordered-merge-with (fn [k a b] b) m1 m2)))))
