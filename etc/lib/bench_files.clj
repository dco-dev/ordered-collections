(ns bench-files
  "Benchmark result file discovery and argument parsing."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [common :as common]))

(defn bench-results-dir
  []
  (common/path "bench-results"))

(defn benchmark-files
  "Return timestamped benchmark EDN files in chronological order.
  Excludes non-timestamped files like string-rope-full.edn."
  []
  (->> (fs/glob (bench-results-dir) "*.edn")
       (map str)
       (filter #(re-find #"/\d{4}-\d{2}-\d{2}_" %))
       sort))

(defn latest-benchmark-file
  []
  (last (benchmark-files)))

(defn- ensure-file!
  [path]
  (when-not (and path (fs/exists? path))
    (throw (ex-info "Benchmark result file not found." {:path path})))
  path)

(defn parse-args
  [args]
  (loop [[arg & more] args
         opts {:top 30}]
    (cond
      (nil? arg) opts
      (#{"--help" "-h"} arg) (recur more (assoc opts :help true))
      (= "--all" arg)        (recur more (assoc opts :all true))
      (= "--file" arg)       (recur (rest more) (assoc opts :file (first more)))
      (= "--baseline" arg)   (recur (rest more) (assoc opts :baseline (first more)))
      (= "--top" arg)        (recur (rest more) (assoc opts :top (Long/parseLong (first more))))
      :else
      (throw (ex-info "Unknown argument." {:arg arg :args args})))))

(defn resolve-files
  [{:keys [file baseline] :as opts}]
  (let [target (or file (latest-benchmark-file))]
    (when-not target
      (throw (ex-info "No benchmark result files found." {:dir (bench-results-dir)})))
    ;; Auto-select the second-most-recent EDN as baseline when none is
    ;; specified, matching the auto-compare behavior in bench-runner.
    (let [auto-baseline
          (when-not baseline
            (let [all  (benchmark-files)
                  target-str (str target)]
              (->> all
                   (remove #(= % target-str))
                   last)))]
      (assoc opts
             :file (ensure-file! target)
             :baseline (some-> (or baseline auto-baseline) ensure-file!)))))

(defn usage []
  (str/join
    "\n"
    ["Usage: bb bench-report [options]"
     ""
     "Options:"
     "  --file PATH        Report on a specific benchmark result file"
     "  --baseline PATH    Compare against a baseline result file"
     "  --top N            Number of ranked rows to show (default 12)"
     "  --all              Show all ranked rows"
     "  --help             Show this help"]))
