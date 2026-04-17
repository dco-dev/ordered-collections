(ns gen-chart
  "Generate a PNG benchmark chart using Java 2D Graphics."
  (:import [java.awt Color Font Graphics2D BasicStroke RenderingHints]
           [java.awt.font TextLayout FontRenderContext]
           [java.awt.geom Line2D$Double Ellipse2D$Double Path2D$Double
                          Rectangle2D$Double AffineTransform]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [java.io File]))

;; ── Benchmark data ──────────────────────────────────────────────────────────

(def data
  ;; [size  string-ns  stringbuilder-ns  stringrope-ns]
  {:repeated-edits
   {:label "200 Random Edits (splice + insert + remove)"
    :rows [[1000    23500    11200    31300]
           [4000    73200    42400    27000]
           [10000  205700   130000    28100]
           [100000 1620000  968400    38100]]}
   :single-splice
   {:label "Single Splice (replace 10 chars at midpoint)"
    :rows [[1000      110      55      146]
           [4000      334     189       95]
           [10000     884     635      109]
           [100000   8200    4800       51]]}})

;; ── Geometry helpers ────────────────────────────────────────────────────────

(def W 1600)
(def H 900)
(def margin-left   140)
(def margin-right   40)
(def margin-top     70)
(def margin-bottom  80)
(def gap            100)  ;; between left and right panels

(defn panel-bounds [panel-idx]
  (let [pw (/ (- W margin-left margin-right gap) 2)
        x0 (+ margin-left (* panel-idx (+ pw gap)))
        y0 margin-top
        x1 (+ x0 pw)
        y1 (- H margin-bottom)]
    {:x0 x0 :y0 y0 :x1 x1 :y1 y1 :pw pw :ph (- y1 y0)}))

(defn log10 [x] (Math/log10 (double x)))

(defn map-range [v vmin vmax pmin pmax]
  (+ pmin (* (- pmax pmin) (/ (- v vmin) (- vmax vmin)))))

;; ── Colors & styling ────────────────────────────────────────────────────────

(def color-string        (Color. 220 60 60))     ;; red
(def color-stringbuilder (Color. 50 120 220))     ;; blue
(def color-stringrope    (Color. 30 170 70))      ;; green
(def color-grid          (Color. 220 220 220))
(def color-axis          (Color. 80 80 80))
(def color-bg            (Color. 252 252 252))
(def color-panel-bg      Color/WHITE)
(def color-title         (Color. 30 30 30))
(def color-subtitle      (Color. 100 100 100))

(def font-title    (Font. "Helvetica" Font/BOLD 28))
(def font-subtitle (Font. "Helvetica" Font/PLAIN 16))
(def font-label    (Font. "Helvetica" Font/PLAIN 14))
(def font-tick     (Font. "Helvetica" Font/PLAIN 12))
(def font-legend   (Font. "Helvetica" Font/PLAIN 14))
(def font-annot    (Font. "Helvetica" Font/BOLD 13))

