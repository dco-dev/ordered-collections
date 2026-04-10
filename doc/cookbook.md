# Use Case Cookbook

Practical examples showing where ordered-collections shines.

## Setup

```clojure
(require '[ordered-collections.core :as oc])
(require '[clojure.core.reducers :as r])
(require '[clojure.string :as str])
```

---

## Ropes

The library provides three rope variants that share one tree kernel: a
generic `rope` for arbitrary Clojure values, a `string-rope` specialized
for text, and a `byte-rope` specialized for binary data. All three support
O(log n) concat, split, splice, insert, and remove; the specialized
variants add type-appropriate Java interop (CharSequence, byte[]) and
faster materialization.

---

## 1. Text Editor Buffer (StringRope)

**Problem:** An editor needs to insert, delete, and replace characters
anywhere in a document with low latency, regardless of document size.
Plain strings are O(n) per edit because every character after the cut
must be shifted. A StringRope makes every edit O(log n) and keeps old
versions available for free.

```clojure
(def doc (oc/string-rope "The quick brown fox jumps over the lazy dog."))

(count doc)      ;; => 44
(nth doc 10)     ;; => \b
(str doc)        ;; => "The quick brown fox jumps over the lazy dog."

;; Insert at cursor — O(log n)
(def v1 (oc/rope-insert doc 10 "dark "))
(str v1)
;; => "The quick dark brown fox jumps over the lazy dog."

;; Delete a range — O(log n)
(def v2 (oc/rope-remove v1 10 15))
(str v2)
;; => "The quick brown fox jumps over the lazy dog."

;; Find-and-replace is just splice — O(log n)
(def v3 (oc/rope-splice doc 16 19 "cat"))
(str v3)
;; => "The quick brown cat jumps over the lazy dog."

;; Extract the visible window — shares structure, no copying
(str (oc/rope-sub v3 4 19))
;; => "quick brown cat"

;; Undo history is free — every version is a persistent snapshot
;; sharing structure with its parent.
(def history [doc v1 v2 v3])
(mapv count history)  ;; => [44 49 44 44]
```

**Why StringRope?** String edits in the middle are O(n) because every
character after the cut must be shifted. A StringRope does each edit in
O(log n). At 100K characters with 200 random edits, StringRope is **~35x
faster than plain String**; at 500K characters it is orders of magnitude
better. `StringRope` implements `java.lang.CharSequence`, so it drops
into any Java API expecting text, and `(str sr)` materializes back to a
regular Java `String` whenever you need one.

---

## 2. Regex and clojure.string on Large Text (StringRope)

**Problem:** Run regex matching, `clojure.string` helpers, and ad-hoc
`java.util.regex.Matcher` work on a multi-megabyte log file that you
also want to edit in place.

```clojure
(def log-text
  (oc/string-rope
    (str "2026-04-10 09:14 INFO  started user=alice\n"
         "2026-04-10 09:14 INFO  request path=/home user=alice\n"
         "2026-04-10 09:15 ERROR auth failed token=xyz123 user=bob\n"
         "2026-04-10 09:16 INFO  request path=/login user=bob\n"
         "2026-04-10 09:17 ERROR db  timeout password=s3cret user=bob\n")))

;; StringRope implements CharSequence, so all of java.util.regex works directly.
(re-seq #"ERROR.*" log-text)
;; => ("ERROR auth failed token=xyz123 user=bob"
;;     "ERROR db  timeout password=s3cret user=bob")

;; clojure.string functions accept CharSequence
(count (str/split-lines log-text))
;; => 5

;; Redact sensitive fields — each replace is O(log n) per match,
;; not O(n) per match like a flat String.
(def sanitized
  (-> log-text
      (str/replace #"password=\S+" "password=<REDACTED>")
      (str/replace #"token=\S+"    "token=<REDACTED>")))

(str/includes? (str sanitized) "<REDACTED>")  ;; => true

;; java.util.regex.Matcher works on the rope directly
(let [m (re-matcher #"user=(\w+)" log-text)]
  (loop [users #{}]
    (if (.find m)
      (recur (conj users (.group m 1)))
      users)))
;; => #{"alice" "bob"}
```

