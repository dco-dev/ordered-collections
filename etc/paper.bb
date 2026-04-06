#!/usr/bin/env bb

;; Generate doc/paper.pdf from doc/paper.md
;; Usage: lein paper
;; Requires: pandoc, weasyprint (brew install pandoc weasyprint)

(require '[babashka.fs :as fs]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/absolutize (fs/parent *file*)))))
(defn path [& parts] (str (apply fs/path root parts)))

(def src (path "doc" "concept" "concept.md"))
(def out (path "doc" "concept" "concept.pdf"))
(def tmp (path "doc" "concept" ".concept-tmp.html"))

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

(def css-file (str (fs/create-temp-file {:prefix "paper-css-" :suffix ".html"})))
(spit css-file css)

(defn check-tool [name]
  (when (str/blank? (:out (sh "which" name)))
    (println (str "Error: " name " not found. Install with: brew install " name))
    (System/exit 1)))

(check-tool "pandoc")
(check-tool "weasyprint")

;; pandoc: markdown -> standalone HTML with embedded CSS
;; Use --resource-path so relative image refs resolve correctly
(let [{:keys [exit err]} (sh "pandoc" src "-o" tmp
                           "--standalone"
                           "--resource-path" (path "doc")
                           "-H" css-file)]
  (when-not (zero? exit)
    (println "pandoc failed:" err)
    (System/exit 1)))

;; weasyprint: HTML -> PDF (run from doc/ so relative image paths resolve)
(let [{:keys [exit err]} (sh "weasyprint" tmp out :dir (path "doc" "concept"))]
  (when-not (zero? exit)
    (println "weasyprint failed:" err)
    (System/exit 1)))

(fs/delete-if-exists tmp)
(fs/delete-if-exists css-file)

(println (str "Generated: " out))
