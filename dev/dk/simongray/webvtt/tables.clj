(ns dk.simongray.webvtt.tables
  "Write the table of HTML's named character references in resources, from
  the entities.json that dev/fetch.sh downloads.

      clojure -X:tables

  The first line of the table has the legacy names, which HTML also reads
  without their semicolon, separated by spaces. Every other line is a
  group of the names that stand for the same characters, in the order of
  their code points:

  - the first code point, as the difference from the first code point of
    the line before, and any further code points, separated by commas and
    in base 36
  - a space, and the names without their semicolons, separated by spaces

  So the table is about a third smaller than entities.json would be as
  plain lines of a name and its code points, both raw and gzipped."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def source
  "Where dev/fetch.sh puts the named character references."
  "dev-resources/entities.json")

(def target
  "Where the table goes."
  "resources/dk/simongray/webvtt/entities.txt")

(defn base-36
  "The number `n` in base 36."
  [n]
  (Long/toString n 36))

(defn lexicographic
  "The order of the sequences of numbers `a` and `b`, by their first
  number that differs, where a sequence comes before a longer one that
  starts with it."
  [a b]
  (or (first (remove zero? (map compare a b)))
      (compare (count a) (count b))))

(defn groups
  "The names of the `entries` of entities.json that stand for the same
  code points, as a sorted map of the code points to the names without
  their ampersands and semicolons. A legacy name, without its semicolon,
  is left out, since it stands for what its name with one does."
  [entries]
  (reduce (fn [acc [k {:strs [codepoints]}]]
            (cond-> acc
              (str/ends-with? k ";") (update codepoints (fnil conj (sorted-set)) (subs k 1 (dec (count k))))))
          (sorted-map-by lexicographic)
          entries))

(defn lines
  "The lines of the table of the `entries` of entities.json."
  [entries]
  (let [legacy (sort (for [[k] entries :when (not (str/ends-with? k ";"))] (subs k 1)))]
    (cons (str/join " " legacy)
          (second (reduce (fn [[previous acc] [[first' & more] names]]
                            [first'
                             (conj acc (str (str/join "," (map base-36 (cons (- first' previous) more)))
                                            " " (str/join " " names)))])
                          [0 []]
                          (groups entries))))))

(defn write!
  "Write the table from entities.json."
  [_]
  (let [entries (json/read-str (slurp source))]
    (io/make-parents target)
    (spit target (str (str/join "\n" (lines entries)) "\n"))))
