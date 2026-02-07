# com.dean/ordered-collections

This library provides a collection of data structures implemented using a
modular, extensible, foldable, weight balanced persistent binary tree:
ordered-sets, ordered-maps, interval-sets, and interval-maps.

![tests](https://github.com/dco-dev/ordered-collections/actions/workflows/clojure.yml/badge.svg)
[![Clojars Project](https://img.shields.io/clojars/v/com.dean/ordered-collections.svg)](https://clojars.org/com.dean/ordered-collections)

---

**New to the library?** See how Zorp uses ordered-maps, interval-maps, segment-trees, and more to run his sneaker empire on the dark side of Pluto: **[Zorp's Sneaker Emporium](doc/zorp-example.md)** — a practical tutorial disguised as interplanetary commerce.

---

### Usage

To install, add the following dependency to your project or build file:

```
[com.dean/ordered-collections "0.2.0"]
```

#### Public API

The public api resides in the top-level `com.dean.ordered-collections.core` namespace:

```clj
(require '[com.dean.ordered-collections.core :as dean])
```

The basic operation of this library is as a drop-in replacement for
`clojure.core/sorted-set` and `clojure.core/sorted-map`.

#### Key Features

- **Full `clojure.lang.Sorted` support**: Use `subseq` and `rsubseq` natively
- **Parallel fold**: All types implement `CollFold` for efficient `r/fold`
- **Proper hashing**: `IHashEq` support for use in hash-based collections
- **Serializable**: `java.io.Serializable` marker interface
- **Fast iteration**: Optimized `IReduceInit`/`IReduce` (faster than `sorted-map`)

#### Constructors

* `(dean/ordered-set   coll)` - sorted set
* `(dean/ordered-set-by   pred coll)` - sorted set with custom comparator
* `(dean/ordered-map   coll)` - sorted map
* `(dean/ordered-map-by   pred coll)` - sorted map with custom comparator
* `(dean/interval-set  coll)` - set supporting interval overlap queries
* `(dean/interval-map  coll)` - map supporting interval overlap queries
* `(dean/priority-queue coll)` - persistent priority queue (min-heap)
* `(dean/ordered-multiset coll)` - sorted multiset (allows duplicates)
* `(dean/fuzzy-set coll)` - set returning closest element to query
* `(dean/fuzzy-map coll)` - map returning value for closest key to query

### Topics

#### What is an Interval Map?

Imagine you'd like to associate values with members of a set of
intervals over some continuous domain such as time or real numbers.
An example of this is shown below. An interval map answers the question,
which intervals overlap at some point on the domain. At 3.14159, in this
case, would be `x4` and `x7`.  The interval map is sparse itself, of
course, and would only need to contain the 8 constituent intervals.

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

This corresponds to the following example code:

```clj

(def x (dean/interval-map {[1 3] :x1
                           [4 7] :x2
                           [8 9] :x3
                           [0 5] :x4
                           [6 8] :x5
                           [9 9] :x6
                           [3 9] :x7
                           [4 5] :x8}))

(x 3.141592654) ;; =>  [:x4 :x7]
(x [5 5])       ;; =>  [:x4 :x7 :x8 :x2]

(get x 9)       ;; =>  [:x7 :x3 :x6]
(get x 9.00001) ;; =>  nil
(get x [1 4])   ;; =>  [:x4 :x1 :x7 :x8 :x2]

```

#### Performance

Benchmarks at N=500,000 elements (JVM 25, Clojure 1.12.4):

**Sets** — ordered-set vs sorted-set:

| Operation | sorted-set | ordered-set | Notes |
|-----------|------------|-------------|-------|
| Construction | 1.6s | 1.3s | **20% faster** (parallel fold) |
| Lookup | 15.3ms | 16.0ms | ~equal |
| Iteration | 77ms | 46ms | **40% faster** (IReduceInit) |
| r/fold | 92ms | 40ms | **2.3x faster** (CollFold) |
| Split ops | — | 2.7ms | **4x faster** than data.avl |

**Maps** — ordered-map vs sorted-map:

| Operation | sorted-map | ordered-map | Notes |
|-----------|------------|-------------|-------|
| Construction | 1.3s | 2.7s | 2.1x (weight-balanced overhead) |
| Lookup | 15.5ms | 17.3ms | ~equal |
| Iteration | 129ms | 116ms | **10% faster** (IReduceInit) |

#### Efficient Set Operations

This library implements a diverse collection of efficient set operations
on foldably parallel ordered sets:

```clj
(def foo (shuffle (range 500000)))

;; Construction: ordered-set is faster than sorted-set
(time (def x (dean/ordered-set foo)))      ;; 500K: ~1.3s
(time (def v (into (sorted-set) foo)))     ;; 500K: ~1.6s

;; Parallel fold: ordered-set is 2.3x faster
(time (r/fold + + x))                      ;; 500K: ~40ms
(time (r/fold + + v))                      ;; 500K: ~92ms

;; subseq/rsubseq support (clojure.lang.Sorted)
(subseq x >= 100 < 200)                    ;; efficient range queries
(rsubseq x > 500)                          ;; reverse range queries

;; Set operations via divide-and-conquer (O(m+n) time)
(def s0 (dean/ordered-set (range 0 1000000 2)))
(def s1 (dean/ordered-set (range 0 1000000 3)))
(time (dean/intersection s0 s1))           ;; 833K elements, ~1.2s
```

### Testing

Testing is accomplished with the standard `lein test`
```
$ lein test

lein test com.dean.ordered-collections.fuzzy-test
lein test com.dean.ordered-collections.interval-map-test
lein test com.dean.ordered-collections.interval-set-test
lein test com.dean.ordered-collections.interval-test
lein test com.dean.ordered-collections.ordered-map-test
lein test com.dean.ordered-collections.ordered-multiset-test
lein test com.dean.ordered-collections.ordered-set-test
lein test com.dean.ordered-collections.priority-queue-test
lein test com.dean.ordered-collections.range-map-test
lein test com.dean.ordered-collections.ranked-set-test
lein test com.dean.ordered-collections.segment-tree-test
lein test com.dean.ordered-collections.tree-test
lein test com.dean.ordered-collections.zorp-test

Ran 211 tests containing 426446 assertions.
0 failures, 0 errors.
```

### Modularity

This data structure library is designed around the following concepts of
modularity and extensibility.

#### Clojure/Java Interfaces

The top level collections are built on the standard Clojure/Java
interfaces, so, for example, working with an `ordered-set` is
identical to working with Clojure's `sorted-set`, using all of the same
standard collection functions, for the 99% case: meta, nth, seq, rseq,
assoc(-in), get(-in), invoke, compare, to-array, empty, .indexOf,
.lastIndexof, size, iterator-seq, first, last, =, count, empty,
contains, conj. disj, cons, fold, and many old friends will just
work, using an efficient implementation that takes full advantage of the
capabilities of our underlying tree index.

#### PExtensibleset

An exception to the above is due to the fact that `clojure.set` does not
provide interfaces for extensible sets. So, we provide our own
intersection, union, difference, subset, and superset.  These operators
work most efficiently on com.dean.ordered-collections collections and provide
support for backward interoperability with clojure (or possibly other)
set datatypes.

#### Root Container

The individual collection types (ordered-set, ordered-map, interval-set,
interval-map} are defined by their individual Class (clojure
`deftype`) of top level container that holds the root of an
indexed tree.  This container describes the behavior of the underlying
tree data structure along several architectural dimensions.

##### INodeCollection


The fundamental collection of nodes provides an interface to node
allocation machinery and to the root contained node.  A variant
based on persistent (on-disk) storage, for example, has been built
with customizations at this layer.

##### IBalancedCollection

For functional balanced trees, provides an interface to the `stitch`
function that returns a new, properly balanced tree containing one newly
allocated node adjoined.  The provided algorithm is
[weight balanced](https://en.wikipedia.org/wiki/Weight-balanced_tree)
however others may be used. We've experimented with red-black trees,
in particular, as variants at this layer.

##### IOrderedCollection

Ordered collections define a comparator and predicates to determine the
underlying algorithmic compatibility of other collections. Interval
Collections are a special type of OrderedCollection.

#### Tree

The heart of the library is our [persistent tree](https://github.com/dco-dev/ordered-collections/blob/master/src/com/dean/ordered_collections/tree/tree.clj).

The code is well documented and explains in more detail the efficiencies
of the internal collection operators.

This species of binary tree supports representations of sets, maps,
and vectors.  In addition to indexed key and range query, it
supports the `nth` operation to return nth node from the beginning of
the ordered tree, and `node-rank` to return the rank (sequential
position) of a given key within the ordered tree, both in logarithmic
time.

The axes of exstensibility of the tree implemntation
(`*compare*`,`*n-join*`, `*t-join*`) correspond to the interfaces
described above.

### Inspiration

 This implementation of a weight-balanced binary interval-tree data
 structure was inspired by the following:

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
     <http://pobox.com/~oleg/ftp/papers/LL3-collections-enumerators.txt>

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
     integer balance parameters from (Hirai/Yamomoto 2011).

 -  Wikipedia
     'Interval Tree'
     <https://en.wikipedia.org/wiki/Interval_tree>

 -  Wikipedia
     'Weight Balanced Tree'
     <https://en.wikipedia.org/wiki/Weight-balanced_tree>

 -  Andrew Baine, Rahul Jaine (2007)
     'Purely Functional Data Structures in Common Lisp'
     Google Summer of Code 2007
     <https://common-lisp.net/project/funds/funds.pdf>
     <https://developers.google.com/open-source/gsoc/2007/>

 - Scott L. Burson
     'Functional Set-Theoretic Collections for Common Lisp'
     <https://common-lisp.net/project/fset/>

### License

The use and distribution terms for this software are covered by the [Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php), which can be found in the file LICENSE.txt at the root of this distribution. By using this software in any fashion, you are agreeing to be bound by the terms of this license. You must not remove this notice, or any other, from this software.
