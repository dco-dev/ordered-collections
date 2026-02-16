(ns com.dean.ordered-collections.comparative-set-bench
  "Comparative benchmark: ordered-collections vs data.avl for set operations.

   Tests at various sizes to verify our threshold choice maximizes performance."
  (:require [clojure.data.avl :as avl]
            [com.dean.ordered-collections.core :as oc]
            [com.dean.ordered-collections.tree.tree :as tree]))

(set! *warn-on-reflection* true)

(defn bench-op
  "Benchmark an operation, returning mean time in microseconds."
  [f warmup-iters bench-iters]
  (dotimes [_ warmup-iters] (f))
  (let [start (System/nanoTime)]
    (dotimes [_ bench-iters] (f))
    (let [elapsed (- (System/nanoTime) start)]
      (/ elapsed (* bench-iters 1000.0)))))

(defn make-test-data
  "Create test sets for both libraries with 50% overlap."
  [size]
  (let [half (quot size 2)
        data1 (shuffle (range half))
        data2 (shuffle (range (quot half 2) (+ half (quot half 2))))
        avl1 (into (avl/sorted-set) data1)
        avl2 (into (avl/sorted-set) data2)
        oc1 (oc/ordered-set data1)
        oc2 (oc/ordered-set data2)]
    {:avl1 avl1 :avl2 avl2 :oc1 oc1 :oc2 oc2}))

(defn bench-size
  "Benchmark all set operations at a given size."
  [size & {:keys [warmup-iters bench-iters] :or {warmup-iters 5 bench-iters 15}}]
  (let [{:keys [avl1 avl2 oc1 oc2]} (make-test-data size)]
    {:size size
     ;; Union
     :avl-union (bench-op #(clojure.set/union avl1 avl2) warmup-iters bench-iters)
     :oc-union (bench-op #(oc/union oc1 oc2) warmup-iters bench-iters)
     ;; Intersection
     :avl-intersect (bench-op #(clojure.set/intersection avl1 avl2) warmup-iters bench-iters)
     :oc-intersect (bench-op #(oc/intersection oc1 oc2) warmup-iters bench-iters)
     ;; Difference
     :avl-diff (bench-op #(clojure.set/difference avl1 avl2) warmup-iters bench-iters)
     :oc-diff (bench-op #(oc/difference oc1 oc2) warmup-iters bench-iters)}))

(defn add-speedups [result]
  (assoc result
         :union-speedup (/ (:avl-union result) (:oc-union result))
         :intersect-speedup (/ (:avl-intersect result) (:oc-intersect result))
         :diff-speedup (/ (:avl-diff result) (:oc-diff result))))

(defn print-results [results]
  (println)
  (println "╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗")
  (println "║                     ORDERED-COLLECTIONS vs DATA.AVL SET OPERATIONS                                ║")
  (println "╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣")
  (println "║   Size   │     Union (μs)      │   Intersect (μs)    │    Diff (μs)        │  Speedup vs AVL     ║")
  (println "║          │   AVL      OC       │   AVL      OC       │   AVL      OC       │   U     I     D     ║")
  (println "╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣")
  (doseq [{:keys [size avl-union oc-union avl-intersect oc-intersect
                  avl-diff oc-diff union-speedup intersect-speedup diff-speedup]} results]
    (printf "║ %7d  │ %7.0f  %7.0f   │ %7.0f  %7.0f   │ %7.0f  %7.0f   │ %5.2fx %5.2fx %5.2fx ║%n"
            size
            avl-union oc-union
            avl-intersect oc-intersect
            avl-diff oc-diff
            union-speedup intersect-speedup diff-speedup))
  (println "╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝")
  (println)
  (println "Speedup > 1.0 means ordered-collections is faster than data.avl")
  (println (str "Current parallel threshold: " tree/+parallel-threshold+)))

(defn run-benchmark
  "Run comparative benchmark at various sizes."
  [& {:keys [sizes warmup-iters bench-iters]
      :or {sizes [1000 5000 10000 25000 50000 100000 250000 500000]
           warmup-iters 5
           bench-iters 15}}]
  (println "Comparative benchmark: ordered-collections vs data.avl")
  (println "Parallel threshold:" tree/+parallel-threshold+)
  (println "Testing sizes:" sizes)
  (println)

  (let [results (vec (for [size sizes]
                       (do
                         (print (str "  Testing size " size "... "))
                         (flush)
                         (let [r (-> (bench-size size :warmup-iters warmup-iters :bench-iters bench-iters)
                                     add-speedups)]
                           (println "done")
                           r))))]
    (print-results results)

    ;; Summary
    (let [avg-union (/ (reduce + (map :union-speedup results)) (count results))
          avg-intersect (/ (reduce + (map :intersect-speedup results)) (count results))
          avg-diff (/ (reduce + (map :diff-speedup results)) (count results))
          min-union (apply min (map :union-speedup results))
          min-intersect (apply min (map :intersect-speedup results))
          min-diff (apply min (map :diff-speedup results))]
      (println)
      (println "Summary:")
      (printf "  Union:        avg %.2fx, min %.2fx%n" avg-union min-union)
      (printf "  Intersection: avg %.2fx, min %.2fx%n" avg-intersect min-intersect)
      (printf "  Difference:   avg %.2fx, min %.2fx%n" avg-diff min-diff)
      (println)
      (when (or (< min-union 1.0) (< min-intersect 1.0) (< min-diff 1.0))
        (println "WARNING: Some operations are slower than data.avl!")))

    results))

(defn quick-bench []
  (run-benchmark :sizes [10000 50000 100000 500000]
                 :warmup-iters 3
                 :bench-iters 10))

(comment
  (quick-bench)
  (run-benchmark)
  )
