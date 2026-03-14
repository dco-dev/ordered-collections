(ns com.dean.ordered-collections.types.fuzzy-map
  "A map that returns the value associated with the closest key.

   When looking up a key, returns the value for the key in the map that is
   closest to the query. For numeric keys, distance is |query - key|.

   Tie-breaking: When two keys are equidistant, use :< to prefer the
   smaller key, or :> to prefer the larger key."
  (:require [clojure.core.reducers       :as r :refer [coll-fold]]
            [com.dean.ordered-collections.types.fuzzy-set :as fuzzy]
            [com.dean.ordered-collections.tree.node     :as node]
            [com.dean.ordered-collections.tree.order    :as order]
            [com.dean.ordered-collections.protocol      :as proto :refer [PRanked]]
            [com.dean.ordered-collections.tree.root]
            [com.dean.ordered-collections.tree.tree     :as tree])
  (:import  [clojure.lang                RT Murmur3 MapEntry]
            [com.dean.ordered_collections.protocol PFuzzy]
            [com.dean.ordered_collections.tree.root     INodeCollection
                                         IBalancedCollection
                                         IOrderedCollection]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Nearest Lookup for Maps
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find-nearest-entry
  "Find the entry with key nearest to query in the tree.

   Parameters:
   - root: the tree root
   - query: the key to find nearest to
   - cmp: comparator for ordering
   - distance-fn: (fn [a b] -> number) returns distance between keys
   - tiebreak: :< (prefer smaller) or :> (prefer larger) when equidistant

   Returns [key value] for the nearest entry, or nil if tree is empty."
  [root query ^java.util.Comparator cmp distance-fn tiebreak]
  (if (node/leaf? root)
    nil
    (binding [order/*compare* cmp]
      (let [;; Split tree at query point
            [lt present gt] (tree/node-split root query)
            ;; Get floor (greatest key <= query)
            floor-node (if present
                         present
                         (when-not (node/leaf? lt)
                           (tree/node-greatest lt)))
            ;; Get ceiling (least key >= query)
            ceiling-node (if present
                           present
                           (when-not (node/leaf? gt)
                             (tree/node-least gt)))]
        (cond
          ;; Query key exists exactly
          present
          [(first present) (second present)]

          ;; Only floor exists
          (and floor-node (nil? ceiling-node))
          [(node/-k floor-node) (node/-v floor-node)]

          ;; Only ceiling exists
          (and ceiling-node (nil? floor-node))
          [(node/-k ceiling-node) (node/-v ceiling-node)]

          ;; Both exist - compare distances
          (and floor-node ceiling-node)
          (let [floor-key (node/-k floor-node)
                ceiling-key (node/-k ceiling-node)
                floor-dist (distance-fn query floor-key)
                ceiling-dist (distance-fn query ceiling-key)]
            (cond
              (< floor-dist ceiling-dist) [floor-key (node/-v floor-node)]
              (> floor-dist ceiling-dist) [ceiling-key (node/-v ceiling-node)]
              ;; Equal distance - use tiebreaker
              (= tiebreak :<) [floor-key (node/-v floor-node)]
              :else [ceiling-key (node/-v ceiling-node)]))

          ;; Empty tree
          :else nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic Environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-fuzzy-map [x & body]
  `(binding [order/*compare* (.getCmp ~(with-meta x {:tag 'com.dean.ordered_collections.tree.root.IOrderedCollection}))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FuzzyMap Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype FuzzyMap [root cmp distance-fn tiebreak _meta]

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
    (and (instance? FuzzyMap o)
         (= cmp (.getCmp ^FuzzyMap o))
         (= distance-fn (.-distance-fn ^FuzzyMap o))
         (= tiebreak (.-tiebreak ^FuzzyMap o))))
  (isSimilar [_ o]
    (map? o))

  IBalancedCollection
  (getStitch [_]
    tree/node-stitch-weight-balanced)

  clojure.lang.IMeta
  (meta [_]
    _meta)

  clojure.lang.IObj
  (withMeta [_ m]
    (new FuzzyMap root cmp distance-fn tiebreak m))

  clojure.lang.Indexed
  (nth [_ i]
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
  ;; Fuzzy lookup - returns the value for the nearest key
  (valAt [this query not-found]
    (if (node/leaf? root)
      not-found
      (if-let [[_ v] (find-nearest-entry root query cmp distance-fn tiebreak)]
        v
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

  java.lang.Comparable
  (compareTo [this o]
    (with-fuzzy-map this
      (cond
        (identical? this o) 0
        (.isCompatible this o) (tree/node-map-compare root (.getRoot ^FuzzyMap o))
        (.isSimilar this o) (.compareTo ^Comparable (into (empty o) this) o)
        true (throw (ex-info "unsupported comparison: " {:this this :o o})))))

  java.util.Map
  (size [_]
    (tree/node-size root))
  (isEmpty [_]
    (node/leaf? root))
  (containsValue [this v]
    (with-fuzzy-map this
      (boolean
        (tree/node-reduce
          (fn [_ n] (when (= v (node/-v n)) (reduced true)))
          nil root))))
  (get [this k]
    (.valAt this k))
  (put [_ _ _]
    (throw (UnsupportedOperationException.)))
  (remove [_ _]
    (throw (UnsupportedOperationException.)))
  (putAll [_ _]
    (throw (UnsupportedOperationException.)))
  (clear [_]
    (throw (UnsupportedOperationException.)))
  (keySet [this]
    (with-fuzzy-map this
      (set (map node/-k (tree/node-seq root)))))
  (values [this]
    (with-fuzzy-map this
      (map node/-v (tree/node-seq root))))
  (entrySet [this]
    (with-fuzzy-map this
      (set (map node/-kv (tree/node-seq root)))))

  java.util.SortedMap
  (comparator [_]
    cmp)
  (firstKey [this]
    (with-fuzzy-map this
      (first (tree/node-least-kv root))))
  (lastKey [this]
    (with-fuzzy-map this
      (first (tree/node-greatest-kv root))))
  (headMap [this k]
    (with-fuzzy-map this
      (new FuzzyMap (tree/node-split-lesser root k) cmp distance-fn tiebreak {})))
  (tailMap [this k]
    (with-fuzzy-map this
      (let [[_ present gt] (tree/node-split root k)]
        (if present
          (new FuzzyMap (tree/node-add gt (first present) (second present)) cmp distance-fn tiebreak {})
          (new FuzzyMap gt cmp distance-fn tiebreak {})))))
  (subMap [this from to]
    (with-fuzzy-map this
      (let [[_ from-present from-gt] (tree/node-split root from)
            from-tree (if from-present
                        (tree/node-add from-gt (first from-present) (second from-present))
                        from-gt)
            to-tree (tree/node-split-lesser root to)
            result (tree/node-set-intersection from-tree to-tree)]
        (new FuzzyMap result cmp distance-fn tiebreak {}))))

  clojure.lang.Sorted
  (entryKey [_ entry]
    (key entry))
  (seq [_ ascending]
    (if ascending
      (tree/entry-seq root)
      (tree/entry-seq-reverse root)))
  (seqFrom [this k ascending]
    (with-fuzzy-map this
      (let [[lt present gt] (tree/node-split root k)]
        (if ascending
          (if present
            (cons (MapEntry. (first present) (second present))
                  (tree/entry-seq gt))
            (tree/entry-seq gt))
          (if present
            (cons (MapEntry. (first present) (second present))
                  (tree/entry-seq-reverse lt))
            (tree/entry-seq-reverse lt))))))

  clojure.lang.Associative
  (containsKey [this k]
    (tree/node-contains? root k cmp))
  (entryAt [this k]
    (some-> root (tree/node-find k cmp) node/-kv))
  (assoc [this k v]
    (new FuzzyMap (tree/node-add root k v cmp tree/node-create-weight-balanced) cmp distance-fn tiebreak _meta))

  clojure.lang.IPersistentCollection
  (count [_]
    (tree/node-size root))
  (cons [this entry]
    (if (map? entry)
      (reduce (fn [m [k v]] (assoc m k v)) this (seq entry))
      (.assoc this (first entry) (second entry))))
  (empty [_]
    (new FuzzyMap (node/leaf) cmp distance-fn tiebreak {}))
  (equiv [this o]
    (with-fuzzy-map this
      (cond
        (identical? this o) true
        (not (instance? clojure.lang.Counted o)) false
        (not= (tree/node-size root) (.count ^clojure.lang.Counted o)) false
        (.isCompatible this o) (zero? (tree/node-map-compare root (.getRoot ^FuzzyMap o)))
        (.isSimilar this o) (.equiv ^clojure.lang.IPersistentCollection (into (empty o) this) o)
        :else false)))

  clojure.lang.IPersistentMap
  (assocEx [this k v]
    (if (.containsKey this k)
      (throw (Exception. "Key already present"))
      (.assoc this k v)))
  (without [this k]
    (new FuzzyMap (tree/node-remove root k cmp tree/node-create-weight-balanced) cmp distance-fn tiebreak _meta))

  Object
  (toString [this]
    (pr-str this))

  clojure.lang.IHashEq
  (hasheq [this]
    ;; Must match APersistentMap: sum of hasheq(MapEntry) for each entry, then mixCollHash
    (Murmur3/mixCollHash
      (unchecked-int
        (tree/node-reduce
          (fn [^long acc n]
            (unchecked-add acc (long (clojure.lang.Util/hasheq
                                       (clojure.lang.MapEntry. (node/-k n) (node/-v n))))))
          (long 0)
          root))
      (tree/node-size root)))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (tree/node-reduce (fn [acc n] (f acc (node/-kv n))) init root))

  clojure.lang.IReduce
  (reduce [this f]
    (let [sentinel (Object.)
          result (tree/node-reduce
                   (fn [acc n]
                     (if (identical? acc sentinel)
                       (node/-kv n)
                       (f acc (node/-kv n))))
                   sentinel root)]
      (if (identical? result sentinel) (f) result)))

  clojure.core.reducers.CollFold
  (coll-fold [this n combinef reducef]
    (with-fuzzy-map this
      (tree/node-chunked-fold n root combinef
        (fn [acc node] (reducef acc (node/-kv node))))))

  PRanked
  (rank-of [_ k]
    (or (tree/node-rank root k cmp) -1))
  (slice [_ start end]
    (let [n (tree/node-size root)
          start (max 0 (long start))
          end (min n (long end))]
      (when (< start end)
        (binding [order/*compare* cmp]
          (map (fn [node] (MapEntry. (node/-k node) (node/-v node)))
               (tree/node-subseq root start (dec end)))))))
  (median [_]
    (let [n (tree/node-size root)]
      (when (pos? n)
        (let [node (tree/node-nth root (quot (dec n) 2))]
          (MapEntry. (node/-k node) (node/-v node))))))
  (percentile [_ pct]
    (let [n (tree/node-size root)]
      (when (pos? n)
        (let [idx (min (dec n) (long (* (/ (double pct) 100.0) n)))
              node (tree/node-nth root idx)]
          (MapEntry. (node/-k node) (node/-v node))))))

  PFuzzy
  (nearest-with-distance [this query]
    (when-not (node/leaf? root)
      (when-let [[k v] (find-nearest-entry root query cmp distance-fn tiebreak)]
        [k v (distance-fn query k)])))
  (exact-contains? [_ k]
    (if (tree/node-find root k cmp) true false))
  (exact-get [_ k]
    (if-let [n (tree/node-find root k cmp)]
      (node/-v n)
      nil))
  (exact-get [_ k not-found]
    (if-let [n (tree/node-find root k cmp)]
      (node/-v n)
      not-found)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Additional Methods
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nearest
  "Find the entry with key nearest to query in the fuzzy map.
   Returns [key value distance] or nil if empty."
  [^FuzzyMap fm query]
  (when-not (node/leaf? (.-root fm))
    (when-let [[k v] (find-nearest-entry (.-root fm) query (.-cmp fm)
                                          (.-distance-fn fm) (.-tiebreak fm))]
      [k v ((.-distance-fn fm) query k)])))

(defn nearest-key
  "Find the key nearest to query in the fuzzy map.
   Returns [key distance] or nil if empty."
  [^FuzzyMap fm query]
  (when-not (node/leaf? (.-root fm))
    (when-let [[k _] (find-nearest-entry (.-root fm) query (.-cmp fm)
                                          (.-distance-fn fm) (.-tiebreak fm))]
      [k ((.-distance-fn fm) query k)])))

(defn exact-get
  "Get the value for exactly the given key (no fuzzy matching).
   Returns value or not-found."
  ([^FuzzyMap fm k]
   (exact-get fm k nil))
  ([^FuzzyMap fm k not-found]
   (if-let [n (tree/node-find (.-root fm) k (.-cmp fm))]
     (node/-v n)
     not-found)))

(defn exact-contains?
  "Check if the fuzzy map contains exactly the given key."
  [^FuzzyMap fm k]
  (if (tree/node-find (.-root fm) k (.-cmp fm)) true false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod print-method FuzzyMap [m ^java.io.Writer w]
  (.write w "#<FuzzyMap ")
  (print-method (into {} (seq m)) w)
  (.write w ">"))
