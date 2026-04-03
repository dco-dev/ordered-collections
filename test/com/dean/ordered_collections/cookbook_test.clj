(ns com.dean.ordered-collections.cookbook-test
  "Tests for examples in doc/cookbook.md

   Ensures all cookbook code snippets work correctly."
  (:refer-clojure :exclude [split-at])
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.core.reducers :as r]
            [com.dean.ordered-collections.core :as oc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 1. Leaderboard with Rank Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-leaderboard []
  ;; Use ordered-map-with for custom comparator without initial entries
  (oc/ordered-map-with (fn [[s1 id1] [s2 id2]]
                         (let [c (compare s2 s1)]  ; descending by score
                           (if (zero? c)
                             (compare id1 id2)     ; then ascending by id
                             c)))))

(defn add-score [board player-id score data]
  (assoc board [score player-id] data))

(defn top-n [board n]
  (->> board (take n) (map (fn [[[score id] data]]
                             {:id id :score score :data data}))))

(defn rank-of-player [board player-id score]
  (oc/rank board [score player-id]))

(defn players-around-rank [board rank window]
  (let [start (max 0 (- rank window))
        end   (min (count board) (+ rank window 1))]
    (map-indexed (fn [i [[score id] _]]
                   {:rank (+ start i) :id id :score score})
                 (oc/slice board start end))))

