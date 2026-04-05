(ns ordered-collections.rope-bench
  "Experimental rope benchmarks.

   These benchmarks focus on workloads ropes are supposed to be good at:

   - concatenation of many pieces
   - structural split
   - subrange extraction
   - middle splice/edit workloads

   The suite has two flavors:

   - text-like workloads: rope vs vector-of-chars vs String
   - generic sequence workloads: rope vs vector

   This is intentionally separate from the main benchmark suite because the
   rope is still experimental and not part of the public API.

   Usage:
     lein bench-rope
     lein bench-rope --quick
     lein bench-rope --full
     lein bench-rope --sizes 10000,100000"
  (:require [clojure.string :as str]
            [ordered-collections.bench-utils :as bu]
            [ordered-collections.tree.rope :as ropetree]
            [ordered-collections.types.rope :as rope]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sizes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sizes-quick   [1000 10000])
(def sizes-default [10000 100000 500000])
(def sizes-full    [10000 100000 500000 1000000])

(def piece-size ropetree/+target-chunk-size+)
(def ^:const splice-size 32)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workloads
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def alphabet
  (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"))

(defn random-char-vector
  [n]
  (vec (repeatedly n #(rand-nth alphabet))))

(defn char-vec->string
  [v]
  (apply str v))

(defn build-piece-vectors
  [n]
  (let [piece-count (max 1 (long (Math/ceil (/ (double n) piece-size))))
        full-pieces (repeatedly piece-count #(random-char-vector piece-size))
        total       (vec (take n (mapcat seq full-pieces)))]
    (->> total
         (partition-all piece-size)
         (mapv vec))))

(defn rope-workload
  [n]
  (let [pieces      (build-piece-vectors n)
        rope-pieces (mapv rope/rope pieces)
        vec-pieces  pieces
        str-pieces  (mapv char-vec->string pieces)
        rope-all    (reduce rope/concat-rope (rope/empty-rope) rope-pieces)
        vec-all     (reduce into [] vec-pieces)
        str-all     (apply str str-pieces)
        mid         (quot n 2)
        start       (max 0 (- mid (quot splice-size 2)))
        end         (min n (+ start splice-size))
        inserted    (random-char-vector splice-size)
        inserted-r  (rope/rope inserted)
        inserted-s  (char-vec->string inserted)]
    {:n          n
     :mid        mid
     :start      start
     :end        end
     :inserted   inserted
     :inserted-r inserted-r
     :inserted-s inserted-s
     :rope       rope-all
     :vector     vec-all
     :string     str-all
     :rope-parts rope-pieces
     :vec-parts  vec-pieces
     :str-parts  str-pieces}))

(defn generic-workload
  [n]
  (let [pieces      (->> (range n)
                      (partition-all piece-size)
                      (mapv vec))
        rope-pieces (mapv rope/rope pieces)
        vec-pieces  pieces
        rope-all    (reduce rope/concat-rope (rope/empty-rope) rope-pieces)
        vec-all     (reduce into [] vec-pieces)
        mid         (quot n 2)
        start       (max 0 (- mid (quot splice-size 2)))
        end         (min n (+ start splice-size))
        inserted    (vec (range splice-size))]
    {:n          n
     :mid        mid
     :start      start
     :end        end
     :inserted   inserted
     :rope       rope-all
     :vector     vec-all
     :rope-parts rope-pieces
     :vec-parts  vec-pieces}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmarks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bench-concat-many
  [n]
  (let [{:keys [rope-parts vec-parts str-parts]} (rope-workload n)]
    [(bu/bench 20 30
       (apply rope/concat-ropes rope-parts))
     (bu/bench 20 30
       (reduce into [] vec-parts))
     (bu/bench 20 30
       (apply str str-parts))]))

(defn bench-split-middle
  [n]
  (let [{:keys [rope vector string mid]} (rope-workload n)]
    [(bu/bench 30 40
       (rope/split-rope-at rope mid))
     (bu/bench 30 40
       [(subvec vector 0 mid) (subvec vector mid)])
     (bu/bench 30 40
       [(subs string 0 mid) (subs string mid)])]))

(defn bench-slice-middle
  [n]
  (let [{:keys [rope vector string start end]} (rope-workload n)]
    [(bu/bench 30 40
       (rope/subrope rope start end))
     (bu/bench 30 40
       (subvec vector start end))
     (bu/bench 30 40
       (subs string start end))]))

(defn bench-splice-middle
  [n]
  (let [{:keys [rope vector string start end inserted inserted-s]} (rope-workload n)]
    [(bu/bench 20 30
       (rope/splice-rope rope start end inserted))
     (bu/bench 20 30
       (vec (concat (subvec vector 0 start) inserted (subvec vector end))))
     (bu/bench 20 30
       (str (subs string 0 start) inserted-s (subs string end)))]))

(defn bench-chunk-iteration
  [n]
  (let [{:keys [rope vector string]} (rope-workload n)]
    [(bu/bench 30 50
       (reduce + 0 (map count (rope/rope-chunks rope))))
     (bu/bench 30 50
       (reduce + 0 (map count (partition-all piece-size vector))))
     (bu/bench 30 50
       (reduce + 0 (map count (partition-all piece-size string))))]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Runner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def columns ["rope" "vector" "string"])
(def generic-columns ["rope" "vector"])

(defn run-benchmark-group
  [title sizes bench-fn]
  (bu/print-header title columns)
  (doseq [n sizes]
    (bu/print-row n (bench-fn n))))

(defn run-generic-benchmark-group
  [title sizes bench-fn]
  (bu/print-header title generic-columns)
  (doseq [n sizes]
    (bu/print-row n (bench-fn n))))

(defn bench-generic-concat-many
  [n]
  (let [{:keys [rope-parts vec-parts]} (generic-workload n)]
    [(bu/bench 20 30
       (apply rope/concat-ropes rope-parts))
     (bu/bench 20 30
       (reduce into [] vec-parts))]))

(defn bench-generic-split-middle
  [n]
  (let [{:keys [rope vector mid]} (generic-workload n)]
    [(bu/bench 30 40
       (rope/split-rope-at rope mid))
     (bu/bench 30 40
       [(subvec vector 0 mid) (subvec vector mid)])]))

(defn bench-generic-slice-middle
  [n]
  (let [{:keys [rope vector start end]} (generic-workload n)]
    [(bu/bench 30 40
       (rope/subrope rope start end))
     (bu/bench 30 40
       (subvec vector start end))]))

(defn bench-generic-splice-middle
  [n]
  (let [{:keys [rope vector start end inserted]} (generic-workload n)]
    [(bu/bench 20 30
       (rope/splice-rope rope start end inserted))
     (bu/bench 20 30
       (vec (concat (subvec vector 0 start) inserted (subvec vector end))))]))

(defn bench-generic-chunk-iteration
  [n]
  (let [{:keys [rope vector]} (generic-workload n)]
    [(bu/bench 30 50
       (reduce + 0 (map count (rope/rope-chunks rope))))
     (bu/bench 30 50
       (reduce + 0 (map count (partition-all piece-size vector))))]))

(defn run-all
  [sizes]
  (println)
  (println "Experimental rope benchmark suite")
  (println "Includes both text-like and generic sequence workloads.")
  (println)
  (println "Text-like workloads: rope vs vector-of-chars vs string.")
  (run-benchmark-group "Concatenate Many Pieces" sizes bench-concat-many)
  (run-benchmark-group "Split In Middle" sizes bench-split-middle)
  (run-benchmark-group "Slice Middle Window" sizes bench-slice-middle)
  (run-benchmark-group "Splice Middle Window" sizes bench-splice-middle)
  (run-benchmark-group "Chunk Iteration" sizes bench-chunk-iteration)
  (println)
  (println "Generic sequence workloads: rope vs vector.")
  (run-generic-benchmark-group "Generic Concat Many Pieces" sizes bench-generic-concat-many)
  (run-generic-benchmark-group "Generic Split In Middle" sizes bench-generic-split-middle)
  (run-generic-benchmark-group "Generic Slice Middle Window" sizes bench-generic-slice-middle)
  (run-generic-benchmark-group "Generic Splice Middle Window" sizes bench-generic-splice-middle)
  (run-generic-benchmark-group "Generic Chunk Iteration" sizes bench-generic-chunk-iteration))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLI
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn print-usage []
  (println "Usage: lein bench-rope [options]")
  (println)
  (println "Options:")
  (println "  --quick            Fast iteration (1K to 10K)")
  (println "  --full             Full suite including 1M")
  (println "  --sizes N,N,...    Custom sizes")
  (println "  --help             Show this help")
  (println)
  (println "Examples:")
  (println "  lein bench-rope --quick")
  (println "  lein bench-rope --sizes 10000,100000"))

(defn parse-args
  [args]
  (bu/parse-standard-args args sizes-quick sizes-default sizes-full))

(defn -main
  [& args]
  (let [{:keys [sizes help]} (parse-args args)]
    (if help
      (print-usage)
      (run-all sizes)))
  (shutdown-agents))
