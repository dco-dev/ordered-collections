(ns com.dean.ordered-collections.parallel
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
  "Run thunk in the common pool and return its result."
  [f]
  (.invoke common-fork-join-pool ^ForkJoinTask (recursive-task f)))

(defmacro fork-join
  "Fork left-expr as a RecursiveTask, compute right-expr inline, then join.

   Important: ForkJoin worker threads do not propagate Clojure dynamic
   bindings. Forked bodies must therefore rely only on explicit arguments
   captured at the parallel entry point, not on dynamic vars such as
   `order/*compare*` or `tree/*t-join*`."
  [[left-sym left-expr right-sym right-expr] combine-expr]
  `(let [left-task# (recursive-task (fn [] ~left-expr))
         _# (.fork ^ForkJoinTask left-task#)
         ~right-sym ~right-expr
         ~left-sym (.join ^ForkJoinTask left-task#)]
     ~combine-expr))