**Why StringRope?** The `CharSequence` contract means every Java text
API works without conversion. You can hold a multi-megabyte log as a
rope, run regex and `clojure.string` over it, and splice in edits — all
in O(log n) per edit. A plain `String` would force an O(n) copy on
every `str/replace` match.

---

## 3. Assembling Large Sequences from Many Parts (Rope)

**Problem:** Collect data from many sources and merge the pieces into
one indexable sequence without paying O(n²) for repeated `into`.

```clojure
;; Imagine sensor batches arriving from many collectors
(def batches
  (for [i (range 20)]
    (vec (range (* i 1000) (* (inc i) 1000)))))

;; Naive vector concat — each `into` copies everything accumulated.
;; Fine for 4 batches, collapses to O(n²) for many.
(def naive (reduce into [] batches))

;; Rope concat — O(k log n). Each piece is joined structurally;
;; nothing is copied.
(def combined (apply oc/rope-concat (map oc/rope batches)))

(count combined)  ;; => 20000
(nth combined 12345)
;; => 12345

;; The combined rope stays fully efficient for downstream work
(reduce + 0 combined)
;; => 199990000

;; Parallel fold splits along the natural tree structure
(r/fold + combined)
;; => 199990000
```

**Why Rope?** When you assemble a sequence from many sources, vector
`into` is O(*total accumulated*) at each step and degrades to O(n²).
Rope concat is O(log n) per join, and the combined rope remains
efficient for random access, reduce, parallel fold, and further
splicing.

---

## 4. Binary Protocol Assembly (ByteRope)

**Problem:** Build a framed network message with a length header plus
payload, then insert a checksum, then slice out just the payload. With
`byte[]` every edit forces an `arraycopy` of the entire buffer; with
ByteRope each edit is O(log n).

```clojure
;; Framed message format: [u32 big-endian length] [payload bytes]
(defn pack-message [^bytes payload]
  (let [len    (alength payload)
        header (byte-array 4)]
    (aset header 0 (unchecked-byte (bit-shift-right len 24)))
    (aset header 1 (unchecked-byte (bit-shift-right len 16)))
    (aset header 2 (unchecked-byte (bit-shift-right len 8)))
    (aset header 3 (unchecked-byte len))
    (oc/byte-rope-concat (oc/byte-rope header) (oc/byte-rope payload))))

(def msg (pack-message (.getBytes "Hello, World!" "UTF-8")))

(count msg)                           ;; => 17
(oc/byte-rope-get-int msg 0)          ;; => 13   (4-byte BE length)
(oc/byte-rope-get-byte msg 4)         ;; => 72   (unsigned 'H')
(oc/byte-rope-hex msg)
;; => "0000000d48656c6c6f2c20576f726c6421"

;; Splice a checksum between header and payload — O(log n)
(def with-csum
  (oc/rope-insert msg 4 (byte-array [(unchecked-byte 0xde)
                                     (unchecked-byte 0xad)
                                     (unchecked-byte 0xbe)
                                     (unchecked-byte 0xef)])))

(oc/byte-rope-hex with-csum)
;; => "0000000ddeadbeef48656c6c6f2c20576f726c6421"

;; Extract just the payload — shares structure with the original
(def payload (oc/rope-sub with-csum 8 (count with-csum)))
(String. (oc/byte-rope-bytes payload) "UTF-8")
;; => "Hello, World!"
```

**Why ByteRope?** Bytes are exposed as unsigned longs (0–255), avoiding
signed-byte pitfalls. Big-endian and little-endian multi-byte reads
(`byte-rope-get-short/int/long` with `-le` variants) are built in. You
can splice, insert, or remove byte ranges in O(log n) instead of copying
the whole buffer, which is exactly what protocol assembly and packet
editing want.

---

## 5. Streaming Cryptographic Digest (ByteRope)

