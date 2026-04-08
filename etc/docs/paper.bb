#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[oc-scripts.common :as common]
         '[oc-scripts.report :as report]
         '[oc-scripts.shell :as shell])

(def src (common/path "doc" "concept" "concept.md"))
(def out (common/path "doc" "concept" "concept.pdf"))
(def tmp (common/path "doc" "concept" ".concept-tmp.html"))

(def css
  "<style>
  body { font-family: Georgia, serif; max-width: 48em; margin: 2em auto;
         line-height: 1.6; font-size: 11pt; color: #222; }
  h1 { font-size: 1.8em; text-align: center; margin-bottom: 0.2em; }
  h2 { font-size: 1.4em; border-bottom: 1px solid #ccc; padding-bottom: 0.3em;
       margin-top: 2em; }
  h3 { font-size: 1.15em; margin-top: 1.5em; }
  code { font-size: 0.9em; background: #f5f5f5; padding: 0.1em 0.3em; }
  pre { background: #f5f5f5; padding: 1em; font-size: 0.85em; line-height: 1.4; }
  table { border-collapse: collapse; margin: 1em 0; }
  th, td { border: 1px solid #ccc; padding: 0.4em 0.8em; text-align: left; }
  th { background: #f0f0f0; }
  hr { border: none; border-top: 2px solid #ccc; margin: 2em 0; }
  @page { size: letter; margin: 1in; }
</style>")

(defn -main []
  (common/banner "paper")
  (common/section "Inputs")
  (report/row-text "Source" src)
  (report/row-text "Output" out)
  (report/row-text "Run ID" common/run-id)

  (shell/ensure-tool! "pandoc" "Install with: brew install pandoc")
  (shell/ensure-tool! "weasyprint" "Install with: brew install weasyprint")

  (let [css-file (str (fs/create-temp-file {:prefix "paper-css-" :suffix ".html"}))]
    (spit css-file css)
    (let [{:keys [exit err]}
          (shell/run-result "pandoc" src "-o" tmp
            "--standalone"
            "--resource-path" (common/path "doc")
            "-H" css-file)]
      (when-not (zero? exit)
        (binding [*out* *err*]
          (println "pandoc failed:")
          (println (str err)))
        (System/exit exit)))
    (let [{:keys [exit err]}
          (shell/run-result "weasyprint" tmp out)]
      (when-not (zero? exit)
        (binding [*out* *err*]
          (println "weasyprint failed:")
          (println (str err)))
        (System/exit exit)))
    (fs/delete-if-exists tmp)
    (fs/delete-if-exists css-file))

  (common/section "Done")
  (report/row-text "Generated" out))

(-main)
