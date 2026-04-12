# Ropes

## Status

The library provides three public rope variants in
`ordered-collections.core`, all backed by the same weight-balanced tree
kernel:

- **`rope`** — generic persistent sequence for arbitrary Clojure values,
  backed by `PersistentVector` chunks.
- **`string-rope`** — specialized text rope backed by `java.lang.String`
  chunks. Implements `CharSequence` for Java interop.
- **`byte-rope`** — specialized binary rope backed by `byte[]` chunks.
  Unsigned 0–255 element semantics.

```clojure
(require '[ordered-collections.core :as oc])

(oc/rope [1 2 3 4 5])                   ; generic
(oc/string-rope "the quick brown fox")  ; text
(oc/byte-rope (byte-array [1 2 3]))     ; binary
```

Implementation namespaces:

- `ordered-collections.kernel.rope` — shared chunked-tree operations
  (concat, split, splice, reduce, fold). Dispatches to chunk primitives
  via the `PRopeChunk` protocol.
- `ordered-collections.kernel.chunk` — `PRopeChunk` extensions for the
  three chunk backends (`APersistentVector`, `String`, `byte[]`).
- `ordered-collections.types.rope` — generic `Rope` deftype.
- `ordered-collections.types.string-rope` — `StringRope` deftype.
- `ordered-collections.types.byte-rope` — `ByteRope` deftype.


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

### Benchmark Summary

| Workload | N=10K | N=100K | N=500K |
|---|---:|---:|---:|
| 200 random edits | **43x** | **498x** | **1968x** |
| Single splice | **6x** | **116x** | **584x** |
| Concat many pieces | **3.4x** | **5.4x** | **9.5x** |
| Chunk iteration | **58x** | **83x** | **117x** |
| Fold (sum) | **5.6x** | **1.5x** | **1.3x** |
| Reduce (sum) | 0.4x | **1.7x** | **1.3x** |
| Random nth (1000) | 0.7x | 0.5x | 0.4x |

The rope wins on 6 of 7 workloads at scale. The advantage grows with collection
size because structural editing is O(log n) vs O(n). Parallel fold beats vectors
via tree-based fork-join decomposition. Random nth is slower (O(log n) vs O(1))
— an inherent tradeoff of tree-backed indexing.

> These numbers are all for tree-mode ropes (above the 1024-element
> flat threshold). Below that threshold the rope stores its content as
> a bare `PersistentVector` directly and every read dispatches to
> vector operations with zero indirection — read performance is
> essentially identical to a raw vector. See **Flat Mode: Zero-Overhead
> Small Ropes** below.


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

![Chunked rope tree structure](assets/rope-structure.svg)

The key visual idea is that indexing is a two-stage operation:

- walk the tree by subtree sizes
- then index within one chunk

That is why the rope can support `nth`, `assoc`, split, and slicing without
pretending that element positions are stored as explicit keys.


## The Chunk Abstraction: One Kernel, Many Backends

The rope kernel is written once and works over any chunk type that
satisfies the `PRopeChunk` protocol. This is what lets the same tree
algebra back the generic `Rope`, the `StringRope`, and the `ByteRope`
without the kernel needing to know which backend it is operating on.

`PRopeChunk` is a small internal protocol (13 methods) that captures
every primitive operation the kernel needs on a chunk:

```
chunk-length       — element count
chunk-nth          — element at index
chunk-slice        — subrange [start, end)
chunk-merge        — concatenate two chunks
chunk-append       — append a single element
chunk-last         — last element
chunk-butlast      — all but last element
chunk-update       — replace element at index
chunk-of           — build a single-element chunk
chunk-reduce-init  — reduce over the chunk with an initial value
chunk-append-sb    — append to a StringBuilder (for materialization)
chunk-splice       — replace a range inside the chunk
chunk-splice-split — same but returning a split pair (overflow path)
```

The extensions live in `ordered-collections.kernel.chunk`:

