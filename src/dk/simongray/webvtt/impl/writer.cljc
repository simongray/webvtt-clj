(ns ^:no-doc dk.simongray.webvtt.impl.writer
  "Write WebVTT files and cue text from file maps and Hiccup, by the
  syntax of section 4, so that they parse back into the same data.

  For data that can't be written so that it parses back the same, e.g. a
  blank line in the text of a cue (which would end the cue there), the
  writer throws an ex-info whose data has a :reason. It still writes what
  merely doesn't conform, e.g. cues out of order."
  (:require [clojure.string :as str]
            [dk.simongray.webvtt.impl.chars :as chars]
            [dk.simongray.webvtt.impl.parser :as parser]
            [dk.simongray.webvtt.impl.settings :as settings]))

(defn refuse
  "Throw an ex-info with the `message`, whose data has the `reason`, the
  value `x` that can't be written and any more `data`."
  [message reason x & {:as data}]
  (throw (ex-info message (merge {:reason reason :value x} data))))

(defn pad
  "The number `n` as text, with zeros before it up to `width` digits, which
  is 3 at most."
  [width n]
  (let [s (str n)]
    (if (< (count s) width)
      (str (subs "000" 0 (- width (count s))) s)
      s)))

;; WebVTT, section 4.1, a WebVTT timestamp, written with its hours as
;; section 6.5 writes the data of a timestamp object
(defn format-timestamp
  "The `seconds` as a WebVTT timestamp, e.g. 00:01:02.500, rounded to the
  millisecond."
  [seconds]
  ;; past 2^53 milliseconds a double no longer holds each millisecond
  (when-not (and (number? seconds) (<= 0 seconds) (< (* 1000.0 seconds) 9007199254740992))
    (refuse "A timestamp must be a number of seconds from 0, and less than 2^53 milliseconds"
            :timestamp seconds))
  (let [ms (long (Math/round (* 1000.0 (double seconds))))]
    (str (pad 2 (quot ms 3600000)) ":"
         (pad 2 (rem (quot ms 60000) 60)) ":"
         (pad 2 (rem (quot ms 1000) 60)) "."
         (pad 3 (rem ms 1000)))))

(defn shift-point
  "The plain decimal of the `digits` with the decimal point `point` digits
  from their start, which may be before the first digit or after the
  last."
  [digits point]
  (let [n (count digits)]
    (cond
      (<= point 0) (str "0." (apply str (repeat (- point) "0")) digits)
      (<= n point) (str digits (apply str (repeat (- point n) "0")))
      :else        (str (subs digits 0 point) "." (subs digits point)))))

