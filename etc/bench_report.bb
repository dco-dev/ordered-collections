#!/usr/bin/env bb

(require '[clojure.string :as str]
         '[bench-analyze :as analyze]
         '[bench-files :as files]
         '[bench-parse :as parse]
         '[bench-render :as render])

(defn -main [& args]
  (let [opts (files/parse-args args)]
    (if (:help opts)
      (println (files/usage))
      (let [{:keys [file baseline] :as opts} (files/resolve-files opts)
            target-result   (parse/load-result file)
            target-rows     (parse/result-rows target-result)
            target-summary  (parse/result-summary target-result target-rows)
            baseline-result (some-> baseline parse/load-result)
            baseline-rows   (some-> baseline-result parse/result-rows)
            baseline-summary (some-> baseline-result
                                     (parse/result-summary
                                       (parse/result-rows baseline-result)))
            scorecard       (analyze/ordered-scorecard target-rows)
            sizes           (sort (set (map :size target-rows)))
            headlines       (analyze/headline-wins target-rows sizes)
            parity          (analyze/parity-cases scorecard)
            wins            (analyze/significant-wins scorecard)
            losses          (analyze/significant-losses scorecard)
            categories      (analyze/category-summary scorecard)
            rope-family     (analyze/rope-family-summary target-rows sizes)
            regressions     (->> (analyze/regression-report target-rows baseline-rows)
                                 (filter #(#{:regression :major-regression} (:status %)))
                                 (sort-by :ratio >))
            improvements    (->> (analyze/regression-report target-rows baseline-rows)
                                 (filter #(#{:improvement :major-improvement} (:status %)))
                                 (sort-by :ratio))
            summary         (analyze/executive-summary target-rows baseline-rows)]
        (render/render-header opts target-summary baseline-summary)
        (render/render-executive-summary summary)
        (render/render-headline-wins headlines sizes)
        (render/render-category-summary categories)
        (render/render-rope-family rope-family)
        (render/render-significant-wins wins opts)
        (render/render-parity parity opts)
        (render/render-significant-losses losses opts)
        ;; Full Scorecard, Regressions, and Improvements are for interactive
        ;; A/B review. They are noisy for an outside reader of the committed
        ;; doc/report.txt snapshot, so --publish suppresses them.
        (when-not (:publish opts)
          (render/render-scorecard
            (sort-by (juxt (comp #(.indexOf analyze/category-order %) :category)
                           #(- (:speedup %)))
                     scorecard)
            opts)
          (render/render-regressions "Regressions" regressions opts)
          (render/render-regressions "Improvements" improvements opts))
        (println)))))

(apply -main *command-line-args*)
