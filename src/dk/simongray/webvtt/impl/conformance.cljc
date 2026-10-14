(ns ^:no-doc dk.simongray.webvtt.impl.conformance
  "Whether a WebVTT file conforms to the syntax of section 4: both the
  errors in its blocks that the parser forgives, and the rules that the
  parser doesn't check, e.g. that cues are in order.

  An error is a map of its :type, a keyword named after the rule it breaks,
  and the :line and :column where it is, counted from 1 in UTF-16 code
  units. The types are in error-types.

  The kind of track (one of the kinds of HTML's track element) sets the
  rules for the text of cues: cue text for :subtitles, :captions and
  :descriptions, chapter titles for :chapters, and any text for
  :metadata."
  #?(:cljs (:require-macros [dk.simongray.webvtt.impl.conformance :refer [constant-map]]))
  (:require [clojure.string :as str]
            [dk.simongray.langtag.registry :as registry]
            [dk.simongray.webvtt.impl.chars :as chars]
            [dk.simongray.webvtt.impl.cue-text :as cue-text]
            [dk.simongray.webvtt.impl.entities :as entities]
            [dk.simongray.webvtt.impl.parser :as parser]
            [dk.simongray.webvtt.impl.settings :as settings]))

#?(:clj
   ;; ClojureScript builds a hash map literal by a call that the Closure
   ;; compiler keeps in every bundle, but an array map by its constructor,
   ;; which it leaves out of a bundle that doesn't use the map, e.g. one
   ;; that only parses (dev/size.sh measures it)
   (defmacro constant-map
     "The map literal `m`, built as an array map in ClojureScript."
     [m]
     (if (:ns &env)
       `(cljs.core/PersistentArrayMap. nil ~(count m) (cljs.core/array ~@(apply concat m)) nil)
       m)))

(def error-types
  "Each type of error, with the section of the spec that it breaks and
  what's wrong."
  (constant-map
   {:signature                   "4.1, the file doesn't start with WEBVTT"
    :header                      "4.1, the WEBVTT line isn't followed by a blank line"
    :missing-blank-line          "4.1, a block starts right after the one before it"
    :block-without-timings       "4.1, a block is neither a cue, a region, a style sheet nor a comment"
    :style-after-cue             "4.1, a STYLE block comes after a cue"
    :region-after-cue            "4.1, a REGION block comes after a cue"
    :timings                     "4.1, a cue's timings don't parse, so the parser leaves the cue out"
    :timestamp                   "4.1, a timestamp isn't written as the syntax says"
    :timing-whitespace           "4.1, the timings lack a space or a tab where they need one, or have other whitespace"
    :end-not-after-start         "4.1, a cue ends at or before its start"
    :cues-out-of-order           "4.1, a cue starts before an earlier cue does"
    :duplicate-cue-id            "4.1, two cues have the same id"
    :invalid-setting             "4.4, a cue setting is unknown or its value is invalid"
    :duplicate-setting           "4.4, a cue has a setting twice"
    :unknown-region              "4.4, a cue's region setting names no region of the file"
    :auto-position               "3.3, a cue of a size other than 100% that's aligned to its start or end has no position"
    :invalid-region-setting      "4.3, a region setting is unknown or its value is invalid"
    :duplicate-region-setting    "4.3, a region has a setting twice"
    :region-without-id           "4.3, a region has no id"
    :duplicate-region-id         "4.3, two regions have the same id"
    :unknown-tag                 "4.2.2, a tag isn't a tag of cue text"
    :rt-outside-ruby             "4.2.2, an rt tag isn't in a ruby"
    :ruby-without-rt             "4.2.2, a ruby has no rt"
    :unclosed-tag                "4.2.2, a tag has no end tag, which only a lone v may leave out"
    :unexpected-end-tag          "4.2.2, an end tag ends no open tag"
    :invalid-class               "4.2.2, a class name is empty or has & or <"
    :missing-annotation          "4.2.2, a v or a lang tag has no annotation"
    :annotation-not-allowed      "4.2.2, a tag other than v or lang has an annotation"
    :invalid-language-tag        "4.2.2, the annotation of a lang tag isn't a valid BCP 47 language tag"
    :invalid-character-reference "4.2.2, an & doesn't start a valid character reference"
    :invalid-timestamp           "4.2.2, an inline timestamp isn't a timestamp"
    :timestamp-out-of-order      "4.2.2, an inline timestamp isn't after the cue's start and the timestamps before it"
    :timestamp-after-end         "4.2.2, an inline timestamp isn't before the cue's end"
    :markup-in-chapter-title     "4.2.3, the title of a chapter has a tag"
    :overlapping-chapters        "4.5.1, two chapters overlap without one holding the other"}))

(def kinds
  "The kinds of track, which pick the rules for the text of cues."
  #{:subtitles :captions :descriptions :chapters :metadata})

(defn error
  "An error of the `type` at the index `at` of the input."
  [type at]
  {:type type :at at})

;; WebVTT, section 4.1, a WebVTT timestamp and a WebVTT percentage

(defn timestamp?
  "Whether the text `s` is a WebVTT timestamp, with two digits or more for
  the hours when it has hours."
  [s]
  (boolean (re-matches #"(?:[0-9]{2,}:)?[0-5][0-9]:[0-5][0-9]\.[0-9]{3}" s)))

(defn percentage?
  "Whether the text `s` is a WebVTT percentage from 0% to 100%."
  [s]
  (some? (settings/percentage s)))

(defn tokens
  "The tokens of the text `s` between ASCII whitespace, with the index `at`
  of `s` in the input, as pairs of the index of each and the token."
  [s at]
  (loop [i (chars/skip-whitespace s 0) acc []]
    (if (< i (count s))
      (let [j (chars/run-end s i #(not (chars/whitespace? %)))]
        (recur (chars/skip-whitespace s j) (conj acc [(+ at i) (subs s i j)])))
      acc)))

(defn duplicates
  "The indices in the `named` pairs of an index and a name whose name came
  before."
  [named]
  (second (reduce (fn [[seen acc] [at name]]
                    (if (contains? seen name)
                      [seen (conj acc at)]
                      [(conj seen name) acc]))
                  [#{} []]
                  named)))

;; WebVTT, section 4.1, the header

(defn header-errors
  "The errors of the header of the `input`, whose `header-block` is the
  block of lines after the line of WEBVTT, or nil."
  [input header-block]
  (let [i (parser/line-end input 0)]
    (cond
      header-block                                 [(error :header (ffirst (:lines header-block)))]
      (not (str/starts-with? (subs input i) "\n\n")) [(error :header i)]
      :else                                        [])))

;; WebVTT, sections 4.1 and 4.4, cue timings and settings

(defn whitespace-between?
  "Whether the `line` has one space or tab or more, and nothing else,
  from `i` to `j`."
  [line i j]
  (boolean (and (< i j) (re-matches #"[ \t]+" (subs line i j)))))

(defn timing-parts
  "Where the parts of the cue timings `line`, which parses, are, as a map
  of:

  - :start and :start-end, where the start time starts and ends
  - :arrow, the index of the arrow
  - :end and :end-end, where the end time starts and ends, which is where
    the settings start"
  [line]
  (let [start     (chars/skip-whitespace line 0)
        [_ i]     (settings/timestamp line start)
        arrow     (chars/skip-whitespace line i)
        end       (chars/skip-whitespace line (+ arrow 3))
        [_ j]     (settings/timestamp line end)]
    {:start start :start-end i :arrow arrow :end end :end-end j}))

(defn timing-errors
  "The errors of the timings of the cue timings `line`, which starts at
  `at`, and whose parts are `parts` (see timing-parts)."
  [line at {:keys [start start-end arrow end end-end] :as parts}]
  (let [rest' (subs line end-end)]
    (cond-> []
      (pos? start)                                   (conj (error :timing-whitespace at))
      (not (timestamp? (subs line start start-end))) (conj (error :timestamp (+ at start)))
      (not (whitespace-between? line start-end arrow)) (conj (error :timing-whitespace (+ at start-end)))
      (not (whitespace-between? line (+ arrow 3) end)) (conj (error :timing-whitespace (+ at arrow 3)))
      (not (timestamp? (subs line end end-end)))     (conj (error :timestamp (+ at end)))
      (re-find #"^[^\t\n\f\r ]" rest')               (conj (error :timing-whitespace (+ at end-end)))
      (re-find #"[\n\f\r]" rest')                    (conj (error :timing-whitespace
                                                                  (+ at end-end (count (re-find #"^[^\n\f\r]*" rest'))))))))

(defn cue-setting-valid?
  "Whether the cue setting of the `name` and the `value` is in the syntax
  of section 4.4."
  [name value]
  (let [[position align] (settings/split-at-comma value)]
    (case name
      "vertical" (contains? #{"rl" "lr"} value)
      "line"     (and (or (re-matches #"-?[0-9]+" position) (percentage? position))
                      (or (nil? align) (contains? #{"start" "center" "end"} align)))
      "position" (and (percentage? position)
                      (or (nil? align) (contains? #{"line-left" "center" "line-right"} align)))
      "size"     (percentage? value)
      "align"    (contains? #{"start" "center" "end" "left" "right"} value)
      "region"   true
      false)))

(defn setting-errors
  "The errors of the settings of the cue timings `line`, which starts at
  `at` and whose parts are `parts` (see timing-parts), where the
  `region-ids` are those of the file's regions."
  [line at {:keys [end-end] :as parts} region-ids]
  (let [named (for [[i token] (tokens (subs line end-end) (+ at end-end))
                    :let [[name value] (settings/setting token)]]
                [i name value])]
    (concat
     (for [[i name value] named
           :when (not (and name (cue-setting-valid? name value)))]
       (error :invalid-setting i))
     (for [[i name value] named
           :when (and (= "region" name) (not (contains? region-ids value)))]
       (error :unknown-region i))
     (map #(error :duplicate-setting %)
          (duplicates (for [[i name] named :when name] [i name]))))))

(defn auto-position-errors
  "The error of the `cue`, whose timings start at `at`, when it's of a size
  other than 100%, aligned to its start or end and has no position (section
  3.3)."
  [{:keys [size align position] :as cue} at]
  (when (and size (contains? #{:start :end} align) (nil? position))
    [(error :auto-position at)]))

;; WebVTT, section 4.2, the text of cues

(defn allowed-code-point?
  "Whether a numeric character reference may refer to the code point `n`:
  not a surrogate, a noncharacter, a carriage return or a control other
  than ASCII whitespace (HTML, section 13.1.4)."
  [n]
  (and (< 0 n 0x110000)
       (not (<= 0xD800 n 0xDFFF))
       (not (<= 0xFDD0 n 0xFDEF))
       (not (contains? #{0xFFFE 0xFFFF} (bit-and n 0xFFFF)))
       (not (and (or (< n 0x20) (<= 0x7F n 0x9F))
                 (not (contains? #{0x09 0x0A 0x0C} n))))))

(defn valid-reference?
  "Whether the character reference just after an ampersand at `i` in the
  text `s` is valid HTML: a name of the table or a number, with its
  semicolon."
  [s i]
  (let [c (chars/char-at s i)]
    (cond
      (= \# c)
      (let [hex?  (contains? #{\x \X} (chars/char-at s (inc i)))
            start (+ i (if hex? 2 1))
            end   (chars/run-end s start (if hex? entities/hex-digit? chars/digit?))]
        (and (< start end)
             (= \; (chars/char-at s end))
             (allowed-code-point? (entities/code-of (subs s start end) (if hex? 16 10)))))

      (entities/alphanumeric? c)
      (let [end (chars/run-end s i entities/alphanumeric?)]
        (and (= \; (chars/char-at s end))
             (contains? (:names @entities/table) (str (subs s i end) ";"))))

      :else false)))

(defn reference-errors
  "The errors of the ampersands in the text `s`, which starts at `at`, that
  don't start a valid character reference."
  [s at]
  (loop [i (str/index-of s "&") acc []]
    (if i
      (recur (str/index-of s "&" (inc i))
             (cond-> acc (not (valid-reference? s (inc i))) (conj (error :invalid-character-reference (+ at i)))))
      acc)))

(defn located-tokens
  "The tokens of the cue text `s`, each with its :offset in `s` and its
  :raw text as written."
  [s]
  (loop [i 0 acc []]
    (if (< i (count s))
      (let [[token j] (cue-text/token s i)]
        (recur j (conj acc (assoc token :offset i :raw (subs s i j)))))
      acc)))

(def tag-names
  "The tag names of cue text."
  #{"c" "i" "b" "u" "ruby" "rt" "v" "lang"})

(defn start-tag-errors
  "The errors of the start tag `token`, at `at` in the input, apart from
  those of where it is, e.g. an rt outside a ruby."
  [{:keys [name classes annotation raw]} at]
  (let [known?        (contains? tag-names name)
        annotated?    (contains? #{"v" "lang"} name)
        annotation-at (chars/run-end raw 1 #(not (chars/whitespace? %)))]
    (concat
     (cond-> []
       (not known?)
       (conj (error :unknown-tag at))

       (some #(or (= "" %) (re-find #"[&<]" %)) classes)
       (conj (error :invalid-class at))

       (and annotated? (str/blank? annotation))
       (conj (error :missing-annotation at))

       (and known? (not annotated?) (some? annotation))
       (conj (error :annotation-not-allowed at))

       (and (= "lang" name) (not (str/blank? annotation)) (not (registry/valid? annotation)))
       (conj (error :invalid-language-tag at)))
     (reference-errors (subs raw annotation-at) (+ at annotation-at)))))

(defn open-tag
  "The `state` of a walk through cue text after the start tag `token` at
  `at` in the input."
  [state {:keys [name] :as token} at]
  (let [state (update state :errors into (start-tag-errors token at))]
    (cond
      (and (= "rt" name) (not= "ruby" (:name (peek (:stack state)))))
      (update state :errors conj (error :rt-outside-ruby at))

      (contains? tag-names name)
      (-> (cond-> state (= "rt" name) (update :stack #(conj (pop %) (assoc (peek %) :rt? true))))
          (update :stack conj {:name name :at at :first? (:first? state)}))

      :else state)))

(defn close-tag
  "The `state` of a walk through cue text after the end tag `token` at `at`
  in the input."
  [state {:keys [name]} at]
  (let [{:keys [stack]} state
        top             (peek stack)
        ruby-ends       (fn [state ruby]
                          (cond-> state
                            (not (:rt? ruby)) (update :errors conj (error :ruby-without-rt (:at ruby)))))]
    (cond
      (= name (:name top))
      (cond-> (update state :stack pop)
        (= "ruby" name) (ruby-ends top))

      (and (= "ruby" name) (= "rt" (:name top)))
      (-> (update state :stack (comp pop pop))
          (ruby-ends (peek (pop stack))))

      :else
      (update state :errors conj (error :unexpected-end-tag at)))))

(defn timestamp-tag
  "The `state` of a walk through the cue text of the `cue` after the
  timestamp tag `token` at `at` in the input."
  [state {:keys [value]} at cue]
  (let [t (cue-text/inline-timestamp value)]
    (cond
      (not (timestamp? value))
      (update state :errors conj (error :invalid-timestamp at))

      (<= t (max (:start-time cue) (:previous state ##-Inf)))
      (update state :errors conj (error :timestamp-out-of-order at))

      (<= (:end-time cue) t)
      (-> (update state :errors conj (error :timestamp-after-end at))
          (assoc :previous t))

      :else
      (assoc state :previous t))))

(defn unclosed-errors
  "The errors of the elements still open on the `stack` at the end of cue
  text, of which only a v that starts the text may have no end tag."
  [stack]
  (for [[i {:keys [name at first?]}] (map-indexed vector stack)
        :when (not (and (= "v" name) first? (zero? i)))]
    (error :unclosed-tag at)))

;; WebVTT, section 4.2.2, caption or subtitle cue text
(defn cue-text-errors
  "The errors of the cue text `s` of the `cue`, where `s` starts at `at` in
  the input."
  [s at cue]
  (let [state (reduce (fn [state {:keys [type offset raw] :as token}]
                        (let [at' (+ at offset)]
                          (-> (case type
                                :string        (update state :errors into (reference-errors raw at'))
                                :start-tag     (open-tag state token at')
                                :end-tag       (close-tag state token at')
                                :timestamp-tag (timestamp-tag state token at' cue))
                              (assoc :first? false))))
                      {:stack [] :errors [] :first? true}
                      (located-tokens s))]
    (concat (:errors state) (unclosed-errors (:stack state)))))

;; WebVTT, section 4.2.3, chapter title text
(defn chapter-title-errors
  "The errors of the chapter title `s`, which starts at `at` in the input:
  any tag and any invalid character reference."
  [s at]
  (mapcat (fn [{:keys [type offset raw]}]
            (if (= :string type)
              (reference-errors raw (+ at offset))
              [(error :markup-in-chapter-title (+ at offset))]))
          (located-tokens s)))

(defn payload-errors
  "The errors of the text of the `cue`, whose payload lines are `lines`,
  pairs of an index and a line, for the `kind` of track."
  [cue lines kind]
  (when-let [at (ffirst lines)]
    (let [s (str/join "\n" (map second lines))]
      (case kind
        (:subtitles :captions :descriptions) (cue-text-errors s at cue)
        :chapters                            (chapter-title-errors s at)
        :metadata                            nil))))

;; WebVTT, section 4.3, region settings

(defn region-setting-valid?
  "Whether the region setting of the `name` and the `value` is in the
  syntax of section 4.3."
  [name value]
  (let [anchor? #(let [[x y] (settings/split-at-comma %)]
                   (and y (percentage? x) (percentage? y)))]
    (case name
      "id"             true
      "width"          (percentage? value)
      "lines"          (boolean (re-matches #"[0-9]+" value))
      "regionanchor"   (anchor? value)
      "viewportanchor" (anchor? value)
      "scroll"         (= "up" value)
      false)))

(defn region-errors
  "The errors of the region `block`, apart from a duplicate id."
  [{[[at] & lines] :lines}]
  (let [named (for [[start line] lines
                    [i token] (tokens line start)
                    :let [[name value] (settings/setting token)]]
                [i name value])]
    (concat
     (for [[i name value] named
           :when (not (and name (region-setting-valid? name value)))]
       (error :invalid-region-setting i))
     (map #(error :duplicate-region-setting %)
          (duplicates (for [[i name] named :when name] [i name])))
     (when-not (some #(= "id" (second %)) named)
       [(error :region-without-id at)]))))

(defn region-id-errors
  "The errors of the region `blocks` with an id that a region before them
  already has."
  [blocks]
  (map #(error :duplicate-region-id %)
       (duplicates (for [{[[at]] :lines :keys [value]} blocks
                         :when (seq (:id value))]
                     [at (:id value)]))))

;; WebVTT, section 4.1, the blocks

(defn comment?
  "Whether the first `line` of a block starts a comment: NOTE, alone or
  followed by a space or a tab."
  [line]
  (boolean (re-matches #"NOTE(?:[ \t].*)?" line)))

(defn other-block-errors
  "The errors of the `block` that's neither a cue, a style sheet nor a
  region, after a cue when `seen-cue?`."
  [{[[at line] :as lines] :lines :keys [arrow]} seen-cue?]
  (let [kind (parser/block-start line)]
    (cond
      (comment? line)                       []
      arrow                                 [(error :timings (first (nth lines arrow)))]
      (and kind seen-cue?)                  [(error (if (= :style kind) :style-after-cue :region-after-cue) at)]
      (= :style kind)                       []
      (= :region kind)                      [(error :region-without-id at)]
      :else                                 [(error :block-without-timings at)])))

(defn cue-errors
  "The errors of the cue `block` for the `kind` of track, where `seen`
  holds the :ids and the :latest start of the cues before it, and the
  `region-ids` are those of the file's regions."
  [{:keys [value lines arrow]} seen region-ids kind]
  (let [[at line]   (nth lines arrow)
        [id-at id]  (when (= 1 arrow) (first lines))
        parts       (timing-parts line)
        {:keys [start-time end-time]} value]
    (concat
     (when (and id (contains? (:ids seen) id))
       [(error :duplicate-cue-id id-at)])
     (timing-errors line at parts)
     (when (<= end-time start-time)
       [(error :end-not-after-start at)])
     (when (< start-time (:latest seen ##-Inf))
       [(error :cues-out-of-order at)])
     (setting-errors line at parts region-ids)
     (auto-position-errors value at)
     (payload-errors value (subvec lines (inc arrow)) kind))))

(defn block-errors
  "The errors of the `blocks` of a file, for the `kind` of track."
  [blocks kind]
  (let [region-ids (set (keep #(get-in % [:value :id]) (filter #(= :region (:kind %)) blocks)))]
    (loop [[block & more] blocks, previous nil, seen {:ids #{}}, acc []]
      (if-not block
        acc
        (let [errors (case (:kind block)
                       :cue        (cue-errors block seen region-ids kind)
                       :region     (region-errors block)
                       :stylesheet []
                       (other-block-errors block (boolean (:latest seen))))
              errors (cond->> errors
                       (= :arrow (:ended previous))
                       (cons (error :missing-blank-line (ffirst (:lines block)))))
              seen   (if (= :cue (:kind block))
                       (-> seen
                           (update :ids conj (get-in block [:value :id] ""))
                           (update :latest (fnil max ##-Inf) (get-in block [:value :start-time])))
                       seen)]
          (recur more block seen (into acc errors)))))))

;; WebVTT, section 4.5.1, a file using only nested cues
(defn chapter-errors
  "The errors of the cue `blocks` of a chapter track whose chapters overlap
  without one holding the other."
  [blocks]
  (let [chapters (sort-by (juxt :start #(- (:end %)))
                          (for [{:keys [value lines arrow]} blocks]
                            {:start (:start-time value) :end (:end-time value)
                             :at (first (nth lines arrow))}))]
    (second (reduce (fn [[stack acc] {:keys [start end at] :as chapter}]
                      (let [stack (into [] (take-while #(< start (:end %))) stack)]
                        [(conj stack chapter)
                         (cond-> acc
                           (and (seq stack) (< (:end (peek stack)) end))
                           (conj (error :overlapping-chapters at)))]))
                    [[] []]
                    chapters))))

(defn line-starts
  "The index in the `input` where each of its lines starts."
  [input]
  (loop [i 0 acc [0]]
    (if-let [j (str/index-of input "\n" i)]
      (recur (inc j) (conj acc (inc j)))
      acc)))

(defn position
  "The line and the column, from 1, of the index `at` in an input whose
  lines start at `starts`, as a pair."
  [starts at]
  (loop [lo 0 hi (dec (count starts))]
    (if (< lo hi)
      (let [mid (quot (+ lo hi 1) 2)]
        (if (<= (nth starts mid) at) (recur mid hi) (recur lo (dec mid))))
      [(inc lo) (inc (- at (nth starts lo)))])))

(defn errors
  "The conformance errors of the text `s` as a WebVTT file, for the `kind`
  of track, in the order of the file."
  [s kind]
  (if-let [{:keys [input blocks header-block]} (parser/scan s)]
    (let [located      #(assoc % :lines (parser/block-lines input %))
          blocks       (mapv located blocks)
          header-block (some-> header-block located)
          starts       (line-starts input)
          cues         (filter #(= :cue (:kind %)) blocks)]
      (->> (concat (header-errors input header-block)
                   (block-errors blocks kind)
                   (region-id-errors (filter #(= :region (:kind %)) blocks))
                   (when (= :chapters kind) (chapter-errors cues)))
           (sort-by :at)
           (mapv (fn [{:keys [type at]}]
                   (let [[line column] (position starts at)]
                     {:type type :line line :column column})))))
    [{:type :signature :line 1 :column 1}]))
