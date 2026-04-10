(ns ordered-collections.types.byte-rope
  "Persistent byte rope optimized for structural editing of binary data.
  Backed by a chunked weight-balanced tree with byte[] chunks.
  O(log n) concat, split, splice, insert, and remove.

  Bytes are exposed as unsigned longs in [0, 255]. Storage is signed Java
  bytes (same bits). Equality with byte[] is content-based; equality with
  Clojure vectors is always false to avoid signed/unsigned confusion.

  Small byte sequences (≤ +flat-threshold+ bytes) are stored as a raw
  byte[] internally, giving byte[]-equivalent performance on reads. When
  edits grow past the threshold, the representation is transparently
  promoted to chunked tree form."
  (:require [clojure.core.protocols :as cp]
            [clojure.core.reducers :as r]
            [ordered-collections.protocol :as proto]
            [ordered-collections.kernel.node :as node
             :refer [leaf leaf? -k -v -l -r]]
            [ordered-collections.kernel.root]
            [ordered-collections.kernel.tree :as tree]
            [ordered-collections.kernel.rope :as ropetree])
  (:import  [clojure.lang RT Murmur3 Util SeqIterator
                           IReduce IReduceInit
                           IEditableCollection ITransientCollection]
            [ordered_collections.kernel.root INodeCollection]
            [java.util ArrayList Arrays]
            [java.io ByteArrayOutputStream InputStream OutputStream]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const ^:private +flat-threshold+
  "Maximum byte length stored in flat (raw byte[]) representation.
  Above this the rope promotes to chunked tree form. Matches the
  StringRope threshold — small binary data stays on the fast path."
  1024)

(def ^:const ^:private +target-chunk-size+
  "ByteRope target chunk size in bytes. Bound into the kernel's
  `*target-chunk-size*` dynamic var via `with-tree`. Tuned via
  `lein bench-rope-tuning`: at 500K bytes, 1024 beats 256 on every
  operation — construction (~3x), nth (+50%), split (+47%),
  splice (+21%), concat (~2.4x) — because byte[] System.arraycopy
  throughput is so high that the win is almost entirely in reducing
  per-chunk tree overhead."
  1024)

(def ^:const ^:private +min-chunk-size+
  "ByteRope minimum internal chunk size (= target/2)."
  512)

(def ^:private byte-array-class (Class/forName "[B"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tree binding macro
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro ^:private with-tree
  "Bind the kernel's dynamic rope context for ByteRope operations:
  `tree/*t-join*` to the allocator, and the CSI target/min to the
  ByteRope-specific constants. Every tree-mutating operation must
  execute inside this binding."
  [alloc & body]
  `(binding [tree/*t-join*                ~alloc
             ropetree/*target-chunk-size* +target-chunk-size+
             ropetree/*min-chunk-size*    +min-chunk-size+]
     ~@body))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Flat-mode helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- byte-array?
  [x]
  (instance? byte-array-class x))

(defn- flat?
  "True when root is a raw byte[] (flat representation)."
  [root]
  (byte-array? root))

(defn- flat-size
  "Size of a flat or tree root. Handles nil, byte[], and tree nodes."
  ^long [root]
  (cond
    (nil? root)        0
    (byte-array? root) (alength ^bytes root)
    :else              (long (-v root))))

(defn- defensive-copy
  ^bytes [^bytes b]
  (Arrays/copyOf b (alength b)))

(defn- ensure-tree-root
  "Promote a flat byte[] root to a tree root. Returns tree nodes unchanged.
  Caller must bind tree/*t-join*."
  [root]
  (cond
    (nil? root)        nil
    (byte-array? root) (ropetree/bytes->root ^bytes root)
    :else              root))

(defn- flat-splice
  "Bulk-arraycopy splice on a flat byte[]. Returns a byte[]."
  ^bytes [^bytes s ^long start ^long end ^bytes rep]
  (let [si (int start)
        ei (int end)
        rl (int (if rep (alength rep) 0))
        sl (alength s)
        result (byte-array (+ (- sl (- ei si)) rl))]
    (System/arraycopy s 0 result 0 si)
    (when (pos? rl)
      (System/arraycopy rep 0 result si rl))
    (System/arraycopy s ei result (+ si rl) (- sl ei))
    result))

(defn- make-root
  "Create a ByteRope root from a byte[] result. Stays flat if ≤ threshold,
  otherwise promotes to tree. Caller must bind tree/*t-join* for promotion."
  [^bytes b]
  (cond
    (zero? (alength b))          nil
    (<= (alength b) +flat-threshold+) b
    :else                        (ropetree/bytes->root b)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Index / range checks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- valid-index?
  [^long n ^long k]
  (and (<= 0 k) (< k n)))

(defn- insert-index?
  [^long n ^long k]
  (and (<= 0 k) (<= k n)))

(defn- check-insert-index!
  [^long n ^long k]
  (when-not (insert-index? n k)
    (throw (IndexOutOfBoundsException.))))

(defn- check-range!
  [^long start ^long end ^long n]
  (when (or (neg? start) (neg? end) (> start end) (> end n))
    (throw (IndexOutOfBoundsException.))))


(declare ->byte-rope ->TransientByteRope ->ByteRope*
         coll->bytes coll->tree-root)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ByteRopeSeq — forward seq over byte[] chunks, yielding unsigned longs
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

(deftype ByteRopeSeq [enum ^bytes chunk ^long i cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (bit-and (long (aget chunk (unchecked-int i))) 0xFF))
  (next [_]
    (let [next-cnt (when cnt (unchecked-dec-int cnt))
          next-i   (unchecked-inc i)]
      (if (< next-i (alength chunk))
        (ByteRopeSeq. enum chunk next-i next-cnt nil)
        (when-let [e (tree/node-enum-rest enum)]
          (let [chunk' ^bytes (-k (tree/node-enum-first e))]
            (ByteRopeSeq. e chunk' 0 next-cnt nil))))))
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
               ^bytes chunk chunk
               i i
               n 0]
          (let [n (+ n (- (alength chunk) i))]
            (if-let [e' (tree/node-enum-rest e)]
              (let [chunk' ^bytes (-k (tree/node-enum-first e'))]
                (recur e' chunk' 0 n))
              n)))))

  IReduceInit
  (reduce [_ f init]
    (loop [e enum
           ^bytes chunk chunk
           i (int i)
           acc init]
      (let [acc (loop [idx i acc acc]
                  (if (< idx (alength chunk))
                    (let [ret (f acc (bit-and (long (aget chunk (unchecked-int idx))) 0xFF))]
                      (if (reduced? ret)
                        ret
                        (recur (unchecked-inc-int idx) ret)))
                    acc))]
        (if (reduced? acc)
          @acc
          (if-let [e' (tree/node-enum-rest e)]
            (let [chunk' ^bytes (-k (tree/node-enum-first e'))]
              (recur e' chunk' (int 0) acc))
            acc)))))

  IReduce
  (reduce [this f]
    (if enum
      (let [acc (bit-and (long (aget chunk (unchecked-int i))) 0xFF)
            next-i (unchecked-inc i)]
        (if (< next-i (alength chunk))
          (.reduce ^IReduceInit
            (ByteRopeSeq. enum chunk next-i nil nil) f acc)
          (if-let [e' (tree/node-enum-rest enum)]
            (let [chunk' ^bytes (-k (tree/node-enum-first e'))]
              (.reduce ^IReduceInit
                (ByteRopeSeq. e' chunk' 0 nil nil) f acc))
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
    (ByteRopeSeq. enum chunk i cnt m)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ByteRopeSeqReverse — reverse seq over byte[] chunks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype ByteRopeSeqReverse [enum ^bytes chunk ^long i cnt _meta]
  clojure.lang.ISeq
  (first [_]
    (bit-and (long (aget chunk (unchecked-int i))) 0xFF))
  (next [_]
    (let [next-cnt (when cnt (unchecked-dec-int cnt))]
      (if (pos? i)
        (ByteRopeSeqReverse. enum chunk (unchecked-dec i) next-cnt nil)
        (when-let [e (tree/node-enum-prior enum)]
          (let [chunk' ^bytes (-k (tree/node-enum-first e))]
            (ByteRopeSeqReverse. e chunk' (dec (alength chunk')) next-cnt nil))))))
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
               ^bytes chunk chunk
               i i
               n 0]
          (let [n (+ n (inc i))]
            (if-let [e' (tree/node-enum-prior e)]
              (let [chunk' ^bytes (-k (tree/node-enum-first e'))]
                (recur e' chunk' (dec (alength chunk')) n))
              n)))))

  IReduceInit
  (reduce [_ f init]
    (loop [e enum
           ^bytes chunk chunk
           i (int i)
           acc init]
      (let [acc (loop [idx i acc acc]
                  (if (neg? idx)
                    acc
                    (let [ret (f acc (bit-and (long (aget chunk (unchecked-int idx))) 0xFF))]
                      (if (reduced? ret)
                        ret
                        (recur (unchecked-dec-int idx) ret)))))]
        (if (reduced? acc)
          @acc
          (if-let [e' (tree/node-enum-prior e)]
            (let [chunk' ^bytes (-k (tree/node-enum-first e'))]
              (recur e' chunk' (int (dec (alength chunk'))) acc))
            acc)))))

  IReduce
  (reduce [this f]
    (if enum
      (let [acc (bit-and (long (aget chunk (unchecked-int i))) 0xFF)]
        (if (pos? i)
          (.reduce ^IReduceInit
            (ByteRopeSeqReverse. enum chunk (unchecked-dec i) nil nil) f acc)
          (if-let [e' (tree/node-enum-prior enum)]
            (let [chunk' ^bytes (-k (tree/node-enum-first e'))]
              (.reduce ^IReduceInit
                (ByteRopeSeqReverse. e' chunk' (dec (alength chunk')) nil nil) f acc))
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
    (ByteRopeSeqReverse. enum chunk i cnt m)))


(defn- byte-rope-seq
  [root]
  (cond
    (nil? root) nil
    (byte-array? root)
    (let [^bytes b root]
      (when (pos? (alength b))
        ;; Flat mode: single-chunk seq with nil enum (node-enum-rest tolerates nil).
        (ByteRopeSeq. nil b 0 (alength b) nil)))
    :else
    (when-let [enum (tree/node-enumerator root)]
      (let [chunk ^bytes (-k (tree/node-enum-first enum))]
        (ByteRopeSeq. enum chunk 0 (ropetree/rope-size root) nil)))))

(defn- byte-rope-rseq
  [root]
  (cond
    (nil? root) nil
    (byte-array? root)
    (let [^bytes b root]
      (when (pos? (alength b))
        (ByteRopeSeqReverse. nil b (dec (alength b)) (alength b) nil)))
    :else
    (when-let [enum (tree/node-enumerator-reverse root)]
      (let [chunk ^bytes (-k (tree/node-enum-first enum))]
        (ByteRopeSeqReverse. enum chunk (dec (alength chunk))
          (ropetree/rope-size root) nil)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Equality / Hashing / Compare
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- byte-rope-equiv
  "Content equality. A ByteRope equals:
  - another ByteRope with identical byte content
  - a byte[] with identical byte content
  Does not equal Clojure vectors, strings, or generic ropes — the signed
  vs unsigned domain mismatch is too easy to get wrong."
  [this o]
  (cond
    (identical? this o) true

    (instance? (class this) o)
    (let [n (long (count this))]
      (and (= n (long (count o)))
           (Arrays/equals ^bytes (proto/rope-str this)
                          ^bytes (proto/rope-str o))))

    (byte-array? o)
    (Arrays/equals ^bytes (proto/rope-str this) ^bytes o)

    :else false))

(defn- byte-rope-hasheq
  "Murmur3 hash over the sequence of unsigned byte values.
  Consistent with seq-based equality of byte ropes; not compatible with
  Clojure's default (identity) hash of a raw byte[]."
  [root]
  (if (nil? root)
    (Murmur3/hashOrdered ())
    (Murmur3/hashOrdered (byte-rope-seq root))))

(defn- byte-rope-compare
  "Unsigned lexicographic comparison. Consistent with Arrays.compareUnsigned
  on byte[] and with protobuf/Okio/Netty ordering conventions."
  ^long [this o]
  (let [^bytes a (proto/rope-str this)
        ^bytes b (cond
                   (byte-array? o) o
                   :else          (proto/rope-str o))]
    (Arrays/compareUnsigned a b)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ByteRope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype ByteRope [root alloc _meta
                   ^:volatile-mutable ^bytes _cc_chunk
                   ^:volatile-mutable ^int   _cc_start
                   ^:volatile-mutable ^int   _cc_end]
  ;; Cursor cache for O(1) amortized sequential nth on tree-mode ropes.
  ;; _cc_chunk: cached chunk (nil = no cache)
  ;; _cc_start/_cc_end: global index range [start, end) of cached chunk

  java.io.Serializable

  INodeCollection
  (getAllocator [_] alloc)

  clojure.lang.IMeta
  (meta [_] _meta)

  clojure.lang.IObj
  (withMeta [_ m] (->ByteRope* root alloc m))

  java.lang.Comparable
  (compareTo [this o]
    (if (identical? this o)
      0
      (byte-rope-compare this o)))

  clojure.lang.Counted
  (count [_]
    (flat-size root))

  clojure.lang.Indexed
  (nth [_ i]
    (let [ii (long i)
          n  (flat-size root)]
      (when-not (valid-index? n ii)
        (throw (IndexOutOfBoundsException.)))
      (if (byte-array? root)
        (bit-and (long (aget ^bytes root (unchecked-int ii))) 0xFF)
        (if (and _cc_chunk (>= ii _cc_start) (< ii _cc_end))
          ;; Cache hit
          (bit-and (long (aget _cc_chunk (unchecked-subtract-int (int ii) _cc_start))) 0xFF)
          ;; Cache miss — find chunk and cache it
          (let [[chunk offset] (ropetree/rope-chunk-at root ii)
                ^bytes ck chunk
                co (int offset)]
            (set! _cc_chunk ck)
            (set! _cc_start co)
            (set! _cc_end (unchecked-add-int co (alength ck)))
            (bit-and (long (aget ck (unchecked-subtract-int (int ii) co))) 0xFF))))))
  (nth [this i not-found]
    (let [ii (long i)
          n  (flat-size root)]
      (if (and (integer? i) (valid-index? n ii))
        (.nth this (int ii))
        not-found)))

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
    (let [b (unchecked-byte (long o))]
      (if (byte-array? root)
        (let [^bytes s root
              n (alength s)]
          (if (< n +flat-threshold+)
            (let [result (Arrays/copyOf s (unchecked-inc-int n))]
              (aset result n b)
              (->ByteRope* result alloc _meta))
            (with-tree alloc
              (->ByteRope* (ropetree/rope-conj-right (ropetree/bytes->root s) b)
                alloc _meta))))
        (if (nil? root)
          (let [a (byte-array 1)]
            (aset a 0 b)
            (->ByteRope* a alloc _meta))
          (with-tree alloc
            (->ByteRope* (ropetree/rope-conj-right root b) alloc _meta))))))
  (empty [_]
    (->ByteRope* nil alloc _meta))
  (equiv [this o]
    (byte-rope-equiv this o))

  clojure.lang.IPersistentStack
  (peek [_]
    (cond
      (nil? root) nil
      (byte-array? root)
      (let [^bytes s root
            n (alength s)]
        (when (pos? n)
          (bit-and (long (aget s (unchecked-dec-int n))) 0xFF)))
      :else
      (ropetree/rope-peek-right root)))
  (pop [_]
    (cond
      (nil? root)
      (throw (IllegalStateException. "Can't pop empty byte-rope"))

      (byte-array? root)
      (let [^bytes s root
            n (alength s)]
        (cond
          (<= n 1) (->ByteRope* nil alloc _meta)
          :else    (->ByteRope*
                     (Arrays/copyOf s (unchecked-dec-int n))
                     alloc _meta)))

      :else
      (with-tree alloc
        (->ByteRope* (ropetree/rope-pop-right root) alloc _meta))))

  clojure.lang.Seqable
  (seq [_]
    (byte-rope-seq root))

  clojure.lang.Reversible
  (rseq [_]
    (byte-rope-rseq root))

  clojure.lang.Sequential

  java.lang.Iterable
  (iterator [this]
    (SeqIterator. (seq this)))

  IReduceInit
  (reduce [_ f init]
    (cond
      (nil? root) init

      (byte-array? root)
      (let [^bytes s root
            len (alength s)]
        (loop [i (int 0), acc init]
          (if (< i len)
            (let [ret (f acc (bit-and (long (aget s i)) 0xFF))]
              (if (reduced? ret)
                @ret
                (recur (unchecked-inc-int i) ret)))
            acc)))

      :else
      (ropetree/rope-reduce f init root)))

  IReduce
  (reduce [this f]
    (cond
      (nil? root) (f)

      (byte-array? root)
      (let [^bytes s root
            len (alength s)]
        (if (zero? len)
          (f)
          (loop [i (int 1)
                 acc (Long/valueOf (bit-and (long (aget s 0)) 0xFF))]
            (if (< i len)
              (let [ret (f acc (bit-and (long (aget s (unchecked-int i))) 0xFF))]
                (if (reduced? ret)
                  @ret
                  (recur (unchecked-inc-int i) ret)))
              acc))))

      :else
      (ropetree/rope-reduce f root)))

  cp/CollReduce
  (coll-reduce [this f]
    (.reduce ^IReduce this f))
  (coll-reduce [this f init]
    (.reduce ^IReduceInit this f init))

  r/CollFold
  (coll-fold [this n combinef reducef]
    (if (byte-array? root)
      (.reduce ^IReduceInit this reducef (combinef))
      (ropetree/rope-fold root (long n) combinef reducef)))

  clojure.lang.IHashEq
  (hasheq [_]
    (byte-rope-hasheq root))

  clojure.lang.Associative
  (containsKey [_ k]
    (and (integer? k) (valid-index? (flat-size root) (long k))))
  (entryAt [this k]
    (when (.containsKey this k)
      (clojure.lang.MapEntry. k (.nth ^clojure.lang.Indexed this (int k)))))
  (assoc [this k v]
    (let [i (long k)
          n (flat-size root)
          b (unchecked-byte (long v))]
      (cond
        (not (insert-index? n i))
        (throw (IndexOutOfBoundsException.))

        (= i n)
        (.cons this v)

        :else
        (if (byte-array? root)
          (let [^bytes s root
                result (Arrays/copyOf s (alength s))]
            (aset result (int i) b)
            (->ByteRope* result alloc _meta))
          (with-tree alloc
            (->ByteRope* (ropetree/rope-assoc root i b) alloc _meta))))))

  java.util.Collection
  (toArray [_]
    (let [n (flat-size root)
          arr (object-array n)]
      (cond
        (zero? n) arr

        (byte-array? root)
        (let [^bytes s root]
          (dotimes [i n]
            (aset arr i (Long/valueOf (bit-and (long (aget s i)) 0xFF))))
          arr)

        :else
        (do
          (ropetree/rope-reduce
            (fn [^long i x]
              (aset arr i (Long/valueOf (long x)))
              (unchecked-inc i))
            (long 0)
            root)
          arr))))
  (isEmpty [_]
    (zero? (flat-size root)))
  (^boolean contains [_ x]
    (if (integer? x)
      (let [target (bit-and (long x) 0xFF)]
        (cond
          (nil? root) false

          (byte-array? root)
          (let [^bytes s root, n (alength s)]
            (loop [i 0]
              (cond
                (>= i n) false
                (= (bit-and (long (aget s i)) 0xFF) target) true
                :else (recur (unchecked-inc i)))))

          :else
          (true?
            (ropetree/rope-reduce
              (fn [_ v]
                (if (= (long v) target) (reduced true) false))
              false
              root))))
      false))
  (containsAll [this c]
    (every? #(.contains this %) c))
  (size [_]
    (int (flat-size root)))
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
    (when-not (or (byte-array? other) (instance? ByteRope other))
      (throw (IllegalArgumentException.
               "ByteRope rope-cat requires a ByteRope or byte[]")))
    (let [^bytes b1 (cond (nil? root)        (byte-array 0)
                          (byte-array? root) root
                          :else              nil)
          ^bytes b2 (if (byte-array? other)
                      other
                      (let [r (.-root ^ByteRope other)]
                        (cond (nil? r)        (byte-array 0)
                              (byte-array? r) r
                              :else           nil)))]
      ;; Fast path: both flat, combined fits threshold
      (if (and b1 b2 (<= (+ (alength b1) (alength b2)) +flat-threshold+))
        (let [n1 (alength b1)
              n2 (alength b2)
              result (byte-array (+ n1 n2))]
          (when (pos? n1) (System/arraycopy b1 0 result 0 n1))
          (when (pos? n2) (System/arraycopy b2 0 result n1 n2))
          (->ByteRope* (when (pos? (alength result)) result) alloc _meta))
        ;; Tree path
        (with-tree alloc
          (let [l (ensure-tree-root root)
                r (if (byte-array? other)
                    (ropetree/bytes->root ^bytes other)
                    (ensure-tree-root (.-root ^ByteRope other)))]
            (->ByteRope* (ropetree/rope-concat l r) alloc _meta))))))
  (rope-split [_ i]
    (let [n  (flat-size root)
          ii (long i)]
      (check-insert-index! n ii)
      (if (byte-array? root)
        (let [^bytes s root
              li (int ii)]
          [(->ByteRope* (when (pos? li) (Arrays/copyOfRange s 0 li)) alloc _meta)
           (->ByteRope* (when (< li (alength s))
                          (Arrays/copyOfRange s li (alength s)))
                        alloc _meta)])
        (with-tree alloc
          (let [[l r] (ropetree/ensure-split-parts
                        (ropetree/rope-split-at root ii))]
            [(->ByteRope* l alloc _meta) (->ByteRope* r alloc _meta)])))))
  (rope-sub [_ start end]
    (let [n (flat-size root)
          si (long start)
          ei (long end)]
      (check-range! si ei n)
      (if (byte-array? root)
        (let [^bytes s root]
          (->ByteRope*
            (when (> ei si) (Arrays/copyOfRange s (int si) (int ei)))
            alloc _meta))
        (with-tree alloc
          (->ByteRope* (ropetree/rope-subvec-root root si ei) alloc _meta)))))
  (rope-insert [this i coll]
    (let [n  (flat-size root)
          ii (long i)]
      (check-insert-index! n ii)
      (if (byte-array? root)
        (let [^bytes ins (coll->bytes coll)]
          (with-tree alloc
            (->ByteRope*
              (make-root (flat-splice ^bytes root ii ii ins))
              alloc _meta)))
        (let [^bytes ins (coll->bytes coll)
              ins-len (alength ins)]
          (or (when (<= ins-len +target-chunk-size+)
                (when-let [new-root (ropetree/rope-splice-inplace
                                      root ii ii
                                      (when (pos? ins-len) ins)
                                      alloc)]
                  (->ByteRope* new-root alloc _meta)))
              (with-tree alloc
                (->ByteRope* (ropetree/rope-insert-root root ii
                               (coll->tree-root coll))
                  alloc _meta)))))))
  (rope-remove [this start end]
    (let [n  (flat-size root)
          si (long start)
          ei (long end)]
      (check-range! si ei n)
      (if (byte-array? root)
        (let [^bytes s root]
          (->ByteRope*
            (let [^bytes result (flat-splice s si ei nil)]
              (when (pos? (alength result)) result))
            alloc _meta))
        (or (when-let [new-root (ropetree/rope-splice-inplace
                                  root si ei nil alloc)]
              (->ByteRope* new-root alloc _meta))
            (with-tree alloc
              (->ByteRope* (ropetree/rope-remove-root root si ei)
                alloc _meta))))))
  (rope-splice [this start end coll]
    (let [n  (flat-size root)
          si (long start)
          ei (long end)]
      (check-range! si ei n)
      (if (byte-array? root)
        (let [^bytes rep (coll->bytes coll)]
          (with-tree alloc
            (->ByteRope*
              (make-root (flat-splice ^bytes root si ei rep))
              alloc _meta)))
        (let [^bytes rep (coll->bytes coll)
              rep-len (alength rep)]
          (or (when (<= rep-len +target-chunk-size+)
                (when-let [new-root (ropetree/rope-splice-inplace
                                      root si ei
                                      (when (pos? rep-len) rep)
                                      alloc)]
                  (->ByteRope* new-root alloc _meta)))
              (with-tree alloc
                (let [mid-root (when (pos? rep-len)
                                 (coll->tree-root coll))]
                  (->ByteRope* (ropetree/rope-splice-root root si ei mid-root)
                    alloc _meta))))))))
  (rope-chunks [_]
    (cond
      (nil? root) nil
      (byte-array? root) (list root)
      :else (ropetree/rope-chunks-seq root)))
  (rope-str [_]
    ;; rope-str on a ByteRope returns a byte[], not a String.
    ;; The protocol name is a legacy of the StringRope-first design; for a
    ;; ByteRope, materialization produces bytes.
    (cond
      (nil? root)        (byte-array 0)
      (byte-array? root) (defensive-copy ^bytes root)
      :else              (ropetree/byte-rope->bytes root)))

  IEditableCollection
  (asTransient [_]
    (->TransientByteRope
      (ensure-tree-root root)
      alloc (ArrayList.) (ByteArrayOutputStream.) 0 true _meta))

  Object
  (hashCode [this]
    (Util/hash this))
  (equals [this o]
    (byte-rope-equiv this o))
  (toString [this]
    ;; Human-readable hex representation. For programmatic access to the
    ;; raw bytes, use rope-str (which returns byte[]).
    (let [^bytes b (proto/rope-str this)
          n (alength b)
          sb (StringBuilder. (* 2 n))]
      (dotimes [i n]
        (let [byte-val (bit-and (long (aget b i)) 0xFF)]
          (.append sb (Character/forDigit (int (bit-shift-right byte-val 4)) 16))
          (.append sb (Character/forDigit (int (bit-and byte-val 0xF)) 16))))
      (.toString sb))))


(defn- ->ByteRope*
  "Construct a ByteRope with fresh (empty) cursor cache."
  [root alloc meta]
  (ByteRope. root alloc meta nil 0 0))

(defn- coll->bytes
  "Coerce a splice/insert argument to a byte[]."
  ^bytes [coll]
  (cond
    (nil? coll)              (byte-array 0)
    (byte-array? coll)       coll
    (instance? ByteRope coll) (proto/rope-str ^ByteRope coll)
    (sequential? coll)
    (let [n (count coll)
          a (byte-array n)]
      (loop [s (seq coll), i 0]
        (if s
          (do (aset a i (unchecked-byte (long (first s))))
              (recur (next s) (unchecked-inc i)))
          a)))
    :else
    (throw (IllegalArgumentException.
             (str "ByteRope cannot coerce " (class coll) " to byte[]")))))

(defn- coll->tree-root
  "Coerce a splice/insert argument to a tree root for the multi-traversal
  fallback. Preserves existing ByteRope tree structure when possible."
  [coll]
  (cond
    (instance? ByteRope coll)
    (ensure-tree-root (.-root ^ByteRope coll))
    (byte-array? coll) (ropetree/bytes->root ^bytes coll)
    :else              (ropetree/bytes->root (coll->bytes coll))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TransientByteRope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- transient-byte-appended-root
  "Build a rope tree from flushed chunks + tail. Caller must bind *t-join*."
  [^ArrayList chunks ^ByteArrayOutputStream tail]
  (let [chunk-count (.size chunks)
        tail-empty? (zero? (.size tail))]
    (cond
      (and (zero? chunk-count) tail-empty?)
      nil

      (zero? chunk-count)
      (ropetree/chunks->root [(.toByteArray tail)])

      tail-empty?
      (ropetree/chunks->root (vec chunks))

      :else
      (ropetree/chunks->root-csi
        (conj (vec chunks) (.toByteArray tail))))))

(def ^:private ^:const +transient-rebuild-threshold+ 4)

(defn- transient-byte-final-root
  "Merge original root with appended chunks/tail. Caller must bind *t-join*."
  [root ^ArrayList chunks ^ByteArrayOutputStream tail]
  (cond
    (and (zero? (.size chunks)) (zero? (.size tail)))
    root

    ;; Fast path: small tail, no flushed chunks — conj into root directly
    (and (zero? (.size chunks)) (<= (.size tail) 32))
    (let [^bytes a (.toByteArray tail)
          n (alength a)]
      (if (nil? root)
        (tree/*t-join* a nil (leaf) (leaf))
        (loop [i 0, r root]
          (if (< i n)
            (recur (unchecked-inc i)
                   (ropetree/rope-conj-right r (aget a i)))
            r))))

    :else
    (let [appended-root (transient-byte-appended-root chunks tail)
          appended-chunks (+ (.size chunks) (if (zero? (.size tail)) 0 1))]
      (cond
        (nil? root)
        appended-root

        (<= appended-chunks +transient-rebuild-threshold+)
        (ropetree/rope-concat root appended-root)

        :else
        (ropetree/chunks->root-csi
          (cond-> (vec (ropetree/root->chunks root))
            (pos? (.size chunks)) (into (vec chunks))
            (pos? (.size tail))   (conj (.toByteArray tail))))))))

(deftype TransientByteRope [^:unsynchronized-mutable root
                             alloc
                             ^ArrayList chunks
                             ^ByteArrayOutputStream tail
                             ^:unsynchronized-mutable chunk-bytes
                             ^:unsynchronized-mutable edit
                             _meta]
  ITransientCollection
  (conj [this x]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (.write tail (unchecked-int (long x)))
    (when (>= (.size tail) +target-chunk-size+)
      (.add chunks (.toByteArray tail))
      (set! chunk-bytes (+ chunk-bytes (.size tail)))
      (.reset tail))
    this)

  (persistent [_]
    (when-not edit (throw (IllegalAccessError. "Transient used after persistent! call")))
    (set! edit false)
    (with-tree alloc
      (let [tree-root  (transient-byte-final-root root chunks tail)
            ;; Demote to flat if the result is small enough
            final-root (if (and tree-root
                                (<= (ropetree/rope-size tree-root) +flat-threshold+))
                         (ropetree/byte-rope->bytes tree-root)
                         tree-root)]
        (->ByteRope* final-root alloc _meta))))

  clojure.lang.Counted
  (count [_]
    (+ (ropetree/rope-size root) chunk-bytes (.size tail)))

  clojure.lang.Indexed
  (nth [this i]
    (let [rs (ropetree/rope-size root)
          j  (- (long i) rs)]
      (cond
        (and (>= i 0) (< i rs))
        (ropetree/rope-nth root (long i))

        (and (>= j 0) (< j chunk-bytes))
        (let [chunk-idx (quot j +target-chunk-size+)
              offset    (rem j +target-chunk-size+)
              ^bytes chunk (.get chunks (int chunk-idx))]
          (bit-and (long (aget chunk (unchecked-int offset))) 0xFF))

        (< (- j chunk-bytes) (.size tail))
        (let [^bytes tb (.toByteArray tail)]
          (bit-and (long (aget tb (unchecked-int (- j chunk-bytes)))) 0xFF))

        :else
        (throw (IndexOutOfBoundsException.)))))
  (nth [this i not-found]
    (let [rs (ropetree/rope-size root)
          n  (+ rs chunk-bytes (.size tail))]
      (if (and (>= (long i) 0) (< (long i) n))
        (.nth this (int i))
        not-found))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn byte-rope
  "Create a persistent byte rope for structural editing of binary data.
  Backed by a chunked weight-balanced tree with byte[] chunks:
  O(log n) concat, split, splice, insert, and remove.

  Bytes are exposed as unsigned longs in [0, 255]. Storage is signed Java
  bytes. Use the input as raw bytes:

    (byte-rope)                          ;=> empty
    (byte-rope (byte-array [1 2 3]))     ;=> from byte[] (defensively copied)
    (byte-rope [0 128 255])              ;=> from seq of unsigned longs
    (byte-rope \"hello\")                  ;=> UTF-8 encoding of the string

  Small byte sequences (≤ 1024 bytes) are stored as a raw byte[] internally
  for zero-overhead reads. Edits that grow past the threshold are
  transparently promoted to chunked tree form."
  ([]
   (->ByteRope* nil ropetree/byte-rope-node-create {}))
  ([x]
   (let [^bytes b (cond
                    (nil? x)           (byte-array 0)
                    (byte-array? x)    (defensive-copy x)
                    (instance? ByteRope x)
                    (proto/rope-str ^ByteRope x)
                    (string? x)        (.getBytes ^String x "UTF-8")
                    (instance? InputStream x)
                    (with-open [in ^InputStream x
                                out (ByteArrayOutputStream.)]
                      (let [buf (byte-array 4096)]
                        (loop []
                          (let [n (.read in buf)]
                            (when (pos? n)
                              (.write out buf 0 n)
                              (recur))))
                        (.toByteArray out)))
                    (sequential? x)
                    (let [n (count x)
                          a (byte-array n)]
                      (loop [s (seq x), i 0]
                        (if s
                          (do (aset a i (unchecked-byte (long (first s))))
                              (recur (next s) (unchecked-inc i)))
                          a)))
                    :else
                    (throw (IllegalArgumentException.
                             (str "byte-rope cannot coerce " (class x)))))]
     (cond
       (zero? (alength b))
       (->ByteRope* nil ropetree/byte-rope-node-create {})

       (<= (alength b) +flat-threshold+)
       (->ByteRope* b ropetree/byte-rope-node-create {})

       :else
       (with-tree ropetree/byte-rope-node-create
         (->ByteRope* (ropetree/bytes->root b)
           ropetree/byte-rope-node-create {}))))))

(defn byte-rope-concat
  "Concatenate byte ropes or byte arrays.
  One argument: returns it as a byte rope.
  Two arguments: O(log n) binary tree join.
  Three or more: O(total chunks) bulk construction."
  ([x]
   (->byte-rope x))
  ([left right]
   (proto/rope-cat (->byte-rope left) (->byte-rope right)))
  ([left right & more]
   (with-tree ropetree/byte-rope-node-create
     (let [alloc ropetree/byte-rope-node-create
           all   (list* left right more)
           chunks (into []
                    (mapcat (fn [x]
                              (let [br (->byte-rope x)
                                    rt (.-root ^ByteRope br)]
                                (cond
                                  (nil? rt)        []
                                  (byte-array? rt) [rt]
                                  :else            (ropetree/root->chunks rt)))))
                    all)]
       (->ByteRope* (ropetree/chunks->root-csi chunks)
         alloc (or (meta left) {}))))))

(defn- ->byte-rope
  "Coerce x to a ByteRope."
  [x]
  (cond
    (instance? ByteRope x) x
    :else                  (byte-rope x)))

(defn read-byte-rope
  "Reader function for #byte/rope tagged literals. Input is a hex string."
  [^String hex]
  (let [n (.length hex)
        _ (when (odd? n)
            (throw (IllegalArgumentException.
                     "byte/rope hex literal must have an even number of characters")))
        b (byte-array (quot n 2))]
    (dotimes [i (quot n 2)]
      (let [hi (Character/digit (.charAt hex (* 2 i)) 16)
            lo (Character/digit (.charAt hex (unchecked-inc-int (* 2 i))) 16)]
        (when (or (neg? hi) (neg? lo))
          (throw (IllegalArgumentException.
                   (str "byte/rope: invalid hex character at position " (* 2 i)))))
        (aset b i (unchecked-byte (bit-or (bit-shift-left hi 4) lo)))))
    (byte-rope b)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method ByteRope [^ByteRope r ^java.io.Writer w]
  (.write w "#byte/rope ")
  (print-method (.toString r) w))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Materialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn byte-rope-bytes
  "Materialize a byte rope to a byte[]. Defensive copy — the caller may
  mutate the returned array without affecting the rope."
  ^bytes [^ByteRope br]
  (proto/rope-str br))

(defn byte-rope-hex
  "Return the byte rope's contents as a lowercase hex string."
  ^String [^ByteRope br]
  (.toString br))

(defn byte-rope-write
  "Stream a byte rope's contents to an OutputStream, chunk by chunk.
  Writes each chunk via one OutputStream.write call so large ropes don't
  materialize the whole content as a single byte[]."
  [^ByteRope br ^OutputStream out]
  (doseq [chunk (proto/rope-chunks br)]
    (let [^bytes c chunk]
      (.write out c 0 (alength c))))
  nil)

(defn byte-rope-input-stream
  "Return a java.io.InputStream that reads over the byte rope's contents.
  Stateful — each call returns a fresh stream."
  ^InputStream [^ByteRope br]
  (let [^bytes data (proto/rope-str br)
        n (alength data)
        pos (int-array 1)]
    (proxy [InputStream] []
      (available []
        (unchecked-subtract-int n (aget pos 0)))
      (read
        ([]
         (let [p (aget pos 0)]
           (if (>= p n)
             -1
             (do (aset pos 0 (unchecked-inc-int p))
                 (bit-and (long (aget data p)) 0xFF)))))
        ([buf]
         (let [^bytes buf buf]
           (.read ^InputStream this buf 0 (alength buf))))
        ([buf off len]
         (let [^bytes buf buf
               p (aget pos 0)]
           (if (>= p n)
             -1
             (let [remaining (unchecked-subtract-int n p)
                   want      (min (int len) remaining)]
               (System/arraycopy data p buf (int off) want)
               (aset pos 0 (unchecked-add-int p want))
               want))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multi-Byte Reads
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- read-byte*
  ^long [^ByteRope br ^long offset]
  (long (.nth ^clojure.lang.Indexed br (int offset))))

(defn byte-rope-get-byte
  "Return the unsigned byte value (long in [0, 255]) at offset."
  ^long [^ByteRope br offset]
  (read-byte* br (long offset)))

(defn byte-rope-get-short
  "Return a big-endian unsigned 16-bit integer (long in [0, 65535]) at offset.
  Reads two bytes starting at offset."
  ^long [^ByteRope br offset]
  (let [off (long offset)
        hi  (read-byte* br off)
        lo  (read-byte* br (unchecked-inc off))]
    (bit-or (bit-shift-left hi 8) lo)))

(defn byte-rope-get-short-le
  "Return a little-endian unsigned 16-bit integer (long in [0, 65535]) at offset."
  ^long [^ByteRope br offset]
  (let [off (long offset)
        lo  (read-byte* br off)
        hi  (read-byte* br (unchecked-inc off))]
    (bit-or (bit-shift-left hi 8) lo)))

(defn byte-rope-get-int
  "Return a big-endian signed 32-bit integer (long with int sign extension)
  at offset. Reads four bytes."
  ^long [^ByteRope br offset]
  (let [off (long offset)
        b0  (read-byte* br off)
        b1  (read-byte* br (unchecked-add off 1))
        b2  (read-byte* br (unchecked-add off 2))
        b3  (read-byte* br (unchecked-add off 3))
        u32 (bit-or (bit-shift-left b0 24)
                    (bit-shift-left b1 16)
                    (bit-shift-left b2 8)
                    b3)]
    ;; Truncate to 32 bits then sign-extend to 64
    (long (unchecked-int u32))))

(defn byte-rope-get-int-le
  "Return a little-endian signed 32-bit integer (long with int sign
  extension) at offset."
  ^long [^ByteRope br offset]
  (let [off (long offset)
        b0  (read-byte* br off)
        b1  (read-byte* br (unchecked-add off 1))
        b2  (read-byte* br (unchecked-add off 2))
        b3  (read-byte* br (unchecked-add off 3))
        u32 (bit-or (bit-shift-left b3 24)
                    (bit-shift-left b2 16)
                    (bit-shift-left b1 8)
                    b0)]
    (long (unchecked-int u32))))

(defn byte-rope-get-long
  "Return a big-endian signed 64-bit integer at offset. Reads eight bytes."
  ^long [^ByteRope br offset]
  (let [off (long offset)
        b0  (read-byte* br off)
        b1  (read-byte* br (unchecked-add off 1))
        b2  (read-byte* br (unchecked-add off 2))
        b3  (read-byte* br (unchecked-add off 3))
        b4  (read-byte* br (unchecked-add off 4))
        b5  (read-byte* br (unchecked-add off 5))
        b6  (read-byte* br (unchecked-add off 6))
        b7  (read-byte* br (unchecked-add off 7))]
    (bit-or (bit-shift-left b0 56)
            (bit-shift-left b1 48)
            (bit-shift-left b2 40)
            (bit-shift-left b3 32)
            (bit-shift-left b4 24)
            (bit-shift-left b5 16)
            (bit-shift-left b6 8)
            b7)))

(defn byte-rope-get-long-le
  "Return a little-endian signed 64-bit integer at offset."
  ^long [^ByteRope br offset]
  (let [off (long offset)
        b0  (read-byte* br off)
        b1  (read-byte* br (unchecked-add off 1))
        b2  (read-byte* br (unchecked-add off 2))
        b3  (read-byte* br (unchecked-add off 3))
        b4  (read-byte* br (unchecked-add off 4))
        b5  (read-byte* br (unchecked-add off 5))
        b6  (read-byte* br (unchecked-add off 6))
        b7  (read-byte* br (unchecked-add off 7))]
    (bit-or (bit-shift-left b7 56)
            (bit-shift-left b6 48)
            (bit-shift-left b5 40)
            (bit-shift-left b4 32)
            (bit-shift-left b3 24)
            (bit-shift-left b2 16)
            (bit-shift-left b1 8)
            b0)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn byte-rope-index-of
  "Return the index of the first occurrence of the unsigned byte value
  (0–255) in the byte rope, or -1 if not found. Optional `from` position."
  (^long [^ByteRope br byte-val]
   (byte-rope-index-of br byte-val 0))
  (^long [^ByteRope br byte-val from]
   (let [target (bit-and (long byte-val) 0xFF)
         n      (long (count br))
         from   (long from)
         start  (max 0 from)]
     (if (>= start n)
       -1
       (let [result (ropetree/rope-reduce
                      (fn [^long i v]
                        (cond
                          (< i start) (unchecked-inc i)
                          (= (long v) target) (reduced i)
                          :else (unchecked-inc i)))
                      (long 0)
                      (let [root (.-root ^ByteRope br)]
                        (cond
                          (nil? root) nil
                          (byte-array? root) (ropetree/bytes->root ^bytes root)
                          :else root)))]
         (if (and (integer? result) (< (long result) n))
           (long result)
           -1))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Digest
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn byte-rope-digest
  "Compute a cryptographic digest of the byte rope's contents using the
  named algorithm (\"SHA-256\", \"SHA-1\", \"MD5\", etc.). Streams chunks
  through java.security.MessageDigest without materializing the whole
  rope. Returns a byte rope of the digest."
  [^ByteRope br ^String algorithm]
  (let [md (java.security.MessageDigest/getInstance algorithm)]
    (doseq [chunk (proto/rope-chunks br)]
      (let [^bytes c chunk]
        (.update md c 0 (alength c))))
    (byte-rope (.digest md))))
