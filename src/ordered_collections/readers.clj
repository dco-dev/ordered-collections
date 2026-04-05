(ns ordered-collections.readers
  "Tagged literal reader functions for EDN round-tripping.

   These functions are referenced by data_readers.clj and are called by
   the Clojure reader when it encounters tagged literals like #oc/set.

   For use with clojure.edn/read-string, pass `readers` as the :readers option."
  (:require [ordered-collections.core :as oc]))

(def ordered-set      oc/ordered-set)
(def ordered-map      oc/ordered-map)
(def interval-set     oc/interval-set)
(def interval-map     oc/interval-map)
(def range-map        oc/range-map)
(def priority-queue   oc/priority-queue)
(def ordered-multiset oc/ordered-multiset)

(def readers
  "Map of tag symbols to reader functions.
   Pass to clojure.edn/read-string as the :readers option:

     (clojure.edn/read-string {:readers readers} s)"
  {'oc/set            ordered-set
   'oc/map            ordered-map
   'oc/interval-set   interval-set
   'oc/interval-map   interval-map
   'oc/range-map      range-map
   'oc/priority-queue priority-queue
   'oc/multiset       ordered-multiset})
