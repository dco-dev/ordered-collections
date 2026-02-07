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
