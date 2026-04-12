# Benchmarks

## Current Numbers

Run `lein bench-report` to generate the full performance report from the
latest benchmark data. The output is also committed as
[report.txt](report.txt) for quick reference.

The report includes headline speedup tables for every collection type,
per-category geomean aggregation, rope family cross-variant comparison,
significant wins/losses, full scorecard, and regressions/improvements
vs the prior run.


## Infrastructure

### Design

Performance work on this project is driven by a structured, versioned
artifact pipeline rather than ad-hoc timing. Every `lein bench` run
produces a self-describing EDN file that records what was measured, on
what hardware, at what commit, with full Criterium statistics per cell.
These artifacts accumulate in `bench-results/` and form a database of
performance history going back to 0.2.0, enabling detailed A/B
comparison against any branch or point in time.

The pipeline has three stages:

1. **Capture** (`bench_runner.clj`) — runs Criterium benchmarks across
   all collection types and cardinalities, normalizes the raw Criterium
   output, and writes a timestamped EDN artifact.
2. **Analyze** (`bench_analyze.clj`) — flattens and classifies the
   artifact, computes the ordered scorecard (OC variant vs best peer),
   detects regressions/improvements against a baseline, and aggregates
   by category.
3. **Render** (`bench_render.clj` + `bench_report.bb`) — produces the
   formatted terminal report with scaling tables, ranked sections, and
   delta annotations.

This separation means the analysis logic is reusable — the same
scorecard and regression functions work whether you're comparing two
runs on the same branch, an optimization branch against master, or
the latest nightly against a release tag.

### The EDN Artifact

Each benchmark run writes a single file to `bench-results/` with the
naming convention `YYYY-MM-DD_HH-mm-ss.edn`. The artifact contains:

- **`:artifact-version`** — schema version (currently 3). Incremented
  when the field structure changes so downstream tools can detect
  incompatibility.
- **`:system`** — full environment snapshot: git rev, branch, dirty
  flag, `git describe`, Java version/vendor/VM, OS, processor count,
  heap sizes, JVM args, Clojure version, Leiningen version, hostname,
  timezone.
- **`:sizes`** — the cardinalities measured (e.g., `[1000 5000 10000
  100000 500000]`).
- **`:benchmarks`** — nested map of `{size → {group → {variant →
  stats}}}`. Each leaf holds the full Criterium result: mean, standard
  deviation, confidence intervals, sample count, execution count,
  outlier classification.
- **`:started-at`**, **`:timestamp`**, **`:duration-ms`** — wall-clock
  bookkeeping.
- **`:mode`**, **`:opts`**, **`:argv`** — reproducibility metadata.

The system snapshot makes it possible to attribute performance changes
to hardware/JVM differences vs code changes. The artifact version
ensures tools fail cleanly rather than silently misinterpreting a
changed schema.

### A/B Comparison

Both `lein bench` and `lein bench-report` automatically compare against
the prior run:

1. **File discovery** — scan `bench-results/` for timestamped EDN
   files, sort lexically (which is chronological), and select the
   most recent file before the current one.
2. **Cell matching** — flatten both artifacts into rows keyed by
   `[size, group, variant]`. Match by composite key.
3. **Delta computation** — for each matched cell, compute
   `new-ns / old-ns` and classify:
   - **Major regression**: >25% slower
   - **Regression**: >10% slower
   - **Improvement**: >10% faster
   - **Major improvement**: >25% faster
   - **Unchanged**: within 10%

This runs automatically at the end of every `lein bench` invocation
(self-contained, no dependency on the bb report tool) and as the
Regressions/Improvements sections in `lein bench-report`.

For targeted A/B testing, specify any two artifacts explicitly:

```
$ lein bench-report \
    --file bench-results/2026-04-12_05-22-59.edn \
    --baseline bench-results/2026-04-09_09-11-13.edn
```

### Analysis Functions

The analyze layer (`bench_analyze.clj`) provides:

