(ns ordered-collections.bench-charts
  "Generate benchmark charts from EDN artifacts via XChart.

   Usage:
     lein bench-charts                     ; latest EDN → doc/charts/
     lein bench-charts --file path.edn     ; specific EDN

   Produces PNG files in doc/charts/ showing headline performance
   across collection types and cardinalities."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ordered-collections.bench-utils :as bu])
  (:import [org.knowm.xchart
            XYChart XYChartBuilder XYSeries XYSeries$XYSeriesRenderStyle
            CategoryChart CategoryChartBuilder CategorySeries CategorySeries$CategorySeriesRenderStyle
            BitmapEncoder BitmapEncoder$BitmapFormat]
           [org.knowm.xchart.style Styler$LegendPosition
                                   CategoryStyler AxesChartStyler]
           [java.awt Color Font BasicStroke]
           [java.io File])
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Style constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +width+  1400)
(def ^:private +height+ 750)

(def ^:private +blue+   (Color. 37 99 235))
(def ^:private +green+  (Color. 5 150 105))
(def ^:private +purple+ (Color. 124 58 237))
(def ^:private +red+    (Color. 220 38 38))
(def ^:private +orange+ (Color. 234 88 12))
(def ^:private +gray+   (Color. 156 163 175))

(def ^:private +series-colors+ [+blue+ +green+ +purple+ +orange+ +red+])
(def ^:private +line-width+ (BasicStroke. 2.5))

(def ^:private +label-font+ (Font. "SansSerif" Font/PLAIN 13))
(def ^:private +title-font+ (Font. "SansSerif" Font/BOLD 16))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data extraction helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- load-bench-edn [path]
  (edn/read-string (slurp path)))

