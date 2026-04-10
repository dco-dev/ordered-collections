# Collections API Reference

This document is a practical reference for the library's collection types.
Use it to answer three questions quickly:

- Which constructor should I use?
- Which collection-specific operations does this type support?
- Which standard Clojure operations work as expected on it?

All library-specific constructors and extension functions are in
`ordered-collections.core`.

```clojure
(require '[ordered-collections.core :as oc])
```

Standard Clojure operations such as `assoc`, `get`, `contains?`, `seq`,
`reduce`, `first`, `last`, `nth`, and `rseq` work directly on the collection
instances themselves where supported.

## Conventions

- `O(log n)` means logarithmic in collection size.
- `O(log n + k)` means logarithmic search plus `k` returned elements.
- `r/fold` refers to `clojure.core.reducers/fold`.
- For ordered maps, seq/reduce/nth results are entries (`[k v]`).
- For ordered sets, seq/reduce/nth results are elements.
- `split-key` returns `[left hit right]`.
  For sets, `hit` is the element itself.
  For maps, `hit` is `[k v]`.

---

## Comparators

These are public helpers for constructing ordered collections with specialized
or custom ordering.

| Function | Signature | Notes |
|---|---|---|
| `long-compare` | — | Specialized `Comparator` for `Long` keys. |
| `double-compare` | — | Specialized `Comparator` for `Double` keys. |
| `string-compare` | — | Specialized `Comparator` for `String` keys. |
| `general-compare` | — | Total order over all values, including non-`Comparable` types (Namespace, Var, etc.). Use with `ordered-set-with` / `ordered-map-with`. ~20% slower lookups on `Comparable` types vs default. |
| `compare-by` | `[pred]` | Convert a total-order predicate such as `<` or `>` into a `Comparator`. |

---

## Ordered Set

Persistent sorted set with positional access, nearest/range operations,
parallel set algebra, and parallel fold.

### Constructors

- `ordered-set`
  - `(ordered-set)`
  - `(ordered-set coll)`
- `ordered-set-by`
  - `(ordered-set-by pred coll)`
- `ordered-set-with`
  - `(ordered-set-with comparator)`
  - `(ordered-set-with comparator coll)`
- `long-ordered-set`
  - `(long-ordered-set)`
  - `(long-ordered-set coll)`
- `double-ordered-set`
  - `(double-ordered-set)`
  - `(double-ordered-set coll)`
- `string-ordered-set`
  - `(string-ordered-set)`
  - `(string-ordered-set coll)`

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `union` | `[s1 s2]` | Divide-and-conquer set union; parallel for large compatible sets. |
| `intersection` | `[s1 s2]` | Divide-and-conquer set intersection; parallel for large compatible sets. |
| `difference` | `[s1 s2]` | Divide-and-conquer set difference; parallel for large compatible sets. |
| `subset?` | `[s1 s2]` | Subset test. |
| `superset?` | `[s1 s2]` | Superset test. |
| `disjoint?` | `[s1 s2]` | Disjointness test. |
| `rank` | `[coll x]` | 0-based rank of `x`, or `nil` if absent. |
| `slice` | `[coll start end]` | Elements in index range `[start,end)`. |
| `median` | `[coll]` | Lower median for even-sized collections. |
| `percentile` | `[coll pct]` | Element at percentile `0..100`. |
| `nearest` | `[coll test k]` | Nearest element satisfying `:<`, `:<=`, `:>=`, `:>`. |
| `subrange` | `[coll test k]` `[coll t1 k1 t2 k2]` | Structure-sharing subcollection. |
| `split-key` | `[k coll]` | Returns `[left hit right]`. |
| `split-at` | `[i coll]` | Returns `[left right]`. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `conj` | `[coll x]` | Add element. |
| `disj` | `[coll x]` | Remove element. |
| `contains?` | `[coll x]` | Exact membership test. |
| `nth` | `[coll i]` `[coll i not-found]` | Positional access. |
| `first` / `last` | `[coll]` | Min/max element. |
| `seq` / `rseq` | `[coll]` | Sorted / reverse-sorted traversal. |
| `reduce` | `[f coll]` `[f init coll]` | Direct tree traversal. |
| `r/fold` | `[n combinef reducef coll]` | Parallel chunked fold. |
| `count` | `[coll]` | O(1). |

