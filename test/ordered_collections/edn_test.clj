(ns ordered-collections.edn-test
  "Tests for EDN/tagged-literal round-tripping of ordered collections.

   Verifies that pr-str produces tagged literals that read-string can
   reconstruct into equivalent collections."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn  :as edn]
            [ordered-collections.core    :as oc]
            [ordered-collections.readers  :as readers]))


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
;; Range Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest range-map-tagged-literal-format
  (testing "pr-str produces #ordered/range-map tag"
    (let [rm (oc/range-map [[[0 10] :a] [[20 30] :b]])]
      (is (clojure.string/starts-with? (pr-str rm) "#ordered/range-map ")))))

(deftest range-map-round-trip
  (testing "read-string round-trip"
    (let [rm (oc/range-map [[[0 10] :a] [[20 30] :b]])]
      (is (= (set (seq rm)) (set (seq (round-trip rm)))))))
  (testing "clojure.edn round-trip"
    (let [rm (oc/range-map [[[0 10] :a] [[20 30] :b]])]
      (is (= (count rm) (count (edn-round-trip rm))))))
  (testing "empty range-map"
    (let [rm (oc/range-map)]
      (is (= rm (round-trip rm))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest priority-queue-tagged-literal-format
  (testing "pr-str produces #ordered/priority-queue tag"
    (let [pq (oc/priority-queue [[1 :a] [3 :c] [2 :b]])]
      (is (clojure.string/starts-with? (pr-str pq) "#ordered/priority-queue ")))))

(deftest priority-queue-round-trip
  (testing "read-string round-trip"
    (let [pq (oc/priority-queue [[1 :a] [3 :c] [2 :b]])]
      (is (= (vec (seq pq)) (vec (seq (round-trip pq)))))))
  (testing "clojure.edn round-trip"
    (let [pq (oc/priority-queue [[1 :a] [3 :c] [2 :b]])]
      (is (= (vec (seq pq)) (vec (seq (edn-round-trip pq)))))))
  (testing "empty priority-queue"
    (let [pq (oc/priority-queue [])]
      (is (= 0 (count (round-trip pq)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Multiset
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-multiset-tagged-literal-format
  (testing "pr-str produces #ordered/multiset tag"
    (let [ms (oc/ordered-multiset [3 1 2 1])]
      (is (clojure.string/starts-with? (pr-str ms) "#ordered/multiset ")))))

(deftest ordered-multiset-round-trip
  (testing "read-string round-trip"
    (let [ms (oc/ordered-multiset [3 1 4 1 5])]
      (is (= (vec (seq ms)) (vec (seq (round-trip ms)))))))
  (testing "clojure.edn round-trip"
    (let [ms (oc/ordered-multiset [1 2 2 3])]
      (is (= (vec (seq ms)) (vec (seq (edn-round-trip ms)))))))
  (testing "empty multiset"
    (let [ms (oc/ordered-multiset [])]
      (is (= 0 (count (round-trip ms)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom Comparator Guarding
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest custom-comparator-prints-unreadable
  (testing "ordered-set with custom comparator prints unreadable form"
    (let [s (oc/ordered-set-by > [3 1 2])]
      (is (clojure.string/starts-with? (pr-str s) "#<OrderedSet "))))
  (testing "ordered-map with custom comparator prints unreadable form"
    (let [m (oc/ordered-map-by > [[3 :c] [1 :a] [2 :b]])]
      (is (clojure.string/starts-with? (pr-str m) "#<OrderedMap "))))
  (testing "priority-queue with custom comparator prints unreadable form"
    (let [pq (oc/priority-queue-by > [[1 :a] [3 :c]])]
      (is (clojure.string/starts-with? (pr-str pq) "#<PriorityQueue "))))
  (testing "ordered-multiset with custom comparator prints unreadable form"
    (let [ms (oc/ordered-multiset-by > [3 1 2])]
      (is (clojure.string/starts-with? (pr-str ms) "#<OrderedMultiset ")))))

(deftest unreadable-forms-throw-on-read
  (testing "unreadable forms cannot be read back"
    (let [s (oc/ordered-set-by > [3 1 2])]
      (is (thrown? Exception (read-string (pr-str s)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Non-Round-Trippable Types (always unreadable)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest non-round-trippable-types-print-unreadable
  (testing "FuzzySet prints unreadable form"
    (let [fs (oc/fuzzy-set [1 5 10 20])]
      (is (clojure.string/starts-with? (pr-str fs) "#<FuzzySet "))))
  (testing "FuzzyMap prints unreadable form"
    (let [fm (oc/fuzzy-map {1 :a 5 :b 10 :c})]
      (is (clojure.string/starts-with? (pr-str fm) "#<FuzzyMap "))))
  (testing "SegmentTree prints unreadable form"
    (let [st (oc/segment-tree + 0 {0 10 1 20 2 30})]
      (is (clojure.string/starts-with? (pr-str st) "#<SegmentTree ")))))


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
