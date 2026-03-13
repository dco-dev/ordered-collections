(ns com.dean.ordered-collections.readers
  "Tagged literal reader functions for EDN round-tripping.

   These functions are referenced by data_readers.clj and are called by
   the Clojure reader when it encounters tagged literals like #ordered/set.

   For use with clojure.edn/read-string, pass `readers` as the :readers option."
  (:require [com.dean.ordered-collections.core :as oc]))

(defn ordered-set
  "Reader function for #ordered/set [...]"
  [coll]
  (oc/ordered-set coll))

(defn ordered-map
  "Reader function for #ordered/map [[k v] ...]"
  [coll]
  (oc/ordered-map coll))

(defn interval-set
  "Reader function for #ordered/interval-set [...]"
  [coll]
  (oc/interval-set coll))

(defn interval-map
  "Reader function for #ordered/interval-map [[k v] ...]"
  [coll]
  (oc/interval-map coll))

(defn range-map
  "Reader function for #ordered/range-map [[[lo hi] v] ...]"
  [coll]
  (oc/range-map coll))

(defn priority-queue
  "Reader function for #ordered/priority-queue [[priority value] ...]"
  [coll]
  (oc/priority-queue coll))

(defn ordered-multiset
  "Reader function for #ordered/multiset [...]"
  [coll]
  (oc/ordered-multiset coll))

(def readers
  "Map of tag symbols to reader functions.
   Pass to clojure.edn/read-string as the :readers option:

     (clojure.edn/read-string {:readers readers} s)"
  {'ordered/set            ordered-set
   'ordered/map            ordered-map
   'ordered/interval-set   interval-set
   'ordered/interval-map   interval-map
   'ordered/range-map      range-map
   'ordered/priority-queue priority-queue
   'ordered/multiset       ordered-multiset})
