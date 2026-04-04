(ns ordered-collections.parallel-threshold-bench
  "Benchmark production-style threshold dispatch for shared parallel operations.

   Usage:
     lein bench-parallel           ; Full benchmark
     lein bench-parallel --quick   ; Quick mode

   Measures:
     1. Forced sequential execution
     2. Production-style dispatch using candidate thresholds
     3. Ordered-map merge under the same shared threshold

   This is intentionally different from directly benchmarking the
   `*-parallel` functions in isolation: production never pays pool-entry
   overhead below the configured threshold."
  (:require [ordered-collections.bench-utils :refer [has-flag?]]
            [ordered-collections.core :as oc]
            [ordered-collections.parallel :as parallel]
            [ordered-collections.tree.tree :as tree]
            [ordered-collections.tree.node :as node]
            [ordered-collections.tree.order :as order]
            [ordered-collections.bench-utils :as bu])
  (:import [ordered_collections.tree.root INodeCollection IOrderedCollection]))


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

(defn stats
  "Compute simple descriptive stats for repeated benchmark samples."
  [samples]
  (let [sorted (sort samples)
        n      (count sorted)
        median (nth sorted (quot n 2))
        mean   (/ (reduce + sorted) n)
        low    (first sorted)
        high   (last sorted)]
    {:samples samples
     :median  median
     :mean    mean
     :low     low
     :high    high}))

