(ns ordered-collections.string-rope-bench
  "StringRope benchmark suite with chart generation.

   Usage:
     lein bench-string-rope                 ; Run benchmarks + generate charts
     lein bench-string-rope --chart-only    ; Charts from existing EDN (no bench)
     lein bench-string-rope --sizes 1000,10000  ; Custom sizes

   Outputs:
     bench-results/string-rope-full.edn             ; Raw benchmark data
     bench-results/string-rope-benchmark.png         ; Log-scale speedup chart
     bench-results/string-rope-benchmark-linear.png  ; Linear-scale speedup chart"
  (:require [ordered-collections.bench-runner :as br]
            [ordered-collections.bench-utils :refer [format-ns parse-sizes]]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.awt Color Font Graphics2D BasicStroke RenderingHints]
           [java.awt.geom Line2D$Double Ellipse2D$Double Rectangle2D$Double]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [java.io File]
           [java.time Instant])
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Benchmark Runner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-sizes [1000 4000 10000 100000])

(def bench-specs
  [[:string-rope-construction br/bench-string-rope-construction]
   [:string-rope-concat       br/bench-string-rope-concat]
   [:string-rope-split        br/bench-string-rope-split]
   [:string-rope-splice       br/bench-string-rope-splice]
   [:string-rope-insert       br/bench-string-rope-insert]
   [:string-rope-remove       br/bench-string-rope-remove]
   [:string-rope-nth          br/bench-string-rope-nth]
   [:string-rope-reduce       br/bench-string-rope-reduce]
   [:string-rope-repeated-edits br/bench-string-rope-repeated-edits]
   [:string-rope-re-find    br/bench-string-rope-re-find]
   [:string-rope-re-seq     br/bench-string-rope-re-seq]
   [:string-rope-re-replace br/bench-string-rope-re-replace]])

(def ^:private edn-path "bench-results/string-rope-full.edn")

(defn run-benchmarks [sizes]
  (let [results (atom {})]
    (doseq [n sizes]
      (println)
      (println (str "===== N = " n " ====="))
      (doseq [[k f] bench-specs]
        (print (str "  " (name k)))
        (flush)
        (swap! results assoc-in [n k] (f n))
        (println)))
    (br/write-results @results edn-path
      {:sizes (vec sizes) :args []} (Instant/now))
    (br/print-summary @results)
    @results))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EDN → Speedup Data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private chart-benchmarks
  ;; [edn-key chart-label competitor-key]
  [[:string-rope-splice    "Single Splice"    :string :string-builder]
   [:string-rope-insert    "Single Insert"    :string :string-builder]
   [:string-rope-remove    "Single Remove"    :string :string-builder]
   [:string-rope-repeated-edits "200 Random Edits" :string :string-builder]
   [:string-rope-concat    "Concat Halves"    :string :string-builder]])

(defn- speedup
  "Compute speedup = competitor-ns / rope-ns. Returns nil if data missing."
  [bench-results bench-key competitor-key]
  (let [rope-ns (get-in bench-results [bench-key :string-rope :mean-ns])
        comp-ns (get-in bench-results [bench-key competitor-key :mean-ns])]
    (when (and rope-ns comp-ns (pos? rope-ns))
      (double (/ comp-ns rope-ns)))))

