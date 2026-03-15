(ns com.dean.ordered-collections.types.fuzzy-set
  "A set that returns the closest element to a query.

   When looking up a value, returns the element in the set that is closest
   to the query. For numeric keys, distance is |query - element|.

   Tie-breaking: When two elements are equidistant, use :< to prefer the
   smaller element, or :> to prefer the larger element."
  (:require [clojure.core.reducers       :as r :refer [coll-fold]]
            [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.protocol      :as proto :refer [PRanked]]
            [com.dean.ordered-collections.tree.root]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang                RT Murmur3]
            [com.dean.ordered_collections.protocol PFuzzy]
            [com.dean.ordered_collections.tree.root     INodeCollection
                                         IBalancedCollection
                                         IOrderedCollection]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Distance Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn numeric-distance
  "Default distance function for numeric types."
  ^double [^Number a ^Number b]
  (Math/abs (- (.doubleValue a) (.doubleValue b))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Nearest Lookup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find-nearest
  "Find the nearest element to query in the tree.

   Parameters:
   - root: the tree root
   - query: the value to find nearest to
   - cmp: comparator for ordering
   - distance-fn: (fn [a b] -> number) returns distance between elements
   - tiebreak: :< (prefer smaller) or :> (prefer larger) when equidistant

   Returns the nearest element, or nil if tree is empty."
  [root query ^java.util.Comparator cmp distance-fn tiebreak]
  (if (node/leaf? root)
    nil
    (binding [order/*compare* cmp]
      (let [;; Split tree at query point
            [lt present gt] (tree/node-split root query)
            ;; Get floor (greatest element <= query)
            floor-node (if present
                         query
                         (when-not (node/leaf? lt)
                           (node/-k (tree/node-greatest lt))))
            ;; Get ceiling (least element >= query)
            ceiling-node (if present
                           query
                           (when-not (node/leaf? gt)
                             (node/-k (tree/node-least gt))))]
        (cond
          ;; Query exists exactly
          present query

          ;; Only floor exists
          (and floor-node (nil? ceiling-node))
          floor-node

          ;; Only ceiling exists
          (and ceiling-node (nil? floor-node))
          ceiling-node

          ;; Both exist - compare distances
          (and floor-node ceiling-node)
          (let [floor-dist (distance-fn query floor-node)
                ceiling-dist (distance-fn query ceiling-node)]
            (cond
              (< floor-dist ceiling-dist) floor-node
              (> floor-dist ceiling-dist) ceiling-node
              ;; Equal distance - use tiebreaker
              (= tiebreak :<) floor-node
              :else ceiling-node))

          ;; Empty tree
          :else nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-fuzzy-set [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.IOrderedCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FuzzySet Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype FuzzySet [root cmp distance-fn tiebreak _meta]

  java.io.Serializable

  INodeCollection
  (getAllocator [_]
    tree/node-create-weight-balanced)
  (getRoot [_]
    root)

  IOrderedCollection
  (getCmp [_]
    cmp)
  (isCompatible [_ o]
    (and (instance? FuzzySet o)
         (= cmp (.getCmp ^FuzzySet o))
         (= distance-fn (.-distance-fn ^FuzzySet o))
         (= tiebreak (.-tiebreak ^FuzzySet o))))
  (isSimilar [_ o]
    (set? o))

  IBalancedCollection
  (getStitch [_]
    tree/node-stitch-weight-balanced)

  clojure.lang.IMeta
  (meta [_]
    _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (new FuzzySet root cmp distance-fn tiebreak m))

  clojure.lang.Indexed
  (nth [_ i]
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
  ;; Fuzzy lookup - returns the nearest element
  (valAt [this query not-found]
    (if (node/leaf? root)
      not-found
      (if-let [nearest (find-nearest root query cmp distance-fn tiebreak)]
        nearest
        not-found)))
  (valAt [this query]
    (.valAt this query nil))

  clojure.lang.IFn
  (invoke [this query not-found]
    (.valAt this query not-found))
  (invoke [this query]
    (.valAt this query))
  (applyTo [this args]
    (let [n (RT/boundedLength args 2)]
      (case n
        0 (throw (clojure.lang.ArityException. n (.. this (getClass) (getSimpleName))))
        1 (.invoke this (first args))
        2 (.invoke this (first args) (second args))
        3 (throw (clojure.lang.ArityException. n (.. this (getClass) (getSimpleName)))))))

  java.util.Collection
  (toArray [this]
    (with-fuzzy-set this
      (object-array (tree/node-vec root :accessor :k))))
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
    (or (tree/node-rank root x cmp) -1))
  (lastIndexOf [this x]
    (.indexOf this x))

  java.util.Set
  (size [_]
    (tree/node-size root))
  (iterator [this]
    (clojure.lang.SeqIterator. (seq this)))
  (containsAll [this s]
    (with-fuzzy-set this
      (every? #(.contains this %) s)))

  java.util.SortedSet
  (comparator [_]
    cmp)
  (first [this]
    (with-fuzzy-set this
      (first (tree/node-least-kv root))))
  (last [this]
    (with-fuzzy-set this
      (first (tree/node-greatest-kv root))))
  (headSet [this x]
    (with-fuzzy-set this
      (new FuzzySet (tree/node-split-lesser root x) cmp distance-fn tiebreak {})))
  (tailSet [this x]
    (with-fuzzy-set this
      (let [[_ present gt] (tree/node-split root x)]
        (if present
          (new FuzzySet (tree/node-add gt (first present) (first present)) cmp distance-fn tiebreak {})
          (new FuzzySet gt cmp distance-fn tiebreak {})))))
  (subSet [this from to]
    (with-fuzzy-set this
      (let [[_ from-present from-gt] (tree/node-split root from)
            from-tree (if from-present
                        (tree/node-add from-gt (first from-present) (first from-present))
                        from-gt)
            to-tree (tree/node-split-lesser root to)
            result  (tree/node-set-intersection from-tree to-tree)]
        (new FuzzySet result cmp distance-fn tiebreak {}))))

  clojure.lang.Sorted
  (entryKey [_ entry]
    entry)
  (seq [_ ascending]
    (if ascending
      (tree/key-seq root)
      (tree/key-seq-reverse root)))
  (seqFrom [this k ascending]
    (with-fuzzy-set this
      (let [[lt present gt] (tree/node-split root k)]
        (if ascending
          (if present
            (cons (first present) (tree/key-seq gt))
            (tree/key-seq gt))
          (if present
            (cons (first present) (tree/key-seq-reverse lt))
            (tree/key-seq-reverse lt))))))

  clojure.lang.IPersistentSet
  (equiv [this o]
    (with-fuzzy-set this
      (cond
        (identical? this o) true
        (not (instance? clojure.lang.Counted o)) false
        (not= (tree/node-size root) (.count ^clojure.lang.Counted o)) false
        (.isCompatible this o) (zero? (tree/node-set-compare root (.getRoot ^FuzzySet o)))
        (.isSimilar this o) (.equiv ^clojure.lang.IPersistentSet (into (empty o) this) o)
        :else false)))
  (count [_]
    (tree/node-size root))
  (empty [_]
    (new FuzzySet (node/leaf) cmp distance-fn tiebreak {}))
  (contains [this k]
    (tree/node-contains? root k cmp))
  (disjoin [this k]
    (new FuzzySet (tree/node-remove root k cmp tree/node-create-weight-balanced) cmp distance-fn tiebreak _meta))
  (cons [this k]
    (new FuzzySet (tree/node-add root k k cmp tree/node-create-weight-balanced) cmp distance-fn tiebreak _meta))

  Object
  (toString [this]
    (pr-str this))

  clojure.lang.IHashEq
  (hasheq [this]
    ;; Must match APersistentSet: sum of hasheq(element), then mixCollHash
    (Murmur3/mixCollHash
      (unchecked-int
        (tree/node-reduce
          (fn [^long acc n]
            (unchecked-add acc (long (clojure.lang.Util/hasheq (node/-k n)))))
          (long 0)
          root))
      (tree/node-size root)))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (tree/node-reduce-keys f init root))

  clojure.lang.IReduce
  (reduce [this f]
    (tree/node-reduce-keys f root))

  clojure.core.reducers.CollFold
  (coll-fold [this n combinef reducef]
    (with-fuzzy-set this
      (tree/node-chunked-fold n root combinef
        (fn [acc node] (reducef acc (node/-k node))))))

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
        (node/-k (tree/node-nth root (quot (dec n) 2))))))
  (percentile [_ pct]
    (let [n (tree/node-size root)]
      (when (pos? n)
        (let [idx (min (dec n) (long (* (/ (double pct) 100.0) n)))]
          (node/-k (tree/node-nth root idx))))))

  PFuzzy
  (nearest-with-distance [this query]
    (when-not (node/leaf? root)
      (let [nearest-elem (.valAt this query)]
        (when nearest-elem
          [nearest-elem (distance-fn query nearest-elem)]))))
  (exact-contains? [_ k]
    (if (tree/node-find root k cmp) true false))
  (exact-get [_ k]
    (throw (UnsupportedOperationException. "exact-get not supported for FuzzySet")))
  (exact-get [_ k not-found]
    (throw (UnsupportedOperationException. "exact-get not supported for FuzzySet"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Additional Methods
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nearest
  "Find the nearest element to query in the fuzzy set.
   Returns [element distance] or nil if empty."
  [^FuzzySet fs query]
  (when-not (node/leaf? (.-root fs))
    (let [nearest (.valAt fs query)]
      (when nearest
        [nearest ((.-distance-fn fs) query nearest)]))))

(defn exact-contains?
  "Check if the fuzzy set contains exactly the given element."
  [^FuzzySet fs k]
  (if (tree/node-find (.-root fs) k (.-cmp fs)) true false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method FuzzySet [s ^java.io.Writer w]
  (.write w "#<FuzzySet ")
  (print-method (vec (seq s)) w)
  (.write w ">"))
