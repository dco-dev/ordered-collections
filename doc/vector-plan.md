# Vector Plan

## Goal

Create a new persistent “vector-like” collection type backed by this library’s
weight-balanced tree architecture.

Target semantic model:

- Clojure `persistent-vector` semantics and API, as far as is reasonable
- indexed sequential collection
- efficient `nth`, `assoc`, `conj`, `pop`, `peek`, `count`, `seq`, `rseq`
- value equality with vectors and sequential collections where appropriate
- metadata support
- serialization / EDN / hashing / reduction behavior consistent with the rest
  of this library

Important realism:

- it is unlikely that a weight-balanced binary tree will be competitive with
  Clojure’s built-in `PersistentVector` for scalar random-access workloads
- this should only be built if it is conceptually clean and offers something
  meaningful that tree architecture does well


## Core Conclusion Up Front

If this is pursued, it should **not** be implemented as:

- an `ordered-map` from integer index to value
- an `ordered-set` with synthetic sequence-number keys

Those approaches are the wrong fit.

Reasons:

- middle insertion would require rekeying everything to the right
- append-heavy usage would still carry unnecessary ordered-key machinery
- the semantics would be pretending an indexed sequence is an ordered map
- the implementation would fight the tree architecture instead of using it well

The right conceptual model is:

## An implicit-index sequence tree

That means:

- position is derived from subtree sizes
- there is no user-facing key space
- ordering is by sequence position, not comparator
- balancing is still weight-balanced
- `nth`, `split-at`, and concatenation are tree primitives

This is much closer to:

- a rope
- a sequence tree
- a size-balanced random-access sequence

than to an ordered set/map.


## Why This Architecture Is Plausible Here

This repository already has several tree mechanics that point in the right
direction:

- subtree sizes are already maintained
- `node-nth` already exists
- rank/slice/split-at logic already exists conceptually
- reducers and enumerators are already strong
- direct seq support is already built around tree traversal
- concatenation by balanced stitch/join is already natural in this codebase

What the current tree is *not* optimized for:

- index-based mutation without ordered keys
- implicit-position insert/delete
- bulk concatenation as the primary operation rather than ordered-key join

So the path is not “reuse ordered-set/map directly.”
The path is “reuse the weight-balanced mechanics and traversal style, but build
an index-native tree layer.”


## The Best Conceptual Model

### `PersistentVectorTree`

A new collection type should probably be a dedicated `deftype`, something like:

- `PersistentVectorTree`
- or `OrderedVector`
- or `TreeVector`

Internally:

- tree nodes store values
- subtree sizes define positions
- the tree is balanced by weight, not by comparator

Core operations would be:

- `nth`: descend by subtree sizes
- `assoc`: replace value at index
- `conj`: append at right edge
- `peek`: greatest/rightmost value
- `pop`: remove greatest/rightmost value
- `split-at`: split by rank
- `cat` / concatenation: balanced join of two sequence trees
- optional `insert-at`: split + concat + singleton
- optional `delete-at`: split around index + concat

This is a very natural fit for weight-balanced trees.


## Important Design Decision

### Do **not** force this into the existing ordered-key tree API

The existing low-level tree API in:

- [tree.clj](/Users/dan/src/ordered-collections/src/ordered_collections/tree/tree.clj)

is built around:

- keys
- comparators
- ordered lookup/split by key

That is the right abstraction for ordered sets/maps.
It is not the right abstraction for a vector.

Trying to express vectors through that API would likely produce:

- ugly code
- bad performance
- confusing semantics
- accidental complexity

Instead, this should probably be a **new tree layer** that reuses the same
balancing ideas and perhaps some traversal infrastructure, but not the entire
ordered-key surface.


## Suggested Namespace Layout

If implemented, a clean structure would be:

- `src/ordered_collections/types/tree_vector.clj`
  - public collection type

- `src/ordered_collections/tree/sequence.clj`
  - low-level implicit-index sequence tree mechanics

Possibly:

- `src/ordered_collections/tree/sequence_node.clj`
  - if a dedicated node representation is warranted

This is likely cleaner than trying to expand `tree.clj` with a second,
orthogonal algorithm family.


## Data Structure Options

### Option A: pure element-per-node sequence tree

Each node stores:

- value
- left child
- right child
- subtree size

Advantages:

- conceptually simple
- maps well to existing weight-balanced logic
- excellent support for `split-at` and concatenation
- no chunk-leaf complexity

Disadvantages:

- very pointer-heavy
- random access and append likely much slower than built-in vector
- poor cache locality

This is the simplest and most defensible first implementation.

### Option B: chunked leaves on top of weight-balanced tree

Each internal node stores:

- subtree size
- left child
- right child

Leaves store small vectors/arrays of elements.

Advantages:

- much better locality
- fewer nodes
- lower constant factors
- closer in spirit to ropes / RRB-style chunking

Disadvantages:

- much more complex
- split/merge logic gets harder
- balancing and leaf-splitting interaction must be designed carefully
- larger implementation surface

This is the more promising performance direction, but probably not the right
first implementation unless the simpler version is already known to be too slow.


## Recommended Plan

### Phase 1: Prove the concept with the simple sequence tree

Build a clean, minimal, non-chunked implicit-index tree first.

Rationale:

- easiest to reason about
- easiest to test
- easiest to validate against vector semantics
- lets us learn whether the collection is valuable at all

If that version is already useful enough, keep it.
If it is too slow but conceptually good, then consider chunked leaves.


## Operations To Support First