---

## Ordered Map

Persistent sorted map with positional access, nearest/range operations,
parallel merge, and parallel fold.

### Constructors

- `ordered-map`
  - `(ordered-map)`
  - `(ordered-map coll)`
- `ordered-map-by`
  - `(ordered-map-by pred coll)`
- `ordered-map-with`
  - `(ordered-map-with comparator)`
  - `(ordered-map-with comparator coll)`
- `long-ordered-map`
  - `(long-ordered-map)`
  - `(long-ordered-map coll)`
- `double-ordered-map`
  - `(double-ordered-map)`
  - `(double-ordered-map coll)`
- `string-ordered-map`
  - `(string-ordered-map)`
  - `(string-ordered-map coll)`

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `assoc-new` | `[m k v]` | Associate only if `k` is absent. |
| `ordered-merge-with` | `[f & maps]` | Merge ordered maps; parallel for large compatible maps. |
| `rank` | `[coll k]` | 0-based rank of key `k`, or `nil`. |
| `slice` | `[coll start end]` | Entries in index range `[start,end)`. |
| `median` | `[coll]` | Median entry. |
| `percentile` | `[coll pct]` | Entry at percentile `0..100`. |
| `nearest` | `[coll test k]` | Nearest entry satisfying `:<`, `:<=`, `:>=`, `:>`. |
| `subrange` | `[coll test k]` `[coll t1 k1 t2 k2]` | Structure-sharing submap. |
| `split-key` | `[k coll]` | Returns `[left-map hit right-map]`. |
| `split-at` | `[i coll]` | Returns `[left right]`. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `assoc` | `[m k v]` | Add/update entry. |
| `dissoc` | `[m k]` | Remove key. |
| `get` | `[m k]` `[m k not-found]` | Exact key lookup. |
| `contains?` | `[m k]` | Exact key membership. |
| `nth` | `[m i]` `[m i not-found]` | Entry at index. |
| `first` / `last` | `[m]` | Min/max key entry. |
| `seq` / `rseq` | `[m]` | Sorted / reverse-sorted entries. |
| `reduce` | `[f m]` `[f init m]` | Reduction over entries. |
| `reduce-kv` | `[f init m]` | `(f acc k v)`. |
| `r/fold` | `[n combinef reducef coll]` | Parallel chunked fold. |
| `count` | `[m]` | O(1). |

---

## Interval Set

Set of intervals with overlap queries via interval-tree augmentation.

### Constructors

- `interval-set`
  - `(interval-set)`
  - `(interval-set coll)`

Bare scalar values become point intervals `[x x]`.

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `overlapping` | `[iset point]` `[iset interval]` | All stored intervals overlapping the query. |
| `span` | `[iset]` | Bounding interval `[min max]`, or `nil`. |
| `union` | `[s1 s2]` | Interval-set union. |
| `intersection` | `[s1 s2]` | Interval-set intersection. |
| `difference` | `[s1 s2]` | Interval-set difference. |
| `subset?` | `[s1 s2]` | Subset test. |
| `superset?` | `[s1 s2]` | Superset test. |
| `disjoint?` | `[s1 s2]` | Disjointness test. |

### Standard collection operations

Important: interval collections intentionally use overlap semantics for lookup-style operations.

| Operation | Signature(s) | Notes |
|---|---|---|
| `conj` | `[coll interval]` | Add interval. |
| `disj` | `[coll interval]` | Remove exact stored interval. |
| `contains?` | `[coll query]` | True if any stored interval overlaps the point/interval query. |
| `get` / IFn | `[coll query]` `[coll query not-found]` | Returns overlapping intervals, not an exact membership value. |
| `nth` | `[coll i]` `[coll i not-found]` | Interval at index. |
| `first` / `last` | `[coll]` | First/last interval by interval ordering. |
| `seq` / `rseq` | `[coll]` | Sorted / reverse-sorted intervals. |
| `reduce` | `[f coll]` `[f init coll]` | Reduction over intervals. |
| `r/fold` | `[n combinef reducef coll]` | Parallel chunked fold. |
| `count` | `[coll]` | O(1). |

