(ns com.dean.ordered-collections.tree.order
  (:refer-clojure :exclude [compare <= >= max])
  (:import [java.util Comparator]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; All comparators implement java.util.Comparator for fast .compare dispatch.
;; This avoids IFn invoke overhead (~5-10ns per call vs ~1-2ns for invokeinterface).

(defn normalize ^long [^long x]
  (if (zero? x)
    x
    (bit-or 1
      (bit-shift-right x 63))))

(defn compare-by
  "Given a predicate that defines a total order over some domain,
  return a three-way Comparator built from it."
  ^Comparator [pred]
  (reify Comparator
    (compare [_ x y]
      (cond
        (pred x y) -1
        (pred y x) +1
        :else       0))))

(def ^Comparator normal-compare
  "Default comparator using clojure.core/compare. Implements java.util.Comparator
   for fast .compare dispatch in tree operations."
  (reify Comparator
    (compare [_ x y]
      (clojure.core/compare x y))))

(def ^Comparator long-compare
  "Specialized comparator for Long keys. Avoids type dispatch overhead of
   clojure.core/compare for ~15-25% faster comparisons on numeric keys."
  (reify Comparator
    (compare [_ x y]
      (Long/compare (long x) (long y)))))

(def ^Comparator int-compare
  "Specialized comparator for Integer keys."
  (reify Comparator
    (compare [_ x y]
      (Integer/compare (int x) (int y)))))

(def ^Comparator string-compare
  "Specialized comparator for String keys."
  (reify Comparator
    (compare [_ x y]
      (.compareTo ^String x y))))

(def ^:dynamic ^Comparator *compare* normal-compare)

(defn compare ^long [x y]
  (.compare ^Comparator *compare* x y))

(defn compare< [x y]
  (neg? (compare x y)))

(defn compare<= [x y]
  (not (pos? (compare x y))))

(defn compare> [x y]
  (pos? (compare x y)))

(defn compare>= [x y]
  (not (neg? (compare x y))))

(defn compare= [x y]
  (zero? (compare x y)))

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
