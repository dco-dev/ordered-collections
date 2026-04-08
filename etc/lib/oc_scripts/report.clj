(ns oc-scripts.report
  "Table-style reporting helpers built on clj-format."
  (:require [clj-format.core :refer [clj-format]]
            [clojure.string :as str]))

(defn- col-spec
  [{:keys [width align]}]
  [:str (cond-> {:width width}
          (= align :right) (assoc :pad :left))])

(defn- table-format
  [cols]
  (->> cols
       (map col-spec)
       (interpose " ")
       vec
       (#(conj % :nl))))

(def row-num-fmt
  (table-format [{:width 42} {:width 12 :align :right}]))

(def row-text-fmt
  (table-format [{:width 24} {:width 0}]))

(def row3-fmt
  (table-format [{:width 34} {:width 14 :align :right} {:width 14 :align :right}]))

(def paragraph-lines-fmt
  [:each :str :nl])

(defn emit
  [fmt & values]
  (print (apply clj-format nil fmt values)))

(defn table-row
  [cols & values]
  (apply emit (table-format cols) values))

(defn row
  [label value]
  (emit row-num-fmt label (str value)))

(defn row-text
  [label value]
  (emit row-text-fmt label (str value)))

(defn row3
  [a b c]
  (emit row3-fmt a (str b) (str c)))

(defn paragraph
  [text]
  (let [width 76
        words (remove str/blank? (str/split (str text) #"\s+"))
        lines (reduce (fn [acc word]
                        (let [line (peek acc)
                              candidate (if (str/blank? line)
                                          word
                                          (str line " " word))]
                          (if (<= (count candidate) width)
                            (conj (pop acc) candidate)
                            (conj acc word))))
                      [""]
                      words)
        lines (remove str/blank? lines)]
    (when (seq lines)
      (emit paragraph-lines-fmt lines))))

(defn format-ns
  [ns]
  (let [n (long (or ns 0))]
    (cond
      (>= n 1000000000) (format "%.2fs" (/ n 1e9))
      (>= n 1000000)    (format "%.2fms" (/ n 1e6))
      (>= n 1000)       (format "%.2fµs" (/ n 1e3))
      :else             (str n "ns"))))

(defn format-ratio
  [ratio]
  (format "%.2fx" (double ratio)))

(defn format-pct
  [pct]
  (format "%+.1f%%" (double pct)))
