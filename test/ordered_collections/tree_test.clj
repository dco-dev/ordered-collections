(ns ordered-collections.tree-test
  (:require [clojure.test :refer :all]
            [ordered-collections.tree.node :as node]
            [ordered-collections.tree.tree :as tree]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- matches [n1 n2]
  (if (node/leaf? n1)
    (is (node/leaf? n2))
    (do
      (is (= (node/-k n1) (node/-k n2)))
      (is (= (node/-v n1) (node/-v n2)))
      (is (= (node/-x n1) (node/-x n2)))
      (matches (node/-l n1) (node/-l n2))
      (matches (node/-r n1) (node/-r n2)))))

(def x1    (tree/node-singleton (gensym) true))
(def x3    (tree/node-create    (gensym) true x1  x1))
(def x5    (tree/node-create    (gensym) true x3  x1))
(def x7    (tree/node-create    (gensym) true x3  x3))
(def x11   (tree/node-create    (gensym) true x3  x7))
(def x15   (tree/node-create    (gensym) true x7  x7))
(def x23   (tree/node-create    (gensym) true x15 x7))
(def x27   (tree/node-create    (gensym) true x15 x11))
(def x31   (tree/node-create    (gensym) true x15 x15))
(def x39   (tree/node-create    (gensym) true x15 x23))
(def x51   (tree/node-create    (gensym) true x23 x27))
(def x63   (tree/node-create    (gensym) true x31 x31))
(def x127  (tree/node-create    (gensym) true x63 x63))

;; TODO: consolidate

(defn- make-integer-tree
  ([size]           (reduce tree/node-add (node/leaf) (shuffle (range size))))
  ([start end]      (reduce tree/node-add (node/leaf) (shuffle (range start end))))
  ([start end step] (reduce tree/node-add (node/leaf) (shuffle (range start end step)))))

(defn- make-string-tree [size]
  (reduce tree/node-add (node/leaf) (map str (shuffle (range size)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tree-allocator-check
  (is (= 0  (tree/node-size   (node/leaf))))
  (is (= 1  (tree/node-weight (node/leaf))))
  (is (= 1  (tree/node-size   (tree/node-singleton :k :v))))
  (is (= 2  (tree/node-weight (tree/node-singleton :k :v))))
  (is (= 1  (tree/node-size   (tree/node-create :k :v (node/leaf) (node/leaf)))))
  (is (= 2  (tree/node-weight (tree/node-create :k :v (node/leaf) (node/leaf)))))
  (is (= 1  (tree/node-size   x1)))
  (is (= 2  (tree/node-weight x1)))
  (is (= 3  (tree/node-size   x3)))
  (is (= 4  (tree/node-weight x3)))
  (is (= 7  (tree/node-size   x7)))
  (is (= 8  (tree/node-weight x7)))
  (is (= 15 (tree/node-size   x15)))
  (is (= 16 (tree/node-weight x15)))
  (is (= 31 (tree/node-size   x31)))
  (is (= 32 (tree/node-weight x31)))
  (is (= 63 (tree/node-size   x63)))
  (is (= 64 (tree/node-weight x63))))

;; (1 2 4 8 16 32 64 128 256 512 1024 2048 4096 8192 16384 32768
;;  65536 131072 262144 524288 1048576)

(deftest tree-health-check
  (doseq [size (take 21 (iterate #(* % 2) 1))]
    (is (tree/node-healthy? (make-string-tree size)))    ;; string/int tree are structurally
    (is (tree/node-healthy? (make-integer-tree size))))) ;; very different due to sort order

(deftest rotation-check:single-left
  (let [node node/->SimpleNode]
    (matches (tree/rotate-single-left tree/node-create-weight-balanced :AK :AV
                  (node :XK :XV (node/leaf) (node/leaf) 1)
                  (node :BK :BV (node :YK :YV (node/leaf) (node/leaf) 1)
                                (node :ZK :XZ (node/leaf) (node/leaf) 1) 3))
             (node :BK :BV
                  (node :AK :AV (node :XK :XV (node/leaf) (node/leaf) 1)
                                (node :YK :YV (node/leaf) (node/leaf) 1) 3)
                  (node :ZK :XZ (node/leaf) (node/leaf) 1) 5))))

(deftest rotation-check:double-left
  (let [node node/->SimpleNode]
    (matches (tree/rotate-double-left tree/node-create-weight-balanced :AK :AV
                 (node :XK :XV (node/leaf) (node/leaf) 1)
                 (node :CK :CV
                       (node :BK :BV (node :Y1K :Y1V (node/leaf) (node/leaf) 1)
                                     (node :Y2K :Y2V (node/leaf) (node/leaf) 1) 3)
                       (node :ZK :ZV (node/leaf) (node/leaf) 1) 5))
             (node :BK :BV
                 (node :AK :AV (node :XK :XV (node/leaf) (node/leaf) 1)
                               (node :Y1K :Y1V (node/leaf) (node/leaf) 1) 3)
                 (node :CK :CV
                       (node :Y2K :Y2V (node/leaf) (node/leaf) 1)
                       (node :ZK :ZV (node/leaf) (node/leaf) 1) 3) 7))))

(deftest rotation-check:single-right
  (let [node node/->SimpleNode]
    (matches (tree/rotate-single-right tree/node-create-weight-balanced :BK :BV
                 (node :AK :AV (node :XK :XV (node/leaf) (node/leaf) 1)
                               (node :YK :YV (node/leaf) (node/leaf) 1) 3)
                 (node :ZK :XZ (node/leaf) (node/leaf) 1))
             (node :AK :AV
                 (node :XK :XV (node/leaf) (node/leaf) 1)
                 (node :BK :BV (node :YK :YV (node/leaf) (node/leaf) 1)
                               (node :ZK :XZ (node/leaf) (node/leaf) 1) 3) 5))))

(deftest rotation-check:double-right
  (let [node node/->SimpleNode]
    (matches (tree/rotate-double-right tree/node-create-weight-balanced :CK :CV
                 (node :AK :AV (node :XK :XV (node/leaf) (node/leaf) 1)
                               (node :BK :BV (node :Y1K :Y1V (node/leaf) (node/leaf) 1)
                                             (node :Y2K :Y2V (node/leaf) (node/leaf) 1) 3) 5)
                 (node :ZK :ZV (node/leaf) (node/leaf) 1))
             (node :BK :BV
                   (node :AK :AV (node :XK :XV (node/leaf) (node/leaf) 1)
                                 (node :Y1K :Y1V (node/leaf) (node/leaf) 1) 3)
                   (node :CK :CV (node :Y2K :Y2V (node/leaf) (node/leaf) 1)
                                 (node :ZK :ZV (node/leaf) (node/leaf) 1) 3) 7))))

(deftest join-check:single-left
  (let [rot:1L (tree/node-stitch :root true x1 x7)]
    (is (= 9 (tree/node-size rot:1L)))
    (is (= :root (node/-k (node/-l rot:1L))))
    (is (= 5 (tree/node-size (node/-l rot:1L))))
    (is (= 3 (tree/node-size (node/-r rot:1L))))
    (tree/kvlr [k v l r] rot:1L
      (is (= k (node/-k (tree/node-stitch k v l r)))))))

(deftest join-check:single-right
  (let [rot:1R (tree/node-stitch :root true x7 x1)]
    (is (= 9 (tree/node-size rot:1R)))
    (is (= :root (node/-k (node/-r rot:1R))))
    (is (= 5 (tree/node-size (node/-r rot:1R))))
    (is (= 3 (tree/node-size (node/-l rot:1R))))
    (tree/kvlr [k v l r] rot:1R
      (is (= k (node/-k (tree/node-stitch k v l r)))))))

(deftest join-check:double-left
  (let [node node/->SimpleNode]
    (matches (tree/node-stitch :AK :AV
                 (node :XK :XV (node/leaf) (node/leaf) 1)
                 (node :CK :CV
                   (node :BK :BV
                     (node :Y1K :Y1V (node :Q1K :Q1V (node/leaf) (node/leaf) 1) (node/leaf) 2)
                     (node :Y2K :Y2V (node :Q2K :Q2V (node/leaf) (node/leaf) 1) (node/leaf) 2) 5)
                   (node :ZK :ZV (node/leaf) (node/leaf) 1) 7))
        (node :BK :BV
          (node :AK :AV
            (node :XK :XV (node/leaf) (node/leaf) 1)
            (node :Y1K :Y1V (node :Q1K :Q1V (node/leaf) (node/leaf) 1) (node/leaf) 2) 4)
          (node :CK :CV
            (node :Y2K :Y2V (node :Q2K :Q2V (node/leaf) (node/leaf) 1) (node/leaf) 2)
            (node :ZK :ZV (node/leaf) (node/leaf) 1) 4) 9))))

(deftest join-check:double-right
  (let [node node/->SimpleNode]
    (matches (tree/node-stitch :CK :CV
                 (node :AK :AV
                   (node :XK :XV (node/leaf) (node/leaf) 1)
                   (node :BK :BV
                     (node :Y1K :Y1V (node :Q1K :Q1V (node/leaf) (node/leaf) 1) (node/leaf) 2)
                     (node :Y2K :Y2V (node :Q2K :Q2V (node/leaf) (node/leaf) 1) (node/leaf) 2) 5) 7)
                 (node :ZK :ZV (node/leaf) (node/leaf) 1))
        (node :BK :BV
          (node :AK :AV
            (node :XK :XV (node/leaf) (node/leaf) 1)
            (node :Y1K :Y1V (node :Q1K :Q1V (node/leaf) (node/leaf) 1) (node/leaf) 2) 4)
          (node :CK :CV
            (node :Y2K :Y2V (node :Q2K :Q2V (node/leaf) (node/leaf) 1) (node/leaf) 2)
            (node :ZK :ZV (node/leaf) (node/leaf) 1) 4) 9))))

(deftest concat3-check
  (is
    (= \A
       (first
         (name
           (node/-k
             (tree/node-least
               (tree/node-concat3
                 (gensym "A") true (node/leaf) x5)))))))
  (is
    (= \Z
       (first
         (name
           (node/-k
             (tree/node-greatest
               (tree/node-concat3
                 (gensym "Z") true x5 (node/leaf))))))))
  (is
    (= \A
       (first
         (name
           (node/-k
             (tree/node-least
               (tree/node-concat3
                 (gensym "C") true
                 (tree/node-singleton (gensym "A") true) x7))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Operational Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: consolidate

(defn- make-tree0 [n]
  (let [ks (shuffle (range n))
        vs (map str ks)
        pairs (map vector ks vs)]
    (reduce #(tree/node-add %1 (first %2) (second %2)) (node/leaf) pairs)))

(defn- make-tree1 [n]
  (let [ks (shuffle (range n))
        vs (map str ks)
        pairs (map vector vs ks)]
    (reduce #(tree/node-add %1 (first %2) (second %2)) (node/leaf) pairs)))

(deftest extrema-check
  (doseq [size [10 100 1000 10000 100000]]
    (let [tree (make-string-tree size)]
      (is (= "0"               (-> tree tree/node-least    node/-k)))
      (is (= (-> size dec str) (-> tree tree/node-greatest node/-k)))
      (is (= "1" (-> tree tree/node-remove-least tree/node-least node/-k)))
      (is (= (-> size dec dec str)
             (-> tree tree/node-remove-greatest tree/node-greatest node/-k))))))

(deftest node-seq-check
  (doseq [size [1 10 100 1000 10000 100000]]
    (let [tree (make-integer-tree size)]
      (is (= (sort < (range size)) (map node/-k (tree/node-seq tree))))
      (is (= (sort > (range size)) (map node/-k (tree/node-seq-reverse tree)))))))

;; TODO: restructure

(deftest association-check
  (letfn [(chk0 [n]
            (let [ks (range n) vs (map str ks) pairs (map vector ks vs)]
              (is (= pairs (tree/node-vec (make-tree0 n) :accessor :kv)))))
          (chk1 [n]
            (let [ks (range n) vs (map str ks) pairs (map vector vs ks)]
              (is (= (sort-by first pairs) (tree/node-vec (make-tree1 n) :accessor :kv)))))]
    (doseq [size [10 100 1000 10000 100000 500000]]
      (chk0 size)
      (chk1 size))))

(deftest node-find-check
  (doseq [size [1 10 100 1000 10000 100000]]
    (let [tree (make-string-tree size)]
      (dotimes [_ 5000]
        (let [i (-> size rand-int str)]
          (is (= i (-> tree (tree/node-find i) node/-v))))))))

(deftest node-rank-nth-check
  (doseq [size [1 10 100 1000 10000 100000]]
    (let [tree (make-integer-tree size)]
      (dotimes [_ 5000]
        (let [i (rand-int size)]
          (is (= i (node/-k (tree/node-nth tree i))))
          (is (= i (tree/node-rank tree i))))))))

;; NOTE: implicitly exercises node-split-nth, node-subseq

(deftest node-reduction-check
  (doseq [size [1 10 100 1000 10000 100000]]
    (let [tree (make-integer-tree size)
          sum  (reduce + (range size))]
      (is (= sum (reduce + (map node/-k (tree/node-seq tree)))))
      (is (= (reverse (range size))
             (tree/node-reduce-right (fn [acc n] (conj acc (node/-k n))) [] tree)))
      (let [seen (volatile! [])]
        (tree/node-run-kv! tree (fn [k v] (vswap! seen conj [k v])))
        (is (= (mapv (fn [k] [k k]) (range size)) @seen)))
      (dotimes [_ 1000]
        (is (= sum (tree/node-fold (inc (rand-int size))
                     tree + (fn ([acc x] (+ acc (node/-k x)))))))))))

(deftest node-traversal-api-check
  (let [tree     (make-tree0 10)
        forward  (mapv identity (range 10))
        reverse  (vec (reverse forward))
        forward-kv (mapv vector forward (map str forward))
        reverse-kv (mapv vector reverse (map str reverse))
        grow-seeded (fn [acc x]
                      (conj (if (vector? acc) acc [acc]) x))
        grow-seeded-entry (fn [acc e]
                            (conj (if (instance? clojure.lang.IMapEntry acc)
                                    [[(key acc) (val acc)]]
                                    acc)
                                  [(key e) (val e)]))
        node-ks  (fn [n] (node/-k n))]
    (let [seen (volatile! [])]
      (tree/node-run! tree #(vswap! seen conj (node-ks %)))
      (is (= forward @seen)))
    (let [seen (volatile! [])]
      (tree/node-run-reverse! tree #(vswap! seen conj (node-ks %)))
      (is (= reverse @seen)))
    (let [seen (volatile! [])]
      (tree/node-run-kv! tree (fn [k v] (vswap! seen conj [k v])))
      (is (= forward-kv @seen)))
    (let [seen (volatile! [])]
      (tree/node-run-kv-reverse! tree (fn [k v] (vswap! seen conj [k v])))
      (is (= reverse-kv @seen)))
    (is (= forward
           (tree/node-reduce (fn [acc n] (conj acc (node-ks n))) [] tree)))
    (is (= reverse
           (tree/node-reduce-right (fn [acc n] (conj acc (node-ks n))) [] tree)))
    (is (= forward
           (tree/node-reduce-keys conj [] tree)))
    (is (= reverse
           (tree/node-reduce-keys-right conj [] tree)))
    (is (= (mapv vector forward (map str forward))
           (tree/node-reduce-kv (fn [acc k v] (conj acc [k v])) [] tree)))
    (is (= (mapv vector reverse (map str reverse))
           (tree/node-reduce-kv-right (fn [acc k v] (conj acc [k v])) [] tree)))
    (is (= forward-kv
           (tree/node-reduce-entries (fn [acc e] (conj acc [(key e) (val e)])) [] tree)))
    (is (= reverse-kv
           (tree/node-reduce-entries-right (fn [acc e] (conj acc [(key e) (val e)])) [] tree)))
    (is (= 3
           (tree/node-reduce (fn [acc n]
                               (let [k (node-ks n)]
                                 (if (= k 3) (reduced k) k)))
                             nil tree)))
    (is (= 3
           (tree/node-reduce-right (fn [_ n]
                                     (let [k (node-ks n)]
                                       (if (= k 3) (reduced k) nil)))
                                   nil tree)))
    (is (= 3
           (tree/node-reduce-keys (fn [_ k] (if (= k 3) (reduced k) nil))
                                  nil tree)))
    (is (= 3
           (tree/node-reduce-keys-right (fn [_ k] (if (= k 3) (reduced k) nil))
                                        nil tree)))
    (is (= [3 "3"]
           (tree/node-reduce-kv (fn [_ k v]
                                  (if (= k 3) (reduced [k v]) nil))
                                nil tree)))
    (is (= [3 "3"]
           (tree/node-reduce-kv-right (fn [_ k v]
                                        (if (= k 3) (reduced [k v]) nil))
                                      nil tree)))
    (is (= [3 "3"]
           (tree/node-reduce-entries (fn [_ e]
                                       (if (= (key e) 3)
                                         (reduced [(key e) (val e)])
                                         nil))
                                     nil tree)))
    (is (= [3 "3"]
           (tree/node-reduce-entries-right (fn [_ e]
                                             (if (= (key e) 3)
                                               (reduced [(key e) (val e)])
                                               nil))
                                           nil tree)))
    (is (= [] (tree/node-reduce conj [] (node/leaf))))
    (is (= [] (tree/node-reduce-right conj [] (node/leaf))))
    (is (= [] (tree/node-reduce-keys conj [] (node/leaf))))
    (is (= [] (tree/node-reduce-keys-right conj [] (node/leaf))))
    (is (= [] (tree/node-reduce-kv (fn [acc k v] (conj acc [k v])) [] (node/leaf))))
    (is (= [] (tree/node-reduce-kv-right (fn [acc k v] (conj acc [k v])) [] (node/leaf))))
    (is (= [] (tree/node-reduce-entries (fn [acc e] (conj acc e)) [] (node/leaf))))
    (is (= [] (tree/node-reduce-entries-right (fn [acc e] (conj acc e)) [] (node/leaf))))
    (is (= forward
           (tree/node-reduce-keys grow-seeded tree)))
    (is (= reverse
           (tree/node-reduce-keys-right grow-seeded tree)))
    (is (= forward-kv
           (tree/node-reduce-entries grow-seeded-entry tree)))
    (is (= reverse-kv
           (tree/node-reduce-entries-right grow-seeded-entry tree)))
    (is (= nil (tree/node-run! (node/leaf) (constantly :impossible))))
    (is (= nil (tree/node-run-kv! (node/leaf) (fn [_ _] :impossible))))))

(deftest node-comparison-check
  (let [nums #(-> % (repeatedly (partial rand-int 1000000000)))
        tree #(->> % nums (reduce tree/node-add (node/leaf)))
        chk  #(let [m0 (node/-k (tree/node-least %1))
                    m1 (node/-k (tree/node-least %2))]
                (is (zero? (tree/node-set-compare %1 %1)))
                (is (zero? (tree/node-set-compare %2 %2)))
                (cond
                  (< m0 m1) (is (= -1 (tree/node-set-compare %1 %2)))
                  (< m1 m0) (is (= 1 (tree/node-set-compare %1 %2)))
                  true      (recur (tree/node-remove-least %1)
                                   (tree/node-remove-least %2))))]
    (doseq [size [1 10 100 1000 10000 100000]]
      (chk (tree size) (tree size)))))

(defspec prop-tree-health-after-random-edit 100
  (prop/for-all [size  (gen/choose 1 500)
                 extra gen/small-integer]
    (let [t        (make-integer-tree size)
          idx      (rand-int size)
          victim   (-> t (tree/node-nth idx) node/-k)
          added    (tree/node-add t extra)
          removed  (tree/node-remove t victim)
          trimmedl (tree/node-remove-least t)
          trimmedr (tree/node-remove-greatest t)]
      (and (tree/node-healthy? added)
           (tree/node-healthy? removed)
           (tree/node-healthy? trimmedl)
           (tree/node-healthy? trimmedr)))))