| Backend                       | Variant       | Primary operations              |
|-------------------------------|---------------|---------------------------------|
| `clojure.lang.APersistentVector` | `rope`        | `subvec`, `into`, `.nth`, `conj` |
| `java.lang.String`            | `string-rope` | `.substring`, `.charAt`, `StringBuilder` |
| `byte[]`                      | `byte-rope`   | `Arrays/copyOfRange`, `System/arraycopy`, `aget`/`aset` |

Each backend is self-contained — it only touches the underlying JVM
type and has no dependency on the rest of the rope kernel. Structural
algorithms (`rope-concat`, `rope-split-at`, `rope-splice-root`,
`rope-reduce`, `rope-fold`, CSI repair) are written exactly once and
dispatch through the protocol.

This is internal dispatch, not user-facing interop. User code never
calls `chunk-length` or `chunk-slice` directly — those are the kernel's
own adapter layer for "here's how to manipulate a chunk of type X". If
you want to add a new rope variant (say, a `LongRope` over `long[]`),
the recipe is:

1. Extend `PRopeChunk` to the new chunk type in `kernel/chunk.clj`.
2. Add a `<variant>-rope-node-create` allocator in `kernel/rope.clj`.
3. Add a `<variant>->root` chunking builder.
4. Create a `types/<variant>_rope.clj` deftype that wraps the tree root
   and binds `*t-join*` to the new allocator in each mutating operation.
5. Expose the public API in `core.clj`.

The shared-kernel approach means every optimization the kernel gains —
`rope-splice-inplace`, monomorphic hot paths, parallel fold, transient
construction — is inherited by all variants at once.


## Chunked Leaves

The current rope implementation is chunked rather than storing one element per
node.

That improves locality and makes the data structure more honestly rope-like.

At a high level:

- small edits often affect only one chunk and a logarithmic path of tree nodes
- splits and concatenations work by rearranging chunk-bearing tree nodes
- indexing descends by subtree element counts, then indexes within the chunk

![Chunk size invariant and boundary repair](assets/rope-csi-boundary.svg)

The subtle rule is the **Chunk Size Invariant (CSI)**:

- every internal chunk must be in the valid size range
- only the rightmost chunk, called the **runt**, is allowed to be undersized

That right-edge exception keeps append efficient, but it also means structural
operations must sometimes repair chunk boundaries. In particular, concat, split,
slice, and splice can move a former right-edge runt into the interior, where it
must be merged and rechunked back into a valid shape.


## Flat Mode: Zero-Overhead Small Ropes

All three rope variants apply the same **flat-mode optimization**: when a
rope's element count is at or below the per-variant flat threshold (1024
by default), the rope skips the tree wrapper entirely and stores its
content as a bare concrete collection in its `root` field — a
`PersistentVector` for the generic rope, a `java.lang.String` for the
string rope, a `byte[]` for the byte rope.

This is just good engineering rather than clever algorithms: a
1024-element rope does not need an outer tree node, an augmented
subtree element count, and a chunk-protocol dispatch layer just to
read one element. Below the threshold every operation dispatches
straight to the native type:

| Variant      | Below threshold         | Above threshold             |
|---           |---                      |---                          |
| `rope`       | `PersistentVector`      | chunked WBT of vectors      |
| `string-rope`| `java.lang.String`      | chunked WBT of strings      |
| `byte-rope`  | `byte[]`                | chunked WBT of byte arrays  |

Concretely:

- **Reads** (`nth`, `seq`, `reduce`, `charAt`, indexed access) go
  directly to the underlying type. No tree descent, no chunk protocol
  call, no per-op indirection.
- **Structural edits** (`rope-insert`, `rope-splice`, `rope-cat`, etc.)
  use the native type's own efficient operations (`subvec` + `into` for
  vectors, `StringBuilder` for strings, `System.arraycopy` for byte
  arrays). If the result would exceed the threshold, the rope
  transparently **promotes** to the chunked tree form.
