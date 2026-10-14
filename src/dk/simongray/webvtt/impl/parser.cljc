(ns ^:no-doc dk.simongray.webvtt.impl.parser
  "The WebVTT parser algorithm of section 6.1: the signature and the header
  of a file, and its blocks of cues, regions and style sheets.

  A file is a map of the parts it has:

  - :header, the header as written, when it's more than WEBVTT
  - :regions, the region maps of its REGION blocks
  - :stylesheets, the text of its STYLE blocks
  - :cues, its cue maps

  The parser follows the algorithm with the input as one string, rather
  than incrementally as it arrives."
  (:require [clojure.string :as str]
            [dk.simongray.webvtt.impl.chars :as chars]
            [dk.simongray.webvtt.impl.settings :as settings]))

;; WebVTT, section 6.1, step 1
(defn input
  "The text `s` as the parser reads it: without a leading BOM, which UTF-8
  decoding removes, with each NUL replaced by U+FFFD, and with every line
  break as a line feed."
  [s]
  (let [s (cond-> s (= 0xFEFF (some-> (chars/char-at s 0) chars/code)) (subs 1))]
    (cond-> s
      (str/includes? s "\u0000") (str/replace "\u0000" (chars/code-point->string 0xFFFD))
      (str/includes? s "\r")     (-> (str/replace "\r\n" "\n") (str/replace "\r" "\n")))))

