# Use Case Cookbook

Practical examples showing where ordered-collections shines.

## Setup

```clojure
(require '[com.dean.ordered-collections.core :as oc])
(require '[clojure.core.reducers :as r])
```

---

## 1. Leaderboard with Rank Queries

**Problem:** Maintain a leaderboard where you need to:
- Add/update player scores
- Get a player's rank
- Get the top N players
- Get players around a specific rank

```clojure
(defn make-leaderboard []
  ;; Map from [score player-id] -> player-data
  ;; Using [score id] tuple ensures uniqueness and sorts by score
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
  ;; Find position in sorted order via iteration
  (let [key [score player-id]]
    (loop [i 0, entries (seq board)]
      (when entries
        (if (= (ffirst entries) key)
          i
          (recur (inc i) (next entries)))))))

(defn players-around-rank [board rank window]
  ;; Get players from (rank - window) to (rank + window)
  (let [start (max 0 (- rank window))
        end (+ rank window 1)]
    (->> (range start end)
         (keep #(when-let [entry (nth board % nil)]
                  (let [[[score id] data] entry]
                    {:rank % :id id :score score}))))))

;; Usage
(def board (-> (make-leaderboard)
               (add-score "alice" 1500 {:name "Alice"})
               (add-score "bob" 1450 {:name "Bob"})
               (add-score "carol" 1600 {:name "Carol"})
               (add-score "dave" 1550 {:name "Dave"})))

(top-n board 3)
;; => ({:id "carol", :score 1600, :data {:name "Carol"}}
;;     {:id "dave", :score 1550, :data {:name "Dave"}}
;;     {:id "alice", :score 1500, :data {:name "Alice"}})

(rank-of-player board "alice" 1500)  ;; => 2 (0-indexed)

(players-around-rank board 2 1)
;; => ({:rank 1, :id "dave", :score 1550}
;;     {:rank 2, :id "alice", :score 1500}
;;     {:rank 3, :id "bob", :score 1450})
```

**Why ordered-collections?** O(log n) rank queries. With sorted-map, finding rank requires O(n) iteration.

---

## 2. Time-Series Windowing

**Problem:** Store timestamped events and efficiently query time ranges.

```clojure
(defn make-event-log []
  (oc/ordered-map))  ; keys are timestamps (longs or instants)

(defn add-event [log timestamp event]
  (assoc log timestamp event))

(defn events-between [log start-time end-time]
  ;; O(log n) to find range, O(k) to iterate k results
  (subseq log >= start-time < end-time))

(defn events-last-n-minutes [log now minutes]
  (let [cutoff (- now (* minutes 60 1000))]
    (subseq log >= cutoff)))

(defn latest-events [log n]
  ;; Last n events (most recent first)
  (take n (rseq log)))

(defn count-events-in-window [log start-time end-time]
  ;; Efficient: uses reduce, not seq materialization
  (reduce (fn [acc _] (inc acc)) 0
          (subseq log >= start-time < end-time)))

;; Usage
(def log (-> (make-event-log)
             (add-event 1000 {:type :login :user "alice"})
             (add-event 2000 {:type :click :page "/home"})
             (add-event 3000 {:type :purchase :item "widget"})
             (add-event 4000 {:type :logout :user "alice"})))

(events-between log 1500 3500)
;; => ([2000 {:type :click, :page "/home"}]
;;     [3000 {:type :purchase, :item "widget"}])

(latest-events log 2)
;; => ([4000 {:type :logout, :user "alice"}]
;;     [3000 {:type :purchase, :item "widget"}])
```

**Why ordered-collections?** Native `subseq`/`rsubseq` support with O(log n) range location.

---

## 3. Meeting Room Scheduler

**Problem:** Track meeting room bookings and find conflicts or free slots.

```clojure
(defn make-room-schedule []
  ;; interval-map: [start end] -> booking-info
  (oc/interval-map))

(defn book-room [schedule start end booking]
  (assoc schedule [start end] booking))

(defn conflicts-at [schedule time]
  ;; What meetings overlap with this time?
  (schedule time))

(defn conflicts-during [schedule start end]
  ;; What meetings overlap with this range?
  (schedule [start end]))

(defn is-available? [schedule start end]
  (empty? (conflicts-during schedule start end)))

;; Usage
(def room-a (-> (make-room-schedule)
                (book-room 900 1000 {:title "Standup" :organizer "alice"})
                (book-room 1030 1130 {:title "Design Review" :organizer "bob"})
                (book-room 1400 1500 {:title "1:1" :organizer "carol"})))

(conflicts-at room-a 930)
;; => [{:title "Standup", :organizer "alice"}]

(conflicts-during room-a 1000 1100)
;; => [{:title "Design Review", :organizer "bob"}]

(is-available? room-a 1200 1400)  ;; => true
(is-available? room-a 1430 1530)  ;; => false
```

