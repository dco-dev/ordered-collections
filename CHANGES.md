# Changelog

All notable changes to this project will be documented in this file.

## [0.2.0] - 2025-02-11

### New Features

#### New Collection Types

- **Priority Queue** (`priority-queue`): O(log n) push/peek/pop with parallel fold
  ```clojure
  (def pq (priority-queue [3 1 4 1 5]))
  (peek pq)  ; => 1 (min element)
  (pop pq)   ; => queue without min
  (push pq 0 :zero)  ; => queue with [0 :zero] added
  ```

- **Ordered Multiset** (`ordered-multiset`): Sorted bag allowing duplicates
  ```clojure
  (def ms (ordered-multiset [3 1 4 1 5 9 2 6 5 3 5]))
  (seq ms)         ; => (1 1 2 3 3 4 5 5 5 6 9)
  (multiplicity ms 5)  ; => 3
  (disj-one ms 5)  ; removes one occurrence
  ```

- **Fuzzy Set** (`fuzzy-set`): Returns closest element to query
  ```clojure
  (def fs (fuzzy-set [1 5 10 20]))
  (fs 7)   ; => 5 (closest to 7)
  (fs 15)  ; => 10 or 20 depending on tiebreak

  ;; With tiebreaker
  (def fs (fuzzy-set [0 10 20] :tiebreak :>))
  (fs 15)  ; => 20 (prefer larger when equidistant)
  ```

- **Fuzzy Map** (`fuzzy-map`): Returns value for closest key to query
  ```clojure
  (def fm (fuzzy-map {0 :zero 10 :ten 100 :hundred}))
  (fm 7)   ; => :ten (closest key to 7 is 10)
  (fm 55)  ; => :ten or :hundred depending on tiebreak

  ;; Exact lookup (no fuzzy matching)
  (fuzzy-exact-get fm 10)   ; => :ten
  (fuzzy-exact-get fm 11)   ; => nil
  ```

#### Specialized Comparator Constructors

- **Type-specific constructors** for competitive lookup performance:
  ```clojure
  ;; Long keys - 3% faster than sorted-set
  (long-ordered-set [1 2 3])
  (long-ordered-map [[1 :a] [2 :b]])

  ;; String keys - 5% faster than sorted-set
  (string-ordered-set ["apple" "banana" "cherry"])
  (string-ordered-map [["a" 1] ["b" 2]])

  ;; Double keys
  (double-ordered-set [1.0 2.0 3.0])
  (double-ordered-map [[1.0 :a] [2.0 :b]])
  ```

- **Custom comparator constructors** for full control:
  ```clojure
  ;; Pass a java.util.Comparator directly
  (ordered-set-with long-compare [1 2 3])
  (ordered-map-with string-compare [["a" 1] ["b" 2]])

  ;; Build from predicate (slightly slower)
  (ordered-set-with (compare-by >) [1 2 3])  ; descending
  ```

- **Exported comparators** for reuse:
  - `long-compare` - optimized Long comparison
  - `double-compare` - optimized Double comparison
  - `string-compare` - optimized String comparison
  - `compare-by` - build Comparator from predicate

#### Full `clojure.lang.Sorted` Support
- `ordered-set` and `ordered-map` now implement `clojure.lang.Sorted`
- Enables native `subseq` and `rsubseq` support:
  ```clojure
  (def os (ordered-set (range 10)))
  (subseq os >= 3 < 7)  ; => (3 4 5 6)
  (rsubseq os > 5)      ; => (9 8 7 6)

  (def om (ordered-map (map #(vector % (str %)) (range 10))))
  (subseq om >= 3 < 7)  ; => ([3 "3"] [4 "4"] [5 "5"] [6 "6"])
  ```

#### Parallel Fold (`r/fold`) for All Collection Types
- All collection types now implement `clojure.core.reducers/CollFold`
- Enables efficient parallel reduction via `r/fold`:
  ```clojure
  (require '[clojure.core.reducers :as r])
  (def os (ordered-set (range 1000000)))
  (r/fold + os)  ; parallel sum - 1.6x faster than sorted-set
  ```
- Supported types:
  - `ordered-set`, `ordered-map`
  - `interval-set`, `interval-map`
  - `priority-queue`, `ordered-multiset`
  - `fuzzy-set`, `fuzzy-map`

#### Proper Hash Support
- `ordered-set` and `ordered-map` now implement `clojure.lang.IHashEq`
- Enables correct behavior in hash-based collections:
  ```clojure
  (def s1 (ordered-set [1 2 3]))
  (def s2 (ordered-set [1 2 3]))
  (= (hash s1) (hash s2))  ; => true
  #{s1 s2}                 ; => #{#{1 2 3}} (deduplicated)
  ```

#### Serialization Support
- `ordered-set` and `ordered-map` now implement `java.io.Serializable`
- Enables serialization via Java serialization mechanisms

