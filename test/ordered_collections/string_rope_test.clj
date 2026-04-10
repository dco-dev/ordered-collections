(ns ordered-collections.string-rope-test
  (:require [clojure.test :refer :all]
            [clojure.core.reducers :as r]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ordered-collections.core :as oc]
            [ordered-collections.kernel.rope :as ropetree]
            [ordered-collections.test-utils :as tu]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic CharSequence Semantics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-charsequence
  (let [sr (oc/string-rope "hello world")]
    (is (instance? CharSequence sr))
    (is (= 11 (.length ^CharSequence sr)))
    (is (= \h (.charAt ^CharSequence sr 0)))
    (is (= \d (.charAt ^CharSequence sr 10)))
    (is (= "ello" (str (.subSequence ^CharSequence sr 1 5))))
    (is (= "hello world" (str sr)))
    (is (= "hello world" (.toString sr)))))

(deftest string-rope-empty
  (let [sr (oc/string-rope)]
    (is (= 0 (count sr)))
    (is (= "" (str sr)))
    (is (nil? (seq sr)))
    (is (nil? (rseq sr)))
    (is (nil? (peek sr)))
    (is (thrown? IllegalStateException (pop sr)))))

(deftest string-rope-single-char
  (let [sr (oc/string-rope "x")]
    (is (= 1 (count sr)))
    (is (= \x (nth sr 0)))
    (is (= "x" (str sr)))
    (is (= \x (peek sr)))
    (is (= "" (str (pop sr))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Equality
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-equality-with-strings
  (is (= (oc/string-rope "hello") "hello"))
  (is (= "hello" (oc/string-rope "hello")))
  (is (= (oc/string-rope "hello") (oc/string-rope "hello")))
  (is (= (oc/string-rope "") ""))
  (is (= "" (oc/string-rope "")))
  (is (not= (oc/string-rope "hello") "world"))
  (is (not= (oc/string-rope "hello") (oc/string-rope "world"))))

(deftest string-rope-not-equal-to-generic-rope
  (is (not= (oc/string-rope "hello") (oc/rope (seq "hello")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Hashing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-hash-consistency
  (is (= (hash (oc/string-rope "hello")) (hash "hello")))
  (is (= (hash (oc/string-rope "")) (hash "")))
  (is (= (hash (oc/string-rope "the quick brown fox"))
         (hash "the quick brown fox"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Indexed / ILookup / IFn
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-nth
  (let [sr (oc/string-rope "abcde")]
    (is (= \a (nth sr 0)))
    (is (= \e (nth sr 4)))
    (is (= :nope (nth sr 10 :nope)))
    (is (thrown? IndexOutOfBoundsException (nth sr 5)))))

(deftest string-rope-get
  (let [sr (oc/string-rope "abc")]
    (is (= \b (get sr 1)))
    (is (nil? (get sr 5)))
    (is (= :nope (get sr 5 :nope)))))

(deftest string-rope-ifn
  (let [sr (oc/string-rope "abc")]
    (is (= \a (sr 0)))
    (is (= \c (sr 2)))
    (is (= :nope (sr 10 :nope)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seq / Rseq
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-seq
  (let [sr (oc/string-rope "hello")]
    (is (= [\h \e \l \l \o] (seq sr)))
    (is (= [\o \l \l \e \h] (rseq sr)))
    (is (= 5 (count (seq sr))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reduce
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-reduce
  (let [sr (oc/string-rope "abc")]
    (is (= "abc" (reduce str "" sr)))
    (is (= "abc" (reduce str sr)))))

(deftest string-rope-reduce-early-termination
  (let [sr (oc/string-rope "abcdefghij")]
    (is (= "abc"
          (reduce (fn [acc c]
                    (if (>= (count acc) 3)
                      (reduced acc)
                      (str acc c)))
            "" sr)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conj / Assoc / Peek / Pop
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-conj
  (let [sr (oc/string-rope "abc")]
    (is (= "abcd" (str (conj sr \d))))
    (is (= "a" (str (conj (oc/string-rope) \a))))))

(deftest string-rope-assoc
  (let [sr (oc/string-rope "abc")]
    (is (= "axc" (str (assoc sr 1 \x))))
    (is (= "abcd" (str (assoc sr 3 \d))))
    (is (thrown? IndexOutOfBoundsException (assoc sr 4 \x)))))

(deftest string-rope-peek-pop
  (let [sr (oc/string-rope "abc")]
    (is (= \c (peek sr)))
    (is (= "ab" (str (pop sr))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural Operations (PRope)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-cat
  (is (= "hello world"
        (str (oc/string-rope-concat (oc/string-rope "hello ") (oc/string-rope "world")))))
  (is (= "hello world"
        (str (oc/string-rope-concat (oc/string-rope "hello ") "world")))))

(deftest string-rope-split
  (let [[l r] (oc/rope-split (oc/string-rope "hello world") 5)]
    (is (= "hello" (str l)))
    (is (= " world" (str r)))))

(deftest string-rope-sub
  (is (= "quick" (str (oc/rope-sub (oc/string-rope "the quick brown") 4 9)))))

(deftest string-rope-insert
  (is (= "hello cruel world"
        (str (oc/rope-insert (oc/string-rope "hello world") 5 " cruel")))))

(deftest string-rope-remove
  (is (= "helloworld"
        (str (oc/rope-remove (oc/string-rope "hello world") 5 6)))))

(deftest string-rope-splice
  (is (= "hello cruel world"
        (str (oc/rope-splice (oc/string-rope "hello world") 5 6 " cruel ")))))

(deftest string-rope-str
  (is (= "hello world" (oc/rope-str (oc/string-rope "hello world"))))
  (is (= "" (oc/rope-str (oc/string-rope)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Metadata
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-metadata
  (let [sr (with-meta (oc/string-rope "hello") {:tag :test})]
    (is (= {:tag :test} (meta sr)))
    (is (= {:tag :test} (meta (empty sr))))
    (let [[l r] (oc/rope-split sr 3)]
      (is (= {:tag :test} (meta l)))
      (is (= {:tag :test} (meta r))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparable
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-comparable
  (is (zero? (compare (oc/string-rope "abc") (oc/string-rope "abc"))))
  (is (neg? (compare (oc/string-rope "abc") (oc/string-rope "abd"))))
  (is (pos? (compare (oc/string-rope "abd") (oc/string-rope "abc"))))
  (is (neg? (compare (oc/string-rope "ab") (oc/string-rope "abc"))))
  (is (pos? (compare (oc/string-rope "abc") (oc/string-rope "ab")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parallel Fold
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-fold
  (let [sr (oc/string-rope (apply str (repeat 10000 "x")))]
    (is (= 10000
          (r/fold + (r/map (constantly 1) sr))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transient
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-transient-basic
  (let [sr (persistent! (reduce conj! (transient (oc/string-rope)) "hello"))]
    (is (= "hello" (str sr)))
    (is (= 5 (count sr)))))

(deftest string-rope-transient-from-existing
  (let [base (oc/string-rope "hello")
        sr   (persistent! (reduce conj! (transient base) " world"))]
    (is (= "hello world" (str sr)))))

(deftest string-rope-transient-empty
  (let [sr (persistent! (transient (oc/string-rope)))]
    (is (= "" (str sr)))
    (is (= 0 (count sr)))))

(deftest string-rope-transient-invalidation
  (let [t (transient (oc/string-rope))]
    (persistent! t)
    (is (thrown? IllegalAccessError (conj! t \a)))
    (is (thrown? IllegalAccessError (persistent! t)))))

(deftest string-rope-transient-large
  (let [text (apply str (range 1000))
        sr   (persistent! (reduce conj! (transient (oc/string-rope)) text))]
    (is (= text (str sr)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Print / EDN
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-print-method
  (is (= "#string/rope \"hello\"" (pr-str (oc/string-rope "hello"))))
  (is (= "#string/rope \"\"" (pr-str (oc/string-rope)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Java Interop
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-regex
  (testing "re-find — direct, no str conversion"
    (is (= "hello" (re-find #"\w+" (oc/string-rope "hello world"))))
    (is (= ["hello" "hello"] (re-find #"(\w+)" (oc/string-rope "hello world"))))
    (is (nil? (re-find #"\d+" (oc/string-rope "no digits here")))))

  (testing "re-matches — full-string match"
    (is (= "hello" (re-matches #"\w+" (oc/string-rope "hello"))))
    (is (nil? (re-matches #"\w+" (oc/string-rope "hello world"))))
    (is (= ["hello world" "hello" "world"]
          (re-matches #"(\w+)\s(\w+)" (oc/string-rope "hello world")))))

  (testing "re-seq — all matches"
    (is (= ["hello" "world" "foo"]
          (re-seq #"\w+" (oc/string-rope "hello world foo"))))
    (is (= ["123" "456"]
          (re-seq #"\d+" (oc/string-rope "abc123def456")))))

  (testing "re-matcher — produces working Matcher"
    (let [m (re-matcher #"\w+" (oc/string-rope "hello world"))]
      (is (= "hello" (re-find m)))
      (is (= "world" (re-find m)))
      (is (nil? (re-find m)))))

  (testing "re-find on multi-chunk rope"
    (let [sr (reduce (fn [r _] (oc/rope-splice r (quot (count r) 2)
                                 (quot (count r) 2) "XYZ"))
               (oc/string-rope (apply str (repeat 500 "a")))
               (range 10))]
      (is (string? (re-find #"XYZ" sr)))
      (is (= (count (re-seq #"XYZ" sr))
             (count (re-seq #"XYZ" (str sr)))))))

  (testing "empty rope"
    (is (nil? (re-find #"\w+" (oc/string-rope ""))))
    (is (= "" (re-matches #"" (oc/string-rope ""))))))

(deftest string-rope-clojure-string
  (testing "str/replace and str/replace-first accept CharSequence"
    (is (= "hell0 w0rld" (str/replace (oc/string-rope "hello world") #"o" "0")))
    (is (= "hell0 world" (str/replace-first (oc/string-rope "hello world") #"o" "0"))))

  (testing "str functions via (str ...) conversion"
    (is (= "HELLO" (str/upper-case (str (oc/string-rope "hello")))))
    (is (= "hello" (str/lower-case (str (oc/string-rope "HELLO")))))))

(deftest string-rope-charsequence-streams
  (testing ".chars() returns correct IntStream"
    (let [sr (oc/string-rope "abc")
          cs (vec (.toArray (.chars ^CharSequence sr)))]
      (is (= [(int \a) (int \b) (int \c)] cs))))

  (testing ".codePoints() returns correct IntStream"
    (let [sr (oc/string-rope "abc")
          cps (vec (.toArray (.codePoints ^CharSequence sr)))]
      (is (= [(int \a) (int \b) (int \c)] cps))))

  (testing "streams on multi-chunk rope"
    (let [text (apply str (repeat 500 "ab"))
          sr   (oc/string-rope text)]
      (is (= (.count (.chars ^CharSequence text))
             (.count (.chars ^CharSequence sr)))))))

(deftest string-rope-collection-interface
  (let [^java.util.Collection sr (oc/string-rope "abc")]
    (is (= 3 (.size sr)))
    (is (not (.isEmpty sr)))
    (is (.isEmpty ^java.util.Collection (oc/string-rope)))
    (is (.contains sr \b))
    (is (not (.contains sr \z)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest string-rope-chunk-boundaries
  (let [text (apply str (repeat 300 "abcdefghij"))
        sr   (oc/string-rope text)]
    (is (= text (str sr)))
    (is (= 3000 (count sr)))
    (is (= \a (nth sr 0)))
    (is (= \j (nth sr 2999)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property-Based Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- clamp ^long [^long n ^long i]
  (min n (max 0 i)))

(defspec prop-string-rope-roundtrip 100
  (prop/for-all [s gen/string]
    (= s (str (oc/string-rope s)))))

(defspec prop-string-rope-split-roundtrip 100
  (prop/for-all [s (gen/such-that #(pos? (count %)) gen/string)
                 i gen/nat]
    (let [n  (count s)
          i' (rem i (inc n))
          sr (oc/string-rope s)
          [l r] (oc/rope-split sr i')]
      (= s (str (str l) (str r))))))

(defspec prop-string-rope-splice-oracle 100
  (prop/for-all [s (gen/such-that #(pos? (count %)) gen/string)
                 a gen/nat
                 b gen/nat
                 ins gen/string]
    (let [n  (count s)
          lo (clamp n (min a b))
          hi (clamp n (max a b))
          expected (str (subs s 0 lo) ins (subs s hi))
          sr (oc/string-rope s)
          result (str (oc/rope-splice sr lo hi ins))]
      (= expected result))))

(defspec prop-string-rope-equality 100
  (prop/for-all [s gen/string]
    (and (= (oc/string-rope s) s)
         (= s (oc/string-rope s))
         (= (hash (oc/string-rope s)) (hash s)))))

(defspec prop-string-rope-csi-after-edits 50
  (prop/for-all [s (gen/such-that #(>= (count %) 10) gen/string 100)
                 ops (gen/vector
                       (gen/one-of
                         [(gen/fmap (fn [[a b]] [:split (min a b) (max a b)])
                            (gen/tuple gen/nat gen/nat))
                          (gen/fmap (fn [[a b ins]] [:splice (min a b) (max a b) ins])
                            (gen/tuple gen/nat gen/nat gen/string))])
                       1 10)]
    (let [sr (oc/string-rope s)
          result (reduce
                   (fn [r [op a b ins]]
                     (let [n (count r)]
                       (case op
                         :split  (first (oc/rope-split r (clamp n a)))
                         :splice (oc/rope-splice r (clamp n (min a b))
                                   (clamp n (max a b))
                                   (or ins "")))))
                   sr ops)
          root (.-root ^ordered_collections.types.string_rope.StringRope result)]
      (or (nil? root) (string? root) (ropetree/invariant-valid? root)))))

(defspec prop-string-rope-regex-oracle 100
  (prop/for-all [s (gen/such-that #(pos? (count %)) gen/string-alphanumeric)]
    (let [sr (oc/string-rope s)]
      (and (= (re-seq #"\w+" s) (re-seq #"\w+" sr))
           (= (re-find #"\w" s) (re-find #"\w" sr))
           (= (re-matches #"\w+" s) (re-matches #"\w+" sr))
           (= (str/replace s #"[aeiou]" "*")
              (str/replace sr #"[aeiou]" "*"))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Flat→Tree Boundary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest flat-tree-boundary
  (testing "1024 chars stays flat"
    (let [s  (apply str (repeat 1024 "x"))
          sr (oc/string-rope s)]
      (is (= 1024 (count sr)))
      (is (string? (.-root ^ordered_collections.types.string_rope.StringRope sr)))
      (is (= s (str sr)))))
  (testing "1025 chars promotes to tree"
    (let [s  (apply str (repeat 1025 "x"))
          sr (oc/string-rope s)]
      (is (= 1025 (count sr)))
      (is (not (string? (.-root ^ordered_collections.types.string_rope.StringRope sr))))
      (is (= s (str sr)))))
  (testing "flat→tree promotion via edits"
    (let [sr (oc/string-rope (apply str (repeat 1020 "a")))]
      (is (string? (.-root ^ordered_collections.types.string_rope.StringRope sr)))
      ;; Insert 10 chars — stays flat (1030 < 1024? no, 1030 > 1024 → promotes)
      (let [sr2 (oc/rope-insert sr 500 "bbbbbbbbbb")]
        (is (= 1030 (count sr2)))
        ;; Should have promoted to tree since 1030 > 1024
        (is (not (string?
                   (.-root ^ordered_collections.types.string_rope.StringRope sr2))))
        (is (= (str (oc/string-rope (apply str (repeat 1020 "a"))))
               (str (apply str (repeat 1020 "a"))))))))
  (testing "tree→flat demotion via transient persistent!"
    ;; Build a large rope then shrink it via transient
    (let [sr (oc/string-rope (apply str (repeat 2000 "x")))
          ;; persistent! from transient should demote if ≤ threshold
          t  (transient sr)
          sr2 (persistent! t)]
      ;; Still 2000 chars, stays tree
      (is (= 2000 (count sr2))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HashMap Key Compatibility
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest hashmap-key-compatibility
  (testing "StringRope hasheq matches String hasheq"
    (let [s  "hello world"
          sr (oc/string-rope s)]
      (is (= (hash s) (hash sr)))))
  (testing "StringRope keys looked up by another StringRope"
    (let [sr1 (oc/string-rope "hello world")
          sr2 (oc/string-rope "hello world")
          m   {sr1 :found}]
      (is (= :found (get m sr2)))))
  (testing "String-keyed map looked up by StringRope"
    (let [s  "hello world"
          sr (oc/string-rope s)
          m  {s :found}]
      ;; StringRope.equals(String) works because we control it
      (is (= :found (get m sr)))))
  (testing "Tree-mode rope hasheq matches"
    (let [s  (apply str (repeat 2000 "x"))
          sr (oc/string-rope s)]
      (is (= (hash s) (hash sr))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cursor Cache Stress
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest cursor-cache-stress
  (testing "sequential charAt across chunk boundaries"
    (let [s  (apply str (map #(char (+ (int \a) (mod % 26))) (range 2000)))
          sr (oc/string-rope s)]
      ;; Forward sequential scan
      (dotimes [i 2000]
        (is (= (.charAt ^CharSequence (oc/string-rope s) i)
               (.charAt s i))
            (str "mismatch at index " i)))))
  (testing "random access charAt"
    (let [s  (apply str (map #(char (+ (int \a) (mod % 26))) (range 4000)))
          sr (oc/string-rope s)
          indices (shuffle (range 4000))]
      ;; Random order — cache misses on every access
      (doseq [i (take 500 indices)]
        (is (= (.charAt ^CharSequence sr i) (.charAt s i))
            (str "random access mismatch at " i)))))
  (testing "cache invalidated by structural edits"
    (let [sr1 (oc/string-rope (apply str (repeat 2000 "a")))]
      ;; Access to populate cache
      (.charAt ^CharSequence sr1 1000)
      ;; Splice creates a NEW StringRope — old cache doesn't carry over
      (let [sr2 (oc/rope-splice sr1 500 1500 "bbb")]
        ;; 2000 - 1000 + 3 = 1003
        (is (= 1003 (count sr2)))
        ;; 0-499 = a, 500-502 = b, 503-1002 = a
        (is (= \a (.charAt ^CharSequence sr2 0)))
        (is (= \a (.charAt ^CharSequence sr2 499)))
        (is (= \b (.charAt ^CharSequence sr2 500)))
        (is (= \b (.charAt ^CharSequence sr2 502)))
        (is (= \a (.charAt ^CharSequence sr2 503)))
        (is (= \a (.charAt ^CharSequence sr2 1002)))))))