Also callable as a function:

- `(iset point)`
- `(iset [lo hi])`

Both return overlapping intervals.

---

## Interval Map

Map from intervals to values with overlap queries via interval-tree augmentation.

### Constructors

- `interval-map`
  - `(interval-map)`
  - `(interval-map coll)`

Input shape is `[[interval value] ...]`.

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `overlapping` | `[imap point]` `[imap interval]` | All stored `[interval value]` entries overlapping the query. |
| `span` | `[imap]` | Bounding interval `[min max]`, or `nil`. |

### Standard collection operations

Important: interval-map intentionally overloads map-style lookup with overlap-query semantics.

| Operation | Signature(s) | Notes |
|---|---|---|
| `assoc` | `[m interval v]` | Add/update interval-value entry. |
| `dissoc` | `[m interval]` | Remove exact stored interval. |
| `get` / IFn | `[m query]` `[m query not-found]` | Returns the seq of overlapping values, not a single exact-key value. |
| `contains?` | `[m query]` | True if any stored interval overlaps the query. |
| `find` / `entryAt` | `[m query]` | Returns the first overlapping stored entry. |
| `nth` | `[m i]` `[m i not-found]` | Entry at index. |
| `first` / `last` | `[m]` | First/last stored entry by interval ordering. |
| `seq` / `rseq` | `[m]` | Sorted / reverse-sorted entries. |
| `reduce` | `[f m]` `[f init m]` | Reduction over entries. |
| `reduce-kv` | `[f init m]` | `(f acc interval value)`. |
| `r/fold` | `[n combinef reducef coll]` | Parallel chunked fold. |
| `count` | `[m]` | O(1). |

Also callable as a function:

- `(imap point)`
- `(imap [lo hi])`

Both return overlapping values.

---

## Range Map

Persistent map from non-overlapping half-open ranges `[lo, hi)` to values.
Inserting a range carves out or replaces overlapping portions of existing ranges.

### Constructors

- `range-map`
  - `(range-map)`
  - `(range-map coll)`

Input may be a map or seq of `[[range value] ...]` pairs.

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `ranges` | `[rm]` | Seq of `[[lo hi] value]` entries. |
| `gaps` | `[rm]` | Unmapped half-open gaps. |
| `get-entry` | `[rm point]` | `[[lo hi] value]` containing `point`, or `nil`. |
| `assoc-coalescing` | `[rm [lo hi] v]` | Insert and merge adjacent equal-valued ranges. |
| `range-remove` | `[rm [lo hi]]` | Remove all mappings intersecting the range. |
| `span` | `[rm]` | Bounding range `[min max]`, or `nil`. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `assoc` | `[rm [lo hi] v]` | Insert without coalescing. |
| `get` / IFn | `[rm point]` `[rm point not-found]` | Value containing `point`. |
| `contains?` | `[rm point]` | Point membership. |
| `seq` | `[rm]` | Sorted `[[lo hi] value]` entries. |
| `reduce` | `[f rm]` `[f init rm]` | Reduction over entries. |
| `reduce-kv` | `[f init rm]` | `(f acc range value)`. |
| `count` | `[rm]` | O(1). |

Also callable as a function:

- `(rm point)`

returns the value for the containing range, or `nil`.

---

## Segment Tree

Persistent ordered map with cached subtree aggregates for fast range queries.

### Constructors

- `segment-tree`
  - `(segment-tree op identity coll)`
- `segment-tree-with`
  - `(segment-tree-with comparator op identity)`
  - `(segment-tree-with comparator op identity coll)`
- `segment-tree-by`
  - `(segment-tree-by pred op identity coll)`
- `sum-tree`
  - `(sum-tree coll)`
- `min-tree`
  - `(min-tree coll)`