| Function | Purpose |
|----------|---------|
| `ordered-scorecard` | For each `[size, group]`, find best OC variant vs best peer, compute speedup, classify as win/parity/loss (thresholds: 1.05x / 0.95x) |
| `regression-report` | Match cells across two artifacts, compute deltas, classify severity |
| `headline-wins` | Extract curated comparisons (from a hand-maintained spec) pivoted by size for the scaling tables |
| `category-summary` | Per-category aggregates: win/parity/loss counts, geometric mean speedup, best win, worst loss with group name |
| `rope-family-summary` | Cross-variant side-by-side at the largest measured size |
| `significant-wins` / `significant-losses` | Filter scorecard by magnitude thresholds |
| `parity-cases` | Filter scorecard for near-1.0x cases |
| `executive-summary` | One-paragraph overview: case count, win/loss tally, best/worst, regression count |

### The Report Sections

`lein bench-report` renders these sections in order:

1. **Run** / **Platform** / **Baseline Run** — metadata headers
2. **Summary** — one-paragraph executive summary
3. **Headline Performance** — scaling tables grouped by section
   (set algebra, ordered set, ordered map, long-specialized,
   string-specialized, rope, string-rope, byte-rope, range map,
   segment tree, priority queue, multiset, fuzzy set/map)
4. **Performance by Category** — geomean aggregation across all cases
   in each category
5. **Rope Family at Scale** — cross-variant structural ops at N=500K
6. **Significant Wins** — ranked wins above 1.2x
7. **At Parity** — cases within 5% of 1.0x
8. **Significant Losses** — ranked losses below 0.83x
9. **Full Scorecard** — all measured comparisons with times and status
10. **Regressions** — A/B deltas flagged as slower
11. **Improvements** — A/B deltas flagged as faster


## Running

```
$ lein bench                  # Criterium, N=100K (~5 min)
$ lein bench --full           # Criterium, N=1K,5K,10K,100K,500K (~60 min)
$ lein bench --readme --full  # README tables only (~10 min)
$ lein bench --sizes 50000    # Custom sizes

$ lein bench-simple           # Quick iteration bench (100 to 100K)
$ lein bench-simple --full    # Full suite (100 to 1M)
$ lein bench-range-map        # Range-map vs Guava TreeRangeMap
$ lein bench-parallel         # Parallel threshold crossover analysis
$ lein bench-rope-tuning      # Rope chunk-size sweep

$ lein bench-report           # Analyze latest results (auto-selects baseline)
$ lein bench-report --all     # Show all rows instead of top 30
```

Results are written to `bench-results/<timestamp>.edn`. The report tool
reads the EDN and produces formatted output. To commit a snapshot:

```
$ lein bench-report > doc/report.txt
```

## Methodology

- **Criterium** for statistical benchmarking: JIT warmup, outlier
  detection, confidence intervals. Quick-benchmark mode (6 samples) for
  the full suite; full benchmark (60 samples) available via `--readme`.
- **Relative ratios** are more meaningful than absolute times. The report
  presents speedup factors (>1x = we win) throughout.
- **Geometric mean** per category: the right average for ratios because
  a 2x win and a 0.5x loss cancel to 1.0x, not 1.25x.
- **Auto-compare**: both `lein bench` and `lein bench-report`
  automatically compare against the prior run and flag regressions
  (>10% slower) and improvements (>10% faster).
- **Artifact versioning**: schema changes increment the artifact
  version so tools fail cleanly on incompatible data rather than
  silently producing wrong results.


## How to Read the Results

### Set Algebra

Two sets of size N with 50% overlap. Adams' divide-and-conquer with
fork-join parallelism. This is the library's dominant advantage — the
split/join structure gives work-optimal set algebra that parallelizes
naturally.

Fork-join thresholds (tuned empirically):
- union: root 131,072 / recursive 65,536 / sequential cutoff 64
- intersection: root 65,536 / recursive 65,536 / sequential cutoff 64
- difference: root 131,072 / recursive 65,536 / sequential cutoff 64

