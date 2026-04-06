(ns ordered-collections.rope-test
  (:require [clojure.test :refer :all]
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

(def gen-full-rope-op
  "Extended rope operation generator including conj, pop, and assoc
  alongside the structural editing operations."
  (gen/one-of
    [;; Structural ops from test-utils
     (gen/fmap (fn [[i x]] [:insert i [x] nil])
       (gen/tuple (gen/choose 0 40) gen/small-integer))
     (gen/fmap (fn [[i xs]] [:insert-many i xs nil])
       (gen/tuple (gen/choose 0 40) (gen/vector gen/small-integer 0 8)))
     (gen/fmap (fn [[a b]] [:remove (min a b) (max a b) nil])
       (gen/tuple (gen/choose 0 40) (gen/choose 0 40)))
     (gen/fmap (fn [[a b xs]] [:splice (min a b) (max a b) xs])
       (gen/tuple (gen/choose 0 40) (gen/choose 0 40)
         (gen/vector gen/small-integer 0 8)))
     (gen/fmap (fn [[a b]] [:subrope (min a b) (max a b) nil])
       (gen/tuple (gen/choose 0 40) (gen/choose 0 40)))
     ;; Vector-like ops
     (gen/fmap (fn [x] [:conj x nil nil]) gen/small-integer)
     (gen/return [:pop nil nil nil])
     (gen/fmap (fn [[i x]] [:assoc i x nil])
       (gen/tuple (gen/choose 0 40) gen/small-integer))]))

(def gen-full-rope-ops
  (gen/vector gen-full-rope-op 0 60))

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
  (prop/for-all [xs  (gen/vector gen/small-integer 0 40)
                 ops gen-full-rope-ops]
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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parallel Fold
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-parallel-fold
  (let [r (oc/rope (range 100000))
        expected (reduce + (range 100000))]
    (is (= expected
          (clojure.core.reducers/fold + r)))
    (is (= expected
          (clojure.core.reducers/fold 1024 + + r)))))


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
                 ops gen-full-rope-ops]
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

(defspec prop-rseq-matches-vector 100
  (prop/for-all [xs (gen/vector gen/small-integer 1 200)]
    (let [r (oc/rope xs)]
      (= (vec (rseq r)) (vec (rseq (vec xs)))))))

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

(defspec prop-concat-all-matches-into 50
  (prop/for-all [chunks (gen/vector (gen/vector gen/small-integer 0 100) 1 20)]
    (let [ropes  (mapv oc/rope chunks)
          result (apply oc/rope-concat-all ropes)
          expected (vec (apply concat chunks))]
      (and (= expected (into [] result))
           (ropetree/invariant-valid? (rope-root-of result))))))

(defspec prop-fold-matches-reduce 50
  (prop/for-all [xs (gen/vector gen/small-integer 100 2000)]
    (let [r (oc/rope xs)]
      (= (reduce + 0 r)
         (clojure.core.reducers/fold + r)))))

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
  ;; Inputs large enough to span many chunks (target=512, so 2000+ elements)
  (prop/for-all [xs  (gen/vector gen/small-integer 2000 4000)
                 ops gen-full-rope-ops]
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
