(ns com.dean.ordered-collections.ordered-multiset-test
  (:require [clojure.test :refer :all]
            [clojure.core.reducers :as r]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [com.dean.ordered-collections.test-utils :as tu]
            [com.dean.ordered-collections.core :as oc]))

(deftest ordered-multiset-basic
  (testing "Empty multiset"
    (let [ms (oc/ordered-multiset [])]
      (is (= 0 (count ms)))
      (is (nil? (seq ms)))))

  (testing "Single element"
    (let [ms (oc/ordered-multiset [42])]
      (is (= 1 (count ms)))
      (is (= [42] (seq ms)))))

  (testing "Multiple distinct elements"
    (let [ms (oc/ordered-multiset [3 1 4 5 2])]
      (is (= 5 (count ms)))
      (is (= [1 2 3 4 5] (seq ms)))))

  (testing "Duplicate elements"
    (let [ms (oc/ordered-multiset [3 1 4 1 5 9 2 6 5 3 5])]
      (is (= 11 (count ms)))
      (is (= [1 1 2 3 3 4 5 5 5 6 9] (seq ms))))))

(deftest ordered-multiset-with-comparator
  (testing "Descending order"
    (let [ms (oc/ordered-multiset-by > [3 1 4 1 5])]
      (is (= [5 4 3 1 1] (seq ms))))))

(deftest ordered-multiset-conj-disj
  (testing "conj adds element"
    (let [ms (-> (oc/ordered-multiset [1 2 3])
                 (conj 2)
                 (conj 2))]
      (is (= 5 (count ms)))
      (is (= [1 2 2 2 3] (seq ms)))))

  (testing "disj-one removes one occurrence"
    (let [ms (oc/ordered-multiset [1 2 2 2 3])]
      (let [ms2 (oc/disj-one ms 2)]
        (is (= 4 (count ms2)))
        (is (= [1 2 2 3] (seq ms2))))))

  (testing "disj-one on non-existent"
    (let [ms (oc/ordered-multiset [1 2 3])]
      (is (= ms (oc/disj-one ms 99)))))

  (testing "disj-all removes all occurrences"
    (let [ms (oc/ordered-multiset [1 2 2 2 3])]
      (let [ms2 (oc/disj-all ms 2)]
        (is (= 2 (count ms2)))
        (is (= [1 3] (seq ms2)))))))

(deftest ordered-multiset-multiplicity
  (testing "multiplicity"
    (let [ms (oc/ordered-multiset [1 2 2 3 3 3 4])]
      (is (= 1 (oc/multiplicity ms 1)))
      (is (= 2 (oc/multiplicity ms 2)))
      (is (= 3 (oc/multiplicity ms 3)))
      (is (= 1 (oc/multiplicity ms 4)))
      (is (= 0 (oc/multiplicity ms 99))))))

(deftest ordered-multiset-distinct-elements
  (testing "distinct-elements"
    (let [ms (oc/ordered-multiset [3 1 4 1 5 9 2 6 5 3 5])]
      (is (= [1 2 3 4 5 6 9] (oc/distinct-elements ms))))))

(deftest ordered-multiset-frequencies
  (testing "element-frequencies"
    (let [ms (oc/ordered-multiset [1 2 2 3 3 3])]
      (is (= {1 1, 2 2, 3 3} (oc/element-frequencies ms))))))

(deftest ordered-multiset-lookup
  (testing "contains?"
    (let [^java.util.Collection ms (oc/ordered-multiset [1 2 3])]
      (is (.contains ms 1))
      (is (.contains ms 2))
      (is (not (.contains ms 99)))))

  (testing "get"
    (let [ms (oc/ordered-multiset [1 2 3])]
      (is (= 2 (ms 2)))
      (is (= 2 (get ms 2)))
      (is (nil? (ms 99)))
      (is (= :default (get ms 99 :default))))))

(deftest ordered-multiset-nth
  (testing "nth access"
    (let [ms (oc/ordered-multiset [3 1 4 1 5])]
      (is (= 1 (nth ms 0)))
      (is (= 1 (nth ms 1)))
      (is (= 3 (nth ms 2)))
      (is (= 4 (nth ms 3)))
      (is (= 5 (nth ms 4))))))

(deftest ordered-multiset-reduce
  (testing "reduce"
    (let [ms (oc/ordered-multiset [1 2 2 3])]
      (is (= 8 (reduce + ms)))))

  (testing "r/fold"
    (let [ms (oc/ordered-multiset (range 1000))]
      (is (= (reduce + (range 1000)) (r/fold + ms))))))

(deftest ordered-multiset-seq-operations
  (testing "seq"
    (let [ms (oc/ordered-multiset [3 1 2])]
      (is (= [1 2 3] (seq ms)))))

  (testing "rseq"
    (let [ms (oc/ordered-multiset [3 1 2])]
      (is (= [3 2 1] (rseq ms))))))

(deftest ordered-multiset-equality
  (testing "equality - same elements"
    (let [ms1 (oc/ordered-multiset [1 2 2 3])
          ms2 (oc/ordered-multiset [3 2 1 2])]
      (is (= ms1 ms2))))

  (testing "inequality - different multiplicities"
    (let [ms1 (oc/ordered-multiset [1 2 2 3])
          ms2 (oc/ordered-multiset [1 2 3])]
      (is (not= ms1 ms2)))))

(deftest ordered-multiset-empty
  (testing "empty"
    (let [ms (oc/ordered-multiset [1 2 3])]
      (is (= 0 (count (empty ms)))))))

(deftest ordered-multiset-collection-interface
  (testing "Collection methods"
    (let [^java.util.Collection ms (oc/ordered-multiset [1 2 3])]
      (is (not (.isEmpty ms)))
      (is (= 3 (.size ms)))
      (is (.contains ms 2))
      (is (.containsAll ms [1 2]))
      (is (not (.containsAll ms [1 2 99]))))))

(defspec prop-ordered-multiset-frequencies 100
  (prop/for-all [xs tu/gen-multiset-elems]
    (let [ms (oc/ordered-multiset xs)]
      (= (frequencies xs)
         (oc/element-frequencies ms)))))

(defspec prop-ordered-multiset-disj-one 100
  (prop/for-all [xs tu/gen-multiset-elems
                 x  gen/small-integer]
    (let [ms (oc/ordered-multiset xs)
          ms' (oc/disj-one ms x)
          expected-freqs (tu/multiset-disj-one-frequencies xs x)]
      (= expected-freqs (oc/element-frequencies ms')))))

(defspec prop-ordered-multiset-disj-all 100
  (prop/for-all [xs tu/gen-multiset-elems
                 x  gen/small-integer]
    (let [ms (oc/ordered-multiset xs)
          ms' (oc/disj-all ms x)
          expected (vec (sort (remove #(= x %) xs)))]
      (= expected (vec (seq ms'))))))