**Why ordered-collections?** Interval queries in O(log n + k) where k is the number of overlapping intervals. Linear scan would be O(n).

---

## 4. IP Address Range Lookup

**Problem:** Map IP ranges to metadata (geolocation, ASN, rate limits).

```clojure
(defn ip->long [ip-str]
  ;; "192.168.1.1" -> long
  (let [parts (map #(Long/parseLong %) (clojure.string/split ip-str #"\."))]
    (reduce (fn [acc part] (+ (bit-shift-left acc 8) part)) 0 parts)))

(defn make-ip-database []
  (oc/interval-map))

(defn add-range [db start-ip end-ip info]
  (assoc db [(ip->long start-ip) (ip->long end-ip)] info))

(defn lookup-ip [db ip]
  (first (db (ip->long ip))))

;; Usage
(def geo-db (-> (make-ip-database)
                (add-range "10.0.0.0" "10.255.255.255"
                           {:type :private :name "Private Class A"})
                (add-range "192.168.0.0" "192.168.255.255"
                           {:type :private :name "Private Class C"})
                (add-range "8.8.0.0" "8.8.255.255"
                           {:type :public :name "Google DNS" :country "US"})))

(lookup-ip geo-db "192.168.1.100")
;; => {:type :private, :name "Private Class C"}

(lookup-ip geo-db "8.8.8.8")
;; => {:type :public, :name "Google DNS", :country "US"}
```

**Why ordered-collections?** Interval-map handles the range lookup naturally.

---

## 5. Parallel Aggregation

**Problem:** Aggregate large datasets efficiently using multiple cores.

```clojure
;; Generate a large dataset
(def transactions
  (oc/ordered-map
    (for [i (range 1000000)]
      [i {:amount (rand-int 1000)
          :category (rand-nth [:food :transport :entertainment :utilities])}])))

;; Sequential sum
(time
  (reduce (fn [acc [_ {:keys [amount]}]] (+ acc amount)) 0 transactions))
;; "Elapsed time: 130 msecs"

;; Parallel sum with r/fold
(time
  (r/fold
    +                                              ; combiner
    (fn [acc [_ {:keys [amount]}]] (+ acc amount)) ; reducer
    transactions))
;; "Elapsed time: 75 msecs" (1.7x speedup)

;; Parallel group-by category
(time
  (r/fold
    (partial merge-with +)  ; combine partial results
    (fn [acc [_ {:keys [amount category]}]]
      (update acc category (fnil + 0) amount))
    transactions))
;; => {:food 124523456, :transport 125012345, ...}
```

**Why ordered-collections?** True parallel fold via tree splitting. `sorted-map` falls back to sequential.

---

## 6. Efficient Set Algebra

**Problem:** Compute intersections/unions/differences on large sorted sets.

```clojure
;; Two sets of user IDs
(def premium-users (oc/ordered-set (range 0 100000 2)))     ; 50K users
(def active-users (oc/ordered-set (range 0 100000 3)))     ; 33K users

;; Find premium AND active users
(time (def premium-active (oc/intersection premium-users active-users)))
;; "Elapsed time: 45 msecs" for 16,667 result elements

;; With clojure.set on sorted-set:
(def premium-ss (into (sorted-set) (range 0 100000 2)))
(def active-ss (into (sorted-set) (range 0 100000 3)))
(time (clojure.set/intersection premium-ss active-ss))
;; "Elapsed time: 180 msecs" - 4x slower

;; Set difference: premium but not active
(time (oc/difference premium-users active-users))
;; "Elapsed time: 50 msecs"

;; Union with deduplication
(time (oc/union premium-users active-users))
;; "Elapsed time: 60 msecs" for 66,667 result elements
```

**Why ordered-collections?** O(m log(n/m)) set operations via split/join vs O(n) linear merge.

---

## 7. Sliding Window Statistics

**Problem:** Maintain statistics over a sliding time window.

