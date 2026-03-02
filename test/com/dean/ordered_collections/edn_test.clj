(ns com.dean.ordered-collections.edn-test
  "Tests for EDN/tagged-literal round-tripping of ordered collections.

   Verifies that pr-str produces tagged literals that read-string can
   reconstruct into equivalent collections."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn  :as edn]
            [com.dean.ordered-collections.core    :as oc]
            [com.dean.ordered-collections.readers  :as readers]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn round-trip
  "pr-str then read-string — uses data_readers.clj on classpath."
  [coll]
  (read-string (pr-str coll)))

(defn edn-round-trip
  "pr-str then clojure.edn/read-string with explicit readers."
  [coll]
  (edn/read-string {:readers readers/readers} (pr-str coll)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-set-tagged-literal-format
  (testing "pr-str produces #ordered/set tag"
    (let [s (oc/ordered-set [3 1 2])]
      (is (= "#ordered/set [1 2 3]" (pr-str s))))))

(deftest ordered-set-round-trip
  (testing "read-string round-trip"
    (let [s (oc/ordered-set [3 1 4 1 5 9 2 6])]
      (is (= s (round-trip s)))
      (is (= (vec s) (vec (round-trip s))))))
  (testing "clojure.edn round-trip"
    (let [s (oc/ordered-set [10 20 30])]
      (is (= s (edn-round-trip s)))))
  (testing "empty set"
    (let [s (oc/ordered-set)]
      (is (= "#ordered/set []" (pr-str s)))
      (is (= s (round-trip s)))))
  (testing "single element"
    (let [s (oc/ordered-set [42])]
      (is (= s (round-trip s)))))
  (testing "keyword elements"
    (let [s (oc/ordered-set [:a :b :c :d])]
      (is (= s (round-trip s)))))
  (testing "string elements"
    (let [s (oc/ordered-set ["alpha" "beta" "gamma"])]
      (is (= s (round-trip s)))))
  (testing "large set"
    (let [s (oc/ordered-set (range 1000))]
      (is (= s (round-trip s))))))

(deftest ordered-set-read-from-literal
  (testing "read literal string directly"
    (let [s (read-string "#ordered/set [5 3 1]")]
      (is (= 3 (count s)))
      (is (= [1 3 5] (vec s)))
      (is (= 1 (first s))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-map-tagged-literal-format
  (testing "pr-str produces #ordered/map tag"
    (let [m (oc/ordered-map [[2 :b] [1 :a] [3 :c]])]
      (is (= "#ordered/map [[1 :a] [2 :b] [3 :c]]" (pr-str m))))))

(deftest ordered-map-round-trip
  (testing "read-string round-trip"
    (let [m (oc/ordered-map {:a 1 :b 2 :c 3})]
      (is (= m (round-trip m)))
      (is (= (vec m) (vec (round-trip m))))))
  (testing "clojure.edn round-trip"
    (let [m (oc/ordered-map [[1 :a] [2 :b]])]
      (is (= m (edn-round-trip m)))))
  (testing "empty map"
    (let [m (oc/ordered-map)]
      (is (= "#ordered/map []" (pr-str m)))
      (is (= m (round-trip m)))))
  (testing "single entry"
    (let [m (oc/ordered-map [[1 :only]])]
      (is (= m (round-trip m)))))
  (testing "complex values"
    (let [m (oc/ordered-map [[1 {:name "alice" :tags [:a :b]}]
                             [2 {:name "bob" :tags [:c]}]])]
      (is (= (vec m) (vec (round-trip m))))))
  (testing "large map"
    (let [m (oc/ordered-map (map #(vector % (* % %)) (range 1000)))]
      (is (= m (round-trip m))))))

(deftest ordered-map-read-from-literal
  (testing "read literal string directly"
    (let [m (read-string "#ordered/map [[1 :a] [2 :b]]")]
      (is (= 2 (count m)))
      (is (= :a (m 1)))
      (is (= :b (m 2))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest interval-set-tagged-literal-format
  (testing "pr-str produces #ordered/interval-set tag"
    (let [s (oc/interval-set [[1 5] [10 20]])]
      (is (clojure.string/starts-with? (pr-str s) "#ordered/interval-set ")))))

(deftest interval-set-round-trip
  (testing "read-string round-trip"
    (let [s (oc/interval-set [[1 5] [10 20] [3 8]])]
      (is (= (vec s) (vec (round-trip s))))))
  (testing "clojure.edn round-trip"
    (let [s (oc/interval-set [[1 5] [10 20]])]
      (is (= (vec s) (vec (edn-round-trip s))))))
  (testing "empty interval-set"
    (let [s (oc/interval-set)]
      (is (= s (round-trip s)))))
  (testing "single interval"
    (let [s (oc/interval-set [[1 10]])]
      (is (= (vec s) (vec (round-trip s))))))
  (testing "overlapping queries work after round-trip"
    (let [s (round-trip (oc/interval-set [[1 5] [3 8] [10 20]]))]
      (is (seq (oc/overlapping s 4))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest interval-map-tagged-literal-format
  (testing "pr-str produces #ordered/interval-map tag"
    (let [m (oc/interval-map {[1 5] :a [10 20] :b})]
      (is (clojure.string/starts-with? (pr-str m) "#ordered/interval-map ")))))

(deftest interval-map-round-trip
  (testing "read-string round-trip"
    (let [m (oc/interval-map {[1 5] :a [10 20] :b [3 8] :c})]
      (is (= (set (map (fn [[k v]] [(vec k) v]) m))
             (set (map (fn [[k v]] [(vec k) v]) (round-trip m)))))))
  (testing "clojure.edn round-trip"
    (let [m (oc/interval-map {[1 5] :a [10 20] :b})]
      (is (= (count m) (count (edn-round-trip m))))))
  (testing "empty interval-map"
    (let [m (oc/interval-map)]
      (is (= m (round-trip m)))))
  (testing "overlapping queries work after round-trip"
    (let [m (round-trip (oc/interval-map {[1 5] :a [3 8] :b}))]
      (is (seq (oc/overlapping m 4))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functional Verification After Round-Trip
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest operations-after-round-trip
  (testing "ordered-set operations work after EDN round-trip"
    (let [s (round-trip (oc/ordered-set [10 20 30 40 50]))]
      (is (= 30 (nth s 2)))
      (is (= 2 (oc/rank s 30)))
      (is (= 30 (oc/nearest s :<= 35)))
      (is (= 40 (oc/nearest s :>= 35)))
      (is (contains? (conj s 25) 25))
      (is (not (contains? (disj s 30) 30)))))
  (testing "ordered-map operations work after EDN round-trip"
    (let [m (round-trip (oc/ordered-map [[1 :a] [2 :b] [3 :c]]))]
      (is (= :b (m 2)))
      (is (= :d (get (assoc m 4 :d) 4)))
      (is (nil? (get (dissoc m 2) 2))))))
