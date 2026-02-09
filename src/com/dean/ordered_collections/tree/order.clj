(ns com.dean.ordered-collections.tree.order
  (:refer-clojure :exclude [compare <= >= max])
  (:import [java.util Comparator]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; All comparators implement java.util.Comparator for fast .compare dispatch.
;; This avoids IFn invoke overhead (~5-10ns per call vs ~1-2ns for invokeinterface).

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
  "Default comparator that delegates to clojure.core/compare.
   For best numeric performance, use long-ordered-set/long-ordered-map."
  (reify Comparator
    (compare [_ x y]
      (clojure.core/compare x y))))

(def ^Comparator long-compare
  "Specialized comparator for Long keys. Avoids type dispatch overhead of
   clojure.core/compare for ~15-25% faster comparisons on numeric keys."
  (reify Comparator
    (compare [_ x y]
      (Long/compare (long x) (long y)))))

(def ^Comparator double-compare
  "Specialized comparator for Double keys."
  (reify Comparator
    (compare [_ x y]
      (Double/compare (double x) (double y)))))

(def ^Comparator string-compare
  "Specialized comparator for String keys. Uses String.compareTo directly."
  (reify Comparator
    (compare [_ x y]
      (.compareTo ^String x y))))

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