- `max-tree`
  - `(max-tree coll)`

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `query` | `[st lo hi]` | Aggregate over inclusive key range `[lo,hi]`. |
| `aggregate` | `[st]` | Whole-tree aggregate. |
| `update-val` | `[st k v]` | Replace value at key. |
| `update-fn` | `[st k f]` | Update value at key via `f`. |
| `rank` | `[st k]` | Rank of key `k`, or `nil`. |
| `slice` | `[st start end]` | Entries in index range `[start,end)`. |
| `median` | `[st]` | Median entry. |
| `percentile` | `[st pct]` | Entry at percentile `0..100`. |
| `nearest` | `[st test k]` | Nearest entry satisfying `:<`, `:<=`, `:>=`, `:>`. |
| `subrange` | `[st test k]` `[st t1 k1 t2 k2]` | Structure-sharing submap. |
| `split-key` | `[k st]` | Returns `[left hit right]`. |
| `split-at` | `[i st]` | Returns `[left right]`. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `assoc` | `[st k v]` | Add/update key and recompute aggregates. |
| `get` / IFn | `[st k]` `[st k not-found]` | Exact key lookup. |
| `contains?` | `[st k]` | Exact key membership. |
| `seq` / `rseq` | `[st]` | Sorted / reverse-sorted entries. |
| `reduce` | `[f st]` `[f init st]` | Reduction over entries. |
| `reduce-kv` | `[f init st]` | `(f acc k v)`. |
| `count` | `[st]` | O(1). |

Also callable as a function:

- `(st k)`

returns the value at key `k`.

---

## Priority Queue

Persistent priority queue ordered by priority and stable insertion order within
equal priorities.

Queue order is defined by the configured comparator:

- `peek-min` / `pop-min` operate on the first element in queue order
- `peek-max` / `pop-max` operate on the last element in queue order

### Constructors

- `priority-queue`
  - `(priority-queue)`
  - `(priority-queue pairs)`
- `priority-queue-by`
  - `(priority-queue-by pred pairs)`
- `priority-queue-with`
  - `(priority-queue-with comparator)`
  - `(priority-queue-with comparator pairs)`

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `push` | `[pq priority value]` | Insert one element. |
| `push-all` | `[pq pairs]` | Insert many `[priority value]` pairs. |
| `peek-min` | `[pq]` | First `[priority value]` in queue order, or `nil`. |
| `peek-min-val` | `[pq]` | First value only. |
| `pop-min` | `[pq]` | Remove first element. |
| `peek-max` | `[pq]` | Last `[priority value]` in queue order, or `nil`. |
| `peek-max-val` | `[pq]` | Last value only. |
| `pop-max` | `[pq]` | Remove last element. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `peek` | `[pq]` | Same as `peek-min`. |
| `pop` | `[pq]` | Same as `pop-min`. |
| `seq` / `rseq` | `[pq]` | Queue-order / reverse queue-order entries. |
| `reduce` | `[f pq]` `[f init pq]` | Reduction over `[priority value]` entries. |
| `r/fold` | `[n combinef reducef pq]` | Parallel chunked fold. |
| `count` | `[pq]` | O(1). |

---

## Ordered Multiset

Persistent sorted bag that allows duplicate elements.

### Constructors

- `ordered-multiset`
  - `(ordered-multiset)`
  - `(ordered-multiset coll)`
- `ordered-multiset-by`
  - `(ordered-multiset-by pred coll)`
- `ordered-multiset-with`
  - `(ordered-multiset-with comparator)`
  - `(ordered-multiset-with comparator coll)`

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `multiplicity` | `[ms x]` | Count of `x`. |
| `disj-one` | `[ms x]` | Remove one occurrence. |
| `disj-all` | `[ms x]` | Remove all occurrences. |
| `distinct-elements` | `[ms]` | Distinct sorted elements. |
| `element-frequencies` | `[ms]` | `{element -> count}` map. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `conj` | `[ms x]` | Add one occurrence. |
| `disj` | `[ms x]` | Same as `disj-one`. |
| `nth` | `[ms i]` `[ms i not-found]` | Positional access with duplicates counted. |
| `seq` / `rseq` | `[ms]` | Sorted / reverse-sorted elements, duplicates repeated. |
| `reduce` | `[f ms]` `[f init ms]` | Reduction over elements. |
| `r/fold` | `[n combinef reducef ms]` | Parallel chunked fold. |
| `count` | `[ms]` | Total element count, including duplicates. |

---

## Fuzzy Set

Ordered set with nearest-neighbor lookup by distance.

### Constructors

