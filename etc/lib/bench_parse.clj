(ns bench-parse
  "Benchmark result parsing and normalization."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn leaf-result?
  [x]
  (and (map? x) (contains? x :mean-ns)))

(defn- group-key
  [group-path]
  (keyword (str/join "/" (map name group-path))))

(defn load-result
  [path]
  (assoc (edn/read-string (slurp path))
         :file path))

(defn result-rows
  [{:keys [benchmarks artifact-version timestamp mode file system]}]
  (letfn [(walk [size path node]
            (if (leaf-result? node)
              [{:file              file
                :artifact-version  artifact-version
                :timestamp         timestamp
                :mode              mode
                :git-rev           (:git-rev system)
                :git-branch        (:git-branch system)
                :size              size
                :group-path        (vec (butlast path))
                :group             (group-key (butlast path))
                :variant           (last path)
                :mean-ns           (:mean-ns node)
                :sample-mean-ns    (:sample-mean-ns node)
                :stdev-ns          (:stdev-ns node)
                :sample-count      (:sample-count node)
                :mean-ci-ns        (:mean-ci-ns node)
                :outliers          (:outliers node)
                :raw               node}]
              (mapcat (fn [[k v]] (walk size (conj path k) v)) node)))]
    (vec
      (mapcat (fn [[size groups]]
                (mapcat (fn [[group node]]
                          (walk size [group] node))
                        groups))
              benchmarks))))

(defn result-summary
  "Summarize a benchmark result. Accepts pre-parsed rows to avoid double-parsing."
  ([result]
   (result-summary result (result-rows result)))
  ([{:keys [artifact-version sizes mode duration-ms timestamp file system]} rows]
   {:file              file
    :artifact-version  artifact-version
    :mode              mode
    :timestamp         timestamp
    :duration-ms       duration-ms
    :sizes             sizes
    :group-count       (count (set (map :group rows)))
    :case-count        (count rows)
    :git-rev           (:git-rev system)
    :git-branch        (:git-branch system)
    :git-dirty?        (:git-dirty? system)
    :hostname          (:hostname system)
    :java-version      (:java-version system)
    :java-vm           (:java-vm system)
    :java-vendor       (:java-vendor system)
    :os-name           (:os-name system)
    :os-version        (:os-version system)
    :os-arch           (:os-arch system)
    :processors        (:processors system)
    :max-memory-mb     (:max-memory-mb system)
    :heap-max-mb       (:heap-max-mb system)
    :heap-committed-mb (:heap-committed-mb system)
    :heap-used-mb      (:heap-used-mb system)}))
