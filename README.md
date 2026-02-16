# com.dean/ordered-collections

**Sorted collections that do more.** Drop-in replacements for `sorted-set` and `sorted-map` with O(log n) positional access, 7-9x faster set operations, and parallel fold support—plus specialized collections you didn't know you needed.

Need to find what's scheduled at 3pm? **Interval maps** let you query overlapping ranges. Building a leaderboard? Get any player's rank in O(log n). Working with sensor data? **Fuzzy lookup** snaps queries to the nearest calibration point. Managing IP allocations? **Range maps** carve out non-overlapping regions.

All built on an extensible weight-balanced tree platform with a shared foundation
for efficient splitting, joining, and parallel operations.

![tests](https://github.com/dco-dev/ordered-collections/actions/workflows/clojure.yml/badge.svg)
[![Clojars Project](https://img.shields.io/clojars/v/com.dean/ordered-collections.svg)](https://clojars.org/com.dean/ordered-collections)

### Documentation

- [Zorp's Sneaker Emporium](doc/zorp-example.md) — Narrative guide and  interesting examples
- [Cookbook](doc/cookbook.md) — Practical examples: leaderboards,
  time-series, scheduling, IP ranges, parallel aggregation
- [When to Use](doc/when-to-use.md) — Decision guide for choosing the right collection type
- [Benchmarks](doc/benchmarks.md) — Detailed performance measurements
- [Competitive Analysis](doc/competitive-analysis.md) — Comparison with other libraries
- [vs clojure.data.avl](doc/vs-clojure-data-avl.md) — Detailed comparison for data.avl users
- [Algorithms](doc/algorithms.md) — Tree structure, rotations, split/join, interval augmentation
- [Why Weight-Balanced Trees?](doc/why-weight-balanced-trees.md) —
  Comparison with red-black and AVL trees
- [Performance Analysis](doc/perf-analysis.md) — In-depth performance thoughts

---

## Quick Start

Use `ordered-set` and `ordered-map` exactly like `sorted-set` and `sorted-map`:

```clojure

(require '[com.dean.ordered-collections.core :as oc])

;; Sets
(def s (oc/ordered-set [3 1 4 1 5 9 2 6]))
(s 4)           ;=> 4
(s 7)           ;=> nil
(conj s 0)      ;=> #{0 1 2 3 4 5 6 9}
(disj s 4)      ;=> #{1 2 3 5 6 9}
(first s)       ;=> 1
(last s)        ;=> 9
(subseq s > 3)  ;=> (4 5 6 9)

;; Maps
(def m (oc/ordered-map {:b 2 :a 1 :c 3}))
(m :b)                  ;=> 2
(assoc m :d 4)          ;=> {:a 1, :b 2, :c 3, :d 4}
(subseq m >= :b <= :c)  ;=> ([:b 2] [:c 3])
```

That's it. All the functions you know work the same way. The difference
is under the hood: faster set operations, O(log n) positional access,
parallel fold support, and more.

### Key Features

- **Full `clojure.lang.Sorted` support**: Use `subseq` and `rsubseq` natively
- **O(log n) first/last**: Via `java.util.SortedSet` interface (~7000x faster than `sorted-set` at scale)
- **O(log n) nth and rank**: Positional access and rank queries in logarithmic time
- **O(log n) split/subrange**: Split at key or index, extract ranges efficiently
- **O(log n) floor/ceiling**: Find nearest element via `nearest`
- **Parallel fold**: All types implement `CollFold` for efficient `r/fold` (2.3x faster)
- **Fast set operations**: Union, intersection, difference 7-9x faster than `clojure.set`
- **Proper hashing**: `IHashEq` support for correct behavior in hash-based collections
- **Serializable**: `java.io.Serializable` marker interface
- **Fast iteration**: Optimized `IReduceInit`/`IReduce` (faster than `sorted-set`)

### Constructors

| Constructor | Description |
|-------------|-------------|
| `(oc/ordered-set coll)` | Sorted set (drop-in replacement for `sorted-set`) |
| `(oc/ordered-set-by pred coll)` | Sorted set with custom comparator |
| `(oc/long-ordered-set coll)` | Sorted set optimized for Long keys (20% faster lookup) |
| `(oc/string-ordered-set coll)` | Sorted set optimized for String keys |
| `(oc/ordered-map coll)` | Sorted map (drop-in replacement for `sorted-map`) |
| `(oc/ordered-map-by pred coll)` | Sorted map with custom comparator |
| `(oc/long-ordered-map coll)` | Sorted map optimized for Long keys |
| `(oc/string-ordered-map coll)` | Sorted map optimized for String keys |
| `(oc/interval-set coll)` | Set supporting interval overlap queries |
| `(oc/interval-map coll)` | Map supporting interval overlap queries |
| `(oc/range-map)` | Non-overlapping ranges (Google Guava TreeRangeMap) |
| `(oc/segment-tree f identity coll)` | O(log n) range aggregate queries |
| `(oc/ranked-set coll)` | Sorted set with O(log n) rank and nth |
| `(oc/priority-queue pairs)` | Priority queue from `[[priority value] ...]` pairs |
| `(oc/ordered-multiset coll)` | Sorted multiset (allows duplicates) |
| `(oc/fuzzy-set coll)` | Returns closest element to query |
| `(oc/fuzzy-map coll)` | Returns value for closest key to query |

---

## Performance

Benchmarks at N=500,000 elements (JVM 21, Clojure 1.12):

**Where ordered-set wins:**

The first/last speedup comes from O(log n) positional access via size annotations—`sorted-set` must traverse the entire seq. Set operations use Adams' divide-and-conquer algorithm with automatic parallelization for large inputs.

| Operation | sorted-set | data.avl | ordered-set | Speedup |
|-----------|------------|----------|-------------|---------|
| First/last access | 17s | 2.6ms | **2.4ms** | **~7000x** vs sorted-set |
| Union | 1.1s | 129ms | **20ms** | **6.5x** vs data.avl |
| Intersection | 870ms | 89ms | **25ms** | **3.5x** vs data.avl |
| Difference | 977ms | 81ms | **18ms** | **4.5x** vs data.avl |
| Parallel fold | 98ms | 95ms | **42ms** | **2.3x** |
| Construction | 1.5s | 1.3s | **1.2s** | **1.25x** |
| Reduce | 96ms | 85ms | **81ms** | **1.2x** |

**Trade-offs:**

| Operation | sorted-set | data.avl | ordered-set | Ratio |
|-----------|------------|----------|-------------|-------|
| Lookup (10K queries) | 12ms | 13ms | 15ms | 0.8x |
| Sequential insert | 1.6s | 2.1s | 2.5s | 0.64x |

**Why the lookup/insert overhead?** By default, `ordered-set` and `ordered-map` support heterogeneous keys—you can mix types freely, unlike Clojure's `sorted-set`. This flexibility requires `clojure.core/compare` dispatch on every comparison. For homogeneous collections, use the specialized constructors:

| Constructor | Comparator | vs sorted-set |
|-------------|------------|---------------|
| `long-ordered-set` | primitive `Long/compare` | **20% faster** lookup |
| `string-ordered-set` | direct `String.compareTo` | **5% faster** lookup |
| `double-ordered-set` | primitive `Double/compare` | ~equal |

---

## How It Works

The core is a weight-balanced binary tree using balance parameters (δ=3, γ=2) from Hirai and Yamamoto (2011). Each node stores its subtree size, enabling O(log n) positional access and efficient parallel decomposition.

**Split and join** are the fundamental primitives. Splitting a tree at a key produces two trees in O(log n); joining two trees where all keys in one are less than all keys in the other is also O(log n). Set operations, subrange extraction, and parallel fold all reduce to split/join.

**Parallelism** Set operations build on Adams' divide-and-conquer
algorithm (1992, "Efficient Sets—A Balancing Act") extended with the
parallel join-based approach from Blelloch, Ferizovic & Sun (2016, "Just
Join for Parallel Ordered Sets"). Complexity is O(m log(n/m + 1)) where
m ≤ n. When combined tree size exceeds a given size, operations
automatically parallelize via ForkJoinPool, yielding significant speedup on multi-core systems.

**Enumerators** provide efficient lazy traversal, inspired by Kiselyov's "Towards the best collection API." Rather than eagerly converting trees to sequences, an enumerator walks down the spine building a chain of frames—each saving (node, subtree, next-frame). This gives O(1) access to the current element, O(log n) amortized cost per advance, and only O(log n) space. Sequences, reduce, and fold all use enumerators internally.

**Augmented trees** extend the basic structure for specialized queries:
- *Interval trees* store the maximum endpoint in each subtree, enabling O(log n + k) overlap queries
- *Segment trees* store aggregate values (sum, min, max) for O(log n) range queries
- *Fuzzy collections* use floor/ceiling operations for O(log n) nearest-neighbor lookup

---

## Meet Zorp

Zorp runs the only sneaker store on the dark side of Pluto. Business is good—the perpetual darkness means nobody can see your shoes, which paradoxically makes everyone *obsessed* with having the freshest ones. "It's about knowing," Zorp explains to confused off-world visitors. "Knowing you're dripping."

The examples below show how Zorp uses each data structure to manage his interplanetary sneaker empire. For advanced patterns including fuzzy matching, temporal queries, and the new 0.2.0 API, see [Zorp's Complete Tale](doc/zorp-example.md).

---

## The Data Structures

### ordered-map / ordered-set

Drop-in replacements for `sorted-map` and `sorted-set` with better performance for bulk operations, parallel fold, and O(log n) positional access.

Zorp's inventory is chaos. Shipments arrive from Earth (8-month delay), Mars (3 weeks), and the Jovian moons (2 days, but they only make sandals). He needs to track thousands of SKUs, look them up fast, and always know what's in stock.

```clojure
;; Zorp's inventory: SKU -> {:name, :size, :quantity, :price}
(def inventory
  (oc/ordered-map
    {"PLT-001" {:name "Shadow Walker 9000" :size 10 :quantity 45 :price 299.99}
     "PLT-002" {:name "Dark Side Dunks"    :size 11 :quantity 12 :price 450.00}
     "PLT-003" {:name "Void Runner"        :size 9  :quantity 0  :price 175.50}
     "JUP-017" {:name "Europa Ice Grip"    :size 10 :quantity 88 :price 225.00}
     "MRS-042" {:name "Olympus Max"        :size 12 :quantity 33 :price 380.00}}))

;; Fast lookup
(inventory "PLT-002")
;; => {:name "Dark Side Dunks", :size 11, :quantity 12, :price 450.00}

;; The ordered-map keeps keys sorted, so Zorp can grab a range efficiently
;; All Plutonian models (SKUs starting with PLT):
(subseq inventory >= "PLT" < "PLU")
;; => (["PLT-001" {...}] ["PLT-002" {...}] ["PLT-003" {...}])

;; New shipment arrives! Immutable update, Zorp's accountant loves the audit trail
(def inventory' (update-in inventory ["PLT-003" :quantity] + 50))
```

"The sorted keys," Zorp muses, stroking his antenna, "they let me slice the catalog by manufacturer prefix. Very satisfying."

**Key features:**
- Full `clojure.lang.Sorted` support: native `subseq` and `rsubseq`
- O(log n) `first`/`last` via `java.util.SortedSet` interface (~7000x faster than `sorted-set` at scale)
- Parallel fold via `CollFold` (2.3x faster)
- Fast set operations: union, intersection, difference 7-9x faster than `clojure.set`

---

### interval-map / interval-set

An interval map associates values with intervals over a continuous domain. Query any point (or range) to find all overlapping intervals. O(log n + k) where k is the number of results.

```
 x8:                         +-----+
 x7:                   +-----------------------------------+
 x6:                                                       +
 x5:                                     +-----------+
 x4: +-----------------------------+
 x3:                                                 +-----+
 x2:                         +-----------------+
 x1:       +-----------+

     0=====1=====2=====3=====4=====5=====6=====7=====8=====9
```

Zorp's store is open during "business hours"—but on the dark side of Pluto, time is meaningless. So he defines shifts by arbitrary time units (PTU: Pluto Time Units). He needs to quickly answer: "Who's working at PTU 4500?"

```clojure
(def shift-schedule
  (oc/interval-map
    {[0 2000]     "Glorm (morning shift)"
     [2000 4000]  "Blixxa (afternoon shift)"
     [4000 6000]  "Zorp (evening shift)"
     [6000 8000]  "Night Bot 3000 (graveyard)"
     [1800 2200]  "Krix Jr. (overlap coverage)"}))

;; Customer calls at PTU 4500. Who picks up?
(shift-schedule 4500)
;; => ("Zorp (evening shift)")

;; During shift change at PTU 2000, who's available?
(shift-schedule 2000)
;; => ("Glorm (morning shift)" "Blixxa (afternoon shift)" "Krix Jr. (overlap coverage)")

;; Query a range: who works any time between PTU 1900-2100?
(shift-schedule [1900 2100])
;; => ("Glorm (morning shift)" "Blixxa (afternoon shift)" "Krix Jr. (overlap coverage)")
```

"The interval map," Zorp explains to his new hire, "handles the overlaps automatically. Krix Jr. wanted 'creative scheduling.' Now I can just query any moment and know who's supposed to be here." (Krix Jr. is the son of Krix the methane baron—nepotism is alive and well on Pluto.)

---

### range-map

A persistent version of [Google Guava's TreeRangeMap](https://guava.dev/releases/snapshot/api/docs/com/google/common/collect/TreeRangeMap.html). Maintains non-overlapping ranges—when you insert a new range, it automatically carves out space by splitting or removing existing ranges that overlap. Each point maps to exactly one value (or none).

**Guava-compatible semantics:**
- `assoc` (Guava's `put`): inserts range, carving out overlaps. Does NOT coalesce adjacent same-value ranges.
- `assoc-coalescing` (Guava's `putCoalescing`): inserts and merges adjacent ranges with the same value.

```
 Before inserting [50, 150] :flash-sale:

 :bronze ████████████████████████████████████████
 :silver                                         ████████████████████████████████████████████████████████████
         0        50       100      150      200      250      300      350      400      450      500

 After inserting [50, 150] :flash-sale:

 :bronze ████████████████████
 :flash  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
 :silver                                         ████████████████████████████████████████████████████████████
         0        50       100      150      200      250      300      350      400      450      500
```

Zorp's discount system is based on purchase amount. Different ranges get different discounts, and ranges can't overlap—each credit amount maps to exactly one discount tier.

```clojure
(def discount-tiers
  (-> (oc/range-map)
      (assoc [0 100]      :no-discount)
      (assoc [100 500]    :bronze-5-percent)
      (assoc [500 1000]   :silver-10-percent)
      (assoc [1000 5000]  :gold-15-percent)
      (assoc [5000 50000] :platinum-20-percent)))

;; Customer's cart is 750 credits
(discount-tiers 750)
;; => :silver-10-percent

;; Edge case: exactly 1000 credits (ranges are [lo, hi) half-open)
(discount-tiers 1000)
;; => :gold-15-percent

;; Zorp runs a flash sale: 8% off for purchases 200-400 credits
;; This automatically splits the bronze tier!
(def flash-sale-tiers
  (assoc discount-tiers [200 400] :flash-sale-8-percent))

(oc/ranges flash-sale-tiers)
;; => ([[0 100] :no-discount]
;;     [[100 200] :bronze-5-percent]      ; auto-trimmed!
;;     [[200 400] :flash-sale-8-percent]  ; inserted
;;     [[400 500] :bronze-5-percent]      ; auto-trimmed!
;;     [[500 1000] :silver-10-percent]
;;     ...)

;; Coalescing: merge adjacent ranges with the same value
(-> (oc/range-map {[0 100] :a})
    (oc/assoc-coalescing [100 200] :a)  ; merges!
    oc/ranges)
;; => ([[0 200] :a])

;; Get entry: find which range contains a point
(oc/get-entry discount-tiers 750)
;; => [[500 1000] :silver-10-percent]

;; Remove a range (trims overlapping ranges)
(oc/range-remove discount-tiers [300 600])
;; => bronze trimmed to [100 300), silver trimmed to [600 1000)
```

"Before the range-map," Zorp recalls darkly, "I had seventeen overlapping discount codes." See the [full subnet allocation example](doc/zorp-example.md#chapter-1-the-subnet-allocation) for IP address management with coalescing.

**Use case scenarios:**
- IP address block allocation (private network ranges, subnet assignment)
- Time slot scheduling (non-overlapping calendar bookings)
- Memory region management (allocation tracking, fragmentation analysis)
- Version range resolution (semantic versioning with deprecation markers)

---

### segment-tree

A segment tree answers range aggregate queries: "what is f(a, a+1, ..., b) for some associative function f?" in O(log n) time, with O(log n) updates.

```
 Input:   index:  1    2    3    4    5    6    7    8
          value: 100  150  200  175  225  300  125  275

 Tree (sum):
                              [1550]           ← sum of all 8 values
                       ┌────────┴────────┐
                     [625]              [925]
                    ┌──┴──┐            ┌──┴──┐
                 [250]  [375]       [525]  [400]
                 ┌─┴─┐  ┌─┴─┐       ┌─┴─┐  ┌─┴─┐
                100 150 200 175    225 300 125 275

 Query [2-5] sum = 150 + [375] + 225 = 750   ← 3 nodes, not 4 leaves
```

Zorp wants to analyze daily sales. Specifically, he needs to answer range queries like "What were total sales from day 50 to day 75?" and update individual days as sales come in—all in logarithmic time.

```clojure
;; Daily sales for the first quarter (90 days)
(def daily-sales
  (oc/segment-tree + 0  ; operation: +, identity: 0
    (into {} (for [day (range 1 91)]
               [day (+ 1000 (rand-int 500))]))))  ; 1000-1500 credits/day

;; Total sales for days 1-30 (first month)
(oc/query daily-sales 1 30)
;; => ~37500

;; Big sale day! Update day 45 with actual figure
(def daily-sales' (assoc daily-sales 45 8500))

;; Query again - the tree updates in O(log n)
(oc/query daily-sales' 40 50)
;; => includes the 8500 spike

;; Zorp also tracks minimum daily sales to identify slow days
(def min-daily-sales
  (oc/segment-tree min Long/MAX_VALUE
    (into {} (for [day (range 1 91)]
               [day (+ 1000 (rand-int 500))]))))

;; Worst day in the second month?
(oc/query min-daily-sales 31 60)
;; => ~1000-1050
```

"The segment tree," Zorp tells his accountant (a sentient calculator from Neptune), "gives me range sums instantly. Quarterly reports used to take hours. Now? Logarithmic time."

---

### ranked-set

A sorted set with O(log n) positional access: `nth`, `rank`, `median`, and percentile queries.

Zorp's loyalty program tracks customer spending. He needs to answer questions like "Who are my top 10 spenders?" and "What percentile is this customer in?" without re-sorting everything constantly.

```clojure
;; Store [total-spent customer-id] pairs so they sort by spending
(def customer-spending
  (oc/ranked-set
    [[15420.00 "CUST-0042"]   ; Krix, the methane baron
     [8730.50  "CUST-0117"]   ; Anonymous (pays in nitrogen credits)
     [45200.00 "CUST-0001"]   ; The Mayor's office
     [3200.00  "CUST-0233"]   ; First-time buyer
     [12800.00 "CUST-0089"]   ; Repeat customer
     [52100.00 "CUST-0007"]   ; "Big Toe" Tony
     [9999.99  "CUST-0404"]])) ; Suspicious round number

;; Who's the biggest spender?
(last customer-spending)
;; => [52100.0 "CUST-0007"]  -- Big Toe Tony, of course

;; Top 3 spenders
(take-last 3 customer-spending)
;; => ([15420.0 "CUST-0042"] [45200.0 "CUST-0001"] [52100.0 "CUST-0007"])

;; What's the median spending level?
(oc/median customer-spending)
;; => [12800.0 "CUST-0089"]

;; A customer wants to know: "Am I in the top 25%?"
(let [spending [8730.50 "CUST-0117"]
      rank (oc/rank customer-spending spending)
      percentile (* 100.0 (/ rank (count customer-spending)))]
  (println "You're at the" (int percentile) "percentile!")
  (> percentile 75))
;; You're at the 14 percentile!
;; => false
```

"Big Toe Tony," Zorp sighs. "He bought every color of the Void Runner. Every. Color. The man has 47 feet."

---

### priority-queue

A persistent priority queue with O(log n) push/peek/pop. Elements are `[priority value]` pairs, ordered by priority (min-heap by default).

Shoes break. It happens. Zorp offers repair services, but some repairs are more urgent than others. A customer's only pair? Rush job. Seventh pair of limited editions? They can wait.

```clojure
(def repair-queue
  (oc/priority-queue
    [[1 {:customer "CUST-0042" :issue "Sole detachment, only pair"}]
     [5 {:customer "CUST-0007" :issue "Scuff marks, has 46 other pairs"}]
     [2 {:customer "CUST-0117" :issue "Lace replacement, formal event tomorrow"}]
     [3 {:customer "CUST-0233" :issue "Squeaky heel"}]
     [1 {:customer "CUST-0089" :issue "Zipper stuck, only winter boots"}]]))

;; Who's first? (lowest priority number = most urgent)
(peek repair-queue)
;; => [1 {:customer "CUST-0042" :issue "Sole detachment, only pair"}]

;; Process both priority-1 jobs, then see who's next
(-> repair-queue pop pop peek)
;; => [2 {:customer "CUST-0117" :issue "Lace replacement, formal event tomorrow"}]

;; Add a new urgent repair
(def repair-queue' (conj repair-queue [0 {:customer "VIP" :issue "Emergency!"}]))
(peek repair-queue')
;; => [0 {:customer "VIP" :issue "Emergency!"}]
```

"Big Toe Tony's scuff marks," Zorp mutters, "can wait until the heat death of the universe."

---

### ordered-set Operations

Fast set algebra with parallel divide-and-conquer for large sets.

Zorp's hottest releases require a reservation system. Customers select time slots to pick up their shoes. Each slot can only be used once.

```clojure
(def all-slots
  (oc/ordered-set (range 100 200)))  ; slots 100-199 available today

(def reserved-slots
  (oc/ordered-set [105 110 115 120 125 142 143 144 150 175 188]))

;; Available slots = all-slots - reserved-slots
(def available (oc/difference all-slots reserved-slots))

(count available)
;; => 89 slots still open

;; Customer wants the earliest available slot at or after 140
(first (subseq available >= 140))
;; => 140 (it's available!)

;; VIP customer wants to know: are ANY slots between 170-180 open?
(seq (subseq available >= 170 < 180))
;; => (170 171 172 173 174 176 177 178 179)  -- plenty! (175 was reserved)

;; Set operations are 7-9x faster than clojure.set for large sets
(def s1 (oc/ordered-set (range 0 500000)))
(def s2 (oc/ordered-set (range 250000 750000)))
(oc/union s1 s2)        ;; 129ms (clojure.set: 1.1s)
(oc/intersection s1 s2) ;; 91ms (clojure.set: 870ms)
(oc/difference s1 s2)   ;; 102ms (clojure.set: 977ms)
```

---

### Split and Range Operations

New in 0.2.0: O(log n) operations for partitioning and range extraction, compatible with `clojure.data.avl`.

```clojure
(def prices (oc/ordered-set [100 200 300 400 500 600 700 800 900]))

;; split-key: partition at a key value
;; Returns [elements-below, exact-match-or-nil, elements-above]
(oc/split-key prices 500)
;; => [#{100 200 300 400} 500 #{600 700 800 900}]

;; split-at: partition at an index
;; Returns [left, right]
(oc/split-at prices 4)
;; => [#{100 200 300 400} #{500 600 700 800 900}]

;; subrange: extract elements by key bounds (returns a collection, not a seq)
(oc/subrange prices >= 300 < 700)
;; => #{300 400 500 600}

;; nearest: floor/ceiling queries
(oc/nearest prices <= 450)  ;; => 400 (greatest element ≤ 450)
(oc/nearest prices >= 450)  ;; => 500 (least element ≥ 450)
(oc/nearest prices < 300)   ;; => 200 (greatest element < 300)
(oc/nearest prices > 700)   ;; => 800 (least element > 700)
```

These operations work on both sets and maps:

```clojure
(def inventory (oc/ordered-map [[10 :a] [20 :b] [30 :c] [40 :d]]))

(oc/split-key inventory 25)
;; => [{10 :a, 20 :b} nil {30 :c, 40 :d}]

(oc/nearest inventory <= 25)
;; => [20 :b]

(oc/subrange inventory >= 15 <= 35)
;; => {20 :b, 30 :c}
```

---

### Also Available

| Constructor | What it does |
|-------------|--------------|
| `ordered-multiset` | Sorted bag allowing duplicates |
| `fuzzy-set`, `fuzzy-map` | Nearest-neighbor lookup (distance must correlate with sort order) |
| `long-ordered-set`, `long-ordered-map` | Optimized for Long keys (20% faster lookup) |
| `string-ordered-set`, `string-ordered-map` | Optimized for String keys |

---

## Architecture

This library is designed around modularity and extensibility. The collections are built on standard Clojure/Java interfaces, so working with an `ordered-set` is identical to working with `sorted-set`—all the familiar functions work: `meta`, `nth`, `seq`, `rseq`, `assoc`, `get`, `first`, `last`, `count`, `contains?`, `conj`, `disj`, `reduce`, and more.

### Interfaces

Each collection type is a `deftype` container holding the root of a weight-balanced tree. The container implements several protocol layers:

| Interface | Purpose |
|-----------|---------|
| `INodeCollection` | Access to node allocation and root node. Enables variants like persistent (on-disk) storage. |
| `IBalancedCollection` | The `stitch` function for creating balanced trees. Default is weight-balanced; red-black is also supported. |
| `IOrderedCollection` | Comparator and compatibility predicates. Interval collections are a specialized variant. |

### Set Operations

Since `clojure.set` doesn't provide interfaces for extensible set operations, this library provides its own `union`, `intersection`, `difference`, `subset?`, and `superset?`. These work most efficiently on ordered-collections but fall back gracefully to `clojure.set` behavior for other set types.

```clojure
(require '[clojure.core.reducers :as r])

;; Parallel fold: 2.3x faster than sorted-set
(r/fold + (oc/ordered-set (range 500000)))

;; First/last via Java SortedSet interface: O(log n)
(.first ^java.util.SortedSet (oc/ordered-set (range 500000)))
(.last ^java.util.SortedSet (oc/ordered-set (range 500000)))

;; Range queries via clojure.lang.Sorted
(subseq (oc/ordered-set (range 100)) >= 25 < 75)
(rsubseq (oc/ordered-set (range 100)) > 50)

;; Parallel set operations: 7-9x faster than clojure.set
(let [s1 (oc/ordered-set (range 0 500000))
      s2 (oc/ordered-set (range 250000 750000))]
  (oc/union s1 s2)
  (oc/intersection s1 s2)
  (oc/difference s1 s2))

;; Map merge with conflict resolution
(let [m1 (oc/ordered-map (map #(vector % %) (range 15000)))
      m2 (oc/ordered-map (map #(vector % (* 2 %)) (range 10000 25000)))]
  (oc/merge-with + m1 m2))
```

### Tree Implementation

The heart of the library is [tree.clj](src/com/dean/ordered_collections/tree/tree.clj). It supports sets, maps, and indexed access with:

- **Key/range queries**: Standard sorted collection operations
- **Positional access**: `nth` returns the nth element in O(log n)
- **Rank queries**: `rank` returns the position of a key in O(log n)
- **Split/join**: O(log n) partitioning and merging, the basis for set operations
- **Parallel decomposition**: Trees split efficiently for `r/fold`

The tree is parameterized by comparator, node constructor, and join strategy—these correspond to the interfaces above and enable the variety of collection types. See [Algorithms](doc/algorithms.md) for implementation details.

---

## Testing & Benchmarks

```
$ lein test

Ran 286 tests containing 454,000+ assertions.
0 failures, 0 errors.
```

The test suite includes generative tests via `test.check` and equivalence tests against `sorted-set`, `sorted-map`, and `clojure.data.avl`.

Benchmarks use [Criterium](https://github.com/hugoduncan/criterium) for statistically rigorous measurements. See [Benchmarks](doc/benchmarks.md) for methodology and detailed results.

---

## Inspiration

The implementation of this weight-balanced binary tree data
structure library was inspired by the following:

-  Adams (1992)
   'Implementing Sets Efficiently in a Functional Language'
   Technical Report CSTR 92-10, University of Southampton.
   <http://groups.csail.mit.edu/mac/users/adams/BB/92-10.ps>

-  Hirai and Yamamoto (2011)
   'Balancing Weight-Balanced Trees'
   Journal of Functional Programming / 21 (3):
   Pages 287-307
   <https://yoichihirai.com/bst.pdf>

-  Oleg Kiselyov
   'Towards the best collection API, A design of the overall optimal
   collection traversal interface'
   <https://okmij.org/ftp/Scheme/enumerators-callcc.html>

-  Nievergelt and Reingold (1972)
   'Binary Search Trees of Bounded Balance'
   STOC '72 Proceedings
   4th Annual ACM symposium on Theory of Computing
   Pages 137-142

-  Driscoll, Sarnak, Sleator, and Tarjan (1989)
   'Making Data Structures Persistent'
   Journal of Computer and System Sciences Volume 38 Issue 1, February 1989
   18th Annual ACM Symposium on Theory of Computing
   Pages 86-124

-  MIT Scheme weight balanced tree as reimplemented by Yoichi Hirai
   and Kazuhiko Yamamoto using the revised non-variant algorithm recommended
   integer balance parameters from (Hirai/Yamamoto 2011).

-  Wikipedia
   'Interval Tree'
   <https://en.wikipedia.org/wiki/Interval_tree>

-  Wikipedia
   'Segment Tree'
   <https://en.wikipedia.org/wiki/Segment_tree>

-  Google Guava
   'RangeMap'
   <https://guava.dev/releases/snapshot/api/docs/com/google/common/collect/RangeMap.html>

-  Wikipedia
   'Weight Balanced Tree'
   <https://en.wikipedia.org/wiki/Weight-balanced_tree>

-  Andrew Baine (2007)
   'Purely Functional Data Structures in Common Lisp'
   Google Summer of Code 2007, mentored by Rahul Jain
   <https://funds.common-lisp.dev/funds.pdf>
   <https://developers.google.com/open-source/gsoc/2007/>

- Scott L. Burson
   'Functional Set-Theoretic Collections for Common Lisp'
   <https://fset.common-lisp.dev/>

-  Adams (1993)
   'Efficient sets—a balancing act'
   Journal of Functional Programming 3(4): 553-562
   <https://www.cambridge.org/core/journals/journal-of-functional-programming/article/functional-pearls-efficient-setsa-balancing-act/0CAA1C189B4F7C15CE9B8C02D0D4B54E>

-  Blelloch, Ferizovic, and Sun (2016)
   'Just Join for Parallel Ordered Sets'
   ACM SPAA 2016
   <https://dl.acm.org/doi/10.1145/2935764.2935768>

-  Haskell containers library (Data.Set, Data.Map)
   <https://hackage.haskell.org/package/containers>

-  SLIB Weight-Balanced Trees (Aubrey Jaffer)
   <https://people.csail.mit.edu/jaffer/slib/Weight_002dBalanced-Trees.html>

-  PAM: Parallel Augmented Maps
   <https://cmuparlay.github.io/PAMWeb/>

---

## License

The use and distribution terms for this software are covered by the [Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php), which can be found in the file LICENSE.txt at the root of this distribution. By using this software in any fashion, you are agreeing to be bound by the terms of this license. You must not remove this notice, or any other, from this software.

---

*Zorp's Sneaker Emporium is a registered trademark of Zorp Enterprises, LLC (Pluto Division). No actual Plutonians were harmed in the making of this documentation. Big Toe Tony's foot count verified by the Pluto Bureau of Standards; foot #23 (Reginald) declined comment. Kevin remains under investigation by the Jovian Commerce Commission for sentience without a license
