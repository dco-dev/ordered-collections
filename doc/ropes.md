# Ropes

## Status

This library currently has an **experimental** rope implementation. It is not
part of the public `ordered-collections.core` API yet.

Current experimental namespaces:

- `ordered-collections.types.rope`
- `ordered-collections.tree.rope`

That experimental status matters:

- the API is still evolving
- the semantics are being shaped to fit the library cleanly
- the implementation is meant to become a good rope, not merely a slower copy
  of `PersistentVector`


## What a Rope Is

A rope is a persistent sequence representation designed to make large
concatenations, slices, and edits cheaper than repeatedly copying flat arrays
or strings.

The classic rope idea is:

- store content in **chunks**
- organize those chunks in a **tree**
- keep enough subtree metadata to support indexing and slicing efficiently
- share unchanged structure across derived values

This is a particularly natural fit when the underlying architecture already has:

- persistent balanced trees
- efficient structural sharing
- good split/join mechanics

That is exactly why ropes are interesting in this codebase.


## Why a Rope Here

This library is built around persistent, weight-balanced trees. That makes
ropes much more natural than array-backed vectors.

For a rope, the tree gives you:

- persistent concatenation
- persistent split
- subrange extraction with structure sharing
- stable asymptotic indexed navigation via subtree sizes

For ordinary vector workloads, Clojure's built-in `PersistentVector` remains the
reference implementation. The rope experiment is interesting because it may do
some **different** things well:

- repeated concatenation
- repeated splitting
- splicing persistent sequences
- editing large sequences without flattening them eagerly


## Rope vs Vector

These are not the same data structure, even if both support `nth` and `assoc`.

`PersistentVector` is optimized for:

- ordinary random access
- append-heavy use
- general-purpose vector workloads

A rope is optimized for:

- concatenation as a first-class operation
- splitting and slicing
- structural editing
- sharing large unchanged pieces

So the right question is not:

- "Can a rope beat `PersistentVector` everywhere?"

The better question is:

- "Can a rope offer a compelling structural-editing sequence type?"


## Rope Design in This Library

The experimental implementation is a **chunked implicit-index rope tree**.

Conceptually:

- each node stores a chunk vector
- node value stores total subtree element count
- left/right children are rope subtrees
- balancing uses the same weight-balanced tree ideas used elsewhere in the
  library
- element positions are derived from subtree sizes, not from user-visible keys

This means:

- there is no comparator
- there is no ordered-key API
- position is the ordering

That is an important design point. The rope is **not** an `ordered-map` from
integer index to value. It is its own collection type with its own low-level
tree layer.


## Chunked Leaves

The current rope implementation is chunked rather than storing one element per
node.

That improves locality and makes the data structure more honestly rope-like.

At a high level:

- small edits often affect only one chunk and a logarithmic path of tree nodes
- splits and concatenations work by rearranging chunk-bearing tree nodes
- indexing descends by subtree element counts, then indexes within the chunk

This is also what distinguishes the rope from the earlier discarded
"tree-vector" prototype.


## Current Experimental API

From `ordered-collections.types.rope`:

- `empty-rope`
- `rope`
- `concat-rope`
- `split-rope-at`
- `subrope`
- `insert-rope-at`
- `remove-rope-range`
- `splice-rope`
- `rope-chunks`
- `chunk-count`

And the `Rope` type itself currently supports:

- `count`
- `nth`
- `get`
- vector-style function lookup
- `assoc`
- `conj`
- `peek`
- `pop`
- `seq`
- `rseq`
- `reduce`
- metadata
- sequential equality


## Examples

Because the rope is experimental, examples use the type namespace directly:

```clojure
(require '[ordered-collections.types.rope :as rope])
```

### Construction

```clojure
(def r (rope/rope [0 1 2 3 4 5]))

(count r)
;; => 6

(nth r 3)
;; => 3

(vec r)
;; => [0 1 2 3 4 5]
```

### Concatenation

```clojure
(def a (rope/rope [0 1 2]))
(def b (rope/rope [3 4 5]))

(vec (rope/concat-rope a b))
;; => [0 1 2 3 4 5]
```

### Split and Slice

```clojure
(let [[l r] (rope/split-rope-at (rope/rope (range 10)) 4)]
  [(vec l) (vec r)])
;; => [[0 1 2 3] [4 5 6 7 8 9]]

(vec (rope/subrope (rope/rope (range 10)) 3 7))
;; => [3 4 5 6]
```