- `fuzzy-set`
  - `(fuzzy-set coll & {:keys [tiebreak distance]})`
- `fuzzy-set-by`
  - `(fuzzy-set-by comparator coll & {:keys [tiebreak distance]})`

Options:

- `:tiebreak` — `:<` or `:>`
- `:distance` — distance function

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `fuzzy-nearest` | `[fs query]` | Returns `[element distance]`. |
| `fuzzy-exact-contains?` | `[fs x]` | Exact membership only. |
| `nearest` | `[fs test k]` | Nearest element satisfying `:<`, `:<=`, `:>=`, `:>`. |
| `subrange` | `[fs test k]` `[fs t1 k1 t2 k2]` | Structure-sharing subcollection. |
| `split-key` | `[k fs]` | Returns `[left hit right]`. |
| `split-at` | `[i fs]` | Returns `[left right]`. |
| `rank` | `[fs x]` | Rank of exact key, or `nil`. |
| `slice` | `[fs start end]` | Elements in index range `[start,end)`. |
| `median` | `[fs]` | Median element. |
| `percentile` | `[fs pct]` | Element at percentile `0..100`. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `conj` | `[fs x]` | Add element. |
| `disj` | `[fs x]` | Remove element. |
| `contains?` | `[fs x]` | Exact membership. |
| `nth` | `[fs i]` `[fs i not-found]` | Positional access. |
| `seq` / `rseq` | `[fs]` | Sorted / reverse-sorted elements. |
| `reduce` | `[f fs]` `[f init fs]` | Reduction over elements. |
| `r/fold` | `[n combinef reducef fs]` | Parallel chunked fold. |
| `count` | `[fs]` | O(1). |

Also callable as a function:

- `(fs query)`

returns the nearest element.

---

## Fuzzy Map

Ordered map with nearest-neighbor lookup by key distance.

### Constructors

- `fuzzy-map`
  - `(fuzzy-map coll & {:keys [tiebreak distance]})`
- `fuzzy-map-by`
  - `(fuzzy-map-by comparator coll & {:keys [tiebreak distance]})`

Options are the same as `fuzzy-set`.

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `fuzzy-nearest` | `[fm query]` | Returns `[key value distance]`. |
| `fuzzy-exact-contains?` | `[fm k]` | Exact key membership only. |
| `fuzzy-exact-get` | `[fm k]` `[fm k not-found]` | Exact key lookup only. |
| `rank` | `[fm k]` | Rank of exact key, or `nil`. |
| `slice` | `[fm start end]` | Entries in index range `[start,end)`. |
| `median` | `[fm]` | Median entry. |
| `percentile` | `[fm pct]` | Entry at percentile `0..100`. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `assoc` | `[fm k v]` | Add/update key-value pair. |
| `dissoc` | `[fm k]` | Remove exact key. |
| `get` / IFn | `[fm k]` `[fm k not-found]` | Fuzzy lookup: value for nearest key. |
| `contains?` | `[fm k]` | Exact key membership. |
| `nth` | `[fm i]` `[fm i not-found]` | Positional access. |
| `seq` / `rseq` | `[fm]` | Sorted / reverse-sorted entries. |
| `reduce` | `[f fm]` `[f init fm]` | Reduction over entries. |
| `reduce-kv` | `[f init fm]` | `(f acc k v)`. |
| `r/fold` | `[n combinef reducef fm]` | Parallel chunked fold. |
| `count` | `[fm]` | O(1). |

Also callable as a function:

- `(fm query)`

returns the value for the nearest key.

---

## Rope

Persistent chunked sequence optimized for structural editing: O(log n) concat,
split, splice, insert, and remove. Backed by a weight-balanced tree of chunk
vectors. Implements `IPersistentVector` (`(vector? rope)` is true),
`java.util.List`, `java.util.RandomAccess`, `Comparable`, and `r/fold`.

### Constructors

- `rope`
  - `(rope)`
  - `(rope coll)`

