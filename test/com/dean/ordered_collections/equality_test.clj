(ns com.dean.ordered-collections.equality-test
  "Generative tests for equality and hashing across all ordered collection types.

   Verifies:
   - Cross-type equality (ordered-set ↔ sorted-set ↔ hash-set ↔ data.avl, etc.)
   - Hash contract compliance (equals → same hash, across types)
   - Hash consistency (insertion order independence)
   - Equality symmetry (a = b ↔ b = a)
   - Specialized variant equality (long-ordered-set = ordered-set, etc.)"
  (:require [clojure.data.avl :as avl]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [com.dean.ordered-collections.core :as oc]
            [com.dean.ordered-collections.test-utils :as tu]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def gen-intervals
  "Vectors of [lo hi] interval pairs."
  (gen/vector
    (gen/fmap (fn [[a b]] [(min a b) (max a b)])
              (gen/tuple (gen/choose -100 100) (gen/choose -100 100)))
    0 50))

(def gen-interval-entries
  "Vectors of [[lo hi] value] pairs with distinct interval keys."
  (gen/fmap
    (fn [kvs] (mapv (fn [[k v]] [[(* k 2) (inc (* k 2))] v]) kvs))
    tu/gen-int-map-entries))

(def gen-multiset-elements
  "Vectors of integers (duplicates allowed)."
  (gen/vector (gen/choose -50 50) 0 100))

(def gen-non-overlapping-ranges
  "Vectors of [[lo hi] value] with non-overlapping ranges."
  (gen/fmap
    (fn [points]
      (let [sorted (sort (distinct points))
            pairs  (partition 2 sorted)]
        (mapv (fn [[lo hi]] [[lo hi] lo]) pairs)))
    (gen/vector (gen/choose -100 100) 0 20)))

(def gen-segment-entries
  "Maps of {int-index int-value}."
  (gen/fmap #(into {} (map-indexed vector %))
            (gen/vector gen/small-integer 0 50)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-set-cross-type-equality-and-hash 100
  (prop/for-all [xs tu/gen-int-set]
    (let [os  (tu/->os xs)
          ss  (tu/->ss xs)
          hs  (tu/->hs xs)
          avl (tu/->as xs)]
      (and (= os ss) (= ss os)
           (= os hs) (= hs os)
           (= os avl) (= avl os)
           (= (hash os) (hash ss) (hash hs) (hash avl))))))

(defspec prop-set-variant-equality-and-hash 100
  (prop/for-all [xs tu/gen-int-set]
    (let [os  (tu/->os xs)
          los (tu/->los xs)
          ros (oc/ordered-set-by > xs)
          ss  (tu/->ss xs)]
      (and (= os los) (= los os) (= los ss)
           (= os ros) (= ros os) (= ros ss)
           (= (hash os) (hash los) (hash ros) (hash ss))))))

(defspec prop-set-string-variant-equality-and-hash 100
  (prop/for-all [xs (gen/vector-distinct gen/string-alphanumeric
                                         {:min-elements 0 :max-elements 100})]
    (let [os  (oc/ordered-set xs)
          sos (oc/string-ordered-set xs)
          ss  (into (sorted-set) xs)]
      (and (= os sos) (= sos os) (= os ss) (= ss os)
           (= (hash os) (hash sos) (hash ss))))))

(defspec prop-set-hash-insertion-order 100
  (prop/for-all [xs tu/gen-int-set]
    (= (hash (tu/->os xs))
       (hash (tu/->os (shuffle xs))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-map-cross-type-equality-and-hash 100
  (prop/for-all [kvs tu/gen-int-map-entries]
    (let [om  (tu/->om kvs)
          sm  (tu/->sm kvs)
          hm  (into {} kvs)
          avl (tu/->am kvs)]
      (and (= om sm) (= sm om)
           (= om hm) (= hm om)
           (= om avl) (= avl om)
           (= (hash om) (hash sm) (hash hm) (hash avl))))))

(defspec prop-map-variant-equality-and-hash 100
  (prop/for-all [kvs tu/gen-int-map-entries]
    (let [om  (tu/->om kvs)
          lom (oc/long-ordered-map kvs)
          rom (oc/ordered-map-by > kvs)
          sm  (tu/->sm kvs)
          hm  (into {} kvs)
          avl (tu/->am kvs)]
      (and ;; cross-variant equality (symmetric)
           (= om lom)  (= lom om)
           (= om rom)  (= rom om)
           (= lom rom) (= rom lom)
           ;; each variant equals reference implementations
           (= lom sm) (= sm lom) (= lom hm) (= hm lom) (= lom avl) (= avl lom)
           (= rom sm) (= sm rom) (= rom hm) (= hm rom) (= rom avl) (= avl rom)
           ;; hash contract across all variants
           (= (hash om) (hash lom) (hash rom) (hash sm) (hash hm) (hash avl))))))

(defspec prop-map-hash-insertion-order 100
  (prop/for-all [kvs tu/gen-int-map-entries]
    (= (hash (tu/->om kvs))
       (hash (tu/->om (shuffle kvs))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-fuzzy-set-cross-type-equality-and-hash 100
  (prop/for-all [xs tu/gen-int-set]
    (let [fs  (oc/fuzzy-set xs)
          os  (tu/->os xs)
          ss  (tu/->ss xs)
          hs  (tu/->hs xs)
          avl (tu/->as xs)]
      (and (= fs os) (= os fs)
           (= fs ss) (= ss fs)
           (= fs hs) (= hs fs)
           (= fs avl) (= avl fs)
           (= (hash fs) (hash os) (hash ss) (hash hs) (hash avl))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fuzzy Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-fuzzy-map-cross-type-equality-and-hash 100
  (prop/for-all [kvs tu/gen-int-map-entries]
    (let [fm  (oc/fuzzy-map kvs)
          om  (tu/->om kvs)
          sm  (tu/->sm kvs)
          hm  (into {} kvs)
          avl (tu/->am kvs)]
      (and (= fm om) (= om fm)
           (= fm sm) (= sm fm)
           (= fm hm) (= hm fm)
           (= fm avl) (= avl fm)
           (= (hash fm) (hash om) (hash sm) (hash hm) (hash avl))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-interval-set-equality-and-hash 100
  (prop/for-all [intervals gen-intervals]
    (let [is1 (oc/interval-set intervals)
          is2 (oc/interval-set (shuffle intervals))]
      (and (= is1 is2) (= is2 is1)
           (= (hash is1) (hash is2))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interval Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-interval-map-equality-and-hash 100
  (prop/for-all [entries gen-interval-entries]
    (let [im1 (oc/interval-map entries)
          im2 (oc/interval-map (shuffle entries))]
      (and (= im1 im2) (= im2 im1)
           (= (hash im1) (hash im2))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered Multiset
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-multiset-equality-and-hash 100
  (prop/for-all [xs gen-multiset-elements]
    (let [ms1 (oc/ordered-multiset xs)
          ms2 (oc/ordered-multiset (shuffle xs))]
      (and (= ms1 ms2) (= ms2 ms1)
           (= (hash ms1) (hash ms2))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-pq-equality-and-hash 100
  (prop/for-all [kvs tu/gen-int-map-entries]
    (let [pq1 (oc/priority-queue kvs)
          pq2 (oc/priority-queue (shuffle kvs))]
      (and (= pq1 pq2) (= pq2 pq1)
           (= (hash pq1) (hash pq2))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Range Map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-range-map-equality-and-hash 100
  (prop/for-all [entries gen-non-overlapping-ranges]
    (let [rm1 (oc/range-map entries)
          rm2 (oc/range-map (shuffle entries))]
      (and (= rm1 rm2) (= rm2 rm1)
           (= (hash rm1) (hash rm2))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Segment Tree
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec prop-segment-tree-equality-and-hash 100
  (prop/for-all [entries gen-segment-entries]
    (let [st1 (oc/segment-tree + 0 entries)
          st2 (oc/segment-tree + 0 (shuffle (vec entries)))]
      (and (= st1 st2) (= st2 st1)
           (= (hash st1) (hash st2))))))
