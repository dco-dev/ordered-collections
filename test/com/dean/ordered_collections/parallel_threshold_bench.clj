(ns com.dean.ordered-collections.parallel-threshold-bench
  "Benchmark to find optimal parallel threshold for set operations.

   Usage:
     lein bench-parallel           ; Full benchmark
     lein bench-parallel --quick   ; Quick mode

   Tests sequential vs parallel execution at various cardinalities
   to find the crossover point where parallelism becomes beneficial."
  (:require [com.dean.ordered-collections.bench-utils :refer [has-flag?]]
            [com.dean.ordered-collections.core :as oc]
            [com.dean.ordered-collections.tree.tree :as tree]
            [com.dean.ordered-collections.tree.node :as node]
            [com.dean.ordered-collections.tree.order :as order])
  (:import [com.dean.ordered_collections.tree.root INodeCollection]))


(defn warmup
  "JIT warmup - run operation multiple times."
  [f n]
  (dotimes [_ n] (f)))

(defn bench-op
  "Benchmark an operation, returning mean time in microseconds."
  [f warmup-iters bench-iters]
  (warmup f warmup-iters)
  (let [start (System/nanoTime)]
    (dotimes [_ bench-iters] (f))
    (let [elapsed (- (System/nanoTime) start)]
      (/ elapsed (* bench-iters 1000.0)))))

(defn make-test-sets
  "Create two ordered-sets with given sizes and overlap ratio."
  [size1 size2 overlap-ratio]
  (let [overlap-size (int (* (min size1 size2) overlap-ratio))
        ;; Elements: set1 has [0, size1), set2 has [offset, offset+size2)
        ;; where offset determines overlap
        offset (- size1 overlap-size)
        s1 (oc/ordered-set (shuffle (range size1)))
        s2 (oc/ordered-set (shuffle (range offset (+ offset size2))))]
    [s1 s2]))

(defn get-roots
  "Extract roots from two ordered-sets."
  [s1 s2]
  [(.getRoot ^INodeCollection s1)
   (.getRoot ^INodeCollection s2)])

(defn bench-threshold-for-size
  "Benchmark sequential vs parallel at a given combined size."
  [size & {:keys [warmup-iters bench-iters overlap-ratio]
           :or {warmup-iters 5 bench-iters 20 overlap-ratio 0.5}}]
  (let [half-size (quot size 2)
        [s1 s2] (make-test-sets half-size half-size overlap-ratio)
        [r1 r2] (get-roots s1 s2)

        ;; Benchmark each operation, sequential vs parallel
        results
        (binding [order/*compare* compare]
          {:union-seq     (bench-op #(tree/node-set-union r1 r2) warmup-iters bench-iters)
           :union-par     (bench-op #(tree/node-set-union-parallel r1 r2) warmup-iters bench-iters)
           :intersect-seq (bench-op #(tree/node-set-intersection r1 r2) warmup-iters bench-iters)
           :intersect-par (bench-op #(tree/node-set-intersection-parallel r1 r2) warmup-iters bench-iters)
           :diff-seq      (bench-op #(tree/node-set-difference r1 r2) warmup-iters bench-iters)
           :diff-par      (bench-op #(tree/node-set-difference-parallel r1 r2) warmup-iters bench-iters)})]

    (assoc results
           :size size
           :union-speedup (/ (:union-seq results) (:union-par results))
           :intersect-speedup (/ (:intersect-seq results) (:intersect-par results))
           :diff-speedup (/ (:diff-seq results) (:diff-par results)))))

(defn print-results-table
  "Print results in a formatted table."
  [results]
  (println)
  (println "╔════════════════════════════════════════════════════════════════════════════════════════╗")
  (println "║                    PARALLEL THRESHOLD BENCHMARK RESULTS                                ║")
  (println "╠════════════════════════════════════════════════════════════════════════════════════════╣")
  (println "║  Size   │   Union (μs)    │ Intersect (μs)  │   Diff (μs)     │      Speedups         ║")
  (println "║         │  Seq    Par     │  Seq    Par     │  Seq    Par     │  U      I      D      ║")
  (println "╠════════════════════════════════════════════════════════════════════════════════════════╣")
  (doseq [{:keys [size union-seq union-par intersect-seq intersect-par
                  diff-seq diff-par union-speedup intersect-speedup diff-speedup]} results]
    (printf "║ %6d  │ %6.0f %6.0f   │ %6.0f %6.0f   │ %6.0f %6.0f   │ %5.2fx %5.2fx %5.2fx  ║%n"
            size
            union-seq union-par
            intersect-seq intersect-par
            diff-seq diff-par
            union-speedup intersect-speedup diff-speedup))
  (println "╚════════════════════════════════════════════════════════════════════════════════════════╝")
  (println)
  (println "Speedup > 1.0 means parallel is faster. Crossover is where speedup crosses 1.0.")
  (println (str "Current threshold: " tree/+parallel-threshold+)))

(defn find-crossover
  "Find approximate crossover point where parallel becomes beneficial."
  [results]
  (let [;; Find first size where all speedups > 1.0
        crossover-union (some #(when (> (:union-speedup %) 1.0) (:size %)) results)
        crossover-intersect (some #(when (> (:intersect-speedup %) 1.0) (:size %)) results)
        crossover-diff (some #(when (> (:diff-speedup %) 1.0) (:size %)) results)]
    {:union crossover-union
     :intersect crossover-intersect
     :diff crossover-diff
     :recommended (some identity [crossover-union crossover-intersect crossover-diff])}))

(defn run-benchmark
  "Run the full threshold benchmark."
  [& {:keys [sizes warmup-iters bench-iters]
      :or {sizes [512 1024 2048 4096 8192 16384 32768 65536 131072]
           warmup-iters 5
           bench-iters 20}}]
  (println "Running parallel threshold benchmark...")
  (println "Testing sizes:" sizes)
  (println "Warmup iterations:" warmup-iters)
  (println "Benchmark iterations:" bench-iters)
  (println)

  (let [results (vec (for [size sizes]
                       (do
                         (print (str "  Testing size " size "... "))
                         (flush)
                         (let [r (bench-threshold-for-size size
                                   :warmup-iters warmup-iters
                                   :bench-iters bench-iters)]
                           (println "done")
                           r))))
        crossover (find-crossover results)]

    (print-results-table results)
    (println)
    (println "Crossover analysis:")
    (println "  Union crossover:     " (or (:union crossover) "not found in range"))
    (println "  Intersect crossover: " (or (:intersect crossover) "not found in range"))
    (println "  Diff crossover:      " (or (:diff crossover) "not found in range"))
    (println "  Recommended threshold:" (or (:recommended crossover) "increase test range"))

    {:results results :crossover crossover}))

(defn quick-bench
  "Quick benchmark with fewer iterations for fast feedback."
  []
  (run-benchmark :sizes [1024 2048 4096 8192 16384 32768]
                 :warmup-iters 3
                 :bench-iters 10))

(defn -main
  "Entry point for lein bench-parallel."
  [& args]
  (if (has-flag? args "--quick" "-q")
    (quick-bench)
    (run-benchmark))
  (shutdown-agents))

(comment
  ;; Quick test
  (quick-bench)

  ;; Full benchmark
  (run-benchmark)

  ;; Fine-grained around expected crossover
  (run-benchmark :sizes [4096 5000 6000 7000 8000 9000 10000 12000 16384])
  )