;; WebVTT, section 6.1, steps 4 to 6
(defn signature?
  "Whether the `input` starts with the file signature: WEBVTT, followed by
  a space, a tab, a line feed or nothing."
  [input]
  (and (str/starts-with? input "WEBVTT")
       (or (= 6 (count input))
           (contains? #{\space \tab \newline} (chars/char-at input 6)))))

(defn line-end
  "The index of the line feed that ends the line at `i` in the `input`, or
  its length when the line is the last."
  [input i]
  (or (str/index-of input "\n" i) (chars/length input)))

(defn skip-line-feeds
  "The index of the first character from `i` in the `input` that isn't a
  line feed."
  [input i]
  (chars/run-end input i #(= \newline %)))

(defn block-start
  "The kind of block that its first line `line` starts, :style or :region,
  or nil: the word alone, or followed by ASCII whitespace."
  [line]
  (let [kind? (fn [word]
                (and (str/starts-with? line word)
                     (= (count line) (chars/run-end line (count word) chars/whitespace?))))]
    (cond
      (kind? "STYLE")  :style
      (kind? "REGION") :region)))

(defn value
  "The kind of block and its value that the `state` of a block and its
  `buffer` make, as a pair: a cue, a style sheet or a region, or nil. A
  cue without text has no :text, as text is its default."
  [{:keys [cue kind]} buffer]
  (cond
    cue              [:cue (cond-> cue (not= "" buffer) (assoc :text buffer))]
    (= :style kind)  [:stylesheet buffer]
    (= :region kind) [:region (settings/region-settings buffer)]
    :else            nil))

(defn finish
  "The block of the `input` that collect-block returns for the `state` of
  a block, whose buffer and lines are in the `bounds`, and which `ended`
  so, and the index `k` where the next block may start, as a pair."
  [input state {:keys [from to start line-count]} ended k]
  (let [[kind v] (value state (subs input from to))]
    [{:kind kind :value v :start start :line-count line-count :arrow (:arrow state) :ended ended}
     k]))

;; WebVTT, section 6.1, "collect a WebVTT block": a block ends at a blank
;; line, or before a line with an arrow that can't be its cue timings. The
;; buffer of the spec is the text of the input from `from` to `to`, since
;; the lines it holds are next to each other there, so that no line is kept
;; but the one being read.
(defn collect-block
  "The block at `i` in the `input`, and the index where the next one may
  start, as a pair. The block is a map of:

  - :kind, :cue, :stylesheet or :region, or nil for a block that's none of
    them, e.g. a comment
  - :value, the cue, the text of the style sheet or the region
  - :start, the index of its first line in the `input`, and :line-count,
    the number of its lines
  - :arrow, the index among its lines of its cue timings, or of a line with
    an arrow that failed to be them
  - :ended, how it ended: :blank, :arrow (before a line with an arrow, which
    starts the next block) or :eof

  The `opts` are:

  - :in-header?, true for the header after the signature, which is never
    a block
  - :seen-cue?, true once the file has a cue, after which no block is a
    style sheet or a region
  - :regions, the regions so far, which the settings of a cue can name"
  [input i {:keys [in-header? seen-cue? regions]}]
  (let [n     (chars/length input)
        start i]
    (loop [i i, previous i, line-count 1, from i, to i, state {}]
      (let [j     (line-end input i)
            line  (subs input i j)
            eof?  (= j n)
            next' (if eof? n (inc j))]
        (cond
          (str/includes? line "-->")
          (if (and (not in-header?)
                   (or (= 1 line-count) (and (= 2 line-count) (not (:arrow state)))))
            (let [id        (subs input from to)
                  cue       (some-> (settings/timings-and-settings line regions)
                                    (cond-> (not= "" id) (assoc :id id)))
                  [from to] (if cue [next' next'] [from to])
                  state     (assoc state :arrow (dec line-count) :cue cue)]
              (if eof?
                (finish input state {:start start :from from :to to :line-count line-count} :eof next')
                (recur next' next' (inc line-count) from to state)))
            (finish input state {:start start :from from :to to :line-count (dec line-count)}
                    :arrow previous))

          (= i j)
          (finish input state {:start start :from from :to to :line-count (dec line-count)}
                  :blank next')

          :else
          (let [kind  (when (and (not in-header?)
                                 (= 2 line-count)
                                 (not seen-cue?)
                                 (nil? (:cue state)))
                        (block-start (subs input from to)))
                state (cond-> state kind (assoc :kind kind))
                from  (if (or kind (= from to)) i from)]
            (if eof?
              (finish input state {:start start :from from :to j :line-count line-count} :eof next')
              (recur next' next' (inc line-count) from j state))))))))

(defn block-lines
  "The lines of the `block` that collect-block returns for the `input`, as
  pairs of the index where each starts and the line."
  [input {:keys [start line-count]}]
  (loop [i start, k 0, acc []]
    (if (< k line-count)
      (let [j (line-end input i)]
        (recur (inc j) (inc k) (conj acc [i (subs input i j)])))
      acc)))

(defn add-block
  "The `file` with the value of the `block` that collect-block returns
  added to it."
  [file {:keys [kind value]}]
  (case kind
    :cue        (update file :cues (fnil conj []) value)
    :stylesheet (update file :stylesheets (fnil conj []) value)
    :region     (update file :regions (fnil conj []) value)
    file))

(defn header-end
  "The index in the `input` after its header, and the header block that
  follows the line with the signature, or nil when none does, as a pair
  (section 6.1, steps 7 to 10)."
  [input]
  (let [n (count input)
        i (line-end input 0)]
    (cond
      (<= n (inc i))                             [n nil]
      (= \newline (chars/char-at input (inc i))) [(+ i 2) nil]
      :else                                      (let [[block j] (collect-block input (inc i) {:in-header? true})]
                                                   [j block]))))

(defn with-header
  "The `file` with the header of the `input`, which ends at `i`, when
  there's more to it than WEBVTT."
  [file input i]
  (let [header (str/replace (subs input 0 i) #"\n+$" "")]
    (cond-> file (not= "WEBVTT" header) (assoc :header header))))

(defn scan
  "The `input` of the text `s` as the parser reads it, its file, and the
  blocks and the header block that collect-block returns for it, as a map
  of :input, :file, :blocks and :header-block, or nil when it doesn't
  start with the signature of a WebVTT file (section 6.1)."
  [s]
  (let [input (input s)]
    (when (signature? input)
      (let [[i header-block] (header-end input)
            n                (count input)]
        (loop [file (with-header {} input i), blocks [], i (skip-line-feeds input i)]
          (if (< i n)
            (let [[block j] (collect-block input i {:seen-cue? (boolean (:cues file))
                                                    :regions   (:regions file)})]
              (recur (add-block file block) (conj blocks block) (skip-line-feeds input j)))
            {:input input :file file :blocks blocks :header-block header-block}))))))

(defn parse
  "The file of the text `s`, or nil when it doesn't start with the
  signature of a WebVTT file."
  [s]
  (:file (scan s)))