**Problem:** Compute a SHA-256 (or any `MessageDigest` algorithm) over
a large binary value without materializing the whole thing as one
`byte[]`.

```clojure
;; Build a ~2 MB rope from 1-KB chunks — no intermediate copies
(def large-data
  (apply oc/byte-rope-concat
    (for [i (range 2048)]
      (byte-array 1024 (unchecked-byte i)))))

(count large-data)  ;; => 2097152

;; Compute SHA-256 by streaming chunks through MessageDigest.
;; The rope never materializes the whole thing — each chunk is fed
;; directly into the digest in its natural block size.
(oc/byte-rope-hex (oc/byte-rope-digest large-data "SHA-256"))
;; => "…64 hex chars…"

;; Any algorithm the JVM supports
(oc/byte-rope-hex (oc/byte-rope-digest large-data "MD5"))
(oc/byte-rope-hex (oc/byte-rope-digest large-data "SHA-512"))

;; Digests are themselves byte-ropes, so you can splice them into
;; other messages without conversion
(def stamped
  (oc/byte-rope-concat
    large-data
    (oc/byte-rope-digest large-data "SHA-256")))
```

**Why ByteRope?** `byte-rope-digest` iterates the rope chunk-by-chunk
through `java.security.MessageDigest` without building an intermediate
`byte[]`. The same pattern applies to streaming compression, encryption,
and any block-oriented consumer. For multi-gigabyte ropes this is the
difference between working and OOM.

---

## 6. Persistent Undo History (any rope variant)

**Problem:** Keep an arbitrarily long edit history for a document
without paying the memory cost of a full copy per version.

```clojure
(def v0 (oc/string-rope "initial document"))
(def v1 (oc/rope-insert  v0 0   "The "))
(def v2 (oc/rope-splice  v1 4 7 "my"))
(def v3 (oc/rope-splice  v2 0 3 "this is"))
(def v4 (oc/rope-insert  v3 (count v3) "!"))

(mapv str [v0 v1 v2 v3 v4])
;; => ["initial document"
;;     "The initial document"
;;     "The my document"
;;     "this is my document"
;;     "this is my document!"]

;; All five versions coexist. Each is a persistent snapshot that shares
;; structure with its neighbours — most of the internal tree nodes are
;; reused across versions.

;; Undo is just picking an older reference
(def current v4)
(def after-undo (nth [v0 v1 v2 v3] 2))
(str after-undo)
;; => "The my document"

;; Diff two versions without materializing either
(= v1 v3)    ;; => false
(count v1)   ;; => 20
(count v3)   ;; => 19
```

**Why Rope?** Persistent ropes make undo trivial — every edit returns
a new value whose internal tree mostly overlaps the previous one. You
can keep hundreds of historical versions of a megabyte document for
the cost of tens of kilobytes. The same pattern works for StringRope
(text editors), ByteRope (binary patch editors), and generic Rope
(any sequential data with a cursor).

---

## 7. Leaderboard with Rank Queries

**Problem:** Maintain a leaderboard where you need to:
- Add player scores
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
  (oc/rank board [score player-id]))

(defn players-around-rank [board rank window]
  (let [start (max 0 (- rank window))
        end   (min (count board) (+ rank window 1))]
    (map-indexed (fn [i [[score id] data]]
                   {:rank (+ start i) :id id :score score})
                 (oc/slice board start end))))

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

## 8. Time-Series Windowing

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

## 9. Meeting Room Scheduler

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

(conflicts-during room-a 1030 1100)
;; => [{:title "Design Review", :organizer "bob"}]

(is-available? room-a 1200 1400)  ;; => true
(is-available? room-a 1430 1530)  ;; => false
```

**Why ordered-collections?** Interval queries in O(log n + k) where k is the number of overlapping intervals. Linear scan would be O(n).

---

## 10. Persistent Work Queue

**Problem:** Schedule work by priority, while keeping stable ordering among equal priorities.

```clojure
(defn make-work-queue []
  (oc/priority-queue))

(defn enqueue [q priority task]
  (oc/push q priority task))

