(ns ordered-collections.parallel
  "Minimal fork-join helpers for CPU-bound tree recursion.

   The small set of primitives needed by the tree algorithms:

   - enter the common pool once at the root
   - fork one recursive branch
   - compute the other branch inline
   - join and combine"
  (:import [java.util.concurrent ForkJoinPool ForkJoinTask RecursiveTask]))

(def ^ForkJoinPool common-fork-join-pool
  "Shared ForkJoinPool used by collection parallel operations."
  (ForkJoinPool/commonPool))

(defn in-fork-join-pool?
  []
  (ForkJoinTask/inForkJoinPool))

(defn ^RecursiveTask recursive-task
  "Create a RecursiveTask from a nullary thunk.

   This avoids the extra Callable -> ForkJoinTask adaptation layer used by
   ForkJoinTask/adapt while keeping task construction generic."
  [f]
  (proxy [RecursiveTask] []
    (compute []
      (f))))

(defn invoke-root
  "Run thunk in the common pool and return its result.

   This is the outer entry point into fork-join execution. Recursive work
   below that point should use `fork-join`"
  [f]
  (.invoke common-fork-join-pool ^ForkJoinTask (recursive-task f)))

(defmacro fork-join
  "Fork left-expr as a RecursiveTask, compute right-expr inline, then join.

   This left-fork/right-inline shape matches the intended recursive
   divide-and-conquer usage: keep one branch local, make the other branch
   stealable by ForkJoinPool worker threads, then combine the results.

   ForkJoin worker threads do not propagate Clojure dynamic bindings.
   Forked bodies must therefore rely only on explicit arguments
   captured at the parallel entry point, not on dynamic vars such as
   `order/*compare*` or `tree/*t-join*`."
  [[left-sym left-expr right-sym right-expr] combine-expr]
  `(let [left-task# (recursive-task (fn [] ~left-expr))
         _# (.fork ^ForkJoinTask left-task#)
         ~right-sym ~right-expr
         ~left-sym (.join ^ForkJoinTask left-task#)]
     ~combine-expr))
