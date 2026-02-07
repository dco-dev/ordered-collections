# Changelog

All notable changes to this project will be documented in this file.

## [0.2.0] - Unreleased

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

#### Iteration Performance
- Stack-based iteration using `java.util.ArrayDeque` replaces enumerator-based traversal
- **Map iteration: 2.4x faster** (now faster than `sorted-map`)
- **Set iteration: 3.9x faster** (now faster than `sorted-set`)
- All types implement optimized `IReduceInit` and `IReduce`

#### Lookup Performance
- Comparators now implement `java.util.Comparator` interface
- Direct `invokeinterface` dispatch eliminates IFn overhead
- **Lookup performance within 8-10% of `sorted-map`**

#### Reduced Dynamic Var Overhead
- Hot-path operations (`assoc`, `dissoc`, `get`, `contains?`) bypass dynamic binding
- Explicit parameter passing to tree functions eliminates binding push/pop overhead
- ~200ns savings per operation

### Bug Fixes

#### SortedSet Semantics
- `tailSet` now correctly returns elements >= x (was exclusive, now inclusive)
- `subSet` now correctly returns elements >= from and < to
- Matches Java `SortedSet` contract

### Performance Summary (vs sorted-map/sorted-set at N=500K)

| Operation | ordered-map | ordered-set |
|-----------|-------------|-------------|
| Construction | 2.2x slower | 0.75x faster |
| Insert | 2.1x slower | 1.6x slower |
| Delete | 1.9x slower | 1.5x slower |
| Lookup | 1.08x slower | 1.21x slower |
| Iteration (reduce) | **0.92x faster** | **0.64x faster** |
| Parallel fold | **1.6x faster** | **1.6x faster** |
| Split | N/A | **5x faster** |

### Breaking Changes

#### Removed Mutable Variants
- **Removed**: `mutable-ordered-set`, `mutable-ordered-map`, `mutable-interval-set`, `mutable-interval-map`
- The mutable variants added API complexity with marginal performance benefit
- Use persistent types directly - construction via `ordered-set` and `ordered-map` is now faster
- For batch operations, the persistent constructors now use parallel fold internally

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
