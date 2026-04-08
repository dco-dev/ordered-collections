#!/usr/bin/env bb

(require '[clojure.string :as str]
         '[oc-scripts-bench-analyze :as analyze]
         '[oc-scripts-bench-files :as files]
         '[oc-scripts-bench-parse :as parse]
         '[oc-scripts-bench-render :as render])

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
            losses          (analyze/significant-losses scorecard)
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
        (render/render-parity parity opts)
        (render/render-significant-losses losses opts)
        (render/render-scorecard
          (sort-by (juxt (comp #(.indexOf analyze/category-order %) :category)
                         #(- (:speedup %)))
                   scorecard)
          opts)
        (render/render-regressions "Regressions" regressions opts)
        (render/render-regressions "Improvements" improvements opts)
        (println)))))

(apply -main *command-line-args*)