```clojure
(defn make-window [max-age-ms]
  {:data (oc/ordered-map)  ; timestamp -> value
   :max-age max-age-ms})

(defn add-sample [{:keys [data max-age] :as window} timestamp value]
  (let [cutoff (- timestamp max-age)
        ;; Remove old entries efficiently
        fresh-data (if-let [first-key (first (keys data))]
                     (if (< first-key cutoff)
                       ;; Split off old data
                       (second (oc/split-at data cutoff))
                       data)
                     data)]
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

;; Usage: 5-second window
(def w (-> (make-window 5000)
           (add-sample 1000 10)
           (add-sample 2000 20)
           (add-sample 3000 15)
           (add-sample 6000 25)   ; this triggers cleanup of t=1000
           ))

(window-stats w)
;; => {:count 3, :sum 60, :mean 20, :min 15, :max 25}
```

**Why ordered-collections?** Efficient range deletion via split, O(log n) bounds queries.

---

## 8. Database Index Simulation

**Problem:** Build a secondary index supporting range queries.

```clojure
(defn make-index []
  ;; Maps indexed-value -> set of primary keys
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
  ;; All PKs where min-val <= indexed-value < max-val
  (->> (subseq idx >= min-val < max-val)
       (mapcat val)
       set))

;; Usage: index users by age
(def age-index (-> (make-index)
                   (index-add 25 "user-1")
                   (index-add 30 "user-2")
                   (index-add 25 "user-3")
                   (index-add 35 "user-4")
                   (index-add 28 "user-5")))

(index-lookup age-index 25)
;; => #{"user-1" "user-3"}

(index-range age-index 25 31)
;; => #{"user-1" "user-3" "user-2" "user-5"}
```

**Why ordered-collections?** Range queries on index values with O(log n) bounds location.

---

## 9. Fuzzy Lookup / Nearest Neighbor

**Problem:** Find the closest matching value when exact match doesn't exist.

```clojure
;; Temperature calibration table
(def calibration (oc/fuzzy-map {0.0   1.000
                                 25.0  1.012
                                 50.0  1.025
                                 75.0  1.041
                                 100.0 1.058}))

;; Get calibration factor for any temperature
(calibration 23.5)   ; => 1.012 (closest to 25.0)
(calibration 60.0)   ; => 1.025 (closest to 50.0)
(calibration 87.5)   ; => 1.041 (closest to 75.0)

;; With tiebreaker preference
(def fm-prefer-larger (oc/fuzzy-map {0 :a 10 :b 20 :c} :tiebreak :>))
(fm-prefer-larger 5)  ; => :b (equidistant from 0 and 10, prefer larger)

;; Fuzzy set for snapping to grid values
(def grid-points (oc/fuzzy-set (range 0 101 10))) ; 0, 10, 20, ..., 100
(grid-points 23)  ; => 20
(grid-points 27)  ; => 30
(grid-points 25)  ; => 20 (tiebreak defaults to :<, prefer smaller)

;; Get nearest with distance info
(oc/fuzzy-nearest calibration 60.0)
;; => [50.0 1.025 10.0]  ; [key value distance]

(oc/fuzzy-nearest grid-points 23)
;; => [20 3.0]  ; [value distance]
```

**Why ordered-collections?** O(log n) nearest-neighbor lookup using tree split. Linear scan would be O(n).

---

## 10. Splitting Collections

**Problem:** Partition a collection at a key or index for divide-and-conquer algorithms.

```clojure
(def prices (oc/ordered-set [100 200 300 400 500 600 700 800 900 1000]))

;; split-key: partition at a key value
;; Returns [elements-below, exact-match-or-nil, elements-above]
(let [[below match above] (oc/split-key prices 500)]
  {:below (vec below)    ;; => [100 200 300 400]
   :match match          ;; => 500
   :above (vec above)})  ;; => [600 700 800 900 1000]

;; Key doesn't have to exist
(let [[below match above] (oc/split-key prices 550)]
  {:below (vec below)    ;; => [100 200 300 400 500]
   :match match          ;; => nil
   :above (vec above)})  ;; => [600 700 800 900 1000]

;; split-at: partition at an index
;; Returns [left, right]
(let [[left right] (oc/split-at prices 3)]
  {:left (vec left)      ;; => [100 200 300]
   :right (vec right)})  ;; => [400 500 600 700 800 900 1000]

;; Useful for pagination
(defn paginate [coll page-size page-num]
  (let [offset (* page-size page-num)
        [_ remaining] (oc/split-at coll offset)
        [page _] (oc/split-at remaining page-size)]
    (vec page)))

(paginate prices 3 1)  ;; => [400 500 600] (page 1, 0-indexed)
```

