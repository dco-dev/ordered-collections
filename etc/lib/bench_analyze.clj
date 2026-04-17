(ns bench-analyze
  "Benchmark analysis: ranking, scorecard, regressions, category classification."
  (:require [clojure.string :as str]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OC Variant Detection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private oc-prefixes
  ["ordered-set" "ordered-map" "long-ordered" "string-ordered"
   "interval-set" "interval-map" "range-map" "string-rope" "rope"])

(defn- oc-variant?
  [variant]
  (let [s (name variant)]
    (boolean (some #(str/starts-with? s %) oc-prefixes))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Category Classification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def category-order
  [:set-algebra :construction :lookup :iteration :fold :split
   :equality :rank :string :interval
   :range-map :segment-tree :priority-queue :multiset :fuzzy
   :rope :string-rope :byte-rope :other])

(def ^:private category-patterns
  ;; Specific patterns first — classify-group returns the first match.
  [[:set-algebra    #"union|intersection|difference"]
   [:range-map      #"^range-map"]
   [:segment-tree   #"^segment-tree"]
   [:priority-queue #"^priority-queue"]
   [:multiset       #"^multiset"]
   [:fuzzy          #"^fuzzy-"]
   [:string-rope    #"^string-rope"]
   [:byte-rope      #"^byte-rope"]
   [:construction   #"construction"]
   [:lookup         #"lookup"]
   [:iteration      #"iteration"]
   [:fold           #"fold|reduce"]
   [:split          #"split"]
   [:equality       #"equal|different|size-different"]
   [:rank           #"rank"]
   [:string         #"string"]
   [:interval       #"interval"]
   [:rope           #"rope"]])

(defn classify-group
  [group]
  (let [g (name group)]
    (or (some (fn [[cat pattern]]
                (when (re-find pattern g) cat))
              category-patterns)
        :other)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Noise Detection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- outlier-count
  [row]
  (reduce + 0 (vals (or (:outliers row) {}))))

(defn- ci-width
  [row]
  (let [[lo hi] (:mean-ci-ns row)]
    (when (and lo hi) (- hi lo))))

(defn noisy-row
  [row]
  (let [mean      (double (:mean-ns row))
        cv        (when (pos? mean) (/ (double (or (:stdev-ns row) 0)) mean))
        ci-ratio  (when-let [w (ci-width row)]
                    (when (pos? mean) (/ (double w) mean)))
        outliers  (outlier-count row)
        sample-c  (or (:sample-count row) 0)
        reasons   (cond-> []
                    (and cv (> cv 0.10))        (conj :high-variance)
                    (and ci-ratio (> ci-ratio 0.20)) (conj :wide-ci)
                    (pos? outliers)              (conj :outliers)
                    (< sample-c 6)              (conj :low-samples))]
    (assoc row
           :cv cv
           :ci-width-ratio ci-ratio
           :outlier-count outliers
           :noise-reasons reasons
           :noisy? (boolean (seq reasons)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ranking
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rank-cases
  [rows]
  (->> rows
       (group-by (juxt :size :group))
       (map (fn [[[size group] case-rows]]
              (let [ranked    (sort-by :mean-ns case-rows)
                    winner    (first ranked)
                    runner-up (second ranked)
                    ranked*   (mapv (fn [row]
                                      (assoc row
                                             :vs-winner-ratio
                                             (/ (double (:mean-ns row))
                                                (double (:mean-ns winner)))))
                                    ranked)]
                {:size              size
                 :group             group
                 :category          (classify-group group)
                 :winner            (:variant winner)
                 :winner-mean-ns    (:mean-ns winner)
                 :runner-up         (:variant runner-up)
                 :runner-up-mean-ns (:mean-ns runner-up)
                 :winner-speedup    (when runner-up
                                      (/ (double (:mean-ns runner-up))
                                         (double (:mean-ns winner))))
                 :ranked            ranked*})))
       (sort-by (juxt :size :group))
       vec))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ordered-Collections Scorecard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Cases with absolute times below this threshold are too noisy for
;; meaningful ratio comparison (sub-microsecond measurements).
(def ^:private ^:const min-meaningful-ns 10000)

(defn ordered-scorecard
  [rows]
  (->> (rank-cases rows)
       (keep (fn [{:keys [size group category ranked]}]
               (let [oc-rows   (filter #(oc-variant? (:variant %)) ranked)
                     peer-rows (remove #(oc-variant? (:variant %)) ranked)
                     best-oc   (first (sort-by :mean-ns oc-rows))
                     best-peer (first (sort-by :mean-ns peer-rows))]
                 (when (and best-oc best-peer
                            (> (double (:mean-ns best-oc)) min-meaningful-ns)
                            (> (double (:mean-ns best-peer)) min-meaningful-ns))
                   (let [speedup (/ (double (:mean-ns best-peer))
                                    (double (:mean-ns best-oc)))]
                     {:size             size
                      :group            group
                      :category         category
                      :ordered-variant  (:variant best-oc)
                      :ordered-mean-ns  (:mean-ns best-oc)
                      :peer-variant     (:variant best-peer)
                      :peer-mean-ns     (:mean-ns best-peer)
                      :speedup          speedup
                      :slowdown         (when (< speedup 1.0) (/ 1.0 speedup))
                      :status           (cond
                                          (> speedup 1.05) :win
                                          (>= speedup 0.95) :parity
                                          :else :loss)})))))
       vec))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Headline Wins — scaling table for README-worthy results
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def headline-benchmarks
  "Curated benchmark comparisons that appear in the README. Each entry
   specifies a group pattern, which OC variant to use, which peer to
   compare against, and a display label."
  [;; Set algebra vs sorted-set
   {:pattern #"^set-union$"        :oc :ordered-set :peer :sorted-set  :label "Union"        :section "Set Algebra vs sorted-set"}
   {:pattern #"^set-intersection$" :oc :ordered-set :peer :sorted-set  :label "Intersection" :section "Set Algebra vs sorted-set"}
   {:pattern #"^set-difference$"   :oc :ordered-set :peer :sorted-set  :label "Difference"   :section "Set Algebra vs sorted-set"}
   ;; Set algebra vs data.avl
   {:pattern #"^set-union$"        :oc :ordered-set :peer :data-avl    :label "Union"        :section "Set Algebra vs data.avl"}
   {:pattern #"^set-intersection$" :oc :ordered-set :peer :data-avl    :label "Intersection" :section "Set Algebra vs data.avl"}
   {:pattern #"^set-difference$"   :oc :ordered-set :peer :data-avl    :label "Difference"   :section "Set Algebra vs data.avl"}
   ;; Set algebra vs hash-set
   {:pattern #"^set-union$"        :oc :ordered-set :peer :clojure-set :label "Union"        :section "Set Algebra vs clojure.core/set"}
   {:pattern #"^set-intersection$" :oc :ordered-set :peer :clojure-set :label "Intersection" :section "Set Algebra vs clojure.core/set"}
   {:pattern #"^set-difference$"   :oc :ordered-set :peer :clojure-set :label "Difference"   :section "Set Algebra vs clojure.core/set"}
   ;; Ordered Set vs competitors
   {:pattern #"^set-construction$"  :oc :ordered-set      :peer :sorted-set       :label "Construction"  :section "Ordered Set vs sorted-set"}
   {:pattern #"^set-lookup$"        :oc :ordered-set      :peer :sorted-set       :label "Lookup"        :section "Ordered Set vs sorted-set"}
   {:pattern #"^set-iteration$"     :oc :ordered-set      :peer :sorted-set       :label "Iteration"     :section "Ordered Set vs sorted-set"}
   {:pattern #"^set-fold$"          :oc :ordered-set-fold :peer :sorted-set-fold  :label "Fold"          :section "Ordered Set vs sorted-set"}
   {:pattern #"^split$"             :oc :ordered-set      :peer :data-avl         :label "Split"         :section "Ordered Set vs sorted-set"}
   {:pattern #"^set-iteration-iterator$" :oc :ordered-set :peer :sorted-set      :label "Iteration (Iterator)" :section "Ordered Set vs sorted-set"}
   {:pattern #"^set-construction$"  :oc :ordered-set      :peer :data-avl         :label "Construction"  :section "Ordered Set vs data.avl"}
   {:pattern #"^set-lookup$"        :oc :ordered-set      :peer :data-avl         :label "Lookup"        :section "Ordered Set vs data.avl"}
   {:pattern #"^set-iteration$"     :oc :ordered-set      :peer :data-avl         :label "Iteration"     :section "Ordered Set vs data.avl"}
   {:pattern #"^set-iteration-iterator$" :oc :ordered-set :peer :data-avl         :label "Iteration (Iterator)" :section "Ordered Set vs data.avl"}
   ;; Ordered Map vs competitors
   {:pattern #"^map-construction$"  :oc :ordered-map      :peer :sorted-map       :label "Construction"  :section "Ordered Map vs sorted-map"}
   {:pattern #"^map-lookup$"        :oc :ordered-map      :peer :sorted-map       :label "Lookup"        :section "Ordered Map vs sorted-map"}
   {:pattern #"^map-iteration$"     :oc :ordered-map      :peer :sorted-map       :label "Iteration"     :section "Ordered Map vs sorted-map"}
   {:pattern #"^map-fold$"          :oc :ordered-map-reduce :peer :sorted-map-reduce :label "Reduce"     :section "Ordered Map vs sorted-map"}
   {:pattern #"^map-construction$"  :oc :ordered-map      :peer :data-avl         :label "Construction"  :section "Ordered Map vs data.avl"}
   {:pattern #"^map-lookup$"        :oc :ordered-map      :peer :data-avl         :label "Lookup"        :section "Ordered Map vs data.avl"}
   {:pattern #"^map-iteration$"     :oc :ordered-map      :peer :data-avl         :label "Iteration"     :section "Ordered Map vs data.avl"}
   ;; Long-specialized
   {:pattern #"^long-construction$" :oc :long-ordered     :peer :sorted-set       :label "Construction"  :section "Long-Specialized vs sorted-set"}
   {:pattern #"^long-lookup$"       :oc :long-ordered     :peer :sorted-set       :label "Lookup"        :section "Long-Specialized vs sorted-set"}
   {:pattern #"^long-union$"        :oc :long-ordered     :peer :sorted-set       :label "Union"         :section "Long-Specialized vs sorted-set"}
   {:pattern #"^long-intersection$" :oc :long-ordered     :peer :sorted-set       :label "Intersection"  :section "Long-Specialized vs sorted-set"}
   {:pattern #"^long-difference$"   :oc :long-ordered     :peer :sorted-set       :label "Difference"    :section "Long-Specialized vs sorted-set"}
   {:pattern #"^long-rank-lookup$"  :oc :long-ordered     :peer :data-avl         :label "Rank lookup"   :section "Long-Specialized vs data.avl"}
   ;; String-specialized
   {:pattern #"^string-set-construction$" :oc :string-ordered :peer :sorted-set-by :label "Construction" :section "String-Specialized vs sorted-set-by"}
   {:pattern #"^string-set-lookup$"       :oc :string-ordered :peer :sorted-set-by :label "Lookup"       :section "String-Specialized vs sorted-set-by"}
   {:pattern #"^string-set-union$"        :oc :string-ordered :peer :sorted-set-by :label "Union"        :section "String-Specialized vs sorted-set-by"}
   {:pattern #"^string-set-intersection$" :oc :string-ordered :peer :sorted-set-by :label "Intersection" :section "String-Specialized vs sorted-set-by"}
   {:pattern #"^string-set-difference$"   :oc :string-ordered :peer :sorted-set-by :label "Difference"   :section "String-Specialized vs sorted-set-by"}
   {:pattern #"^string-rank-lookup$"      :oc :string-ordered :peer :data-avl      :label "Rank lookup"  :section "String-Specialized vs data.avl"}
   ;; Rope vs PersistentVector (matches bench_runner groups)
   {:pattern #"^rope-repeated-edits$"  :oc :rope   :peer :vector  :label "200 Random Edits"  :section "Rope vs PersistentVector"}
   {:pattern #"^rope-splice$"          :oc :rope   :peer :vector  :label "Single Splice"     :section "Rope vs PersistentVector"}
   {:pattern #"^rope-concat$"          :oc :rope   :peer :vector  :label "Concat Pieces"     :section "Rope vs PersistentVector"}
   {:pattern #"^rope-chunk-iteration$" :oc :rope   :peer :vector  :label "Chunk Iteration"   :section "Rope vs PersistentVector"}
   {:pattern #"^rope-reduce$"          :oc :rope   :peer :vector  :label "Reduce (sum)"      :section "Rope vs PersistentVector"}
   {:pattern #"^rope-fold-sum$"       :oc :rope   :peer :vector  :label "Fold (sum)"        :section "Rope vs PersistentVector"}
   {:pattern #"^rope-nth$"            :oc :rope   :peer :vector  :label "Random nth (1000)" :section "Rope vs PersistentVector"}
   {:pattern #"^rope-fold-freq$"     :oc :rope   :peer :vector  :label "Fold (freq map)"  :section "Rope vs PersistentVector"}
   ;; StringRope vs String (idiomatic str+subs)
   {:pattern #"^string-rope-splice$"          :oc :string-rope :peer :string :label "Single Splice"      :section "StringRope vs String"}
   {:pattern #"^string-rope-insert$"          :oc :string-rope :peer :string :label "Single Insert"      :section "StringRope vs String"}
   {:pattern #"^string-rope-remove$"          :oc :string-rope :peer :string :label "Single Remove"      :section "StringRope vs String"}
   {:pattern #"^string-rope-concat$"          :oc :string-rope :peer :string :label "Concat Halves"      :section "StringRope vs String"}
   {:pattern #"^string-rope-split$"           :oc :string-rope :peer :string :label "Split at Midpoint"  :section "StringRope vs String"}
   {:pattern #"^string-rope-repeated-edits$"  :oc :string-rope :peer :string :label "200 Random Edits"   :section "StringRope vs String"}
   {:pattern #"^string-rope-nth$"             :oc :string-rope :peer :string :label "Random nth (1000)"  :section "StringRope vs String"}
   {:pattern #"^string-rope-reduce$"          :oc :string-rope :peer :string :label "Reduce (sum chars)" :section "StringRope vs String"}
   {:pattern #"^string-rope-re-find$"        :oc :string-rope :peer :string :label "re-find"            :section "StringRope vs String"}
   {:pattern #"^string-rope-re-seq$"         :oc :string-rope :peer :string :label "re-seq"             :section "StringRope vs String"}
   {:pattern #"^string-rope-str$"            :oc :string-rope :peer :string :label "Materialization (str)" :section "StringRope vs String"}
   {:pattern #"^string-rope-re-replace$"     :oc :string-rope :peer :string :label "str/replace (regex)" :section "StringRope vs String"}
   ;; StringRope vs StringBuilder (optimal mutable baseline)
   {:pattern #"^string-rope-splice$"          :oc :string-rope :peer :string-builder :label "Single Splice"      :section "StringRope vs StringBuilder"}
   {:pattern #"^string-rope-insert$"          :oc :string-rope :peer :string-builder :label "Single Insert"      :section "StringRope vs StringBuilder"}
   {:pattern #"^string-rope-remove$"          :oc :string-rope :peer :string-builder :label "Single Remove"      :section "StringRope vs StringBuilder"}
   {:pattern #"^string-rope-concat$"          :oc :string-rope :peer :string-builder :label "Concat Halves"      :section "StringRope vs StringBuilder"}
   {:pattern #"^string-rope-split$"           :oc :string-rope :peer :string-builder :label "Split at Midpoint"  :section "StringRope vs StringBuilder"}
   {:pattern #"^string-rope-repeated-edits$"  :oc :string-rope :peer :string-builder :label "200 Random Edits"   :section "StringRope vs StringBuilder"}
   {:pattern #"^string-rope-construction$"    :oc :string-rope :peer :string-builder :label "Construction"       :section "StringRope vs StringBuilder"}
   ;; ByteRope vs byte[] (arraycopy baseline)
   {:pattern #"^byte-rope-splice$"          :oc :byte-rope :peer :byte-array :label "Single Splice"      :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-insert$"          :oc :byte-rope :peer :byte-array :label "Single Insert"      :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-remove$"          :oc :byte-rope :peer :byte-array :label "Single Remove"      :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-concat$"          :oc :byte-rope :peer :byte-array :label "Concat 4 Pieces"    :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-split$"           :oc :byte-rope :peer :byte-array :label "Split at Midpoint"  :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-repeated-edits$"  :oc :byte-rope :peer :byte-array :label "200 Random Edits"   :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-nth$"             :oc :byte-rope :peer :byte-array :label "Random nth (1000)"  :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-reduce$"          :oc :byte-rope :peer :byte-array :label "Reduce (sum bytes)" :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-fold$"            :oc :byte-rope :peer :byte-array :label "Fold (sum bytes)"   :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-construction$"    :oc :byte-rope :peer :byte-array :label "Construction"       :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-bytes$"           :oc :byte-rope :peer :byte-array :label "Materialization"    :section "ByteRope vs byte[]"}
   {:pattern #"^byte-rope-digest$"          :oc :byte-rope :peer :byte-array :label "SHA-256"            :section "ByteRope vs byte[]"}
   ;; Range Map vs Guava TreeRangeMap
   {:pattern #"^range-map-construction$"       :oc :range-map :peer :guava-range-map :label "Construction"        :section "Range Map vs Guava TreeRangeMap"}
   {:pattern #"^range-map-bulk-construction$"  :oc :range-map :peer :guava-range-map :label "Bulk Construction"   :section "Range Map vs Guava TreeRangeMap"}
   {:pattern #"^range-map-lookup$"             :oc :range-map :peer :guava-range-map :label "Point Lookup"        :section "Range Map vs Guava TreeRangeMap"}
   {:pattern #"^range-map-carve-out$"       :oc :range-map :peer :guava-range-map :label "Carve-out Insert" :section "Range Map vs Guava TreeRangeMap"}
   {:pattern #"^range-map-iteration$"       :oc :range-map :peer :guava-range-map :label "Iteration"      :section "Range Map vs Guava TreeRangeMap"}
   ;; Segment Tree vs sorted-map range reduction
   {:pattern #"^segment-tree-construction$" :oc :segment-tree :peer :sorted-map :label "Construction"     :section "Segment Tree vs sorted-map"}
   {:pattern #"^segment-tree-query$"        :oc :segment-tree :peer :sorted-map :label "Range Query"      :section "Segment Tree vs sorted-map"}
   {:pattern #"^segment-tree-update$"       :oc :segment-tree :peer :sorted-map :label "Point Update"     :section "Segment Tree vs sorted-map"}
   ;; Priority Queue vs sorted-set-by of [priority seqnum value] tuples
   {:pattern #"^priority-queue-construction$" :oc :priority-queue :peer :sorted-set-by :label "Construction" :section "Priority Queue vs sorted-set-by"}
   {:pattern #"^priority-queue-push$"       :oc :priority-queue :peer :sorted-set-by :label "Push"         :section "Priority Queue vs sorted-set-by"}
   {:pattern #"^priority-queue-pop-min$"    :oc :priority-queue :peer :sorted-set-by :label "Pop-min"      :section "Priority Queue vs sorted-set-by"}
   ;; Ordered Multiset vs sorted-map counts
   {:pattern #"^multiset-construction$"     :oc :ordered-multiset :peer :sorted-map-counts :label "Construction" :section "Ordered Multiset vs sorted-map counts"}
   {:pattern #"^multiset-multiplicity$"     :oc :ordered-multiset :peer :sorted-map-counts :label "Multiplicity" :section "Ordered Multiset vs sorted-map counts"}
   {:pattern #"^multiset-iteration$"        :oc :ordered-multiset :peer :sorted-map-counts :label "Iteration"    :section "Ordered Multiset vs sorted-map counts"}
   ;; Fuzzy Set vs sorted-set + manual floor/ceiling
   {:pattern #"^fuzzy-set-construction$"    :oc :fuzzy-set :peer :sorted-set :label "Construction"    :section "Fuzzy Set vs sorted-set"}
   {:pattern #"^fuzzy-set-nearest$"         :oc :fuzzy-set :peer :sorted-set :label "Nearest Lookup"  :section "Fuzzy Set vs sorted-set"}
   ;; Fuzzy Map vs sorted-map + manual floor/ceiling
   {:pattern #"^fuzzy-map-construction$"    :oc :fuzzy-map :peer :sorted-map :label "Construction"    :section "Fuzzy Map vs sorted-map"}
   {:pattern #"^fuzzy-map-nearest$"         :oc :fuzzy-map :peer :sorted-map :label "Nearest Lookup"  :section "Fuzzy Map vs sorted-map"}])

(defn headline-wins
  "Extract headline speedups pivoted by size, with explicit OC variant and peer.
   Returns rows grouped by section for rendering."
  [rows sizes]
  (let [by-key (group-by (juxt :size :group :variant) rows)
        lookup (fn [size group variant]
                 (:mean-ns (first (by-key [size group variant]))))]
    (->> headline-benchmarks
         (keep (fn [{:keys [pattern oc peer label section]}]
                 (let [groups (filter #(re-find pattern (name %))
                                      (set (map :group rows)))]
                   (when (seq groups)
                     (let [group (first groups)]
                       {:label   label
                        :section section
                        :sizes   (mapv (fn [sz]
                                         (let [oc-ns   (lookup sz group oc)
                                               peer-ns (lookup sz group peer)]
                                           (when (and oc-ns peer-ns (pos? (double oc-ns)))
                                             {:speedup (/ (double peer-ns) (double oc-ns))})))
                                       sizes)})))))
         vec)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parity, Losses, Wins extraction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parity-cases
  [scorecard]
  (->> scorecard
       (filter #(= :parity (:status %)))
       (sort-by :group)
       vec))

(def ^:private loss-context
  "Known architectural explanations for expected losses."
  {"set-iteration"    "Enumerator-based seq allocates per step"
   "map-iteration"    "Enumerator-based seq allocates per step"
   "string-iteration" "Same enumerator overhead on string-keyed maps"
   "first-last"       "sorted-set caches first; ours traverses to least node"
   "rank-access"      "data.avl rank has lower constant factor"
   "rank-lookup"      "data.avl rank has lower constant factor"
   "size-different"   "hash-set short-circuits on count mismatch; we walk elements"
   "different"        "hash-set equiv short-circuits earlier via hash check"
   "equal"            "hash-set equiv uses cached hash; we walk tree"})

(defn significant-losses
  "Scorecard entries where OC is >1.2x slower, with architectural context."
  [scorecard]
  (->> scorecard
       (filter #(and (= :loss (:status %))
                     (< (:speedup %) 0.83)))
       (map (fn [row]
              (assoc row
                     :context (get loss-context (name (:group row)))
                     :slowdown (/ 1.0 (:speedup row)))))
       (sort-by :slowdown >)
       vec))

(defn significant-wins
  "Scorecard entries where OC wins by >1.2x."
  [scorecard]
  (->> scorecard
       (filter #(and (= :win (:status %))
                     (> (:speedup %) 1.2)))
       (sort-by :speedup >)
       vec))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Category Summary — per-category aggregated stats
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- median
  [xs]
  (let [sorted (sort xs)
        n      (count sorted)]
    (when (pos? n)
      (nth sorted (quot n 2)))))

(defn- geomean
  "Geometric mean — the right average for speedup ratios because
   the arithmetic mean over-weights the large wins."
  [xs]
  (let [clean (remove (fn [x] (or (nil? x) (not (pos? x)))) xs)
        n     (count clean)]
    (when (pos? n)
      (Math/exp (/ (reduce + (map #(Math/log (double %)) clean)) n)))))

(defn category-summary
  "Aggregate the scorecard by category and return per-category stats.
   Each entry reports case counts, median/geomean speedup, best win,
   and worst loss within the category. Useful for a top-level
   'Performance by Category' section in the report."
  [scorecard]
  (->> scorecard
       (group-by :category)
       (map (fn [[category rows]]
              (let [speedups  (map :speedup rows)
                    wins      (filter #(= :win (:status %)) rows)
                    losses    (filter #(= :loss (:status %)) rows)
                    parity    (filter #(= :parity (:status %)) rows)
                    best      (when (seq wins) (apply max-key :speedup wins))
                    worst     (when (seq losses) (apply min-key :speedup losses))]
                {:category  category
                 :total     (count rows)
                 :wins      (count wins)
                 :parity    (count parity)
                 :losses    (count losses)
                 :median    (median speedups)
                 :geomean   (geomean speedups)
                 :best-win  (:speedup best)
                 :best-win-group (:group best)
                 :worst-loss (:speedup worst)
                 :worst-loss-group (:group worst)})))
       (sort-by (juxt (fn [{:keys [category]}]
                        (.indexOf category-order category))))
       vec))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rope Family Summary — cross-variant comparison for structural ops
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private rope-family-ops
  "Operations where all three rope variants (rope, string-rope,
   byte-rope) have matching benchmark groups, laid out side-by-side
   for a quick cross-variant glance."
  [{:label "Concat"           :rope "rope-concat"          :string "string-rope-concat"         :byte "byte-rope-concat"}
   {:label "Split"            :rope nil                    :string "string-rope-split"          :byte "byte-rope-split"}
   {:label "Splice"           :rope "rope-splice"          :string "string-rope-splice"         :byte "byte-rope-splice"}
   {:label "Insert"           :rope nil                    :string "string-rope-insert"         :byte "byte-rope-insert"}
   {:label "Remove"           :rope nil                    :string "string-rope-remove"         :byte "byte-rope-remove"}
   {:label "200 Random Edits" :rope "rope-repeated-edits"  :string "string-rope-repeated-edits" :byte "byte-rope-repeated-edits"}
   {:label "Random nth"       :rope "rope-nth"             :string "string-rope-nth"            :byte "byte-rope-nth"}
   {:label "Reduce"           :rope "rope-reduce"          :string "string-rope-reduce"         :byte "byte-rope-reduce"}])

(defn- rope-family-variant-baseline
  [variant group-name]
  (case variant
    :rope        {:oc :rope :peer :vector}
    :string-rope {:oc :string-rope :peer :string}
    :byte-rope   {:oc :byte-rope :peer :byte-array}))

(defn rope-family-summary
  "For each structural op, collect per-variant speedup vs the natural
   baseline at the largest benchmarked size. Returns rows with keys
   :label, :rope-speedup, :string-rope-speedup, :byte-rope-speedup.
   Missing cells are nil (e.g. generic rope has no 'insert' group)."
  [rows sizes]
  (let [by-key  (group-by (juxt :size :group :variant) rows)
        max-n   (apply max sizes)
        lookup  (fn [size group variant]
                  (:mean-ns (first (by-key [size group (keyword variant)]))))
        speedup (fn [variant group-name]
                  (when group-name
                    (let [{:keys [oc peer]} (rope-family-variant-baseline variant group-name)
                          oc-ns   (lookup max-n (keyword group-name) oc)
                          peer-ns (lookup max-n (keyword group-name) peer)]
                      (when (and oc-ns peer-ns (pos? (double oc-ns)))
                        (/ (double peer-ns) (double oc-ns))))))]
    (->> rope-family-ops
         (map (fn [{:keys [label rope string byte]}]
                {:label               label
                 :size                max-n
                 :rope-speedup        (speedup :rope rope)
                 :string-rope-speedup (speedup :string-rope string)
                 :byte-rope-speedup   (speedup :byte-rope byte)}))
         ;; Only include rows that have at least one variant speedup
         (filter (fn [{:keys [rope-speedup string-rope-speedup byte-rope-speedup]}]
                   (some some? [rope-speedup string-rope-speedup byte-rope-speedup])))
         vec)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Regressions (baseline comparison)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn regression-report
  [target-rows baseline-rows]
  (when baseline-rows
    (let [baseline-map (into {}
                             (map (juxt (juxt :size :group :variant) identity))
                             baseline-rows)]
      (->> target-rows
           (keep (fn [row]
                   (when-let [old (baseline-map [(:size row) (:group row) (:variant row)])]
                     (let [ratio (/ (double (:mean-ns row))
                                    (double (:mean-ns old)))
                           pct   (* 100.0 (dec ratio))]
                       {:size        (:size row)
                        :group       (:group row)
                        :variant     (:variant row)
                        :old-mean-ns (:mean-ns old)
                        :new-mean-ns (:mean-ns row)
                        :ratio       ratio
                        :delta-pct   pct
                        :status      (cond
                                       (>= ratio 1.25) :major-regression
                                       (>= ratio 1.10) :regression
                                       (<= ratio 0.80) :major-improvement
                                       (<= ratio 0.90) :improvement
                                       :else :unchanged)}))))
           vec))))

(defn noisy-rows
  [rows]
  (->> rows
       (map noisy-row)
       (filter :noisy?)
       (sort-by (juxt #(or (:ci-width-ratio %) 0.0)
                      #(or (:cv %) 0.0))
                #(compare %2 %1))
       vec))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executive Summary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn executive-summary
  [rows baseline-rows]
  (let [scorecard (ordered-scorecard rows)
        noisy     (noisy-rows rows)
        regress   (regression-report rows baseline-rows)
        wins      (significant-wins scorecard)
        losses    (significant-losses scorecard)]
    {:case-count       (count rows)
     :benchmark-groups (count (set (map :group rows)))
     :sizes            (sort (set (map :size rows)))
     :ordered-wins     (count (filter #(= :win (:status %)) scorecard))
     :ordered-losses   (count (filter #(= :loss (:status %)) scorecard))
     :ordered-parity   (count (filter #(= :parity (:status %)) scorecard))
     :best-win         (when (seq wins) (:speedup (first wins)))
     :best-win-group   (when (seq wins) (:group (first wins)))
     :worst-loss       (when (seq losses) (:slowdown (first losses)))
     :worst-loss-group (when (seq losses) (:group (first losses)))
     :noisy-cases      (count noisy)
     :has-baseline?    (boolean baseline-rows)
     :regressions      (count (filter #(#{:regression :major-regression} (:status %)) regress))
     :improvements     (count (filter #(#{:improvement :major-improvement} (:status %)) regress))}))
