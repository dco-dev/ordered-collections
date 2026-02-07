(defproject com.dean/ordered-collections "0.2.0"
  :description "Persistent Weight-Balanced Sorted Collections for Clojure"
  :author       "Dan Lentz"
  :url "http://github.com/dco-dev/ordered-collections"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/math.combinatorics "0.3.2"]]

  :profiles {:dev {:dependencies [[org.clojure/data.avl "0.2.0"]
                                  [criterium "0.4.6"]]}}

  :plugins [[lein-codox "0.10.8"]
            [lein-ancient "0.7.0"]
            [lein-cloverage "1.2.4"]]

  :signing  {:gpg-key "0CA466A1AB48F0C0264AF55307BAD70176C4B179"}

  :codox    {:output-path  "doc/api"
             :src-dir-uri "https://github.com/dco-dev/ordered-collections/blob/master/"
             :src-linenum-anchor-prefix "L"
             :project {:name "com.dean/ordered-collections"}}

  :global-vars {*warn-on-reflection* true})
