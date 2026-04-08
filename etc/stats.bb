#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[common :as common]
         '[report :as report]
         '[shell :as shell])

(defn count-lines
  [p]
  (count (str/split-lines (slurp p))))

(defn clj-files
  [dir]
  (->> (fs/glob (common/path dir) "**.clj")
       (mapv str)))

(defn file-lines
  [files]
  (reduce + 0 (map count-lines files)))

(def src-files  (clj-files "src"))
(def test-files (clj-files "test"))
(def doc-files  (->> (fs/glob (common/path "doc") "**.md") (mapv str)))

(def src-lines  (file-lines src-files))
(def test-lines (file-lines test-files))
(def doc-lines  (+ (file-lines doc-files)
                   (count-lines (common/path "README.md"))
                   (count-lines (common/path "CHANGES.md"))))

(def test-count
  (->> test-files
       (mapcat #(re-seq #"\(deftest\s+|\(defspec\s+" (slurp %)))
       count))

(def commit-count    (some-> (shell/run! "git" "log" "--oneline") str/split-lines count))
(def first-commit    (shell/run! "git" "log" "--format=%as" "--reverse" "--" "."))
(def latest-commit   (shell/run! "git" "log" "-1" "--format=%as"))
(def branch          (shell/run! "git" "branch" "--show-current"))
(def short-sha       (shell/run! "git" "log" "-1" "--format=%h"))
(def dirty?          (not (str/blank? (shell/run! "git" "status" "--porcelain"))))
(def contributors    (some-> (shell/run! "git" "log" "--format=%aN") str/split-lines distinct))
(def tags            (some-> (shell/run! "git" "tag" "--list") str/split-lines))

(def churn-data
  (some->> (shell/run! "git" "log" "--format=" "--name-only" "--diff-filter=M" "--" "src/")
           str/split-lines
           (remove str/blank?)
           frequencies
           (sort-by val >)
           (take 5)))

(def project-version
  (some->> (slurp (common/path "project.clj"))
           (re-find #"defproject\s+\S+\s+\"([^\"]+)\"")
           second))

(def type-files (clj-files "src/ordered_collections/types"))
(def type-count
  (count (filter #(not (or (str/includes? % "shared")
                           (str/includes? % "interop")))
                 type-files)))

(def protocol-count
  (some->> (slurp (common/path "src/ordered_collections/protocol.clj"))
           (re-seq #"defprotocol\s+")
           count))

(def public-fn-count
  (let [core (slurp (common/path "src/ordered_collections/core.clj"))]
    (+ (count (re-seq #"\(defn\s+" core))
       (count (re-seq #"\(defalias\s+" core)))))

(def node-type-count
  (some->> (slurp (common/path "src/ordered_collections/kernel/node.clj"))
           (re-seq #"deftype\s+")
           count))

(def kondo-available?
  (shell/tool-available? "clj-kondo"))

(def analysis
  (when kondo-available?
    (let [{:keys [out]}
          (shell/run-result "clj-kondo" "--lint" (common/path "src")
            "--config" "{:output {:analysis {:var-definitions {:meta true}} :format :edn}}")]
      (some-> out edn/read-string :analysis))))

(def var-defs      (:var-definitions analysis))
(def ns-defs       (:namespace-definitions analysis))
(def var-usages    (:var-usages analysis))

(def vars-per-ns
  (when var-defs
    (->> var-defs
         (group-by :ns)
         (map (fn [[ns vars]] [(name ns) (count vars)]))
         (sort-by second >)
         (take 5))))

(def public-var-count  (count (remove :private var-defs)))
(def private-var-count (count (filter :private var-defs)))

(def ns-fan-in
  (when var-usages
    (let [src-nses (set (map :name ns-defs))]
      (->> var-usages
           (filter #(and (src-nses (:to %))
                         (not= (:from %) (:to %))
                         (src-nses (:from %))))
           (map :to)
           frequencies
           (map (fn [[ns n]] [(name ns) n]))
           (sort-by second >)
           (take 5)))))

(def java-version (System/getProperty "java.version"))

(defn -main []
  (common/banner "ordered-collections")

  (common/section "Project")
  (report/row-text "Version"       project-version)
  (report/row-text "Branch"        (str branch " (" short-sha (when dirty? ", dirty") ")"))
  (report/row-text "Run ID"        common/run-id)
  (report/row-text "First commit"  (first (str/split-lines (or first-commit ""))))
  (report/row-text "Latest commit" latest-commit)
  (report/row-text "Total commits" commit-count)
  (report/row-text "Contributors"  (str/join ", " contributors))
  (when (seq tags)
    (report/row-text "Tags" (str/join ", " tags)))

  (common/section "Architecture")
  (report/row "Collection types"     type-count)
  (report/row "Protocols"            protocol-count)
  (report/row "Node types"           node-type-count)
  (report/row "Public API functions" public-fn-count)
  (when analysis
    (report/row "Total var definitions" (count var-defs))
    (report/row "  public"              public-var-count)
    (report/row "  private"             private-var-count))

  (common/section "Codebase Size")
  (report/row3 "" "Files" "Lines")
  (report/row3 "Source"             (count src-files) src-lines)
  (report/row3 "Tests & benchmarks" (count test-files) test-lines)
  (report/row3 "Documentation (md)" (+ (count doc-files) 2) doc-lines)
  (println (apply str (repeat 62 "─")))
  (report/row3 "Total"
    (+ (count src-files) (count test-files) (count doc-files) 2)
    (+ src-lines test-lines doc-lines))

  (common/section "Largest Source Files")
  (doseq [[f n] (->> src-files
                     (map (fn [f] [(last (str/split f #"/")) (count-lines f)]))
                     (sort-by second >)
                     (take 5))]
    (report/row f n))

  (common/section "Tests")
  (report/row "Test definitions (deftest + defspec)" test-count)

  (when (seq vars-per-ns)
    (common/section "Vars per Namespace (top 5)")
    (doseq [[ns n] vars-per-ns]
      (report/row ns n)))

  (when (seq ns-fan-in)
    (common/section "Most-Depended-On Namespaces")
    (doseq [[ns n] ns-fan-in]
      (report/row ns n)))

  (common/section "Most-Modified Source Files (git churn)")
  (doseq [[f n] churn-data]
    (report/row (last (str/split f #"/")) n))

  (common/section "Environment")
  (report/row-text "Java"     java-version)
  (report/row-text "Babashka" (shell/run! "bb" "--version"))
  (report/row-text "Platform" (str (System/getProperty "os.name") " "
                                   (System/getProperty "os.arch")))
  (println))

(-main)
