(ns com.dean.ordered-collections.range-map-bench
  "Benchmark comparing our range-map against Google Guava's TreeRangeMap.

   Run with: lein run -m com.dean.ordered-collections.range-map-bench/run-all

   Note: Our range-map is persistent (immutable) while Guava's is mutable.
   This means every modification creates a new structure via path-copying,
   which has inherent overhead but enables safe concurrent reads, undo/history,
   and structural sharing."
  (:require [com.dean.ordered-collections.core :as oc])
  (:import [com.google.common.collect TreeRangeMap Range]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Guava Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^TreeRangeMap guava-range-map []
  (TreeRangeMap/create))

(defn guava-put! [^TreeRangeMap grm ^long lo ^long hi v]
  (.put grm (Range/closedOpen lo hi) v)
  grm)

(defn guava-put-coalescing! [^TreeRangeMap grm ^long lo ^long hi v]
  (.putCoalescing grm (Range/closedOpen lo hi) v)
  grm)

(defn guava-remove! [^TreeRangeMap grm ^long lo ^long hi]
  (.remove grm (Range/closedOpen lo hi))
  grm)

(defn guava-get [^TreeRangeMap grm ^long x]
  (.get grm x))

(defn guava-get-entry [^TreeRangeMap grm ^long x]
  (.getEntry grm x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Infrastructure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro bench
  "Run f for warmup-iters, then measure actual-iters. Returns [total-ms per-op-ms]."
  [warmup-iters actual-iters & body]
  `(do
     ;; Warmup
     (dotimes [_# ~warmup-iters]
       ~@body)
     ;; GC before measurement
     (System/gc)
     (Thread/sleep 50)
     ;; Measure
     (let [start# (System/nanoTime)]
       (dotimes [_# ~actual-iters]
         ~@body)
       (let [end# (System/nanoTime)
             total-ms# (/ (- end# start#) 1e6)
             per-op# (/ total-ms# ~actual-iters)]
         [total-ms# per-op#]))))

(defn format-result [label [total-ms per-op]]
  (printf "  %-35s %8.2f ms total  %8.3f ms/op%n" label total-ms per-op)
  (flush))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Data Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn gen-non-overlapping-ranges
  "Generate n non-overlapping ranges [lo, hi) with values."
  ([n] (gen-non-overlapping-ranges n 100))
  ([n spacing]
   (vec (for [i (range n)]
          (let [lo (* i spacing)
                hi (+ lo (quot spacing 2) (rand-int (quot spacing 2)))]
            [lo hi (keyword (str "v" i))])))))

(defn gen-overlapping-ranges
  "Generate n potentially overlapping ranges."
  [n max-coord]
  (vec (for [i (range n)]
         (let [lo (rand-int max-coord)
               hi (+ lo 100 (rand-int 500))]
           [lo hi (keyword (str "v" i))]))))

(defn gen-lookup-points
  "Generate n random lookup points in [0, max-coord)."
  [n max-coord]
  (vec (repeatedly n #(rand-int max-coord))))

(defn gen-coalescing-ranges
  "Generate n adjacent ranges with the same value."
  [n]
  (vec (for [i (range n)]
         [(* i 100) (* (inc i) 100) :same-value])))

(defn gen-remove-ranges
  "Generate n ranges for removal operations."
  [n max-coord]
  (vec (for [i (range n)]
         (let [lo (+ 50 (* i (quot max-coord n)))
               hi (+ lo 100)]
           [lo hi]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-construction-non-overlapping
  "Benchmark inserting non-overlapping ranges."
  [n iterations]
  (println (str "\n=== Construction: " n " non-overlapping ranges ==="))
  (let [ranges (gen-non-overlapping-ranges n)]

    (format-result "Guava TreeRangeMap"
      (bench 100 iterations
        (let [grm (guava-range-map)]
          (doseq [[lo hi v] ranges]
            (guava-put! grm lo hi v))
          grm)))

    (format-result "Our range-map"
      (bench 100 iterations
        (reduce (fn [rm [lo hi v]]
                  (assoc rm [lo hi] v))
                (oc/range-map)
                ranges)))))

(defn bench-construction-overlapping
  "Benchmark inserting overlapping ranges (requires carving)."
  [n iterations]
  (println (str "\n=== Construction: " n " overlapping ranges ==="))
  (let [ranges (gen-overlapping-ranges n 5000)]

    (format-result "Guava TreeRangeMap"
      (bench 100 iterations
        (let [grm (guava-range-map)]
          (doseq [[lo hi v] ranges]
            (guava-put! grm lo hi v))
          grm)))

    (format-result "Our range-map"
      (bench 100 iterations
        (reduce (fn [rm [lo hi v]]
                  (assoc rm [lo hi] v))
                (oc/range-map)
                ranges)))))

(defn bench-point-lookups
  "Benchmark point lookup operations."
  [num-ranges num-lookups iterations]
  (println (str "\n=== Point Lookups: " num-lookups " lookups on " num-ranges "-range map ==="))
  (let [ranges (gen-non-overlapping-ranges num-ranges)
        points (gen-lookup-points num-lookups 10000)
        guava-built (reduce (fn [grm [lo hi v]] (guava-put! grm lo hi v))
                            (guava-range-map)
                            ranges)
        ours-built (reduce (fn [rm [lo hi v]] (assoc rm [lo hi] v))
                           (oc/range-map)
                           ranges)]

    (format-result "Guava TreeRangeMap"
      (bench 100 iterations
        (doseq [x points]
          (guava-get guava-built x))))

    (format-result "Our range-map"
      (bench 100 iterations
        (doseq [x points]
          (ours-built x))))))

(defn bench-get-entry
  "Benchmark get-entry operations."
  [num-ranges num-lookups iterations]
  (println (str "\n=== Get-Entry: " num-lookups " get-entry calls on " num-ranges "-range map ==="))
  (let [ranges (gen-non-overlapping-ranges num-ranges)
        points (gen-lookup-points num-lookups 10000)
        guava-built (reduce (fn [grm [lo hi v]] (guava-put! grm lo hi v))
                            (guava-range-map)
                            ranges)
        ours-built (reduce (fn [rm [lo hi v]] (assoc rm [lo hi] v))
                           (oc/range-map)
                           ranges)]

    (format-result "Guava getEntry"
      (bench 100 iterations
        (doseq [x points]
          (guava-get-entry guava-built x))))

    (format-result "Our get-entry"
      (bench 100 iterations
        (doseq [x points]
          (oc/get-entry ours-built x))))))

(defn bench-coalescing
  "Benchmark coalescing insert operations."
  [n iterations]
  (println (str "\n=== Coalescing: " n " adjacent same-value ranges ==="))
  (let [ranges (gen-coalescing-ranges n)]

    (format-result "Guava putCoalescing"
      (bench 100 iterations
        (let [grm (guava-range-map)]
          (doseq [[lo hi v] ranges]
            (guava-put-coalescing! grm lo hi v))
          grm)))

    (format-result "Our assoc-coalescing"
      (bench 100 iterations
        (reduce (fn [rm [lo hi v]]
                  (oc/assoc-coalescing rm [lo hi] v))
                (oc/range-map)
                ranges)))))

(defn bench-range-removal
  "Benchmark range removal operations."
  [num-ranges num-removes iterations]
  (println (str "\n=== Range Removal: " num-removes " removes from " num-ranges "-range map ==="))
  (let [insert-ranges (gen-non-overlapping-ranges num-ranges)
        remove-ranges (gen-remove-ranges num-removes (* num-ranges 100))]

    (format-result "Guava remove"
      (bench 100 iterations
        (let [grm (reduce (fn [g [lo hi v]] (guava-put! g lo hi v))
                          (guava-range-map)
                          insert-ranges)]
          (doseq [[lo hi] remove-ranges]
            (guava-remove! grm lo hi))
          grm)))

    (format-result "Our range-remove"
      (bench 100 iterations
        (let [rm (reduce (fn [r [lo hi v]] (assoc r [lo hi] v))
                         (oc/range-map)
                         insert-ranges)]
          (reduce (fn [r [lo hi]]
                    (oc/range-remove r [lo hi]))
                  rm
                  remove-ranges))))))

(defn bench-iteration
  "Benchmark iterating over all entries."
  [num-ranges iterations]
  (println (str "\n=== Iteration: traverse all " num-ranges " ranges ==="))
  (let [ranges (gen-non-overlapping-ranges num-ranges)
        guava-built (reduce (fn [grm [lo hi v]] (guava-put! grm lo hi v))
                            (guava-range-map)
                            ranges)
        ours-built (reduce (fn [rm [lo hi v]] (assoc rm [lo hi] v))
                           (oc/range-map)
                           ranges)]

    (format-result "Guava asMapOfRanges iteration"
      (bench 100 iterations
        (let [^java.util.Map m (.asMapOfRanges ^TreeRangeMap guava-built)]
          (reduce (fn [acc ^java.util.Map$Entry e]
                    (+ acc (hash (.getValue e))))
                  0
                  (.entrySet m)))))

    (format-result "Our seq iteration"
      (bench 100 iterations
        (reduce (fn [acc [_ v]]
                  (+ acc (hash v)))
                0
                ours-built)))))

(defn bench-snapshot-modify
  "Benchmark creating snapshots while modifying - persistence advantage.
   Guava must copy the entire map for each snapshot."
  [num-ranges num-snapshots iterations]
  (println (str "\n=== Snapshot + Modify: " num-snapshots " snapshots from " num-ranges "-range map ==="))
  (let [ranges (gen-non-overlapping-ranges num-ranges)
        max-coord (* num-ranges 100)]

    (format-result "Guava (must copy each snapshot)"
      (bench 50 iterations
        (let [grm (reduce (fn [g [lo hi v]] (guava-put! g lo hi v))
                          (guava-range-map)
                          ranges)]
          (loop [i 0 snapshots []]
            (if (< i num-snapshots)
              (let [copy (TreeRangeMap/create)]
                (.putAll copy grm)
                (guava-put! grm (rand-int max-coord) (+ max-coord (rand-int 1000)) :new)
                (recur (inc i) (conj snapshots copy)))
              snapshots)))))

    (format-result "Our (structural sharing)"
      (bench 50 iterations
        (let [rm (reduce (fn [r [lo hi v]] (assoc r [lo hi] v))
                         (oc/range-map)
                         ranges)]
          (loop [i 0 current rm snapshots []]
            (if (< i num-snapshots)
              (let [new-rm (assoc current
                                  [(rand-int max-coord) (+ max-coord (rand-int 1000))]
                                  :new)]
                (recur (inc i) new-rm (conj snapshots current)))
              snapshots)))))))

(defn bench-reduce
  "Benchmark reduce over the collection."
  [num-ranges iterations]
  (println (str "\n=== Reduce: sum values over " num-ranges " ranges ==="))
  (let [ranges (vec (for [i (range num-ranges)]
                      [(* i 100) (+ (* i 100) 50 (rand-int 50)) i]))
        guava-built (reduce (fn [grm [lo hi v]] (guava-put! grm lo hi v))
                            (guava-range-map)
                            ranges)
        ours-built (reduce (fn [rm [lo hi v]] (assoc rm [lo hi] v))
                           (oc/range-map)
                           ranges)]

    (format-result "Guava reduce via entrySet"
      (bench 100 iterations
        (let [^java.util.Map m (.asMapOfRanges ^TreeRangeMap guava-built)]
          (reduce (fn [^long acc ^java.util.Map$Entry e]
                    (+ acc (long (.getValue e))))
                  0
                  (.entrySet m)))))

    (format-result "Our reduce"
      (bench 100 iterations
        (reduce (fn [^long acc [_ v]]
                  (+ acc (long v)))
                0
                ours-built)))))

(defn bench-heavy-versioning
  "Benchmark creating many versions with modifications between each.
   This is where persistence provides massive advantage."
  [num-ranges num-versions iterations]
  (println (str "\n=== Heavy Versioning: " num-versions " versions of " num-ranges "-range map ==="))
  (let [ranges (gen-non-overlapping-ranges num-ranges)
        max-coord (* num-ranges 100)]

    (format-result "Guava (copy for each version)"
      (bench 20 iterations
        (let [grm (reduce (fn [g [lo hi v]] (guava-put! g lo hi v))
                          (guava-range-map)
                          ranges)]
          (loop [i 0 versions (transient [])]
            (if (< i num-versions)
              (let [copy (TreeRangeMap/create)
                    lo (rand-int max-coord)
                    rm-lo (rand-int max-coord)]
                (.putAll copy grm)
                ;; Modify original after copy
                (guava-put! grm lo (+ lo 100 (rand-int 1000)) :new)
                (guava-remove! grm rm-lo (+ rm-lo 10))
                (recur (inc i) (conj! versions copy)))
              (persistent! versions))))))

    (format-result "Our (structural sharing)"
      (bench 20 iterations
        (let [rm (reduce (fn [r [lo hi v]] (assoc r [lo hi] v))
                         (oc/range-map)
                         ranges)]
          (loop [i 0 current rm versions (transient [])]
            (if (< i num-versions)
              (let [lo (rand-int max-coord)
                    rm-lo (rand-int max-coord)
                    new-rm (-> current
                               (assoc [lo (+ lo 100 (rand-int 1000))] :new)
                               (oc/range-remove [rm-lo (+ rm-lo 10)]))]
                (recur (inc i) new-rm (conj! versions current)))
              (persistent! versions))))))))

(defn bench-lookup-after-versions
  "After creating N versions, lookup in all of them.
   Guava copies are independent; ours share structure."
  [num-ranges num-versions num-lookups iterations]
  (println (str "\n=== Lookup Across " num-versions " Versions ==="))
  (let [ranges (gen-non-overlapping-ranges num-ranges)
        max-coord (* num-ranges 100)
        points (gen-lookup-points num-lookups max-coord)

        ;; Build Guava versions
        guava-base (reduce (fn [g [lo hi v]] (guava-put! g lo hi v))
                           (guava-range-map)
                           ranges)
        guava-versions (loop [i 0 grm guava-base versions []]
                         (if (< i num-versions)
                           (let [copy (TreeRangeMap/create)]
                             (.putAll copy grm)
                             (guava-put! grm (rand-int max-coord) (+ max-coord (rand-int 1000)) :new)
                             (recur (inc i) grm (conj versions copy)))
                           versions))

        ;; Build our versions
        ours-base (reduce (fn [r [lo hi v]] (assoc r [lo hi] v))
                          (oc/range-map)
                          ranges)
        ours-versions (loop [i 0 current ours-base versions []]
                        (if (< i num-versions)
                          (let [new-rm (assoc current
                                              [(rand-int max-coord) (+ max-coord (rand-int 1000))]
                                              :new)]
                            (recur (inc i) new-rm (conj versions current)))
                          versions))]

    (format-result (str "Guava lookup across " num-versions " versions")
      (bench 50 iterations
        (doseq [grm guava-versions
                x points]
          (guava-get grm x))))

    (format-result (str "Our lookup across " num-versions " versions")
      (bench 50 iterations
        (doseq [rm ours-versions
                x points]
          (rm x))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Points
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-quick
  "Run a quick benchmark suite."
  []
  (println)
  (println "========================================================================")
  (println "  Range-Map Performance: ordered-collections vs Guava TreeRangeMap")
  (println "  JVM:" (System/getProperty "java.version")
           " Clojure:" (clojure-version))
  (println "========================================================================")
  (println)
  (println "Note: Our range-map is PERSISTENT (immutable), Guava's is MUTABLE.")
  (println "      Persistence enables safe sharing, undo, concurrent reads.")

  (bench-construction-non-overlapping 100 500)
  (bench-construction-overlapping 100 500)
  (bench-point-lookups 100 1000 500)
  (bench-get-entry 100 1000 500)
  (bench-coalescing 50 500)
  (bench-range-removal 100 20 500)

  (println)
  (println "========================================================================")
  (println))

(defn run-all
  "Run the full benchmark suite with more iterations."
  []
  (println)
  (println "========================================================================")
  (println "  Range-Map Performance: ordered-collections vs Guava TreeRangeMap")
  (println "  JVM:" (System/getProperty "java.version")
           " Clojure:" (clojure-version))
  (println (java.util.Date.))
  (println "========================================================================")
  (println)
  (println "Note: Our range-map is PERSISTENT (immutable), Guava's is MUTABLE.")
  (println "      Persistence enables safe sharing, undo, concurrent reads.")

  ;; Small scale (original tests)
  (println)
  (println "--- Small Scale (100 ranges) ---")
  (bench-construction-non-overlapping 100 1000)
  (bench-construction-overlapping 100 1000)
  (bench-point-lookups 100 1000 1000)
  (bench-get-entry 100 1000 1000)
  (bench-coalescing 50 1000)
  (bench-range-removal 100 20 1000)

  ;; Medium scale
  (println)
  (println "--- Medium Scale (1,000 ranges) ---")
  (bench-construction-non-overlapping 1000 500)
  (bench-point-lookups 1000 10000 500)
  (bench-iteration 1000 500)
  (bench-reduce 1000 500)

  ;; Large scale
  (println)
  (println "--- Large Scale (10,000 ranges) ---")
  (bench-construction-non-overlapping 10000 100)
  (bench-point-lookups 10000 50000 100)
  (bench-iteration 10000 200)
  (bench-reduce 10000 200)

  ;; Persistence advantage scenarios
  (println)
  (println "--- Persistence Advantage Scenarios ---")
  (bench-snapshot-modify 1000 50 200)
  (bench-snapshot-modify 5000 100 100)
  (bench-heavy-versioning 1000 200 50)
  (bench-heavy-versioning 5000 500 20)
  (bench-lookup-after-versions 1000 50 100 100)

  (println)
  (println "========================================================================")
  (println))

(defn -main [& args]
  (if (some #{"--quick" "-q"} args)
    (run-quick)
    (run-all)))
