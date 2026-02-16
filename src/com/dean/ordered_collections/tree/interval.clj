(ns com.dean.ordered-collections.tree.interval
  (:require [com.dean.ordered-collections.tree.order :as order])
  (:import  [clojure.lang MapEntry PersistentVector]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PInterval
  "an interval is represented as an ordered pair of endpoints"
  (a [_] "interval start coordinate")
  (b [_] "interval end coordinate"))

(extend-protocol PInterval
  MapEntry
  (a [this] (.key this))
  (b [this] (.val this))
  PersistentVector
  (a [this] (this 0))
  (b [this] (this 1)))

(defn ordered-pair?
  "valid interval pair?"
  [x]
  (and (or (instance? MapEntry x) (instance? PersistentVector x) (satisfies? PInterval x))
       (order/compare<= (a x) (b x))))

(defn ordered-pair
  "Ensure a normalized interval pair."
  ([x y] {:pre [(order/compare<= x y)]}
   (MapEntry. x y))
  ([x]
   (cond
     (ordered-pair? x) x
     (sequential? x) (throw (AssertionError. (str "Non ordered sequential pair " x)))
     true (MapEntry. x x))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Semantics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn overlaps?
  "Overlapping intervals?   [=========]
                                [=========]"
  [i0 i1]
  (or (order/<= (a i1) (b i0) (b i1))
      (order/<= (a i1) (a i0) (b i1))))

(defn includes?
  "Inclusive intervals?    [==========]
                              [====]"
  [i0 i1]
  (order/<= (a i0) (a i1) (b i1) (b i0)))

(defn intersects?
  "returns true if there is any common point between intervals i0 and i1"
  [i0 i1]
  (or (overlaps? i0 i1)
      (includes? i0 i1)
      (includes? i1 i0)))
