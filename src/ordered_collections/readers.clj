(ns ordered-collections.readers
  "Tagged literal reader functions for EDN round-tripping.

   These functions are referenced by data_readers.clj and are called by
   the Clojure reader when it encounters tagged literals like #ordered/set.

   For use with clojure.edn/read-string, pass `readers` as the :readers option."
  (:require [ordered-collections.core :as oc]))

(def ordered-set      oc/ordered-set)
(def ordered-map      oc/ordered-map)
(def interval-set     oc/interval-set)
(def interval-map     oc/interval-map)
(def range-map        oc/range-map)
(def priority-queue   oc/priority-queue)
(def ordered-multiset oc/ordered-multiset)
(def rope             oc/rope)

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
   'ordered/multiset       ordered-multiset
   'ordered/rope           rope})
