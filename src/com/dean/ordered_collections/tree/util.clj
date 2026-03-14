(ns com.dean.ordered-collections.tree.util)

(defmacro defalias
  "Define a var as an alias for another var, copying :arglists from the source.

   Usage:
     (defalias my-fn \"Optional docstring.\" other-ns/fn)

   Copies :arglists metadata from the source var so that (doc my-fn) shows
   the correct argument list in the REPL. The docstring on the alias, if
   provided, overrides the source's docstring."
  ([name src]
   `(do
      (def ~name ~src)
      (alter-meta! (var ~name) merge (select-keys (meta (var ~src)) [:arglists]))
      (var ~name)))
  ([name doc src]
   `(do
      (def ~name ~doc ~src)
      (alter-meta! (var ~name) merge (select-keys (meta (var ~src)) [:arglists]))
      (var ~name))))