### Performance Improvements

#### ForkJoinPool Parallel Set Operations
- Set operations (union, intersection, difference) now use `java.util.concurrent.ForkJoinPool`
- Work-stealing parallelism based on Blelloch, Ferizovic, Sun (2016) join-based algorithms
- **6.9x faster** union, **7.4x faster** intersection vs `clojure.set`
- Automatic threshold tuning (8K elements) for optimal sequential/parallel tradeoff

#### Primitive Lookup Optimization
- `long-ordered-set` and `long-ordered-map` now use primitive `Long/compare` directly
- Bypasses `java.util.Comparator` interface dispatch entirely
- **20% faster** lookups than `sorted-set` for Long keys
- Automatic detection: uses fast path when comparator is `long-compare`

#### Primitive Node Types
- `LongKeyNode` and `DoubleKeyNode` store keys as primitives (not boxed)
- Used automatically by `long-ordered-set`, `long-ordered-map`, etc.
- Reduces GC pressure and memory overhead for numeric workloads

#### Iteration Performance
- All types implement optimized `IReduceInit` and `IReduce` for fast reduce
- **Direct reduce: 2.1x faster than sorted-set** via direct tree traversal

#### Seq Performance
- New direct `ISeq` implementations (`KeySeq`, `EntrySeq`) replace lazy-seq + map wrappers
- Seq types also implement `IReduceInit` for fast reduce over seqs
- **Reduce over seq: 1.4x faster than sorted-set/sorted-map**
- **Seq iteration (first/next): within 7% of sorted-set/sorted-map**
- Efficient reverse seq via `KeySeqReverse` and `EntrySeqReverse`
- All seq types implement `Counted` for O(1) count when size is known

#### Lookup Performance
- Comparators implement `java.util.Comparator` for fast dispatch
- `long-ordered-set`/`long-ordered-map` use primitive `Long/compare`
- **`long-ordered-set` is 3% faster than `sorted-set`** for numeric keys
- `ordered-set` with default comparator is 14% slower (use `long-*` for numerics)

#### Reduced Dynamic Var Overhead
- Hot-path operations (`assoc`, `dissoc`, `get`, `contains?`) bypass dynamic binding
- Explicit parameter passing to tree functions eliminates binding push/pop overhead
- ~200ns savings per operation

### Bug Fixes

#### SortedSet Semantics
- `tailSet` now correctly returns elements >= x (was exclusive, now inclusive)
- `subSet` now correctly returns elements >= from and < to
- Matches Java `SortedSet` contract

#### Interval Tree Construction
- Fixed `interval-set` and `interval-map` construction to use sequential reduce instead of parallel fold
- Previously, parallel workers lost dynamic binding for node allocator, causing `ClassCastException` for collections >2048 elements
- Interval trees now construct correctly at all sizes

### Performance Summary (vs sorted-map/sorted-set at N=100K)

| Operation | ordered-* | long-ordered-* | string-ordered-* |
|-----------|-----------|----------------|------------------|
| Construction (batch) | **14% faster** | **7% faster** | **14% faster** |
| Sequential insert | 1.4-2.3x slower | 1.4-2.3x slower | 1.4-2.3x slower |
| Lookup | 58% slower | **20% faster** | **5% faster** |
| Direct reduce | **2.4x faster** | **2.4x faster** | **2.4x faster** |
| Reduce over seq | **27% faster** | **27% faster** | **27% faster** |
| First/last | **13,000x faster** | **13,000x faster** | **13,000x faster** |
| Set operations | **7x faster** | **7x faster** | **7x faster** |
| Parallel fold | **2.3x faster** | **2.3x faster** | **2.3x faster** |
| nth/rank | **O(log n)** | **O(log n)** | **O(log n)** |

### Breaking Changes

#### Removed Mutable Variants
- **Removed**: `mutable-ordered-set`, `mutable-ordered-map`, `mutable-interval-set`, `mutable-interval-map`
- The mutable variants added API complexity with marginal performance benefit
- Use persistent types directly - construction via `ordered-set` and `ordered-map` is now faster
- For batch operations, the persistent constructors now use parallel fold internally

#### Removed Transient Support
- **Removed**: `transient`/`persistent!` support from all collection types
- The implementation only saved wrapper allocation, not tree node allocation
- Tree operations still did full path-copying, providing no meaningful speedup
- This simplifies the API without loss of real-world performance

---

## [0.1.2] - 2024

- Documentation improvements
- Minor bug fixes

## [0.1.1] - 2024

- Initial public release
- Weight-balanced persistent binary trees
- `ordered-set`, `ordered-map`, `interval-set`, `interval-map`
- Efficient set operations (intersection, union, difference)
- `nth` and `indexOf` in O(log n) time