### Collection-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `rope-concat` | `[x]` `[a b]` `[a b & more]` | One arg: coerce to rope. Two: O(log n) join. Three+: O(total chunks) bulk. |
| `rope-split` | `[rope i]` | Split at index, returns `[left right]`. O(log n). |
| `rope-sub` | `[rope start end]` | Subrange rope. O(log n). |
| `rope-insert` | `[rope i coll]` | Insert elements at index. O(log n). |
| `rope-remove` | `[rope start end]` | Remove range `[start, end)`. O(log n). |
| `rope-splice` | `[rope start end coll]` | Replace range with new content. O(log n). |
| `rope-chunks` | `[rope]` | Seq of internal chunk vectors. |
| `rope-chunks-reverse` | `[rope]` | Reverse seq of internal chunk vectors. |
| `rope-chunk-count` | `[rope]` | Number of chunks. |
| `rope-str` | `[rope]` | Rope of chars/strings to String via StringBuilder. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `conj` | `[rope x]` | Append to end. |
| `assoc` | `[rope i x]` | Replace element at index (or append if `i = count`). |
| `nth` | `[rope i]` `[rope i not-found]` | Positional access. O(log n). |
| `get` | `[rope i]` `[rope i not-found]` | Same as `nth`. |
| `peek` | `[rope]` | Last element. |
| `pop` | `[rope]` | Remove last element. |
| `seq` / `rseq` | `[rope]` | Forward / reverse traversal. |
| `reduce` | `[f rope]` `[f init rope]` | Chunk-aware reduction with `reduced` support. |
| `r/fold` | `[n combinef reducef rope]` | Parallel fork-join fold. |
| `compare` | `[rope1 rope2]` | Lexicographic comparison. |
| `count` | `[rope]` | O(1). |

Also supports `java.util.List` methods: `.get`, `.indexOf`, `.lastIndexOf`,
`.contains`, `.containsAll`, `.subList`, `.size`, `.toArray`.

`rope-sub` returns a `Rope` that shares structure with the original rope,
supporting all rope operations including `assoc`, `conj`, `peek`, and `pop`.

Since `Rope` implements `IPersistentVector`, `(vec rope)` returns the rope
itself. Use `(into [] rope)` to materialize a `PersistentVector`.

---

## String Rope

Persistent chunked text sequence optimized for structural text editing:
O(log n) concat, split, splice, insert, and remove. Backed by a
weight-balanced tree of `java.lang.String` chunks. Implements
`java.lang.CharSequence` for seamless Java text interop, so it drops
into `java.util.regex`, `clojure.string`, and any API expecting text.

Small strings (≤ 1024 characters) are stored as a raw `String`
internally with zero tree overhead. Edits that grow past the threshold
transparently promote to the chunked form.

### Constructors

- `string-rope`
  - `(string-rope)`
  - `(string-rope s)` — accepts a `String`, another `StringRope`, or
    anything `str` can coerce.
- `string-rope-concat`
  - `(string-rope-concat x)` — coerce one argument
  - `(string-rope-concat a b)` — O(log n) binary join
  - `(string-rope-concat a b & more)` — O(total chunks) bulk

### Collection-specific operations

All operations from the shared `PRope` protocol work on StringRope via
the same public functions documented for Rope:

| Function | Signature(s) | Notes |
|---|---|---|
| `rope-concat` | `[x]` `[a b]` `[a b & more]` | Prefer `string-rope-concat` for type-preserving concat. |
| `rope-split` | `[sr i]` | Split at index, returns `[left right]`. O(log n). |
| `rope-sub` | `[sr start end]` | Structure-sharing subrange. O(log n). |
| `rope-insert` | `[sr i coll]` | Insert text at index. `coll` may be a `String`, another `StringRope`, or anything `(str coll)` can coerce. |
| `rope-remove` | `[sr start end]` | Remove range `[start, end)`. O(log n). |
| `rope-splice` | `[sr start end coll]` | Replace range with new text. O(log n). |
| `rope-chunks` | `[sr]` | Seq of internal `String` chunks. |
| `rope-str` | `[sr]` | Materialize to `java.lang.String` (same as `(str sr)`). |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `count` | `[sr]` | O(1). |
| `nth` | `[sr i]` `[sr i not-found]` | Returns a `Character`. O(log n) on tree mode, O(1) on flat mode, O(1) amortized on tree via cursor cache. |
| `get` / IFn | `[sr i]` `[sr i not-found]` | Same as `nth`. |
| `conj` | `[sr c]` | Append a single character. |
| `assoc` | `[sr i c]` | Replace character at index (or append if `i = count`). |
| `peek` | `[sr]` | Last character. |
| `pop` | `[sr]` | Remove last character. |
| `seq` / `rseq` | `[sr]` | Forward / reverse `Character` seq. |
| `reduce` | `[f sr]` `[f init sr]` | Chunk-aware reduction with `reduced` support. |
| `r/fold` | `[n combinef reducef sr]` | Parallel fork-join fold. |
| `str` | `[sr]` | Materialize content to a `java.lang.String`. |
| `compare` | `[a b]` | Lexicographic, matches `String.compareTo`. |