- **Transients** always build in tree form internally, but at
  `persistent!` time the final result is **demoted** back to flat form
  if it fits under the threshold.
- **Memory overhead** for a small rope is essentially identical to the
  underlying type — the flat-mode rope is just the bare collection
  plus the deftype field headers (alloc, meta).
  Measured with `clj-memory-meter`, a 1024-element rope is within 0.1
  bytes/element of a raw `PersistentVector`.

The flat threshold for each variant is tuned independently and can
diverge as each variant's performance profile demands, but currently
all three use 1024 — that happens to coincide with the
`+target-chunk-size+`, so "small enough to live in a single chunk"
and "small enough to stay flat" are the same regime.

The asymmetry worth noting: on the generic rope, flat-mode reads are
*not* O(1) — `PersistentVector.nth` is O(log₃₂ n), a trie-level
lookup. They are, however, as fast as reads get on the JVM for
arbitrary Clojure values, and they skip the outer rope-tree
indirection entirely. On the specialized variants, flat-mode
`charAt` and unsigned-byte indexing are genuinely O(1) because
`String` and `byte[]` are contiguous.


## API

### Shared rope API (works on all three variants)

The `PRope` protocol is implemented by `Rope`, `StringRope`, and
`ByteRope`. Every function below dispatches on the protocol, so the
same call works regardless of which rope variant you pass in:

- `rope-split` — split at index, returns `[left right]`
- `rope-sub` — extract subrange `[start, end)`
- `rope-insert` — insert content at index
- `rope-remove` — remove range
- `rope-splice` — replace range with new content
- `rope-chunks` — seq of internal chunk values
- `rope-str` — materialize to the natural backing type (String / byte[] / etc.)

### Generic Rope

- `rope` — constructor (any seqable)
- `rope-concat` — 1-arg coerce, 2-arg O(log n) join, 3+-arg bulk
- `rope-chunks-reverse` — reverse chunk seq
- `rope-chunk-count` — number of chunks

The `Rope` type supports:

- `count`, `nth`, `get`, `assoc`, `conj`, `peek`, `pop`
- vector-style function lookup
- `seq`, `rseq`
- `reduce` (with correct early termination via `reduced`)
- `clojure.core.reducers/fold` (parallel fork-join)
- `compare` (lexicographic)
- `java.util.List`: `get`, `indexOf`, `lastIndexOf`, `contains`, `subList`
- `java.util.Collection`: `size`, `isEmpty`, `toArray`, `containsAll`
- `IPersistentVector` — `(vector? r)` is true
- metadata, sequential equality, ordered hashing

### StringRope

- `string-rope` — constructor (from `String`, another StringRope, or anything `str` can coerce)
- `string-rope-concat` — variadic, same 1/2/3+ semantics as `rope-concat`

The `StringRope` type implements `java.lang.CharSequence` and most of
the same Clojure interfaces as the generic Rope, with text-appropriate
semantics:

- `nth` / `charAt` return a `Character`
- `(str sr)` materializes to a `java.lang.String`
- Equality with `java.lang.String` is content-based
- Hash matches `String`'s so ropes and strings can co-exist as map keys
- `Comparable` — lexicographic compare matches `String.compareTo`
- `IEditableCollection` — `TransientStringRope` with a `StringBuilder` tail
- Works with `re-find` / `re-seq` / `re-matches` / `java.util.regex.Matcher`
- Works with `clojure.string` (all of its functions accept `CharSequence`)
- Works with `java.io.*` APIs that accept `CharSequence`

### ByteRope

ByteRope is essentially a **persistent, structure-sharing memory** — a
model of a contiguous byte region that supports O(log n) structural
editing (splice, insert, remove), zero-cost immutable snapshots (every
version persists via path-copying), automatic coalescing of adjacent
chunks (the CSI invariant merges undersized neighbors), and garbage
collection of unreachable versions by the JVM. You get the mental model
of a mutable byte buffer with the safety properties of a persistent data
structure.

