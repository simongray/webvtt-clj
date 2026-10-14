(ns ^:no-doc dk.simongray.webvtt.impl.chars
  "The characters that the algorithms of WebVTT look for, and the runs of
  text between them.

  The primitive hints and the code units keep the scanning of the JVM
  unboxed, which dev/dk/simongray/webvtt/bench.cljc showed to matter.")

(defn length
  "The length of the text `s`."
  ^long [s]
  #?(:clj (.length ^String s) :cljs (.-length s)))

(defn char-at
  "The character at `i` in the text `s`, or nil past its end."
  [s ^long i]
  (when (< i (length s))
    (.charAt ^String s i)))

(defn code
  "The UTF-16 code unit of the character `c`."
  ^long [c]
  #?(:clj (int c) :cljs (.charCodeAt c 0)))

(defn code-at
  "The UTF-16 code unit at `i` in the text `s`, which must be in it."
  ^long [s ^long i]
  #?(:clj (int (.charAt ^String s i)) :cljs (.charCodeAt s i)))

;; a <= of three arguments is a variadic call that boxes them on the JVM
(defn between?
  "Whether the number `n` is from `lo` to `hi`."
  [^long lo ^long n ^long hi]
  (and (<= lo n) (<= n hi)))

;; Infra, ASCII digit
(defn digit?
  "Whether the character `c` is an ASCII digit."
  [c]
  (case c
    (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9) true
    false))

;; Infra, ASCII whitespace
(defn whitespace?
  "Whether the character `c` is ASCII whitespace: a tab, a line feed, a
  form feed, a carriage return or a space."
  [c]
  (case c
    (\tab \newline \formfeed \return \space) true
    false))

(defn run-end
  "The index of the first character from `i` in the text `s` that `pred`
  doesn't match, or the length of `s` when they all do."
  ^long [s ^long i pred]
  (let [n (length s)]
    (loop [i i]
      (if (and (< i n) (pred (.charAt ^String s i)))
        (recur (inc i))
        i))))

(defn digits-end
  "The index of the first character from `i` in the text `s` that isn't an
  ASCII digit, or the length of `s`."
  ^long [s ^long i]
  (let [n (length s)]
    (loop [i i]
      (if (and (< i n) (between? 48 (code-at s i) 57))
        (recur (inc i))
        i))))

;; Infra, "skip ASCII whitespace"
(defn skip-whitespace
  "The index of the first character from `i` in the text `s` that isn't
  ASCII whitespace."
  ^long [s ^long i]
  (let [n (length s)]
    (loop [i i]
      (if (and (< i n) (case (code-at s i) (9 10 12 13 32) true false))
        (recur (inc i))
        i))))

;; Infra, "split a string on ASCII whitespace"
(defn words
  "The runs of the text `s` between ASCII whitespace, or nil when it has
  none."
  [s]
  (re-seq #"[^\t\n\f\r ]+" s))

(defn code-point->string
  "The character of the code point `n` as a string."
  [n]
  #?(:clj  (String. (Character/toChars (int n)))
     :cljs (js/String.fromCodePoint n)))
