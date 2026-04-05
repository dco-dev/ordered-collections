# Rope Plan

## Goal

Develop the experimental `ordered-collections.types.rope/Rope` into a real,
useful persistent rope collection, while keeping it isolated from the existing
public API surface.

The current implementation is intentionally experimental:

- it does **not** live in `core.clj`
- it is **not** documented in the README
- it is free to evolve until the design is clearly worthwhile

This document is a technical handoff for future work. It should let another
competent AI continue the rope effort without having to rediscover the design
history.


## Current State

The experiment currently lives in:

- [src/ordered_collections/tree/rope.clj](/Users/dan/src/ordered-collections/src/ordered_collections/tree/rope.clj)
- [src/ordered_collections/types/rope.clj](/Users/dan/src/ordered-collections/src/ordered_collections/types/rope.clj)
- [test/ordered_collections/rope_test.clj](/Users/dan/src/ordered-collections/test/ordered_collections/rope_test.clj)

Validation status at the time of writing:

- `lein test`
- `529` tests
- `469408` assertions
- `0` failures
- `0` errors


## Design History

### What was tried first

The first prototype was effectively a vector-like collection implemented as a
weight-balanced tree with **one element per node**.

That version had three major problems:

- it was not really rope-like
- it was badly uncompetitive with Clojure `PersistentVector`
- structural operations like `assoc` and `split` became pathological because
  the concatenation strategy was too naive

The initial implementation used a sequence-tree helper namespace:

- `src/ordered_collections/tree/sequence.clj`

That namespace has now been replaced by:

- `src/ordered_collections/tree/rope.clj`


### Why the current design is better

The current rope is **chunked**:

- each tree node stores a chunk vector in the node key slot
- the node value stores subtree **element count**
- the tree balance metric remains ordinary node count

This is much closer to a real rope:

- concatenation is structural
- slicing is structural
- chunk boundaries matter
- append and split no longer force a one-element-per-node representation


## Conceptual Model

The rope is an **implicit-index chunk tree**.

Important properties:

- there is no user-facing key space
- order is positional, not comparator-driven
- subtree element counts determine indices
- weight-balanced tree rotations still provide persistence and balance
- chunk vectors are the locality unit

The architecture is intentionally compatible with the rest of the codebase:

- it reuses the existing node representation (`SimpleNode`)
- it reuses `node-stitch`, `node-least-kv`, `node-remove-least`, and the tree
  enumerator/reducer infrastructure where that is actually useful
- it does **not** try to force a rope through the ordered-key API


## Low-Level Representation

In [src/ordered_collections/tree/rope.clj](/Users/dan/src/ordered-collections/src/ordered_collections/tree/rope.clj):

- node key = chunk vector
- node value = subtree element count
- left/right children = rope subtrees
- node balance metric = subtree node count, as with the rest of the tree code

The constructor used internally is:

- `rope-node-create`

It creates a `SimpleNode` with:

- `k = chunk`
- `v = (+ (count chunk) (rope-size l) (rope-size r))`
- `x = ordinary node-count balance metric`

So:

- `tree/node-size` still measures node count
- `rope-size` measures total element count

That distinction is fundamental.


## Current Public Experimental API

In [src/ordered_collections/types/rope.clj](/Users/dan/src/ordered-collections/src/ordered_collections/types/rope.clj):

- `empty-rope`
- `rope`
- `concat-rope`
- `rope-chunks`
- `rope-chunks-reverse`
- `chunk-count`
- `split-rope-at`
- `subrope`
- `insert-rope-at`
- `remove-rope-range`
- `splice-rope`

The `Rope` type currently supports:

- `count`
- `nth`
- `get`
- vector-style IFn lookup
- `assoc`
- `conj`
- `peek`
- `pop`
- `seq`
- `rseq`
- `reduce`
- metadata
- sequential equality
- vector marker behavior via `IPersistentVector`

There is now also a `RopeSlice` type used by `subrope`.


## Current Low-Level Operations