### Insertion

```clojure
(vec (rope/insert-rope-at (rope/rope (range 6)) 2 [:a :b]))
;; => [0 1 :a :b 2 3 4 5]
```

### Range Removal

```clojure
(vec (rope/remove-rope-range (rope/rope (range 10)) 4 7))
;; => [0 1 2 3 7 8 9]
```

### Splicing

```clojure
(vec (rope/splice-rope (rope/rope (range 10)) 2 5 [:x :y]))
;; => [0 1 :x :y 5 6 7 8 9]
```

### Chunk Introspection

```clojure
(def r (rope/rope (range 130)))

(rope/chunk-count r)
;; => 3

(mapv count (rope/rope-chunks r))
;; => [64 64 2]
```

This chunk view is useful because it exposes the real structure of the rope.
Unlike a flat vector, a rope has meaningful chunk boundaries.


## Conceptual Tradeoffs

### Strengths

- persistent concatenation is natural
- persistent split is natural
- subrange extraction is natural
- structure sharing is central rather than incidental
- chunked representation offers better locality than one-element tree nodes

### Weaknesses

- chunk normalization is still primitive
- there is no slice/view type yet
- the API is still narrower than mature rope libraries
- there is no claim yet that this is broadly competitive with
  `PersistentVector`


## How This Relates to Other Rope Designs

The current design is closest to a general persistent sequence rope, not a
text-only rope.

Some rope libraries are specialized for text and therefore also expose:

- byte indexing
- character indexing
- line indexing
- UTF-8 chunk navigation
- incremental builders
- slice/view objects

Our current experiment is intentionally more general:

- chunks hold arbitrary Clojure values
- indexing is element-based
- the API is sequence-oriented rather than text-oriented


## Papers

### Boehm, Atkinson, and Plass (1995)

The canonical rope paper is:

- Hans-J. Boehm, Russ Atkinson, and Michael F. Plass.
  "Ropes: an Alternative to Strings."
  *Software: Practice and Experience* 25(12), 1995.

Repository-local materials:

- [Citation note](papers/boehm-atkinson-plass-1995-ropes-citation.md)
- [SRI citation/abstract page](papers/boehm-atkinson-plass-1995-ropes-sri.html)

Public citation page:

- https://www.sri.com/publication/ropes-an-alternative-to-strings/

This remains the most important reference for the basic rope idea:

- concatenate by structure
- slice without flattening everything
- share large unchanged subtrees


## Implementation References

### Adobe / SGI rope implementation overview

This is a valuable implementation note, especially for understanding classic
rope engineering tradeoffs:

- substring nodes
- function/lazy nodes
- balancing policy
- iterator caching
- reference-counting vs GC tradeoffs

Reference:

- https://stlab.adobe.com/stldoc_ropeimpl.html


## Prominent Open Source Rope Implementations

### Ropey

Rust text rope with a mature, well-known API and strong documentation:

- docs: https://docs.rs/ropey/latest/ropey/
- repo: https://github.com/cessen/ropey

Useful reference points:

- dedicated rope builder
- slicing model
- chunk APIs
- text-specific indexing support


### crop

Another Rust rope implementation, B-tree based and focused on text-editing
workloads:

- docs: https://docs.rs/crop/latest/crop/
- repo: https://github.com/nomad/crop

Useful reference points:

- `Rope`, `RopeSlice`, and `RopeBuilder`
- explicit editing operations
- text-oriented slice semantics


### xi rope

The rope implementation from the Xi editor ecosystem is another important
practical reference for persistent editing structures:

- repo: https://github.com/xi-editor/xi-editor/tree/master/rust/rope

This is particularly relevant as a design reference for editor-oriented
structural editing.


### Sycamore (Common Lisp)

A Common Lisp data-structure library that includes ropes alongside
weight-balanced trees:

- repo: https://github.com/ndantam/sycamore
- reference manual: https://quickref.common-lisp.net/sycamore.html

This is especially relevant for this project because it shows a rope living in
a Lisp ecosystem next to weight-balanced tree structures.


## Where This Could Go

If the experimental rope continues to develop, the next natural steps are:

- improve chunk normalization
- add richer chunk iteration
- consider a slice/view type
- decide whether the rope deserves promotion into the public library API

Until then, it should be treated as:

- promising
- structurally interesting
- still experimental