(defn next-task [q]
  (oc/peek-min q))

(defn run-next [q]
  (let [[priority task] (oc/peek-min q)]
    {:task task
     :remaining (oc/pop-min q)}))

;; Usage
(def q (-> (make-work-queue)
           (enqueue 5 {:job :backup})
           (enqueue 1 {:job :page-oncall})
           (enqueue 2 {:job :send-email})
           (enqueue 1 {:job :invalidate-cache})))

(next-task q)
;; => [1 {:job :page-oncall}]

(-> q run-next :task)
;; => {:job :page-oncall}

(-> q run-next :remaining next-task)
;; => [1 {:job :invalidate-cache}]
```

**Why ordered-collections?** A persistent priority queue gives O(log n) enqueue/dequeue while preserving insertion order among equal priorities.

---

## 11. Parallel Aggregation

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

## 12. Efficient Set Algebra

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

## 13. Sliding Window Statistics

**Problem:** Maintain statistics over a sliding time window.

```clojure
(defn make-window [max-age-ms]
  {:data (oc/ordered-map)  ; timestamp -> value
   :max-age max-age-ms})

(defn add-sample [{:keys [data max-age] :as window} timestamp value]
  (let [cutoff (- timestamp max-age)
        ;; Keep samples at or after the cutoff.
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

## 14. Range Aggregate Queries (Segment Tree)

**Problem:** Answer "what is the sum/max/min of values from key a to key b?" with efficient updates.

```clojure
;; Daily sales data
(def sales
  (oc/segment-tree + 0  ; operation and identity
    {0 1200, 1 1500, 2 1100, 3 1800, 4 2200, 5 1900, 6 1600}))

;; Query: total sales for days 2-5
(oc/query sales 2 5)
;; => 7000 (1100 + 1800 + 2200 + 1900)

;; Query: total for entire week
(oc/query sales 0 6)
;; => 11300

;; Update day 3's sales (O(log n) update, not rebuild)
(def sales-updated (assoc sales 3 2500))
(oc/query sales-updated 2 5)
;; => 7700 (1100 + 2500 + 2200 + 1900)

;; Track peak daily sales
(def peaks (oc/segment-tree max 0 {0 1200, 1 1500, 2 1100, 3 1800, 4 2200, 5 1900, 6 1600}))
(oc/query peaks 0 6)
;; => 2200 (max across all days)

(oc/query peaks 0 2)
;; => 1500 (max for days 0-2)

;; Shorthand for sum trees
(def sum-tree (oc/sum-tree {0 100, 1 200, 2 300, 3 400}))
(oc/query sum-tree 1 3)
;; => 900 (200 + 300 + 400)
```

**Why ordered-collections?** O(log n) range queries and O(log n) updates. Linear scan would be O(n) per query.

---

## 15. Database Index Simulation

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

## 16. Ordered Multiset

**Problem:** Track duplicate values while keeping them sorted.

```clojure
(def readings
  (oc/ordered-multiset [72 68 72 70 68 72 71]))

(seq readings)
;; => (68 68 70 71 72 72 72)

(oc/multiplicity readings 72)
;; => 3

(oc/distinct-elements readings)
;; => (68 70 71 72)

(oc/element-frequencies readings)
;; => {68 2, 70 1, 71 1, 72 3}

(-> readings
    (oc/disj-one 72)
    (oc/multiplicity 72))
;; => 2
```

**Why ordered-collections?** You get sorted duplicate-preserving semantics with efficient counting and removal of one occurrence.

---

## 17. Fuzzy Lookup / Nearest Neighbor

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

## 18. Splitting Collections

**Problem:** Partition a collection at a key or index for divide-and-conquer algorithms.

```clojure
(def prices (oc/ordered-set [100 200 300 400 500 600 700 800 900 1000]))

;; split-key: partition at a key value
;; Returns [elements-below, exact-match-or-nil, elements-above]
(let [[below match above] (oc/split-key 500 prices)]
  {:below (vec below)    ;; => [100 200 300 400]
   :match match          ;; => 500
   :above (vec above)})  ;; => [600 700 800 900 1000]

;; Key doesn't have to exist
(let [[below match above] (oc/split-key 550 prices)]
  {:below (vec below)    ;; => [100 200 300 400 500]
   :match match          ;; => nil
   :above (vec above)})  ;; => [600 700 800 900 1000]

;; split-at: partition at an index
;; Returns [left, right]
(let [[left right] (oc/split-at 3 prices)]
  {:left (vec left)      ;; => [100 200 300]
   :right (vec right)})  ;; => [400 500 600 700 800 900 1000]

;; Useful for pagination
(defn paginate [coll page-size page-num]
  (let [offset (* page-size page-num)
        [_ remaining] (oc/split-at offset coll)
        [page _] (oc/split-at page-size remaining)]
    (vec page)))

(paginate prices 3 1)  ;; => [400 500 600] (page 1, 0-indexed)
```

**Why ordered-collections?** O(log n) split operations. Essential for parallel algorithms and range partitioning.

---

## 19. Subrange Extraction

**Problem:** Extract a contiguous range of elements by key bounds.

```clojure
(def inventory
  (oc/ordered-map
    [[10 "widget-a"] [20 "widget-b"] [30 "widget-c"]
     [40 "widget-d"] [50 "widget-e"] [60 "widget-f"]]))

;; Two-sided bounds
(oc/subrange inventory :>= 25 :<= 50)
;; => {30 "widget-c", 40 "widget-d", 50 "widget-e"}

;; One-sided bounds
(oc/subrange inventory :> 40)
;; => {50 "widget-e", 60 "widget-f"}

(oc/subrange inventory :< 30)
;; => {10 "widget-a", 20 "widget-b"}

;; Works with sets too
(def ids (oc/ordered-set (range 0 100 5)))  ; 0, 5, 10, ..., 95
(vec (oc/subrange ids :>= 20 :< 40))
;; => [20 25 30 35]

;; Count elements in range without materializing
(count (oc/subrange ids :>= 50 :<= 80))  ;; => 7
```

**Why ordered-collections?** Returns a new ordered collection that shares structure with the original tree. O(log n) to create, efficient to iterate.

---

## 20. Floor/Ceiling Queries

**Problem:** Find the nearest element at or above/below a target.

```clojure
(def versions (oc/ordered-set [100 200 300 450 500 800]))

;; Find version at or below target
(oc/nearest versions :<= 350)  ;; => 300
(oc/nearest versions :<= 300)  ;; => 300 (exact match)
(oc/nearest versions :<= 50)   ;; => nil (nothing at or below)

;; Find version strictly below target
(oc/nearest versions :< 300)   ;; => 200

;; Find version at or above target
(oc/nearest versions :>= 350)  ;; => 450
(oc/nearest versions :>= 800)  ;; => 800

;; Find version strictly above target
(oc/nearest versions :> 500)   ;; => 800

;; Practical: find applicable config version
(def config-versions
  (oc/ordered-map
    [[100 {:feature-a true}]
     [200 {:feature-a true :feature-b true}]
     [350 {:feature-a true :feature-b true :feature-c true}]]))

(defn config-for-version [v]
  (when-let [[_ config] (oc/nearest config-versions :<= v)]
    config))

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
   (oc/subrange my-set :>= 100 :< 200)

   ;; Slow: creates intermediate seq, tests every element
   (filter #(<= 100 % 199) my-set)
   ```

6. **Use `nearest` for floor/ceiling**
   ```clojure
   ;; Fast: O(log n)
   (oc/nearest my-set :<= target)

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

8. **Pick the right rope variant**
   ```clojure
   ;; Text editing — StringRope beats plain String at ~100+ chars
   (oc/string-rope "…")

   ;; Binary data — ByteRope beats byte[] once edits get expensive
   (oc/byte-rope #_…)

   ;; Anything else sequential — the generic rope
   (oc/rope [1 2 3 …])
   ```
