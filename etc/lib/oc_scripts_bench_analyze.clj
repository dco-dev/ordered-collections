(ns oc-scripts-bench-analyze
  "Benchmark analysis: ranking, scorecard, regressions, category classification."
  (:require [clojure.string :as str]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OC Variant Detection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private oc-prefixes
  ["ordered-set" "ordered-map" "long-ordered" "string-ordered"
   "interval-set" "interval-map" "range-map" "rope"])

(defn- oc-variant?
  [variant]
  (let [s (name variant)]
    (boolean (some #(str/starts-with? s %) oc-prefixes))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Category Classification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def category-order
  [:set-algebra :construction :lookup :iteration :fold :split
   :equality :rank :string :interval :rope :other])

(def ^:private category-patterns
  [[:set-algebra   #"union|intersection|difference"]
   [:construction  #"construction"]
   [:lookup        #"lookup"]
   [:iteration     #"iteration"]
   [:fold          #"fold|reduce"]
   [:split         #"split"]
   [:equality      #"equal|different|size-different"]
   [:rank          #"rank"]
   [:string        #"string"]
   [:interval      #"interval"]
   [:rope          #"rope"]])

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
   ;; Other operations
   {:pattern #"^set-construction$" :oc :ordered-set :peer :sorted-set  :label "Construction" :section "Other Operations"}
   {:pattern #"^set-lookup$"       :oc :ordered-set :peer :sorted-set  :label "Lookup"       :section "Other Operations"}
   {:pattern #"^split$"            :oc :ordered-set :peer :data-avl    :label "Split"        :section "Other Operations"}
   {:pattern #"^set-fold$"         :oc :ordered-set-fold :peer :sorted-set-fold :label "Fold" :section "Other Operations"}
   ;; Rope vs PersistentVector (matches bench_runner groups)
   {:pattern #"^rope-repeated-edits$"  :oc :rope   :peer :vector  :label "200 Random Edits"  :section "Rope vs PersistentVector"}
   {:pattern #"^rope-splice$"          :oc :rope   :peer :vector  :label "Single Splice"     :section "Rope vs PersistentVector"}
   {:pattern #"^rope-concat$"          :oc :rope   :peer :vector  :label "Concat Pieces"     :section "Rope vs PersistentVector"}
   {:pattern #"^rope-chunk-iteration$" :oc :rope   :peer :vector  :label "Chunk Iteration"   :section "Rope vs PersistentVector"}
   {:pattern #"^rope-reduce$"          :oc :rope   :peer :vector  :label "Reduce (sum)"      :section "Rope vs PersistentVector"}
   {:pattern #"^rope-nth$"             :oc :rope   :peer :vector  :label "Random nth (1000)" :section "Rope vs PersistentVector"}])

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
