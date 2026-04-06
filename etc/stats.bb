#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[clj-figlet.core :as fig]
         '[clj-format.core :refer [clj-format]]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

;; All paths relative to the project root (one level up from etc/)
(def root (str (fs/parent (fs/absolutize (fs/parent *file*)))))
(defn path [& parts] (str (apply fs/path root parts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sh! [& args]
  (let [{:keys [out exit]} (apply sh (concat args [:dir root]))]
    (when (zero? exit)
      (str/trim out))))

(defn count-lines [p]
  (count (str/split-lines (slurp p))))

(defn clj-files [dir]
  (->> (fs/glob (path dir) "**.clj")
       (mapv str)))

(defn rule []
  (println (apply str (repeat 72 "─"))))

(defn section [title]
  (println)
  (rule)
  (println (str "  " title))
  (rule))

(def row-num-fmt  [[:str {:width 42}] [:str {:width 12 :pad :left}] :nl])
(def row-text-fmt [[:str {:width 24}] :str :nl])
(def row3-fmt     [[:str {:width 34}] [:str {:width 14 :pad :left}] [:str {:width 14 :pad :left}] :nl])

(defn row [label value]
  (print (clj-format nil row-num-fmt label (str value))))

(defn row-text [label value]
  (print (clj-format nil row-text-fmt label (str value))))

(defn row3 [a b c]
  (print (clj-format nil row3-fmt a (str b) (str c))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Collection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Source files
(def src-files  (clj-files "src"))
(def test-files (clj-files "test"))
(def doc-files  (->> (fs/glob (path "doc") "**.md") (mapv str)))

;; Line counts
(defn file-lines [files]
  (reduce + 0 (map count-lines files)))

(def src-lines  (file-lines src-files))
(def test-lines (file-lines test-files))
(def doc-lines  (+ (file-lines doc-files)
                   (count-lines (path "README.md"))
                   (count-lines (path "CHANGES.md"))))

;; Test stats (count test deftests and defspecs without running them)
(def test-count
  (->> (clj-files "test")
       (mapcat #(re-seq #"\(deftest\s+|\(defspec\s+" (slurp %)))
       count))

;; Git stats
(def commit-count    (some-> (sh! "git" "log" "--oneline") str/split-lines count))
(def first-commit    (sh! "git" "log" "--format=%as" "--reverse" "--" "."))
(def latest-commit   (sh! "git" "log" "-1" "--format=%as"))
(def branch          (sh! "git" "branch" "--show-current"))
(def short-sha       (sh! "git" "log" "-1" "--format=%h"))
(def dirty?          (not (str/blank? (sh! "git" "status" "--porcelain"))))
(def contributors    (some-> (sh! "git" "log" "--format=%aN") str/split-lines distinct))
(def tags            (some-> (sh! "git" "tag" "--list") str/split-lines))

;; Churn: most-modified files
(def churn-data
  (some->> (sh! "git" "log" "--format=" "--name-only" "--diff-filter=M" "--" "src/")
           str/split-lines
           (remove str/blank?)
           frequencies
           (sort-by val >)
           (take 5)))

;; Project version
(def project-version
  (some->> (slurp (path "project.clj"))
           (re-find #"defproject\s+\S+\s+\"([^\"]+)\"")
           second))

;; Collection types
(def type-files (clj-files "src/ordered_collections/types"))
(def type-count (count (filter #(not (or (str/includes? % "shared")
                                         (str/includes? % "interop")))
                                type-files)))

;; Protocols
(def protocol-count
  (some->> (slurp (path "src/ordered_collections/protocol.clj"))
           (re-seq #"defprotocol\s+")
           count))

;; Public API functions
(def public-fn-count
  (let [core (slurp (path "src/ordered_collections/core.clj"))]
    (+ (count (re-seq #"\(defn\s+" core))
       (count (re-seq #"\(defalias\s+" core)))))

;; Node types
(def node-type-count
  (some->> (slurp (path "src/ordered_collections/kernel/node.clj"))
           (re-seq #"deftype\s+")
           count))

;; clj-kondo analysis (exit code 2/3 = lint warnings, not failure)
(def kondo-available? (some? (sh! "clj-kondo" "--version")))

(def analysis
  (when kondo-available?
    (let [{:keys [out]} (sh "clj-kondo" "--lint" (path "src")
                            "--config" "{:output {:analysis {:var-definitions {:meta true}} :format :edn}}"
                            :dir root)]
      (some-> out clojure.edn/read-string :analysis))))

(def var-defs      (:var-definitions analysis))
(def ns-defs       (:namespace-definitions analysis))
(def var-usages    (:var-usages analysis))

;; Vars per namespace (top 5)
(def vars-per-ns
  (when var-defs
    (->> var-defs
         (group-by :ns)
         (map (fn [[ns vars]] [(name ns) (count vars)]))
         (sort-by second >)
         (take 5))))

;; Public vs private vars
(def public-var-count  (count (remove :private var-defs)))
(def private-var-count (count (filter :private var-defs)))

;; Namespace fan-in (who depends on me)
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

;; Environment
(def java-version (System/getProperty "java.version"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Report
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(println (fig/render "small" "ordered-collections"))

(section "Project")
(row-text "Version"          project-version)
(row-text "Branch"           (str branch " (" short-sha (when dirty? ", dirty") ")"))
(row-text "First commit"     (first (str/split-lines (or first-commit ""))))
(row-text "Latest commit"    latest-commit)
(row-text "Total commits"    commit-count)
(row-text "Contributors"     (str/join ", " contributors))
(when (seq tags)
  (row-text "Tags"           (str/join ", " tags)))

(section "Architecture")
(row "Collection types"     type-count)
(row "Protocols"            protocol-count)
(row "Node types"           node-type-count)
(row "Public API functions"  public-fn-count)
(when analysis
  (row "Total var definitions" (count var-defs))
  (row "  public"              public-var-count)
  (row "  private"             private-var-count))

(section "Codebase Size")
(row3 "" "Files" "Lines")
(row3 "Source"              (count src-files) src-lines)
(row3 "Tests & benchmarks"  (count test-files) test-lines)
(row3 "Documentation (md)"  (+ (count doc-files) 2) doc-lines)
(println (apply str (repeat 62 "─")))
(row3 "Total"
      (+ (count src-files) (count test-files) (count doc-files) 2)
      (+ src-lines test-lines doc-lines))

(section "Largest Source Files")
(doseq [[f n] (->> src-files
                   (map (fn [f] [(last (str/split f #"/")) (count-lines f)]))
                   (sort-by second >)
                   (take 5))]
  (row f n))

(section "Tests")
(row "Test definitions (deftest + defspec)" test-count)

(when (seq vars-per-ns)
  (section "Vars per Namespace (top 5)")
  (doseq [[ns n] vars-per-ns]
    (row ns n)))

(when (seq ns-fan-in)
  (section "Most-Depended-On Namespaces")
  (doseq [[ns n] ns-fan-in]
    (row ns n)))

(section "Most-Modified Source Files (git churn)")
(doseq [[f n] churn-data]
  (row (last (str/split f #"/")) n))

(section "Environment")
(row-text "Java"             java-version)
(row-text "Babashka"         (sh! "bb" "--version"))
(row-text "Platform"         (str (System/getProperty "os.name") " "
                                  (System/getProperty "os.arch")))

(println)
