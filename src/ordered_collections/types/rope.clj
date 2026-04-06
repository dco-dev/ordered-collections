(ns ordered-collections.types.rope
  "Persistent rope-like indexed sequence backed by an implicit-index
  weight-balanced tree."
  (:require [clojure.core.protocols :as cp]
            [clojure.core.reducers :as r]
            [ordered-collections.parallel :as par]
            [ordered-collections.protocol :as proto]
            [ordered-collections.tree.rope :as ropetree])
  (:import  [clojure.lang RT Murmur3 MapEntry ILookup
                           Associative Indexed Seqable Reversible Sequential
                           IPersistentCollection IPersistentStack IObj IMeta
                           IEditableCollection ITransientCollection
                           IReduce IReduceInit SeqIterator Util]
            [java.util ArrayList]))


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

(defn- seq-compare
  "Lexicographic comparison of two sequential collections."
  [s1 s2]
  (loop [s1 (seq s1) s2 (seq s2)]
    (cond
      (nil? s1) (if (nil? s2) 0 -1)
      (nil? s2) 1
      :else
      (let [c (compare (first s1) (first s2))]
        (if (zero? c)
          (recur (next s1) (next s2))
          c)))))

(defn- linear-index-of
  "Linear scan for first index of x, or -1."
  [root x]
  (let [n      (ropetree/rope-size root)
        result (ropetree/rope-reduce
                 (fn [^long i elem]
                   (if (Util/equiv elem x)
                     (reduced i)
                     (unchecked-inc i)))
                 (long 0)
                 root)]
    (if (< (long result) n) (long result) -1)))

(defn- linear-last-index-of
  "Forward linear scan tracking the last matching index. O(n)."
  [root x]
  (let [result (ropetree/rope-reduce
                 (fn [[^long i ^long found] elem]
                   [(unchecked-inc i)
                    (if (Util/equiv elem x) i found)])
                 [(long 0) (long -1)]
                 root)]
    (long (second result))))

(defn- rope-to-array
  ^objects [root]
  (let [n   (ropetree/rope-size root)
        arr (object-array n)]
    (ropetree/rope-reduce
      (fn [^long i x]
        (aset arr i x)
        (unchecked-inc i))
      (long 0)
      root)
    arr))

