(ns ^:no-doc dk.simongray.webvtt.impl.cue-text
  "The cue text parsing rules of WebVTT, section 6.4: the tokenizer, and
  the tree of nodes that its tokens build, as Hiccup. Also the rules for
  extracting the chapter title of section 6.6.

  The tree is a vector of nodes. A node is a string of text, an element
  [tag attrs & children] with a tag of :c, :i, :b, :u, :ruby, :rt, :v or
  :lang, or an inline timestamp [:timestamp {:time seconds}]. The attrs of
  an element hold its :class, a vector of class names, when it has any,
  the :voice of a :v and the :lang of a :lang.

  Each state of the tokenizer reads the run of text up to the character
  that ends it, rather than a character at a time."
  (:require [clojure.string :as str]
            [dk.simongray.webvtt.impl.chars :as chars]
            [dk.simongray.webvtt.impl.entities :as entities]
            [dk.simongray.webvtt.impl.settings :as settings]))

(defn text-of
  "The cue text of the `cue`, a cue map or its text."
  [cue]
  (if (map? cue) (:text cue "") (str cue)))

;; WebVTT, section 6.4, the WebVTT cue text tokenizer. A token is a map of
;; its :type, :string, :start-tag, :end-tag or :timestamp-tag, and of its
;; :value, or of a start tag's :name, :classes and :annotation.

;; the runs of text are found by index-of, which dev/dk/simongray/webvtt/bench.cljc
;; showed to be faster than calling a predicate on each character, and a
;; reference never runs past the < or > that ends a run
(defn decoded-run
  "The text from `i` in the cue text `s` up to the next `stop`, a string of
  one character, or its end, with its character references decoded, and
  the index where it stops, as a pair."
  [s i stop]
  (let [end (or (str/index-of s stop i) (chars/length s))]
    (loop [i i parts []]
      (let [amp (str/index-of s "&" i)]
        (if (and amp (< amp end))
          (let [parts (conj parts (subs s i amp))]
            (if-let [[found k] (entities/character-reference s (inc amp))]
              (recur k (conj parts found))
              (recur (inc amp) (conj parts "&"))))
          (let [last-part (subs s i end)]
            [(if (seq parts) (apply str (conj parts last-part)) last-part) end]))))))

(defn string-token
  "The string token at `i` in the cue text `s`, and the index after it, as
  a pair (the data state)."
  [s i]
  (let [[value j] (decoded-run s i "<")]
    [{:type :string :value value} j]))

(defn start-tag
  "A start tag token of the `name`, the `classes` and the `annotation`."
  [name classes annotation]
  {:type :start-tag :name name :classes classes :annotation annotation})

(defn tag-name-end?
  "Whether the character `c` ends the name or a class of a start tag."
  [c]
  (case c
    (\tab \formfeed \space \newline \. \>) true
    false))

(defn annotation
  "The start tag token of the `name` and the `classes`, with the
  annotation from `i` in the cue text `s`, and the index after it, as a
  pair (the start tag annotation state). The annotation starts with the
  `buffer`, and has its ASCII whitespace trimmed and collapsed."
  [s i name classes buffer]
  (let [[value j] (decoded-run s i ">")
        value     (str/join " " (chars/words (str buffer value)))]
    [(start-tag name classes value) (cond-> j (= \> (chars/char-at s j)) inc)]))

(defn after-name
  "The start tag token of the `name` and the `classes`, whose name or last
  class ends at `j` in the cue text `s`, and the index after it, as a
  pair."
  [s j name classes]
  (case (chars/char-at s j)
    (\tab \formfeed \space) (annotation s (inc j) name classes "")
    \newline                (annotation s (inc j) name classes "\n")
    \>                      [(start-tag name classes nil) (inc j)]
    [(start-tag name classes nil) j]))

