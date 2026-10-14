(ns ^:no-doc dk.simongray.webvtt.impl.entities
  "The character references of HTML, which cue text and its annotations
  decode (WebVTT, section 6.4, and HTML, sections 13.2.5.77 to 13.2.5.84).
  WebVTT never reads them as part of an attribute.

  The named references are the table of section 13.5 of the HTML
  Standard, in resources, which dev/dk/simongray/webvtt/tables.clj makes
  from WHATWG's entities.json (under CC BY 4.0). It's read on first use."
  #?(:cljs (:require-macros [dk.simongray.webvtt.impl.entities :refer [table-text]]))
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            [dk.simongray.webvtt.impl.chars :as chars]))

#?(:clj
   ;; ClojureScript carries the table as a string literal, which the JVM
   ;; reads from resources when it's evaluated
   (defmacro table-text
     "The text of the table of named character references."
     []
     (let [path "dk/simongray/webvtt/entities.txt"]
       (if (:ns &env)
         (slurp (io/resource path))
         `(slurp (io/resource ~path))))))

(defn base-36
  "The number of the text `s` in base 36."
  [s]
  #?(:clj (Long/parseLong s 36) :cljs (js/parseInt s 36)))

(defn group
  "The first code point of a `line` of the table, which follows a line
  whose first code point is `previous`, and the names of its group with
  the characters that they stand for, as a pair (see
  dk.simongray.webvtt.tables for the format)."
  [previous line]
  (let [[codes & names] (str/split line #" ")
        [delta & more]  (map base-36 (str/split codes #","))
        code-point      (+ previous delta)
        characters      (apply str (map chars/code-point->string (cons code-point more)))]
    [code-point (map #(vector (str % ";") characters) names)]))

(def table
  "The named character references, as a map of:

  - :names, the characters of each name without its ampersand, so both
    amp; and the legacy amp give &
  - :longest, the length of the longest name
  - :longest-legacy, the length of the longest name without a semicolon"
  (delay
    (let [[legacy & lines] (str/split-lines (table-text))
          legacy           (str/split legacy #" ")
          named            (second (reduce (fn [[previous acc] line]
                                             (let [[code-point entries] (group previous line)]
                                               [code-point (into acc entries)]))
                                           [0 {}]
                                           lines))
          names            (into named (map #(vector % (get named (str % ";")))) legacy)]
      {:names          names
       :longest        (apply max (map count (keys names)))
       :longest-legacy (apply max (map count legacy))})))

;; Infra, ASCII alphanumeric and ASCII hex digit
(defn alphanumeric?
  "Whether the character `c` is an ASCII letter or digit."
  [c]
  (boolean (when c
             (let [n (chars/code c)]
               (or (chars/between? 48 n 57) (chars/between? 65 n 90) (chars/between? 97 n 122))))))

(defn hex-digit?
  "Whether the character `c` is an ASCII hex digit."
  [c]
  (boolean (when c
             (let [n (chars/code c)]
               (or (chars/between? 48 n 57) (chars/between? 65 n 70) (chars/between? 97 n 102))))))

;; HTML, section 13.2.5.78, the longest name that matches, where only a
;; legacy name, which has no semicolon, can match a part of the run
(defn named-reference
  "The characters of the named character reference whose name starts at
  `i` in the text `s`, and the index past it, as a pair, or nil when no
  name matches."
  [s i]
  (let [{:keys [names longest longest-legacy]} @table
        end (min (chars/run-end s i alphanumeric?) (+ i longest))
        run (subs s i end)]
    (if-let [found (and (= \; (chars/char-at s end)) (get names (str run ";")))]
      [found (inc end)]
      (some (fn [k]
              (when-let [found (get names (subs run 0 k))]
                [found (+ i k)]))
            (range (min (count run) longest-legacy) 0 -1)))))

(defn code-of
  "The number in the `digits` of base `radix`, but at most 0x110000, past
  which every number gives the same character."
  [digits radix]
  (reduce (fn [acc c]
            (min 0x110000 (+ (* acc radix)
                             #?(:clj  (Character/digit (char c) (int radix))
                                :cljs (js/parseInt c radix)))))
          0
          digits))

;; HTML, section 13.2.5.84
(def c1-replacements
  "The characters of Windows-1252 that a numeric reference to a C1 control
  stands for, by its code point."
  {0x80 0x20AC 0x82 0x201A 0x83 0x0192 0x84 0x201E 0x85 0x2026 0x86 0x2020
   0x87 0x2021 0x88 0x02C6 0x89 0x2030 0x8A 0x0160 0x8B 0x2039 0x8C 0x0152
   0x8E 0x017D 0x91 0x2018 0x92 0x2019 0x93 0x201C 0x94 0x201D 0x95 0x2022
   0x96 0x2013 0x97 0x2014 0x98 0x02DC 0x99 0x2122 0x9A 0x0161 0x9B 0x203A
   0x9C 0x0153 0x9E 0x017E 0x9F 0x0178})

(defn referenced
  "The character of a numeric reference to the code point `n`, as the
  numeric character reference end state of HTML checks it (section
  13.2.5.84)."
  [n]
  (chars/code-point->string
   (cond
     (zero? n)              0xFFFD
     (< 0x10FFFF n)         0xFFFD
     (<= 0xD800 n 0xDFFF)   0xFFFD
     :else                  (get c1-replacements n n))))

;; HTML, sections 13.2.5.80 to 13.2.5.84
(defn numeric-reference
  "The character of the numeric character reference whose x or digits
  start at `i` in the text `s`, just after its number sign, and the index
  past it, as a pair, or nil when it has no digits."
  [s i]
  (let [hex?   (contains? #{\x \X} (chars/char-at s i))
        start  (if hex? (inc i) i)
        end    (chars/run-end s start (if hex? hex-digit? chars/digit?))]
    (when (< start end)
      [(referenced (code-of (subs s start end) (if hex? 16 10)))
       (if (= \; (chars/char-at s end)) (inc end) end)])))

;; HTML, section 13.2.5.77
(defn character-reference
  "The characters of the character reference that starts at `i` in the
  text `s`, just after its ampersand, and the index past it, as a pair,
  or nil when there's none, where the ampersand stays text."
  [s i]
  (let [c (chars/char-at s i)]
    (cond
      (= \# c)            (numeric-reference s (inc i))
      (alphanumeric? c)   (named-reference s i)
      :else               nil)))
