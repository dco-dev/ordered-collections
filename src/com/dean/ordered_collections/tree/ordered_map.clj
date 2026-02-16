(ns com.dean.ordered-collections.tree.ordered-map
  (:require [clojure.core.reducers       :as r :refer [coll-fold]]
            [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.protocol :as proto]
            [com.dean.ordered-collections.tree.root]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang                RT Murmur3]
            [com.dean.ordered_collections.tree.protocol PNearest PRanked PSplittable]
            [com.dean.ordered_collections.tree.root     INodeCollection
                                         IBalancedCollection
                                         IOrderedCollection]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-ordered-map [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.IOrderedCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype OrderedMap [root cmp alloc stitch _meta]

  java.io.Serializable  ;; marker interface for serialization

  INodeCollection
  (getAllocator [_]
    alloc)
  (getRoot [_]
    root)

  IOrderedCollection
  (getCmp [_]
    cmp)
  (isCompatible [_ o]
    (and (instance? OrderedMap o)
         (= cmp (.getCmp ^IOrderedCollection o))
         (= stitch (.getStitch ^IBalancedCollection o))))
  (isSimilar [_ o]
    (map? o))

  IBalancedCollection
  (getStitch [_]
    stitch)

  clojure.lang.IMeta
  (meta [_]
    _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (OrderedMap. root cmp alloc stitch m))

  clojure.lang.Indexed
  (nth [_ i]
    ;; nth doesn't need comparator - only uses subtree sizes
    (node/-kv (tree/node-nth root i)))
  (nth [_ i not-found]
    (if (and (>= i 0) (< i (tree/node-size root)))
      (node/-kv (tree/node-nth root i))
      not-found))

  clojure.lang.MapEquivalence

  clojure.lang.Seqable
  (seq [_]
    (tree/entry-seq root (tree/node-size root)))

  clojure.lang.Reversible
  (rseq [_]
    (tree/entry-seq-reverse root (tree/node-size root)))

  clojure.lang.ILookup
  (valAt [this k not-found]
    ;; Fast paths for specialized comparators
    (cond
      (identical? cmp order/long-compare)   (tree/node-find-val-long root (long k) not-found)
      (identical? cmp order/string-compare) (tree/node-find-val-string root k not-found)
      :else (tree/node-find-val root k not-found cmp)))
  (valAt [this k]
    (.valAt this k nil))

  clojure.lang.IFn
  (invoke [this k not-found]
    (.valAt this k not-found))
  (invoke [this k]
    (.valAt this k))
  (applyTo [this args]
    (let [n (RT/boundedLength args 2)]
      (case n
        0 (throw (clojure.lang.ArityException. n (.. this (getClass) (getSimpleName))))
        1 (.invoke this (first args))
        2 (.invoke this (first args) (second args))
        3 (throw (clojure.lang.ArityException. n (.. this (getClass) (getSimpleName)))))))

  java.lang.Comparable
  (compareTo [this o]
    (with-ordered-map this
      (cond
        (identical? this o) 0
        (.isCompatible this o) (tree/node-map-compare root (.getRoot ^INodeCollection o))
        (.isSimilar    this o) (.compareTo ^Comparable (into (empty o) this) o)
        true (throw (ex-info "unsupported comparison: " {:this this :o o})))))

  clojure.lang.Counted
  (count [this]
    (tree/node-size root))

  clojure.lang.Associative
  (containsKey [this k]
    ;; Fast paths for specialized comparators
    (cond
      (identical? cmp order/long-compare)   (tree/node-contains-long? root (long k))
      (identical? cmp order/string-compare) (tree/node-contains-string? root k)
      :else (tree/node-contains? root k cmp)))
  (entryAt [this k]
    ;; Fast paths for specialized comparators
    (when-let [n (cond
                   (identical? cmp order/long-compare)   (tree/node-find-long root (long k))
                   (identical? cmp order/string-compare) (tree/node-find-string root k)
                   :else (tree/node-find root k cmp))]
      (node/-kv n)))
  (assoc [this k v]
    (OrderedMap. (tree/node-add root k v cmp tree/node-create-weight-balanced) cmp alloc stitch _meta))
  (empty [this]
    (OrderedMap. (node/leaf) cmp alloc stitch {}))

  java.util.Map
  (get [this k]
    (.valAt this k))
  (isEmpty [_]
    (node/leaf? root))
  (size [_]
    (tree/node-size root))
  (keySet [this]
    (with-ordered-map this
      (set (tree/node-vec root :accessor :k))))
  (put [_ _ _]
    (throw (UnsupportedOperationException.)))
  (putAll [_ _]
    (throw (UnsupportedOperationException.)))
  (clear [_]
    (throw (UnsupportedOperationException.)))
  (values [this]
    (with-ordered-map this
      (tree/node-vec root :accessor :v)))
  (entrySet [this]
    (with-ordered-map this
      (set (tree/node-vec root :accessor :kv))))
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))

  clojure.lang.IPersistentCollection
  (equiv [this o]
    (with-ordered-map this
      (cond
        (identical? this o) 0
        (.isCompatible this o) (and (= (.count this) (.count ^clojure.lang.Counted o))
                                    (zero? (tree/node-map-compare root (.getRoot ^INodeCollection o))))
        (map? o) (.equiv ^clojure.lang.IPersistentCollection (into (empty o) (tree/node-vec root :accessor :kv)) o)
        true     (throw (ex-info "unsupported comparison: " {:this this :o o})))))

  (cons [this o]
    (.assoc this (nth o 0) (nth o 1)))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (tree/node-reduce-entries f init root))

  clojure.lang.IReduce
  (reduce [this f]
    ;; No-init reduce: first entry becomes initial accumulator
    (if (node/leaf? root)
      (f)
      (let [least (tree/node-least root)
            first-entry (clojure.lang.MapEntry. (node/-k least) (node/-v least))
            seen-first (volatile! false)]
        (tree/node-reduce-entries
          (fn [acc entry]
            (if @seen-first
              (f acc entry)
              (do (vreset! seen-first true) entry)))
          first-entry
          root))))

  clojure.core.reducers.CollFold
  (coll-fold [this n combinef reducef]
    (with-ordered-map this
      (tree/node-parallel-fold-entries combinef reducef root)))

  clojure.lang.IPersistentMap
  (assocEx [this k v]
    (if-let [new-root (tree/node-add-if-absent root k v cmp tree/node-create-weight-balanced)]
      (OrderedMap. new-root cmp alloc stitch _meta)
      (throw (Exception. "Key or value already present"))))
  (without [this k]
    (OrderedMap. (tree/node-remove root k cmp tree/node-create-weight-balanced) cmp alloc stitch _meta))

  clojure.lang.Sorted
  (comparator [_]
    cmp)
  (entryKey [_ entry]
    (key entry))  ;; extract key from MapEntry
  (seq [_ ascending]
    (if ascending
      (tree/entry-seq root)
      (tree/entry-seq-reverse root)))
  (seqFrom [this k ascending]
    (with-ordered-map this
      (let [[lt present gt] (tree/node-split root k)]
        (if ascending
          ;; ascending: entries with keys >= k
          (if present
            (cons (clojure.lang.MapEntry. (first present) (second present))
                  (tree/entry-seq gt))
            (tree/entry-seq gt))
          ;; descending: entries with keys <= k
          (if present
            (cons (clojure.lang.MapEntry. (first present) (second present))
                  (tree/entry-seq-reverse lt))
            (tree/entry-seq-reverse lt))))))

  clojure.lang.IHashEq
  (hasheq [this]
    ;; Map hash is sum of (hasheq(key) XOR hasheq(val)) for all entries
    (tree/node-reduce
      (fn [^long acc n]
        (unchecked-add acc (bit-xor (clojure.lang.Util/hasheq (node/-k n))
                                    (clojure.lang.Util/hasheq (node/-v n)))))
      (long 0)
      root))

  PNearest
  (nearest [this test k]
    (with-ordered-map this
      (case test
        :< (when-let [n (tree/node-predecessor root k)]
             [(node/-k n) (node/-v n)])
        :<= (when-let [n (tree/node-find-nearest root k :<)]
              [(node/-k n) (node/-v n)])
        :> (when-let [n (tree/node-successor root k)]
             [(node/-k n) (node/-v n)])
        :>= (when-let [n (tree/node-find-nearest root k :>)]
              [(node/-k n) (node/-v n)])
        (throw (ex-info "nearest test must be :<, :<=, :>, or :>=" {:test test})))))

  PRanked
  (rank-of [_ k]
    (or (tree/node-rank root k cmp) -1))
  (slice [_ start end]
    (let [n (tree/node-size root)
          start (max 0 (long start))
          end (min n (long end))]
      (when (< start end)
        (binding [order/*compare* cmp]
          (map (fn [node] (clojure.lang.MapEntry. (node/-k node) (node/-v node)))
               (tree/node-subseq root start (dec end)))))))
  (median [_]
    (let [n (tree/node-size root)]
      (when (pos? n)
        (binding [order/*compare* cmp]
          (let [node (tree/node-nth root (quot (dec n) 2))]
            (clojure.lang.MapEntry. (node/-k node) (node/-v node)))))))
  (percentile [_ pct]
    (let [n (tree/node-size root)]
      (when (pos? n)
        (let [idx (min (dec n) (long (* (/ (double pct) 100.0) n)))]
          (binding [order/*compare* cmp]
            (let [node (tree/node-nth root idx)]
              (clojure.lang.MapEntry. (node/-k node) (node/-v node))))))))

  PSplittable
  (split-key [this k]
    (with-ordered-map this
      (let [[l present r] (tree/node-split root k)
            entry (when present [(first present) (second present)])]
        [(OrderedMap. l cmp alloc stitch {})
         entry
         (OrderedMap. r cmp alloc stitch {})])))
  (split-at [this i]
    (with-ordered-map this
      (let [n (tree/node-size root)]
        (cond
          (<= i 0) [(.empty this) this]
          (>= i n) [this (.empty this)]
          :else
          (let [left-root  (tree/node-split-lesser root (node/-k (tree/node-nth root i)))
                right-root (tree/node-split-nth root i)]
            [(OrderedMap. left-root cmp alloc stitch {})
             (OrderedMap. right-root cmp alloc stitch {})])))))
  (subrange [this test k]
    (with-ordered-map this
      (let [result-root (case test
                          (:< :<=) (tree/node-split-lesser root k)
                          (:> :>=) (tree/node-split-greater root k)
                          (throw (ex-info "subrange test must be :<, :<=, :>, or :>=" {:test test})))
            ;; For <= and >=, include the key itself if present
            result-root (case test
                          (:<= :>=) (if-let [n (tree/node-find root k)]
                                      (tree/node-add result-root (node/-k n) (node/-v n))
                                      result-root)
                          result-root)]
        (OrderedMap. result-root cmp alloc stitch {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method OrderedMap [m w]
  ((get (methods print-method) clojure.lang.IPersistentMap) m w))