(defn tag-classes
  "The start tag token of the `name`, with the classes from `i` in the cue
  text `s`, and the index after it, as a pair (the start tag class
  state)."
  [s i name]
  (loop [i i classes []]
    (let [j       (chars/run-end s i #(not (tag-name-end? %)))
          classes (conj classes (subs s i j))]
      (if (= \. (chars/char-at s j))
        (recur (inc j) classes)
        (after-name s j name classes)))))

(defn start-tag-token
  "The start tag token whose name starts at `i` in the cue text `s`, and
  the index after it, as a pair (the start tag state)."
  [s i]
  (let [j    (chars/run-end s i #(not (tag-name-end? %)))
        name (subs s i j)]
    (if (= \. (chars/char-at s j))
      (tag-classes s (inc j) name)
      (after-name s j name []))))

(defn tag-value
  "The text from `i` in the cue text `s` up to a > or its end, and the
  index after it, as a pair, for an end tag or a timestamp tag."
  [s i]
  (let [j (or (str/index-of s ">" i) (chars/length s))]
    [(subs s i j) (cond-> j (= \> (chars/char-at s j)) inc)]))

(defn tag-token
  "The tag token at `i` in the cue text `s`, just after its <, and the
  index after it, as a pair (the tag state)."
  [s i]
  (let [c (chars/char-at s i)]
    (cond
      (nil? c)               [(start-tag "" [] nil) i]
      (= \> c)               [(start-tag "" [] nil) (inc i)]
      (contains? #{\tab \newline \formfeed \space} c)
      (annotation s (inc i) "" [] "")
      (= \. c)               (tag-classes s (inc i) "")
      (= \/ c)               (let [[value j] (tag-value s (inc i))]
                               [{:type :end-tag :name value} j])
      (chars/digit? c)       (let [[value j] (tag-value s i)]
                               [{:type :timestamp-tag :value value} j])
      :else                  (start-tag-token s i))))

(defn token
  "The token at `i` in the cue text `s`, and the index after it, as a
  pair."
  [s i]
  (if (= \< (chars/char-at s i))
    (tag-token s (inc i))
    (string-token s i)))

;; WebVTT, section 6.4, the WebVTT cue text parsing rules. The tree is
;; built on a stack of the elements still open, under the root, a vector
;; of the nodes so far.

(defn append
  "The `stack` with the `node` added to the element on top of it, where a
  string after a string joins it."
  [stack node]
  (let [top      (peek stack)
        previous (peek top)]
    (conj (pop stack)
          (if (and (string? node) (string? previous))
            (conj (pop top) (str previous node))
            (conj top node)))))

(defn close
  "The `stack` with the element on top of it closed."
  [stack]
  (append (pop stack) (peek stack)))

(defn current-tag
  "The tag of the element on top of the `stack`, or nil at the root."
  [stack]
  (when (< 1 (count stack))
    (first (peek stack))))

(def element-tags
  "The tag of the element that each tag name of cue text opens, :rt only
  in a :ruby."
  {"c" :c "i" :i "b" :b "u" :u "ruby" :ruby "rt" :rt "v" :v "lang" :lang})

(defn element
  "The element of the `tag` that the start tag `token` opens, with its
  classes, but none that's empty, and its annotation as a :voice or a
  :lang."
  [tag {:keys [classes annotation]}]
  (let [classes (filterv seq classes)]
    [tag (cond-> {}
           (seq classes) (assoc :class classes)
           (= :v tag)    (assoc :voice (or annotation ""))
           (= :lang tag) (assoc :lang (or annotation "")))]))

(defn open
  "The `stack` with the element of the start tag `token` opened on it,
  or as it is for a tag that opens none."
  [stack token]
  (let [tag (element-tags (:name token))]
    (if (and tag (or (not= :rt tag) (= :ruby (current-tag stack))))
      (conj stack (element tag token))
      stack)))

(defn end
  "The `stack` with the element that the end tag `token` ends closed, or as
  it is when it ends none. A </ruby> closes an open :rt too."
  [stack token]
  (let [tag     (current-tag stack)
        name    (:name token)]
    (cond
      (and tag (= tag (element-tags name)))     (close stack)
      (and (= "ruby" name) (= :rt tag))         (-> stack close close)
      :else                                     stack)))

(defn inline-timestamp
  "The seconds of the timestamp tag `value`, or nil when it isn't one
  timestamp and nothing more."
  [value]
  (when-let [[seconds i] (settings/timestamp value 0)]
    (when (= i (count value))
      seconds)))

(defn build
  "The `stack` with the `token` added to the tree."
  [stack token]
  (case (:type token)
    :string        (append stack (:value token))
    :start-tag     (open stack token)
    :end-tag       (end stack token)
    :timestamp-tag (if-let [t (inline-timestamp (:value token))]
                     (append stack [:timestamp {:time t}])
                     stack)))

(defn nodes
  "The tree of the cue text `s`."
  [s]
  (let [n (count s)]
    (loop [i 0 stack [[]]]
      (if (< i n)
        (let [[token j] (token s i)]
          (recur j (build stack token)))
        (peek (nth (iterate close stack) (dec (count stack))))))))

(defn children
  "The children of the element `node`."
  [node]
  (let [content (rest node)]
    (if (map? (first content)) (rest content) content)))

;; WebVTT, section 6.6, the rules for extracting the chapter title: the text
;; in order, but that of ruby text
(defn text
  "The plain text of the `nodes` of a tree."
  [nodes]
  (letfn [(texts [node]
            (cond
              (string? node)                       [node]
              (contains? #{:rt :timestamp} (first node)) []
              :else                                (mapcat texts (children node))))]
    (apply str (mapcat texts nodes))))

(defn runs
  "The text of the `nodes` of a tree in runs, split at its inline
  timestamps, as maps of the :start-time and the :text of each, where the
  first run starts at `start`. The text leaves out ruby text, as text does,
  and a run without text is left out."
  [nodes start]
  (letfn [(items [node]
            (cond
              (string? node)               [node]
              (= :rt (first node))         []
              (= :timestamp (first node))  [(:time (second node))]
              :else                        (mapcat items (children node))))]
    (->> (mapcat items nodes)
         (reduce (fn [acc item]
                   (if (string? item)
                     (update-in acc [(dec (count acc)) :text] str item)
                     (conj acc {:start-time item :text ""})))
                 [{:start-time start :text ""}])
         (filterv #(seq (:text %))))))
