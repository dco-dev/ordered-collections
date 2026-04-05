(ns ordered-collections.types.rope
  "Persistent rope-like indexed sequence backed by an implicit-index
  weight-balanced tree."
  (:require [clojure.core.protocols :as cp]
            [ordered-collections.tree.rope :as ropetree])
  (:import  [clojure.lang RT Murmur3 MapEntry IPersistentVector ILookup
                           Associative Indexed Seqable Reversible Sequential
                           IPersistentCollection IPersistentStack IObj IMeta
                           IReduce IReduceInit SeqIterator Util]))


(defn- seq-equiv
  [s1 o]
  (if-not (or (instance? clojure.lang.Sequential o)
              (instance? java.util.List o))
    false
    (loop [s1 (seq s1) s2 (seq o)]
      (cond
        (nil? s1) (nil? s2)
        (nil? s2) false
        (not (Util/equiv (first s1) (first s2))) false
        :else (recur (next s1) (next s2))))))

(defn- valid-index?
  [^long n k]
  (and (integer? k) (<= 0 (long k)) (< (long k) n)))

(defn- assoc-index?
  [^long n k]
  (and (integer? k) (<= 0 (long k)) (<= (long k) n)))

(declare ->Rope)
(declare ->RopeSlice)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype Rope [root _meta]

  java.io.Serializable

  IMeta
  (meta [_]
    _meta)

  IObj
  (withMeta [_ m]
    (Rope. root m))

  clojure.lang.Counted
  (count [_]
    (ropetree/rope-size root))

  Indexed
  (nth [_ i]
    (ropetree/rope-nth root i))
  (nth [_ i not-found]
    (if (valid-index? (ropetree/rope-size root) i)
      (ropetree/rope-nth root (long i))
      not-found))

  ILookup
  (valAt [this k]
    (.nth this k nil))
  (valAt [this k not-found]
    (.nth this k not-found))

  clojure.lang.IFn
  (invoke [this k]
    (.valAt this k))
  (invoke [this k not-found]
    (.valAt this k not-found))
  (applyTo [this args]
    (let [n (RT/boundedLength args 2)]
      (case n
        0 (throw (clojure.lang.ArityException. n (.. this getClass getSimpleName)))
        1 (.invoke this (first args))
        2 (.invoke this (first args) (second args))
        (throw (clojure.lang.ArityException. n (.. this getClass getSimpleName))))))

  Associative
  (containsKey [_ k]
    (valid-index? (ropetree/rope-size root) k))
  (entryAt [this k]
    (when (.containsKey this k)
      (MapEntry. k (.nth this k))))
  (assoc [this k v]
    (let [n (ropetree/rope-size root)]
      (cond
        (not (assoc-index? n k))
        (throw (IndexOutOfBoundsException.))

        (= (long k) n)
        (Rope. (ropetree/rope-conj-right root v) _meta)

        :else
        (Rope. (ropetree/rope-assoc root (long k) v) _meta))))

  IPersistentCollection
  (cons [_ o]
    (Rope. (ropetree/rope-conj-right root o) _meta))
  (empty [_]
    (Rope. nil _meta))
  (equiv [this o]
    (seq-equiv this o))

  IPersistentStack
  (peek [_]
    (ropetree/rope-peek-right root))
  (pop [_]
    (Rope. (ropetree/rope-pop-right root) _meta))

  IPersistentVector

  Seqable
  (seq [_]
    (ropetree/rope-seq root))

  Reversible
  (rseq [_]
    (ropetree/rope-rseq root))

  Sequential

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. (seq this)))

  IReduceInit
  (reduce [_ f init]
    (ropetree/rope-reduce f init root))

  IReduce
  (reduce [_ f]
    (ropetree/rope-reduce f root))

  cp/CollReduce
  (coll-reduce [this f]
    (.reduce ^IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^IReduceInit this f init))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))
  (toString [this]
    (pr-str (vec this))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope Slice
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype RopeSlice [root ^long start ^long end _meta]

  java.io.Serializable

  IMeta
  (meta [_]
    _meta)

  IObj
  (withMeta [_ m]
    (RopeSlice. root start end m))

  clojure.lang.Counted
  (count [_]
    (- end start))

  Indexed
  (nth [_ i]
    (let [n (- end start)]
      (if (valid-index? n i)
        (ropetree/rope-nth root (+ start (long i)))
        (throw (IndexOutOfBoundsException.)))))
  (nth [_ i not-found]
    (let [n (- end start)]
      (if (valid-index? n i)
        (ropetree/rope-nth root (+ start (long i)))
        not-found)))

  ILookup
  (valAt [this k]
    (.nth this k nil))
  (valAt [this k not-found]
    (.nth this k not-found))

  clojure.lang.IFn
  (invoke [this k]
    (.valAt this k))
  (invoke [this k not-found]
    (.valAt this k not-found))
  (applyTo [this args]
    (let [n (RT/boundedLength args 2)]
      (case n
        0 (throw (clojure.lang.ArityException. n (.. this getClass getSimpleName)))
        1 (.invoke this (first args))
        2 (.invoke this (first args) (second args))
        (throw (clojure.lang.ArityException. n (.. this getClass getSimpleName))))))

  Seqable
  (seq [this]
    (seq (subvec (vec (ropetree/rope-seq root)) start end)))

  Reversible
  (rseq [this]
    (rseq (vec (seq this))))

  Sequential

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. (seq this)))

  IReduceInit
  (reduce [this f init]
    (reduce f init (seq this)))

  IReduce
  (reduce [this f]
    (reduce f (seq this)))

  cp/CollReduce
  (coll-reduce [this f]
    (.reduce ^IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^IReduceInit this f init))

  IPersistentCollection
  (cons [_ o]
    (Rope. (ropetree/normalize-root
             (ropetree/rope-concat
               (ropetree/rope-subvec-root root start end)
               (ropetree/coll->root [o])))
      _meta))
  (empty [_]
    (RopeSlice. root start start _meta))
  (equiv [this o]
    (seq-equiv this o))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))
  (toString [this]
    (pr-str (vec this))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn empty-rope
  []
  (Rope. nil {}))

