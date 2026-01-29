(ns com.dean.interval-tree.mutable-test
  (:require [clojure.test :refer :all]
            [com.dean.interval-tree.tree.node    :as node]
            [com.dean.interval-tree.tree.tree    :as tree]
            [com.dean.interval-tree.tree.mutable :as mut]))

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

(defn- make-mutable-integer-tree
  ([size]           (reduce mut/node-add! (node/leaf) (shuffle (range size))))
  ([start end]      (reduce mut/node-add! (node/leaf) (shuffle (range start end))))
  ([start end step] (reduce mut/node-add! (node/leaf) (shuffle (range start end step)))))

(defn- make-mutable-string-tree [size]
  (reduce mut/node-add! (node/leaf) (map str (shuffle (range size)))))

(defn- make-persistent-integer-tree
  ([size]           (reduce tree/node-add (node/leaf) (shuffle (range size))))
  ([start end]      (reduce tree/node-add (node/leaf) (shuffle (range start end))))
  ([start end step] (reduce tree/node-add (node/leaf) (shuffle (range start end step)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-allocator-check
  (is (= 0  (tree/node-size   (node/leaf))))
  (is (= 1  (tree/node-weight (node/leaf))))
  (is (= 1  (tree/node-size   (mut/node-singleton! :k :v))))
  (is (= 2  (tree/node-weight (mut/node-singleton! :k :v))))
  (let [n (mut/node-create! :k :v (node/leaf) (node/leaf))]
    (is (= 1 (tree/node-size n)))
    (is (= 2 (tree/node-weight n)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rotation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rotation-check:mutable-single-left
  (let [node node/->MutableSimpleNode
        result (mut/rotate-single-left!
                 (node :AK :AV
                   (node :XK :XV (node/leaf) (node/leaf) 1)
                   (node :BK :BV (node :YK :YV (node/leaf) (node/leaf) 1)
                                 (node :ZK :XZ (node/leaf) (node/leaf) 1) 3) 5))]
    (is (= :BK (node/-k result)))
    (is (= :BV (node/-v result)))
    (is (= 5   (node/-x result)))
    (is (= :AK (node/-k (node/-l result))))
    (is (= 3   (node/-x (node/-l result))))
    (is (= :XK (node/-k (node/-l (node/-l result)))))
    (is (= :YK (node/-k (node/-r (node/-l result)))))
    (is (= :ZK (node/-k (node/-r result))))
    (is (= 1   (node/-x (node/-r result))))))

(deftest rotation-check:mutable-double-left
  (let [node node/->MutableSimpleNode
        result (mut/rotate-double-left!
                 (node :AK :AV
                   (node :XK :XV (node/leaf) (node/leaf) 1)
                   (node :CK :CV
                     (node :BK :BV (node :Y1K :Y1V (node/leaf) (node/leaf) 1)
                                   (node :Y2K :Y2V (node/leaf) (node/leaf) 1) 3)
                     (node :ZK :ZV (node/leaf) (node/leaf) 1) 5) 7))]
    (is (= :BK (node/-k result)))
    (is (= :BV (node/-v result)))
    (is (= 7   (node/-x result)))
    (is (= :AK (node/-k (node/-l result))))
    (is (= 3   (node/-x (node/-l result))))
    (is (= :CK (node/-k (node/-r result))))
    (is (= 3   (node/-x (node/-r result))))
    (is (= :XK  (node/-k (node/-l (node/-l result)))))
    (is (= :Y1K (node/-k (node/-r (node/-l result)))))
    (is (= :Y2K (node/-k (node/-l (node/-r result)))))
    (is (= :ZK  (node/-k (node/-r (node/-r result)))))))

(deftest rotation-check:mutable-single-right
  (let [node node/->MutableSimpleNode
        result (mut/rotate-single-right!
                 (node :BK :BV
                   (node :AK :AV (node :XK :XV (node/leaf) (node/leaf) 1)
                                 (node :YK :YV (node/leaf) (node/leaf) 1) 3)
                   (node :ZK :XZ (node/leaf) (node/leaf) 1) 5))]
    (is (= :AK (node/-k result)))
    (is (= :AV (node/-v result)))
    (is (= 5   (node/-x result)))
    (is (= :XK (node/-k (node/-l result))))
    (is (= 1   (node/-x (node/-l result))))
    (is (= :BK (node/-k (node/-r result))))
    (is (= 3   (node/-x (node/-r result))))
    (is (= :YK (node/-k (node/-l (node/-r result)))))
    (is (= :ZK (node/-k (node/-r (node/-r result)))))))

(deftest rotation-check:mutable-double-right
  (let [node node/->MutableSimpleNode
        result (mut/rotate-double-right!
                 (node :CK :CV
                   (node :AK :AV (node :XK :XV (node/leaf) (node/leaf) 1)
                                 (node :BK :BV (node :Y1K :Y1V (node/leaf) (node/leaf) 1)
                                               (node :Y2K :Y2V (node/leaf) (node/leaf) 1) 3) 5)
                   (node :ZK :ZV (node/leaf) (node/leaf) 1) 7))]
    (is (= :BK (node/-k result)))
    (is (= :BV (node/-v result)))
    (is (= 7   (node/-x result)))
    (is (= :AK (node/-k (node/-l result))))
    (is (= 3   (node/-x (node/-l result))))
    (is (= :CK (node/-k (node/-r result))))
    (is (= 3   (node/-x (node/-r result))))
    (is (= :XK  (node/-k (node/-l (node/-l result)))))
    (is (= :Y1K (node/-k (node/-r (node/-l result)))))
    (is (= :Y2K (node/-k (node/-l (node/-r result)))))
    (is (= :ZK  (node/-k (node/-r (node/-r result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stitch Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mut-x1  [] (mut/node-singleton! (gensym) true))
(defn- mut-x3  [] (mut/node-create! (gensym) true (mut-x1) (mut-x1)))
(defn- mut-x7  [] (mut/node-create! (gensym) true (mut-x3) (mut-x3)))
(defn- mut-x15 [] (mut/node-create! (gensym) true (mut-x7) (mut-x7)))

(deftest stitch-check:mutable-single-left
  (let [n (mut/node-create! :root true (mut-x1) (mut-x7))]
    (is (= 9 (tree/node-size n)))
    (let [result (mut/node-stitch! n)]
      (is (= 9 (tree/node-size result)))
      (is (= :root (node/-k (node/-l result))))
      (is (= 5 (tree/node-size (node/-l result))))
      (is (= 3 (tree/node-size (node/-r result)))))))

(deftest stitch-check:mutable-single-right
  (let [n (mut/node-create! :root true (mut-x7) (mut-x1))]
    (is (= 9 (tree/node-size n)))
    (let [result (mut/node-stitch! n)]
      (is (= 9 (tree/node-size result)))
      (is (= :root (node/-k (node/-r result))))
      (is (= 5 (tree/node-size (node/-r result))))
      (is (= 3 (tree/node-size (node/-l result)))))))

(deftest stitch-check:mutable-double-left
  (let [node node/->MutableSimpleNode
        n (mut/node-create! :AK :AV
            (node :XK :XV (node/leaf) (node/leaf) 1)
            (node :CK :CV
              (node :BK :BV
                (node :Y1K :Y1V (node :Q1K :Q1V (node/leaf) (node/leaf) 1) (node/leaf) 2)
                (node :Y2K :Y2V (node :Q2K :Q2V (node/leaf) (node/leaf) 1) (node/leaf) 2) 5)
              (node :ZK :ZV (node/leaf) (node/leaf) 1) 7))]
    (let [result (mut/node-stitch! n)]
      (is (= :BK (node/-k result)))
      (is (= 9   (node/-x result)))
      (is (= :AK (node/-k (node/-l result))))
      (is (= 4   (node/-x (node/-l result))))
      (is (= :CK (node/-k (node/-r result))))
      (is (= 4   (node/-x (node/-r result)))))))

(deftest stitch-check:mutable-double-right
  (let [node node/->MutableSimpleNode
        n (mut/node-create! :CK :CV
            (node :AK :AV
              (node :XK :XV (node/leaf) (node/leaf) 1)
              (node :BK :BV
                (node :Y1K :Y1V (node :Q1K :Q1V (node/leaf) (node/leaf) 1) (node/leaf) 2)
                (node :Y2K :Y2V (node :Q2K :Q2V (node/leaf) (node/leaf) 1) (node/leaf) 2) 5) 7)
            (node :ZK :ZV (node/leaf) (node/leaf) 1))]
    (let [result (mut/node-stitch! n)]
      (is (= :BK (node/-k result)))
      (is (= 9   (node/-x result)))
      (is (= :AK (node/-k (node/-l result))))
      (is (= 4   (node/-x (node/-l result))))
      (is (= :CK (node/-k (node/-r result))))
      (is (= 4   (node/-x (node/-r result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Health Checks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-tree-health-check
  (doseq [size (take 21 (iterate #(* % 2) 1))]
    (is (tree/node-healthy? (make-mutable-string-tree size)))
    (is (tree/node-healthy? (make-mutable-integer-tree size)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Equivalence Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-persistent-equivalence-check
  (doseq [size [1 10 100 1000 10000]]
    (let [input     (shuffle (range size))
          mut-tree  (reduce mut/node-add! (node/leaf) input)
          pers-tree (reduce tree/node-add (node/leaf) input)]
      (is (= (map node/-k (tree/node-seq mut-tree))
             (map node/-k (tree/node-seq pers-tree))))
      (is (= (map node/-v (tree/node-seq mut-tree))
             (map node/-v (tree/node-seq pers-tree))))
      (is (= (tree/node-size mut-tree)
             (tree/node-size pers-tree))))))

(deftest mutable-node-seq-check
  (doseq [size [1 10 100 1000 10000]]
    (let [tree (make-mutable-integer-tree size)]
      (is (= (sort < (range size)) (map node/-k (tree/node-seq tree))))
      (is (= (sort > (range size)) (map node/-k (tree/node-seq-reverse tree)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node-add! / Node-remove! Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-add-remove-check
  (doseq [size [10 100 1000 10000]]
    (let [input (shuffle (range size))
          tree  (reduce mut/node-add! (node/leaf) input)]
      (is (= size (tree/node-size tree)))
      (is (tree/node-healthy? tree))
      ;; remove half the elements
      (let [to-remove (take (quot size 2) (shuffle (range size)))
            remaining (sort (remove (set to-remove) (range size)))
            result    (reduce mut/node-remove! tree to-remove)]
        (is (= (count remaining) (tree/node-size result)))
        (is (= remaining (map node/-k (tree/node-seq result))))
        (is (tree/node-healthy? result))))))

(deftest mutable-add-duplicate-check
  (let [tree (reduce mut/node-add! (node/leaf) [3 1 4 1 5 9 2 6 5 3 5])]
    (is (= [1 2 3 4 5 6 9] (map node/-k (tree/node-seq tree))))
    (is (tree/node-healthy? tree))))

(deftest mutable-remove-nonexistent-check
  (let [tree (reduce mut/node-add! (node/leaf) [1 2 3 4 5])]
    (is (= 5 (tree/node-size (mut/node-remove! tree 99))))
    (is (= [1 2 3 4 5] (map node/-k (tree/node-seq (mut/node-remove! tree 99)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversion Round-Trip Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest conversion-round-trip-check
  (doseq [size [1 10 100 1000 10000]]
    (let [input     (shuffle (range size))
          pers-tree (reduce tree/node-add (node/leaf) input)
          mut-tree  (mut/node->mutable pers-tree)
          back-pers (mut/node->persistent mut-tree)]
      ;; mutable tree has same traversal as persistent
      (is (= (map node/-k (tree/node-seq pers-tree))
             (map node/-k (tree/node-seq mut-tree))))
      ;; round-trip preserves structure
      (is (= (map node/-k (tree/node-seq pers-tree))
             (map node/-k (tree/node-seq back-pers))))
      (is (= (tree/node-size pers-tree)
             (tree/node-size back-pers)))
      (is (tree/node-healthy? mut-tree))
      (is (tree/node-healthy? back-pers)))))

(deftest mutable-to-persistent-type-check
  (let [input     (shuffle (range 100))
        mut-tree  (reduce mut/node-add! (node/leaf) input)
        pers-tree (mut/node->persistent mut-tree)]
    (is (instance? com.dean.interval_tree.tree.node.SimpleNode pers-tree))
    (is (instance? com.dean.interval_tree.tree.node.MutableSimpleNode mut-tree))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read-Only Operations on Mutable Trees
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mutable-node-find-check
  (doseq [size [1 10 100 1000 10000]]
    (let [tree (make-mutable-string-tree size)]
      (dotimes [_ 1000]
        (let [i (-> size rand-int str)]
          (is (= i (-> tree (tree/node-find i) node/-v))))))))

(deftest mutable-node-rank-nth-check
  (doseq [size [1 10 100 1000 10000]]
    (let [tree (make-mutable-integer-tree size)]
      (dotimes [_ 1000]
        (let [i (rand-int size)]
          (is (= i (node/-k (tree/node-nth tree i))))
          (is (= i (tree/node-rank tree i))))))))

(deftest mutable-node-fold-check
  (doseq [size [1 10 100 1000 10000]]
    (let [tree (make-mutable-integer-tree size)
          sum  (reduce + (range size))]
      (is (= sum (tree/node-fold-left
                   (fn [acc n] (+ acc (node/-k n))) 0 tree))))))
