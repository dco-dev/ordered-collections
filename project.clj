(defproject com.dean/ordered-collections "0.2.1-SNAPSHOT"
  :description "Fast, modern, _ropes_ and ordered collections that do more than sort."
  :author       "Dan Lentz <danlentz@gmail.com>"
  :url "http://github.com/dco-dev/ordered-collections"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.12.4" :scope "provided"]]

  :profiles {:dev {:dependencies [[org.clojure/data.avl "0.2.0"]
                                  [org.clojure/test.check "1.1.1"]
                                  [org.clojure/math.combinatorics "0.3.2"]
                                  [criterium "0.4.6"]
                                  [com.clojure-goes-fast/clj-memory-meter "0.3.0"]
                                  [com.google.guava/guava "33.0.0-jre"]]
                   :jvm-opts ["-Djdk.attach.allowAttachSelf"
                              "-XX:+EnableDynamicAgentLoading"]}}

  :plugins [[lein-codox "0.10.8"]
            [lein-ancient "0.7.0"]
            [lein-cloverage "1.2.4"]
            [lein-shell "0.5.0"]]

  :signing  {:gpg-key "0CA466A1AB48F0C0264AF55307BAD70176C4B179"}

  :repl-options {:init-ns ordered-collections.core}

  :codox    {:output-path  "doc/api"
             :src-dir-uri "https://github.com/dco-dev/ordered-collections/blob/master/"
             :src-linenum-anchor-prefix "L"
             :project {:name "ordered-collections"}}

  :global-vars {*warn-on-reflection* true} ; during tests clj-memory-meter will complain

  :aliases {"bench"           ["run" "-m" "ordered-collections.bench-runner"]
             "bench-simple"    ["run" "-m" "ordered-collections.simple-bench"]
             "bench-range-map" ["run" "-m" "ordered-collections.range-map-bench"]
             "bench-parallel"  ["run" "-m" "ordered-collections.parallel-threshold-bench"]
             "bench-rope-fold" ["run" "-m" "ordered-collections.rope-fold-bench"]
             "bench-transient-rope" ["run" "-m" "ordered-collections.transient-rope-bench"]
             "bench-rope-tuning" ["run" "-m" "ordered-collections.rope-tuning-bench"]
             "bench-string-rope" ["run" "-m" "ordered-collections.string-rope-bench"]
             "stats"           ["shell" "bb" "stats"]
             "bench-report"    ["shell" "bb" "bench-report"]
             "paper"           ["shell" "bb" "paper"]})
