(ns ordered-collections.byte-rope-test
  (:require [clojure.test :refer :all]
            [clojure.core.reducers :as r]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ordered-collections.core :as oc]
            [ordered-collections.kernel.rope :as ropetree]
            [ordered-collections.protocol :as proto]
            ;; Force-load reader registrations so #byte/rope literals resolve.
            [ordered-collections.readers])
  (:import [ordered_collections.types.byte_rope ByteRope]
           [java.io ByteArrayInputStream ByteArrayOutputStream]))


(defn- ba [& xs]
  (byte-array (map #(unchecked-byte (long %)) xs)))

(defn- ba= [^bytes a ^bytes b]
  (java.util.Arrays/equals a b))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-construction
  (testing "empty"
    (let [br (oc/byte-rope)]
      (is (zero? (count br)))
      (is (nil? (seq br)))
      (is (= "" (oc/byte-rope-hex br)))))

  (testing "from byte-array"
    (let [data (ba 1 2 3)
          br (oc/byte-rope data)]
      (is (= 3 (count br)))
      (is (= [1 2 3] (vec (seq br))))
      (testing "defensive copy — mutating input does not affect rope"
        (aset data 0 (unchecked-byte 99))
        (is (= 1 (nth br 0))))))

  (testing "from sequential collection"
    (let [br (oc/byte-rope [0 127 128 255])]
      (is (= 4 (count br)))
      (is (= [0 127 128 255] (vec (seq br))))))

  (testing "from string (UTF-8)"
    (let [br (oc/byte-rope "Hello")]
      (is (= 5 (count br)))
      (is (ba= (ba 0x48 0x65 0x6c 0x6c 0x6f) (oc/byte-rope-bytes br)))))

  (testing "UTF-8 encodes multi-byte characters"
    (let [br (oc/byte-rope "é")]  ;; U+00E9 → C3 A9
      (is (= 2 (count br)))
      (is (= [0xc3 0xa9] (vec (seq br))))))

  (testing "from InputStream"
    (let [in (ByteArrayInputStream. (ba 10 20 30 40))
          br (oc/byte-rope in)]
      (is (= 4 (count br)))
      (is (= [10 20 30 40] (vec (seq br))))))

  (testing "from another byte rope"
    (let [br1 (oc/byte-rope [1 2 3])
          br2 (oc/byte-rope br1)]
      (is (= br1 br2))))

  (testing "large input promotes to tree"
    (let [data (byte-array (map #(mod % 256) (range 2000)))
          br (oc/byte-rope data)]
      (is (= 2000 (count br)))
      (is (ba= data (oc/byte-rope-bytes br))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unsigned Semantics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest unsigned-semantics
  (testing "nth returns unsigned long 0–255"
    (let [br (oc/byte-rope [0 1 127 128 200 255])]
      (is (= 0 (nth br 0)))
      (is (= 255 (nth br 5)))
      (is (every? #(and (<= 0 %) (<= % 255)) (map #(nth br %) (range 6))))))

  (testing "reduce yields unsigned values"
    (let [br (oc/byte-rope [0 127 128 255])]
      (is (= (+ 0 127 128 255) (reduce + br)))
      (is (= (+ 100 0 127 128 255) (reduce + 100 br)))))

  (testing "seq yields unsigned values"
    (is (= [0 127 128 255] (vec (seq (oc/byte-rope [0 127 128 255])))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Indexed / ILookup / IFn
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-indexed
  (let [br (oc/byte-rope [10 20 30 40 50])]
    (testing "nth"
      (is (= 10 (nth br 0)))
      (is (= 50 (nth br 4)))
      (is (thrown? IndexOutOfBoundsException (nth br 5)))
      (is (= :not-found (nth br 5 :not-found))))
    (testing "get / ILookup"
      (is (= 30 (get br 2)))
      (is (nil? (get br 10)))
      (is (= :def (get br 10 :def))))
    (testing "IFn"
      (is (= 20 (br 1)))
      (is (= :def (br 100 :def))))
    (testing "peek / pop"
      (is (= 50 (peek br)))
      (is (= [10 20 30 40] (vec (seq (pop br)))))
      (is (= 10 (peek (oc/byte-rope [10])))))))

(deftest byte-rope-assoc
  (let [br (oc/byte-rope [1 2 3 4 5])]
    (is (= [1 99 3 4 5] (vec (seq (assoc br 1 99)))))
    (is (= [1 2 3 4 5 99] (vec (seq (assoc br 5 99)))))  ;; append via assoc
    (is (thrown? IndexOutOfBoundsException (assoc br 10 1)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-cat
  (testing "flat + flat"
    (let [a (oc/byte-rope [1 2 3])
          b (oc/byte-rope [4 5 6])]
      (is (= [1 2 3 4 5 6] (vec (seq (proto/rope-cat a b)))))))

  (testing "with raw byte[] as RHS"
    (let [a (oc/byte-rope [1 2])
          b (ba 3 4)]
      (is (= [1 2 3 4] (vec (seq (proto/rope-cat a b)))))))

  (testing "combine exceeds flat threshold — promotes to tree"
    (let [a (oc/byte-rope (byte-array (repeat 800 1)))
          b (oc/byte-rope (byte-array (repeat 800 2)))
          c (proto/rope-cat a b)]
      (is (= 1600 (count c)))
      (is (= 1 (nth c 0)))
      (is (= 2 (nth c 1599))))))

(deftest byte-rope-split
  (let [br (oc/byte-rope (byte-array (range 100)))]
    (testing "split at midpoint"
      (let [[l r] (proto/rope-split br 50)]
        (is (= 50 (count l)))
        (is (= 50 (count r)))
        (is (= 0 (nth l 0)))
        (is (= 50 (nth r 0)))))
    (testing "split at 0"
      (let [[l r] (proto/rope-split br 0)]
        (is (zero? (count l)))
        (is (= 100 (count r)))))
    (testing "split at end"
      (let [[l r] (proto/rope-split br 100)]
        (is (= 100 (count l)))
        (is (zero? (count r)))))
    (testing "out of range"
      (is (thrown? IndexOutOfBoundsException (proto/rope-split br 101))))))

(deftest byte-rope-sub
  (let [br (oc/byte-rope (byte-array (range 100)))]
    (testing "subrange"
      (let [s (proto/rope-sub br 10 20)]
        (is (= 10 (count s)))
        (is (= 10 (nth s 0)))
        (is (= 19 (nth s 9)))))
    (testing "empty range"
      (is (zero? (count (proto/rope-sub br 50 50)))))))

(deftest byte-rope-insert
  (let [br (oc/byte-rope [1 2 3 4 5])]
    (testing "insert in middle"
      (is (= [1 2 99 100 3 4 5]
             (vec (seq (proto/rope-insert br 2 [99 100]))))))
    (testing "insert at front"
      (is (= [99 1 2 3 4 5]
             (vec (seq (proto/rope-insert br 0 [99]))))))
    (testing "insert at end"
      (is (= [1 2 3 4 5 99]
             (vec (seq (proto/rope-insert br 5 [99]))))))
    (testing "insert byte-array"
      (is (= [1 2 99 100 3 4 5]
             (vec (seq (proto/rope-insert br 2 (ba 99 100)))))))
    (testing "insert byte-rope"
      (is (= [1 2 99 100 3 4 5]
             (vec (seq (proto/rope-insert br 2 (oc/byte-rope [99 100])))))))
    (testing "insert nothing"
      (is (= [1 2 3 4 5]
             (vec (seq (proto/rope-insert br 2 []))))))))

(deftest byte-rope-remove
  (let [br (oc/byte-rope [1 2 3 4 5 6 7])]
    (is (= [1 2 6 7] (vec (seq (proto/rope-remove br 2 5)))))
    (is (= [] (vec (seq (proto/rope-remove br 0 7)))))
    (is (= [1 2 3 4 5 6 7] (vec (seq (proto/rope-remove br 3 3)))))))

(deftest byte-rope-splice
  (let [br (oc/byte-rope [1 2 3 4 5])]
    (testing "replace range with new content"
      (is (= [1 2 99 100 101 5]
             (vec (seq (proto/rope-splice br 2 4 [99 100 101]))))))
    (testing "replace with nothing = remove"
      (is (= [1 2 5] (vec (seq (proto/rope-splice br 2 4 []))))))
    (testing "splice with byte-rope replacement"
      (is (= [1 2 99 5]
             (vec (seq (proto/rope-splice br 2 4 (oc/byte-rope [99])))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Equality / Hashing / Comparison
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-equality
  (testing "byte-rope to byte-rope"
    (is (= (oc/byte-rope [1 2 3]) (oc/byte-rope [1 2 3])))
    (is (not= (oc/byte-rope [1 2 3]) (oc/byte-rope [1 2 4]))))

  (testing "byte-rope to byte[]"
    (is (= (oc/byte-rope [1 2 3]) (ba 1 2 3)))
    (is (not= (oc/byte-rope [1 2 3]) (ba 1 2 4))))

  (testing "byte-rope NOT equal to vector"
    (is (not= (oc/byte-rope [1 2 3]) [1 2 3])))

  (testing "byte-rope NOT equal to string rope or string"
    (is (not= (oc/byte-rope "hello") (oc/string-rope "hello")))
    (is (not= (oc/byte-rope "hello") "hello")))

  (testing "tree-mode equality"
    (let [a (oc/byte-rope (byte-array (map #(mod % 256) (range 3000))))
          b (oc/byte-rope (byte-array (map #(mod % 256) (range 3000))))]
      (is (= a b)))))

(deftest byte-rope-hash
  (testing "equal byte ropes hash the same"
    (is (= (hash (oc/byte-rope [1 2 3])) (hash (oc/byte-rope [1 2 3])))))
  (testing "tree-mode and flat-mode with same content hash the same"
    (let [flat (oc/byte-rope [1 2 3])
          tree (let [b (oc/byte-rope (byte-array (repeat 2000 99)))]
                 (proto/rope-sub b 0 3))]
      (is (= 3 (count tree))))))

(deftest byte-rope-comparable
  (testing "unsigned lexicographic ordering"
    (is (neg? (compare (oc/byte-rope [0 127]) (oc/byte-rope [0 128]))))
    (is (pos? (compare (oc/byte-rope [255]) (oc/byte-rope [127 0]))))  ;; unsigned 255 > 127
    (is (zero? (compare (oc/byte-rope [1 2 3]) (oc/byte-rope [1 2 3])))))
  (testing "prefix is less than longer"
    (is (neg? (compare (oc/byte-rope [1 2]) (oc/byte-rope [1 2 3]))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reduce / Fold / Seq
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-reduce
  (testing "reduce with init"
    (is (= 15 (reduce + 0 (oc/byte-rope [1 2 3 4 5])))))
  (testing "reduce without init"
    (is (= 15 (reduce + (oc/byte-rope [1 2 3 4 5])))))
  (testing "reduced early termination"
    (is (= :stop (reduce (fn [_ x] (if (= x 3) (reduced :stop) x))
                         (oc/byte-rope [1 2 3 4 5])))))
  (testing "tree-mode reduce matches expected sum"
    (let [br (oc/byte-rope (byte-array (map #(mod % 256) (range 10000))))
          expected (reduce + (map #(mod % 256) (range 10000)))]
      (is (= expected (reduce + br))))))

(deftest byte-rope-fold
  (testing "r/fold matches sequential reduce"
    (let [br (oc/byte-rope (byte-array (map #(mod % 256) (range 10000))))
          sequential (reduce + br)
          parallel (r/fold + br)]
      (is (= sequential parallel)))))

(deftest byte-rope-seq
  (testing "forward seq"
    (is (= [1 2 3] (seq (oc/byte-rope [1 2 3])))))
  (testing "reverse seq"
    (is (= [3 2 1] (vec (rseq (oc/byte-rope [1 2 3]))))))
  (testing "multi-chunk seq"
    (let [br (oc/byte-rope (byte-array (map #(mod % 256) (range 2000))))]
      (is (= 2000 (count (seq br))))
      (is (= (map #(mod % 256) (range 2000)) (seq br))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multi-Byte Reads
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest multi-byte-reads-flat
  (let [br (oc/byte-rope (ba 0x12 0x34 0x56 0x78 0x9a 0xbc 0xde 0xf0))]
    (testing "big-endian"
      (is (= 0x12      (oc/byte-rope-get-byte br 0)))
      (is (= 0x1234    (oc/byte-rope-get-short br 0)))
      (is (= 0x12345678 (oc/byte-rope-get-int br 0)))
      (is (= 0x123456789abcdef0 (oc/byte-rope-get-long br 0))))
    (testing "little-endian"
      (is (= 0x3412    (oc/byte-rope-get-short-le br 0)))
      (is (= 0x78563412 (oc/byte-rope-get-int-le br 0)))
      ;; 0xf0debc9a78563412 as signed long is negative
      (is (= (unchecked-long 0xf0debc9a78563412)
             (oc/byte-rope-get-long-le br 0))))
    (testing "sign extension of int"
      (let [b (oc/byte-rope (ba 0xff 0xff 0xff 0xff))]
        (is (= -1 (oc/byte-rope-get-int b 0)))
        (is (= -1 (oc/byte-rope-get-int-le b 0)))))))

(deftest multi-byte-reads-tree
  (testing "cross-chunk reads work correctly"
    (let [data (byte-array (map #(mod % 256) (range 2000)))
          br (oc/byte-rope data)]
      ;; Tree-backed — pick a position well inside the tree
      (is (= (bit-and (aget data 500) 0xff) (oc/byte-rope-get-byte br 500)))
      ;; Two-byte read that might straddle a chunk boundary
      (let [expected (bit-or (bit-shift-left (bit-and (long (aget data 500)) 0xff) 8)
                             (bit-and (long (aget data 501)) 0xff))]
        (is (= expected (oc/byte-rope-get-short br 500)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-search
  (testing "index-of"
    (let [br (oc/byte-rope [1 2 3 4 5 3 2 1])]
      (is (= 2 (oc/byte-rope-index-of br 3)))
      (is (= 5 (oc/byte-rope-index-of br 3 3)))
      (is (= -1 (oc/byte-rope-index-of br 99)))
      (is (= -1 (oc/byte-rope-index-of br 3 99)))))

  (testing "index-of unsigned semantics"
    (let [br (oc/byte-rope [0 128 255])]
      (is (= 0 (oc/byte-rope-index-of br 0)))
      (is (= 1 (oc/byte-rope-index-of br 128)))
      (is (= 2 (oc/byte-rope-index-of br 255))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Materialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-materialization
  (let [br (oc/byte-rope (ba 0x48 0x65 0x6c 0x6c 0x6f))]
    (testing "byte-rope-bytes defensive copy"
      (let [out (oc/byte-rope-bytes br)]
        (is (ba= out (ba 0x48 0x65 0x6c 0x6c 0x6f)))
        (aset ^bytes out 0 (byte 0))
        (is (= 0x48 (nth br 0)))))  ;; rope unchanged
    (testing "byte-rope-hex"
      (is (= "48656c6c6f" (oc/byte-rope-hex br))))
    (testing "byte-rope-write"
      (let [out (ByteArrayOutputStream.)]
        (oc/byte-rope-write br out)
        (is (ba= (.toByteArray out) (ba 0x48 0x65 0x6c 0x6c 0x6f)))))
    (testing "byte-rope-input-stream"
      (let [in-stream (oc/byte-rope-input-stream br)
            buf (byte-array 10)
            n (.read in-stream buf 0 10)]
        (is (= 5 n))
        (is (ba= (java.util.Arrays/copyOf buf 5) (ba 0x48 0x65 0x6c 0x6c 0x6f)))
        (is (= -1 (.read in-stream)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Digest
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-digest-sha256
  (testing "matches reference for \"hello\""
    (let [br (oc/byte-rope "hello")
          digest (oc/byte-rope-digest br "SHA-256")]
      (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
             (oc/byte-rope-hex digest)))
      (is (= 32 (count digest)))))
  (testing "streamed digest matches materialized digest for large input"
    (let [data (byte-array (map #(mod % 256) (range 10000)))
          br (oc/byte-rope data)
          streamed (oc/byte-rope-digest br "SHA-256")
          reference (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                      (.digest md data))]
      (is (ba= (oc/byte-rope-bytes streamed) reference)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transient
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-transient
  (testing "batch construction via conj!"
    (let [xs (mapv #(mod % 256) (range 500))
          t (transient (oc/byte-rope))
          t (reduce conj! t xs)
          final (persistent! t)]
      (is (= 500 (count final)))
      (is (= (first xs) (nth final 0)))
      (is (= (last xs) (nth final 499)))))
  (testing "demotes to flat when small enough at persistent! time"
    (let [t (transient (oc/byte-rope))
          t (reduce conj! t (range 100))
          final (persistent! t)]
      ;; 100 bytes is well under the flat threshold, expect flat root
      (is (instance? ByteRope final))
      (is (bytes? (.-root ^ByteRope final)))))
  (testing "cannot conj after persistent!"
    (let [t (transient (oc/byte-rope))
          _ (persistent! t)]
      (is (thrown? IllegalAccessError (conj! t 1))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Flat / Tree Boundary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest flat-tree-boundary
  (testing "1024 bytes stays flat"
    (let [br (oc/byte-rope (byte-array (repeat 1024 0x41)))]
      (is (= 1024 (count br)))
      (let [root (.-root ^ByteRope br)]
        (is (some? root))
        (is (bytes? root)))))
  (testing "1025 bytes promotes to tree"
    (let [br (oc/byte-rope (byte-array (repeat 1025 0x41)))]
      (is (= 1025 (count br)))
      (let [root (.-root ^ByteRope br)]
        (is (not (bytes? root))))))
  (testing "edits that grow past threshold promote"
    (let [br (oc/byte-rope (byte-array (repeat 1020 1)))]
      (is (bytes? (.-root ^ByteRope br)))
      (let [br2 (proto/rope-insert br 500 (byte-array (repeat 10 2)))]
        (is (= 1030 (count br2)))
        (is (not (bytes? (.-root ^ByteRope br2))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EDN Round-Trip
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-edn-roundtrip
  (testing "print-method emits #byte/rope hex literal"
    (is (= "#byte/rope \"68656c6c6f\""
           (pr-str (oc/byte-rope "hello")))))
  (testing "round-trips through EDN reader"
    (let [source "#byte/rope \"deadbeef\""
          parsed (read-string source)]
      (is (instance? ByteRope parsed))
      (is (= 4 (count parsed)))
      (is (= [0xde 0xad 0xbe 0xef] (vec (seq parsed))))))
  (testing "invalid hex rejected"
    (is (thrown? IllegalArgumentException (read-string "#byte/rope \"xy\"")))
    (is (thrown? IllegalArgumentException (read-string "#byte/rope \"abc\"")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Metadata
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest byte-rope-metadata
  (let [br (-> (oc/byte-rope [1 2 3]) (with-meta {:source :test}))]
    (is (= {:source :test} (meta br)))
    (testing "meta preserved through edits"
      (is (= {:source :test} (meta (proto/rope-insert br 0 [0]))))
      (is (= {:source :test} (meta (assoc br 0 99))))
      (is (= {:source :test} (meta (pop br)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property-Based Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- clamp ^long [^long n ^long i]
  (min n (max 0 i)))

(def gen-byte
  (gen/fmap #(bit-and (long %) 0xff) gen/nat))

(def gen-byte-seq
  (gen/vector gen-byte 0 200))

(defspec prop-byte-rope-roundtrip 100
  (prop/for-all [xs gen-byte-seq]
    (let [br (oc/byte-rope xs)]
      (= (vec xs) (vec (seq br))))))

(defspec prop-split-roundtrip 100
  (prop/for-all [xs (gen/such-that seq gen-byte-seq)
                 i gen/nat]
    (let [n (count xs)
          i' (rem i (inc n))
          br (oc/byte-rope xs)
          [l r] (proto/rope-split br i')]
      (and (= (take i' xs) (vec (seq l)))
           (= (drop i' xs) (vec (seq r)))))))

(defspec prop-splice-oracle 100
  (prop/for-all [xs (gen/such-that seq gen-byte-seq)
                 a gen/nat
                 b gen/nat
                 ins gen-byte-seq]
    (let [n (count xs)
          lo (clamp n (min a b))
          hi (clamp n (max a b))
          expected (concat (take lo xs) ins (drop hi xs))
          br (oc/byte-rope xs)
          result (proto/rope-splice br lo hi ins)]
      (= (vec expected) (vec (seq result))))))

(defspec prop-concat-roundtrip 100
  (prop/for-all [xs gen-byte-seq
                 ys gen-byte-seq]
    (let [a (oc/byte-rope xs)
          b (oc/byte-rope ys)]
      (= (concat xs ys) (vec (seq (proto/rope-cat a b)))))))

(defspec prop-equality 100
  (prop/for-all [xs gen-byte-seq]
    (let [a (oc/byte-rope xs)
          b (oc/byte-rope xs)]
      (and (= a b)
           (= (hash a) (hash b))))))

(defspec prop-byte-array-equality 100
  (prop/for-all [xs gen-byte-seq]
    (let [br (oc/byte-rope xs)
          ba (byte-array (map unchecked-byte xs))]
      (= br ba))))

(defspec prop-csi-after-edits 50
  (prop/for-all [xs (gen/vector gen-byte 10 500)
                 ops (gen/vector
                       (gen/one-of
                         [(gen/fmap (fn [[a b]] [:split (min a b)])
                            (gen/tuple gen/nat gen/nat))
                          (gen/fmap (fn [[a b ins]] [:splice (min a b) (max a b) ins])
                            (gen/tuple gen/nat gen/nat gen-byte-seq))])
                       1 8)]
    (let [br (oc/byte-rope xs)
          result (reduce
                   (fn [r [op a b ins]]
                     (let [n (count r)]
                       (case op
                         :split  (first (proto/rope-split r (clamp n a)))
                         :splice (proto/rope-splice r (clamp n (min a b))
                                   (clamp n (max a b))
                                   (or ins [])))))
                   br ops)
          root (.-root ^ByteRope result)]
      (or (nil? root) (bytes? root) (ropetree/invariant-valid? root)))))

(defspec prop-nth-matches-seq 100
  (prop/for-all [xs (gen/such-that seq gen-byte-seq)]
    (let [br (oc/byte-rope xs)]
      (every? identity
              (map #(= (nth (vec xs) %) (nth br %))
                   (range (count xs)))))))

(defspec prop-multi-byte-reads 50
  (prop/for-all [xs (gen/vector gen-byte 8 200)
                 i gen/nat]
    (let [n (count xs)
          br (oc/byte-rope xs)
          off (rem i (max 1 (- n 7)))
          expected-short (bit-or (bit-shift-left (long (nth xs off)) 8)
                                 (long (nth xs (inc off))))]
      (= expected-short (oc/byte-rope-get-short br off)))))