(defn rope
  ([] (empty-rope))
  ([coll]
   (Rope. (ropetree/coll->root coll) {})))

(defn- slice-root
  [^RopeSlice x]
  (ropetree/rope-subvec-root (.-root x) (.-start x) (.-end x)))

(defn- rope-root
  [x]
  (cond
    (instance? Rope x)
    (.-root ^Rope x)

    (instance? RopeSlice x)
    (slice-root x)

    :else
    (ropetree/coll->root x)))

(defn concat-rope
  [left right]
  (Rope. (ropetree/normalize-root
           (ropetree/rope-concat (rope-root left) (rope-root right)))
    (meta left)))

(defn rope-chunks
  [v]
  (ropetree/rope-chunks-seq (rope-root v)))

(defn rope-chunks-reverse
  [v]
  (ropetree/rope-chunks-rseq (rope-root v)))

(defn chunk-count
  [v]
  (count (ropetree/root->chunks (rope-root v))))

(defn split-rope-at
  [v i]
  (let [[l r] (ropetree/rope-split-at (rope-root v) (long i))]
    [(Rope. (ropetree/normalize-root l) (meta v))
     (Rope. (ropetree/normalize-root r) (meta v))]))

(defn subrope
  [v start end]
  (let [n (count v)]
    (when (or (neg? start) (neg? end) (> start end) (> end n))
      (throw (IndexOutOfBoundsException.)))
    (RopeSlice. (rope-root v) (long start) (long end) (meta v))))

(defn insert-rope-at
  [v i inserted]
  (let [n (count v)]
    (when (or (neg? i) (> i n))
      (throw (IndexOutOfBoundsException.)))
    (let [[l r] (split-rope-at v i)
          mid   (if (instance? Rope inserted) inserted (rope inserted))]
      (concat-rope (concat-rope l mid) r))))

(defn remove-rope-range
  [v start end]
  (let [n (count v)]
    (when (or (neg? start) (neg? end) (> start end) (> end n))
      (throw (IndexOutOfBoundsException.)))
    (let [[l r]    (split-rope-at v start)
          [_ rr]   (split-rope-at r (- end start))]
      (concat-rope l rr))))

(defn splice-rope
  [v start end inserted]
  (let [n (count v)]
    (when (or (neg? start) (neg? end) (> start end) (> end n))
      (throw (IndexOutOfBoundsException.)))
    (let [[l r]    (split-rope-at v start)
          [_ rr]   (split-rope-at r (- end start))
          mid      (if (instance? Rope inserted) inserted (rope inserted))]
      (concat-rope (concat-rope l mid) rr))))
