# When to Use Which Collection

## Decision Matrix

| You need... | Use |
|-------------|-----|
| Drop-in `sorted-set`/`sorted-map` replacement | `ordered-set` / `ordered-map` |
| Fast set algebra (union, intersection, difference) | `ordered-set` / `ordered-map` |
| O(log n) nth, rank, median, percentile | `ordered-set` / `ordered-map` |
| Fast endpoint access | `ordered-set` / `ordered-map` |
| Parallel `r/fold` | `ordered-set` / `ordered-map` |
| Overlapping interval queries ("what's scheduled at 3pm?") | `interval-set` / `interval-map` |
| Non-overlapping range allocation ("which subnet owns this IP?") | `range-map` |
| Range aggregate queries ("total sales from day 10 to 50?") | `segment-tree` |
| Snap to closest value by distance | `fuzzy-set` / `fuzzy-map` |
| Floor/ceiling (directional nearest) | `ordered-set` / `ordered-map` via `nearest` |
| Min-heap / max-heap with persistent undo | `priority-queue` |
| Sorted bag with duplicates | `ordered-multiset` |
| Zero dependencies, basic sorted access only | `sorted-set` / `sorted-map` |
| ClojureScript | `clojure.data.avl` |
| Transient batch mutation | `clojure.data.avl` |

## Choosing Between Similar Collections

### interval-map vs range-map

Both map ranges to values. The difference is whether ranges can overlap.

| | interval-map | range-map |
|--|--|--|
| Overlapping ranges | Yes | No — inserting carves out overlaps |
| Point query returns | All overlapping values | Exactly one value |
| Use case | Schedules, event logs, genomic annotations | IP subnets, memory regions, discount tiers |

```clojure
;; interval-map: "what meetings overlap 2pm?"
(def schedule (oc/interval-map {[9 12] "standup" [11 15] "workshop"}))
(schedule 11)  ;=> ("standup" "workshop")   ← both

;; range-map: "which tier is 750 credits?"
(def tiers (oc/range-map {[0 500] :bronze [500 5000] :gold}))
(tiers 750)    ;=> :gold                    ← exactly one
```

### ordered-set vs fuzzy-set

Both support nearest-neighbor, but with different semantics.

| | ordered-set + `nearest` | fuzzy-set |
|--|--|--|
| Exact match | Returns element | Returns element |
| No exact match | Must specify direction (`:<=`, `:>=`, `:<`, `:>`) | Automatically returns closest by distance |
| Equidistant | N/A (direction chosen) | Configurable tiebreak (`:< ` or `:>`) |
| Use case | Floor/ceiling lookups | Snapping to nearest bucket |

```clojure
;; ordered-set: explicit direction
(oc/nearest sizes :<= 11.3)  ;=> 11.0  (floor)
(oc/nearest sizes :>= 11.3)  ;=> 11.5  (ceiling)

;; fuzzy-set: automatic closest
(def sizes (oc/fuzzy-set [6 7 8 9 10 11 12 13]))
(sizes 11.3)                 ;=> 11    (closest by distance)
```

### segment-tree vs reduce over subrange

Both answer "aggregate over a range." The tradeoff is query speed vs update speed.

| | segment-tree | subrange + reduce |
|--|--|--|
| Query | O(log n) | O(k) where k = range size |
| Update | O(log n) | O(1) (assoc) |
| Use case | Many queries, moderate updates | Few queries, many updates |

### priority-queue vs (first (ordered-set ...))

Both give you the front element in sorted order. `priority-queue` allows duplicate priorities, stores `[priority value]` pairs, and preserves insertion order among equal priorities in forward queue order. `ordered-set` is a full sorted set.

## API Compatibility

All collections support standard Clojure interfaces: `get`, `assoc`, `dissoc`, `seq`, `rseq`, `count`, `reduce`, `into`, `=`, `hash`, `meta`.

`ordered-set` and `ordered-map` additionally implement `java.util.SortedSet`/`java.util.Map`, `clojure.lang.Sorted` (`subseq`, `rsubseq`), and `clojure.core.reducers/CollFold`.

For migration guides, see [vs clojure.data.avl](vs-clojure-data-avl.md). For performance details, see [Performance Analysis](perf-analysis.md). For the full library comparison, see [Competitive Analysis](competitive-analysis.md).
