(ns oc-scripts.shell
  "Shell helpers for babashka support scripts."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [oc-scripts.common :as common]))

(defn run-result
  [& args]
  (apply sh (concat args [:dir common/root])))

(defn run!
  [& args]
  (let [{:keys [out exit err]} (apply run-result args)]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println (str "Command failed: " (str/join " " args)))
        (when-not (str/blank? err)
          (println (str/trim err))))
      (System/exit exit))
    (common/trim-out out)))

(defn tool-available?
  [name]
  (let [{:keys [out exit]} (sh "which" name)]
    (and (zero? exit)
         (not (str/blank? out)))))

(defn ensure-tool!
  [name install-hint]
  (when-not (tool-available? name)
    (binding [*out* *err*]
      (println (str "Error: " name " not found."))
      (when install-hint
        (println install-hint)))
    (System/exit 1)))