(defn decimal
  "The number `x` as a plain decimal without an exponent or needless
  zeros, e.g. 40 for 40.0 and 0.00001 for 1.0E-5, and with -0 as 0."
  [x]
  (let [s (str (+ (double x) 0.0))
        s (if-let [[_ sign whole fraction exponent]
                   (re-matches #"(-?)([0-9]+)(?:\.([0-9]+))?[eE]([-+]?[0-9]+)" s)]
            (str sign (shift-point (str whole fraction)
                                   (+ (count whole) (parse-long (str/replace exponent "+" "")))))
            s)
        s (if (str/includes? s ".") (str/replace s #"\.?0+$" "") s)]
    (str/replace s #"^(-?)0+(?=[0-9])" "$1")))

(defn percentage
  "The number `x` as a WebVTT percentage, e.g. 40%."
  [x]
  (when-not (and (number? x) (<= 0 x 100))
    (refuse "A percentage must be a number from 0 to 100" :percentage x))
  (str (decimal x) "%"))

;; WebVTT, section 4.2.2, cue text: escape what would parse as markup, and
;; any line feed that would leave a blank line and so end the cue
(defn text-escape
  "The text `s` of a text node, with &, <, > and a carriage return as
  character references."
  [s]
  (str/escape s {\& "&amp;" \< "&lt;" \> "&gt;" \return "&#13;"}))

(defn without-blank-lines
  "The cue text `s` with a line feed as a character reference where it
  would leave an empty line: at the start or the end, or after another."
  [s]
  (let [n (count s)]
    (apply str (map-indexed (fn [i c]
                              (if (and (= \newline c)
                                       (or (zero? i)
                                           (= (dec n) i)
                                           (= \newline (.charAt ^String s (dec i)))))
                                "&#10;"
                                c))
                            s))))

(defn annotation
  "The annotation `s` of a start tag as it's written: with its whitespace
  collapsed, as the parser would collapse it, and with &, > and a last
  hyphen that would make an arrow with the tag's > as character
  references."
  [s]
  (-> (str/escape (str/join " " (chars/words (str s))) {\& "&amp;" \> "&gt;"})
      (str/replace #"--$" "-&#45;")))

(defn class-names
  "The `classes` of a start tag, each after a dot, without the empty ones,
  which the parser would leave out anyway."
  [classes]
  (apply str (for [c classes :when (seq c)]
               (if (re-find #"[\t\n\f\r .>]" c)
                 (refuse (str "A class name can't hold whitespace, . or >: " (pr-str c))
                             :class c)
                 (str "." c)))))

(def tag-names
  "The tag name of each element of cue text."
  {:c "c" :i "i" :b "b" :u "u" :ruby "ruby" :rt "rt" :v "v" :lang "lang"})

(declare write-nodes)

(defn write-element
  "The cue text of the element `node`, in a ruby element when `in-ruby?`."
  [[tag & more :as node] in-ruby?]
  (let [[attrs children] (if (map? (first more)) [(first more) (rest more)] [{} more])
        name             (tag-names tag)]
    (cond
      (= :timestamp tag) (str "<" (format-timestamp (:time attrs)) ">")
      (nil? name)        (refuse (str "Cue text has no element " (pr-str tag)) :tag node)
      (and (= :rt tag) (not in-ruby?))
      (refuse "An rt element must be in a ruby element" :rt node)
      :else
      (let [start (str "<" name (class-names (:class attrs))
                       (case tag
                         :v    (some->> (:voice attrs) annotation not-empty (str " "))
                         :lang (some->> (:lang attrs) annotation not-empty (str " "))
                         nil)
                       ">")]
        ;; a class can't escape the hyphens that would make an arrow with the >
        (when (str/includes? start "-->")
          (refuse "A class name before the > of its tag can't end in --" :class node))
        (str start (write-nodes children (= :ruby tag)) "</" name ">")))))

(defn write-nodes
  "The cue text of the `nodes`, where a sequence is spliced in, nil is
  left out and a number is written as its text, in a ruby element when
  `in-ruby?`."
  [nodes in-ruby?]
  (apply str (for [node nodes]
               (cond
                 (nil? node)    ""
                 (string? node) (text-escape node)
                 (number? node) (str node)
                 (vector? node) (write-element node in-ruby?)
                 (seq? node)    (write-nodes node in-ruby?)
                 :else          (refuse (str "Cue text has no node " (pr-str node)) :node node)))))

(defn cue-text
  "The cue text of the tree `nodes`, or of one element."
  [nodes]
  (without-blank-lines
   (write-nodes (if (keyword? (first nodes)) [nodes] nodes) false)))

;; WebVTT, section 4.1, the WebVTT file body

(defn check-lines
  "The text `s` of a block or a part of one, as it is, or throw when it
  has a carriage return, an empty line or an arrow, which wouldn't parse
  back as written. The message names `s` by `what` it is."
  [s what]
  (cond
    (str/includes? s "\r")
    (refuse (str what " can't hold a carriage return") :carriage-return s)

    (or (= "" s) (str/starts-with? s "\n") (str/ends-with? s "\n") (str/includes? s "\n\n"))
    (refuse (str what " can't hold an empty line") :empty-line s)

    (str/includes? s "-->")
    (refuse (str what " can't hold -->") :arrow s)

    :else s))

(defn header
  "The header of the `file`, or WEBVTT. Its first line may hold anything,
  since the parser reads the line with the signature whole."
  [{:keys [header] :as file}]
  (if (nil? header)
    "WEBVTT"
    (let [s (str header)
          i (str/index-of s "\n")]
      (when-not (parser/signature? s)
        (refuse "A header must start with WEBVTT, followed by a space, a tab or a line break" :header s))
      (when (str/includes? s "\r")
        (refuse "A header can't hold a carriage return" :carriage-return s))
      (when i
        (check-lines (subs s (inc i)) "A header"))
      s)))

(defn region-block
  "The REGION block of the `region`. A region of only defaults gets a
  lines setting anyway, since the word REGION alone isn't a region."
  [region]
  (let [r       (merge settings/region-defaults region)
        id      (:id r)
        pair    (fn [[x y]] (str (percentage x) "," (percentage y)))
        written (cond-> []
                  (seq id)                      (conj (str "id:" id))
                  (contains? region :width)     (conj (str "width:" (percentage (:width r))))
                  (contains? region :region-anchor)
                  (conj (str "regionanchor:" (pair (:region-anchor r))))
                  (contains? region :viewport-anchor)
                  (conj (str "viewportanchor:" (pair (:viewport-anchor r))))
                  (= :up (:scroll r))           (conj "scroll:up"))
        lines   (:lines r)]
    (when (re-find #"[\t\n\f\r ]|-->" id)
      (refuse "A region id can't hold whitespace or -->" :region-id region))
    (when-not (and (number? lines) (<= 0 lines) (< lines ##Inf) (== lines (Math/floor lines)))
      (refuse "The lines of a region must be a whole number from 0" :lines region))
    (str "REGION\n"
         (str/join " " (cond-> written
                         (or (contains? region :lines) (empty? written))
                         (conj (str "lines:" (decimal lines))))))))

(defn style-block
  "The STYLE block of the text `s` of a style sheet."
  [s]
  (when (= "" s)
    (refuse "A style sheet can't be empty" :stylesheet s))
  (str "STYLE\n" (check-lines s "A style sheet")))

;; the settings take their defaults by :or rather than by a merge with
;; cue-defaults, which dev/dk/simongray/webvtt/bench.cljc showed to cost a
;; tenth of emit
(defn line-setting
  "The line setting of the cue `c`, or nil."
  [{:keys [line line-align snap-to-lines?] :or {line :auto line-align :start snap-to-lines? true} :as c}]
  (if (= :auto line)
    (when (or (not= :start line-align) (not snap-to-lines?))
      (refuse "A cue with a line alignment or without snap-to-lines needs a line" :line c))
    (str "line:"
         (if snap-to-lines?
           (if (and (number? line) (< (abs line) ##Inf))
             (decimal line)
             (refuse "A line must be a finite number" :line c))
           (percentage line))
         (when (not= :start line-align) (str "," (name line-align))))))

(defn position-setting
  "The position setting of the cue `c`, or nil."
  [{:keys [position position-align] :or {position :auto position-align :auto} :as c}]
  (if (= :auto position)
    (when (not= :auto position-align)
      (refuse "A cue with a position alignment needs a position" :position c))
    (str "position:" (percentage position)
         (when (not= :auto position-align) (str "," (name position-align))))))

(defn cue-settings
  "The settings of the `cue`, with the `regions` of its file, as written
  after its timings. The region comes last, since a line, size or
  vertical setting after it would take the cue out of the region."
  [{:keys [vertical size align region] :or {vertical :horizontal size 100.0 align :center} :as cue}
   regions]
  (when (and region (not (some #(= region (:id %)) regions)))
    (refuse (str "A cue's region must be one of the file's: " (pr-str region)) :region cue))
  (->> [(when (not= :horizontal vertical) (str "vertical:" (name vertical)))
        (line-setting cue)
        (position-setting cue)
        (when-not (settings/default? settings/cue-defaults :size size)
          (str "size:" (percentage size)))
        (when (not= :center align) (str "align:" (name align)))
        (some->> region (str "region:"))]
       (remove nil?)
       (str/join " ")))

(defn cue-block
  "The cue block of the `cue`, with the `regions` of its file."
  [{:keys [id start-time end-time text] :or {id "" text ""} :as cue} regions]
  (let [written (cue-settings cue regions)]
    (when (re-find #"[\n\r]|-->" (str id))
      (refuse "A cue id can't hold a line break or -->" :cue-id cue))
    (when (seq text)
      (check-lines text "The text of a cue"))
    (str (when (seq id) (str id "\n"))
         (format-timestamp start-time) " --> " (format-timestamp end-time)
         (when (seq written) (str " " written))
         "\n" text)))

(defn emit
  "The text of the `file`."
  [{:keys [regions stylesheets cues] :as file}]
  (str (str/join "\n\n" (concat [(header file)]
                                (map region-block regions)
                                (map style-block stylesheets)
                                (map #(cue-block % regions) cues)))
       "\n"))
