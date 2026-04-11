(ns ordered-collections.rope-tuning-bench
  "Systematic chunk-size tuning benchmark for all three rope variants.

   Sweeps a set of candidate chunk sizes for each variant and measures
   every major operation, reporting the per-operation speedup relative
   to the natural baseline (Vector / String / byte[]). Each candidate
   is run with the kernel's `*target-chunk-size*` dynamic var bound to
   the candidate value so the ENTIRE pipeline — construction, concat,
   split, splice, reduce — operates at that chunk size.

   Used to identify optimal per-variant CSI constants, which are then
   set in the corresponding `types/*_rope.clj` variant file.

   Usage:
     lein bench-rope-tuning                 ; full sweep
     lein bench-rope-tuning --quick         ; smaller, faster
     lein bench-rope-tuning --variant rope  ; one variant only
     lein bench-rope-tuning --variant string-rope
     lein bench-rope-tuning --variant byte-rope

   Optimal chunk size is typically the one that maximizes the
   geometric mean of the per-operation speedup vs the baseline."
  (:require [ordered-collections.bench-utils :as bu :refer [has-flag?]]
            [ordered-collections.kernel.rope :as ropetree]
            [ordered-collections.kernel.chunk]
            [ordered-collections.kernel.node :as node :refer [leaf leaf?]]
            [ordered-collections.kernel.tree :as tree]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Infrastructure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- bench-op
  "Benchmark thunk f, returning mean time in microseconds."
  [f warmup-iters bench-iters]
  (dotimes [_ warmup-iters] (f))
  (let [start (System/nanoTime)]
    (dotimes [_ bench-iters] (f))
    (/ (- (System/nanoTime) start) (* bench-iters 1000.0))))

(defn- bench-op-samples
  "Benchmark f multiple times and return summary stats."
  [f warmup-iters bench-iters sample-runs]
  (let [samples (vec (repeatedly sample-runs #(bench-op f warmup-iters bench-iters)))
        sorted  (sort samples)
        n       (count sorted)]
    {:median  (nth sorted (quot n 2))
     :mean    (/ (reduce + sorted) n)
     :low     (first sorted)
     :high    (last sorted)}))

(defn- geomean [xs]
  (let [xs (remove (fn [x] (or (nil? x) (zero? x))) xs)
        n  (count xs)]
    (if (zero? n)
      0.0
      (Math/exp (/ (reduce + (map #(Math/log (double %)) xs)) n)))))

(defmacro with-chunk-size
  "Bind kernel CSI vars to target/minsz for the duration of body."
  [target minsz & body]
  `(binding [ropetree/*target-chunk-size* (long ~target)
             ropetree/*min-chunk-size*    (long ~minsz)]
     ~@body))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generic Rope (vector chunks) vs PersistentVector
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- build-generic-rope
  "Build a rope tree at the currently bound *target-chunk-size*.
  Caller must bind *t-join* and CSI vars."
  [coll]
  (ropetree/coll->root coll))

(defn- bench-generic-rope-at [^long n ^long target opts]
  (let [minsz (quot target 2)]
    (with-chunk-size target minsz
      (binding [tree/*t-join* ropetree/rope-node-create]
        (let [data   (range n)
              root   (build-generic-rope data)
              v      (vec data)
              mid    (quot n 2)
              span   (min 16 (quot n 4))
              lo     (max 0 (- mid span))
              hi     (min n (+ mid span))
              ins    (vec (range (* 2 span)))
              ins-rt (ropetree/coll->root ins)
              rng    (java.util.Random. 42)
              idxs   (int-array (repeatedly 1000 #(.nextInt rng (max 1 n))))
              chunk-pieces (vec (partition-all target data))
              piece-roots (mapv #(ropetree/coll->root %) chunk-pieces)]
          {:target target
           :n      n
           :chunks (count chunk-pieces)

           ;; Construction
           :construct
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/rope-node-create]
                         (ropetree/coll->root data)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(vec data)
                        (:warmup opts) (:iters opts) (:samples opts))}

           ;; nth
           :nth
           {:rope (bench-op-samples
                    #(dotimes [i 1000]
                       (ropetree/rope-nth root (aget idxs i)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(dotimes [i 1000]
                           (.nth ^clojure.lang.Indexed v (aget idxs i)))
                        (:warmup opts) (:iters opts) (:samples opts))}

           ;; reduce
           :reduce
           {:rope (bench-op-samples
                    #(ropetree/rope-reduce + 0 root)
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(reduce + 0 v)
                        (:warmup opts) (:iters opts) (:samples opts))}

           ;; split at midpoint
           :split
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/rope-node-create]
                         (ropetree/rope-split-at root mid)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(do [(subvec v 0 mid) (subvec v mid)] nil)
                        (:warmup opts) (:iters opts) (:samples opts))}

           ;; splice small replacement at midpoint
           :splice
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/rope-node-create]
                         (ropetree/rope-splice-root root lo hi ins-rt)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(vec (concat (subvec v 0 lo) ins (subvec v hi)))
                        (:warmup opts) (:iters opts) (:samples opts))}

           ;; bulk concat of pre-built pieces
           :concat
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/rope-node-create]
                         (reduce ropetree/rope-concat nil piece-roots)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(reduce into [] chunk-pieces)
                        (:warmup opts) (:iters opts) (:samples opts))}})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; StringRope (String chunks) vs String
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- random-text ^String [^long n]
  (let [sb (StringBuilder. (int n))
        chars "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789"
        nchars (.length chars)]
    (dotimes [_ n]
      (.append sb (.charAt chars (rand-int nchars))))
    (.toString sb)))

(defn- bench-string-rope-at [^long n ^long target opts]
  (let [minsz (quot target 2)]
    (with-chunk-size target minsz
      (binding [tree/*t-join* ropetree/string-rope-node-create]
        (let [^String text (random-text n)
              root         (ropetree/str->root text)
              mid          (quot n 2)
              span         (min 16 (quot n 4))
              lo           (max 0 (- mid span))
              hi           (min n (+ mid span))
              ^String ins  (.toString (StringBuilder. (apply str (repeat (* 2 span) "X"))))
              ins-rt       (ropetree/str->root ins)
              rng          (java.util.Random. 42)
              idxs         (int-array (repeatedly 1000 #(.nextInt rng (max 1 n))))]
          {:target target
           :n      n
           :chunks (count (ropetree/root->chunks root))

           :construct
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/string-rope-node-create]
                         (ropetree/str->root text)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(String. text)
                        (:warmup opts) (:iters opts) (:samples opts))}

           :nth
           {:rope (bench-op-samples
                    #(dotimes [i 1000]
                       (ropetree/rope-nth root (aget idxs i)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(dotimes [i 1000]
                           (.charAt text (aget idxs i)))
                        (:warmup opts) (:iters opts) (:samples opts))}

           :reduce
           {:rope (bench-op-samples
                    #(ropetree/rope-reduce
                       (fn [^long acc c] (+ acc (long (int c))))
                       (long 0) root)
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(let [len (.length text)]
                           (loop [i (int 0), acc (long 0)]
                             (if (< i len)
                               (recur (unchecked-inc-int i)
                                      (+ acc (long (int (.charAt text i)))))
                               acc)))
                        (:warmup opts) (:iters opts) (:samples opts))}

           :split
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/string-rope-node-create]
                         (ropetree/rope-split-at root mid)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(do [(.substring text 0 mid) (.substring text mid)] nil)
                        (:warmup opts) (:iters opts) (:samples opts))}

           :splice
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/string-rope-node-create]
                         (ropetree/rope-splice-root root lo hi ins-rt)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(let [sb (StringBuilder. (+ (.length text) (.length ins) (- lo) hi))]
                           (.append sb text 0 lo)
                           (.append sb ins)
                           (.append sb text hi (.length text))
                           (.toString sb))
                        (:warmup opts) (:iters opts) (:samples opts))}

           :concat
           (let [chunks (ropetree/root->chunks root)
                 chunk-roots (mapv #(ropetree/str->root %) chunks)
                 chunk-strs  (vec chunks)]
             {:rope (bench-op-samples
                      #(with-chunk-size target minsz
                         (binding [tree/*t-join* ropetree/string-rope-node-create]
                           (reduce ropetree/rope-concat nil chunk-roots)))
                      (:warmup opts) (:iters opts) (:samples opts))
              :baseline (bench-op-samples
                          #(let [sb (StringBuilder. (int n))]
                             (doseq [^String s chunk-strs]
                               (.append sb s))
                             (.toString sb))
                          (:warmup opts) (:iters opts) (:samples opts))})})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ByteRope (byte[] chunks) vs byte[]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- random-bytes ^bytes [^long n]
  (let [rng (java.util.Random. 42)
        b (byte-array (int n))]
    (.nextBytes rng b)
    b))

(defn- ba-splice
  ^bytes [^bytes s ^long start ^long end ^bytes rep]
  (let [si (int start)
        ei (int end)
        sl (alength s)
        rl (int (if rep (alength rep) 0))
        result (byte-array (+ (- sl (- ei si)) rl))]
    (System/arraycopy s 0 result 0 si)
    (when (pos? rl)
      (System/arraycopy rep 0 result si rl))
    (System/arraycopy s ei result (+ si rl) (- sl ei))
    result))

(defn- bench-byte-rope-at [^long n ^long target opts]
  (let [minsz (quot target 2)]
    (with-chunk-size target minsz
      (binding [tree/*t-join* ropetree/byte-rope-node-create]
        (let [^bytes data (random-bytes n)
              root        (ropetree/bytes->root data)
              mid         (quot n 2)
              span        (min 16 (quot n 4))
              lo          (max 0 (- mid span))
              hi          (min n (+ mid span))
              ^bytes ins  (random-bytes (* 2 span))
              ins-rt      (ropetree/bytes->root ins)
              rng         (java.util.Random. 42)
              idxs        (int-array (repeatedly 1000 #(.nextInt rng (max 1 n))))]
          {:target target
           :n      n
           :chunks (count (ropetree/root->chunks root))

           :construct
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/byte-rope-node-create]
                         (ropetree/bytes->root data)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(java.util.Arrays/copyOf data n)
                        (:warmup opts) (:iters opts) (:samples opts))}

           :nth
           {:rope (bench-op-samples
                    #(dotimes [i 1000]
                       (ropetree/rope-nth root (aget idxs i)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(dotimes [i 1000]
                           (bit-and (long (aget data (aget idxs i))) 0xff))
                        (:warmup opts) (:iters opts) (:samples opts))}

           :reduce
           {:rope (bench-op-samples
                    #(ropetree/rope-reduce
                       (fn [^long acc x] (+ acc (long x)))
                       (long 0) root)
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(let [len (alength data)]
                           (loop [i (int 0), acc (long 0)]
                             (if (< i len)
                               (recur (unchecked-inc-int i)
                                      (+ acc (bit-and (long (aget data i)) 0xff)))
                               acc)))
                        (:warmup opts) (:iters opts) (:samples opts))}

           :split
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/byte-rope-node-create]
                         (ropetree/rope-split-at root mid)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(do [(java.util.Arrays/copyOfRange data 0 mid)
                              (java.util.Arrays/copyOfRange data mid n)]
                             nil)
                        (:warmup opts) (:iters opts) (:samples opts))}

           :splice
           {:rope (bench-op-samples
                    #(with-chunk-size target minsz
                       (binding [tree/*t-join* ropetree/byte-rope-node-create]
                         (ropetree/rope-splice-root root lo hi ins-rt)))
                    (:warmup opts) (:iters opts) (:samples opts))
            :baseline (bench-op-samples
                        #(ba-splice data lo hi ins)
                        (:warmup opts) (:iters opts) (:samples opts))}

           :concat
           (let [chunks (ropetree/root->chunks root)
                 chunk-roots (mapv #(ropetree/bytes->root %) chunks)
                 chunk-bas   (vec chunks)]
             {:rope (bench-op-samples
                      #(with-chunk-size target minsz
                         (binding [tree/*t-join* ropetree/byte-rope-node-create]
                           (reduce ropetree/rope-concat nil chunk-roots)))
                      (:warmup opts) (:iters opts) (:samples opts))
              :baseline (bench-op-samples
                          #(let [result (byte-array (int n))]
                             (loop [i 0, off 0]
                               (if (< i (count chunk-bas))
                                 (let [^bytes c (nth chunk-bas i)
                                       cl (alength c)]
                                   (System/arraycopy c 0 result off cl)
                                   (recur (unchecked-inc i) (+ off cl)))
                                 result)))
                          (:warmup opts) (:iters opts) (:samples opts))})})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sweep Runner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-targets [64 128 256 512 1024])
(def default-sizes   [1000 5000 10000 100000 500000])

(defn- run-variant [variant n target opts]
  (print (format "  %-12s N=%7d target=%4d " (name variant) n target))
  (flush)
  (let [result (case variant
                 :rope        (bench-generic-rope-at n target opts)
                 :string-rope (bench-string-rope-at n target opts)
                 :byte-rope   (bench-byte-rope-at n target opts))]
    (println)
    result))

(defn run-sweep
  "Sweep all chunk-size candidates for a given variant across all sizes."
  [variant sizes targets opts]
  (vec (for [n sizes, target targets]
         (assoc (run-variant variant n target opts)
                :variant variant))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reporting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ratio
  "Baseline / rope median. >1 means rope is faster, <1 means baseline is faster."
  [op-result]
  (when (and op-result (get-in op-result [:rope :median]) (get-in op-result [:baseline :median]))
    (/ (get-in op-result [:baseline :median])
       (get-in op-result [:rope :median]))))

(defn- fmt-ratio [^double r]
  (cond
    (nil? r) "  —  "
    (>= r 100)  (format "%5.0fx" r)
    (>= r 10)   (format "%5.1fx" r)
    (>= r 1)    (format "%5.2fx" r)
    :else       (format "%5.3fx" r)))

(defn- fmt-us [stats]
  (when stats (bu/format-ns (* 1000 (:median stats)))))

(defn- score
  "Geometric mean of the per-operation speedup ratios. Higher is better.
  Used to rank chunk-size candidates."
  [result]
  (geomean
    (keep #(ratio (get result %)) [:construct :nth :reduce :split :splice :concat])))

(defn- baseline-name [variant]
  (case variant
    :rope        "vector"
    :string-rope "String"
    :byte-rope   "byte[]"))

(defn- print-variant-header [variant]
  (println)
  (println "═══════════════════════════════════════════════════════════════════════════════════════════")
  (printf "  %-12s chunk-size tuning — vs %s (>1x = rope wins)%n"
          (name variant) (baseline-name variant))
  (println "═══════════════════════════════════════════════════════════════════════════════════════════"))

(defn- print-size-section [size-results variant]
  (let [{:keys [n]} (first size-results)]
    (println)
    (printf "  N = %,d   (baseline: %s)%n" n (baseline-name variant))
    (println "  ┌────────┬────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┬────────┐")
    (println "  │ target │ chunks │ construct│    nth   │  reduce  │  split   │  splice  │  concat  │ score  │")
    (println "  ├────────┼────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┼────────┤")
    (doseq [r (sort-by :target size-results)]
      (printf "  │  %4d  │  %4d  │  %7s │  %7s │  %7s │  %7s │  %7s │  %7s │ %6.2f │%n"
              (:target r)
              (:chunks r)
              (fmt-ratio (ratio (:construct r)))
              (fmt-ratio (ratio (:nth r)))
              (fmt-ratio (ratio (:reduce r)))
              (fmt-ratio (ratio (:split r)))
              (fmt-ratio (ratio (:splice r)))
              (fmt-ratio (ratio (:concat r)))
              (double (score r))))
    (println "  └────────┴────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┴────────┘")
    (println (str "  baselines: "
                  "construct=" (fmt-us (:baseline (:construct (first size-results))))
                  "  nth=" (fmt-us (:baseline (:nth (first size-results))))
                  "  reduce=" (fmt-us (:baseline (:reduce (first size-results))))
                  "  split=" (fmt-us (:baseline (:split (first size-results))))
                  "  splice=" (fmt-us (:baseline (:splice (first size-results))))
                  "  concat=" (fmt-us (:baseline (:concat (first size-results))))))))

(defn- print-best
  "Print the best (highest-score) chunk size for each N across the sweep."
  [results]
  (println)
  (println "  ── Best chunk size per N (by geomean of per-op speedups) ──")
  (doseq [[n size-results] (sort-by key (group-by :n results))]
    (let [ranked (sort-by score > size-results)
          best   (first ranked)]
      (printf "    N=%,7d → target=%4d  score=%.2f%n" n (:target best) (double (score best))))))

(defn print-sweep-results
  [results]
  (let [by-variant (group-by :variant results)]
    (doseq [[variant variant-results] (sort-by key by-variant)]
      (print-variant-header variant)
      (doseq [[_n size-results] (sort-by key (group-by :n variant-results))]
        (print-size-section size-results variant))
      (print-best variant-results))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Runner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-benchmark
  [& {:keys [sizes targets variants warmup iters samples]
      :or {sizes default-sizes
           targets default-targets
           variants [:rope :string-rope :byte-rope]
           warmup 10
           iters 30
           samples 7}}]
  (let [opts {:warmup warmup :iters iters :samples samples}]
    (println "Rope chunk-size tuning benchmark")
    (println "Variants:     " variants)
    (println "Targets:      " targets)
    (println "Sizes:        " sizes)
    (println (format "Per-measurement: %d warmup, %d iters, %d samples%n" warmup iters samples))
    (let [results (vec (for [variant variants
                             n sizes
                             target targets]
                         (run-variant variant n target opts)))
          tagged  (mapv #(assoc %1 :variant %2)
                        results
                        (for [variant variants
                              _n sizes
                              _target targets]
                          variant))]
      (print-sweep-results tagged)
      (println)
      (println "  Current defaults in implementation:")
      (println (format "    kernel/rope.clj:       +target-chunk-size+ = %d  +min-chunk-size+ = %d"
                       ropetree/+target-chunk-size+ ropetree/+min-chunk-size+))
      (println "    Per-variant defaults live in each types/*_rope.clj file.")
      tagged)))

(defn quick-bench []
  (run-benchmark :sizes [10000 100000]
                 :targets [128 256 512]
                 :warmup 5 :iters 15 :samples 5))

(defn -main [& args]
  (let [quick?  (has-flag? args "--quick" "-q")
        variant-arg (some->> (partition-all 2 1 args)
                             (filter (fn [[a _]] (#{"--variant"} a)))
                             first second keyword)
        variants (if variant-arg [variant-arg] [:rope :string-rope :byte-rope])]
    (if quick?
      (run-benchmark :sizes [10000 100000]
                     :targets [128 256 512]
                     :variants variants
                     :warmup 5 :iters 15 :samples 5)
      (run-benchmark :variants variants)))
  (shutdown-agents))