Against `sorted-set`, the gap is mainly algorithmic: `clojure.set` paths
over built-in sorted collections do not exploit a native split/join
algebra. Against `data.avl`, both libraries benefit from ordered trees,
but our split/join constant factors are lower and the operations also
parallelize.

Against `clojure.core/set` (hash-set), this is not an ordered-collection
comparison — it's an exploratory stress test. Even so, the current
implementation wins decisively because hash-set operations are O(n)
membership scans while ours are O(n log n / p) with parallelism.

### Construction

Batch from collection via parallel fold + union. This is the right path
to benchmark and to use. Sequential `conj` is a different workload
(covered as "insert" in the bench runner).

### Split

Weight-balanced trees have lower constant factors than AVL for split —
no height recomputation needed. Weight composes trivially after join;
AVL trees must recompute heights bottom-up.

### Fold (r/fold)

`CollFold` is not just delegated blindly to `r/fold`. `node-fold` splits
the tree eagerly in the caller thread and folds subtrees in parallel.
This keeps split overhead under control and avoids depending on dynamic
bindings inside ForkJoinPool worker tasks.

### Lookup

All ordered-tree libraries are in the same practical tier for point
lookup: O(log n) with similar constants. Small differences are not
meaningful compared with the much larger wins in set algebra and
split/join-derived operations.

### Iteration / Reduce

Both ordered-collections and data.avl implement direct tree reduction
paths. The monomorphic reduce paths (added in 0.2.1) bypass the
PRopeChunk protocol for rope variants, yielding ~1.7-1.8x improvement
on byte-rope and string-rope reduce.

### Rope Family

The rope family (generic `rope`, `string-rope`, `byte-rope`) is
optimized for structural editing — concat, split, splice, insert, and
remove are O(log n) vs O(n) for the native baselines (PersistentVector,
String, byte[]). The advantage is unbounded and grows linearly with
collection size.

**Where the ropes lose:**

| Area | Ratio vs baseline | Why |
|------|-------------------|-----|
| Random nth | 0.01-0.2x | O(log n) tree descent vs O(1) array/trie lookup |
| Reduce | 0.1-0.5x (string/byte) | Per-chunk overhead vs bare array loop |
| Split | 0.1-0.3x at small N | O(log n) vs O(1) `subvec` / memcpy; crosses over at ~50K |
| Construction | 0.2-0.5x at large N | Tree building vs array allocation |
| Regex (re-seq) | 0.2x | CharSequence dispatch overhead vs String fast path in Pattern |

These losses are architectural — inherent to the tree-backed design, not
the implementation. At scale, the structural editing wins (100-1300x)
dominate any mixed workload.

**Monomorphic hot paths** (0.2.1): each variant inlines the tree walk
for `nth` and `reduce`, using direct chunk-type calls (`alength`/`aget`,
`.length`/`.charAt`, `.count`/`.nth`) instead of dispatching through the
`PRopeChunk` protocol at every tree level. This cuts `nth` cost by
~2-2.5x and `reduce` by ~1.7-3.3x vs the generic kernel path.

### Specialized Node Types

Primitive-specialized node types (`LongKeyNode`, `DoubleKeyNode`)
reduce boxing and comparison overhead for homogeneous-key cases.
`string-ordered-set` uses `String.compareTo` directly. These are
constant-factor optimizations on top of the same tree algebra.

### Specialized Collections

Range maps, segment trees, priority queues, multisets, and fuzzy
collections are each benchmarked against their natural competitor:

- **Segment tree** vs sorted-map subseq for range queries — the standout,
  with O(log n) vs O(k) giving 2800x at 500K.
- **Priority queue** vs sorted-set-by of tuples — 2-4x on push/pop due
  to avoiding tuple allocation.
- **Range map** vs Guava TreeRangeMap — comparable on construction and
  lookup, 2-2.5x on carve-out insert and iteration due to persistent
  structure sharing.
- **Fuzzy set/map** vs sorted-set with manual floor/ceiling — comparable.
  The value is API ergonomics, not raw speed.