### Java interop

Because `StringRope` implements `java.lang.CharSequence`, it works
directly with:

- `java.util.regex.Pattern` / `Matcher` — `re-find`, `re-seq`,
  `re-matches`, `re-matcher`
- All `clojure.string` functions (they accept `CharSequence`)
- `java.io.Writer.append(CharSequence)` and friends

```clojure
(def doc (oc/string-rope "the quick brown fox"))

(re-find #"\w+" doc)                    ;=> "the"
(clojure.string/upper-case (str doc))   ;=> "THE QUICK BROWN FOX"
(count doc)                             ;=> 19
(.charAt ^CharSequence doc 4)           ;=> \q
```

### Equality and hashing

- `(= (string-rope "x") "x")` is true — StringRope is equal to any
  `CharSequence` with the same content.
- `(hash (string-rope "x"))` matches `(hash "x")`, so StringRope and
  String can be used interchangeably as hash-map keys.
- `(= (string-rope "x") (oc/rope [\x]))` is false — the generic rope
  and the string rope have different identity.

### Printed form

```clojure
#string/rope "hello world"
```

Round-trips through EDN via the `#string/rope` tagged literal.

### Transient

`(transient string-rope)` returns a `TransientStringRope` backed by a
`StringBuilder` tail buffer. Call `conj!` with characters (or
single-character strings) and `persistent!` to finalize. Useful for
batch construction of large strings.

---

## Byte Rope

Persistent chunked binary sequence: O(log n) concat, split, splice,
insert, and remove. Backed by a weight-balanced tree of `byte[]`
chunks. Bytes are exposed as unsigned longs in `[0, 255]` throughout
the API — storage is signed Java bytes (same bits), avoiding the usual
signed-byte pitfalls.

Small byte sequences (≤ 1024 bytes) are stored as a raw `byte[]`
internally. Edits that grow past the threshold transparently promote
to chunked form.

ByteRope is the immutable persistent counterpart to `java.nio.ByteBuffer`
/ protobuf `ByteString` / Okio `ByteString` — same conventions
(unsigned bytes, big-endian default, lexicographic compare via
`Arrays/compareUnsigned`), different semantics (persistent snapshots,
structural sharing, O(log n) edits).

### Constructors

- `byte-rope`
  - `(byte-rope)`
  - `(byte-rope x)` — accepts any of:
    - `byte[]` (defensively copied)
    - another `ByteRope`
    - `String` (UTF-8 encoded)
    - `java.io.InputStream` (fully consumed)
    - sequential of unsigned integers in `[0, 255]`
- `byte-rope-concat`
  - `(byte-rope-concat x)` — coerce one argument
  - `(byte-rope-concat a b)` — O(log n) binary join
  - `(byte-rope-concat a b & more)` — O(total chunks) bulk

### Collection-specific operations

From the shared `PRope` protocol:

| Function | Signature(s) | Notes |
|---|---|---|
| `rope-split` | `[br i]` | Split at index. O(log n). |
| `rope-sub` | `[br start end]` | Structure-sharing subrange. O(log n). |
| `rope-insert` | `[br i coll]` | Insert bytes at index. `coll` may be a `byte[]`, another `ByteRope`, or a sequential of unsigned integers. |
| `rope-remove` | `[br start end]` | Remove range `[start, end)`. O(log n). |
| `rope-splice` | `[br start end coll]` | Replace range with new bytes. O(log n). |
| `rope-chunks` | `[br]` | Seq of internal `byte[]` chunks. |
| `rope-str` | `[br]` | Materialize to a defensively-copied `byte[]`. |