(declare ->Rope)
(declare ->TransientRope)

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
    (if (valid-index? (ropetree/rope-size root) i)
      (ropetree/rope-nth root (long i))
      (throw (IndexOutOfBoundsException.))))
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

  Seqable
  (seq [_]
    (ropetree/rope-seq root))

  Reversible
  (rseq [_]
    (ropetree/rope-rseq root))

  Sequential

  java.lang.Comparable
  (compareTo [this o]
    (if (identical? this o)
      0
      (seq-compare this o)))

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

  r/CollFold
  (coll-fold [this n combinef reducef]
    (let [sz (ropetree/rope-size root)]
      (if (<= sz (long n))
        (.reduce ^IReduceInit this reducef (combinef))
        (let [mid (quot sz 2)
              [l r] (proto/rope-split this mid)
              fold* (fn fold* [^Rope child]
                      (let [csz (count child)]
                        (if (<= csz (long n))
                          (.reduce ^IReduceInit child reducef (combinef))
                          (let [cmid   (quot csz 2)
                                [cl cr] (proto/rope-split child cmid)]
                            (par/fork-join
                              [lv (fold* cl) rv (fold* cr)]
                              (combinef lv rv))))))]
          (if (par/in-fork-join-pool?)
            (par/fork-join
              [lv (fold* l) rv (fold* r)]
              (combinef lv rv))
            (par/invoke-root
              #(par/fork-join
                 [lv (fold* l) rv (fold* r)]
                 (combinef lv rv))))))))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  java.util.Collection
  (toArray [this]
    (rope-to-array root))
  (isEmpty [_]
    (nil? root))
  (^boolean contains [_ x]
    (not (neg? (linear-index-of root x))))
  (containsAll [this c]
    (every? #(.contains this %) c))
  (size [_]
    (ropetree/rope-size root))
  (add [_ _]
    (throw (UnsupportedOperationException.)))
  (addAll [_ _]
    (throw (UnsupportedOperationException.)))
  (^boolean remove [_ _]
    (throw (UnsupportedOperationException.)))
  (removeAll [_ _]
    (throw (UnsupportedOperationException.)))
  (retainAll [_ _]
    (throw (UnsupportedOperationException.)))
  (clear [_]
    (throw (UnsupportedOperationException.)))

  java.util.List
  (get [_ i]
    (if (valid-index? (ropetree/rope-size root) i)
      (ropetree/rope-nth root (long i))
      (throw (IndexOutOfBoundsException.))))
  (indexOf [_ x]
    (linear-index-of root x))
  (lastIndexOf [_ x]
    (linear-last-index-of root x))
  (set [_ _ _]
    (throw (UnsupportedOperationException.)))
  (subList [this from to]
    (proto/rope-sub this from to))

  proto/PRope
  (rope-concat [this other]
    (let [other-root (if (instance? Rope other)
                       (.-root ^Rope other)
                       (ropetree/coll->root other))]
      (Rope. (ropetree/rope-concat root other-root) _meta)))
  (rope-split [this i]
    (let [[l r] (ropetree/ensure-split-parts
                  (ropetree/rope-split-at root (long i)))]
      [(Rope. l _meta) (Rope. r _meta)]))
  (rope-sub [this start end]
    (let [n (ropetree/rope-size root)]
      (when (or (neg? start) (neg? end) (> start end) (> end n))
        (throw (IndexOutOfBoundsException.)))
      (Rope. (ropetree/rope-subvec-root root start end) _meta)))
  (rope-insert [this i coll]
    (let [n (ropetree/rope-size root)]
      (when (or (neg? i) (> i n))
        (throw (IndexOutOfBoundsException.)))
      (let [[l r] (proto/rope-split this i)
            mid   (if (instance? Rope coll) coll (Rope. (ropetree/coll->root coll) {}))]
        (proto/rope-concat (proto/rope-concat l mid) r))))
  (rope-remove [this start end]
    (let [n (ropetree/rope-size root)]
      (when (or (neg? start) (neg? end) (> start end) (> end n))
        (throw (IndexOutOfBoundsException.)))
      (let [[l r]  (proto/rope-split this start)
            [_ rr] (proto/rope-split r (- end start))]
        (proto/rope-concat l rr))))
  (rope-splice [this start end coll]
    (let [n (ropetree/rope-size root)]
      (when (or (neg? start) (neg? end) (> start end) (> end n))
        (throw (IndexOutOfBoundsException.)))
      (let [[l r]  (proto/rope-split this start)
            [_ rr] (proto/rope-split r (- end start))
            mid    (if (instance? Rope coll) coll (Rope. (ropetree/coll->root coll) {}))]
        (proto/rope-concat (proto/rope-concat l mid) rr))))
  (rope-chunks [this]
    (ropetree/rope-chunks-seq root))
  (rope-str [this]
    (ropetree/rope->str root))

  IEditableCollection
  (asTransient [_]
    (->TransientRope root (ArrayList.) true _meta))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))
  (toString [this]
    (pr-str (vec this))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transient Rope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype TransientRope [^:unsynchronized-mutable root
                        ^ArrayList tail
                        ^:unsynchronized-mutable edit
                        _meta]
  ITransientCollection
  (conj [this x]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (.add tail x)
    (when (>= (.size tail) ropetree/+target-chunk-size+)
      (let [chunk (vec tail)
            cnode (ropetree/chunks->root [chunk])]
        (.clear tail)
        (set! root (if (nil? root)
                     cnode
                     (ropetree/rope-concat root cnode)))))
    this)

  (persistent [this]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (set! edit false)
    (let [final-root (if (.isEmpty tail)
                       root
                       (let [cnode (ropetree/chunks->root [(vec tail)])]
                         (.clear tail)
                         (if (nil? root)
                           cnode
                           (ropetree/rope-concat root cnode))))]
      (Rope. final-root _meta)))

  clojure.lang.Counted
  (count [_]
    (+ (ropetree/rope-size root) (.size tail)))

  Indexed
  (nth [this i]
    (let [rs (ropetree/rope-size root)
          ts (.size tail)]
      (cond
        (and (>= i 0) (< i rs))   (ropetree/rope-nth root i)
        (< (- i rs) ts)            (.get tail (- i rs))
        :else                      (throw (IndexOutOfBoundsException.)))))
  (nth [this i not-found]
    (let [rs (ropetree/rope-size root)
          ts (.size tail)
          n  (+ rs ts)]
      (if (and (>= i 0) (< i n))
        (.nth this i)
        not-found))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rope-empty
  []
  (Rope. nil {}))

(defn rope
  ([] (rope-empty))
  ([coll]
   (Rope. (ropetree/coll->root coll) {})))

(defn- rope-root
  [x]
  (if (instance? Rope x)
    (.-root ^Rope x)
    (ropetree/coll->root x)))

(defn rope-concat-all
  "Bulk concatenation of rope values or rope-coercible collections.
  Collects all chunks and builds the tree directly in O(total chunks),
  avoiding pairwise tree operations."
  [& xs]
  (Rope. (ropetree/chunks->root-csi
           (into [] (mapcat (comp ropetree/root->chunks rope-root)) xs))
    {}))

(defn rope-chunks-reverse
  [v]
  (ropetree/rope-chunks-rseq (rope-root v)))

(defn rope-chunk-count
  [v]
  (count (ropetree/root->chunks (rope-root v))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Print Methods
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method Rope [^Rope r ^java.io.Writer w]
  (.write w "#ordered/rope ")
  (print-method (vec r) w))
