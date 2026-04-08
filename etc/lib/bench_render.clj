(ns bench-render
  "Terminal rendering for benchmark reports."
  (:require [clojure.string :as str]
            [common :as common]
            [report :as report]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Column Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def scorecard-cols
  [{:width 8} {:width 24} {:width 18} {:width 18} {:width 10 :align :right}
   {:width 10 :align :right} {:width 10 :align :right} {:width 8}])

(def loss-cols
  [{:width 8} {:width 22} {:width 18} {:width 18} {:width 12 :align :right} {:width 0}])

(def delta-cols
  [{:width 8} {:width 22} {:width 20} {:width 10 :align :right}
   {:width 10 :align :right} {:width 10 :align :right} {:width 14}])

(def noise-cols
  [{:width 8} {:width 20} {:width 18} {:width 8 :align :right}
   {:width 8 :align :right} {:width 12}])

(def parity-cols
  [{:width 24} {:width 18} {:width 18} {:width 10 :align :right}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- limit
  [xs {:keys [top all]}]
  (if all xs (take (or top 12) xs)))

(defn- status-label
  [status]
  (-> status name (str/replace "-" " ") str/upper-case))

(defn- speedup-str
  "Format a speedup ratio as '12.3x' for wins, '2.5x slower' for losses."
  [speedup]
  (if (>= speedup 1.0)
    (format "%.1fx" (double speedup))
    (format "%.1fx slower" (/ 1.0 (double speedup)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Header
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- short-rev [rev]
  (when rev (subs rev 0 (min 10 (count rev)))))

(defn render-header
  [{:keys [file baseline]} target-summary baseline-summary]
  (common/banner "bench report")
  (common/section "Run")
  (report/row-text "File" file)
  (when baseline (report/row-text "Baseline" baseline))
  (report/row-text "Timestamp" (:timestamp target-summary))
  (report/row-text "Mode" (:mode target-summary))
  (report/row-text "Artifact version" (or (:artifact-version target-summary) "legacy"))
  (report/row-text "Git branch" (:git-branch target-summary))
  (report/row-text "Git rev" (short-rev (:git-rev target-summary)))
  (report/row-text "Sizes" (pr-str (:sizes target-summary)))
  (report/row "Benchmark cases" (:case-count target-summary))
  (report/row "Benchmark groups" (:group-count target-summary))
  (common/section "Platform")
  (report/row-text "Host" (:hostname target-summary))
  (report/row-text "OS" (str (:os-name target-summary) " "
                             (:os-version target-summary) " "
                             (:os-arch target-summary)))
  (report/row "Processors" (:processors target-summary))
  (report/row-text "Java" (str (:java-version target-summary)
                               " (" (:java-vendor target-summary) ")"))
  (report/row-text "VM" (:java-vm target-summary))
  (report/row "Max memory (MB)" (:max-memory-mb target-summary))
  (report/row "Heap max (MB)" (:heap-max-mb target-summary))
  (report/row "Heap committed (MB)" (:heap-committed-mb target-summary))
  (report/row "Heap used (MB)" (:heap-used-mb target-summary))
  (when baseline-summary
    (common/section "Baseline Run")
    (report/row-text "File" (:file baseline-summary))
    (report/row-text "Timestamp" (:timestamp baseline-summary))
    (report/row-text "Mode" (:mode baseline-summary))
    (report/row-text "Git branch" (:git-branch baseline-summary))
    (report/row-text "Git rev" (short-rev (:git-rev baseline-summary)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executive Summary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-executive-summary
  [summary]
  (common/section "Summary")
  (report/paragraph
    (str (:case-count summary) " benchmarks across "
         (:benchmark-groups summary) " groups at N="
         (str/join ", " (map str (:sizes summary))) ". "
         (:ordered-wins summary) " wins, "
         (:ordered-parity summary) " at parity, "
         (:ordered-losses summary) " losses."
         (when (:best-win summary)
           (str " Best win: " (format "%.1fx" (double (:best-win summary)))
                " on " (name (:best-win-group summary)) "."))
         (when (:worst-loss summary)
           (str " Worst loss: " (format "%.1fx" (double (:worst-loss summary)))
                " slower on " (name (:worst-loss-group summary)) "."))
         (when (:has-baseline? summary)
           (str " " (:regressions summary) " regressions, "
                (:improvements summary) " improvements vs baseline.")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Headline Wins — scaling table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-headline-wins
  "Render headline speedups grouped by section, matching README structure."
  [headlines sizes]
  (when (seq headlines)
    (common/section "Headline Performance")
    (let [size-labels (mapv #(str "N=" %) sizes)
          cols (into [{:width 22}]
                     (mapv (fn [_] {:width 12 :align :right}) sizes))
          sections (partition-by :section headlines)]
      (doseq [section-rows sections]
        (let [section-title (:section (first section-rows))]
          (println)
          (println (str "  " section-title))
          (apply report/table-row cols (into [""] size-labels))
          (doseq [{:keys [label sizes]} section-rows]
            (let [cells (mapv (fn [entry]
                                (if entry
                                  (let [s (:speedup entry)]
                                    (if (>= s 1.0)
                                      (format "**%.1fx**" (double s))
                                      (format "%.1fx" (double s))))
                                  "—"))
                              sizes)]
              (apply report/table-row cols (into [label] cells)))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-parity
  [parity-rows opts]
  (when (seq parity-rows)
    (common/section "At Parity")
    (apply report/table-row parity-cols ["Group" "OC Variant" "Peer" "Ratio"])
    ;; Deduplicate by group — show one row per group at the median size
    (let [by-group (group-by :group parity-rows)
          deduped  (map (fn [[_ rows]] (first (sort-by :size rows))) by-group)]
      (doseq [{:keys [group ordered-variant peer-variant speedup]} (limit (sort-by :group deduped) opts)]
        (apply report/table-row parity-cols
               [(name group) (name ordered-variant) (name peer-variant)
                (format "~%.2fx" (double speedup))])))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Significant Losses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-significant-losses
  [losses opts]
  (when (seq losses)
    (common/section "Significant Losses")
    (apply report/table-row loss-cols ["Size" "Group" "OC Variant" "Peer" "Slowdown" "Context"])
    (doseq [{:keys [size group ordered-variant peer-variant slowdown context]}
            (limit losses opts)]
      (apply report/table-row loss-cols
             [(str size)
              (name group)
              (name ordered-variant)
              (name peer-variant)
              (format "%.1fx slower" (double slowdown))
              (or context "")]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Full Scorecard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-scorecard
  [scorecard opts]
  (common/section "Full Scorecard")
  (apply report/table-row scorecard-cols
         ["Size" "Group" "OC Variant" "Peer" "OC Time" "Peer Time" "Speedup" "Status"])
  (doseq [{:keys [size group ordered-variant peer-variant
                   ordered-mean-ns peer-mean-ns speedup status]}
          (limit scorecard opts)]
    (apply report/table-row scorecard-cols
           [(str size)
            (name group)
            (name ordered-variant)
            (name peer-variant)
            (report/format-ns ordered-mean-ns)
            (report/format-ns peer-mean-ns)
            (speedup-str speedup)
            (status-label status)])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Regressions / Improvements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-regressions
  [title rows opts]
  (when (seq rows)
    (common/section title)
    (apply report/table-row delta-cols ["Size" "Group" "Variant" "Old" "New" "Delta" "Status"])
    (doseq [{:keys [size group variant old-mean-ns new-mean-ns delta-pct status]}
            (limit rows opts)]
      (apply report/table-row delta-cols
             [(str size)
              (name group)
              (name variant)
              (report/format-ns old-mean-ns)
              (report/format-ns new-mean-ns)
              (report/format-pct delta-pct)
              (status-label status)]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Noise Watchlist
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-noise
  [rows opts]
  (when (seq rows)
    (common/section "Noise Watchlist")
    (apply report/table-row noise-cols ["Size" "Group" "Variant" "CV" "CI Width" "Reasons"])
    (doseq [{:keys [size group variant cv ci-width-ratio noise-reasons]}
            (limit rows opts)]
      (apply report/table-row noise-cols
             [(str size)
              (name group)
              (name variant)
              (if cv (format "%.3f" cv) "-")
              (if ci-width-ratio (format "%.3f" ci-width-ratio) "-")
              (str/join ", " (map name noise-reasons))]))))