In [src/ordered_collections/tree/rope.clj](/Users/dan/src/ordered-collections/src/ordered_collections/tree/rope.clj):

- `rope-size`
- `chunks->root`
- `root->chunks`
- `coll->root`
- `normalize-chunks`
- `normalize-root`
- `rope-concat`
- `rope-nth`
- `rope-assoc`
- `rope-split-at`
- `rope-subvec-root`
- `rope-peek-right`
- `rope-pop-right`
- `rope-conj-right`
- `rope-chunks-seq`
- `rope-chunks-rseq`
- `rope-seq`
- `rope-rseq`
- `rope-chunks-reduce`
- `rope-reduce`


## What This Is Good At

The current rope is already structurally interesting in ways a plain vector is
not:

- persistent concatenation is natural
- persistent split is natural
- subrange extraction is natural
- chunked locality is better than the original one-element tree

This makes the rope potentially compelling for workloads dominated by:

- repeated concatenation
- slicing
- splicing
- persistent editing of large sequences


## What This Is Not Good At Yet

It is **not** yet a mature rope implementation.

Major gaps:

- no builder API
- no local incremental chunk normalization policy
- current normalization is rebuild-style repacking
- `RopeSlice` is useful, but still implemented more simply than a mature
  first-class slice type would be
- no serious benchmarking yet
- no proof that it beats built-in vectors on any important workload

The user explicitly said there is no sense doing Criterium work yet. Structural
and API maturity come first.


## Important Constraints

### 1. Keep it isolated

Do **not** wire the rope into:

- `src/ordered_collections/core.clj`
- `README.md`
- benchmark claims
- public reader tags

unless the user explicitly asks for promotion out of experimental status.


### 2. Resemble the other collection types

The user asked that this should resemble:

- `ordered_set.clj`
- `priority_queue.clj`
- the other collection `deftype` namespaces

That means:

- collection semantics belong in `types/rope.clj`
- low-level mechanics belong in `tree/rope.clj`
- avoid leaking experimental code into unrelated namespaces


### 3. Do not overfit to vector

The target semantic reference is Clojure vector behavior, but the likely value
of the rope is **not** beating `PersistentVector` at ordinary random access.

The interesting question is:

- can this become a good rope?

not:

- can this fake being a better vector?


## What Has Been Added Since The First Draft

The first draft of this plan was written before the rope had:

- structural editing operations
- chunk iteration
- normalization
- a slice/view type

Those now exist.

Specifically:

- `insert-rope-at`
- `remove-rope-range`
- `splice-rope`
- `rope-chunks`
- `rope-chunks-reverse`
- `chunk-count`
- `RopeSlice`
- `normalize-root`


## Current Assessment

The rope is now:

- clearly rope-shaped
- structurally useful
- better than the original one-element-per-node prototype
- still not ready to be treated as a polished public collection

Roughly:

- good experimental rope prototype
- not yet a production rope

The main reason is that the remaining work is now about *quality of the rope
model*, not just missing basic operations.


## Current Main Weaknesses

### 1. Normalization is too blunt

The current normalization story is:

- flatten chunk sequence
- repartition by target chunk size
- rebuild

That is clean and correct, but not mature.

What a better rope would want:

- local adjacent-chunk merge/split
- normalization that preserves more of the existing structure
- less full rebuilding after edits


### 2. `RopeSlice` is semantically useful, but not yet elegant

Current `RopeSlice` behavior is acceptable, but some methods still lean on
simpler materializing paths rather than a true dedicated slice traversal layer.

That is good enough for the experiment, but not the final form.


### 3. No benchmarked compelling workload yet

There is still no demonstrated story like:

- "rope clearly wins on repeated splice workloads"
- "rope clearly wins on repeated concat/split workflows"

Until that exists, the experiment should remain isolated.


## Recommended Next Work

### 1. Improve local chunk normalization

This is now the most important technical next step.

Possible direction:

- after concat or split, inspect exposed boundary chunks
- merge adjacent small chunks
- split oversized chunks
- rebuild only the small local fringe when possible

