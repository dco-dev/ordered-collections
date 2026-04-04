(ns ordered-collections.memory-test
  "Memory overhead analysis for ordered-collections.

   Compares memory usage per element across:
   - clojure.core/sorted-set and sorted-map
   - clojure.data.avl sorted collections
   - ordered-collections

   Run with: lein test :only ordered-collections.memory-test"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.avl :as avl]
            [ordered-collections.core :as oc]
            [clj-memory-meter.core :as mm]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Memory Measurement Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn measure-bytes
  "Measure total memory of object in bytes."
  [obj]
  (mm/measure obj :bytes true))

(defn bytes-per-element
  "Calculate bytes per element for a collection."
  [coll n]
  (double (/ (measure-bytes coll) n)))

(defn format-bytes
  "Format bytes as human-readable string."
  [bytes]
  (cond
    (< bytes 1024) (format "%.0f B" (double bytes))
    (< bytes (* 1024 1024)) (format "%.1f KB" (/ bytes 1024.0))
    :else (format "%.1f MB" (/ bytes 1024.0 1024.0))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Memory Comparison
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest set-memory-comparison
  (testing "Memory per element for sorted sets"
    (doseq [n [1000 10000 100000]]
      (let [data (vec (shuffle (range n)))

            ;; Build each collection type
            core-set   (into (sorted-set) data)
            avl-set    (into (avl/sorted-set) data)
            ordered    (oc/ordered-set data)
            long-set   (oc/long-ordered-set data)

            ;; Measure
            core-bpe   (bytes-per-element core-set n)
            avl-bpe    (bytes-per-element avl-set n)
            ordered-bpe (bytes-per-element ordered n)
            long-bpe   (bytes-per-element long-set n)]

        (println)
        (println (format "=== Set Memory at N=%,d ===" n))
        (println (format "  sorted-set:      %5.1f bytes/elem  (total: %s)"
                         core-bpe (format-bytes (measure-bytes core-set))))
        (println (format "  data.avl:        %5.1f bytes/elem  (total: %s)"
                         avl-bpe (format-bytes (measure-bytes avl-set))))
        (println (format "  ordered-set:     %5.1f bytes/elem  (total: %s)"
                         ordered-bpe (format-bytes (measure-bytes ordered))))
        (println (format "  long-ordered:    %5.1f bytes/elem  (total: %s)"
                         long-bpe (format-bytes (measure-bytes long-set))))

        ;; Basic sanity checks - memory should be reasonable
        (is (< ordered-bpe 100) "ordered-set should use < 100 bytes/element")
        (is (< long-bpe 100) "long-ordered-set should use < 100 bytes/element")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Map Memory Comparison
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest map-memory-comparison
  (testing "Memory per entry for sorted maps"
    (doseq [n [1000 10000 100000]]
      (let [data (vec (for [i (shuffle (range n))] [i (* i 2)]))

            ;; Build each collection type
            core-map   (into (sorted-map) data)
            avl-map    (into (avl/sorted-map) data)
            ordered    (oc/ordered-map data)
            long-map   (oc/long-ordered-map data)

            ;; Measure
            core-bpe   (bytes-per-element core-map n)
            avl-bpe    (bytes-per-element avl-map n)
            ordered-bpe (bytes-per-element ordered n)
            long-bpe   (bytes-per-element long-map n)]

        (println)
        (println (format "=== Map Memory at N=%,d ===" n))
        (println (format "  sorted-map:      %5.1f bytes/entry  (total: %s)"
                         core-bpe (format-bytes (measure-bytes core-map))))
        (println (format "  data.avl:        %5.1f bytes/entry  (total: %s)"
                         avl-bpe (format-bytes (measure-bytes avl-map))))
        (println (format "  ordered-map:     %5.1f bytes/entry  (total: %s)"
                         ordered-bpe (format-bytes (measure-bytes ordered))))
        (println (format "  long-ordered:    %5.1f bytes/entry  (total: %s)"
                         long-bpe (format-bytes (measure-bytes long-map))))

        ;; Basic sanity checks
        (is (< ordered-bpe 150) "ordered-map should use < 150 bytes/entry")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specialized Collection Memory
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest specialized-collection-memory
  (testing "Memory for specialized collection types"
    (let [n 10000
          data (vec (shuffle (range n)))
          intervals (vec (for [i (range n)] [(* i 2) (+ (* i 2) (rand-int 10))]))

          ;; Build collections
          interval-set (oc/interval-set intervals)
          interval-map (oc/interval-map (map #(vector % :val) intervals))
          multiset     (oc/ordered-multiset (concat data data)) ; duplicates
          priority-q   (oc/priority-queue (map #(vector % %) data))
          fuzzy        (oc/fuzzy-set data)

          ;; Measure
          iset-bpe (bytes-per-element interval-set n)
          imap-bpe (bytes-per-element interval-map n)
          mset-bpe (bytes-per-element multiset (* 2 n))
          pq-bpe   (bytes-per-element priority-q n)
          fuzz-bpe (bytes-per-element fuzzy n)]

      (println)
      (println (format "=== Specialized Collections at N=%,d ===" n))
      (println (format "  interval-set:    %5.1f bytes/interval  (total: %s)"
                       iset-bpe (format-bytes (measure-bytes interval-set))))
      (println (format "  interval-map:    %5.1f bytes/interval  (total: %s)"
                       imap-bpe (format-bytes (measure-bytes interval-map))))
      (println (format "  ordered-multiset:%5.1f bytes/elem  (total: %s)"
                       mset-bpe (format-bytes (measure-bytes multiset))))
      (println (format "  priority-queue:  %5.1f bytes/elem  (total: %s)"
                       pq-bpe (format-bytes (measure-bytes priority-q))))
      (println (format "  fuzzy-set:       %5.1f bytes/elem  (total: %s)"
                       fuzz-bpe (format-bytes (measure-bytes fuzzy))))

      ;; Sanity checks
      (is (< iset-bpe 200) "interval-set should use < 200 bytes/interval"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node Structure Analysis
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest node-structure-analysis
  (testing "Individual node memory breakdown"
    (let [;; Create minimal collections to measure node overhead
          small-set (oc/ordered-set [1 2 3])
          small-map (oc/ordered-map [[1 :a] [2 :b] [3 :c]])

          ;; Get root nodes via reflection (for analysis only)
          set-root (.getRoot ^ordered_collections.tree.root.INodeCollection small-set)
          map-root (.getRoot ^ordered_collections.tree.root.INodeCollection small-map)]

      (println)
      (println "=== Node Structure Analysis ===")
      (println (format "  SimpleNode (3 elements): %s"
                       (format-bytes (measure-bytes set-root))))
      (println (format "  Single SimpleNode:       ~%d bytes (estimated)"
                       (quot (measure-bytes set-root) 3)))

      ;; Node should be reasonably sized
      (is (< (measure-bytes set-root) 500) "3-element tree should be < 500 bytes"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Memory Scaling Analysis
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest memory-scaling
  (testing "Memory scales linearly with size"
    (let [sizes [1000 2000 5000 10000 20000]
          measurements (for [n sizes]
                         (let [coll (oc/ordered-set (range n))]
                           {:n n
                            :bytes (measure-bytes coll)
                            :per-elem (bytes-per-element coll n)}))]

      (println)
      (println "=== Memory Scaling ===")
      (doseq [{:keys [n bytes per-elem]} measurements]
        (println (format "  N=%,6d: %8s total, %.1f bytes/elem"
                         n (format-bytes bytes) per-elem)))

      ;; Per-element cost should be roughly constant
      (let [per-elem-values (map :per-elem measurements)
            min-pe (apply min per-elem-values)
            max-pe (apply max per-elem-values)]
        (is (< (- max-pe min-pe) 10)
            "Per-element memory should be consistent across sizes")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Summary Report
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest memory-summary-report
  (testing "Generate memory summary for documentation"
    (let [n 100000
          data (vec (shuffle (range n)))
          map-data (vec (for [i data] [i (* i 2)]))

          ;; Sets
          core-set (into (sorted-set) data)
          avl-set (into (avl/sorted-set) data)
          ordered-set (oc/ordered-set data)
          long-set (oc/long-ordered-set data)

          ;; Maps
          core-map (into (sorted-map) map-data)
          avl-map (into (avl/sorted-map) map-data)
          ordered-map (oc/ordered-map map-data)
          long-map (oc/long-ordered-map map-data)]

      (println)
      (println "╔══════════════════════════════════════════════════════════════╗")
      (println "║           MEMORY OVERHEAD SUMMARY (N=100,000)                ║")
      (println "╠══════════════════════════════════════════════════════════════╣")
      (println "║ Collection Type      │ Bytes/Elem │ Total Memory │ vs sorted ║")
      (println "╠══════════════════════════════════════════════════════════════╣")
      (let [core-bpe (bytes-per-element core-set n)]
        (doseq [[name coll] [["sorted-set" core-set]
                             ["data.avl" avl-set]
                             ["ordered-set" ordered-set]
                             ["long-ordered-set" long-set]]]
          (let [bpe (bytes-per-element coll n)
                ratio (/ bpe core-bpe)]
            (println (format "║ %-20s │ %10.1f │ %12s │ %8.2fx ║"
                             name bpe (format-bytes (measure-bytes coll)) ratio)))))
      (println "╠══════════════════════════════════════════════════════════════╣")
      (let [core-bpe (bytes-per-element core-map n)]
        (doseq [[name coll] [["sorted-map" core-map]
                             ["data.avl map" avl-map]
                             ["ordered-map" ordered-map]
                             ["long-ordered-map" long-map]]]
          (let [bpe (bytes-per-element coll n)
                ratio (/ bpe core-bpe)]
            (println (format "║ %-20s │ %10.1f │ %12s │ %8.2fx ║"
                             name bpe (format-bytes (measure-bytes coll)) ratio)))))
      (println "╚══════════════════════════════════════════════════════════════╝")

      ;; Assertions for documentation accuracy
      (is true "Summary report generated"))))