### Must-have MVP

- `count`
- `empty`
- `conj`
- `peek`
- `pop`
- `nth`
- `assoc`
- `seq`
- `rseq`
- `reduce`
- metadata
- equality / hashing
- serialization

### Strongly desirable in phase 1

- `split-at`
- concatenation / `into`
- `subvec`-like slicing

### Nice later additions

- `insert-at`
- `delete-at`
- efficient concatenation API
- chunked fold / parallel fold if it ends up making sense


## API Guidance

### What should the public constructor be?

Likely:

- `(tree-vector)`
- `(tree-vector coll)`

Potential specialized public operations:

- `(tv/split-at i v)` or extend existing `split-at` protocol if semantics fit
- `(tv/subvec v start end)` or extend via protocol if clean
- `(tv/concat v1 v2)`

### Semantics target

The target model is `PersistentVector`, so the collection should implement as
many familiar interfaces as are sensible:

- `Indexed`
- `Counted`
- `Seqable`
- `Reversible`
- `ILookup`
- `IPersistentVector`
- `Associative`
- `IPersistentStack`
- `IReduce`
- `IObj`
- `IMeta`

But do not fake interfaces that do not fit naturally.


## Likely Tree Primitives Needed

In a new sequence-tree namespace, expect to need:

- `seq-node-size`
- `seq-node-create`
- `seq-node-stitch`
- `seq-node-concat`
- `seq-node-split-at`
- `seq-node-nth`
- `seq-node-assoc-nth`
- `seq-node-pop-right`
- `seq-node-peek-right`
- `seq-node-reduce`
- enumerator / seq support for values

This should parallel the current tree style, but without comparator-based
ordering.


## Performance Expectations

This is the most important strategic point.

### What this collection will likely do well

- persistent concatenation
- split-at
- subvec-like slicing if implemented structurally
- `nth` / `assoc` in `O(log n)`
- persistence with structural sharing
- large-sequence structural editing

### What it will likely not beat

- built-in `PersistentVector` for:
  - raw append
  - raw random access
  - cache-local iteration over flat vectors

Therefore the value proposition should probably not be:

- “faster vector”

It should instead be:

- “vector-like persistent sequence with strong structural split/concat/edit
   operations”

This is a very different story.


## Go / No-Go Criteria

This project should not be pursued indefinitely if it cannot justify itself.

Suggested criteria:

### Go

Proceed if phase-1 implementation is:

- conceptually clean
- semantically correct
- easy to test
- reasonably fast for `nth`, `assoc`, `conj`, and seq
- clearly strong on split/concat/slice-style operations

### No-Go

Stop if:

- the implementation becomes an ugly distortion of ordered-set/map code
- default vector operations are too slow even for a niche structure
- there is no clear win versus simply using `PersistentVector`
- docs would have to oversell the collection to justify its existence


## Benchmark Plan

Before any serious claim is made, benchmark against:

- `PersistentVector`
- possibly `clojure.lang.PersistentQueue` for certain stack/queue-like ends if
  relevant

Benchmark at:

- `1K`
- `10K`
- `100K`
- maybe `1M`

Workloads:

1. construction from collection
2. repeated `conj`
3. repeated `nth`
4. repeated `assoc`
5. iteration / reduce
6. `split-at`
7. concatenation of two halves
8. subvec/slice extraction

The most important likely differentiators are:

- `split-at`
- concatenation
- structural slicing

Those are where a tree vector might actually make sense.


## Testing Plan

Use vector semantics as the reference model.

For randomized tests:

- compare against built-in vectors
- apply random operation sequences to both
- validate:
  - `count`
  - `nth`
  - `assoc`
  - `conj`
  - `peek`
  - `pop`
  - `seq`
  - `rseq`
  - equality / hashing
  - metadata propagation

If split/cat are included:

- `split-at i` followed by concat should reconstruct original
- slices should match `subvec`
- `nth` should align before and after split/concat transforms


## Relationship To Existing Protocols

Some existing protocols may be reusable:

- `split-at`
- maybe ranked/slice protocols

But do not force the vector type into order-query protocols that assume
comparator semantics such as:

- `nearest`
- comparator-based `subrange`

Vector position is not ordered-key search.


## Documentation Guidance

If this collection is added, documentation should be careful and honest.

Do **not** describe it as a replacement for Clojure vectors.

Describe it as:

- a persistent indexed sequence tree
- vector-like semantics
- strong structural split/concat behavior
- logarithmic indexed access and updates

Potential docs to update if implemented:

- `README.md`
- `doc/collections-api.md`
- `doc/algorithms.md`
- `doc/benchmarks.md`
- `CHANGES.md`


## Recommendation

If work begins, start with:

1. a new low-level implicit-index sequence tree namespace
2. a minimal `tree-vector` `deftype`
3. only the core vector operations
4. strong tests against built-in vectors
5. focused benchmarks on:
   - `nth`
   - `assoc`
   - `conj`
   - `split-at`
   - concatenation

Only after that decide whether:

- the feature is worth keeping
- chunked leaves are needed
- additional vector API surface is warranted


## Bottom Line

A vector built on this architecture can be conceptually elegant, but only if it
is treated as an **implicit-index sequence tree**, not as an ordered map with
integer keys.

The right question is not:

- “can we beat `PersistentVector`?”

The right question is:

- “can we create a clean, persistent, indexed sequence type with excellent
   structural split/concat semantics that fits naturally with this codebase’s
   tree design?”

That is the version of this project worth pursuing.