This would preserve the current clean tree model while making the rope less
reliant on full rechunking.


### 2. Improve `RopeSlice`

Possible next improvements:

- direct slice chunk traversal without flattening through `vec`
- direct chunk iteration over slices
- slice-specific concat/split helpers where that reduces materialization


### 3. Add randomized edit-sequence testing

The next best tests would be operation-sequence property tests against ordinary
vectors:

- random insert
- random remove-range
- random splice
- random subrope/use-as-source cases

That would do more for confidence than adding many more example tests.


### 4. Only after that, explore benchmark claims

At that point, lightweight benchmark exploration would make sense for:

- repeated concat
- repeated split
- repeated splice
- workloads dominated by structural editing rather than random scalar `nth`


## Previous "Next Additions"

These were the next most defensible rope-facing operations, and they are now
implemented:

- structural editing
- chunk exposure
- a slice/view type


## Implementation Guidance

### Insert

Preferred implementation:

1. split at index
2. build a small rope from inserted content
3. concat left + inserted + right

Use chunk-sized rope construction rather than repeated single-element `conj`
when inserting collections.


### Remove range

Preferred implementation:

1. split at `start`
2. split the right part at `(- end start)`
3. discard middle
4. concat left + right


### Splice

Preferred implementation:

1. split at `start`
2. split right at removed length
3. concat left + inserted + right


### Chunk iteration

Prefer exposing chunk vectors directly, probably as a seq of vectors. That is a
more honest rope API than flattening everything immediately.


## Structural Risks

### Chunk-size drift

This has improved, but not been solved completely.

The rope now normalizes chunk layout, but by rebuild-style rechunking rather
than local incremental repair. So chunk drift is controlled, but still not in
its ideal final form.


### Balancing metric mismatch

The tree is weight-balanced by **node count**, not element count.

That is acceptable because chunks stay roughly bounded, but it is still an
important invariant:

- if chunk sizes become wildly uneven, node-count balancing may no longer imply
  good index performance

That is another reason chunk normalization matters.


## Testing Guidance

Current tests live in:

- [test/ordered_collections/rope_test.clj](/Users/dan/src/ordered-collections/test/ordered_collections/rope_test.clj)

They currently cover:

- basic vector-like semantics
- metadata
- split/subrope/concat
- chunk-boundary behavior
- large structural operations
- editing operations
- normalization after repeated tiny concatenations
- slice/view behavior
- chunk iteration in both directions

Next tests to add when API grows:

- repeated random edit sequences checked against ordinary vectors
- chunk-seq invariants
- preservation of equality / metadata through editing functions
- slice-as-source editing/concat cases

For randomized tests, use ordinary vectors as the reference model.


## Benchmark Guidance

The user explicitly said:

- no need for Criterium work yet

So performance work should be lightweight and diagnostic only until the API and
structure settle.

Useful ad hoc checks once work resumes:

- large `nth`
- repeated `concat-rope`
- repeated `split-rope-at`
- editing workloads using insert/remove/splice
- repeated tiny concatenation to stress normalization
- slice-heavy workflows

Only once the rope API is more complete should it graduate to:

- `simple_bench`
- or a dedicated rope benchmark namespace


## Naming Guidance

The user prefers:

- `rope`

over:

- `tree-vector`

That is the correct direction. Keep the names rope-specific from here on.


## What Not To Do

- Do not add this to `core.clj` yet.
- Do not document it publicly as a shipped collection.
- Do not compare it favorably to `PersistentVector` without strong evidence.
- Do not force it through ordered-set/map abstractions.
- Do not introduce comparator machinery into the rope.
- Do not grow a big macro layer around it unless measurement justifies it.


## Suggested Next Steps

1. Replace rebuild-style normalization with a more local normalization strategy.
2. Improve `RopeSlice` traversal so it does less materialization.
3. Add randomized edit-sequence tests against vectors.
4. Do lightweight ad hoc benchmark exploration for structural-editing
   workloads.
5. Only then revisit whether the experiment deserves broader exposure.
