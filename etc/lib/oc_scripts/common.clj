(ns oc-scripts.common
  "Shared support for babashka scripts under etc/."
  (:require [babashka.fs :as fs]
            [clj-figlet.core :as fig]
            [clojure.string :as str]))

(defn- find-root
  [start]
  (or (some (fn [dir]
              (when (fs/exists? (fs/path dir "project.clj"))
                (str dir)))
            (take-while some? (iterate fs/parent start)))
      (throw (ex-info "Could not locate project root from script path."
               {:start (str start)}))))

(def root
  (find-root (fs/parent (fs/absolutize *file*))))

(def run-id
  (str (random-uuid)))

(defn path
  [& parts]
  (str (apply fs/path root parts)))

(defn trim-out
  [s]
  (some-> s str/trim))

(defn banner
  [title]
  (println (fig/render "small" title)))

(defn rule
  []
  (println (apply str (repeat 72 "─"))))

(defn section
  [title]
  (println)
  (rule)
  (println (str "  " title))
  (rule))