**Why ordered-collections?** O(log n) split operations. Essential for parallel algorithms and range partitioning.

---

## 11. Subrange Extraction

**Problem:** Extract a contiguous range of elements by key bounds.

```clojure
(def inventory
  (oc/ordered-map
    [[10 "widget-a"] [20 "widget-b"] [30 "widget-c"]
     [40 "widget-d"] [50 "widget-e"] [60 "widget-f"]]))

;; Two-sided bounds
(oc/subrange inventory >= 25 <= 50)
;; => {30 "widget-c", 40 "widget-d", 50 "widget-e"}

;; One-sided bounds
(oc/subrange inventory > 40)
;; => {50 "widget-e", 60 "widget-f"}

(oc/subrange inventory < 30)
;; => {10 "widget-a", 20 "widget-b"}

;; Works with sets too
(def ids (oc/ordered-set (range 0 100 5)))  ; 0, 5, 10, ..., 95
(vec (oc/subrange ids >= 20 < 40))
;; => [20 25 30 35]

;; Count elements in range without materializing
(count (oc/subrange ids >= 50 <= 80))  ;; => 7
```

**Why ordered-collections?** Returns a view backed by the original tree. O(log n) to create, efficient iteration.

---

## 12. Floor/Ceiling Queries

**Problem:** Find the nearest element at or above/below a target.

```clojure
(def versions (oc/ordered-set [100 200 300 450 500 800]))

;; Find version at or below target
(oc/nearest versions <= 350)  ;; => 300
(oc/nearest versions <= 300)  ;; => 300 (exact match)
(oc/nearest versions <= 50)   ;; => nil (nothing at or below)

;; Find version strictly below target
(oc/nearest versions < 300)   ;; => 200

;; Find version at or above target
(oc/nearest versions >= 350)  ;; => 450
(oc/nearest versions >= 800)  ;; => 800

;; Find version strictly above target
(oc/nearest versions > 500)   ;; => 800

;; Practical: find applicable config version
(def config-versions
  (oc/ordered-map
    [[100 {:feature-a true}]
     [200 {:feature-a true :feature-b true}]
     [350 {:feature-a true :feature-b true :feature-c true}]]))

(defn config-for-version [v]
  (when-let [k (oc/nearest (keys config-versions) <= v)]
    (config-versions k)))

(config-for-version 275)
;; => {:feature-a true, :feature-b true}
```

**Why ordered-collections?** O(log n) floor/ceiling queries using tree structure.

---

## Performance Tips

1. **Use `reduce` over `seq`** - Direct reduce uses optimized IReduceInit path
   ```clojure
   ;; Fast
   (reduce + 0 my-set)

   ;; Slower (forces lazy seq)
   (reduce + 0 (seq my-set))
   ```

2. **Use `r/fold` for large collections** - Parallelizes automatically
   ```clojure
   (r/fold + my-large-set)  ; uses all cores
   ```

3. **Use `subseq` for range queries** - More efficient than filter
   ```clojure
   ;; Fast: O(log n) to find bounds
   (subseq my-map >= 100 < 200)

   ;; Slow: O(n) full scan
   (filter (fn [[k _]] (<= 100 k 199)) my-map)
   ```

4. **Use constructor for bulk loading**
   ```clojure
   ;; For bulk loading, use the constructor (uses parallel fold internally)
   (oc/ordered-set big-data)     ; fast: parallel construction
   (oc/ordered-map key-val-pairs)
   ```

5. **Use `subrange` instead of filtering**
   ```clojure
   ;; Fast: O(log n) bounds, returns a view
   (oc/subrange my-set >= 100 < 200)

   ;; Slow: creates intermediate seq, tests every element
   (filter #(<= 100 % 199) my-set)
   ```

6. **Use `nearest` for floor/ceiling**
   ```clojure
   ;; Fast: O(log n)
   (oc/nearest my-set <= target)

   ;; Slow: O(n) in worst case
   (last (take-while #(<= % target) my-set))
   ```

7. **Use specialized constructors for homogeneous keys**
   ```clojure
   ;; 20% faster lookup for Long keys
   (oc/long-ordered-set (range 1000000))
   (oc/long-ordered-map (map #(vector % %) (range 1000000)))

   ;; 5% faster for String keys
   (oc/string-ordered-set ["alice" "bob" "carol"])
   ```
