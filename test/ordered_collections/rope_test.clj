(ns ordered-collections.rope-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ordered-collections.core :as oc]
            [ordered-collections.tree.rope :as ropetree]
            [ordered-collections.test-utils :as tu]))


(deftest rope-basic-semantics
  (let [r (oc/rope [0 1 2 3 4])]
    (is (vector? r))
    (is (= 5 (count r)))
    (is (= [0 1 2 3 4] (vec r)))
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
  (let [r (oc/rope (range 130))]
    (is (= 130 (count r)))
    (is (= (range 130) (vec r)))
    (is (= [(vec (range 130))]
          (vec (oc/rope-chunks r))))
    (is (= 1 (oc/rope-chunk-count r)))
    (is (= (range 60 70) (vec (oc/rope-sub r 60 70))))
    (let [split ropetree/+min-chunk-size+]
      (let [[l rr] (oc/rope-split r split)]
        (is (= (range split) (vec l)))
        (is (= (range split 130) (vec rr))))
      (let [[l rr] (oc/rope-split r (inc split))]
        (is (= (range (inc split)) (vec l)))
        (is (= (range (inc split) 130) (vec rr)))))
    (is (= (range 140)
          (vec (reduce conj r (range 130 140)))))))

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
    (is (thrown? IndexOutOfBoundsException (oc/rope-remove r -1 2)))
    (is (thrown? IndexOutOfBoundsException (oc/rope-splice r 4 2 [:x])))))

(deftest rope-normalization-and-chunk-iteration
  (let [tiny (reduce oc/rope-concat (map #(oc/rope [%]) (range 130)))]
    (is (= (range 130) (vec tiny)))
    (is (= [130] (mapv count (oc/rope-chunks tiny))))
    (is (= [130] (mapv count (oc/rope-chunks-reverse tiny))))))

(deftest rope-slice-view
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

(defspec prop-rope-random-edit-sequences 100
  (prop/for-all [xs  (gen/vector gen/small-integer 0 40)
                 ops tu/gen-rope-ops]
    (= (reduce apply-vector-op (vec xs) ops)
       (vec (reduce apply-rope-op (oc/rope xs) ops)))))


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

(deftest rope-slice-hash-equality
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
;; Bounds Checking
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rope-bounds-checking
  (let [r (oc/rope [0 1 2])]
    (is (thrown? IndexOutOfBoundsException (nth r 3)))
    (is (thrown? IndexOutOfBoundsException (nth r -1)))
    (is (= :nope (nth r 3 :nope)))
    (is (= :nope (nth r -1 :nope)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chunk Shape After Long Edit Sequences
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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


(defn- rope-root-of [r]
  (cond
    (instance? ordered_collections.types.rope.Rope r)
    (.-root ^ordered_collections.types.rope.Rope r)
    (instance? ordered_collections.types.rope.RopeSlice r)
    (.-root ^ordered_collections.types.rope.RopeSlice r)))

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
                 ops tu/gen-rope-ops]
    (let [r (reduce apply-rope-op (oc/rope xs) ops)
          v (reduce apply-vector-op (vec xs) ops)]
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