(defn bench-op-samples
  "Benchmark f multiple times and return summary stats.

   The median is used for threshold comparisons to reduce noise from
   occasional scheduling hiccups."
  [f warmup-iters bench-iters sample-runs]
  (stats (vec (repeatedly sample-runs #(bench-op f warmup-iters bench-iters)))))

(defn- make-keys
  [n key-profile]
  (case key-profile
    :string (bu/generate-string-keys n)
    :long   (vec (range n))
    (throw (ex-info "unsupported key profile" {:key-profile key-profile}))))

(defn make-test-sets
  "Create two ordered-sets with given sizes and overlap ratio."
  [size1 size2 overlap-ratio key-profile]
  (let [overlap-size (int (* (min size1 size2) overlap-ratio))
        ;; Elements: set1 has [0, size1), set2 has [offset, offset+size2)
        ;; where offset determines overlap
        offset (- size1 overlap-size)
        keys1  (make-keys size1 key-profile)
        keys2  (subvec (make-keys (+ offset size2) key-profile) offset (+ offset size2))
        [s1 s2]
        (case key-profile
          :string [(oc/string-ordered-set (shuffle keys1))
                   (oc/string-ordered-set (shuffle keys2))]
          :long   [(oc/ordered-set (shuffle keys1))
                   (oc/ordered-set (shuffle keys2))]
          (throw (ex-info "unsupported key profile" {:key-profile key-profile})))]
    [s1 s2]))

(defn make-test-maps
  "Create two ordered-maps with given sizes and overlap ratio."
  [size1 size2 overlap-ratio key-profile]
  (let [overlap-size (int (* (min size1 size2) overlap-ratio))
        offset       (- size1 overlap-size)
        keys1        (make-keys size1 key-profile)
        keys2        (subvec (make-keys (+ offset size2) key-profile) offset (+ offset size2))
        pairs1       (map-indexed (fn [idx k] [k idx]) (shuffle keys1))
        pairs2       (map-indexed (fn [idx k] [k (+ idx size1)]) (shuffle keys2))]
    (case key-profile
      :string [(oc/string-ordered-map pairs1)
               (oc/string-ordered-map pairs2)]
      :long   [(oc/ordered-map pairs1)
               (oc/ordered-map pairs2)]
      (throw (ex-info "unsupported key profile" {:key-profile key-profile})))))

(defn get-roots
  "Extract roots from two compatible ordered collections."
  [c1 c2]
  [(.getRoot ^INodeCollection c1)
   (.getRoot ^INodeCollection c2)])

(defn- production-dispatch
  "Mimic the production top-level dispatch decision at a given threshold."
  [threshold seq-f par-f total]
  (if (>= total threshold)
    (par-f)
    (seq-f)))

(def ^:private default-threshold-candidates
  [4096 8192 16384 32768 65536 131072 210000 262144 524288])

(def ^:private default-recursive-threshold-candidates
  [4096 16384 32768 65536])

(defn- current-thresholds
  []
  {:union tree/+parallel-union-root-threshold+
   :intersect tree/+parallel-intersection-root-threshold+
   :diff tree/+parallel-difference-root-threshold+
   :merge tree/+parallel-merge-root-threshold+})

(defn- current-recursive-thresholds
  []
  {:union tree/+parallel-union-recursive-threshold+
   :intersect tree/+parallel-intersection-recursive-threshold+
   :diff tree/+parallel-difference-recursive-threshold+
   :merge tree/+parallel-merge-recursive-threshold+})

(def ^:private union-parallel-kernel
  (ns-resolve 'ordered-collections.tree.tree 'node-set-union-parallel*))

(def ^:private intersect-parallel-kernel
  (ns-resolve 'ordered-collections.tree.tree 'node-set-intersection-parallel*))

(def ^:private diff-parallel-kernel
  (ns-resolve 'ordered-collections.tree.tree 'node-set-difference-parallel*))

(def ^:private merge-parallel-kernel
  (ns-resolve 'ordered-collections.tree.tree 'node-map-merge-parallel*))

(defn bench-threshold-for-size
  "Benchmark production-style dispatch at a given combined size."
  [size & {:keys [warmup-iters bench-iters sample-runs overlap-ratio thresholds key-profile]
           :or {warmup-iters 5
                bench-iters 20
                sample-runs 9
                overlap-ratio 0.5
                thresholds default-threshold-candidates
                key-profile :long}}]
  (let [half-size (quot size 2)
        [s1 s2] (make-test-sets half-size half-size overlap-ratio key-profile)
        [m1 m2] (make-test-maps half-size half-size overlap-ratio key-profile)
        [r1 r2] (get-roots s1 s2)
        [mr1 mr2] (get-roots m1 m2)
        set-cmp (.getCmp ^IOrderedCollection s1)
        map-cmp (.getCmp ^IOrderedCollection m1)
        total   (+ (tree/node-size r1) (tree/node-size r2))
        union-seq-f     #(tree/node-set-union r1 r2)
        union-par-f     #(tree/node-set-union-parallel r1 r2)
        intersect-seq-f #(tree/node-set-intersection r1 r2)
        intersect-par-f #(tree/node-set-intersection-parallel r1 r2)
        diff-seq-f      #(tree/node-set-difference r1 r2)
        diff-par-f      #(tree/node-set-difference-parallel r1 r2)
        merge-fn        (fn [_ left _] left)
        merge-seq-f     #(tree/node-map-merge mr1 mr2 merge-fn)
        merge-par-f     #(tree/node-map-merge-parallel mr1 mr2 merge-fn)

        ;; Benchmark each operation using production-style threshold dispatch.
        set-results
        (binding [order/*compare* set-cmp]
          {:union-seq     (bench-op-samples union-seq-f warmup-iters bench-iters sample-runs)
           :intersect-seq (bench-op-samples intersect-seq-f warmup-iters bench-iters sample-runs)
           :diff-seq      (bench-op-samples diff-seq-f warmup-iters bench-iters sample-runs)
           :thresholds
           (into {}
                 (for [threshold thresholds]
                   [threshold
                    {:union
                     (bench-op-samples
                       #(production-dispatch threshold union-seq-f union-par-f total)
                       warmup-iters bench-iters sample-runs)
                     :intersect
                     (bench-op-samples
                       #(production-dispatch threshold intersect-seq-f intersect-par-f total)
                       warmup-iters bench-iters sample-runs)
                     :diff
                     (bench-op-samples
                       #(production-dispatch threshold diff-seq-f diff-par-f total)
                       warmup-iters bench-iters sample-runs)}]))})
        merge-results
        (binding [order/*compare* map-cmp]
          {:merge-seq
           (bench-op-samples merge-seq-f warmup-iters bench-iters sample-runs)
           :merge-thresholds
           (into {}
                 (for [threshold thresholds]
                   [threshold
                    (bench-op-samples
                      #(production-dispatch threshold merge-seq-f merge-par-f total)
                      warmup-iters bench-iters sample-runs)]))})
        results
        (assoc set-results
               :merge-seq (:merge-seq merge-results)
               :thresholds
               (reduce (fn [acc threshold]
                         (assoc acc threshold
                                (assoc (get acc threshold)
                                       :merge (get-in merge-results [:merge-thresholds threshold]))))
                       (:thresholds set-results)
                       thresholds))]

    (assoc results :size size :total total :key-profile key-profile)))

(defn bench-recursive-threshold-for-size
  "Benchmark explicit recursive-threshold choices once an operation has already
   entered the parallel path at the root."
  [size & {:keys [warmup-iters bench-iters sample-runs overlap-ratio recursive-thresholds key-profile]
           :or {warmup-iters 5
                bench-iters 20
                sample-runs 9
                overlap-ratio 0.5
                recursive-thresholds default-recursive-threshold-candidates
                key-profile :long}}]
  (let [recursive-thresholds (sort (distinct (concat recursive-thresholds
                                                      (vals (current-recursive-thresholds)))))
        half-size (quot size 2)
        [s1 s2]   (make-test-sets half-size half-size overlap-ratio key-profile)
        [m1 m2]   (make-test-maps half-size half-size overlap-ratio key-profile)
        [r1 r2]   (get-roots s1 s2)
        [mr1 mr2] (get-roots m1 m2)
        set-cmp   (.getCmp ^IOrderedCollection s1)
        map-cmp   (.getCmp ^IOrderedCollection m1)
        join      tree/*t-join*
        merge-fn  (fn [_ left _] left)

        union-seq-f     #(tree/node-set-union r1 r2)
        intersect-seq-f #(tree/node-set-intersection r1 r2)
        diff-seq-f      #(tree/node-set-difference r1 r2)
        merge-seq-f     #(tree/node-map-merge mr1 mr2 merge-fn)

        union-par-f     (fn [recursive-threshold]
                          #(parallel/invoke-root
                             (fn []
                               (union-parallel-kernel r1 r2 set-cmp join recursive-threshold))))
        intersect-par-f (fn [recursive-threshold]
                          #(parallel/invoke-root
                             (fn []
                               (intersect-parallel-kernel r1 r2 set-cmp join recursive-threshold))))
        diff-par-f      (fn [recursive-threshold]
                          #(parallel/invoke-root
                             (fn []
                               (diff-parallel-kernel r1 r2 set-cmp join recursive-threshold))))
        merge-par-f     (fn [recursive-threshold]
                          #(parallel/invoke-root
                             (fn []
                               (merge-parallel-kernel mr1 mr2 merge-fn map-cmp join recursive-threshold))))

        set-results
        {:union-seq     (binding [order/*compare* set-cmp]
                         (bench-op-samples union-seq-f warmup-iters bench-iters sample-runs))
         :intersect-seq (binding [order/*compare* set-cmp]
                         (bench-op-samples intersect-seq-f warmup-iters bench-iters sample-runs))
         :diff-seq      (binding [order/*compare* set-cmp]
                         (bench-op-samples diff-seq-f warmup-iters bench-iters sample-runs))
         :recursive-thresholds
         (into {}
               (for [threshold recursive-thresholds]
                 [threshold
                   {:union
                   (binding [order/*compare* set-cmp]
                     (bench-op-samples (union-par-f threshold) warmup-iters bench-iters sample-runs))
                   :intersect
                   (binding [order/*compare* set-cmp]
                     (bench-op-samples (intersect-par-f threshold) warmup-iters bench-iters sample-runs))
                   :diff
                   (binding [order/*compare* set-cmp]
                     (bench-op-samples (diff-par-f threshold) warmup-iters bench-iters sample-runs))}]))}

        merge-results
        {:merge-seq (binding [order/*compare* map-cmp]
                     (bench-op-samples merge-seq-f warmup-iters bench-iters sample-runs))
         :merge-recursive-thresholds
         (into {}
               (for [threshold recursive-thresholds]
                 [threshold
                  (binding [order/*compare* map-cmp]
                    (bench-op-samples (merge-par-f threshold) warmup-iters bench-iters sample-runs))]))}

        results
        (assoc set-results
               :merge-seq (:merge-seq merge-results)
               :recursive-thresholds
               (reduce (fn [acc threshold]
                         (assoc acc threshold
                                (assoc (get acc threshold)
                                       :merge (get-in merge-results [:merge-recursive-thresholds threshold]))))
                       (:recursive-thresholds set-results)
                       recursive-thresholds))]
    (assoc results :size size :key-profile key-profile)))

(defn- best-threshold
  [threshold-results op]
  (apply min-key #(get-in threshold-results [% op :median]) (keys threshold-results)))

(defn- speedup
  [seq-stats candidate-stats]
  (/ (:median seq-stats) (:median candidate-stats)))

(defn print-results-table
  "Print results in a formatted table."
  [key-profile results]
  (println)
  (println (str "Key profile: " (name key-profile)))
  (println "╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗")
  (println "║                          PRODUCTION THRESHOLD BENCHMARK RESULTS                                              ║")
  (println "╠══════════════════════════════════════════════════════════════════════════════════════════════════════════════╣")
  (println "║  Size   │  Best Union         │ Best Intersect      │ Best Diff           │ Best Merge          │ Current     ║")
  (println "║         │ thres   med   spd   │ thres   med   spd   │ thres   med   spd   │ thres   med   spd   │ U I D M     ║")
  (println "╠══════════════════════════════════════════════════════════════════════════════════════════════════════════════╣")
  (doseq [{:keys [size union-seq intersect-seq diff-seq merge-seq thresholds]} results]
    (let [union-best-th      (best-threshold thresholds :union)
          intersect-best-th  (best-threshold thresholds :intersect)
          diff-best-th       (best-threshold thresholds :diff)
          merge-best-th      (best-threshold thresholds :merge)
          current-thresholds (current-thresholds)]
      (printf "║ %6d  │ %6d %6.0f %5.2fx │ %6d %6.0f %5.2fx │ %6d %6.0f %5.2fx │ %6d %6.0f %5.2fx │ %4.0f %4.0f %4.0f %4.0f ║%n"
              size
              union-best-th
              (get-in thresholds [union-best-th :union :median])
              (speedup union-seq (get-in thresholds [union-best-th :union]))
              intersect-best-th
              (get-in thresholds [intersect-best-th :intersect :median])
              (speedup intersect-seq (get-in thresholds [intersect-best-th :intersect]))
              diff-best-th
              (get-in thresholds [diff-best-th :diff :median])
              (speedup diff-seq (get-in thresholds [diff-best-th :diff]))
              merge-best-th
              (get-in thresholds [merge-best-th :merge :median])
              (speedup merge-seq (get-in thresholds [merge-best-th :merge]))
              (get-in thresholds [(:union current-thresholds) :union :median])
              (get-in thresholds [(:intersect current-thresholds) :intersect :median])
              (get-in thresholds [(:diff current-thresholds) :diff :median])
              (get-in thresholds [(:merge current-thresholds) :merge :median]))))
  (println "╚══════════════════════════════════════════════════════════════════════════════════════════════════════════════╝")
  (println)
  (println "Speedup > 1.0 means the candidate threshold beat forced sequential on median time.")
  (println (str "Current root thresholds:"
                " union=" tree/+parallel-union-root-threshold+
                " intersect=" tree/+parallel-intersection-root-threshold+
                " diff=" tree/+parallel-difference-root-threshold+
                " merge=" tree/+parallel-merge-root-threshold+))
  (println (str "Current recursive thresholds:"
                " union=" tree/+parallel-union-recursive-threshold+
                " intersect=" tree/+parallel-intersection-recursive-threshold+
                " diff=" tree/+parallel-difference-recursive-threshold+
                " merge=" tree/+parallel-merge-recursive-threshold+)))

(defn find-crossover
  "Find best candidate threshold per operation at each tested size."
  [results]
  (let [current-thresholds (current-thresholds)]
    (mapv (fn [{:keys [size union-seq intersect-seq diff-seq merge-seq thresholds]}]
            {:size size
             :union-threshold (best-threshold thresholds :union)
             :union-speedup (speedup union-seq (get-in thresholds [(best-threshold thresholds :union) :union]))
             :intersect-threshold (best-threshold thresholds :intersect)
             :intersect-speedup (speedup intersect-seq (get-in thresholds [(best-threshold thresholds :intersect) :intersect]))
             :diff-threshold (best-threshold thresholds :diff)
             :diff-speedup (speedup diff-seq (get-in thresholds [(best-threshold thresholds :diff) :diff]))
             :merge-threshold (best-threshold thresholds :merge)
             :merge-speedup (speedup merge-seq (get-in thresholds [(best-threshold thresholds :merge) :merge]))
             :current-thresholds current-thresholds
             :current-union-speedup (speedup union-seq (get-in thresholds [(:union current-thresholds) :union]))
             :current-intersect-speedup (speedup intersect-seq (get-in thresholds [(:intersect current-thresholds) :intersect]))
             :current-diff-speedup (speedup diff-seq (get-in thresholds [(:diff current-thresholds) :diff]))
             :current-merge-speedup (speedup merge-seq (get-in thresholds [(:merge current-thresholds) :merge]))})
          results)))

(defn run-benchmark
  "Run the full threshold benchmark."
  [& {:keys [sizes warmup-iters bench-iters sample-runs thresholds key-profiles]
      :or {sizes [4096 16384 65536 131072 262144 524288 1048576]
           warmup-iters 5
           bench-iters 20
           sample-runs 9
           thresholds default-threshold-candidates
           key-profiles [:long :string]}}]
  (println "Running parallel threshold benchmark...")
  (println "Testing sizes:" sizes)
  (println "Warmup iterations:" warmup-iters)
  (println "Benchmark iterations:" bench-iters)
  (println "Sample runs:" sample-runs)
  (println "Threshold candidates:" thresholds)
  (println "Key profiles:" key-profiles)
  (println)

  (let [results-by-profile
        (into {}
              (for [key-profile key-profiles]
                [key-profile
                 (vec (for [size sizes]
                        (do
                          (print (str "  Testing " (name key-profile) " size " size "... "))
                          (flush)
                          (let [r (bench-threshold-for-size size
                                    :warmup-iters warmup-iters
                                    :bench-iters bench-iters
                                    :sample-runs sample-runs
                                    :thresholds thresholds
                                    :key-profile key-profile)]
                            (println "done")
                            r))))]))
        summaries-by-profile
        (into {}
              (map (fn [[key-profile results]]
                     [key-profile (find-crossover results)])
                   results-by-profile))]

    (doseq [key-profile key-profiles]
      (let [results   (get results-by-profile key-profile)
            summaries (get summaries-by-profile key-profile)]
        (print-results-table key-profile results)
        (println)
        (println "Per-size threshold analysis:")
        (doseq [{:keys [size union-threshold union-speedup
                        intersect-threshold intersect-speedup
                        diff-threshold diff-speedup
                        merge-threshold merge-speedup
                        current-thresholds current-union-speedup
                        current-intersect-speedup current-diff-speedup
                        current-merge-speedup]} summaries]
          (printf "  size=%d  union=%d (%4.2fx)  intersect=%d (%4.2fx)  diff=%d (%4.2fx)  merge=%d (%4.2fx)  current=[%d %d %d %d] [%4.2fx %4.2fx %4.2fx %4.2fx]%n"
                  size
                  union-threshold union-speedup
                  intersect-threshold intersect-speedup
                  diff-threshold diff-speedup
                  merge-threshold merge-speedup
                  (:union current-thresholds)
                  (:intersect current-thresholds)
                  (:diff current-thresholds)
                  (:merge current-thresholds)
                  current-union-speedup current-intersect-speedup current-diff-speedup current-merge-speedup))
        (println)))

    {:results-by-profile results-by-profile
     :summaries-by-profile summaries-by-profile}))

(defn print-recursive-results-table
  [key-profile results]
  (println)
  (println (str "Key profile: " (name key-profile)))
  (println "╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗")
  (println "║                         RECURSIVE THRESHOLD BENCHMARK RESULTS                                                ║")
  (println "╠══════════════════════════════════════════════════════════════════════════════════════════════════════════════╣")
  (println "║  Size   │  Best Union         │ Best Intersect      │ Best Diff           │ Best Merge          │ Current     ║")
  (println "║         │ recur   med   spd   │ recur   med   spd   │ recur   med   spd   │ recur   med   spd   │ U I D M     ║")
  (println "╠══════════════════════════════════════════════════════════════════════════════════════════════════════════════╣")
  (doseq [{:keys [size union-seq intersect-seq diff-seq merge-seq recursive-thresholds]} results]
    (let [union-best-th      (best-threshold recursive-thresholds :union)
          intersect-best-th  (best-threshold recursive-thresholds :intersect)
          diff-best-th       (best-threshold recursive-thresholds :diff)
          merge-best-th      (best-threshold recursive-thresholds :merge)
          current-thresholds (current-recursive-thresholds)]
      (printf "║ %6d  │ %6d %6.0f %5.2fx │ %6d %6.0f %5.2fx │ %6d %6.0f %5.2fx │ %6d %6.0f %5.2fx │ %4.0f %4.0f %4.0f %4.0f ║%n"
              size
              union-best-th
              (get-in recursive-thresholds [union-best-th :union :median])
              (speedup union-seq (get-in recursive-thresholds [union-best-th :union]))
              intersect-best-th
              (get-in recursive-thresholds [intersect-best-th :intersect :median])
              (speedup intersect-seq (get-in recursive-thresholds [intersect-best-th :intersect]))
              diff-best-th
              (get-in recursive-thresholds [diff-best-th :diff :median])
              (speedup diff-seq (get-in recursive-thresholds [diff-best-th :diff]))
              merge-best-th
              (get-in recursive-thresholds [merge-best-th :merge :median])
              (speedup merge-seq (get-in recursive-thresholds [merge-best-th :merge]))
              (get-in recursive-thresholds [(:union current-thresholds) :union :median])
              (get-in recursive-thresholds [(:intersect current-thresholds) :intersect :median])
              (get-in recursive-thresholds [(:diff current-thresholds) :diff :median])
              (get-in recursive-thresholds [(:merge current-thresholds) :merge :median]))))
  (println "╚══════════════════════════════════════════════════════════════════════════════════════════════════════════════╝")
  (println)
  (println "Current root thresholds stay fixed during this benchmark.")
  (println (str "Current recursive thresholds:"
                " union=" tree/+parallel-union-recursive-threshold+
                " intersect=" tree/+parallel-intersection-recursive-threshold+
                " diff=" tree/+parallel-difference-recursive-threshold+
                " merge=" tree/+parallel-merge-recursive-threshold+)))

(defn find-recursive-crossover
  [results]
  (let [current-thresholds (current-recursive-thresholds)]
    (mapv (fn [{:keys [size union-seq intersect-seq diff-seq merge-seq recursive-thresholds]}]
            {:size size
             :union-threshold (best-threshold recursive-thresholds :union)
             :union-speedup (speedup union-seq (get-in recursive-thresholds [(best-threshold recursive-thresholds :union) :union]))
             :intersect-threshold (best-threshold recursive-thresholds :intersect)
             :intersect-speedup (speedup intersect-seq (get-in recursive-thresholds [(best-threshold recursive-thresholds :intersect) :intersect]))
             :diff-threshold (best-threshold recursive-thresholds :diff)
             :diff-speedup (speedup diff-seq (get-in recursive-thresholds [(best-threshold recursive-thresholds :diff) :diff]))
             :merge-threshold (best-threshold recursive-thresholds :merge)
             :merge-speedup (speedup merge-seq (get-in recursive-thresholds [(best-threshold recursive-thresholds :merge) :merge]))
             :current-thresholds current-thresholds
             :current-union-speedup (speedup union-seq (get-in recursive-thresholds [(:union current-thresholds) :union]))
             :current-intersect-speedup (speedup intersect-seq (get-in recursive-thresholds [(:intersect current-thresholds) :intersect]))
             :current-diff-speedup (speedup diff-seq (get-in recursive-thresholds [(:diff current-thresholds) :diff]))
             :current-merge-speedup (speedup merge-seq (get-in recursive-thresholds [(:merge current-thresholds) :merge]))})
          results)))

(defn run-recursive-benchmark
  [& {:keys [sizes warmup-iters bench-iters sample-runs recursive-thresholds key-profiles]
      :or {sizes [4096 16384 65536 131072 262144 524288 1048576]
           warmup-iters 5
           bench-iters 20
           sample-runs 9
           recursive-thresholds default-recursive-threshold-candidates
           key-profiles [:long :string]}}]
  (println "Running recursive threshold benchmark...")
  (println "Testing sizes:" sizes)
  (println "Warmup iterations:" warmup-iters)
  (println "Benchmark iterations:" bench-iters)
  (println "Sample runs:" sample-runs)
  (println "Recursive threshold candidates:" recursive-thresholds)
  (println "Key profiles:" key-profiles)
  (println)

  (let [results-by-profile
        (into {}
              (for [key-profile key-profiles]
                [key-profile
                 (vec (for [size sizes]
                        (do
                          (print (str "  Testing " (name key-profile) " size " size "... "))
                          (flush)
                          (let [r (bench-recursive-threshold-for-size size
                                    :warmup-iters warmup-iters
                                    :bench-iters bench-iters
                                    :sample-runs sample-runs
                                    :recursive-thresholds recursive-thresholds
                                    :key-profile key-profile)]
                            (println "done")
                            r))))]))
        summaries-by-profile
        (into {}
              (map (fn [[key-profile results]]
                     [key-profile (find-recursive-crossover results)])
                   results-by-profile))]
    (doseq [key-profile key-profiles]
      (let [results   (get results-by-profile key-profile)
            summaries (get summaries-by-profile key-profile)]
        (print-recursive-results-table key-profile results)
        (println)
        (println "Per-size recursive-threshold analysis:")
        (doseq [{:keys [size union-threshold union-speedup
                        intersect-threshold intersect-speedup
                        diff-threshold diff-speedup
                        merge-threshold merge-speedup
                        current-thresholds current-union-speedup
                        current-intersect-speedup current-diff-speedup
                        current-merge-speedup]} summaries]
          (printf "  size=%d  union=%d (%4.2fx)  intersect=%d (%4.2fx)  diff=%d (%4.2fx)  merge=%d (%4.2fx)  current=[%d %d %d %d] [%4.2fx %4.2fx %4.2fx %4.2fx]%n"
                  size
                  union-threshold union-speedup
                  intersect-threshold intersect-speedup
                  diff-threshold diff-speedup
                  merge-threshold merge-speedup
                  (:union current-thresholds)
                  (:intersect current-thresholds)
                  (:diff current-thresholds)
                  (:merge current-thresholds)
                  current-union-speedup current-intersect-speedup current-diff-speedup current-merge-speedup))
        (println)))
    {:results-by-profile results-by-profile
     :summaries-by-profile summaries-by-profile}))

(defn quick-bench
  "Quick benchmark with fewer iterations for fast feedback."
  []
  (run-benchmark :sizes [4096 16384 65536 131072 262144]
                 :warmup-iters 3
                 :bench-iters 10
                 :sample-runs 5))

(defn quick-recursive-bench
  []
  (run-recursive-benchmark :sizes [65536 131072 262144 524288 1048576]
                           :warmup-iters 3
                           :bench-iters 10
                           :sample-runs 5))

(defn -main
  "Entry point for lein bench-parallel."
  [& args]
  (cond
    (has-flag? args "--recursive")
    (if (has-flag? args "--quick" "-q")
      (quick-recursive-bench)
      (run-recursive-benchmark))

    (has-flag? args "--quick" "-q")
    (quick-bench)
    :else
    (run-benchmark))
  (shutdown-agents))

(comment
  ;; Quick test
  (quick-bench)

  ;; Full benchmark
  (run-benchmark)

  ;; Fine-grained around and above current threshold
  (run-benchmark :sizes [131072 196608 262144 393216 524288 786432 1048576])

  ;; Recursive-threshold sweep with current root thresholds held fixed
  (run-recursive-benchmark)
  )
