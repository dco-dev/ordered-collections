(ns ordered-collections.types.string-rope
  "Persistent string rope optimized for structural text editing.
  Backed by a chunked weight-balanced tree with String chunks.
  O(log n) concat, split, splice, insert, and remove.
  Implements CharSequence for seamless Java interop.

  Small strings (≤ +flat-threshold+ chars) are stored as a raw String
  internally, giving String-equivalent performance on read operations.
  When edits grow the content past the threshold, the representation
  is transparently promoted to the chunked tree form."
  (:require [clojure.core.protocols :as cp]
            [clojure.core.reducers :as r]
            [ordered-collections.protocol :as proto]
            [ordered-collections.kernel.node :as node
             :refer [leaf leaf? -k -v -l -r]]
            [ordered-collections.kernel.tree :as tree]
            [ordered-collections.kernel.rope :as ropetree])
  (:import  [clojure.lang RT Murmur3 Util SeqIterator
                           IReduce IReduceInit
                           IEditableCollection ITransientCollection]
            [ordered_collections.kernel.root INodeCollection]
            [java.util ArrayList]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const ^:private +flat-threshold+
  "Maximum string length stored in flat (raw String) representation.
  Above this, the rope promotes to chunked tree form. Set to match
  the crossover point where tree edits outperform StringBuilder."
  1024)

(def ^:const ^:private +target-chunk-size+
  "StringRope target chunk size in characters. Bound into the kernel's
  `*target-chunk-size*` dynamic var via `with-tree`. Tuned via
  `lein bench-rope-tuning`: 1024 wins on every operation at N=500K
  vs the historical 256, because JEP 254 compact strings make larger
  String chunks proportionally cheaper than vector chunks."
  1024)

(def ^:const ^:private +min-chunk-size+
  "StringRope minimum internal chunk size (= target/2)."
  512)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree binding macro
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro ^:private with-tree
  "Bind the kernel's dynamic rope context for StringRope operations:
  `tree/*t-join*` to the allocator, and the CSI target/min to the
  StringRope-specific constants. Every tree-mutating operation must
  execute inside this binding."
  [alloc & body]
  `(binding [tree/*t-join*                ~alloc
             ropetree/*target-chunk-size* +target-chunk-size+
             ropetree/*min-chunk-size*    +min-chunk-size+]
     ~@body))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Flat-mode helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- flat?
  "True when root is a raw String (flat representation)."
  [root]
  (string? root))

(defn- flat-size
  "Size of a flat or tree root. Handles nil, String, and tree nodes."
  ^long [root]
  (cond
    (nil? root)     0
    (string? root)  (.length ^String root)
    :else           (long (-v root))))

(defn- ensure-tree-root
  "Promote a flat String root to a tree root. Returns tree nodes unchanged.
  Caller must bind tree/*t-join*."
  [root alloc]
  (cond
    (nil? root)     nil
    (string? root)  (ropetree/str->root ^String root)
    :else           root))

(defn- flat-splice
  "StringBuilder-based splice on a flat String. Returns a String."
  ^String [^String s ^long start ^long end ^String rep]
  (let [si   (int start)
        ei   (int end)
        rlen (int (if rep (.length rep) 0))
        sb   (StringBuilder. (+ (.length s) rlen (- si) ei))]
    (.append sb s 0 si)
    (when rep (.append sb rep))
    (.append sb s ei (.length s))
    (.toString sb)))

(defn- make-root
  "Create a StringRope root from a String result. Stays flat if ≤ threshold,
  otherwise promotes to tree. Caller must bind tree/*t-join* for promotion."
  [^String s alloc]
  (cond
    (zero? (.length s))          nil
    (<= (.length s) +flat-threshold+) s
    :else                        (ropetree/str->root s)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- valid-index?
  [^long n ^long k]
  (and (<= 0 k) (< k n)))

(defn- insert-index?
  [^long n ^long k]
  (and (<= 0 k) (<= k n)))

(defn- check-index!
  [^long n ^long k]
  (when-not (valid-index? n k)
    (throw (IndexOutOfBoundsException.))))

(defn- check-insert-index!
  [^long n ^long k]
  (when-not (insert-index? n k)
    (throw (IndexOutOfBoundsException.))))

(defn- check-range!
  [^long start ^long end ^long n]
  (when (or (neg? start) (neg? end) (> start end) (> end n))
    (throw (IndexOutOfBoundsException.))))

(defn- safe-split-index
  "Adjust split index to avoid splitting a UTF-16 surrogate pair.
  If i lands between a high and low surrogate, moves back one position."
  ^long [^String s ^long i]
  (if (and (pos? i) (< i (.length s))
           (Character/isHighSurrogate (.charAt s (unchecked-int (dec i)))))
    (dec i)
    i))

(declare ->string-rope ->TransientStringRope ->StringRope*
         coll->str coll->tree-root)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monomorphic tree reduce (same rationale as byte_rope.clj)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- string-rope-tree-reduce
  "Reduce `f` over every character in the string-rope tree rooted at `n`.
  Monomorphic — assumes chunks are String. Returns acc or Reduced."
  [f acc n]
  (if (leaf? n)
    acc
    (let [l        (-l n)
          acc-left (if (leaf? l) acc (string-rope-tree-reduce f acc l))]
      (if (reduced? acc-left)
        acc-left
        (let [^String ck (-k n)
              len        (.length ck)
              acc-chunk
              (loop [i (int 0), acc acc-left]
                (if (< i len)
                  (let [ret (f acc (Character/valueOf (.charAt ck (unchecked-int i))))]
                    (if (reduced? ret)
                      ret
                      (recur (unchecked-inc-int i) ret)))
                  acc))]
          (if (reduced? acc-chunk)
            acc-chunk
            (string-rope-tree-reduce f acc-chunk (-r n))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; StringRopeSeq — forward seq over String chunks using .charAt
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seq-equiv
  [s1 o]
  (if-not (or (instance? clojure.lang.Sequential o)
              (instance? java.util.List o))
    false
    (loop [s1 (seq s1) s2 (seq o)]
      (cond
        (nil? s1) (nil? s2)
        (nil? s2) false
        (not= (first s1) (first s2)) false
        :else (recur (next s1) (next s2))))))

(deftype StringRopeSeq [enum ^String chunk ^long i cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (Character/valueOf (.charAt chunk (unchecked-int i))))
  (next [_]
    (let [next-cnt (when cnt (unchecked-dec-int cnt))
          next-i   (unchecked-inc i)]
      (if (< next-i (.length chunk))
        (StringRopeSeq. enum chunk next-i next-cnt nil)
        (when-let [e (tree/node-enum-rest enum)]
          (let [chunk' ^String (-k (tree/node-enum-first e))]
            (StringRopeSeq. e chunk' 0 next-cnt nil))))))
  (more [this]
    (or (.next this) ()))
  (cons [this o]
    (clojure.lang.Cons. o this))

  clojure.lang.Seqable
  (seq [this] this)

  clojure.lang.Sequential

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. this))

  clojure.lang.Counted
  (count [_]
    (or cnt
        (loop [e enum
               ^String chunk chunk
               i i
               n 0]
          (let [n (+ n (- (.length chunk) i))]
            (if-let [e' (tree/node-enum-rest e)]
              (let [chunk' ^String (-k (tree/node-enum-first e'))]
                (recur e' chunk' 0 n))
              n)))))

  IReduceInit
  (reduce [_ f init]
    (loop [e enum
           ^String chunk chunk
           i (int i)
           acc init]
      (let [acc (loop [idx i acc acc]
                  (if (< idx (.length chunk))
                    (let [ret (f acc (Character/valueOf (.charAt chunk (unchecked-int idx))))]
                      (if (reduced? ret)
                        ret
                        (recur (unchecked-inc-int idx) ret)))
                    acc))]
        (if (reduced? acc)
          @acc
          (if-let [e' (tree/node-enum-rest e)]
            (let [chunk' ^String (-k (tree/node-enum-first e'))]
              (recur e' chunk' (int 0) acc))
            acc)))))

  IReduce
  (reduce [this f]
    (if enum
      (let [acc (Character/valueOf (.charAt chunk (unchecked-int i)))
            next-i (unchecked-inc i)]
        (if (< next-i (.length chunk))
          (.reduce ^IReduceInit
            (StringRopeSeq. enum chunk next-i nil nil) f acc)
          (if-let [e' (tree/node-enum-rest enum)]
            (let [chunk' ^String (-k (tree/node-enum-first e'))]
              (.reduce ^IReduceInit
                (StringRopeSeq. e' chunk' 0 nil nil) f acc))
            acc)))
      (f)))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  clojure.lang.IPersistentCollection
  (empty [_] ())
  (equiv [this o]
    (seq-equiv this o))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (StringRopeSeq. enum chunk i cnt m)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; StringRopeSeqReverse — reverse seq over String chunks using .charAt
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype StringRopeSeqReverse [enum ^String chunk ^long i cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (Character/valueOf (.charAt chunk (unchecked-int i))))
  (next [_]
    (let [next-cnt (when cnt (unchecked-dec-int cnt))]
      (if (pos? i)
        (StringRopeSeqReverse. enum chunk (unchecked-dec i) next-cnt nil)
        (when-let [e (tree/node-enum-prior enum)]
          (let [chunk' ^String (-k (tree/node-enum-first e))]
            (StringRopeSeqReverse. e chunk' (dec (.length chunk')) next-cnt nil))))))
  (more [this]
    (or (.next this) ()))
  (cons [this o]
    (clojure.lang.Cons. o this))

  clojure.lang.Seqable
  (seq [this] this)

  clojure.lang.Sequential

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. this))

  clojure.lang.Counted
  (count [_]
    (or cnt
        (loop [e enum
               ^String chunk chunk
               i i
               n 0]
          (let [n (+ n (inc i))]
            (if-let [e' (tree/node-enum-prior e)]
              (let [chunk' ^String (-k (tree/node-enum-first e'))]
                (recur e' chunk' (dec (.length chunk')) n))
              n)))))

  IReduceInit
  (reduce [_ f init]
    (loop [e enum
           ^String chunk chunk
           i (int i)
           acc init]
      (let [acc (loop [idx i acc acc]
                  (if (neg? idx)
                    acc
                    (let [ret (f acc (Character/valueOf (.charAt chunk (unchecked-int idx))))]
                      (if (reduced? ret)
                        ret
                        (recur (unchecked-dec-int idx) ret)))))]
        (if (reduced? acc)
          @acc
          (if-let [e' (tree/node-enum-prior e)]
            (let [chunk' ^String (-k (tree/node-enum-first e'))]
              (recur e' chunk' (int (dec (.length chunk'))) acc))
            acc)))))

  IReduce
  (reduce [this f]
    (if enum
      (let [acc (Character/valueOf (.charAt chunk (unchecked-int i)))]
        (if (pos? i)
          (.reduce ^IReduceInit
            (StringRopeSeqReverse. enum chunk (unchecked-dec i) nil nil) f acc)
          (if-let [e' (tree/node-enum-prior enum)]
            (let [chunk' ^String (-k (tree/node-enum-first e'))]
              (.reduce ^IReduceInit
                (StringRopeSeqReverse. e' chunk' (dec (.length chunk')) nil nil) f acc))
            acc)))
      (f)))

  clojure.lang.IHashEq
  (hasheq [this]
    (Murmur3/hashOrdered this))

  clojure.lang.IPersistentCollection
  (empty [_] ())
  (equiv [this o]
    (seq-equiv this o))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (Util/equals this o))

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (StringRopeSeqReverse. enum chunk i cnt m)))


(defn- string-rope-seq
  [root]
  (cond
    (nil? root) nil
    (string? root)
    (let [^String s root]
      (when (pos? (.length s))
        ;; Flat mode: use the whole string as a single chunk with nil enum.
        ;; node-enum-rest handles nil safely, returning nil at end of chunk.
        (StringRopeSeq. nil s 0 (.length s) nil)))
    :else
    (when-let [enum (tree/node-enumerator root)]
      (let [chunk ^String (-k (tree/node-enum-first enum))]
        (StringRopeSeq. enum chunk 0 (ropetree/rope-size root) nil)))))

(defn- string-rope-rseq
  [root]
  (cond
    (nil? root) nil
    (string? root)
    (let [^String s root]
      (when (pos? (.length s))
        (StringRopeSeqReverse. nil s (dec (.length s)) (.length s) nil)))
    :else
    (when-let [enum (tree/node-enumerator-reverse root)]
      (let [chunk ^String (-k (tree/node-enum-first enum))]
        (StringRopeSeqReverse. enum chunk (dec (.length chunk))
          (ropetree/rope-size root) nil)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Equality / Hashing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- string-rope-equiv
  "CharSequence-based equality. StringRope equals:
  - another StringRope with same content
  - a java.lang.String with same content
  - any CharSequence with same content
  Does NOT equal a generic Rope of Characters."
  [this o]
  (cond
    (identical? this o) true

    (instance? CharSequence o)
    (let [^CharSequence cs1 this
          ^CharSequence cs2 o
          n (.length cs1)]
      (and (= n (.length cs2))
           (loop [i 0]
             (if (= i n)
               true
               (if (= (.charAt cs1 i) (.charAt cs2 i))
                 (recur (unchecked-inc i))
                 false)))))

    :else false))

(defn- string-rope-hasheq
  "Hash compatible with String's hasheq for value equality.
  Clojure strings use Murmur3/hashInt on String.hashCode."
  [root]
  (if (string? root)
    (Murmur3/hashInt (.hashCode ^String root))
    (Murmur3/hashInt (.hashCode (ropetree/rope->str root)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; StringRope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype StringRope [root alloc _meta]

  java.io.Serializable

  INodeCollection
  (getAllocator [_] alloc)

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m] (->StringRope* root alloc m))

  java.lang.CharSequence
  ;; Monomorphic charAt — inlines the tree walk with direct `.length`/`.charAt`
  ;; on the known String chunks, bypassing PRopeChunk protocol dispatch.
  (charAt [_ i]
    (let [ii (long i)
          n  (flat-size root)]
      (when-not (valid-index? n ii)
        (throw (StringIndexOutOfBoundsException. (int i))))
      (if (string? root)
        (.charAt ^String root (int i))
        (loop [nd root, j ii]
          (let [l  (-l nd)
                ls (if (leaf? l) 0 (long (-v l)))
                ^String ck (-k nd)
                cs (long (.length ck))
                rs (+ ls cs)]
            (cond
              (< j ls) (recur l j)
              (< j rs) (.charAt ck (unchecked-int (- j ls)))
              :else    (recur (-r nd) (- j rs))))))))
  (length [_]
    (flat-size root))
  (subSequence [_ start end]
    (if (string? root)
      (let [^String s root
            n (.length s)]
        (check-range! (long start) (long end) n)
        (->StringRope* (.substring s (int start) (int end)) alloc _meta))
      (let [n (ropetree/rope-size root)]
        (check-range! (long start) (long end) n)
        (with-tree alloc
          (->StringRope* (ropetree/rope-subvec-root root (long start) (long end))
            alloc _meta)))))
  (toString [_]
    (cond
      (nil? root)     ""
      (string? root)  root
      :else           (ropetree/rope->str root)))

  java.lang.Comparable
  (compareTo [this o]
    (if (identical? this o)
      0
      (let [^CharSequence cs (if (instance? CharSequence o)
                               o
                               (str o))
            n1 (.length ^CharSequence this)
            n2 (.length cs)]
        (loop [i 0]
          (cond
            (and (= i n1) (= i n2)) 0
            (= i n1) -1
            (= i n2) 1
            :else
            (let [c (compare (.charAt ^CharSequence this i) (.charAt cs i))]
              (if (zero? c)
                (recur (unchecked-inc i))
                c)))))))

  clojure.lang.Counted
  (count [_]
    (flat-size root))

  clojure.lang.Indexed
  ;; Monomorphic nth — same tree walk as charAt, returns Character (boxed)
  ;; per the Indexed contract.
  (nth [_ i]
    (let [ii (long i)
          n  (flat-size root)]
      (when-not (valid-index? n ii)
        (throw (IndexOutOfBoundsException.)))
      (if (string? root)
        (Character/valueOf (.charAt ^String root (unchecked-int ii)))
        (loop [nd root, j ii]
          (let [l  (-l nd)
                ls (if (leaf? l) 0 (long (-v l)))
                ^String ck (-k nd)
                cs (long (.length ck))
                rs (+ ls cs)]
            (cond
              (< j ls) (recur l j)
              (< j rs) (Character/valueOf (.charAt ck (unchecked-int (- j ls))))
              :else    (recur (-r nd) (- j rs))))))))
  (nth [this i not-found]
    (if (and (integer? i) (valid-index? (flat-size root) (long i)))
      (.nth this (int i))
      not-found))

  clojure.lang.ILookup
  (valAt [this k]
    (if (integer? k)
      (.nth this (int k) nil)
      nil))
  (valAt [this k not-found]
    (if (integer? k)
      (.nth this (int k) not-found)
      not-found))

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

  clojure.lang.IPersistentCollection
  (cons [_ o]
    (if (string? root)
      (let [^String s root
            c (char o)]
        (if (< (.length s) +flat-threshold+)
          (let [sb (StringBuilder. (unchecked-inc-int (.length s)))]
            (.append sb s)
            (.append sb c)
            (->StringRope* (.toString sb) alloc _meta))
          (with-tree alloc
            (->StringRope* (ropetree/rope-conj-right (ropetree/str->root s) c)
              alloc _meta))))
      (if (nil? root)
        (->StringRope* (String/valueOf (char o)) alloc _meta)
        (with-tree alloc
          (->StringRope* (ropetree/rope-conj-right root (char o)) alloc _meta)))))
  (empty [_]
    (->StringRope* nil alloc _meta))
  (equiv [this o]
    (string-rope-equiv this o))

  clojure.lang.IPersistentStack
  (peek [_]
    (cond
      (nil? root)     nil
      (string? root)  (let [^String s root]
                        (when (pos? (.length s))
                          (Character/valueOf (.charAt s (unchecked-dec-int (.length s))))))
      :else           (let [c (ropetree/rope-peek-right root)]
                        (if (instance? Character c) c (Character/valueOf (char c))))))
  (pop [_]
    (if (string? root)
      (let [^String s root
            n (.length s)]
        (cond
          (<= n 1) (->StringRope* nil alloc _meta)
          :else    (->StringRope* (.substring s 0 (unchecked-dec-int n)) alloc _meta)))
      (with-tree alloc
        (->StringRope* (ropetree/rope-pop-right root) alloc _meta))))

  clojure.lang.Seqable
  (seq [_]
    (string-rope-seq root))

  clojure.lang.Reversible
  (rseq [_]
    (string-rope-rseq root))

  clojure.lang.Sequential

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. (seq this)))

  IReduceInit
  (reduce [_ f init]
    (if (string? root)
      (let [^String s root
            len (.length s)]
        (loop [i (int 0), acc init]
          (if (< i len)
            (let [ret (f acc (Character/valueOf (.charAt s (unchecked-int i))))]
              (if (reduced? ret)
                @ret
                (recur (unchecked-inc-int i) ret)))
            acc)))
      (let [result (string-rope-tree-reduce f init root)]
        (if (reduced? result) @result result))))

  IReduce
  (reduce [_ f]
    (if (string? root)
      (let [^String s root
            len (.length s)]
        (if (zero? len)
          (f)
          (loop [i (int 1), acc (Character/valueOf (.charAt s 0))]
            (if (< i len)
              (let [ret (f acc (Character/valueOf (.charAt s (unchecked-int i))))]
                (if (reduced? ret)
                  @ret
                  (recur (unchecked-inc-int i) ret)))
              acc))))
      ;; Tree mode: seed from first char of leftmost chunk, then walk the rest.
      (let [^ordered_collections.kernel.node.INode least (tree/node-least root)
            ^String first-chunk (-k least)
            first-char (Character/valueOf (.charAt first-chunk 0))
            rest-root  (tree/node-remove-least root)
            rest-of-first-chunk-acc
            (let [len (.length first-chunk)]
              (loop [i (int 1), acc first-char]
                (if (< i len)
                  (let [ret (f acc (Character/valueOf (.charAt first-chunk (unchecked-int i))))]
                    (if (reduced? ret) ret (recur (unchecked-inc-int i) ret)))
                  acc)))]
        (if (reduced? rest-of-first-chunk-acc)
          @rest-of-first-chunk-acc
          (let [result (if (leaf? rest-root)
                         rest-of-first-chunk-acc
                         (string-rope-tree-reduce f rest-of-first-chunk-acc rest-root))]
            (if (reduced? result) @result result))))))

  cp/CollReduce
  (coll-reduce [this f]
    (.reduce ^IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^IReduceInit this f init))

  r/CollFold
  (coll-fold [this n combinef reducef]
    (cond
      (nil? root)    (combinef)
      (string? root) (.reduce ^IReduceInit this reducef (combinef))
      :else          (ropetree/rope-fold root (long n) combinef reducef)))

  clojure.lang.IHashEq
  (hasheq [_]
    (string-rope-hasheq root))

  clojure.lang.Associative
  (containsKey [_ k]
    (and (integer? k) (valid-index? (flat-size root) (long k))))
  (entryAt [this k]
    (when (.containsKey this k)
      (clojure.lang.MapEntry. k (.nth ^clojure.lang.Indexed this (int k)))))
  (assoc [this k v]
    (let [i (long k)
          n (flat-size root)]
      (cond
        (not (insert-index? n i))
        (throw (IndexOutOfBoundsException.))

        (= i n)
        (.cons this v)

        :else
        (if (string? root)
          (let [^String s root
                sb (StringBuilder. (.length s))]
            (.append sb s 0 (int i))
            (.append sb (char v))
            (.append sb s (unchecked-inc-int (int i)) (.length s))
            (->StringRope* (.toString sb) alloc _meta))
          (with-tree alloc
            (->StringRope* (ropetree/rope-assoc root i (char v)) alloc _meta))))))

  java.util.Collection
  (toArray [this]
    (if (string? root)
      (let [^String s root
            n (.length s)
            arr (object-array n)]
        (dotimes [i n]
          (aset arr i (Character/valueOf (.charAt s i))))
        arr)
      (let [n (ropetree/rope-size root)
            arr (object-array n)]
        (ropetree/rope-reduce
          (fn [^long i x]
            (aset arr i (if (instance? Character x) x (Character/valueOf (char x))))
            (unchecked-inc i))
          (long 0)
          root)
        arr)))
  (isEmpty [_]
    (nil? root))
  (^boolean contains [_ x]
    (if (instance? Character x)
      (let [^Character ch x
            target (.charValue ch)]
        (if (string? root)
          (let [^String s root]
            (not= -1 (.indexOf s (int target))))
          (true?
            (ropetree/rope-reduce
              (fn [_ c]
                (if (= (char c) target) (reduced true) false))
              false
              root))))
      false))
  (containsAll [this c]
    (every? #(.contains this %) c))
  (size [_]
    (flat-size root))
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

  proto/PRope
  (rope-cat [this other]
    (when-not (or (string? other) (instance? StringRope other))
      (throw (IllegalArgumentException.
               "StringRope rope-cat requires a StringRope or String")))
    (let [^String s1 (cond (nil? root)     ""
                           (string? root)  root
                           :else           nil)
          ^String s2 (if (string? other)
                       other
                       (let [r (.-root ^StringRope other)]
                         (cond (nil? r)     ""
                               (string? r)  r
                               :else        nil)))]
      ;; Fast path: both sides are flat strings and combined fits threshold
      (if (and s1 s2 (<= (+ (.length s1) (.length s2)) +flat-threshold+))
        (let [result (str s1 s2)]
          (->StringRope* (when (pos? (.length result)) result) alloc _meta))
        ;; Tree path
        (with-tree alloc
          (let [l (ensure-tree-root root alloc)
                r (if (string? other)
                    (ropetree/str->root ^String other)
                    (ensure-tree-root (.-root ^StringRope other) alloc))]
            (->StringRope* (ropetree/rope-concat l r) alloc _meta))))))
  (rope-split [_ i]
    (let [n (flat-size root)]
      (check-insert-index! n (long i))
      (if (string? root)
        (let [^String s root
              ii (int i)]
          [(->StringRope* (when (pos? ii) (.substring s 0 ii)) alloc _meta)
           (->StringRope* (when (< ii (.length s)) (.substring s ii)) alloc _meta)])
        (with-tree alloc
          (let [[l r] (ropetree/ensure-split-parts
                        (ropetree/rope-split-at root (long i)))]
            [(->StringRope* l alloc _meta) (->StringRope* r alloc _meta)])))))
  (rope-sub [_ start end]
    (let [n (flat-size root)]
      (check-range! (long start) (long end) n)
      (if (string? root)
        (let [^String s root
              result (.substring s (int start) (int end))]
          (->StringRope* (when (pos? (.length result)) result) alloc _meta))
        (with-tree alloc
          (->StringRope* (ropetree/rope-subvec-root root (long start) (long end))
            alloc _meta)))))
  (rope-insert [this i coll]
    (let [n (flat-size root)
          ii (long i)]
      (check-insert-index! n ii)
      (if (string? root)
        (let [result (flat-splice (or ^String root "") ii ii (coll->str coll))]
          (with-tree alloc
            (->StringRope* (make-root result alloc) alloc _meta)))
        (let [^String ins (coll->str coll)
              ins-len (.length ins)]
          (or (when (<= ins-len +target-chunk-size+)
                (when-let [new-root (ropetree/rope-splice-inplace
                                      root ii ii (when (pos? ins-len) ins) alloc)]
                  (->StringRope* new-root alloc _meta)))
              (with-tree alloc
                (->StringRope* (ropetree/rope-insert-root root ii
                                 (coll->tree-root coll alloc))
                  alloc _meta)))))))
  (rope-remove [this start end]
    (let [n (flat-size root)]
      (check-range! (long start) (long end) n)
      (if (string? root)
        (let [^String s root
              ^String result (flat-splice s (long start) (long end) nil)]
          (->StringRope* (when (pos? (.length result)) result) alloc _meta))
        (or (when-let [new-root (ropetree/rope-splice-inplace
                                  root (long start) (long end) nil alloc)]
              (->StringRope* new-root alloc _meta))
            (with-tree alloc
              (->StringRope* (ropetree/rope-remove-root root (long start) (long end))
                alloc _meta))))))
  (rope-splice [this start end coll]
    (let [n  (flat-size root)
          si (long start)
          ei (long end)]
      (check-range! si ei n)
      (if (string? root)
        (let [result (flat-splice (or ^String root "") si ei (coll->str coll))]
          (with-tree alloc
            (->StringRope* (make-root result alloc) alloc _meta)))
        (let [^String rep (coll->str coll)
              rep-len (.length rep)]
          (or (when (<= rep-len +target-chunk-size+)
                (when-let [new-root (ropetree/rope-splice-inplace
                                      root si ei (when (pos? rep-len) rep) alloc)]
                  (->StringRope* new-root alloc _meta)))
              (with-tree alloc
                (let [mid-root (when (pos? rep-len)
                                 (coll->tree-root coll alloc))]
                  (->StringRope* (ropetree/rope-splice-root root si ei mid-root)
                    alloc _meta))))))))
  (rope-chunks [_]
    (cond
      (nil? root)     nil
      (string? root)  (list root)
      :else           (ropetree/rope-chunks-seq root)))
  (rope-str [_]
    (cond
      (nil? root)     ""
      (string? root)  root
      :else           (ropetree/rope->str root)))

  IEditableCollection
  (asTransient [_]
    (->TransientStringRope
      (ensure-tree-root root alloc)
      alloc (ArrayList.) (StringBuilder.) 0 true _meta))

  Object
  (hashCode [this]
    (.hashCode (.toString this)))
  (equals [this o]
    (string-rope-equiv this o)))


(defn- ->StringRope*
  "Construct a StringRope."
  [root alloc meta]
  (StringRope. root alloc meta))

(defn- coll->str
  "Coerce a splice/insert argument to a String."
  ^String [coll]
  (cond
    (string? coll)               coll
    (instance? StringRope coll)  (.toString ^StringRope coll)
    :else                        (str coll)))

(defn- coll->tree-root
  "Coerce a splice/insert argument to a tree root for the multi-traversal
  fallback. Preserves existing StringRope tree structure when possible."
  [coll alloc]
  (cond
    (instance? StringRope coll)
    (ensure-tree-root (.-root ^StringRope coll) alloc)
    (string? coll) (ropetree/str->root ^String coll)
    :else          (ropetree/str->root (str coll))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TransientStringRope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- transient-string-appended-root
  "Build a rope tree from flushed chunks + tail. Caller must bind *t-join*."
  [^ArrayList chunks ^StringBuilder tail]
  (let [chunk-count (.size chunks)
        tail-empty? (zero? (.length tail))]
    (cond
      (and (zero? chunk-count) tail-empty?)
      nil

      (zero? chunk-count)
      (ropetree/chunks->root [(.toString tail)])

      tail-empty?
      (ropetree/chunks->root (vec chunks))

      :else
      (ropetree/chunks->root-csi
        (conj (vec chunks) (.toString tail))))))

(def ^:private ^:const +transient-rebuild-threshold+ 4)

(defn- transient-string-final-root
  "Merge original root with appended chunks/tail. Caller must bind *t-join*."
  [root ^ArrayList chunks ^StringBuilder tail]
  (cond
    (and (zero? (.size chunks)) (zero? (.length tail)))
    root

    ;; Fast path: small tail, no flushed chunks — build from tail string
    (and (zero? (.size chunks)) (<= (.length tail) 32))
    (let [s (.toString tail)]
      (if (nil? root)
        ;; Empty root — just make a single chunk node from the tail
        (tree/*t-join* s nil (leaf) (leaf))
        ;; Non-empty root — conj chars individually
        (let [n (.length s)]
          (loop [i 0, r root]
            (if (< i n)
              (recur (unchecked-inc i) (ropetree/rope-conj-right r (.charAt s i)))
              r)))))

    :else
    (let [appended-root (transient-string-appended-root chunks tail)
          appended-chunks (+ (.size chunks) (if (zero? (.length tail)) 0 1))]
      (cond
        (nil? root)
        appended-root

        (<= appended-chunks +transient-rebuild-threshold+)
        (ropetree/rope-concat root appended-root)

        :else
        (ropetree/chunks->root-csi
          (cond-> (vec (ropetree/root->chunks root))
            (pos? (.size chunks)) (into (vec chunks))
            (pos? (.length tail)) (conj (.toString tail))))))))

(deftype TransientStringRope [^:unsynchronized-mutable root
                               alloc
                               ^ArrayList chunks
                               ^StringBuilder tail
                               ^:unsynchronized-mutable chunk-chars
                               ^:unsynchronized-mutable edit
                               _meta]
  ITransientCollection
  (conj [this x]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (.append tail (char x))
    (when (>= (.length tail) +target-chunk-size+)
      (.add chunks (.toString tail))
      (set! chunk-chars (+ chunk-chars (.length tail)))
      (.setLength tail 0))
    this)

  (persistent [_]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (set! edit false)
    (with-tree alloc
      (let [tree-root (transient-string-final-root root chunks tail)
            ;; Demote to flat if the result is small enough
            final-root (if (and tree-root
                                (<= (ropetree/rope-size tree-root) +flat-threshold+))
                         (ropetree/rope->str tree-root)
                         tree-root)]
        (->StringRope* final-root alloc _meta))))

  clojure.lang.Counted
  (count [_]
    (+ (ropetree/rope-size root) chunk-chars (.length tail)))

  clojure.lang.Indexed
  (nth [this i]
    (let [rs (ropetree/rope-size root)
          j  (- (long i) rs)]
      (cond
        (and (>= i 0) (< i rs))
        (let [c (ropetree/rope-nth root (long i))]
          (if (instance? Character c) c (Character/valueOf (char c))))

        (and (>= j 0) (< j chunk-chars))
        (let [chunk-idx (quot j +target-chunk-size+)
              offset    (rem j +target-chunk-size+)
              ^String chunk (.get chunks (int chunk-idx))]
          (Character/valueOf (.charAt chunk (unchecked-int offset))))

        (< (- j chunk-chars) (.length tail))
        (Character/valueOf (.charAt tail (unchecked-int (- j chunk-chars))))

        :else
        (throw (IndexOutOfBoundsException.)))))
  (nth [this i not-found]
    (let [rs (ropetree/rope-size root)
          n  (+ rs chunk-chars (.length tail))]
      (if (and (>= (long i) 0) (< (long i) n))
        (.nth this (int i))
        not-found))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn string-rope
  "Create a persistent string rope for structural text editing.
  Backed by a chunked weight-balanced tree: O(log n) concat, split,
  splice, insert, and remove. Competitive at all sizes, dominant at scale.

  Small strings (≤ 1024 chars) are stored in flat String representation
  for zero-overhead reads. Edits that grow past the threshold are
  transparently promoted to the chunked tree form.

  Implements CharSequence for seamless Java interop.

  Examples:
    (string-rope)                        ;=> #string/rope \"\"
    (string-rope \"hello world\")          ;=> #string/rope \"hello world\"
    (string-rope (slurp \"big-file.txt\")) ;=> efficient chunked representation"
  ([] (->StringRope* nil ropetree/string-rope-node-create {}))
  ([s] (let [^String text (str s)]
         (cond
           (zero? (.length text))
           (->StringRope* nil ropetree/string-rope-node-create {})

           (<= (.length text) +flat-threshold+)
           (->StringRope* text ropetree/string-rope-node-create {})

           :else
           (with-tree ropetree/string-rope-node-create
             (->StringRope* (ropetree/str->root text)
               ropetree/string-rope-node-create {}))))))

(defn string-rope-concat
  "Concatenate string ropes or strings.
  One argument: returns it as a string rope.
  Two arguments: O(log n) binary tree join.
  Three or more: O(total chunks) bulk construction."
  ([x]
   (->string-rope x))
  ([left right]
   (proto/rope-cat (->string-rope left) (->string-rope right)))
  ([left right & more]
   (with-tree ropetree/string-rope-node-create
     (let [alloc ropetree/string-rope-node-create
           all   (list* left right more)
           chunks (into []
                    (mapcat (fn [x]
                              (let [r (->string-rope x)
                                    rt (.-root ^StringRope r)]
                                (cond
                                  (nil? rt)     []
                                  (string? rt)  [rt]
                                  :else         (ropetree/root->chunks rt)))))
                    all)]
       (->StringRope* (ropetree/chunks->root-csi chunks)
         alloc (or (meta left) {}))))))

(defn- ->string-rope
  "Coerce x to a StringRope."
  [x]
  (cond
    (instance? StringRope x) x
    (string? x) (string-rope x)
    :else (string-rope (str x))))

(defn read-string-rope
  "Reader function for #string/rope tagged literals."
  [s]
  (string-rope s))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method StringRope [^StringRope r ^java.io.Writer w]
  (.write w "#string/rope ")
  (print-method (.toString r) w))