### Byte-specific operations

| Function | Signature(s) | Notes |
|---|---|---|
| `byte-rope-bytes` | `[br]` | Defensive-copy `byte[]` materialization. Same as `rope-str` but with a more precise name. |
| `byte-rope-hex` | `[br]` | Return a lowercase hex string. |
| `byte-rope-write` | `[br out]` | Stream chunks to a `java.io.OutputStream`. |
| `byte-rope-input-stream` | `[br]` | Return a fresh `java.io.InputStream` over the contents. |
| `byte-rope-get-byte` | `[br offset]` | Unsigned byte value (long in `[0, 255]`). |
| `byte-rope-get-short` | `[br offset]` | Big-endian unsigned 16-bit integer. |
| `byte-rope-get-short-le` | `[br offset]` | Little-endian unsigned 16-bit integer. |
| `byte-rope-get-int` | `[br offset]` | Big-endian signed 32-bit integer. |
| `byte-rope-get-int-le` | `[br offset]` | Little-endian signed 32-bit integer. |
| `byte-rope-get-long` | `[br offset]` | Big-endian signed 64-bit integer. |
| `byte-rope-get-long-le` | `[br offset]` | Little-endian signed 64-bit integer. |
| `byte-rope-index-of` | `[br b]` `[br b from]` | First index of the unsigned byte value, or -1. |
| `byte-rope-digest` | `[br algorithm]` | Compute a cryptographic digest (`"SHA-256"`, `"MD5"`, etc.) by streaming chunks through `java.security.MessageDigest`. Returns a ByteRope of the digest. |

### Standard collection operations

| Operation | Signature(s) | Notes |
|---|---|---|
| `count` | `[br]` | O(1). |
| `nth` | `[br i]` `[br i not-found]` | Returns an unsigned long in `[0, 255]`. O(log n) on tree mode, O(1) on flat mode, O(1) amortized on tree via cursor cache. |
| `get` / IFn | `[br i]` `[br i not-found]` | Same as `nth`. |
| `conj` | `[br b]` | Append a single byte (accepts an integer in `[0, 255]`). |
| `assoc` | `[br i b]` | Replace byte at index (or append if `i = count`). |
| `peek` | `[br]` | Last byte as an unsigned long. |
| `pop` | `[br]` | Remove last byte. |
| `seq` / `rseq` | `[br]` | Forward / reverse seq of unsigned longs. |
| `reduce` | `[f br]` `[f init br]` | Chunk-aware reduction with `reduced` support. |
| `r/fold` | `[n combinef reducef br]` | Parallel fork-join fold. |
| `compare` | `[a b]` | Unsigned lexicographic via `Arrays/compareUnsigned`. |

### Equality and hashing

- `(= (byte-rope (byte-array [1 2 3])) (byte-array [1 2 3]))` is true —
  ByteRope is equal to a `byte[]` with the same content.
- `(= (byte-rope [1 2 3]) [1 2 3])` is **false** — intentionally not equal
  to a Clojure vector, to avoid signed vs unsigned confusion.
- `(hash (byte-rope [1 2 3]))` is a content-based Murmur3 hash over the
  unsigned byte values. (Clojure's default `hash` on a raw `byte[]` is
  identity-based, not content-based, so ByteRope hash and byte[] hash
  are not comparable — use ByteRope instances as hash-map keys.)

### Printed form

```clojure
#byte/rope "48656c6c6f"
```

Round-trips through EDN via the `#byte/rope` tagged literal. The literal
content is a lowercase hex string.

### Transient

`(transient byte-rope)` returns a `TransientByteRope` backed by a
`ByteArrayOutputStream` tail buffer. Call `conj!` with unsigned integer
values and `persistent!` to finalize.

### Does NOT implement

- `java.lang.CharSequence` — ByteRope is not text. Convert explicitly
  via `(String. (byte-rope-bytes br) "UTF-8")` or similar.
- `IPersistentVector` — ByteRope is a specialized byte sequence, not
  a general vector.
- `java.util.List` — too many mutable method stubs to implement
  meaningfully for an unsigned byte domain.
