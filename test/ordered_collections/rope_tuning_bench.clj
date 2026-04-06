(ns ordered-collections.rope-tuning-bench
  "Systematic benchmark for rope chunk-size tuning.

   Measures every rope operation across candidate chunk sizes to find the
   optimal target/min pair. Each candidate rebuilds the test data at that
   chunk size, then benchmarks all key operations against PersistentVector.

   Usage:
     lein bench-rope-tuning           ; Default sizes
     lein bench-rope-tuning --quick   ; Fast feedback

   This is the rope analogue of bench-parallel: it finds the chunk size
   that gives the best overall profile rather than guessing."
  (:require [ordered-collections.bench-utils :as bu :refer [has-flag?]]
            [ordered-collections.tree.rope :as ropetree]
            [ordered-collections.tree.node :as node :refer [leaf leaf?]]
            [ordered-collections.tree.tree :as tree]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Infrastructure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-op
  "Benchmark f, returning mean time in microseconds."
  [f warmup-iters bench-iters]
  (dotimes [_ warmup-iters] (f))
  (let [start (System/nanoTime)]
    (dotimes [_ bench-iters] (f))
    (/ (- (System/nanoTime) start) (* bench-iters 1000.0))))

(defn bench-op-samples
  "Benchmark f multiple times and return summary stats."
  [f warmup-iters bench-iters sample-runs]
  (let [samples (vec (repeatedly sample-runs #(bench-op f warmup-iters bench-iters)))
        sorted  (sort samples)
        n       (count sorted)]
    {:median  (nth sorted (quot n 2))
     :mean    (/ (reduce + sorted) n)
     :low     (first sorted)
     :high    (last sorted)
     :samples samples}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope Construction at Arbitrary Chunk Size
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rope-node-create-fn
  "Build a rope-node-create function for a given chunk size."
  []
  (fn [chunk _ l r]
    (node/->SimpleNode chunk
      (+ (count chunk) (ropetree/rope-size l) (ropetree/rope-size r))
      l r
      (+ 1 (tree/node-size l) (tree/node-size r)))))

(defn build-rope-root
  "Build a rope root from a collection using a specific target chunk size."
  [coll ^long target]
  (let [chunks (mapv vec (partition-all target coll))]
    (ropetree/chunks->root chunks)))

(defn build-vector
  [coll]
  (vec coll))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Individual Operation Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-nth-for-chunk-size
  "Benchmark 1000 random nth lookups."
  [root v n opts]
  (let [rng  (java.util.Random. 42)
        idxs (vec (repeatedly 1000 #(.nextInt rng (int n))))]
    {:rope   (bench-op-samples
               #(dotimes [i 1000]
                  (ropetree/rope-nth root (nth idxs i)))
               (:warmup opts) (:iters opts) (:samples opts))
     :vector (bench-op-samples
               #(dotimes [i 1000]
                  (nth v (nth idxs i)))
               (:warmup opts) (:iters opts) (:samples opts))}))

(defn bench-reduce-for-chunk-size
  "Benchmark reduce + over all elements."
  [root v opts]
  {:rope   (bench-op-samples
             #(ropetree/rope-reduce + 0 root)
             (:warmup opts) (:iters opts) (:samples opts))
   :vector (bench-op-samples
             #(reduce + 0 v)
             (:warmup opts) (:iters opts) (:samples opts))})

(defn bench-split-for-chunk-size
  "Benchmark split at midpoint."
  [root v n opts]
  (let [mid (quot n 2)]
    {:rope   (bench-op-samples
               #(ropetree/rope-split-at root mid)
               (:warmup opts) (:iters opts) (:samples opts))
     :vector (bench-op-samples
               #(do [(subvec v 0 mid) (subvec v mid)] nil)
               (:warmup opts) (:iters opts) (:samples opts))}))

(defn bench-splice-for-chunk-size
  "Benchmark splice at midpoint."
  [root v n opts]
  (let [mid   (quot n 2)
        start (max 0 (- mid 16))
        end   (min n (+ mid 16))
        ins   (vec (range 32))
        ins-root (build-rope-root ins ropetree/+target-chunk-size+)]
    {:rope   (bench-op-samples
               #(let [[l _] (ropetree/rope-split-at root start)
                      [_ r] (ropetree/rope-split-at root end)]
                  (ropetree/rope-concat (ropetree/rope-concat l ins-root) r))
               (:warmup opts) (:iters opts) (:samples opts))
     :vector (bench-op-samples
               #(vec (concat (subvec v 0 start) ins (subvec v end)))
               (:warmup opts) (:iters opts) (:samples opts))}))

(defn bench-concat-for-chunk-size
  "Benchmark bulk concat of pre-built pieces."
  [pieces-root vec-pieces opts]
  {:rope   (bench-op-samples
             #(reduce ropetree/rope-concat nil pieces-root)
             (:warmup opts) (:iters opts) (:samples opts))
   :vector (bench-op-samples
             #(reduce into [] vec-pieces)
             (:warmup opts) (:iters opts) (:samples opts))})

(defn bench-text-splice-for-chunk-size
  "Benchmark splice of character data: rope vs String."
  [root ^String s n opts]
  (let [mid   (quot n 2)
        start (max 0 (- mid 16))
        end   (min n (+ mid 16))
        ins   (vec (seq "REPLACED-CONTENT!!!!!!!!!!!!!!!!"))
        ins-s "REPLACED-CONTENT!!!!!!!!!!!!!!!!"
        ins-root (build-rope-root ins ropetree/+target-chunk-size+)]
    {:rope   (bench-op-samples
               #(let [[l _] (ropetree/rope-split-at root start)
                      [_ r] (ropetree/rope-split-at root end)]
                  (ropetree/rope-concat (ropetree/rope-concat l ins-root) r))
               (:warmup opts) (:iters opts) (:samples opts))
     :string (bench-op-samples
               #(str (subs s 0 start) ins-s (subs s end))
               (:warmup opts) (:iters opts) (:samples opts))}))

