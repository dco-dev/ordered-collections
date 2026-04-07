(ns ordered-collections.rope-test
  (:require [clojure.test :refer :all]
            [clojure.core.reducers :as r]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ordered-collections.core :as oc]
            [ordered-collections.kernel.rope :as ropetree]
            [ordered-collections.test-utils :as tu]))


(deftest rope-basic-semantics
  (let [r (oc/rope [0 1 2 3 4])]
    (is (vector? r))
    (is (= 5 (count r)))
    (is (= [0 1 2 3 4] r))
    (is (= 0 (nth r 0)))
    (is (= 4 (nth r 4)))
    (is (= :nope (nth r 9 :nope)))
    (is (= 2 (get r 2)))
    (is (= :nope (get r 9 :nope)))
    (is (= [4 3 2 1 0] (vec (rseq r))))
    (is (= 10 (reduce + r)))
    (is (= [0 1 2 3 4 5] (vec (conj r 5))))
    (is (= [0 1 9 3 4] (vec (assoc r 2 9))))
    (is (= 4 (peek r)))
    (is (= [0 1 2 3] (vec (pop r))))
    (is (= [0 1 2 3 4] (seq r)))
    (is (= r [0 1 2 3 4]))
    (is (= [0 1 2 3 4] r))))

(deftest rope-indexed-associative-semantics
  (let [r (oc/rope [:a :b :c])]
    (is (contains? r 0))
    (is (contains? r 2))
    (is (not (contains? r 3)))
    (let [e (.entryAt ^clojure.lang.Associative r 1)]
      (is (= 1 (key e)))
      (is (= :b (val e))))
    (is (= [:a :b :c :d] (vec (assoc r 3 :d))))
    (is (thrown? IndexOutOfBoundsException (assoc r 4 :e)))))

(deftest rope-metadata-and-structural-ops
  (let [r (with-meta (oc/rope [0 1 2 3 4 5]) {:tag :rope})]
    (is (= {:tag :rope} (meta r)))
    (is (= {:tag :rope} (meta (empty r))))
    (let [[l rr] (oc/rope-split r 3)]
      (is (= [0 1 2] (vec l)))
      (is (= [3 4 5] (vec rr)))
      (is (= {:tag :rope} (meta l)))
      (is (= {:tag :rope} (meta rr))))
    (let [sv (oc/rope-sub r 2 5)]
      (is (= [2 3 4] (vec sv)))
      (is (= {:tag :rope} (meta sv))))
    (let [c (oc/rope-concat (oc/rope [0 1 2]) (oc/rope [3 4 5]))]
      (is (= [0 1 2 3 4 5] (vec c))))))

(deftest rope-empty-edge-cases
  (let [r (oc/rope)]
    (is (= [] (vec r)))
    (is (nil? (seq r)))
    (is (nil? (rseq r)))
    (is (nil? (peek r)))
    (is (thrown? IllegalStateException (pop r)))
    (is (= :nope (get r 0 :nope)))
    (is (thrown? IndexOutOfBoundsException (assoc r 1 :x)))))

(deftest rope-large-structural-operations
  (let [r (oc/rope (range 100000))]
    (is (= :x (nth (assoc r 75000 :x) 75000)))
    (let [[l rr] (oc/rope-split r 50000)]
      (is (= 50000 (count l)))
      (is (= 50000 (count rr)))
      (is (= 0 (nth l 0)))
      (is (= 50000 (nth rr 0))))
    (is (= 99999 (peek r)))
    (is (= 99998 (peek (pop r))))
    (is (= 12345 (nth r 12345)))
    (is (= :y (nth (assoc r 32768 :y) 32768)))
    (is (= (range 49990 50010) (vec (oc/rope-sub r 49990 50010))))))

(deftest rope-chunk-boundary-behavior
  (let [n    (+ ropetree/+min-chunk-size+ 2)
        r    (oc/rope (range n))
        mid  (quot n 2)]
    (is (= n (count r)))
    (is (= (range n) (vec r)))
    (is (= 1 (oc/rope-chunk-count r)))
    (is (= (range mid (+ mid 10)) (vec (oc/rope-sub r mid (+ mid 10)))))
    (let [split mid]
      (let [[l rr] (oc/rope-split r split)]
        (is (= (range split) (vec l)))
        (is (= (range split n) (vec rr))))
      (let [[l rr] (oc/rope-split r (inc split))]
        (is (= (range (inc split)) (vec l)))
        (is (= (range (inc split) n) (vec rr)))))
    (is (= (range (+ n 10))
          (vec (reduce conj r (range n (+ n 10))))))))

(deftest rope-editing-operations
  (let [r (oc/rope (range 10))]
    (is (= [0 1 :a :b 2 3 4 5 6 7 8 9]
          (vec (oc/rope-insert r 2 [:a :b]))))
    (is (= [:a :b 0 1 2 3 4 5 6 7 8 9]
          (vec (oc/rope-insert r 0 (oc/rope [:a :b])))))
    (is (= [0 1 2 3 7 8 9]
          (vec (oc/rope-remove r 4 7))))
    (is (= [0 1 :x :y 5 6 7 8 9]
          (vec (oc/rope-splice r 2 5 [:x :y]))))
    (is (= (range 10) (vec (oc/rope-remove r 3 3))))
    (is (thrown? IndexOutOfBoundsException (oc/rope-insert r 11 [:x])))
    (is (thrown? IndexOutOfBoundsException (oc/rope-insert r 1.5 [:x])))
    (is (thrown? IndexOutOfBoundsException (oc/rope-remove r -1 2)))
    (is (thrown? IndexOutOfBoundsException (oc/rope-remove r 1.2 3)))
    (is (thrown? IndexOutOfBoundsException (oc/rope-splice r 4 2 [:x])))
    (is (thrown? IndexOutOfBoundsException (oc/rope-splice r 2 4.1 [:x])))
    (is (thrown? IndexOutOfBoundsException (oc/rope-split r 2.2)))
    (is (thrown? IndexOutOfBoundsException (oc/rope-sub r 1 3.5)))))

(deftest rope-concat-coercion
  (is (= [0 1 2 3]
        (vec (oc/rope-concat [0 1] (oc/rope [2 3])))))
  (is (= [0 1 2 3]
        (vec (oc/rope-concat (oc/rope [0 1]) [2 3]))))
  (let [left (with-meta (oc/rope [0 1]) {:tag :left})]
    (is (= {:tag :left}
          (meta (oc/rope-concat left [2 3]))))))