This makes ByteRope useful far beyond simple binary blobs:
- **Binary protocol construction** — build packets by splicing fields
  at offsets, roll back on error by keeping the prior version
- **Undo/redo** — each edit produces a new ByteRope; old versions are
  retained as long as referenced, discarded by GC when not
- **Diffing and patching** — apply a patch to a snapshot without
  copying the unmodified regions (structure sharing)
- **Streaming** — `byte-rope-input-stream` and `byte-rope-write` let
  you feed chunks through Java I/O without materializing the full
  contents

API surface:

- `byte-rope` — constructor (from `byte[]`, another ByteRope, `String` (UTF-8), `InputStream`, seq of unsigned longs)
- `byte-rope-concat` — variadic, same 1/2/3+ semantics
- `byte-rope-bytes` — defensively-copied `byte[]` materialization
- `byte-rope-hex` — lowercase hex string
- `byte-rope-write` — stream chunks to an `OutputStream`
- `byte-rope-input-stream` — adapter returning a fresh `java.io.InputStream`
- `byte-rope-get-byte` / `-short` / `-int` / `-long` — big-endian multi-byte reads
- `byte-rope-get-short-le` / `-int-le` / `-long-le` — little-endian variants
- `byte-rope-index-of` — first index of a given unsigned byte value
- `byte-rope-digest` — streaming `java.security.MessageDigest`; returns a ByteRope

The `ByteRope` type exposes bytes as unsigned longs in `[0, 255]`:

- `nth` / `reduce` / `seq` yield longs in that range
- Equality with `byte[]` is content-based
- Not equal to Clojure vectors (intentional — avoids signed/unsigned confusion)
- `Comparable` — unsigned lexicographic via `Arrays/compareUnsigned`
- `IEditableCollection` — `TransientByteRope` with a `ByteArrayOutputStream` tail
- Does **not** implement `CharSequence` or `IPersistentVector` — bytes are their own domain


## Examples

```clojure
(require '[ordered-collections.core :as oc])
```

### Construction

```clojure
(def r (oc/rope [0 1 2 3 4 5]))

(count r)
;; => 6

(nth r 3)
;; => 3

(vec r)
;; => [0 1 2 3 4 5]
```

### Concatenation

```clojure
(def a (oc/rope [0 1 2]))
(def b (oc/rope [3 4 5]))

(oc/rope-concat a b)  ;; => #vec/rope [0 1 2 3 4 5]

;; Variadic — bulk concatenation in O(total chunks)

(oc/rope-concat (oc/rope [1 2]) (oc/rope [3 4]) (oc/rope [5 6]))
;; => #vec/rope [1 2 3 4 5 6]
```

### Split and Slice

```clojure
(let [[l r] (oc/rope-split (oc/rope (range 10)) 4)]
  [(vec l) (vec r)])
;; => [[0 1 2 3] [4 5 6 7 8 9]]

(vec (oc/rope-sub (oc/rope (range 10)) 3 7))
;; => [3 4 5 6]
```

### Insertion

```clojure
(vec (oc/rope-insert (oc/rope (range 6)) 2 [:a :b]))
;; => [0 1 :a :b 2 3 4 5]
```

### Range Removal

```clojure
(vec (oc/rope-remove (oc/rope (range 10)) 4 7))
;; => [0 1 2 3 7 8 9]
```

### Splicing

```clojure
(vec (oc/rope-splice (oc/rope (range 10)) 2 5 [:x :y]))
;; => [0 1 :x :y 5 6 7 8 9]
```

### Chunk Introspection

```clojure
(def r (oc/rope (range 130)))

(oc/rope-chunk-count r)
;; => 3

(mapv count (oc/rope-chunks r))
;; => [64 64 2]
```

This chunk view is useful because it exposes the real structure of the rope.
Unlike a flat vector, a rope has meaningful chunk boundaries.


## Tutorial: When and How to Use a Rope