(def stroke-line  (BasicStroke. 3.0 BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
(def stroke-grid  (BasicStroke. 1.0))
(def stroke-axis  (BasicStroke. 1.5))

;; ── Drawing helpers ─────────────────────────────────────────────────────────

(defn draw-line [^Graphics2D g x1 y1 x2 y2]
  (.draw g (Line2D$Double. x1 y1 x2 y2)))

(defn draw-circle [^Graphics2D g cx cy r]
  (.fill g (Ellipse2D$Double. (- cx r) (- cy r) (* 2 r) (* 2 r))))

(defn draw-string-centered [^Graphics2D g ^String s x y]
  (let [fm   (.getFontMetrics g)
        sw   (.stringWidth fm s)]
    (.drawString g s (int (- x (/ sw 2))) (int y))))

(defn draw-string-right [^Graphics2D g ^String s x y]
  (let [fm (.getFontMetrics g)
        sw (.stringWidth fm s)]
    (.drawString g s (int (- x sw)) (int y))))

(defn format-ns [ns]
  (cond
    (>= ns 1000000) (format "%.1fms" (/ ns 1000000.0))
    (>= ns 1000)    (format "%.0fus" (/ ns 1000.0))
    :else            (format "%.0fns" (double ns))))

(defn format-size [n]
  (cond
    (>= n 1000000) (str (/ n 1000000) "M")
    (>= n 1000)    (str (/ n 1000) "k")
    :else           (str n)))

;; ── Panel rendering ─────────────────────────────────────────────────────────

(defn draw-panel [^Graphics2D g {:keys [label rows]} panel-idx]
  (let [{:keys [x0 y0 x1 y1 pw ph]} (panel-bounds panel-idx)
        ;; Compute data ranges (log scale)
        sizes    (mapv first rows)
        all-vals (mapcat (fn [[_ a b c]] [a b c]) rows)
        log-xmin (log10 (apply min sizes))
        log-xmax (log10 (apply max sizes))
        log-ymin (Math/floor (log10 (apply min (filter pos? all-vals))))
        log-ymax (Math/ceil  (log10 (apply max all-vals)))
        ;; Add padding
        log-xmin (- log-xmin 0.1)
        log-xmax (+ log-xmax 0.1)
        log-ymin (- log-ymin 0.2)
        log-ymax (+ log-ymax 0.2)
        ;; Map functions
        mx (fn [v] (map-range (log10 v) log-xmin log-xmax x0 x1))
        my (fn [v] (map-range (log10 v) log-ymin log-ymax y1 y0))]

    ;; Panel background
    (.setColor g color-panel-bg)
    (.fill g (Rectangle2D$Double. x0 y0 pw ph))

    ;; Grid lines (Y axis - powers of 10)
    (.setStroke g stroke-grid)
    (.setColor g color-grid)
    (doseq [exp (range (int log-ymin) (inc (int log-ymax)))]
      (let [yy (my (Math/pow 10 exp))]
        (when (and (>= yy y0) (<= yy y1))
          (draw-line g x0 yy x1 yy))))

    ;; Grid lines (X axis - at data points)
    (doseq [s sizes]
      (let [xx (mx s)]
        (draw-line g xx y0 xx y1)))

    ;; Axes
    (.setStroke g stroke-axis)
    (.setColor g color-axis)
    (draw-line g x0 y1 x1 y1)  ;; bottom
    (draw-line g x0 y0 x0 y1)  ;; left

    ;; Y tick labels
    (.setFont g font-tick)
    (.setColor g color-axis)
    (doseq [exp (range (int log-ymin) (inc (int log-ymax)))]
      (let [yy (my (Math/pow 10 exp))
            ns-val (long (Math/pow 10 exp))]
        (when (and (>= yy y0) (<= yy y1))
          (draw-string-right g (format-ns ns-val) (- x0 8) (+ yy 4)))))

    ;; X tick labels
    (doseq [s sizes]
      (draw-string-centered g (format-size s) (mx s) (+ y1 20)))

    ;; X axis label
    (.setFont g font-label)
    (.setColor g color-subtitle)
    (draw-string-centered g "String Length (chars)" (/ (+ x0 x1) 2) (+ y1 45))

    ;; Y axis label (only for left panel)
    (when (zero? panel-idx)
      (let [at (AffineTransform.)
            cy (/ (+ y0 y1) 2)]
        (.rotate at (- (/ Math/PI 2)))
        (let [old-transform (.getTransform g)]
          (.setTransform g (doto (AffineTransform. old-transform)
                            (.translate 30 cy)
                            (.rotate (- (/ Math/PI 2)))))
          (.setFont g font-label)
          (.setColor g color-subtitle)
          (draw-string-centered g "Time (log scale)" 0 0)
          (.setTransform g old-transform))))

    ;; Panel title
    (.setFont g font-label)
    (.setColor g color-title)
    (draw-string-centered g label (/ (+ x0 x1) 2) (- y0 12))

    ;; Plot lines and points
    (doseq [[color-val series-idx series-label]
            [[color-string 1 "String (str+subs)"]
             [color-stringbuilder 2 "StringBuilder"]
             [color-stringrope 3 "StringRope"]]]
      (.setColor g color-val)
      (.setStroke g stroke-line)
      ;; Lines
      (let [pts (mapv (fn [row]
                        [(mx (nth row 0)) (my (nth row series-idx))])
                      rows)]
        (doseq [i (range (dec (count pts)))]
          (let [[px1 py1] (nth pts i)
                [px2 py2] (nth pts (inc i))]
            (draw-line g px1 py1 px2 py2)))
        ;; Points
        (doseq [[px py] pts]
          (draw-circle g px py 5))))

    ;; Speedup annotations at 100k
    (let [last-row (last rows)
          string-ns    (nth last-row 1)
          sb-ns        (nth last-row 2)
          rope-ns      (nth last-row 3)
          xx           (+ (mx (first last-row)) 8)
          yr           (my rope-ns)]
      (.setFont g font-annot)
      (.setColor g color-stringrope)
      (let [vs-str (format "%.0fx vs String" (/ (double string-ns) rope-ns))
            vs-sb  (format "%.0fx vs StringBuilder" (/ (double sb-ns) rope-ns))]
        (.drawString g vs-str (int (- xx 100)) (int (- yr 18)))
        (.drawString g vs-sb  (int (- xx 100)) (int (- yr 4)))))))

;; ── Main rendering ──────────────────────────────────────────────────────────

(defn render-chart [^String path]
  (let [img (BufferedImage. W H BufferedImage/TYPE_INT_ARGB)
        g   (.createGraphics img)]
    ;; Anti-aliasing
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)

    ;; Background
    (.setColor g color-bg)
    (.fillRect g 0 0 W H)

    ;; Title
    (.setFont g font-title)
    (.setColor g color-title)
    (draw-string-centered g "StringRope: Persistent O(log n) String Editing" (/ W 2) 35)

    (.setFont g font-subtitle)
    (.setColor g color-subtitle)
    (draw-string-centered g "vs String (idiomatic Clojure) and StringBuilder (optimal mutable)"
      (/ W 2) 58)

    ;; Panels
    (draw-panel g (:single-splice data) 0)
    (draw-panel g (:repeated-edits data) 1)

    ;; Legend (centered below panels)
    (let [ly    (- H 18)
          lx    (- (/ W 2) 200)
          items [[color-string "String (str + subs)"]
                 [color-stringbuilder "StringBuilder"]
                 [color-stringrope "StringRope (persistent, O(log n))"]]]
      (loop [items items, x lx]
        (when (seq items)
          (let [[color label] (first items)]
            (.setColor g color)
            (.fillRect g (int x) (int (- ly 8)) 20 3)
            (draw-circle g (+ x 10) (- ly 7) 4)
            (.setFont g font-legend)
            (.setColor g color-axis)
            (.drawString g ^String label (int (+ x 28)) (int ly))
            (let [fm (.getFontMetrics g)
                  sw (.stringWidth fm ^String label)]
              (recur (rest items) (+ x sw 60)))))))

    (.dispose g)
    (ImageIO/write img "png" (File. path))
    (println (str "Chart written to: " path))))

(render-chart "bench-results/string-rope-benchmark.png")
