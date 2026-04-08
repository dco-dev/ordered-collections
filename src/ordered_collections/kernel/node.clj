(ns ordered-collections.kernel.node
  (:import  [clojure.lang MapEntry]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Leaf Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; It can sometimes be the case that "leaf" nodes aren't a static value,
;; but computed/generated/populated in some way. so i usually make `leaf`
;; a function rather than value just as a matter of practice in order to
;; have a complete abstraction layer between node and tree layers.

(definline leaf []
  nil)

(definline leaf? [x]
  `(identical? ~x nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node Capability
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: this exists to work around spurious build warnings during clojurescript
;; build phase of enclosing project

(defmacro ^:private definterface-once [iname & args]
  (when-not (resolve iname)
    `(definterface ~iname ~@args)))

(definterface-once INode
  (k  []  "key:             any value")
  (v  []  "value:           any value")
  (l  []  "left-child:      a Node or Leaf")
  (r  []  "right-child:     a Node or Leaf")
  (kv []  "key-val:         a pair containing both key and value"))

(definterface-once IBalancedNode
  (^long x []  "balance-metric:  an integer value"))

(definterface-once IAugmentedNode
  (z  []  "auxiliary constituent(s) for extended tree algorithms"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Model
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype SimpleNode [k v l r ^long x]
  java.io.Serializable
  IBalancedNode
  (x  [_] x)
  INode
  (k  [_] k)
  (v  [_] v)
  (l  [_] l)
  (r  [_] r)
  (kv [_] (MapEntry. k v)))

(deftype IntervalNode [k v l r ^long x z]
  java.io.Serializable
  IBalancedNode
  (x  [_] x)
  IAugmentedNode
  (z  [_] z)     ;; max node child interval span
  INode
  (k  [_] k)
  (v  [_] v)
  (l  [_] l)
  (r  [_] r)
  (kv [_] (MapEntry. k v)))

(deftype AggregateNode [k v l r ^long x agg]
  java.io.Serializable
  IBalancedNode
  (x  [_] x)
  IAugmentedNode
  (z  [_] agg)     ;; cached subtree aggregate: op(left.agg, val, right.agg)
  INode
  (k  [_] k)
  (v  [_] v)
  (l  [_] l)
  (r  [_] r)
  (kv [_] (MapEntry. k v)))

;; Primitive-specialized node types for better performance with numeric keys.
;; These avoid boxing overhead for Long/Double keys.
;;
;; Academic justification: Boxing overhead for primitive comparisons can be
;; 15-25% of total lookup time (see competitive-analysis.md). By storing
;; the key as a primitive long, we eliminate:
;;   1. Box allocation during insertion
;;   2. Unboxing during comparison
;;   3. GC pressure from boxed Long objects

(deftype LongKeyNode [^long k v l r ^long x]
  java.io.Serializable
  IBalancedNode
  (x  [_] x)
  INode
  (k  [_] k)
  (v  [_] v)
  (l  [_] l)
  (r  [_] r)
  (kv [_] (MapEntry. k v)))

(deftype DoubleKeyNode [^double k v l r ^long x]
  java.io.Serializable
  IBalancedNode
  (x  [_] x)
  INode
  (k  [_] k)
  (v  [_] v)
  (l  [_] l)
  (r  [_] r)
  (kv [_] (MapEntry. k v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constitutent Accessors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; @gunnarson style

(definline -k  [n] `(.k  ~(with-meta n {:tag 'ordered_collections.kernel.node.INode})))
(definline -v  [n] `(.v  ~(with-meta n {:tag 'ordered_collections.kernel.node.INode})))
(definline -l  [n] `(.l  ~(with-meta n {:tag 'ordered_collections.kernel.node.INode})))
(definline -r  [n] `(.r  ~(with-meta n {:tag 'ordered_collections.kernel.node.INode})))
(definline -x  [n] `(.x  ~(with-meta n {:tag 'ordered_collections.kernel.node.IBalancedNode})))
(definline -z  [n] `(.z  ~(with-meta n {:tag 'ordered_collections.kernel.node.IAugmentedNode})))
(definline -kv [n] `(.kv ~(with-meta n {:tag 'ordered_collections.kernel.node.INode})))