Most people encounter ropes in the context of text editors. That is a valid use
case, but it is a narrow lens. A rope is a general-purpose persistent sequence
type that excels at **structural editing** — any workload where you repeatedly
split, concatenate, splice, or slice large sequences without wanting to copy the
whole thing every time.

This tutorial walks through several concrete scenarios where a rope is a better
fit than a vector.


### Setup

```clojure
(require '[ordered-collections.core :as oc])
```


### Scenario 1: Assembling a Large Sequence from Parts

Suppose you are collecting data from many sources and need to merge the results
into one ordered sequence. With vectors, each concatenation copies both sides:

```clojure
;; Expensive with vectors — every `into` copies everything accumulated so far
(reduce into [] [chunk-a chunk-b chunk-c chunk-d ...])
```

With a rope, concatenation is structural. No bulk copying happens — the rope
just records that the pieces sit next to each other in a tree:

```clojure
(def parts [(oc/rope sensor-readings-batch-1)
            (oc/rope sensor-readings-batch-2)
            (oc/rope sensor-readings-batch-3)])

(def combined (apply oc/rope-concat parts))

;; The full sequence is available for indexed access or reduce,
;; but no flattening happened:
(count combined)
(nth combined 12345)
(reduce + combined)
```

This matters when the parts are large and numerous. The rope grows by adding
tree structure, not by copying elements.


### Scenario 2: Splitting and Rearranging

A common pattern in data pipelines: take a large sequence, split it at a
position, and rearrange or process the halves independently.

```clojure
(def events (oc/rope (range 100000)))

;; Split at position 40000
(let [[head tail] (oc/rope-split events 40000)]
  ;; head and tail share structure with the original
  (count head)  ;; => 40000
  (count tail)  ;; => 60000

  ;; Process tail, then put it back in front of head
  (def reordered (oc/rope-concat tail head)))
```

Both `rope-split` and `rope-concat` are O(log n). The original `events` rope
is unchanged — this is persistent. You now have three independent snapshots of
the sequence (original, head, tail) without tripling memory usage.

![Split once, share both halves, concat structurally](assets/rope-split-concat.svg)

The important point is that split does not flatten the rope into two vectors.
It cuts one path through the tree, reuses the untouched subtrees on each side,
and then restores chunk validity at the new fringes.


### Scenario 3: Splicing Into the Middle

Inserting elements into the middle of a vector is O(n) — every element after the
insertion point must be shifted. With a rope, it is O(log n):

```clojure
(def timeline (oc/rope (range 1000)))

;; Insert a burst of priority events at position 200
(def updated (oc/rope-insert timeline 200 [:alert-1 :alert-2 :alert-3]))

(nth updated 200)  ;; => :alert-1
(nth updated 203)  ;; => 200  (original element, shifted right)
```

This is useful for any ordered stream where you need to retroactively inject
data at a specific position.

Remove a range just as easily:

```clojure
;; Remove positions 400 through 500
(def trimmed (oc/rope-remove timeline 400 500))

(count trimmed)  ;; => 900
```

Or replace a range:

```clojure
;; Replace positions 100–110 with new data
(def patched (oc/rope-splice timeline 100 110 [:patched]))

(count patched)  ;; => 991  (removed 10, inserted 1)
```

![Splice replaces one middle range and shares the rest](assets/rope-splice-sharing.svg)

That picture is the essence of why ropes are attractive for persistent editing:
the edit rebuilds the path near the change, but large left and right regions
remain shared with the previous version.


### Scenario 4: Windowing and Slicing

Extract a subrange without copying the entire sequence:

```clojure
(def measurements (oc/rope (range 1000000)))

;; Extract a window — this shares structure with the original
(def window (oc/rope-sub measurements 499000 501000))

(count window)  ;; => 2000
(nth window 0)  ;; => 499000
```

The resulting rope shares structure with the original and supports all rope
operations including `assoc`, `conj`, `peek`, `pop`, and further slicing.


### Scenario 5: Version History / Undo