(deftest leaderboard-test
  (let [board (-> (make-leaderboard)
                  (add-score "alice" 1500 {:name "Alice"})
                  (add-score "bob" 1450 {:name "Bob"})
                  (add-score "carol" 1600 {:name "Carol"})
                  (add-score "dave" 1550 {:name "Dave"}))]

    (testing "top-n returns highest scorers"
      (is (= [{:id "carol" :score 1600 :data {:name "Carol"}}
              {:id "dave" :score 1550 :data {:name "Dave"}}
              {:id "alice" :score 1500 :data {:name "Alice"}}]
             (top-n board 3))))

    (testing "rank-of-player returns position"
      (is (= 0 (rank-of-player board "carol" 1600)))
      (is (= 2 (rank-of-player board "alice" 1500)))
      (is (= 3 (rank-of-player board "bob" 1450))))

    (testing "players-around-rank"
      (let [around (players-around-rank board 2 1)]
        (is (= 3 (count around)))
        (is (= "dave" (:id (first around))))
        (is (= "alice" (:id (second around))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 2. Time-Series Windowing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-event-log []
  (oc/ordered-map))

(defn add-event [log timestamp event]
  (assoc log timestamp event))

(defn events-between [log start-time end-time]
  (subseq log >= start-time < end-time))

(defn latest-events [log n]
  ;; rsubseq requires test and key; use rseq for full reverse
  (take n (rseq log)))

(defn count-events-in-window [log start-time end-time]
  (reduce (fn [acc _] (inc acc)) 0
          (subseq log >= start-time < end-time)))

(deftest time-series-windowing-test
  (let [log (-> (make-event-log)
                (add-event 1000 {:type :login :user "alice"})
                (add-event 2000 {:type :click :page "/home"})
                (add-event 3000 {:type :purchase :item "widget"})
                (add-event 4000 {:type :logout :user "alice"}))]

    (testing "events-between"
      (let [events (vec (events-between log 1500 3500))]
        (is (= 2 (count events)))
        (is (= 2000 (ffirst events)))
        (is (= 3000 (first (second events))))))

    (testing "latest-events"
      (let [events (vec (latest-events log 2))]
        (is (= 2 (count events)))
        (is (= 4000 (ffirst events)))
        (is (= 3000 (first (second events))))))

    (testing "count-events-in-window"
      (is (= 2 (count-events-in-window log 1500 3500)))
      (is (= 4 (count-events-in-window log 0 5000)))
      (is (= 0 (count-events-in-window log 5000 6000))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 3. Meeting Room Scheduler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-room-schedule []
  (oc/interval-map))

(defn book-room [schedule start end booking]
  (assoc schedule [start end] booking))

(defn conflicts-at [schedule time]
  (schedule time))

(defn conflicts-during [schedule start end]
  (schedule [start end]))

(defn is-available? [schedule start end]
  (empty? (conflicts-during schedule start end)))

(deftest meeting-room-scheduler-test
  (let [room-a (-> (make-room-schedule)
                   (book-room 900 1000 {:title "Standup" :organizer "alice"})
                   (book-room 1030 1130 {:title "Design Review" :organizer "bob"})
                   (book-room 1400 1500 {:title "1:1" :organizer "carol"}))]

    (testing "conflicts-at point query"
      (is (= [{:title "Standup" :organizer "alice"}]
             (conflicts-at room-a 930)))
      (is (empty? (conflicts-at room-a 1200))))

    (testing "conflicts-during range query"
      ;; Range [1000, 1100] overlaps with [900, 1000] (at endpoint) and [1030, 1130]
      (let [conflicts (set (conflicts-during room-a 1030 1100))]
        (is (contains? conflicts {:title "Design Review" :organizer "bob"}))))

    (testing "is-available? for non-overlapping slot"
      ;; [1200, 1400) doesn't overlap with any meeting
      (is (empty? (conflicts-during room-a 1200 1399))))

    (testing "is-available? for overlapping slot"
      ;; [1430, 1530] overlaps with [1400, 1500]
      (is (not (empty? (conflicts-during room-a 1430 1530)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 4. Persistent Work Queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-work-queue []
  (oc/priority-queue))

(defn enqueue [q priority task]
  (oc/push q priority task))

(defn next-task [q]
  (oc/peek-min q))

(defn run-next [q]
  (let [[_ task] (oc/peek-min q)]
    {:task task
     :remaining (oc/pop-min q)}))

(deftest priority-queue-cookbook-test
  (let [q (-> (make-work-queue)
              (enqueue 5 {:job :backup})
              (enqueue 1 {:job :page-oncall})
              (enqueue 2 {:job :send-email})
              (enqueue 1 {:job :invalidate-cache}))]
    (testing "peek-min returns first element in queue order"
      (is (= [1 {:job :page-oncall}] (next-task q))))

    (testing "pop-min preserves stable equal-priority ordering"
      (is (= {:job :page-oncall} (:task (run-next q))))
      (is (= [1 {:job :invalidate-cache}]
             (-> q run-next :remaining next-task))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 5. Parallel Aggregation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parallel-aggregation-test
  (let [transactions (oc/ordered-map
                       (for [i (range 10000)]
                         [i {:amount (mod i 100)
                             :category (nth [:food :transport :entertainment :utilities]
                                           (mod i 4))}]))]

    (testing "sequential reduce"
      (let [total (reduce (fn [acc [_ {:keys [amount]}]] (+ acc amount))
                          0 transactions)]
        (is (= 495000 total))))  ; sum of 0..99 repeated 100 times

    (testing "parallel fold produces same result"
      (let [total (r/fold
                    +
                    (fn [acc [_ {:keys [amount]}]] (+ acc amount))
                    transactions)]
        (is (= 495000 total))))

    (testing "parallel group-by"
      (let [by-category (r/fold
                          (partial merge-with +)
                          (fn [acc [_ {:keys [amount category]}]]
                            (update acc category (fnil + 0) amount))
                          transactions)]
        (is (= 4 (count by-category)))
        (is (every? pos? (vals by-category)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 6. Efficient Set Algebra
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest efficient-set-algebra-test
  (let [premium-users (oc/ordered-set (range 0 10000 2))
        active-users (oc/ordered-set (range 0 10000 3))]

    (testing "intersection"
      (let [premium-active (oc/intersection premium-users active-users)]
        ;; Elements divisible by both 2 and 3 = divisible by 6
        (is (= (count (range 0 10000 6)) (count premium-active)))
        (is (every? #(and (zero? (mod % 2)) (zero? (mod % 3))) premium-active))))

    (testing "difference"
      (let [premium-only (oc/difference premium-users active-users)]
        ;; Premium (div by 2) but not active (div by 3)
        (is (every? #(zero? (mod % 2)) premium-only))
        (is (not-any? #(zero? (mod % 6)) premium-only))))

    (testing "union"
      (let [all-users (oc/union premium-users active-users)]
        ;; Union of div-by-2 and div-by-3
        (is (every? #(or (zero? (mod % 2)) (zero? (mod % 3))) all-users))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 7. Sliding Window Statistics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-window [max-age-ms]
  {:data (oc/ordered-map)
   :max-age max-age-ms})

(defn add-sample [{:keys [data max-age] :as window} timestamp value]
  (let [cutoff (- timestamp max-age)
        fresh-data (oc/subrange data :>= cutoff)]
    (assoc window :data (assoc fresh-data timestamp value))))

(defn window-stats [{:keys [data]}]
  (when (seq data)
    (let [values (map val data)
          n (count values)
          sum (reduce + values)]
      {:count n
       :sum sum
       :mean (/ sum n)
       :min (apply min values)
       :max (apply max values)})))

(deftest sliding-window-statistics-test
  (testing "basic windowing"
    (let [w (-> (make-window 5000)
                (add-sample 1000 10)
                (add-sample 2000 20)
                (add-sample 3000 15))]
      (is (= {:count 3 :sum 45 :mean 15 :min 10 :max 20}
             (window-stats w)))))

  (testing "old samples are evicted"
    (let [w (-> (make-window 5000)
                (add-sample 1000 10)
                (add-sample 2000 20)
                (add-sample 3000 15)
                (add-sample 6500 25))]  ; evicts 1000
      (is (= 3 (:count (window-stats w))))
      (is (= 60 (:sum (window-stats w)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 8. Range Aggregate Queries (Segment Tree)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest segment-tree-test
  (let [sales (oc/segment-tree + 0
                {0 1200, 1 1500, 2 1100, 3 1800, 4 2200, 5 1900, 6 1600})]

    (testing "range sum queries"
      (is (= (+ 1100 1800 2200 1900) (oc/query sales 2 5)))  ; days 2-5
      (is (= (+ 1200 1500 1100 1800 2200 1900 1600) (oc/query sales 0 6))))  ; all

    (testing "update preserves structure"
      (let [updated (assoc sales 3 2500)]
        (is (= (+ 1100 2500 2200 1900) (oc/query updated 2 5)))))

    (testing "max tree"
      (let [peaks (oc/segment-tree max 0
                    {0 1200, 1 1500, 2 1100, 3 1800, 4 2200, 5 1900, 6 1600})]
        (is (= 2200 (oc/query peaks 0 6)))    ; max across all
        (is (= 1500 (oc/query peaks 0 2)))))  ; max for days 0-2

    (testing "sum-tree shorthand"
      (let [st (oc/sum-tree {0 100, 1 200, 2 300, 3 400})]
        (is (= 900 (oc/query st 1 3)))))))    ; 200 + 300 + 400

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 9. Database Index Simulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-index []
  (oc/ordered-map))

(defn index-add [idx value pk]
  (update idx value (fnil conj #{}) pk))

(defn index-remove [idx value pk]
  (let [pks (disj (get idx value #{}) pk)]
    (if (empty? pks)
      (dissoc idx value)
      (assoc idx value pks))))

(defn index-lookup [idx value]
  (get idx value #{}))

(defn index-range [idx min-val max-val]
  (->> (subseq idx >= min-val < max-val)
       (mapcat val)
       set))

(deftest database-index-simulation-test
  (let [age-index (-> (make-index)
                      (index-add 25 "user-1")
                      (index-add 30 "user-2")
                      (index-add 25 "user-3")
                      (index-add 35 "user-4")
                      (index-add 28 "user-5"))]

    (testing "exact lookup"
      (is (= #{"user-1" "user-3"} (index-lookup age-index 25)))
      (is (= #{"user-2"} (index-lookup age-index 30)))
      (is (= #{} (index-lookup age-index 99))))

    (testing "range lookup"
      (is (= #{"user-1" "user-2" "user-3" "user-5"}
             (index-range age-index 25 31))))

    (testing "index-remove"
      (let [idx' (index-remove age-index 25 "user-1")]
        (is (= #{"user-3"} (index-lookup idx' 25)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 10. Ordered Multiset
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ordered-multiset-cookbook-test
  (let [readings (oc/ordered-multiset [72 68 72 70 68 72 71])]
    (testing "duplicates stay sorted"
      (is (= [68 68 70 71 72 72 72] (vec (seq readings)))))

    (testing "multiplicity and distinct-elements"
      (is (= 3 (oc/multiplicity readings 72)))
      (is (= [68 70 71 72] (vec (oc/distinct-elements readings)))))

    (testing "frequency map and disj-one"
      (is (= {68 2, 70 1, 71 1, 72 3}
             (oc/element-frequencies readings)))
      (is (= 2 (-> readings (oc/disj-one 72) (oc/multiplicity 72)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 11. Fuzzy Lookup / Nearest Neighbor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fuzzy-lookup-test
  (let [calibration (oc/fuzzy-map {0.0 1.000
                                    25.0 1.012
                                    50.0 1.025
                                    75.0 1.041
                                    100.0 1.058})]

    (testing "fuzzy-map lookup"
      (is (= 1.012 (calibration 23.5)))
      (is (= 1.025 (calibration 60.0)))
      (is (= 1.041 (calibration 87.5))))

    (testing "fuzzy-nearest returns key, value, and distance"
      (let [result (oc/fuzzy-nearest calibration 60.0)]
        ;; Result is [key value distance]
        (is (vector? result))
        (is (= 3 (count result)))
        (let [[k v dist] result]
          (is (= 50.0 k))
          (is (= 1.025 v))
          (is (= 10.0 dist))))))

  (testing "fuzzy-map with tiebreak"
    (let [fm (oc/fuzzy-map {0 :a 10 :b 20 :c} :tiebreak :>)]
      (is (= :b (fm 5)))))

  (testing "fuzzy-set"
    (let [grid-points (oc/fuzzy-set (range 0 101 10))]
      (is (= 20 (grid-points 23)))
      (is (= 30 (grid-points 27)))
      (is (= 20 (grid-points 25)))))  ; default tiebreak :<

  (testing "fuzzy-nearest on set"
    (let [grid-points (oc/fuzzy-set (range 0 101 10))
          [val dist] (oc/fuzzy-nearest grid-points 23)]
      (is (= 20 val))
      (is (== 3 dist)))))  ; use == for numeric equality

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 11. Splitting Collections
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest splitting-collections-test
  (let [prices (oc/ordered-set [100 200 300 400 500 600 700 800 900 1000])]

    (testing "split-key with existing key"
      (let [[below match above] (oc/split-key 500 prices)]
        (is (= [100 200 300 400] (vec below)))
        (is (= 500 match))
        (is (= [600 700 800 900 1000] (vec above)))))

    (testing "split-key with non-existing key"
      (let [[below match above] (oc/split-key 550 prices)]
        (is (= [100 200 300 400 500] (vec below)))
        (is (nil? match))
        (is (= [600 700 800 900 1000] (vec above)))))

    (testing "split-at"
      (let [[left right] (oc/split-at 3 prices)]
        (is (= [100 200 300] (vec left)))
        (is (= [400 500 600 700 800 900 1000] (vec right)))))

    (testing "pagination using split-at"
      (let [paginate (fn [coll page-size page-num]
                       (let [offset (* page-size page-num)
                             [_ remaining] (oc/split-at offset coll)
                             [page _] (oc/split-at page-size remaining)]
                         (vec page)))]
        (is (= [100 200 300] (paginate prices 3 0)))
        (is (= [400 500 600] (paginate prices 3 1)))
        (is (= [700 800 900] (paginate prices 3 2)))
        (is (= [1000] (paginate prices 3 3)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 12. Subrange Extraction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest subrange-extraction-test
  (let [inventory (oc/ordered-map
                    [[10 "widget-a"] [20 "widget-b"] [30 "widget-c"]
                     [40 "widget-d"] [50 "widget-e"] [60 "widget-f"]])]

    (testing "two-sided bounds >="
      (let [sub (oc/subrange inventory :>= 25 :<= 50)]
        (is (= 3 (count sub)))
        (is (contains? sub 30))
        (is (contains? sub 50))
        (is (not (contains? sub 20)))))

    (testing "one-sided bound >"
      (let [sub (oc/subrange inventory :> 40)]
        (is (= 2 (count sub)))
        (is (contains? sub 50))
        (is (contains? sub 60))))

    (testing "one-sided bound <"
      (let [sub (oc/subrange inventory :< 30)]
        (is (= 2 (count sub)))
        (is (contains? sub 10))
        (is (contains? sub 20)))))

  (testing "subrange on set"
    (let [ids (oc/ordered-set (range 0 100 5))]
      (is (= [20 25 30 35] (vec (oc/subrange ids :>= 20 :< 40))))
      (is (= 7 (count (oc/subrange ids :>= 50 :<= 80)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 13. Floor/Ceiling Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest floor-ceiling-queries-test
  (let [versions (oc/ordered-set [100 200 300 450 500 800])]

    (testing "nearest :<="
      (is (= 300 (oc/nearest versions :<= 350)))
      (is (= 300 (oc/nearest versions :<= 300)))
      (is (nil? (oc/nearest versions :<= 50))))

    (testing "nearest :<"
      (is (= 200 (oc/nearest versions :< 300)))
      (is (= 300 (oc/nearest versions :< 350))))

    (testing "nearest :>="
      (is (= 450 (oc/nearest versions :>= 350)))
      (is (= 800 (oc/nearest versions :>= 800))))

    (testing "nearest :>"
      (is (= 800 (oc/nearest versions :> 500)))
      (is (nil? (oc/nearest versions :> 800)))))

  (testing "nearest on ordered-map"
    (let [config-versions (oc/ordered-map
                            [[100 {:feature-a true}]
                             [200 {:feature-a true :feature-b true}]
                             [350 {:feature-a true :feature-b true :feature-c true}]])]
      (is (= [200 {:feature-a true :feature-b true}]
             (oc/nearest config-versions :<= 300)))
      (is (= [350 {:feature-a true :feature-b true :feature-c true}]
             (oc/nearest config-versions :>= 300))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Performance Tips Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest performance-tips-test
  (testing "specialized constructors work"
    (let [long-set (oc/long-ordered-set (range 100))
          string-set (oc/string-ordered-set ["alice" "bob" "carol"])]
      (is (= 100 (count long-set)))
      (is (contains? long-set 50))
      (is (= 3 (count string-set)))
      (is (contains? string-set "bob"))))

  (testing "r/fold works on ordered collections"
    (let [s (oc/ordered-set (range 1000))]
      (is (= (reduce + (range 1000))
             (r/fold + s))))))