(deftest rope-normalization-and-chunk-iteration
  (let [tiny (reduce oc/rope-concat (map #(oc/rope [%]) (range 130)))]
    (is (= (range 130) (vec tiny)))
    (is (= [130] (mapv count (oc/rope-chunks tiny))))
    (is (= [130] (mapv count (oc/rope-chunks-reverse tiny))))))

(deftest rope-sub-view
  (let [r  (oc/rope (range 20))
        sv (oc/rope-sub r 5 15)]
    (is (= 10 (count sv)))
    (is (= (range 5 15) (vec sv)))
    (is (= 5 (nth sv 0)))
    (is (= 14 (nth sv 9)))
    (is (= :nope (nth sv 20 :nope)))
    (is (= [14 13 12 11 10 9 8 7 6 5] (vec (rseq sv))))
    (is (= 95 (reduce + sv)))
    (is (= {:tag :slice} (meta (with-meta sv {:tag :slice}))))
    (is (= [5 6 :x 8 9 10 11 12 13 14]
          (vec (assoc (oc/rope (vec sv)) 2 :x))))
    (is (= (range 5 15)
          (vec (oc/rope-concat sv (oc/rope [])))))
    (is (= [0 1 2 3 4 5 6 7 8 9 10 11]
          (vec (oc/rope-concat (oc/rope (range 5))
                 (oc/rope-sub (oc/rope (range 20)) 5 12)))))
    (is (= [5 6 :a :b 10 11 12 13 14]
          (vec (oc/rope-splice sv 2 5 [:a :b]))))
    (is (= (range 7 12)
          (vec (oc/rope-sub sv 2 7)))))) 

(deftest rope-chunk-shape-invariants
  (let [tiny  (reduce oc/rope-concat (map #(oc/rope [%]) (range 1000)))
        sizes (mapv count (oc/rope-chunks tiny))]
    (is (= (range 1000) (vec tiny)))
    (is (ropetree/invariant-valid? (.-root ^ordered_collections.types.rope.Rope tiny))
      "CSI must hold after 1000 single-element concats")
    ;; All chunks except the last must be >= min
    (is (every? #(>= % ropetree/+min-chunk-size+) (butlast sizes)))
    ;; No chunk exceeds target
    (is (every? #(<= % ropetree/+target-chunk-size+) sizes))
    ;; Reasonable chunk count: at most ceil(n/min) chunks
    (is (<= (count sizes) (inc (quot 1000 ropetree/+min-chunk-size+))))))

(defn- clamp-index
  [n i]
  (min n (max 0 i)))

(defn- apply-rope-op
  [r [op a b c]]
  (case op
    :insert
    (let [i (clamp-index (count r) a)]
      (oc/rope-insert r i b))

    :insert-many
    (let [i (clamp-index (count r) a)]
      (oc/rope-insert r i b))

    :remove
    (let [n (count r)
          lo (clamp-index n a)
          hi (clamp-index n b)]
      (oc/rope-remove r lo hi))

    :splice
    (let [n (count r)
          lo (clamp-index n a)
          hi (clamp-index n b)]
      (oc/rope-splice r lo hi c))

    :subrope
    (let [n (count r)
          lo (clamp-index n a)
          hi (clamp-index n b)]
      (oc/rope-sub r lo hi))))

(defn- apply-vector-op
  [v [op a b c]]
  (case op
    :insert
    (let [i (clamp-index (count v) a)]
      (vec (concat (subvec v 0 i) b (subvec v i))))

    :insert-many
    (let [i (clamp-index (count v) a)]
      (vec (concat (subvec v 0 i) b (subvec v i))))

    :remove
    (let [n  (count v)
          lo (clamp-index n a)
          hi (clamp-index n b)]
      (vec (concat (subvec v 0 lo) (subvec v hi))))

    :splice
    (let [n  (count v)
          lo (clamp-index n a)
          hi (clamp-index n b)]
      (vec (concat (subvec v 0 lo) c (subvec v hi))))

    :subrope
    (let [n  (count v)
          lo (clamp-index n a)
          hi (clamp-index n b)]
      (subvec v lo hi))))

(defn gen-rope-op
  "Generate a rope operation with index range [0, max-idx].
  Callers control the index range to match collection scale."
  [max-idx]
  (gen/one-of
    [;; Structural ops
     (gen/fmap (fn [[i x]] [:insert i [x] nil])
       (gen/tuple (gen/choose 0 max-idx) gen/small-integer))
     (gen/fmap (fn [[i xs]] [:insert-many i xs nil])
       (gen/tuple (gen/choose 0 max-idx) (gen/vector gen/small-integer 0 8)))
     (gen/fmap (fn [[a b]] [:remove (min a b) (max a b) nil])
       (gen/tuple (gen/choose 0 max-idx) (gen/choose 0 max-idx)))
     (gen/fmap (fn [[a b xs]] [:splice (min a b) (max a b) xs])
       (gen/tuple (gen/choose 0 max-idx) (gen/choose 0 max-idx)
         (gen/vector gen/small-integer 0 8)))
     (gen/fmap (fn [[a b]] [:subrope (min a b) (max a b) nil])
       (gen/tuple (gen/choose 0 max-idx) (gen/choose 0 max-idx)))
     ;; Vector-like ops
     (gen/fmap (fn [x] [:conj x nil nil]) gen/small-integer)
     (gen/return [:pop nil nil nil])
     (gen/fmap (fn [[i x]] [:assoc i x nil])
       (gen/tuple (gen/choose 0 max-idx) gen/small-integer))]))

(defn gen-rope-ops
  "Generate 0-n rope operations with index range [0, max-idx]."
  [n max-idx]
  (gen/vector (gen-rope-op max-idx) 0 n))

(defn- apply-full-rope-op
  [r [op a b c]]
  (case op
    :conj    (conj r a)
    :pop     (if (pos? (count r)) (pop r) r)
    :assoc   (let [n (count r)]
               (if (and (pos? n) (< a n)) (assoc r (rem a n) b) r))
    (apply-rope-op r [op a b c])))

(defn- apply-full-vector-op
  [v [op a b c]]
  (case op
    :conj    (conj v a)
    :pop     (if (pos? (count v)) (pop v) v)
    :assoc   (let [n (count v)]
               (if (and (pos? n) (< a n)) (assoc v (rem a n) b) v))
    (apply-vector-op v [op a b c])))

(defspec prop-rope-random-edit-sequences 100
  (prop/for-all [xs  (gen/vector gen/small-integer 0 100)
                 ops (gen-rope-ops 60 100)]
    (= (reduce apply-full-vector-op (vec xs) ops)
       (vec (reduce apply-full-rope-op (oc/rope xs) ops)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reduced Handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-reduce-early-termination
  (testing "2-arity reduce with reduced"
    (let [r (oc/rope (range 1000))]
      (is (= 10
            (reduce (fn [acc x]
                      (if (>= acc 10)
                        (reduced acc)
                        (inc acc)))
              0 r)))))
  (testing "1-arity reduce with reduced"
    (let [r (oc/rope (range 1 1000))]
      (is (= 10
            (reduce (fn [acc x]
                      (if (>= (long acc) 10)
                        (reduced acc)
                        (+ (long acc) (long x))))
              r)))))
  (testing "reduced across chunk boundaries"
    (let [r (oc/rope (range 10000))
          stop-at 500]
      (is (= stop-at
            (reduce (fn [^long acc x]
                      (if (>= acc stop-at)
                        (reduced acc)
                        (unchecked-inc acc)))
              (long 0) r))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Java Interop
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-java-list-interface
  (let [^java.util.List r (oc/rope [10 20 30 40 50])]
    (is (instance? java.util.List r))
    (is (instance? java.util.Collection r))
    (is (= 5 (.size r)))
    (is (not (.isEmpty r)))
    (is (.isEmpty ^java.util.Collection (oc/rope)))
    (is (= 30 (.get r 2)))
    (is (thrown? IndexOutOfBoundsException (.get r 5)))
    (is (= 0 (.indexOf r 10)))
    (is (= 4 (.indexOf r 50)))
    (is (= -1 (.indexOf r 99)))
    (is (= 4 (.lastIndexOf r 50)))
    (is (.contains r 30))
    (is (not (.contains r 99)))
    (is (.containsAll r [10 30 50]))
    (is (not (.containsAll r [10 99])))
    (is (= [10 20 30 40 50] (vec (.toArray r))))
    (is (thrown? UnsupportedOperationException (.add r 60)))
    (is (thrown? UnsupportedOperationException (.clear r)))))

(deftest rope-sublist
  (let [^java.util.List r (oc/rope (range 20))
        sl (.subList r 5 10)]
    (is (= [5 6 7 8 9] (vec sl)))
    (is (instance? java.util.List sl))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparable
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-comparable
  (let [a (oc/rope [1 2 3])
        b (oc/rope [1 2 4])
        c (oc/rope [1 2 3])
        d (oc/rope [1 2])
        e (oc/rope [1 2 3 4])]
    (is (zero? (compare a c)))
    (is (neg? (compare a b)))
    (is (pos? (compare b a)))
    (is (pos? (compare a d)))
    (is (neg? (compare d a)))
    (is (neg? (compare a e)))
    (is (pos? (compare e a)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Hash and Equality Consistency
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-hash-equality-consistency
  (let [r (oc/rope [1 2 3 4 5])
        v [1 2 3 4 5]]
    (is (= r v))
    (is (= v r))
    (is (= (hash r) (hash v))))
  (let [r1 (oc/rope (range 100))
        r2 (oc/rope (range 100))]
    (is (= r1 r2))
    (is (= (hash r1) (hash r2))))
  (testing "empty rope hash matches empty vector"
    (is (= (hash (oc/rope)) (hash [])))))

(deftest rope-sub-hash-equality
  (let [r  (oc/rope (range 20))
        sv (oc/rope-sub r 5 10)
        v  [5 6 7 8 9]]
    (is (= sv v))
    (is (= v sv))
    (is (= (hash sv) (hash v)))))

(deftest rope-sequential-equality-and-ordering-contract
  (let [r (oc/rope [1 2 3])
        v [1 2 3]
        l '(1 2 3)
        a (oc/rope [1 2 3])
        b (oc/rope [1 2 4])]
    (is (= r v))
    (is (= r l))
    (is (zero? (compare r v)))
    (is (zero? (compare r l)))
    (is (neg? (compare a b)))
    (is (pos? (compare b a)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parallel Fold
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-parallel-fold
  (let [r (oc/rope (range 100000))
        expected (reduce + (range 100000))]
    (is (= expected
          (r/fold + r)))
    (is (= expected
          (r/fold 1024 + + r)))))

(deftest rope-parallel-fold-edge-cases
  (testing "empty rope"
    (let [r (oc/rope)]
      (is (= 0 (r/fold + r)))
      (is (= [] (r/fold 1 into conj r)))
      (is (= {} (r/fold 1
                 (fn ([] {}) ([m1 m2] (merge-with + m1 m2)))
                 (fn [m x] (update m x (fnil inc 0)))
                 r)))))
  (testing "singleton rope"
    (let [r (oc/rope [42])]
      (is (= 42 (r/fold + r)))
      (is (= [42] (r/fold 1 into conj r)))
      (is (= "x" (r/fold 1 str (fn [s ch] (str s ch)) (oc/rope "x"))))))
  (testing "threshold boundaries preserve order"
    (let [r (oc/rope (range 300))]
      (doseq [threshold [1 2 3 7 31 63 64 65 127 128 129 255 256 257 1024]]
        (is (= (vec (range 300))
              (r/fold threshold into conj r)))
        (is (= (reduce + (range 300))
              (r/fold threshold + + r)))))))

(deftest rope-parallel-fold-after-structural-edits
  (let [r0 (oc/rope (range 1000))
        r1 (oc/rope-insert r0 200 [:a :b :c])
        r2 (oc/rope-remove r1 500 700)
        r3 (oc/rope-splice r2 20 40 (range 10))
        expected (vec r3)
        freq-combine (fn ([] {}) ([m1 m2] (merge-with + m1 m2)))
        freq-reduce  (fn [m x] (update m (class x) (fnil inc 0)))]
    (doseq [threshold [1 8 64 256 1024]]
      (is (= expected (r/fold threshold into conj r3)))
      (is (= (reduce freq-reduce {} r3)
            (r/fold threshold freq-combine freq-reduce r3))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Print Methods
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-print-method
  (is (= "#ordered/rope [1 2 3]" (pr-str (oc/rope [1 2 3]))))
  (is (= "#ordered/rope []" (pr-str (oc/rope))))
  (is (= "#ordered/rope [5 6 7]"
        (pr-str (oc/rope-sub (oc/rope (range 10)) 5 8)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interop: String, Vector, Sequence
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-str-conversion
  (is (= "hello" (oc/rope-str (oc/rope (seq "hello")))))
  (is (= "" (oc/rope-str (oc/rope))))
  (is (= "The quick brown fox"
        (oc/rope-str (oc/rope-concat (oc/rope (seq "The quick "))
                                     (oc/rope (seq "brown fox"))))))
  (testing "rope-str on slice"
    (is (= "quick"
          (oc/rope-str (oc/rope-sub (oc/rope (seq "The quick brown")) 4 9))))))

(deftest rope-to-vec-conversion
  (testing "vec on rope preserves content"
    (let [r (oc/rope [1 2 3 4 5])]
      (is (= [1 2 3 4 5] (vec r)))))
  (testing "into [] materializes to PersistentVector"
    (let [r (oc/rope [1 2 3 4 5])
          v (into [] r)]
      (is (= [1 2 3 4 5] v))
      (is (instance? clojure.lang.PersistentVector v))))
  (testing "empty rope"
    (is (= [] (into [] (oc/rope))))))

(deftest rope-sequence-interop
  (testing "rope from various seqable sources"
    (is (= [1 2 3] (vec (oc/rope [1 2 3]))))
    (is (= [0 1 2] (vec (oc/rope (range 3)))))
    (is (= [\h \e \l \l \o] (vec (oc/rope "hello"))))
    (is (= [1 2 3] (vec (oc/rope '(1 2 3))))))
  (testing "rope operations accept non-rope collections"
    (is (= [1 2 3] (vec (oc/rope-concat (oc/rope [1 2]) [3]))))
    (is (= [1 2 :x 3] (vec (oc/rope-insert (oc/rope [1 2 3]) 2 [:x]))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bounds Checking
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-bounds-checking
  (let [r (oc/rope [0 1 2])]
    (is (thrown? IndexOutOfBoundsException (nth r 3)))
    (is (thrown? IndexOutOfBoundsException (nth r -1)))
    (is (= :nope (nth r 3 :nope)))
    (is (= :nope (nth r -1 :nope)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transient
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-transient-basic
  (let [r (persistent! (reduce conj! (transient (oc/rope)) (range 1000)))]
    (is (= (range 1000) (vec (into [] r))))
    (is (= 1000 (count r)))
    (is (ropetree/invariant-valid? (.-root ^ordered_collections.types.rope.Rope r)))))

(deftest rope-transient-from-existing
  (let [base (oc/rope (range 500))
        r    (persistent! (reduce conj! (transient base) (range 500 1000)))]
    (is (= (range 1000) (into [] r)))
    (is (= 1000 (count r)))))

(deftest rope-transient-empty
  (let [r (persistent! (transient (oc/rope)))]
    (is (= [] (into [] r)))
    (is (= 0 (count r)))))

(deftest rope-transient-small
  (let [r (persistent! (reduce conj! (transient (oc/rope)) [1 2 3]))]
    (is (= [1 2 3] (into [] r)))))

(deftest rope-transient-invalidation
  (let [t (transient (oc/rope))]
    (persistent! t)
    (is (thrown? IllegalAccessError (conj! t 1)))
    (is (thrown? IllegalAccessError (persistent! t)))))

(deftest rope-transient-count
  (let [t (reduce conj! (transient (oc/rope)) (range 100))]
    (is (= 100 (count t)))))

(deftest rope-transient-nth
  (let [t (reduce conj! (transient (oc/rope)) (range 500))]
    (is (= 0 (nth t 0)))
    (is (= 255 (nth t 255)))
    (is (= 256 (nth t 256)))
    (is (= 499 (nth t 499)))
    (is (= :nope (nth t 500 :nope)))))

(deftest rope-transient-live-boundaries
  (let [target ropetree/+target-chunk-size+
        base   (oc/rope (range 100))
        t      (transient base)]
    (doseq [x (range 100 (+ 100 (* 2 target) 17))]
      (conj! t x))
    (is (= (+ 100 (* 2 target) 17) (count t)))
    (is (= 0 (nth t 0)))
    (is (= 99 (nth t 99)))
    (is (= 100 (nth t 100)))
    (is (= (+ 100 target -1) (nth t (+ 100 target -1))))
    (is (= (+ 100 target) (nth t (+ 100 target))))
    (is (= (+ 100 (* 2 target) -1) (nth t (+ 100 (* 2 target) -1))))
    (is (= (+ 100 (* 2 target)) (nth t (+ 100 (* 2 target)))))
    (is (= (+ 100 (* 2 target) 16) (nth t (+ 100 (* 2 target) 16))))
    (is (= :nope (nth t (+ 100 (* 2 target) 17) :nope)))))


(defn- rope-root-of [^ordered_collections.types.rope.Rope r]
  (.-root r))

(defspec prop-chunk-shape-after-edits 50
  (prop/for-all [xs  (gen/vector gen/small-integer 0 200)
                 ops tu/gen-rope-ops]
    (let [r    (reduce apply-rope-op (oc/rope xs) ops)
          root (rope-root-of r)]
      (ropetree/invariant-valid? root))))

(defspec prop-csi-after-individual-ops 100
  (prop/for-all [xs (gen/vector gen/small-integer 10 100)]
    (let [r (oc/rope xs)
          root (rope-root-of r)]
      (and
        ;; Construction
        (ropetree/invariant-valid? root)
        ;; Concat
        (ropetree/invariant-valid?
          (rope-root-of (oc/rope-concat r (oc/rope (take 5 xs)))))
        ;; Split
        (let [[l rr] (oc/rope-split r (quot (count xs) 2))]
          (and (ropetree/invariant-valid? (rope-root-of l))
               (ropetree/invariant-valid? (rope-root-of rr))))
        ;; Subrope
        (ropetree/invariant-valid?
          (rope-root-of (oc/rope-sub r 1 (dec (count xs)))))
        ;; Insert
        (ropetree/invariant-valid?
          (rope-root-of (oc/rope-insert r 3 [:x :y :z])))
        ;; Splice
        (ropetree/invariant-valid?
          (rope-root-of (oc/rope-splice r 2 5 [:a :b])))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Large-Scale Property Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-large-edit-sequences 50
  (prop/for-all [xs  (gen/vector gen/small-integer 200 600)
                 ops (gen-rope-ops 100 600)]
    (let [r (reduce apply-full-rope-op (oc/rope xs) ops)
          v (reduce apply-full-vector-op (vec xs) ops)]
      (and (= v (into [] r))
           (ropetree/invariant-valid? (rope-root-of r))))))

(defspec prop-hash-equality-with-vector 100
  (prop/for-all [xs (gen/vector gen/small-integer 0 500)]
    (let [r (oc/rope xs)]
      (and (= r (vec xs))
           (= (vec xs) r)
           (= (hash r) (hash (vec xs)))))))

(defspec prop-reduce-matches-vector 100
  (prop/for-all [xs (gen/vector gen/small-integer 1 500)]
    (let [r (oc/rope xs)
          v (vec xs)]
      (and (= (reduce + 0 r) (reduce + 0 v))
           (= (reduce conj [] r) (reduce conj [] v))))))

(defspec prop-seq-reduce-consistency 100
  (prop/for-all [xs (gen/vector gen/small-integer 1 2000)]
    (let [r (oc/rope xs)]
      ;; seq and reduce must agree with each other
      (= (reduce conj [] r)
         (into [] (seq r))))))

(defspec prop-seq-walk-matches-vector 100
  (prop/for-all [xs (gen/vector gen/small-integer 1 2000)]
    (let [r (oc/rope xs)]
      ;; Walk the RopeSeq via .next chain, element by element
      (loop [s (seq r), v (seq xs)]
        (cond
          (and (nil? s) (nil? v)) true
          (or (nil? s) (nil? v))  false
          (not= (first s) (first v)) false
          :else (recur (next s) (next v)))))))

(defspec prop-rseq-walk-matches-vector 100
  (prop/for-all [xs (gen/vector gen/small-integer 1 2000)]
    (let [r (oc/rope xs)
          v (vec xs)]
      ;; Walk the RopeSeqReverse via .next chain
      (loop [s (rseq r), v (rseq v)]
        (cond
          (and (nil? s) (nil? v)) true
          (or (nil? s) (nil? v))  false
          (not= (first s) (first v)) false
          :else (recur (next s) (next v)))))))

(defspec prop-conj-pop-round-trip 100
  (prop/for-all [xs (gen/vector gen/small-integer 0 300)]
    (let [r (reduce conj (oc/rope) xs)
          popped (reduce (fn [r _] (pop r)) r (range (min 10 (count xs))))]
      (and (= (vec r) xs)
           (= (count popped) (max 0 (- (count xs) (min 10 (count xs)))))))))

(defspec prop-transient-round-trip 100
  (prop/for-all [xs (gen/vector gen/small-integer 0 1000)]
    (let [r (persistent! (reduce conj! (transient (oc/rope)) xs))]
      (and (= (vec xs) (into [] r))
           (= (count xs) (count r))
           (ropetree/invariant-valid? (rope-root-of r))))))

(defspec prop-transient-live-nth-matches-vector 50
  (prop/for-all [base  (gen/vector gen/small-integer 0 300)
                 extra (gen/vector gen/small-integer 0 2000)]
    (let [t    (reduce conj! (transient (oc/rope base)) extra)
          v    (vec (concat base extra))
          n    (count v)
          idxs (if (zero? n)
                 []
                 (distinct (concat [0 (dec n)]
                             (range 0 n (max 1 (quot n 25))))))]
      (and (= n (count t))
           (every? #(= (nth t %) (nth v %)) idxs)
           (= :nope (nth t n :nope))))))

(defspec prop-concat-all-matches-into 50
  ;; Mix of tiny (1-5), undersized (<min), valid (min-target), and
  ;; target-sized chunks to exercise chunks->root-csi boundary merging
  (prop/for-all [chunks (gen/vector
                          (gen/one-of
                            [(gen/vector gen/small-integer 1 5)      ;; tiny
                             (gen/vector gen/small-integer 0 100)    ;; undersized
                             (gen/vector gen/small-integer 128 256)  ;; valid
                             (gen/vector gen/small-integer 256 256)]);; exactly target
                          1 30)]
    (let [ropes  (mapv oc/rope chunks)
          result (apply oc/rope-concat-all ropes)
          expected (vec (apply concat chunks))]
      (and (= expected (into [] result))
           (ropetree/invariant-valid? (rope-root-of result))))))

(defspec prop-fold-matches-reduce 50
  (prop/for-all [xs (gen/vector gen/small-integer 0 4000)
                 threshold (gen/elements [1 2 3 4 8 16 31 32 63 64 65 127 128 129 255 256 257 511 512 1024 4096])]
    (let [r (oc/rope xs)]
      (and
        ;; Commutative: sum
        (= (reduce + 0 r)
           (r/fold threshold + + r))
        ;; Non-commutative: ordered collection into vector
        ;; fold with into must produce the same ordered result
        (= (reduce conj [] r)
           (r/fold threshold into conj r))
        ;; Map-building workload with nontrivial combine/reduce
        (= (reduce (fn [m x] (update m (mod (long x) 17) (fnil inc 0))) {} r)
           (r/fold threshold
             (fn ([] {}) ([m1 m2] (merge-with + m1 m2)))
             (fn [m x] (update m (mod (long x) 17) (fnil inc 0)))
             r))))))

(defspec prop-metadata-preserved 100
  (prop/for-all [xs (gen/vector gen/small-integer 10 100)]
    (let [m {:tag :test}
          r (with-meta (oc/rope xs) m)]
      (and (= m (meta r))
           (= m (meta (oc/rope-concat r (oc/rope [99]))))
           (= m (meta (first (oc/rope-split r 5))))
           (= m (meta (oc/rope-sub r 2 8)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multi-Chunk Scale and Structural Integrity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rope-tree-healthy?
  "Check that the rope root satisfies both CSI and WBT balance."
  [root]
  (and (ropetree/invariant-valid? root)
       (or (nil? root)
           (ordered-collections.kernel.tree/node-healthy? root))))

(defspec prop-multi-chunk-edit-sequences 50
  ;; Inputs large enough to span many chunks; indices scale to full range
  (prop/for-all [xs  (gen/vector gen/small-integer 2000 4000)
                 ops (gen-rope-ops 120 4000)]
    (let [r (reduce apply-full-rope-op (oc/rope xs) ops)
          v (reduce apply-full-vector-op (vec xs) ops)]
      (and (= v (into [] r))
           (rope-tree-healthy? (rope-root-of r))))))

(defspec prop-nth-matches-vector 100
  (prop/for-all [xs (gen/vector gen/small-integer 1 2000)]
    (let [r (oc/rope xs)
          v (vec xs)
          n (count xs)
          ;; Check every 50th index plus first and last
          idxs (distinct (concat [0 (dec n)]
                           (range 0 n (max 1 (quot n 20)))))]
      (every? #(= (nth r %) (nth v %)) idxs))))

(defspec prop-assoc-matches-vector 100
  (prop/for-all [xs (gen/vector gen/small-integer 1 500)
                 i  (gen/choose 0 499)
                 v  gen/small-integer]
    (let [n (count xs)]
      (if (< i n)
        (= (assoc (vec xs) i v)
           (vec (assoc (oc/rope xs) i v)))
        true))))

(defspec prop-rope-str-matches-apply-str 100
  (prop/for-all [xs (gen/vector (gen/elements (seq "abcdefghij ")) 0 500)]
    (let [r (oc/rope xs)]
      (= (apply str xs)
         (oc/rope-str r)))))

(defspec prop-into-vec-materializes 100
  (prop/for-all [xs (gen/vector gen/small-integer 0 2000)]
    (let [r (oc/rope xs)
          v (into [] r)]
      (and (instance? clojure.lang.PersistentVector v)
           (= xs v)))))

(defspec prop-concat-mixed-sizes 50
  (prop/for-all [left  (gen/vector gen/small-integer 0 2000)
                 right (gen/vector gen/small-integer 0 2000)]
    (let [result (oc/rope-concat (oc/rope left) (oc/rope right))
          root   (rope-root-of result)]
      (and (= (into (vec left) right) (into [] result))
           (rope-tree-healthy? root)))))

(defspec prop-transient-many-flushes 50
  ;; Large enough to exercise many chunk flushes
  (prop/for-all [xs (gen/vector gen/small-integer 5000 10000)]
    (let [r (persistent! (reduce conj! (transient (oc/rope)) xs))]
      (and (= (vec xs) (into [] r))
           (rope-tree-healthy? (rope-root-of r))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IFn Invocation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-ifn-invocation
  (let [r (oc/rope [10 20 30 40 50])]
    (is (= 10 (r 0)))
    (is (= 30 (r 2)))
    (is (= 50 (r 4)))
    (is (= :nope (r 5 :nope)))
    (is (nil? (r 99)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Persistent Sharing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-persistent-sharing
  (testing "split halves are independent"
    (let [r (oc/rope (range 100))
          [left right] (oc/rope-split r 50)
          left'  (oc/rope-splice left 10 20 [:x])
          right' (oc/rope-insert right 0 [:y])]
      ;; Originals unchanged
      (is (= (vec (range 100)) (vec r)))
      (is (= (vec (range 50)) (vec left)))
      (is (= (vec (range 50 100)) (vec right)))
      ;; Edits only affect their target
      (is (= 41 (count left')))
      (is (= 51 (count right')))))
  (testing "sub shares structure but edits are independent"
    (let [r   (oc/rope (range 1000))
          sub (oc/rope-sub r 100 200)
          sub' (assoc sub 50 :replaced)]
      (is (= 500 (nth r 500)))
      (is (= :replaced (nth sub' 50)))
      (is (= 150 (nth sub 50))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Empty Edge Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-empty-operations
  (let [e (oc/rope)]
    (testing "concat with empty"
      (is (= [1 2 3] (vec (oc/rope-concat e (oc/rope [1 2 3])))))
      (is (= [1 2 3] (vec (oc/rope-concat (oc/rope [1 2 3]) e)))))
    (testing "split empty"
      (let [[l r] (oc/rope-split e 0)]
        (is (= [] (vec l)))
        (is (= [] (vec r)))))
    (testing "sub of empty"
      (is (= [] (vec (oc/rope-sub e 0 0)))))
    (testing "insert into empty"
      (is (= [:a] (vec (oc/rope-insert e 0 [:a])))))
    (testing "empty from empty"
      (is (= [] (vec (empty e)))))
    (testing "conj to empty"
      (is (= [42] (vec (conj e 42)))))))

(deftest rope-single-element
  (let [r (oc/rope [42])]
    (is (= 1 (count r)))
    (is (= 42 (nth r 0)))
    (is (= 42 (peek r)))
    (is (= [] (vec (pop r))))
    (let [[l rr] (oc/rope-split r 0)]
      (is (= [] (vec l)))
      (is (= [42] (vec rr))))
    (let [[l rr] (oc/rope-split r 1)]
      (is (= [42] (vec l)))
      (is (= [] (vec rr))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mixed Element Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-mixed-element-types 100
  (prop/for-all [xs (gen/vector gen/any-printable-equatable 0 200)]
    (let [r (oc/rope xs)]
      (and (= (count xs) (count r))
           (= (vec xs) (vec r))
           (= (hash (vec xs)) (hash r))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EDN Round-Trip Property
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-edn-round-trip 100
  (prop/for-all [xs (gen/vector gen/small-integer 0 500)]
    (let [r  (oc/rope xs)
          rt (clojure.edn/read-string
               {:readers {'ordered/rope oc/rope}}
               (pr-str r))]
      (and (= (vec r) (vec rt))
           (= (count r) (count rt))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conj/Pop CSI and WBT Health
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-conj-pop-preserves-health 50
  (prop/for-all [xs (gen/vector gen/small-integer 0 2000)]
    (let [r (reduce conj (oc/rope) xs)
          popped (reduce (fn [r _] (if (pos? (count r)) (pop r) r))
                   r (range (min 500 (count xs))))]
      (and (rope-tree-healthy? (rope-root-of r))
           (rope-tree-healthy? (rope-root-of popped))))))

(defspec prop-adversarial-chunk-boundaries 50
  ;; Build ropes at CSI boundary sizes — mostly multi-chunk (1K+)
  ;; with a few small edge cases
  (prop/for-all [size (gen/frequency
                        [[1 (gen/return 1)]
                         [1 (gen/return ropetree/+min-chunk-size+)]
                         [1 (gen/return ropetree/+target-chunk-size+)]
                         [2 (gen/return (inc ropetree/+target-chunk-size+))]
                         [3 (gen/return (* 3 ropetree/+target-chunk-size+))]
                         [3 (gen/return (* 5 ropetree/+target-chunk-size+))]
                         [4 (gen/return (inc (* 4 ropetree/+target-chunk-size+)))]
                         [5 (gen/return (* 10 ropetree/+target-chunk-size+))]])]
    (let [r   (oc/rope (range size))
          mid (quot size 2)
          v   (vec (range size))]
      (and
        ;; Construction
        (rope-tree-healthy? (rope-root-of r))
        (= v (vec r))
        ;; Split at midpoint
        (let [[l rr] (oc/rope-split r mid)]
          (and (= (subvec v 0 mid) (vec l))
               (= (subvec v mid) (vec rr))
               (rope-tree-healthy? (rope-root-of l))
               (rope-tree-healthy? (rope-root-of rr))))
        ;; Concat two boundary-sized ropes
        (let [c (oc/rope-concat r (oc/rope (range size)))]
          (and (= (into v (range size)) (vec c))
               (rope-tree-healthy? (rope-root-of c))))
        ;; Sub at boundary positions
        (let [s (oc/rope-sub r (min 1 mid) (max mid (dec size)))]
          (rope-tree-healthy? (rope-root-of s)))
        ;; Splice at midpoint
        (or (<= size 2)
            (let [sp (oc/rope-splice r (min 1 mid) (max mid (dec size)) [:x])]
              (rope-tree-healthy? (rope-root-of sp))))))))

(defspec prop-large-adversarial-edits 30
  ;; Large ropes (5K-50K) with many operations hitting the full index range.
  ;; Verifies correctness AND structural health after a long edit sequence.
  (prop/for-all [size (gen/frequency
                        [[3 (gen/choose 5000 10000)]
                         [4 (gen/choose 10000 25000)]
                         [3 (gen/choose 25000 50000)]])
                 ops  (gen/vector (gen-rope-op 50000) 100 200)]
    (let [r (reduce apply-full-rope-op (oc/rope (range size)) ops)
          v (reduce apply-full-vector-op (vec (range size)) ops)]
      (and (= v (vec r))
           (rope-tree-healthy? (rope-root-of r))))))

(defspec prop-split-at-every-chunk-boundary 30
  ;; Build a large rope, then split at positions that land exactly on
  ;; chunk boundaries (multiples of target) and verify both halves.
  (prop/for-all [nchunks (gen/choose 4 40)]
    (let [size (* nchunks ropetree/+target-chunk-size+)
          r    (oc/rope (range size))
          v    (vec (range size))]
      (every? identity
        (for [i (range 1 nchunks)]
          (let [pos (* i ropetree/+target-chunk-size+)
                [l rr] (oc/rope-split r pos)]
            (and (= (subvec v 0 pos) (vec l))
                 (= (subvec v pos) (vec rr))
                 (rope-tree-healthy? (rope-root-of l))
                 (rope-tree-healthy? (rope-root-of rr)))))))))

(defspec prop-repeated-split-concat-round-trip 50
  ;; Split a large rope into many pieces, then concat them back.
  ;; The result must equal the original.
  (prop/for-all [size (gen/choose 2000 20000)
                 nsplits (gen/choose 3 15)]
    (let [r    (oc/rope (range size))
          ;; Split into nsplits pieces at evenly spaced positions
          positions (mapv #(long (* size (/ (double (inc %)) (inc nsplits))))
                     (range nsplits))
          pieces (loop [remaining r, positions positions, acc []]
                   (if (empty? positions)
                     (conj acc remaining)
                     (let [[l rr] (oc/rope-split remaining (first positions))]
                       (recur rr
                         (mapv #(- % (first positions)) (rest positions))
                         (conj acc l)))))
          ;; Concat all pieces back together
          reassembled (reduce oc/rope-concat pieces)]
      (and (= (vec (range size)) (vec reassembled))
           (rope-tree-healthy? (rope-root-of reassembled))))))

(defspec prop-nested-sub-correctness 50
  ;; Take a large rope, sub a window, sub a sub-window, verify at each step
  (prop/for-all [size (gen/choose 5000 20000)]
    (let [r  (oc/rope (range size))
          v  (vec (range size))
          q1 (quot size 4)
          q3 (* 3 q1)
          s1 (oc/rope-sub r q1 q3)
          v1 (subvec v q1 q3)
          inner-size (- q3 q1)
          iq1 (quot inner-size 4)
          iq3 (* 3 iq1)
          s2 (oc/rope-sub s1 iq1 iq3)
          v2 (subvec v1 iq1 iq3)]
      (and (= v1 (vec s1))
           (= v2 (vec s2))
           (rope-tree-healthy? (rope-root-of s1))
           (rope-tree-healthy? (rope-root-of s2))))))

(defspec prop-interleaved-insert-remove 50
  ;; Alternating inserts and removes at random positions on a large rope.
  ;; This stresses CSI normalization under mixed growth/shrinkage.
  (prop/for-all [size (gen/choose 3000 10000)
                 ops  (gen/vector
                        (gen/one-of
                          [(gen/fmap (fn [[i x]] [:insert i [x]])
                             (gen/tuple (gen/choose 0 10000) gen/small-integer))
                           (gen/fmap (fn [[a b]] [:remove (min a b) (max a b)])
                             (gen/tuple (gen/choose 0 10000) (gen/choose 0 10000)))])
                        100 200)]
    (let [apply-op (fn [[coll-r coll-v] [op a b]]
                     (let [n (count coll-r)]
                       (case op
                         :insert
                         (let [i (min a n)]
                           [(oc/rope-insert coll-r i b)
                            (vec (concat (subvec coll-v 0 i) b (subvec coll-v i)))])
                         :remove
                         (let [lo (min a n)
                               hi (min b n)
                               lo (min lo hi)
                               hi (max lo hi)]
                           [(oc/rope-remove coll-r lo hi)
                            (vec (concat (subvec coll-v 0 lo) (subvec coll-v hi)))]))))
          [r v] (reduce apply-op [(oc/rope (range size)) (vec (range size))] ops)]
      (and (= v (vec r))
           (rope-tree-healthy? (rope-root-of r))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coverage Gap Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; splice-root edge cases: empty mid, empty left, empty right
(deftest rope-splice-root-edge-cases
  (let [r (oc/rope (range 100))]
    (testing "remove-only splice (empty mid)"
      (is (= (vec (concat (range 10) (range 50 100)))
            (vec (oc/rope-remove r 10 50)))))
    (testing "splice at start (left is empty)"
      (is (= (into [:x :y] (range 10 100))
            (vec (oc/rope-splice r 0 10 [:x :y])))))
    (testing "splice at end (right is empty)"
      (is (= (into (vec (range 90)) [:x :y])
            (vec (oc/rope-splice r 90 100 [:x :y])))))
    (testing "remove everything"
      (is (= [] (vec (oc/rope-remove r 0 100)))))
    (testing "insert into empty"
      (is (= [:a :b] (vec (oc/rope-insert (oc/rope) 0 [:a :b])))))
    (testing "splice replacing everything"
      (is (= [:z] (vec (oc/rope-splice r 0 100 [:z])))))))

;; merge-boundary cascade: concatenating tiny ropes forces one-more-neighbor pull
(deftest rope-merge-boundary-cascade
  (testing "concatenating many single-element ropes"
    (let [r (reduce oc/rope-concat (map #(oc/rope [%]) (range 50)))]
      (is (= (range 50) (vec r)))
      (is (ropetree/invariant-valid? (rope-root-of r)))))
  (testing "concat two very small ropes"
    (let [a (oc/rope [1 2])
          b (oc/rope [3 4])
          c (oc/rope-concat a b)]
      (is (= [1 2 3 4] (vec c)))
      (is (ropetree/invariant-valid? (rope-root-of c)))))
  (testing "concat where boundary chunks are both undersized"
    ;; Build a rope, split to get a runt, then concat with another runt
    (let [r   (oc/rope (range 1000))
          [l _] (oc/rope-split r 3)    ;; l has 3 elements (runt)
          [_ rr] (oc/rope-split r 997) ;; rr has 3 elements (runt)
          c   (oc/rope-concat l rr)]
      (is (= (into (vec (range 3)) (range 997 1000)) (vec c)))
      (is (ropetree/invariant-valid? (rope-root-of c))))))

;; rope-concat-all with mixed Rope and non-Rope collections
(deftest rope-concat-all-mixed-types
  (is (= [1 2 3 4 5 6]
        (vec (oc/rope-concat-all (oc/rope [1 2]) [3 4] (oc/rope [5 6])))))
  (is (= (range 100)
        (vec (apply oc/rope-concat-all
               (map #(if (even? %)
                       (oc/rope (range (* % 10) (* (inc %) 10)))
                       (vec (range (* % 10) (* (inc %) 10))))
                 (range 10)))))))

;; rope-chunks-reverse property test
(defspec prop-chunks-reverse-matches-forward 100
  (prop/for-all [xs (gen/vector gen/small-integer 1 2000)]
    (let [r (oc/rope xs)]
      (= (reverse (map vec (oc/rope-chunks r)))
         (map vec (oc/rope-chunks-reverse r))))))

;; rope-chunk-count property test
(defspec prop-chunk-count-matches-chunks 100
  (prop/for-all [xs (gen/vector gen/small-integer 0 2000)]
    (let [r (oc/rope xs)]
      (= (oc/rope-chunk-count r)
         (count (vec (oc/rope-chunks r)))))))

;; normalize-root: full rechunk produces valid CSI
(deftest rope-normalize-root
  (testing "normalize-root on a well-formed rope is idempotent"
    (let [root (rope-root-of (oc/rope (range 1000)))
          normalized (ropetree/normalize-root root)]
      (is (= (vec (range 1000))
            (vec (oc/rope (range 1000)))))
      (is (ropetree/invariant-valid? normalized))))
  (testing "normalize-root on an artificially degraded root restores CSI"
    ;; Build a root with many tiny chunks by raw construction
    (let [tiny-chunks (mapv vector (range 50))
          bad-root    (ropetree/chunks->root tiny-chunks)
          fixed       (ropetree/normalize-root bad-root)]
      (is (= (range 50) (vec (map first (ropetree/root->chunks bad-root)))))
      (is (ropetree/invariant-valid? fixed))
      (is (= (range 50)
            (reduce into [] (ropetree/root->chunks fixed))))))
  (testing "normalize-root on nil returns nil"
    (is (nil? (ropetree/normalize-root nil)))))

;; RopeSeqReverse count fallback (cnt is nil after .next chains)
(deftest rope-seq-reverse-count-after-next
  (let [r  (oc/rope (range 20))
        rs (rseq r)
        rs2 (next rs)
        rs5 (nth (iterate next rs) 5)]
    (is (= 20 (count rs)))
    (is (= 19 (count rs2)))
    (is (= 15 (count rs5)))))

;; RopeSeq count after .next chains
(deftest rope-seq-count-after-next
  (let [r (oc/rope (range 100))
        s (seq r)
        s5 (nth (iterate next s) 5)
        s50 (nth (iterate next s) 50)]
    (is (= 100 (count s)))
    (is (= 95 (count s5)))
    (is (= 50 (count s50)))))

;; RopeSeq reduce via .next-obtained seq
(deftest rope-seq-reduce-from-mid
  (let [r (oc/rope (range 100))
        s (nth (iterate next (seq r)) 10)]
    (is (= (reduce + (range 10 100))
          (reduce + s)))))

;; ensure-right-fringe actually fires
(deftest rope-fringe-repair
  (testing "split inside a chunk creates undersized fringe that gets repaired"
    (let [r (oc/rope (range 1000))
          ;; Split at a position inside a chunk, not at a chunk boundary
          [l rr] (oc/rope-split r 5)]
      (is (= (range 5) (vec l)))
      (is (= (range 5 1000) (vec rr)))
      (is (ropetree/invariant-valid? (rope-root-of l)))
      (is (ropetree/invariant-valid? (rope-root-of rr)))))
  (testing "subrope creating small fringes on both sides"
    (let [r (oc/rope (range 2000))
          s (oc/rope-sub r 3 1997)]
      (is (= (vec (range 3 1997)) (vec s)))
      (is (ropetree/invariant-valid? (rope-root-of s))))))

;; rope-peek-right and rope-pop-right coverage
(deftest rope-peek-pop-coverage
  (let [r (oc/rope (range 500))]
    (is (= 499 (peek r)))
    (is (= 498 (peek (pop r))))
    (is (= 499 (count (pop r))))
    ;; Pop down to a single element
    (let [single (reduce (fn [r _] (pop r)) (oc/rope [1 2 3]) (range 2))]
      (is (= [1] (vec single)))
      (is (= 1 (peek single))))))

;; rope-remove-root is just splice-root with nil mid
(deftest rope-remove-root-delegation
  (let [r (oc/rope (range 100))]
    (is (= (vec (oc/rope-remove r 10 20))
          (vec (concat (range 10) (range 20 100)))))))

;; rope-fold kernel function directly
(deftest rope-fold-kernel
  (let [root (rope-root-of (oc/rope (range 10000)))]
    (is (= (reduce + (range 10000))
          (ropetree/rope-fold root 512 + +)))))