Because ropes are persistent, every edit produces a new rope that shares
structure with the previous version. This makes version history cheap:

```clojure
(def v0 (oc/rope [:a :b :c :d :e]))
(def v1 (oc/rope-insert v0 2 [:x :y]))
(def v2 (oc/rope-remove v1 0 2))
(def v3 (oc/rope-splice v2 1 3 [:z]))

;; All four versions coexist, sharing most of their structure:
(into [] v0)  ;; => [:a :b :c :d :e]
(into [] v1)  ;; => [:a :b :x :y :c :d :e]
(into [] v2)  ;; => [:x :y :c :d :e]
(into [] v3)  ;; => [:x :z :d :e]
```

The memory cost of keeping all four versions is much less than four independent
copies. This pattern applies to any application that needs to track or undo
edits to an ordered collection: document editing, configuration changes,
simulation state, game replay.


### Scenario 6: Parallel Reduction

Ropes support `clojure.core.reducers/fold` for parallel reduction via
fork-join:

```clojure
(require '[clojure.core.reducers :as r])

(def large (oc/rope (range 1000000)))

;; Sequential reduce
(reduce + large)

;; Parallel fold — splits the rope at its natural tree structure
(r/fold + large)
```

The rope's internal tree structure provides natural split points for parallel
work distribution. This is useful for compute-heavy aggregation over large
sequences.


### Scenario 7: Chunk-Aware Processing

Unlike a flat vector, a rope has visible internal structure. You can iterate
over the chunks directly, which is useful when the data was assembled from
meaningful pieces:

```clojure
(def assembled (apply oc/rope-concat
                 (map oc/rope [[1 2 3] [4 5 6] [7 8 9]])))

;; Iterate over the internal chunks
(doseq [chunk (oc/rope-chunks assembled)]
  (println "chunk:" chunk "sum:" (reduce + chunk)))
;; chunk: [1 2 3 4 5 6 7 8 9] sum: 45
```

Note that the rope may merge small chunks for efficiency. The chunk boundaries
reflect the rope's internal balancing, not necessarily the original assembly
boundaries. But for large pieces, the original structure is typically preserved.


### Scenario 8: Converting Between Ropes, Strings, and Vectors

Ropes interoperate naturally with other Clojure and Java sequence types.

**Building ropes from anything seqable:**

```clojure
(oc/rope [1 2 3])           ;; from a vector
(oc/rope (range 1000))      ;; from a range
(oc/rope '(:a :b :c))       ;; from a list
(oc/rope "hello")           ;; from a string (rope of Characters)
```

**Rope to String** — `rope-str` uses a chunk-aware StringBuilder, much faster
than `(apply str r)` for large ropes:

```clojure
(def doc (oc/rope "The quick brown fox"))

(oc/rope-str doc)
;; => "The quick brown fox"

;; After editing:
(oc/rope-str (oc/rope-splice doc 4 9 "slow"))
;; => "The slow brown fox"
```

**Rope to PersistentVector** — use `vec`:

```clojure
(type (vec (oc/rope [1 2 3])))
;; => clojure.lang.PersistentVector
```

Note that `(vector? (oc/rope [1 2 3]))` returns `false` — ropes are sequential
but not vectors.

**Rope operations accept non-rope collections** — concat, insert, and splice
coerce their arguments automatically:

```clojure
(oc/rope-concat (oc/rope [1 2]) [3 4])
;; => #vec/rope [1 2 3 4]

(oc/rope-insert (oc/rope [1 2 3]) 1 [:a :b])
;; => #vec/rope [1 :a :b 2 3]
```

**Interop summary:**

| From | To | How |
|---|---|---|
| Any seqable | Rope | `(oc/rope coll)` |
| String | Rope of chars | `(oc/rope "hello")` |
| Rope | String | `(oc/rope-str r)` |
| Rope | PersistentVector | `(vec r)` |
| Rope | lazy seq | `(seq r)` |
| Rope | Java array | `(.toArray r)` |
| Rope | EDN round-trip | `#vec/rope` tagged literal |