(defn bench-text-split-for-chunk-size
  "Benchmark split of character data: rope vs String."
  [root ^String s n opts]
  (let [mid (quot n 2)]
    {:rope   (bench-op-samples
               #(ropetree/rope-split-at root mid)
               (:warmup opts) (:iters opts) (:samples opts))
     :string (bench-op-samples
               #(do [(subs s 0 mid) (subs s mid)] nil)
               (:warmup opts) (:iters opts) (:samples opts))}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chunk Size Sweep
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-chunk-candidates [64 128 256 512 1024])
(def default-sizes [10000 100000 500000])

(defn- random-char-string ^String [^long n]
  (let [alphabet "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        sb (StringBuilder. n)]
    (dotimes [_ n]
      (.append sb (.charAt alphabet (rand-int (.length alphabet)))))
    (.toString sb)))

(defn bench-chunk-size-at-n
  "Run all operations for a given chunk size and collection size."
  [^long n ^long target opts]
  (let [data       (range n)
        root       (build-rope-root data target)
        v          (build-vector data)
        ;; Build pieces for concat bench
        pieces     (->> data (partition-all target) (mapv vec))
        piece-roots (mapv #(build-rope-root % target) pieces)
        ;; Text data for string comparison
        text       (random-char-string n)
        text-root  (build-rope-root (vec (seq text)) target)]
    (print ".")
    (flush)
    {:chunk-size   target
     :n            n
     :chunks       (count pieces)
     :nth          (bench-nth-for-chunk-size root v n opts)
     :reduce       (bench-reduce-for-chunk-size root v opts)
     :split        (bench-split-for-chunk-size root v n opts)
     :splice       (bench-splice-for-chunk-size root v n opts)
     :concat       (bench-concat-for-chunk-size piece-roots pieces opts)
     :text-splice  (bench-text-splice-for-chunk-size text-root text n opts)
     :text-split   (bench-text-split-for-chunk-size text-root text n opts)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reporting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ratio [rope-stats vec-stats]
  (/ (:median vec-stats) (:median rope-stats)))

(defn- fmt-ratio [r]
  (if (>= r 1.0)
    (format "%5.1fx" r)
    (format "%5.2fx" r)))

(defn- fmt-us [stats]
  (bu/format-ns (* 1000 (:median stats))))

(defn print-chunk-size-results
  [results]
  (println)
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (println "  ROPE CHUNK SIZE TUNING — vs Vector")
  (println "  Each cell shows: rope median / ratio vs vector (>1x = rope wins)")
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (doseq [[n size-results] (sort-by key (group-by :n results))]
    (println)
    (printf "  N = %,d%n" n)
    (println "  ┌────────────┬────────────────┬────────────────┬────────────────┬────────────────┬────────────────┐")
    (println "  │ chunk size │     nth 1000   │     reduce     │     split      │     splice     │     concat     │")
    (println "  ├────────────┼────────────────┼────────────────┼────────────────┼────────────────┼────────────────┤")
    (doseq [{:keys [chunk-size nth reduce split splice concat]} (sort-by :chunk-size size-results)]
      (printf "  │   %4d     │ %8s %5s │ %8s %5s │ %8s %5s │ %8s %5s │ %8s %5s │%n"
        chunk-size
        (fmt-us (:rope nth))       (fmt-ratio (ratio (:rope nth) (:vector nth)))
        (fmt-us (:rope reduce))    (fmt-ratio (ratio (:rope reduce) (:vector reduce)))
        (fmt-us (:rope split))     (fmt-ratio (ratio (:rope split) (:vector split)))
        (fmt-us (:rope splice))    (fmt-ratio (ratio (:rope splice) (:vector splice)))
        (fmt-us (:rope concat))    (fmt-ratio (ratio (:rope concat) (:vector concat)))))
    (println "  └────────────┴────────────────┴────────────────┴────────────────┴────────────────┴────────────────┘")
    (println (str "  vector baseline: nth=" (fmt-us (:vector (:nth (first size-results))))
                  "  reduce=" (fmt-us (:vector (:reduce (first size-results))))
                  "  split=" (fmt-us (:vector (:split (first size-results))))
                  "  splice=" (fmt-us (:vector (:splice (first size-results))))
                  "  concat=" (fmt-us (:vector (:concat (first size-results)))))))

  (println)
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (println "  ROPE CHUNK SIZE TUNING — vs String (text workload)")
  (println "  Each cell shows: rope median / ratio vs string (>1x = rope wins)")
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (doseq [[n size-results] (sort-by key (group-by :n results))]
    (println)
    (printf "  N = %,d chars%n" n)
    (println "  ┌────────────┬────────────────┬────────────────┐")
    (println "  │ chunk size │  text splice   │  text split    │")
    (println "  ├────────────┼────────────────┼────────────────┤")
    (doseq [{:keys [chunk-size text-splice text-split]} (sort-by :chunk-size size-results)]
      (printf "  │   %4d     │ %8s %5s │ %8s %5s │%n"
        chunk-size
        (fmt-us (:rope text-splice))  (fmt-ratio (ratio (:rope text-splice) (:string text-splice)))
        (fmt-us (:rope text-split))   (fmt-ratio (ratio (:rope text-split) (:string text-split)))))
    (println "  └────────────┴────────────────┴────────────────┘")
    (println (str "  string baseline: splice=" (fmt-us (:string (:text-splice (first size-results))))
                  "  split=" (fmt-us (:string (:text-split (first size-results)))))))

  (println)
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (printf "  Current chunk size: target=%d  min=%d%n"
    ropetree/+target-chunk-size+ ropetree/+min-chunk-size+)
  (println "═══════════════════════════════════════════════════════════════════════════════════════════"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Runner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-benchmark
  [& {:keys [sizes chunk-candidates warmup iters samples]
      :or {sizes default-sizes
           chunk-candidates default-chunk-candidates
           warmup 10
           iters 30
           samples 7}}]
  (let [opts {:warmup warmup :iters iters :samples samples}]
    (println "Rope chunk-size tuning benchmark")
    (println "Chunk candidates:" chunk-candidates)
    (println "Collection sizes:" sizes)
    (println (format "Per-measurement: %d warmup, %d iters, %d samples" warmup iters samples))
    (println)
    (let [results (vec (for [n sizes, target chunk-candidates]
                         (do
                           (print (format "  chunk=%d N=%,d " target n))
                           (flush)
                           (let [r (bench-chunk-size-at-n n target opts)]
                             (println)
                             r))))]
      (print-chunk-size-results results)
      results)))

(defn quick-bench []
  (run-benchmark :sizes [10000 100000]
                 :chunk-candidates [128 256 512]
                 :warmup 5 :iters 15 :samples 5))

(defn -main [& args]
  (if (has-flag? args "--quick" "-q")
    (quick-bench)
    (run-benchmark))
  (shutdown-agents))
