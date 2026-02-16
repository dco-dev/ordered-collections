(ns com.dean.ordered-collections.tree.ordered-set
  (:require [clojure.core.reducers       :as r :refer [coll-fold]]
            [clojure.set]
            [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.tree.protocol :as proto]
            [com.dean.ordered-collections.tree.root]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang                RT Murmur3]
            [com.dean.ordered_collections.tree.protocol PExtensibleSet PNearest PRanked PSplittable]
            [com.dean.ordered_collections.tree.root     INodeCollection
                                         IBalancedCollection
                                         IOrderedCollection]))


;; - IMapIterable:  https://github.com/clojure/clojure/blob/master/src/jvm/clojure/lang/PersistentHashMap.java
;; - Collection Check: https://github.com/ztellman/collection-check/blob/master/src/collection_check/core.cljc

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-ordered-set [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.IOrderedCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype OrderedSet [root cmp alloc stitch _meta]

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
    (and (instance? OrderedSet o) (= cmp (.getCmp ^OrderedSet o)) (= stitch (.getStitch ^OrderedSet o))))
  (isSimilar [_ o]
    (set? o))

  IBalancedCollection
  (getStitch [_]
    stitch)

  PExtensibleSet
  (intersection [this that]
    (with-ordered-set this
      (cond
        (identical? this that)    this
        (.isCompatible this that)
        (let [that-root (.getRoot ^OrderedSet that)
              use-parallel? (>= (+ (tree/node-size root) (tree/node-size that-root))
                                tree/+parallel-threshold+)]
          (new OrderedSet
               (if use-parallel?
                 (tree/node-set-intersection-parallel root that-root)
                 (tree/node-set-intersection root that-root))
               cmp alloc stitch {}))
        (.isSimilar this that)    (clojure.set/intersection (into #{} this) that)
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (union [this that]
    (with-ordered-set this
      (cond
        (identical? this that)    this
        (.isCompatible this that)
        (let [that-root (.getRoot ^OrderedSet that)
              use-parallel? (>= (+ (tree/node-size root) (tree/node-size that-root))
                                tree/+parallel-threshold+)]
          (new OrderedSet
               (if use-parallel?
                 (tree/node-set-union-parallel root that-root)
                 (tree/node-set-union root that-root))
               cmp alloc stitch {}))
        (.isSimilar this that)    (clojure.set/union (into #{} this) that)
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (difference [this that]
    (with-ordered-set this
      (cond
        (identical? this that)    (.empty this)
        (.isCompatible this that)
        (let [that-root (.getRoot ^OrderedSet that)
              use-parallel? (>= (+ (tree/node-size root) (tree/node-size that-root))
                                tree/+parallel-threshold+)]
          (new OrderedSet
               (if use-parallel?
                 (tree/node-set-difference-parallel root that-root)
                 (tree/node-set-difference root that-root))
               cmp alloc stitch {}))
        (.isSimilar this that)    (clojure.set/difference (into #{} this) that)
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (subset [this that]
    (with-ordered-set this
      (cond
        (identical? this that)    true
        (.isCompatible this that) (tree/node-subset? (.getRoot ^OrderedSet that) root) ;; Grr. reverse args of tree/subset
        (.isSimilar this that)    (clojure.set/subset? (into #{} this) that)
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))
  (superset [this that]
    (with-ordered-set this
      (cond
        (identical? this that)    true
        (.isCompatible this that) (tree/node-subset? root (.getRoot ^OrderedSet that)) ;; Grr. reverse args of tree/subset
        (.isSimilar this that)    (clojure.set/subset? that (into #{} this))
        true (throw (ex-info "unsupported set operands: " {:this this :that that})))))

  clojure.lang.IMeta
  (meta [_]
    _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (new OrderedSet root cmp alloc stitch m))

  clojure.lang.Indexed
  (nth [_ i]
    ;; nth doesn't need comparator - only uses subtree sizes
    (node/-k (tree/node-nth root i)))
  (nth [_ i not-found]
    (if (and (>= i 0) (< i (tree/node-size root)))
      (node/-k (tree/node-nth root i))
      not-found))

  clojure.lang.Seqable
  (seq [_]
    (tree/key-seq root (tree/node-size root)))

  clojure.lang.Reversible
  (rseq [_]
    (tree/key-seq-reverse root (tree/node-size root)))

  clojure.lang.ILookup
  (valAt [this k not-found]
    ;; Fast paths for specialized comparators
    (if (cond
          (identical? cmp order/long-compare)   (tree/node-contains-long? root (long k))
          (identical? cmp order/string-compare) (tree/node-contains-string? root k)
          :else (tree/node-contains? root k cmp))
      k not-found))
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
    (with-ordered-set this
      (cond
        (identical? this o)   0
        (.isCompatible this o) (tree/node-set-compare root (.getRoot ^OrderedSet o))
        (.isSimilar    this o) (.compareTo ^Comparable (into (empty o) this) o)
        true (throw (ex-info "unsupported comparison: " {:this this :o o})))))

  java.util.Collection
  (toArray [this]
    (with-ordered-set this
      (object-array (tree/node-vec root :accessor :k)))) ; better constructor not a priority
  (isEmpty [_]
    (node/leaf? root))
  (add [_ _]
    (throw (UnsupportedOperationException.)))
  (addAll [_ _]
    (throw (UnsupportedOperationException.)))
  (removeAll [_ _]
    (throw (UnsupportedOperationException.)))
  (retainAll [_ _]
    (throw (UnsupportedOperationException.)))

  java.util.List
  (indexOf [_ x]
    (tree/node-rank root x cmp))
  (lastIndexOf [this x]
    (.indexOf this x))

  java.util.Set
  (size [_]
    (tree/node-size root))
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))
  (containsAll [this s]
    (with-ordered-set this
      (cond
        (identical? this s)    true
        (.isCompatible this s) (tree/node-subset? root (.getRoot ^OrderedSet s))
        (coll? s)              (every? #(.contains this %) s)
        true     (throw (ex-info "unsupported comparison: " {:this this :s s})))))

  java.util.SortedSet
  (comparator [_]
    cmp)
  (first [this]
    (with-ordered-set this
      (first (tree/node-least-kv root))))
  (last [this]
    (with-ordered-set this
      (first (tree/node-greatest-kv root))))
  (headSet [this x]
    ;; elements < x (exclusive)
    (with-ordered-set this
      (new OrderedSet (tree/node-split-lesser root x) cmp alloc stitch {})))
  (tailSet [this x]
    ;; elements >= x (inclusive)
    (with-ordered-set this
      (let [[_ present gt] (tree/node-split root x)]
        (if present
          ;; x exists: add it to the greater-than tree
          (new OrderedSet (tree/node-add gt (first present) (first present)) cmp alloc stitch {})
          ;; x doesn't exist: just return greater-than tree
          (new OrderedSet gt cmp alloc stitch {})))))
  (subSet [this from to]
    ;; elements >= from and < to
    (with-ordered-set this
      (let [[_ from-present from-gt] (tree/node-split root from)
            ;; Start with elements > from
            from-tree (if from-present
                        (tree/node-add from-gt (first from-present) (first from-present))
                        from-gt)
            ;; Intersect with elements < to
            to-tree (tree/node-split-lesser root to)
            result  (tree/node-set-intersection from-tree to-tree)]
        (new OrderedSet result cmp alloc stitch {}))))

  java.util.NavigableSet
  (ceiling [this x]
    (with-ordered-set this
      (let [[_ x' r] (tree/node-split root x)]
        (if (some? x')
          (first x')
          (when-not (node/leaf? r)
            (first (tree/node-least-kv r)))))))
  (floor [this x]
    (with-ordered-set this
      (let [[l x' _] (tree/node-split root x)]
        (if (some? x')
          (first x')
          (when-not (node/leaf? l)
            (first (tree/node-greatest-kv l)))))))

  clojure.lang.Sorted
  ;; comparator method is inherited from java.util.SortedSet above
  (entryKey [_ entry]
    entry)  ;; for sets, the entry IS the key
  (seq [_ ascending]
    (if ascending
      (tree/key-seq root)
      (tree/key-seq-reverse root)))
  (seqFrom [this k ascending]
    (with-ordered-set this
      (let [[lt present gt] (tree/node-split root k)]
        (if ascending
          ;; ascending: elements >= k (present + gt)
          (if present
            (cons (first present) (tree/key-seq gt))
            (tree/key-seq gt))
          ;; descending: elements <= k (present + lt in reverse)
          (if present
            (cons (first present) (tree/key-seq-reverse lt))
            (tree/key-seq-reverse lt))))))

  clojure.lang.IPersistentSet
  (equiv [this o]
    (with-ordered-set this
      (cond
        (identical? this o) true
        (not= (tree/node-size root) (.count ^clojure.lang.Counted o)) false
        (.isCompatible this o) (zero? (tree/node-set-compare root (.getRoot ^OrderedSet o)))
        (.isSimilar    this o) (.equiv ^clojure.lang.IPersistentSet (into (empty o) this) o)
        true     (throw (ex-info "unsupported comparison: " {:this this :o o})))))
  (count [_]
    (tree/node-size root))
  (empty [_]
    (new OrderedSet (node/leaf) cmp alloc stitch {}))
  (contains [this k]
    ;; Fast paths for specialized comparators
    (cond
      (identical? cmp order/long-compare)   (tree/node-contains-long? root (long k))
      (identical? cmp order/string-compare) (tree/node-contains-string? root k)
      :else (tree/node-contains? root k cmp)))
  (disjoin [this k]
    (new OrderedSet (tree/node-remove root k cmp tree/node-create-weight-balanced) cmp alloc stitch _meta))
  (cons [this k]
    (new OrderedSet (tree/node-add root k k cmp tree/node-create-weight-balanced) cmp alloc stitch _meta))

  clojure.lang.IHashEq
  (hasheq [this]
    ;; Set hash is sum of hasheq of all elements (order-independent)
    (tree/node-reduce
      (fn [^long acc n]
        (unchecked-add acc (Murmur3/hashInt (clojure.lang.Util/hasheq (node/-k n)))))
      (long 0)
      root))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (tree/node-reduce-keys f init root))

  clojure.lang.IReduce
  (reduce [this f]
    ;; No-init reduce: first element becomes initial accumulator
    (if (node/leaf? root)
      (f)
      (let [first-key (node/-k (tree/node-least root))
            seen-first (volatile! false)]
        (tree/node-reduce-keys
          (fn [acc k]
            (if @seen-first
              (f acc k)
              (do (vreset! seen-first true) k)))
          first-key
          root))))

  clojure.core.reducers.CollFold
  (coll-fold [this n combinef reducef]
    (with-ordered-set this
      (tree/node-parallel-fold-keys combinef reducef root)))

  PNearest
  (nearest [this test k]
    (with-ordered-set this
      (case test
        :< (when-let [n (tree/node-predecessor root k)]
             (node/-k n))
        :<= (when-let [n (tree/node-find-nearest root k :<)]
              (node/-k n))
        :> (when-let [n (tree/node-successor root k)]
             (node/-k n))
        :>= (when-let [n (tree/node-find-nearest root k :>)]
              (node/-k n))
        (throw (ex-info "nearest test must be :<, :<=, :>, or :>=" {:test test})))))

  PRanked
  (rank-of [_ x]
    (or (tree/node-rank root x cmp) -1))
  (slice [_ start end]
    (let [n (tree/node-size root)
          start (max 0 (long start))
          end (min n (long end))]
      (when (< start end)
        (binding [order/*compare* cmp]
          (map node/-k (tree/node-subseq root start (dec end)))))))
  (median [_]
    (let [n (tree/node-size root)]
      (when (pos? n)
        (binding [order/*compare* cmp]
          (node/-k (tree/node-nth root (quot (dec n) 2)))))))
  (percentile [_ pct]
    (let [n (tree/node-size root)]
      (when (pos? n)
        (let [idx (min (dec n) (long (* (/ (double pct) 100.0) n)))]
          (binding [order/*compare* cmp]
            (node/-k (tree/node-nth root idx)))))))

  PSplittable
  (split-key [this k]
    (with-ordered-set this
      (let [[l present r] (tree/node-split root k)
            entry (when present (first present))]
        [(new OrderedSet l cmp alloc stitch {})
         entry
         (new OrderedSet r cmp alloc stitch {})])))
  (split-at [this i]
    (with-ordered-set this
      (let [n (tree/node-size root)]
        (cond
          (<= i 0) [(.empty this) this]
          (>= i n) [this (.empty this)]
          :else
          (let [left-root  (tree/node-split-lesser root (node/-k (tree/node-nth root i)))
                right-root (tree/node-split-nth root i)]
            [(new OrderedSet left-root cmp alloc stitch {})
             (new OrderedSet right-root cmp alloc stitch {})])))))
  (subrange [this test k]
    (with-ordered-set this
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
        (new OrderedSet result-root cmp alloc stitch {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method OrderedSet [s w]
  ((get (methods print-method) clojure.lang.IPersistentSet) s w))



(comment

  ;; W00T!

  (def foo (shuffle (range 500000)))
  (def bar (shuffle (range 1000000)))

  (def s0 (shuffle (range 0 1000000 2)))
  (def s1 (shuffle (range 0 1000000 3)))

  ;; Home Team:

  (time (def x (ordered-set foo)))         ;; 500K: "Elapsed time: 564.248517 msecs"
  (time (def y (ordered-set bar)))         ;;   1M: "Elapsed time: 1187.734211 msecs"
  (time (def s (proto/intersection
                 (ordered-set s0)
                 (ordered-set s1))))       ;; 833K: "Elapsed time: 1242.961445 msecs"
  (time (r/fold + + y))                    ;;   1M: "Elapsed time: 54.363545 msecs"

  ;; Visitors:

  (time (def v (into (sorted-set) foo)))   ;; 500K: "Elapsed time: 839.188189 msecs"
  (time (def w (into (sorted-set) bar)))   ;;   1M: "Elapsed time: 1974.798286 msecs"
  (time (def s (clojure.set/intersection
                 (into (sorted-set) s0)
                 (into (sorted-set) s1)))) ;; 833K: "Elapsed time: 1589.786106 msecs"
  (time (r/fold + + w))                    ;;   1M: "Elapsed time: 167.916539 msecs"


  (require '[criterium.core])

  (criterium.core/bench (def x (ordered-set foo)))

;;   Evaluation count : 120 in 60 samples of 2 calls.
;;              Execution time mean : 612.435645 ms
;;     Execution time std-deviation : 60.421726 ms
;;    Execution time lower quantile : 565.022632 ms ( 2.5%)
;;    Execution time upper quantile : 771.090227 ms (97.5%)
;;                    Overhead used : 1.708588 ns
;;
;; Found 11 outliers in 60 samples (18.3333 %)
;; 	low-severe	 1 (1.6667 %)
;; 	low-mild	 10 (16.6667 %)
;;  Variance from outliers : 68.6890 % Variance is severely inflated by outliers

  (criterium.core/bench (def v (into (sorted-set) foo)))

;;   Evaluation count : 120 in 60 samples of 2 calls.
;;              Execution time mean : 819.376840 ms
;;     Execution time std-deviation : 29.835432 ms
;;    Execution time lower quantile : 789.678093 ms ( 2.5%)
;;    Execution time upper quantile : 907.561055 ms (97.5%)
;;                    Overhead used : 1.708588 ns
;;
;; Found 5 outliers in 60 samples (8.3333 %)
;; 	low-severe	 3 (5.0000 %)
;; 	low-mild	 2 (3.3333 %)
;;  Variance from outliers : 22.2640 % Variance is moderately inflated by outliers

;;;
;; clojure.data.avl

  (require '[clojure.data.avl :as avl])

  (time (def z (into (avl/sorted-set) foo))) ;; 500K: "Elapsed time: 586.862601 msecs"
  (time (def z (into (avl/sorted-set) bar))) ;; 1M:   "Elapsed time: 1399.241718 msecs"

  (criterium.core/bench (def z (into (avl/sorted-set) foo)))

;; Evaluation count : 120 in 60 samples of 2 calls.
;;              Execution time mean : 606.249611 ms
;;     Execution time std-deviation : 16.864172 ms
;;    Execution time lower quantile : 560.393078 ms ( 2.5%)
;;    Execution time upper quantile : 631.176588 ms (97.5%)
;;                    Overhead used : 1.710404 ns
;;
;; Found 4 outliers in 60 samples (6.6667 %)
;; 	low-severe	 3 (5.0000 %)
;; 	low-mild	 1 (1.6667 %)
;;  Variance from outliers : 14.2428 % Variance is moderately inflated by outliers

  (time (r/fold + + z))

  )
