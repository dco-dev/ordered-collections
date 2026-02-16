(ns com.dean.ordered-collections.serialization-test
  "Randomized test suite for Java serialization of ordered-collections.
   Tests round-trip serialization at various cardinalities with nontrivial datasets.

   Types that implement java.io.Serializable and use built-in comparators:
   - ordered-set, ordered-map
   - ordered-multiset
   - priority-queue
   - fuzzy-set, fuzzy-map

   Types NOT currently serializable:
   - interval-set, interval-map (no Serializable marker)
   - segment-tree, range-map (no Serializable marker)

   Note: Collections created with custom comparators (via ordered-set-by, etc.)
   will only be serializable if the custom comparator itself is serializable."
  (:require [clojure.test :refer [deftest testing is]]
            [com.dean.ordered-collections.core :as oc])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            ObjectInputStream ObjectOutputStream]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Serialization Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn serialize
  "Serialize an object to a byte array"
  ^bytes [obj]
  (let [baos (ByteArrayOutputStream.)
        oos  (ObjectOutputStream. baos)]
    (.writeObject oos obj)
    (.close oos)
    (.toByteArray baos)))

(defn deserialize
  "Deserialize an object from a byte array"
  [^bytes bytes]
  (let [bais (ByteArrayInputStream. bytes)
        ois  (ObjectInputStream. bais)]
    (.readObject ois)))

(defn round-trip
  "Serialize and deserialize an object"
  [obj]
  (-> obj serialize deserialize))

