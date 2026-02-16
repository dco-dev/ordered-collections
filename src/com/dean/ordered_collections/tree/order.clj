(ns com.dean.ordered-collections.tree.order
  (:refer-clojure :exclude [compare <= >= max])
  (:import [java.util Comparator]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; All comparators implement java.util.Comparator for fast .compare dispatch.
;; This avoids IFn invoke overhead (~5-10ns per call vs ~1-2ns for invokeinterface).

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Serializable Comparator Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Using deftype instead of reify so comparators are serializable.
;; This enables Java serialization of collections that store these comparators.

;; Each comparator type implements equals/hashCode based on type,
;; so that equivalent comparators are considered equal after deserialization.

(deftype NormalComparator []
  java.io.Serializable
  Comparator
  (compare [_ x y]
    (clojure.core/compare x y))
  Object
  (equals [_ o] (instance? NormalComparator o))
  (hashCode [_] 1))

(deftype LongComparator []
  java.io.Serializable
  Comparator
  (compare [_ x y]
    (Long/compare (long x) (long y)))
  Object
  (equals [_ o] (instance? LongComparator o))
  (hashCode [_] 2))

(deftype DoubleComparator []
  java.io.Serializable
  Comparator
  (compare [_ x y]
    (Double/compare (double x) (double y)))
  Object
  (equals [_ o] (instance? DoubleComparator o))
  (hashCode [_] 3))

(deftype StringComparator []
  java.io.Serializable
  Comparator
  (compare [_ x y]
    (.compareTo ^String x y))
  Object
  (equals [_ o] (instance? StringComparator o))
  (hashCode [_] 4))

(deftype PredicateComparator [pred]
  java.io.Serializable
  Comparator
  (compare [_ x y]
    (cond
      (pred x y) -1
      (pred y x) +1
      :else       0))
  Object
  (equals [this o]
    (and (instance? PredicateComparator o)
         (= pred (.-pred ^PredicateComparator o))))
  (hashCode [_] (hash pred)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparator Instances
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn compare-by
  "Given a predicate that defines a total order over some domain,
  return a three-way Comparator built from it.
  Note: The predicate must be serializable for the comparator to be serializable."
  ^Comparator [pred]
  (->PredicateComparator pred))

(def ^Comparator normal-compare
  "Default comparator that delegates to clojure.core/compare.
   For best numeric performance, use long-ordered-set/long-ordered-map."
  (->NormalComparator))

(def ^Comparator long-compare
  "Specialized comparator for Long keys. Avoids type dispatch overhead of
   clojure.core/compare for ~15-25% faster comparisons on numeric keys."
  (->LongComparator))

(def ^Comparator double-compare
  "Specialized comparator for Double keys."
  (->DoubleComparator))

(def ^Comparator string-compare
  "Specialized comparator for String keys. Uses String.compareTo directly."
  (->StringComparator))

(def ^:dynamic ^Comparator *compare* normal-compare)

(defn compare ^long [x y]
  (.compare ^Comparator *compare* x y))

(defn compare<= [x y]
  (not (pos? (compare x y))))

(defn compare> [x y]
  (pos? (compare x y)))

(defn compare>= [x y]
  (not (neg? (compare x y))))

(defn max [x & args]
  (reduce #(if (compare> %1 %2) %1 %2) x args))

(defn <=
  ([x] true)
  ([x y] (compare<= x y))
  ([x y & more]
   (if (compare<= x y)
     (if (next more)
       (recur y (first more) (next more))
       (compare<= y (first more)))
     false)))

(defn >=
  ([x] true)
  ([x y] (compare>= x y))
  ([x y & more]
   (if (compare>= x y)
     (if (next more)
       (recur y (first more) (next more))
       (compare>= y (first more)))
     false)))