### Scenario 9: Java Interop

The rope implements `java.util.List` and `java.util.Collection`, so it works
with Java APIs that expect these interfaces:

```clojure
(def r (oc/rope [10 20 30 40 50]))

(.get r 2)           ;; => 30
(.size r)            ;; => 5
(.contains r 30)     ;; => true
(.indexOf r 40)      ;; => 3
(.subList r 1 4)     ;; => #vec/rope [20 30 40]
(.toArray r)         ;; => Object[5] {10, 20, 30, 40, 50}
```


### When to Use a Rope vs. a Vector

Use a **vector** when:

- you mostly append to the end and read by index
- random access performance matters more than edit performance
- the sequence is small or medium-sized
- you never split, splice, or concatenate in the middle

Use a **rope** when:

- you frequently concatenate large sequences
- you frequently split or slice sequences
- you need to splice into the middle of large sequences
- you want cheap persistent snapshots after structural edits
- you want to reduce over very large sequences in parallel
- you assemble a sequence from many parts and then process it


## Specialized Ropes

The generic `Rope` stores any Clojure value in `PersistentVector`
chunks. That is the right choice for heterogeneous sequential data
but pays for boxing and per-element object headers on workloads where
the element type is uniform. The library provides two specialized
variants backed by native JVM types:

### StringRope — text

`StringRope` backs each chunk with a `java.lang.String`. The JEP 254
compact-string optimization means a 256-character ASCII chunk occupies
about the same space as a 256-byte `byte[]`, plus object headers.
`StringRope` implements `java.lang.CharSequence`, so every Java and
Clojure text API accepts it directly:

```clojure
(require '[clojure.string :as str])

(def doc (oc/string-rope "The quick brown fox jumps over the lazy dog."))

(count doc)                           ;; => 44
(str doc)                             ;; => "The quick brown fox jumps over the lazy dog."
(subs doc 4 9)                        ;; NOT supported — use rope-sub

(str (oc/rope-sub doc 4 9))           ;; => "quick"
(str (oc/rope-splice doc 4 9 "slow")) ;; => "The slow brown fox jumps over the lazy dog."

;; Regex and clojure.string work directly because doc implements CharSequence
(re-seq #"\w+" doc)
(str/upper-case (str doc))
(str/replace doc #"\w+" clojure.string/upper-case)
```

Key properties:

- `(= (string-rope "hello") "hello")` is true
- `(hash (string-rope "hello"))` matches `(hash "hello")`
- `#string/rope "…"` tagged literal round-trips through EDN
- Transient support via `StringBuilder` tail buffer for fast batch construction
- Small strings (≤ 1024 chars) are stored as a raw `String` internally
  with zero tree overhead; edits that grow past the threshold transparently
  promote to chunked form

Performance vs `java.lang.String` (structural editing workloads):

| Workload | N=1K | N=10K | N=100K |
|---|---:|---:|---:|
| 200 random edits | **3x** | **14x** | **35x** |
| Single splice | 2x | **18x** | **153x** |
| Random nth | 0.3x | 0.2x | 0.1x |

Random-access reads are slower than `String` (O(log n) vs O(1)) but
bounded; structural edits scale indefinitely while `String` edits are
always O(n).

### ByteRope — binary data

`ByteRope` backs each chunk with a primitive `byte[]`. This matches
the mutable-world defaults of `java.nio.ByteBuffer`, protobuf
`ByteString`, Okio, and Netty — but with persistent semantics and
O(log n) structural edits.

