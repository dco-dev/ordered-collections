(ns com.dean.ordered-collections.tree.protocol
  (:require [clojure.set :as set]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PExtensibleSet
  (intersection [this that])
  (union        [this that])
  (difference   [this that])
  (subset       [this that])
  (superset     [this that]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority Queue Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol PPriorityQueue
  "Protocol for priority queue operations.
  Elements are [priority value] pairs."
  (pq-push     [pq priority value] "Add element with given priority. O(log n).")
  (pq-push-all [pq pairs]          "Add multiple [priority value] pairs. O(k log n).")
  (pq-peek-val [pq]                "Return just the value of min element, or nil.")
  (pq-peek-max [pq]                "Return [priority value] of max element, or nil.")
  (pq-peek-max-val [pq]            "Return just the value of max element, or nil.")
  (pq-pop-max  [pq]                "Remove max element. O(log n)."))

(extend-type clojure.lang.PersistentHashSet
  PExtensibleSet
  (intersection [this that]
    (set/intersection this that))
  (union [this that]
    (set/union this that))
  (difference [this that]
    (set/difference this that))
  (subset [this that]
    (set/subset? this that))
  (superset [this that]
    (set/subset? that this)))

(extend-type clojure.lang.PersistentTreeSet
  PExtensibleSet
  (intersection [this that]
    (set/intersection this that))
  (union [this that]
    (set/union this that))
  (difference [this that]
    (set/difference this that))
  (subset [this that]
    (set/subset? this that))
  (superset [this that]
    (set/subset? that this)))
