(ns ordered-collections.kernel.chunk
  "PRopeChunk protocol extensions for the built-in chunk backends used by
  the rope kernel.

  The rope kernel operates on opaque 'chunks' whose concrete type varies by
  rope variant:

    Rope        — APersistentVector chunks (arbitrary Clojure values)
    StringRope  — java.lang.String chunks (UTF-16 code units)
    ByteRope    — byte[] chunks (raw bytes, unsigned 0–255 semantics)

  Each backend here provides the 13 primitive operations the kernel needs
  (length, slice, merge, nth, append, last, butlast, update, of,
  reduce-init, append-sb, splice, splice-split). The rope kernel dispatches
  through the PRopeChunk protocol so that rope-concat/split/splice/etc.
  are written once and work for every backend.

  PRopeChunk is strictly an internal dispatch table — nothing outside the
  kernel should depend on these methods directly. User-facing interop with
  Clojure built-in collections lives in `ordered-collections.types.interop`."
  (:require [ordered-collections.protocol :as proto]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; APersistentVector — generic Rope backend
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-type clojure.lang.APersistentVector
  proto/PRopeChunk
  (chunk-length [c] (.count ^clojure.lang.Counted c))
  (chunk-slice [c start end] (subvec c (int start) (int end)))
  (chunk-merge [c other] (into c other))
  (chunk-nth [c i] (.nth ^clojure.lang.Indexed c (unchecked-int i)))
  (chunk-append [c x] (conj c x))
  (chunk-last [c] (peek c))
  (chunk-butlast [c] (pop c))
  (chunk-update [c i x] (assoc c (int i) x))
  (chunk-of [_ x] [x])
  (chunk-reduce-init [c f init]
    (if (instance? clojure.lang.IReduceInit c)
      (.reduce ^clojure.lang.IReduceInit c f init)
      ;; SubVector fallback — indexed iteration
      (let [^clojure.lang.Indexed c c
            n (.count ^clojure.lang.Counted c)]
        (loop [i (int 0) acc init]
          (if (< i n)
            (let [ret (f acc (.nth c i))]
              (if (reduced? ret) @ret (recur (unchecked-inc-int i) ret)))
            acc)))))
  (chunk-append-sb [c ^java.lang.StringBuilder sb]
    (let [n (.count ^clojure.lang.Counted c)]
      (dotimes [i n]
        (.append sb (.nth ^clojure.lang.Indexed c (unchecked-int i))))))
  (chunk-splice [c start end replacement]
    (let [prefix (subvec c 0 (int start))
          suffix (subvec c (int end))]
      (if replacement
        (into (into prefix replacement) suffix)
        (into prefix suffix))))
  (chunk-splice-split [c start end replacement half]
    (let [si   (int start)
          ei   (int end)
          r    (or replacement [])
          h    (int half)
          rlen (count r)]
      (cond
        (<= h si)
        [(subvec c 0 h)
         (into (into (subvec c h si) r) (subvec c ei))]

        (<= h (+ si rlen))
        (let [roff (- h si)
              rv   (vec r)]
          [(into (subvec c 0 si) (subvec rv 0 roff))
           (into (subvec rv roff) (subvec c ei))])

        :else
        (let [soff (+ ei (- h si rlen))]
          [(into (into (subvec c 0 si) r) (subvec c ei soff))
           (subvec c soff)])))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String — StringRope backend
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-type String
  proto/PRopeChunk
  (chunk-length [c] (.length ^String c))
  (chunk-slice [c start end] (.substring ^String c (int start) (int end)))
  (chunk-merge [c other] (str c other))
  (chunk-nth [c i] (.charAt ^String c (unchecked-int i)))
  (chunk-append [c x] (str c (char x)))
  (chunk-last [c] (.charAt ^String c (unchecked-dec-int (.length ^String c))))
  (chunk-butlast [c] (.substring ^String c 0 (unchecked-dec-int (.length ^String c))))
  (chunk-update [c i x]
    (let [sb (StringBuilder. ^String c)]
      (.setCharAt sb (unchecked-int i) (char x))
      (.toString sb)))
  (chunk-of [_ x] (String/valueOf (char x)))
  (chunk-reduce-init [c f init]
    (let [^String s c
          n (.length s)]
      (loop [i (int 0) acc init]
        (if (< i n)
          (let [ret (f acc (.charAt s i))]
            (if (reduced? ret) @ret (recur (unchecked-inc-int i) ret)))
          acc))))
  (chunk-append-sb [c ^java.lang.StringBuilder sb]
    (.append sb ^String c))
  (chunk-splice [c start end replacement]
    (let [^String s c
          ^String r (or replacement "")
          si (int start)
          ei (int end)
          sb (StringBuilder. (+ (- (.length s) (- ei si)) (.length r)))]
      (.append sb s 0 si)
      (.append sb r)
      (.append sb s ei (.length s))
      (.toString sb)))
  (chunk-splice-split [c start end replacement half]
    (let [^String s c
          ^String r (or replacement "")
          si (int start)
          ei (int end)
          rl (.length r)
          sl (.length s)
          h  (int half)
          rhs-len (- (+ sl rl) (- ei si) h)]
      (cond
        ;; Split falls in prefix — left is a substring, right needs StringBuilder
        (<= h si)
        [(.substring s 0 h)
         (let [sb (StringBuilder. rhs-len)]
           (.append sb s h si)
           (.append sb r)
           (.append sb s ei sl)
           (.toString sb))]

        ;; Split falls in replacement — both need StringBuilder
        (<= h (+ si rl))
        (let [roff (- h si)]
          [(let [sb (StringBuilder. h)]
             (.append sb s 0 si)
             (.append sb r 0 roff)
             (.toString sb))
           (let [sb (StringBuilder. rhs-len)]
             (.append sb r roff rl)
             (.append sb s ei sl)
             (.toString sb))])

        ;; Split falls in suffix — left needs StringBuilder, right is a substring
        :else
        (let [soff (+ ei (- h si rl))]
          [(let [sb (StringBuilder. h)]
             (.append sb s 0 si)
             (.append sb r)
             (.append sb s ei soff)
             (.toString sb))
           (.substring s soff sl)])))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; byte[] — ByteRope backend
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend (Class/forName "[B")
  proto/PRopeChunk
  {:chunk-length    (fn [c] (alength ^bytes c))
   :chunk-slice     (fn [c start end]
                      (java.util.Arrays/copyOfRange ^bytes c (int start) (int end)))
   :chunk-merge     (fn [c other]
                      (let [^bytes a c, ^bytes b other
                            al (alength a), bl (alength b)
                            result (byte-array (+ al bl))]
                        (System/arraycopy a 0 result 0 al)
                        (System/arraycopy b 0 result al bl)
                        result))
   :chunk-nth       (fn [c i]
                      (bit-and (long (aget ^bytes c (unchecked-int i))) 0xFF))
   :chunk-append    (fn [c x]
                      (let [^bytes a c
                            n (alength a)
                            result (java.util.Arrays/copyOf a (unchecked-inc-int n))]
                        (aset result n (unchecked-byte (long x)))
                        result))
   :chunk-last      (fn [c]
                      (let [^bytes a c
                            n (alength a)]
                        (bit-and (long (aget a (unchecked-dec-int n))) 0xFF)))
   :chunk-butlast   (fn [c]
                      (let [^bytes a c]
                        (java.util.Arrays/copyOf a (unchecked-dec-int (alength a)))))
   :chunk-update    (fn [c i x]
                      (let [result (aclone ^bytes c)]
                        (aset result (unchecked-int i) (unchecked-byte (long x)))
                        result))
   :chunk-of        (fn [_ x]
                      (let [a (byte-array 1)]
                        (aset a 0 (unchecked-byte (long x)))
                        a))
   :chunk-reduce-init (fn [c f init]
                        (let [^bytes a c, n (alength a)]
                          (loop [i (int 0), acc init]
                            (if (< i n)
                              (let [ret (f acc (bit-and (long (aget a i)) 0xFF))]
                                (if (reduced? ret) @ret (recur (unchecked-inc-int i) ret)))
                              acc))))
   :chunk-append-sb (fn [c ^StringBuilder sb]
                      ;; Hex-encode bytes for textual display (bytes are not chars).
                      (let [^bytes a c, n (alength a)]
                        (dotimes [i n]
                          (let [b (bit-and (long (aget a i)) 0xFF)]
                            (.append sb (Character/forDigit (int (bit-shift-right b 4)) 16))
                            (.append sb (Character/forDigit (int (bit-and b 0xF)) 16))))))
   :chunk-splice    (fn [c start end replacement]
                      (let [^bytes s c
                            ^bytes r (if replacement replacement (byte-array 0))
                            si (int start), ei (int end)
                            sl (alength s), rl (alength r)
                            result (byte-array (+ (- sl (- ei si)) rl))]
                        (System/arraycopy s 0 result 0 si)
                        (when (pos? rl)
                          (System/arraycopy r 0 result si rl))
                        (System/arraycopy s ei result (+ si rl) (- sl ei))
                        result))
   :chunk-splice-split (fn [c start end replacement half]
                         (let [^bytes s c
                               ^bytes r (if replacement replacement (byte-array 0))
                               si (int start), ei (int end)
                               sl (alength s), rl (alength r)
                               h  (int half)
                               rhs-len (- (+ sl rl) (- ei si) h)]
                           (cond
                             ;; Split falls in prefix — left is a copyOfRange, right built from pieces
                             (<= h si)
                             [(java.util.Arrays/copyOfRange s 0 h)
                              (let [rhs (byte-array rhs-len)]
                                (System/arraycopy s h rhs 0 (- si h))
                                (when (pos? rl)
                                  (System/arraycopy r 0 rhs (- si h) rl))
                                (System/arraycopy s ei rhs (+ (- si h) rl) (- sl ei))
                                rhs)]

                             ;; Split falls within the replacement
                             (<= h (+ si rl))
                             (let [roff (- h si)
                                   lhs (byte-array h)
                                   rhs (byte-array rhs-len)]
                               (System/arraycopy s 0 lhs 0 si)
                               (when (pos? roff)
                                 (System/arraycopy r 0 lhs si roff))
                               (when (< roff rl)
                                 (System/arraycopy r roff rhs 0 (- rl roff)))
                               (System/arraycopy s ei rhs (- rl roff) (- sl ei))
                               [lhs rhs])

                             ;; Split falls in suffix
                             :else
                             (let [soff (+ ei (- h si rl))
                                   lhs (byte-array h)]
                               (System/arraycopy s 0 lhs 0 si)
                               (when (pos? rl)
                                 (System/arraycopy r 0 lhs si rl))
                               (System/arraycopy s ei lhs (+ si rl) (- soff ei))
                               [lhs (java.util.Arrays/copyOfRange s soff sl)])))) })