```clojure
(def msg (oc/byte-rope (.getBytes "Hello, World!" "UTF-8")))

(count msg)                           ;; => 13
(oc/byte-rope-hex msg)                ;; => "48656c6c6f2c20576f726c6421"
(nth msg 0)                           ;; => 72        (unsigned 'H')
(oc/byte-rope-get-int msg 0)          ;; => 1214606444 (big-endian u32)

(def with-prefix
  (oc/byte-rope-concat
    (oc/byte-rope (byte-array [0xde 0xad 0xbe 0xef]))
    msg))

(oc/byte-rope-hex with-prefix)
;; => "deadbeef48656c6c6f2c20576f726c6421"

;; Cryptographic digest streams chunks through MessageDigest
(oc/byte-rope-hex (oc/byte-rope-digest msg "SHA-256"))
;; => "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"
```

Key properties:

- Unsigned byte semantics — `nth` / `reduce` / `seq` yield longs in `[0, 255]`
- Equality with `byte[]` is content-based; intentionally not equal to Clojure vectors
- Unsigned lexicographic `Comparable` (consistent with protobuf/Okio/Netty)
- Big-endian multi-byte reads with `-le` variants for little-endian
- `#byte/rope "hex"` tagged literal round-trips through EDN
- Defensive copy on construction — never shares mutable `byte[]` with the caller
- Transient support via `ByteArrayOutputStream` tail buffer
- Small byte sequences (≤ 1024 bytes) are stored as a raw `byte[]` with zero
  tree overhead; edits that grow past the threshold promote transparently
- Streaming primitives: `byte-rope-write` for `OutputStream`,
  `byte-rope-input-stream` for `InputStream`, `byte-rope-digest` for
  `MessageDigest` — no full materialization needed

Performance vs `byte[]` (structural editing workloads):

| Workload | N=1K | N=10K | N=100K |
|---|---:|---:|---:|
| 200 random edits | 0.25x | 0.4x | **3.3x** |
| Single splice at midpoint | 0.3x | 0.2x | ~1x |
| Random nth | 1x | 0.5x | 0.3x |

Small-scale byte-array operations win because `System/arraycopy` is
absurdly fast. The rope takes over at the scale where persistent edits
matter — roughly 100K+ bytes with repeated splicing. For single reads
or small buffers, stay with `byte[]`. For binary-protocol assembly,
streaming digests, or any workload with many edits on a large buffer,
use `ByteRope`.

### Choosing a variant

| You want... | Use |
|---|---|
| Text editing, regex, clojure.string, Java interop | `string-rope` |
| Binary protocol assembly, streaming digest, patch editing | `byte-rope` |
| Anything else sequential (vectors of arbitrary values) | `rope` |

All three share the same public API through the `PRope` protocol —
`rope-split`, `rope-sub`, `rope-insert`, `rope-remove`, `rope-splice`,
`rope-chunks`, `rope-str` — so code that works on one variant often
works on the others with minimal changes.


## Conceptual Tradeoffs

### Strengths

- persistent concatenation is natural
- persistent split is natural
- subrange extraction is natural
- structure sharing is central rather than incidental
- chunked representation offers better locality than one-element tree nodes

### Weaknesses

- random `nth` access is O(log n) rather than O(1) — inherent tree tradeoff
- split and slice are O(log n) vs O(1) for `subvec` — inherent
- reduce is slower than vectors at small N (< 10K elements)
- element-by-element construction via `conj` is slower than vectors; prefer
  `(oc/rope coll)` for bulk construction


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

- `Rope` and `RopeBuilder`
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


## Current Capabilities

The rope now provides:

- full Clojure collection interfaces: `Indexed`, `Associative`,
  `IPersistentStack`, `Seqable`, `Reversible`,
  `IReduceInit`, `IReduce`, `Counted`, `IHashEq`, `IMeta`, `IObj`,
  `Sequential`, `Comparable`
- `java.util.List` and `java.util.Collection` for Java interop
- `clojure.core.reducers/CollFold` for parallel fold
- chunk-level iteration in both directions
- structural editing: `rope-insert`, `rope-remove`, `rope-splice`
- correct `reduced` early-termination in all reduce paths
- `print-method` for readable REPL output

## Future Work

- Further constant-factor optimization of reduce at small N (0.6x at 10K;
  already 1.4x faster than vectors at N ≥ 100K)
