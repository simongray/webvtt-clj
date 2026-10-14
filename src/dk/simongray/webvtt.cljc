(ns dk.simongray.webvtt
  "Parse, write and check WebVTT files, as the W3C's WebVTT spec defines
  them.

  A file is a plain map of the parts it has:

  - :header, the text from WEBVTT to the first blank line, when there's
    more to it than WEBVTT (e.g. the X-TIMESTAMP-MAP of an HLS segment)
  - :regions, a vector of region maps
  - :stylesheets, a vector of the text of each STYLE block
  - :cues, a vector of cue maps

  A cue or a region only has the attributes that differ from the spec's
  defaults, which cue-defaults and region-defaults hold. A cue always has
  a :start-time and an :end-time in seconds. Its :text is the cue text as
  written.

  The functions that parse cue text take a cue or its text. The tree that
  hiccup returns looks like this:

      [[:v {:voice \"Bob\" :class [\"loud\"]}
        \"Hi \" [:timestamp {:time 2.0}] [:i {} \"there\"]]]

  The elements are named after the tags of cue text: :c, :i, :b, :u,
  :ruby, :rt, :v and :lang. Each has an attribute map, which holds its
  :class when it has classes, and the :voice of a :v or the :lang of a
  :lang. An inline timestamp is an empty :timestamp element whose :time is
  when the text after it is spoken."
  (:require [dk.simongray.webvtt.impl.conformance :as conformance]
            [dk.simongray.webvtt.impl.cue-text :as cue-text]
            [dk.simongray.webvtt.impl.parser :as parser]
            [dk.simongray.webvtt.impl.settings :as settings]
            [dk.simongray.webvtt.impl.writer :as writer]))

(def cue-defaults
  "The default attributes of a cue, which a cue map leaves out (WebVTT,
  section 3.3), e.g. :align :center. A browser works out a :line or a
  :position of :auto when it renders the cue."
  settings/cue-defaults)

(def region-defaults
  "The default attributes of a region, which a region map leaves out
  (WebVTT, section 3.4), e.g. :lines 3."
  settings/region-defaults)

;; WebVTT, section 6.1
(defn parse
  "The file map of the text `s`, or nil when it isn't a WebVTT file, i.e.
  when it doesn't start with WEBVTT.

  Like a browser, it never throws on text and leaves out what it can't
  read, e.g. a cue with malformed timings.

      (parse \"WEBVTT\\n\\n00:01.000 --> 00:04.000 align:start\\nHi\")
      ;; => {:cues [{:start-time 1.0 :end-time 4.0 :align :start
      ;;             :text \"Hi\"}]}"
  [s]
  (parser/parse s))

;; WebVTT, section 6.4
(defn hiccup
  "The text of the `cue` (a cue map or its text) parsed into a vector of
  Hiccup nodes.

      (hiccup \"<v Bob>Hi <i>there</i>\")
      ;; => [[:v {:voice \"Bob\"} \"Hi \" [:i {} \"there\"]]]"
  [cue]
  (cue-text/nodes (cue-text/text-of cue)))

;; WebVTT, section 6.6
(defn text
  "The plain text of the `cue` (a cue map or its text), with its
  character references decoded and without its tags, timestamps or ruby
  text, as the spec has it for the title of a chapter.

      (text \"<v Bob>Fish &amp; chips\")
      ;; => \"Fish & chips\""
  [cue]
  (cue-text/text (hiccup cue)))

(defn runs
  "The text of the `cue` split at its inline timestamps into runs, as maps
  of the :start-time and the :text of each, e.g. to highlight each word as
  it's spoken. The first run starts with the cue, and there's no run
  without text.

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
  "The cue text of the Hiccup `nodes`, a tree like those that hiccup
  returns or a single element. It escapes the text, and an element may
  leave out its attribute map.

      (cue-text [[:v {:voice \"Bob\"} \"Fish & chips\"]])
      ;; => \"<v Bob>Fish &amp; chips</v>\"

  It throws an ex-info for a tree that wouldn't parse back the same, e.g.
  one with an rt element outside a ruby element."
  [nodes]
  (writer/cue-text nodes))

(def error-types
  "Each type of conformance error, with the section of the spec that it
  breaks and what's wrong, e.g. :end-not-after-start, for a cue that ends
  at or before its start (section 4.1)."
  conformance/error-types)

;; WebVTT, section 4
(defn conformance-errors
  "The conformance errors of the text `s` as a WebVTT file for the `kind`
  of track, in the order they come in the file. Each error is a map of its
  :type (a key of error-types) and the :line and :column where it is.

      (conformance-errors
       \"WEBVTT\\n\\n00:00:02.000 --> 00:00:01.000 align:left align:start\\nHi\")
      ;; => [{:type :end-not-after-start :line 3 :column 1}
      ;;     {:type :duplicate-setting :line 3 :column 42}]

  The kind of track sets the rules for the text of cues. The kinds are
  those of HTML's track element:

  - :subtitles (the default), :captions and :descriptions check cue text
  - :chapters checks chapter titles and that the chapters nest
  - :metadata allows any text

  A file can parse and still have errors, e.g. a cue with timings that
  don't parse, which parse leaves out."
  ([s]
   (conformance-errors s :subtitles))
  ([s kind]
   (when-not (contains? conformance/kinds kind)
     (throw (ex-info (str "No such kind of track: " (pr-str kind)) {:kind kind})))
   (conformance/errors (str s) kind)))

(defn conforming?
  "Whether the text `s` is a conforming WebVTT file for the `kind` of track
  (:subtitles by default), which is one of the kinds of
  conformance-errors."
  ([s]
   (conforming? s :subtitles))
  ([s kind]
   (empty? (conformance-errors s kind))))

;; WebVTT, section 4.1
(defn emit
  "The text of the `file`, a map like those that parse returns, which
  parses back into the same map.

  It throws an ex-info for a file that wouldn't parse back the same, e.g.
  one with a blank line in the :text of a cue, or a cue in a region that
  the file doesn't have. It still writes a file that merely doesn't
  conform, e.g. one with its cues out of order."
  [file]
  (writer/emit file))