(defn- bench-files []
  (->> (file-seq (io/file "bench-results"))
       (filter #(.isFile ^File %))
       (filter #(re-find #"\d{4}-\d{2}-\d{2}_.*\.edn$" (.getName ^File %)))
       (sort-by #(.getName ^File %))))

(defn- latest-bench-file []
  (last (bench-files)))

(defn- lookup
  "Find mean-ns for a specific [size group variant] in the EDN."
  [edn size group variant]
  (get-in edn [:benchmarks size group variant :mean-ns]))

(defn- speedup
  "Compute speedup: baseline-ns / oc-ns. Returns nil if either is missing."
  [edn size group oc-variant peer-variant]
  (let [oc   (lookup edn size group oc-variant)
        peer (lookup edn size group peer-variant)]
    (when (and oc peer (pos? oc))
      (/ (double peer) (double oc)))))

(defn- sizes-vec [edn]
  (sort (keys (:benchmarks edn))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; XChart helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- style-xy! [^XYChart chart & {:keys [log-x log-y y-label title]}]
  (let [styler (.getStyler chart)]
    (.setChartTitleFont styler +title-font+)
    (.setAxisTickLabelsFont styler +label-font+)
    (.setLegendFont styler +label-font+)
    (.setLegendPosition styler Styler$LegendPosition/InsideNW)
    (.setPlotGridLinesVisible styler true)
    (.setPlotGridLinesColor styler (Color. 230 230 230))
    (.setChartBackgroundColor styler Color/WHITE)
    (.setPlotBackgroundColor styler Color/WHITE)
    (.setPlotBorderVisible styler false)
    (when log-x (.setXAxisLogarithmic styler true))
    (when log-y (.setYAxisLogarithmic styler true))
    (when title (.setTitle chart title))
    (when y-label (.setYAxisTitle chart y-label))
    (.setXAxisTitle chart "Collection size (N)"))
  chart)

(defn- add-xy-series!
  [^XYChart chart series-name x-vals y-vals color]
  (let [^XYSeries series (.addSeries chart (str series-name)
                                     (double-array x-vals)
                                     (double-array y-vals))]
    (.setLineColor series color)
    (.setLineStyle series +line-width+)
    (.setMarkerColor series color)
    (.setXYSeriesRenderStyle series XYSeries$XYSeriesRenderStyle/Line)
    series))

(defn- add-parity-line!
  "Add a dashed 1.0x reference line."
  [^XYChart chart sizes]
  (let [^XYSeries series (.addSeries chart "1.0x (parity)"
                                     (double-array sizes)
                                     (double-array (repeat (count sizes) 1.0)))]
    (.setLineColor series +gray+)
    (.setLineStyle series (BasicStroke. 1.5
                                       BasicStroke/CAP_BUTT
                                       BasicStroke/JOIN_MITER
                                       10.0
                                       (float-array [6.0 4.0])
                                       0.0))
    (.setMarkerColor series (Color. 0 0 0 0))
    (.setShowInLegend series false)
    series))

(defn- style-category! [^CategoryChart chart & {:keys [log-y title y-label legend?]}]
  (let [^CategoryStyler styler (.getStyler chart)]
    (.setChartTitleFont styler +title-font+)
    (.setAxisTickLabelsFont styler +label-font+)
    (.setLegendFont styler +label-font+)
    (.setLegendVisible styler (boolean legend?))
    (when legend? (.setLegendPosition styler Styler$LegendPosition/InsideNE))
    (.setPlotGridLinesVisible styler true)
    (.setPlotGridLinesColor styler (Color. 230 230 230))
    (.setChartBackgroundColor styler Color/WHITE)
    (.setPlotBackgroundColor styler Color/WHITE)
    (.setPlotBorderVisible styler false)
    (.setDefaultSeriesRenderStyle styler CategorySeries$CategorySeriesRenderStyle/Bar)
    (.setAvailableSpaceFill styler 0.7)
    (.setOverlapped styler true)
    (when log-y (.setYAxisLogarithmic styler true))
    (when title (.setTitle chart title))
    (.setXAxisTitle chart "")
    (when y-label (.setYAxisTitle chart y-label)))
  chart)

(defn- save-chart! [chart path]
  (let [f (io/file path)]
    (io/make-parents f)
    (BitmapEncoder/saveBitmapWithDPI chart path
                                     BitmapEncoder$BitmapFormat/PNG 150)
    (println (str "  wrote " path))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chart 1: Set Algebra Scaling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chart-set-algebra [edn sizes out-dir]
  (let [chart (-> (XYChartBuilder.)
                  (.width +width+) (.height +height+)
                  (.build))
        ops [["Union"        :set-union        :ordered-set :sorted-set  +blue+]
             ["Intersection" :set-intersection :ordered-set :sorted-set  +green+]
             ["Difference"   :set-difference   :ordered-set :sorted-set  +purple+]]]
    (style-xy! chart
               :log-x true :log-y true
               :title "Set Algebra: ordered-set vs sorted-set"
               :y-label "Speedup (×)")
    (add-parity-line! chart sizes)
    (doseq [[label group oc peer color] ops]
      (let [ys (mapv #(or (speedup edn % group oc peer) 1.0) sizes)]
        (add-xy-series! chart label (mapv double sizes) ys color)))
    (save-chart! chart (str out-dir "/set-algebra-scaling.png"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chart 2: Rope Structural Editing Scaling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chart-rope-editing [edn sizes out-dir]
  (let [chart (-> (XYChartBuilder.)
                  (.width +width+) (.height +height+)
                  (.build))
        ops [["Rope vs Vector"        :rope-repeated-edits   :rope        :vector     +blue+]
             ["StringRope vs String"   :string-rope-repeated-edits :string-rope :string +green+]
             ["ByteRope vs byte[]"     :byte-rope-repeated-edits :byte-rope :byte-array  +purple+]]]
    (style-xy! chart
               :log-x true :log-y true
               :title "Rope Structural Editing: 200 Random Splices"
               :y-label "Speedup (×)")
    (add-parity-line! chart sizes)
    (doseq [[label group oc peer color] ops]
      (let [ys (mapv #(or (speedup edn % group oc peer) 0.1) sizes)]
        (add-xy-series! chart label (mapv double sizes) ys color)))
    (save-chart! chart (str out-dir "/rope-editing-scaling.png"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chart 3: Collection Winners (horizontal bar)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chart-collection-winners [edn sizes out-dir]
  (let [max-size (last sizes)
        entries [["Range Map (carve-out)"          :range-map-carve-out    :range-map        :guava-range-map]
                 ["Priority Queue (push)"          :priority-queue-push    :priority-queue   :sorted-set-by]
                 ["Set Algebra (union)"            :set-union              :ordered-set      :sorted-set]
                 ["ByteRope (remove)"              :byte-rope-remove       :byte-rope        :byte-array]
                 ["StringRope (splice)"            :string-rope-splice     :string-rope      :string]
                 ["Rope (repeated edits)"          :rope-repeated-edits    :rope             :vector]
                 ["Segment Tree (range query)"     :segment-tree-query     :segment-tree     :sorted-map]]
        values  (mapv (fn [[_ group oc peer]]
                        (or (speedup edn max-size group oc peer) 1.0))
                      entries)
        ;; Use XY chart as a dot plot on a log-Y axis. Each collection type
        ;; becomes a separate named series (one point each) so the legend
        ;; provides the labels. More effective than a bar chart when values
        ;; span 3 orders of magnitude.
        ^XYChart chart (-> (XYChartBuilder.)
                           (.width +width+) (.height +height+)
                           (.build))
        n (count entries)]
    (style-xy! chart
               :log-y true
               :title (str "Best Headline Win per Collection Type (N=" max-size ")")
               :y-label "Speedup (x)")
    (.setXAxisTitle chart "")
    (let [styler (.getStyler chart)]
      (.setLegendPosition styler Styler$LegendPosition/InsideNW)
      (.setXAxisTicksVisible styler false)
      (.setXAxisMin styler -0.5)
      (.setXAxisMax styler (double (- n 0.5))))
    ;; One point per collection type, each in its own color
    (doseq [[i [label _ _ _]] (map-indexed vector entries)]
      (let [color (nth +series-colors+ (mod i (count +series-colors+)))
            ^XYSeries series (.addSeries chart
                                         (str label " (" (format "%.0fx" (double (nth values i))) ")")
                                         (double-array [(double i)])
                                         (double-array [(double (nth values i))]))]
        (.setMarkerColor series color)
        (.setLineColor series (Color. 0 0 0 0))
        (.setXYSeriesRenderStyle series XYSeries$XYSeriesRenderStyle/Scatter)))
    (add-parity-line! chart [0.0 (double (dec n))])
    (save-chart! chart (str out-dir "/collection-winners.png"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chart 4: Rope Operations Profile (diverging bar)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chart-rope-profile [edn sizes out-dir]
  (let [max-size (last sizes)
        ops [["construction"   :rope-construction   :rope :vector]
             ["nth (random)"   :rope-nth            :rope :vector]
             ["reduce (sum)"   :rope-reduce         :rope :vector]
             ["fold (sum)"     :rope-fold-sum       :rope :vector]
             ["concat"         :rope-concat         :rope :vector]
             ["splice"         :rope-splice         :rope :vector]
             ["repeated-edits" :rope-repeated-edits :rope :vector]]
        labels   (mapv first ops)
        values   (mapv (fn [[_ group oc peer]]
                         (or (speedup edn max-size group oc peer) 1.0))
                       ops)
        ;; Split into wins and losses for color coding
        win-vals (mapv #(if (>= % 1.0) % nil) values)
        loss-vals (mapv #(if (< % 1.0) % nil) values)
        ^CategoryChart chart (-> (CategoryChartBuilder.)
                                 (.width +width+) (.height +height+)
                                 (.build))]
    ;; Switch to XY scatter — log-Y on CategoryChart doesn't work with
    ;; values spanning 0.1x-1300x.
    (let [^XYChart xychart (-> (XYChartBuilder.)
                               (.width +width+) (.height +height+)
                               (.build))
          n (count ops)]
      (style-xy! xychart
                 :log-y true
                 :title (str "Rope vs PersistentVector — Full Profile (N=" max-size ")")
                 :y-label "Speedup (x, log scale)")
      (.setXAxisTitle xychart "")
      (let [styler (.getStyler xychart)]
        (.setXAxisTicksVisible styler false)
        (.setXAxisMin styler -0.5)
        (.setXAxisMax styler (double (- n 0.5))))
      ;; One point per operation, colored by win/loss
      (doseq [[i [label _ _ _]] (map-indexed vector ops)]
        (let [v     (double (nth values i))
              color (if (>= v 1.0) +blue+ +red+)
              ^XYSeries series (.addSeries xychart
                                           (str label " (" (format "%.1fx" v) ")")
                                           (double-array [(double i)])
                                           (double-array [v]))]
          (.setMarkerColor series color)
          (.setLineColor series (Color. 0 0 0 0))
          (.setXYSeriesRenderStyle series XYSeries$XYSeriesRenderStyle/Scatter)))
      (add-parity-line! xychart [0.0 (double (dec n))])
      (save-chart! xychart (str out-dir "/rope-operations-profile.png")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chart 5: Absolute Time — Rope vs Vector
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chart-rope-absolute [edn sizes out-dir]
  (let [chart (-> (XYChartBuilder.)
                  (.width +width+) (.height +height+)
                  (.build))
        rope-ns  (mapv #(let [v (lookup edn % :rope-repeated-edits :rope)]
                          (if v (/ v 1e6) 0.01))
                       sizes)
        vec-ns   (mapv #(let [v (lookup edn % :rope-repeated-edits :vector)]
                          (if v (/ v 1e6) 0.01))
                       sizes)]
    (style-xy! chart
               :log-x true :log-y true
               :title "200 Random Edits: Absolute Time"
               :y-label "Time (ms)")
    (add-xy-series! chart "PersistentVector" (mapv double sizes) vec-ns +red+)
    (add-xy-series! chart "Rope"             (mapv double sizes) rope-ns +blue+)
    (save-chart! chart (str out-dir "/rope-vs-vector-absolute.png"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chart 6: StringRope Crossover
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chart-string-rope-crossover [edn sizes out-dir]
  (let [chart (-> (XYChartBuilder.)
                  (.width +width+) (.height +height+)
                  (.build))
        ops [["splice"         :string-rope-splice          :string-rope :string +blue+]
             ["insert"         :string-rope-insert          :string-rope :string +green+]
             ["remove"         :string-rope-remove          :string-rope :string +purple+]
             ["repeated edits" :string-rope-repeated-edits  :string-rope :string +orange+]
             ["concat"         :string-rope-concat          :string-rope :string (Color. 100 100 100)]]]
    (style-xy! chart
               :log-x true :log-y true
               :title "StringRope vs String: Crossover by Operation"
               :y-label "Speedup (×)")
    (add-parity-line! chart sizes)
    (doseq [[label group oc peer color] ops]
      (let [ys (mapv #(or (speedup edn % group oc peer) 0.01) sizes)]
        (add-xy-series! chart label (mapv double sizes) ys color)))
    (save-chart! chart (str out-dir "/string-rope-crossover.png"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chart 7: ByteRope Crossover
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chart-byte-rope-crossover [edn sizes out-dir]
  (let [chart (-> (XYChartBuilder.)
                  (.width +width+) (.height +height+)
                  (.build))
        ops [["splice"         :byte-rope-splice          :byte-rope :byte-array +blue+]
             ["insert"         :byte-rope-insert          :byte-rope :byte-array +green+]
             ["remove"         :byte-rope-remove          :byte-rope :byte-array +purple+]
             ["repeated edits" :byte-rope-repeated-edits  :byte-rope :byte-array +orange+]
             ["split"          :byte-rope-split           :byte-rope :byte-array (Color. 100 100 100)]]]
    (style-xy! chart
               :log-x true :log-y true
               :title "ByteRope vs byte[]: Crossover by Operation"
               :y-label "Speedup (×)")
    (add-parity-line! chart sizes)
    (doseq [[label group oc peer color] ops]
      (let [ys (mapv #(or (speedup edn % group oc peer) 0.01) sizes)]
        (add-xy-series! chart label (mapv double sizes) ys color)))
    (save-chart! chart (str out-dir "/byte-rope-crossover.png"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Runner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate-charts
  [edn-path out-dir]
  (println "Generating benchmark charts...")
  (println (str "  source: " edn-path))
  (println (str "  output: " out-dir))
  (println)
  (let [edn   (load-bench-edn (str edn-path))
        sizes (sizes-vec edn)]
    (chart-set-algebra           edn sizes out-dir)
    (chart-rope-editing          edn sizes out-dir)
    (chart-collection-winners    edn sizes out-dir)
    (chart-rope-profile          edn sizes out-dir)
    (chart-rope-absolute         edn sizes out-dir)
    (chart-string-rope-crossover edn sizes out-dir)
    (chart-byte-rope-crossover   edn sizes out-dir)
    (println)
    (println "Done. 7 charts written.")))

(defn -main [& args]
  (let [file-arg (some->> (partition-all 2 1 args)
                          (filter (fn [[a _]] (= "--file" a)))
                          first second)
        edn-path (or file-arg (latest-bench-file))
        out-dir  (or (some->> (partition-all 2 1 args)
                              (filter (fn [[a _]] (= "--output" a)))
                              first second)
                     "doc/charts")]
    (if edn-path
      (generate-charts edn-path out-dir)
      (do (println "No benchmark EDN found in bench-results/")
          (System/exit 1)))
    (shutdown-agents)))