(defn- extract-speedups
  "Extract speedup series from EDN results for one competitor.
   Returns [[label [[size speedup] ...]] ...]"
  [results sizes competitor-key]
  (vec
    (for [[bench-key label _ _] chart-benchmarks
          :let [pts (vec (for [n sizes
                               :let [s (speedup (get results n) bench-key competitor-key)]
                               :when s]
                           [n s]))]
          :when (seq pts)]
      [label pts])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chart Rendering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private W 1400)
(def ^:private H 750)
(def ^:private margin-left   90)
(def ^:private margin-right  30)
(def ^:private margin-top    100)
(def ^:private margin-bottom 100)
(def ^:private panel-gap     80)

(def ^:private line-colors
  [(Color. 30 160 60)      ;; green - splice
   (Color. 50 120 210)     ;; blue - insert
   (Color. 200 80 40)      ;; orange - remove
   (Color. 140 50 180)     ;; purple - 200 edits
   (Color. 180 150 30)])   ;; gold - concat

(def ^:private color-axis     (Color. 80 80 80))
(def ^:private color-bg       (Color. 250 250 252))
(def ^:private color-grid     (Color. 235 235 235))
(def ^:private color-parity   (Color. 180 50 50))
(def ^:private color-win-bg   (Color. 230 245 230))
(def ^:private color-loss-bg  (Color. 248 235 235))
(def ^:private color-title    (Color. 30 30 30))
(def ^:private color-subtitle (Color. 100 100 100))

(def ^:private font-title     (Font. "SansSerif" Font/BOLD 26))
(def ^:private font-subtitle  (Font. "SansSerif" Font/PLAIN 14))
(def ^:private font-panel     (Font. "SansSerif" Font/BOLD 16))
(def ^:private font-tick      (Font. "SansSerif" Font/PLAIN 11))
(def ^:private font-label     (Font. "SansSerif" Font/PLAIN 12))
(def ^:private font-legend    (Font. "SansSerif" Font/PLAIN 12))
(def ^:private font-parity    (Font. "SansSerif" Font/ITALIC 11))
(def ^:private font-zone      (Font. "SansSerif" Font/BOLD 11))
(def ^:private font-annot     (Font. "SansSerif" Font/BOLD 14))

(def ^:private stroke-line    (BasicStroke. 2.5 BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
(def ^:private stroke-grid    (BasicStroke. 1.0))
(def ^:private stroke-parity  (BasicStroke. 2.0 BasicStroke/CAP_BUTT BasicStroke/JOIN_MITER
                                10.0 (float-array [6 4]) 0.0))
(def ^:private stroke-axis    (BasicStroke. 1.5))
(def ^:private stroke-border  (BasicStroke. 1.0))

(defn- log10 ^double [x] (Math/log10 (double x)))

(defn- map-range [v vmin vmax pmin pmax]
  (+ pmin (* (- pmax pmin) (/ (- v vmin) (- vmax vmin)))))

(defn- panel-bounds [panel-idx]
  (let [pw (/ (- W margin-left margin-right panel-gap) 2)
        x0 (+ margin-left (* panel-idx (+ pw panel-gap)))
        y0 margin-top
        x1 (+ x0 pw)
        y1 (- H margin-bottom)]
    {:x0 (double x0) :y0 (double y0) :x1 (double x1) :y1 (double y1)
     :pw (double pw) :ph (double (- y1 y0))}))

(defn- draw-line [^Graphics2D g x1 y1 x2 y2]
  (.draw g (Line2D$Double. (double x1) (double y1) (double x2) (double y2))))

(defn- draw-circle [^Graphics2D g cx cy r]
  (.fill g (Ellipse2D$Double. (- (double cx) r) (- (double cy) r) (* 2.0 r) (* 2.0 r))))

(defn- draw-string-centered [^Graphics2D g ^String s x y]
  (let [fm (.getFontMetrics g)
        sw (.stringWidth fm s)]
    (.drawString g s (int (- x (/ sw 2))) (int y))))

(defn- draw-string-right [^Graphics2D g ^String s x y]
  (let [fm (.getFontMetrics g)
        sw (.stringWidth fm s)]
    (.drawString g s (int (- x sw)) (int y))))

(defn- format-speedup ^String [v]
  (if (>= v 10)
    (format "%.0f\u00d7" (double v))
    (format "%.1f\u00d7" (double v))))

(defn- format-size ^String [n]
  (cond
    (>= n 1000000) (str (/ n 1000000) "M")
    (>= n 1000)    (str (/ n 1000) "k")
    :else           (str n)))

(defn- nice-ticks
  "Generate nice round tick values for a linear axis from 0 to ymax."
  [ymax]
  (let [raw-step (/ ymax 6.0)
        mag      (Math/pow 10 (Math/floor (Math/log10 raw-step)))
        norm     (/ raw-step mag)
        step     (* mag (cond (<= norm 1.5) 1 (<= norm 3.5) 2 (<= norm 7.5) 5 :else 10))]
    (loop [v step, acc []]
      (if (> v (* ymax 1.01)) acc (recur (+ v step) (conj acc v))))))


;; ── Panel drawing ───────────────────────────────────────────────────────────

(defn- draw-panel
  [^Graphics2D g bench-data panel-idx panel-title
   & {:keys [log-y? shared-ymax]}]
  (let [{:keys [x0 y0 x1 y1 pw ph]} (panel-bounds panel-idx)
        all-speedups (mapcat (fn [[_ pts]] (map second pts)) bench-data)
        sizes        (mapv first (second (first bench-data)))
        log-xmin     (- (log10 (apply min sizes)) 0.15)
        log-xmax     (+ (log10 (apply max sizes)) 0.15)
        mx           (fn [v] (map-range (log10 (double v)) log-xmin log-xmax x0 x1))
        ;; Y axis setup
        max-sp       (double (apply max all-speedups))
        min-sp       (double (apply min all-speedups))
        [my parity-y y-ticks]
        (if log-y?
          (let [log-ymin (min -0.6 (- (log10 (max 0.1 min-sp)) 0.2))
                log-ymax (+ (log10 max-sp) 0.3)
                my       (fn [v] (map-range (log10 (double v)) log-ymin log-ymax y1 y0))
                ticks    (filter #(and (>= (log10 %) log-ymin) (<= (log10 %) log-ymax))
                           [0.1 0.5 2 5 10 20 50 100 200 500])]
            [my (my 1.0) ticks])
          (let [ymax  (or shared-ymax (* max-sp 1.12))
                my    (fn [v] (map-range (double v) 0.0 ymax y1 y0))
                ticks (nice-ticks ymax)]
            [my (my 1.0) ticks]))]

    ;; Win/loss background zones
    (.setColor g color-win-bg)
    (.fillRect g (int x0) (int y0) (int pw) (int (- parity-y y0)))
    (.setColor g color-loss-bg)
    (.fillRect g (int x0) (int parity-y) (int pw) (int (- y1 parity-y)))

    ;; Panel border
    (.setColor g (Color. 200 200 200))
    (.setStroke g stroke-border)
    (.draw g (Rectangle2D$Double. x0 y0 pw ph))

    ;; Grid lines (Y)
    (.setStroke g stroke-grid)
    (.setColor g color-grid)
    (doseq [v y-ticks]
      (let [yy (my v)]
        (when (and (>= yy y0) (<= yy y1))
          (draw-line g x0 yy x1 yy))))

    ;; Grid lines (X)
    (doseq [s sizes]
      (.setColor g color-grid)
      (.setStroke g stroke-grid)
      (draw-line g (mx s) y0 (mx s) y1))

    ;; Parity line — dashed red
    (.setStroke g stroke-parity)
    (.setColor g color-parity)
    (draw-line g x0 parity-y x1 parity-y)

    ;; Zone labels
    (.setFont g font-zone)
    (.setColor g (Color. 40 130 50))
    (.drawString g "StringRope faster \u2191" (int (+ x0 6)) (int (+ y0 16)))
    (.setColor g (Color. 170 50 50))
    (.drawString g "StringRope slower \u2193" (int (+ x0 6)) (int (- y1 6)))

    ;; Axes
    (.setStroke g stroke-axis)
    (.setColor g color-axis)
    (draw-line g x0 y1 x1 y1)
    (draw-line g x0 y0 x0 y1)

    ;; Y tick labels
    (.setFont g font-tick)
    (.setColor g color-axis)
    ;; Parity label
    (when (and (>= parity-y y0) (<= parity-y y1))
      (.setColor g color-parity)
      (.setFont g font-parity)
      (draw-string-right g "1\u00d7 parity" (- x0 6) (+ parity-y 4))
      (.setFont g font-tick)
      (.setColor g color-axis))
    (doseq [v y-ticks]
      (let [yy (my v)]
        (when (and (>= yy y0) (<= yy y1))
          (draw-string-right g (format-speedup v) (- x0 6) (+ yy 4)))))

    ;; X tick labels
    (doseq [s sizes]
      (draw-string-centered g (format-size s) (mx s) (+ y1 16)))
    (.setFont g font-label)
    (.setColor g color-subtitle)
    (draw-string-centered g "String Length" (/ (+ x0 x1) 2) (+ y1 35))

    ;; Panel title
    (.setFont g font-panel)
    (.setColor g color-title)
    (draw-string-centered g panel-title (/ (+ x0 x1) 2) (- y0 10))

    ;; Plot lines and points
    (doseq [[idx [_label pts]] (map-indexed vector bench-data)]
      (let [color (nth line-colors idx)]
        (.setColor g color)
        (.setStroke g stroke-line)
        (let [mapped-pts (mapv (fn [[size sp]] [(mx size) (my sp)]) pts)]
          (doseq [i (range (dec (count mapped-pts)))]
            (let [[px1 py1] (nth mapped-pts i)
                  [px2 py2] (nth mapped-pts (inc i))]
              (draw-line g px1 py1 px2 py2)))
          (doseq [[px py] mapped-pts]
            (.setColor g Color/WHITE)
            (draw-circle g px py 5.5)
            (.setColor g color)
            (draw-circle g px py 4))
          (let [[px py] (last mapped-pts)
                sp (second (last pts))]
            (.setFont g font-annot)
            (.setColor g color)
            (.drawString g ^String (format-speedup sp) (int (+ px 8)) (int (+ py 5)))))))))


;; ── Full chart rendering ────────────────────────────────────────────────────

(defn- draw-legend [^Graphics2D g bench-data-left]
  (let [ly     (- H 28)
        labels (mapv first bench-data-left)
        _      (.setFont g font-legend)
        fm     (.getFontMetrics g)
        spacing 32
        total-w (reduce + (map-indexed
                            (fn [_i label] (+ 26 (.stringWidth fm ^String label) spacing))
                            labels))
        start-x (- (/ W 2) (/ total-w 2))]
    (loop [i 0, x start-x]
      (when (< i (count labels))
        (let [color (nth line-colors i)
              label (nth labels i)]
          (.setColor g color)
          (.setStroke g stroke-line)
          (draw-line g x (- ly 4) (+ x 18) (- ly 4))
          (draw-circle g (+ x 9) (- ly 4) 3.5)
          (.setFont g font-legend)
          (.setColor g color-axis)
          (.drawString g ^String label (int (+ x 24)) (int ly))
          (let [sw (.stringWidth fm ^String label)]
            (recur (inc i) (+ x 24 sw spacing))))))))

(defn- render-chart
  [^String path data-vs-string data-vs-sb & {:keys [log-y?]}]
  (let [img (BufferedImage. W H BufferedImage/TYPE_INT_ARGB)
        g   (.createGraphics img)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
    (.setRenderingHint g RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)

    ;; Background
    (.setColor g color-bg)
    (.fillRect g 0 0 W H)

    ;; Title
    (.setFont g font-title)
    (.setColor g color-title)
    (draw-string-centered g "StringRope: Speedup vs String and StringBuilder" (/ W 2) 32)
    (.setFont g font-subtitle)
    (.setColor g color-subtitle)
    (draw-string-centered g
      "Persistent, immutable, O(log n)  \u2022  Thread-safe  \u2022  Free undo via structural sharing"
      (/ W 2) 52)
    (draw-string-centered g
      (str "Higher = faster. Dashed red line = break-even. "
           (if log-y? "Log" "Linear") " scale on Y-axis.")
      (/ W 2) 70)

    ;; Panels
    (if log-y?
      (do (draw-panel g data-vs-string 0 "vs String (str + subs)" :log-y? true)
          (draw-panel g data-vs-sb     1 "vs StringBuilder (optimal mutable)" :log-y? true))
      (let [all-sp (concat (mapcat (fn [[_ pts]] (map second pts)) data-vs-string)
                           (mapcat (fn [[_ pts]] (map second pts)) data-vs-sb))
            ymax   (* (double (apply max all-sp)) 1.12)]
        (draw-panel g data-vs-string 0 "vs String (str + subs)" :shared-ymax ymax)
        (draw-panel g data-vs-sb     1 "vs StringBuilder (optimal mutable)" :shared-ymax ymax)))

    ;; Legend
    (draw-legend g data-vs-string)

    (.dispose g)
    (ImageIO/write img "png" (File. path))
    (println (str "  Chart written to: " path))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate-charts
  "Generate both log and linear speedup charts from EDN results."
  [results sizes]
  (System/setProperty "java.awt.headless" "true")
  (let [vs-string (extract-speedups results sizes :string)
        vs-sb     (extract-speedups results sizes :string-builder)]
    (println)
    (println "Generating charts...")
    (render-chart "bench-results/string-rope-benchmark.png"
      vs-string vs-sb :log-y? true)
    (render-chart "bench-results/string-rope-benchmark-linear.png"
      vs-string vs-sb :log-y? false)))

(defn -main [& args]
  (let [chart-only? (some #{"--chart-only"} args)
        sizes       (if-let [s (some (fn [[a b]] (when (= a "--sizes") b))
                                (partition 2 1 args))]
                      (parse-sizes s)
                      default-sizes)]
    (if chart-only?
      (let [data (edn/read-string (slurp edn-path))]
        (generate-charts (:benchmarks data) (:sizes data)))
      (let [results (run-benchmarks sizes)]
        (generate-charts results sizes))))
  (shutdown-agents))
