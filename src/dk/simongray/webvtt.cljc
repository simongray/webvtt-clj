(ns dk.simongray.webvtt
  "Parse, write and check WebVTT files, by the WebVTT spec of the W3C.

  A file is a plain map of the parts it has:

  - :header, the header as written, from WEBVTT to the first blank line,
    when it's more than WEBVTT (e.g. the X-TIMESTAMP-MAP of HLS)
  - :regions, a vector of region maps
  - :stylesheets, a vector of the text of each STYLE block
  - :cues, a vector of cue maps

  A cue or a region only has the attributes whose values aren't the
  defaults of the spec, which cue-defaults and region-defaults hold. A cue
  always has a :start-time and an :end-time, in seconds, and its :text is
  the cue text as written.

  The functions that read cue text take a cue or its text, and return its
  tree as Hiccup:

      [[:v {:voice \"Bob\" :class [\"loud\"]}
        \"Hi \" [:timestamp {:time 2.0}] [:i {} \"there\"]]]

  The elements are named after the tags of cue text: :c, :i, :b, :u,
  :ruby, :rt, :v and :lang. Each has an attribute map, with its :class
  when it has any, and the :voice of a :v or the :lang of a :lang. An
  inline timestamp is an empty :timestamp element whose :time is when the
  text after it is spoken."
  (:require [dk.simongray.webvtt.impl.conformance :as conformance]
            [dk.simongray.webvtt.impl.cue-text :as cue-text]
            [dk.simongray.webvtt.impl.parser :as parser]
            [dk.simongray.webvtt.impl.settings :as settings]
            [dk.simongray.webvtt.impl.writer :as writer]))

(def cue-defaults
  "The attributes of a cue that a cue map leaves out (WebVTT, section 3.3),
  e.g. :align :center. A :line or :position of :auto is chosen when the cue
  is rendered."
  settings/cue-defaults)

(def region-defaults
  "The attributes of a region that a region map leaves out (WebVTT,
  section 3.4), e.g. :lines 3."
  settings/region-defaults)

;; WebVTT, section 6.1
(defn parse
  "The file map of the text `s`, or nil when it isn't a WebVTT file, i.e.
  when it doesn't start with WEBVTT.

  Like a browser, the parser never throws on text, and leaves out what it
  can't read, e.g. a cue whose timings are malformed.

      (parse \"WEBVTT\\n\\n00:01.000 --> 00:04.000 align:start\\nHi\")
      ;; => {:cues [{:start-time 1.0 :end-time 4.0 :align :start
      ;;             :text \"Hi\"}]}"
  [s]
  (parser/parse s))

;; WebVTT, section 6.4
(defn hiccup
  "The tree of the text of the `cue`, a cue map or its text, as a vector
  of Hiccup nodes.

      (hiccup \"<v Bob>Hi <i>there</i>\")
      ;; => [[:v {:voice \"Bob\"} \"Hi \" [:i {} \"there\"]]]"
  [cue]
  (cue-text/nodes (cue-text/text-of cue)))

;; WebVTT, section 6.6
(defn text
  "The plain text of the `cue`, a cue map or its text, by the rules for
  extracting a chapter title: its character references decoded, and
  without its tags, timestamps and ruby text.

      (text \"<v Bob>Fish &amp; chips\")
      ;; => \"Fish & chips\""
  [cue]
  (cue-text/text (hiccup cue)))

(defn runs
  "The text of the `cue` split at its inline timestamps, as maps of the
  :start-time and the :text of each run, e.g. for the words of a
  karaoke-style highlight. The first run starts with the cue, and a run
  without text is left out.

      (runs {:start-time 1.0 :end-time 3.0
             :text \"Never <00:00:01.500>drink\"})
      ;; => [{:start-time 1.0 :text \"Never \"}
      ;;     {:start-time 1.5 :text \"drink\"}]"
  [cue]
  (cue-text/runs (hiccup cue) (:start-time cue)))

;; WebVTT, section 6.3, "collect a WebVTT timestamp"
(defn parse-timestamp
  "The seconds of the WebVTT timestamp `s`, e.g. 62.5 for 00:01:02.500, or
  nil when `s` is anything else."
  [s]
  (cue-text/inline-timestamp (str s)))

(defn format-timestamp
  "The `seconds` as a WebVTT timestamp, e.g. 00:01:02.500 for 62.5,
  rounded to the millisecond."
  [seconds]
  (writer/format-timestamp seconds))

;; WebVTT, section 4.2.2
(defn cue-text
  "The cue text of the tree `nodes`, Hiccup as hiccup returns it, or of
  one element. Text is escaped, and an element can leave out its
  attribute map.

      (cue-text [[:v {:voice \"Bob\"} \"Fish & chips\"]])
      ;; => \"<v Bob>Fish &amp; chips</v>\"

  It throws an ex-info for a tree that wouldn't read back the same, e.g.
  one with an rt element outside a ruby element."
  [nodes]
  (writer/cue-text nodes))

(def error-types
  "Each type of conformance error, with the section of the spec whose rule
  it breaks and what that rule says, e.g. :end-not-after-start, a cue that
  ends at or before its start (section 4.1)."
  conformance/error-types)

;; WebVTT, section 4
(defn conformance-errors
  "The conformance errors of the text `s` as a WebVTT file of the `kind`
  of track, in the order of the file. Each error is a map of its :type,
  one of error-types, and the :line and :column where it is.

      (conformance-errors
       \"WEBVTT\\n\\n00:00:02.000 --> 00:00:01.000 align:left align:start\\nHi\")
      ;; => [{:type :end-not-after-start :line 3 :column 1}
      ;;     {:type :duplicate-setting :line 3 :column 42}]

  The kinds are those of HTML's track element, and pick the rules for the
  text of cues:

  - :subtitles, the default, :captions and :descriptions, cue text
  - :chapters, chapter titles, of chapters that nest
  - :metadata, any text

  A file that parse reads can still have errors, e.g. a cue whose
  timings don't parse, which parse leaves out."
  ([s]
   (conformance-errors s :subtitles))
  ([s kind]
   (when-not (contains? conformance/kinds kind)
     (throw (ex-info (str "No such kind of track: " (pr-str kind)) {:kind kind})))
   (conformance/errors (str s) kind)))

(defn conforming?
  "Whether the text `s` is a conforming WebVTT file of the `kind` of track,
  :subtitles by default, as conformance-errors has the kinds."
  ([s]
   (conforming? s :subtitles))
  ([s kind]
   (empty? (conformance-errors s kind))))

;; WebVTT, section 4.1
(defn emit
  "The text of the `file`, a file map as parse returns it, which parse
  reads back as the same map.

  It throws an ex-info for a file that wouldn't read back the same, e.g.
  one with a blank line in the :text of a cue, or a cue in a region that
  the file doesn't have. A file that merely doesn't conform is written as
  it is, e.g. one with its cues out of order."
  [file]
  (writer/emit file))