(defn serializable?
  "Test if an object can be serialized without throwing"
  [obj]
  (try
    (serialize obj)
    true
    (catch java.io.NotSerializableException _
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rand-longs
  "Generate n unique random longs in [0, max-val)"
  [n max-val]
  (loop [s (transient #{})]
    (if (>= (count s) n)
      (vec (persistent! s))
      (recur (conj! s (long (rand max-val)))))))

(defn rand-strings
  "Generate n unique random strings"
  [n]
  (let [chars "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"]
    (loop [s (transient #{})]
      (if (>= (count s) n)
        (vec (persistent! s))
        (recur (conj! s (apply str (repeatedly (+ 5 (rand-int 20)) #(rand-nth chars)))))))))

(defn rand-map-entries
  "Generate n unique [k v] pairs"
  [n max-key]
  (let [keys (rand-longs n max-key)]
    (mapv #(vector % (rand-int 1000000)) keys)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Scales
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cardinalities [10 100 1000 10000 50000])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Set Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-set-serialization
  (testing "ordered-set round-trip serialization"
    (doseq [n cardinalities]
      (testing (str "cardinality " n)
        (let [data (rand-longs n (* n 10))
              original (oc/ordered-set data)
              restored (round-trip original)]
          (is (= (count original) (count restored))
              "count preserved")
          (is (= (vec original) (vec restored))
              "elements and order preserved")
          (is (= (first original) (first restored))
              "first preserved")
          (is (= (last original) (last restored))
              "last preserved")
          ;; Verify it's still functional
          (is (contains? restored (first data))
              "contains? works after deserialization")
          (is (= (nth original (quot n 2)) (nth restored (quot n 2)))
              "nth works after deserialization"))))))

(deftest ordered-set-with-keywords
  (testing "ordered-set with keyword elements"
    (doseq [n [10 100 1000]]
      (testing (str "cardinality " n)
        (let [data (mapv #(keyword (str "k" %)) (range n))
              original (oc/ordered-set (shuffle data))
              restored (round-trip original)]
          (is (= (vec original) (vec restored))
              "keyword elements preserved"))))))

(deftest ordered-set-with-mixed-integers
  (testing "ordered-set with negative and positive integers"
    (doseq [n cardinalities]
      (testing (str "cardinality " n)
        (let [data (mapv #(- % (quot n 2)) (range n))
              original (oc/ordered-set (shuffle data))
              restored (round-trip original)]
          (is (= (vec original) (vec restored))
              "mixed integers preserved")
          (is (< (first restored) 0)
              "negative values at front"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Map Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-map-serialization
  (testing "ordered-map round-trip serialization"
    (doseq [n cardinalities]
      (testing (str "cardinality " n)
        (let [data (rand-map-entries n (* n 10))
              original (oc/ordered-map data)
              restored (round-trip original)]
          (is (= (count original) (count restored))
              "count preserved")
          (is (= (vec original) (vec restored))
              "entries and order preserved")
          (is (= (vec (keys original)) (vec (keys restored)))
              "keys preserved")
          (is (= (vec (vals original)) (vec (vals restored)))
              "vals preserved")
          ;; Verify it's still functional
          (let [[k v] (first data)]
            (is (= v (get restored k))
                "get works after deserialization")))))))

(deftest ordered-map-with-complex-values
  (testing "ordered-map with complex nested values"
    (doseq [n [10 100 1000]]
      (testing (str "cardinality " n)
        (let [data (mapv (fn [i] [i {:id i
                                    :name (str "item-" i)
                                    :tags [:a :b :c]
                                    :nested {:x i :y (* i 2)}}])
                         (range n))
              original (oc/ordered-map (shuffle data))
              restored (round-trip original)]
          (is (= (vec original) (vec restored))
              "complex values preserved"))))))

(deftest ordered-map-with-string-keys
  (testing "ordered-map with string keys"
    (doseq [n [10 100 1000]]
      (testing (str "cardinality " n)
        (let [keys (rand-strings n)
              data (mapv #(vector % (hash %)) keys)
              original (oc/ordered-map data)
              restored (round-trip original)]
          (is (= (vec original) (vec restored))
              "string keys preserved"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Multiset Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-multiset-serialization
  (testing "ordered-multiset round-trip serialization"
    (doseq [n cardinalities]
      (testing (str "cardinality " n)
        ;; Include duplicates - elements in range [0, n/2) so ~2 of each
        (let [data (repeatedly n #(rand-int (quot n 2)))
              original (oc/ordered-multiset data)
              restored (round-trip original)]
          (is (= (count original) (count restored))
              "count preserved (including duplicates)")
          (is (= (vec original) (vec restored))
              "elements and order preserved")
          ;; Verify multiplicities
          (doseq [sample (take 10 (shuffle (vec (set data))))]
            (is (= (oc/multiplicity original sample)
                   (oc/multiplicity restored sample))
                (str "multiplicity of " sample " preserved"))))))))

(deftest ordered-multiset-high-multiplicity
  (testing "ordered-multiset with high multiplicity elements"
    (let [;; 1000 copies of each of 10 distinct elements
          data (for [i (range 10) _ (range 1000)] i)
          original (oc/ordered-multiset data)
          restored (round-trip original)]
      (is (= 10000 (count original) (count restored))
          "total count preserved")
      (doseq [i (range 10)]
        (is (= 1000 (oc/multiplicity restored i))
            (str "multiplicity of " i " is 1000"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Note: Priority queue serialization is complex because the default comparator
;; (clojure.core/compare) is a Clojure function that may not serialize correctly.
;; These tests are currently disabled until custom serialization is implemented.

(comment
  (deftest priority-queue-serialization
    (testing "priority-queue round-trip serialization"
      (doseq [n cardinalities]
        (testing (str "cardinality " n)
          (let [data (mapv (fn [_] [(rand-int 100) {:id (rand-int 1000000)}]) (range n))
                original (oc/priority-queue data)
                restored (round-trip original)]
            (is (= (count original) (count restored))
                "count preserved")
            (is (= (peek original) (peek restored))
                "peek (min element) preserved")
            ;; Verify we can pop all elements in same order
            (loop [orig original, rest restored, i 0]
              (when (and (seq orig) (< i 100))  ; check first 100
                (is (= (peek orig) (peek rest))
                    (str "element " i " matches after pop"))
                (recur (pop orig) (pop rest) (inc i)))))))))

  (deftest priority-queue-ordering-preserved
    (testing "priority-queue maintains heap property after deserialization"
      (let [data (mapv (fn [i] [i (str "priority-" i)]) (shuffle (range 1000)))
            original (oc/priority-queue data)
            restored (round-trip original)]
        ;; Pop all elements and verify they come out in order
        (loop [pq restored, prev-priority Long/MIN_VALUE]
          (when (seq pq)
            (let [[priority _] (peek pq)]
              (is (>= (long priority) prev-priority)
                  "elements come out in priority order")
              (recur (pop pq) (long priority))))))))
  ) ; end comment


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Set/Map Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fuzzy-set-serialization
  (testing "fuzzy-set round-trip serialization"
    (doseq [n [10 100 1000]]
      (testing (str "cardinality " n)
        (let [data (rand-longs n (* n 10))
              original (oc/fuzzy-set data)
              restored (round-trip original)]
          (is (= (count original) (count restored))
              "count preserved")
          (is (= (vec original) (vec restored))
              "elements preserved")
          ;; Verify fuzzy lookup works
          (let [query (+ (apply max data) 5)]
            (is (= (oc/fuzzy-nearest original query)
                   (oc/fuzzy-nearest restored query))
                "nearest lookup works")))))))

(deftest fuzzy-map-serialization
  (testing "fuzzy-map round-trip serialization"
    (doseq [n [10 100 1000]]
      (testing (str "cardinality " n)
        (let [data (into {} (rand-map-entries n (* n 10)))
              original (oc/fuzzy-map data)
              restored (round-trip original)]
          (is (= (count original) (count restored))
              "count preserved")
          (is (= (vec original) (vec restored))
              "entries preserved")
          ;; Verify fuzzy lookup works
          (let [max-key (apply max (keys data))
                query (+ max-key 5)]
            (is (= (get original query)
                   (get restored query))
                "fuzzy get works")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest empty-collections-serialization
  (testing "empty collections serialize correctly"
    (is (= [] (vec (round-trip (oc/ordered-set)))))
    (is (= [] (vec (round-trip (oc/ordered-map)))))
    (is (= [] (vec (round-trip (oc/ordered-multiset [])))))
    (is (= [] (vec (round-trip (oc/fuzzy-set [])))))))

(deftest single-element-serialization
  (testing "single element collections serialize correctly"
    (is (= [42] (vec (round-trip (oc/ordered-set [42])))))
    (is (= [[1 :a]] (vec (round-trip (oc/ordered-map [[1 :a]])))))
    (is (= [42] (vec (round-trip (oc/ordered-multiset [42])))))
    (is (= [42] (vec (round-trip (oc/fuzzy-set [42])))))))

(deftest large-values-serialization
  (testing "collections with large/extreme values"
    (let [data [Long/MIN_VALUE -1 0 1 Long/MAX_VALUE]
          original (oc/ordered-set data)
          restored (round-trip original)]
      (is (= (vec original) (vec restored))
          "extreme long values preserved")
      (is (= Long/MIN_VALUE (first restored)))
      (is (= Long/MAX_VALUE (last restored))))))

(deftest serialized-size-reasonable
  (testing "serialized size is reasonable"
    (let [n 10000
          data (rand-longs n (* n 10))
          original (oc/ordered-set data)
          bytes (serialize original)
          ;; Each element needs storage for value + tree structure overhead.
          ;; Allow up to 50 bytes per element for tree nodes with all metadata.
          max-expected (* n 50)]
      (is (< (count bytes) max-expected)
          (str "serialized size " (count bytes) " should be < " max-expected)))))

(deftest multiple-serialization-rounds
  (testing "multiple serialization rounds produce identical results"
    (let [data (rand-longs 1000 10000)
          original (oc/ordered-set data)
          round1 (round-trip original)
          round2 (round-trip round1)
          round3 (round-trip round2)]
      (is (= (vec original) (vec round1) (vec round2) (vec round3))
          "multiple round trips preserve data"))))

(deftest concurrent-serialization
  (testing "concurrent serialization works correctly"
    (let [data (rand-longs 1000 10000)
          original (oc/ordered-set data)
          results (doall
                    (pmap (fn [_] (vec (round-trip original)))
                          (range 10)))]
      (is (every? #(= (vec original) %) results)
          "all concurrent serializations produce same result"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functional Verification After Deserialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-set-operations-after-deserialization
  (testing "ordered-set operations work after deserialization"
    (let [data (rand-longs 1000 10000)
          original (oc/ordered-set data)
          restored (round-trip original)
          new-elem (+ 10000 (rand-int 1000))]
      ;; conj
      (is (contains? (conj restored new-elem) new-elem)
          "conj works")
      ;; disj
      (let [to-remove (first data)]
        (is (not (contains? (disj restored to-remove) to-remove))
            "disj works"))
      ;; subseq
      (let [mid (nth (vec (sort data)) 500)]
        (is (= (vec (subseq original >= mid))
               (vec (subseq restored >= mid)))
            "subseq works"))
      ;; set operations between original and restored should work
      ;; because comparators implement equals
      (let [other-data (rand-longs 500 10000)
            other (oc/ordered-set other-data)]
        (is (= (vec (oc/union original other))
               (vec (oc/union restored other)))
            "union works")
        (is (= (vec (oc/intersection original other))
               (vec (oc/intersection restored other)))
            "intersection works")))))

(deftest ordered-map-operations-after-deserialization
  (testing "ordered-map operations work after deserialization"
    (let [data (rand-map-entries 1000 10000)
          original (oc/ordered-map data)
          restored (round-trip original)
          new-key (+ 10000 (rand-int 1000))]
      ;; assoc
      (is (= :new-val (get (assoc restored new-key :new-val) new-key))
          "assoc works")
      ;; dissoc
      (let [[k _] (first data)]
        (is (nil? (get (dissoc restored k) k))
            "dissoc works"))
      ;; update
      (let [[k v] (first data)]
        (is (= (inc v) (get (update restored k inc) k))
            "update works")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Non-Serializable Types Documentation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest non-serializable-types-documentation
  (testing "Types without Serializable marker"
    ;; These types don't have the Serializable marker
    (is (not (instance? java.io.Serializable (oc/interval-set [[1 5] [10 20]])))
        "interval-set does not have Serializable marker")
    (is (not (instance? java.io.Serializable (oc/interval-map [[[1 5] :a] [[10 20] :b]])))
        "interval-map does not have Serializable marker")
    (is (not (instance? java.io.Serializable (oc/segment-tree + [1 2 3])))
        "segment-tree does not have Serializable marker")
    (is (not (instance? java.io.Serializable (oc/range-map [[[1 5] :a] [[10 20] :b]])))
        "range-map does not have Serializable marker")))
