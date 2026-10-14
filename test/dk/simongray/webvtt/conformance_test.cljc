(ns dk.simongray.webvtt.conformance-test
  "The conformance errors of WebVTT files, with a case or more for each type
  of error, since no test suite for them exists."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.webvtt :as webvtt]))

(defn cue
  "A file of one cue from 00:00.000 to 00:03.000 with the `settings` and
  the `text`."
  [settings text]
  (str "WEBVTT\n\n00:00.000 --> 00:03.000" settings "\n" text "\n"))

(def cases
  "Files, each with its kind of track and its errors as triples of the
  type, the line and the column."
  [;; section 4.1, the file
   ["WEBVT\n" :subtitles [[:signature 1 1]]]
   ["WEBVTT\nKind: captions\n\n00:00.000 --> 00:01.000\nHi" :subtitles [[:header 2 1]]]
   ["WEBVTT" :subtitles [[:header 1 7]]]
   ["WEBVTT\n\n00:00.000 --> 00:01.000\nA\n00:01.000 --> 00:02.000\nB" :subtitles
    [[:missing-blank-line 5 1]]]
   ["WEBVTT\n\nhello\n\n00:00.000 --> 00:01.000\nA" :subtitles [[:block-without-timings 3 1]]]
   ["WEBVTT\n\n00:00.000 --> 00:01.000\nA\n\nSTYLE\n::cue {}" :subtitles [[:style-after-cue 6 1]]]
   ["WEBVTT\n\n00:00.000 --> 00:01.000\nA\n\nREGION\nid:r" :subtitles [[:region-after-cue 6 1]]]
   ["WEBVTT\n\n00:00.000 --> 00:0x.000\nA" :subtitles [[:timings 3 1]]]
   ["WEBVTT\n\n0:00:00.000 --> 00:00:01.000\nA" :subtitles [[:timestamp 3 1]]]
   ["WEBVTT\n\n00:00.000 -->00:01.000\nA" :subtitles [[:timing-whitespace 3 14]]]
   ["WEBVTT\n\n00:00.000 --> 00:01.000align:start\nA" :subtitles [[:timing-whitespace 3 24]]]
   ["WEBVTT\n\n00:01.000 --> 00:01.000\nA" :subtitles [[:end-not-after-start 3 1]]]
   ["WEBVTT\n\n00:02.000 --> 00:03.000\nA\n\n00:01.000 --> 00:04.000\nB" :subtitles
    [[:cues-out-of-order 6 1]]]
   ["WEBVTT\n\na\n00:01.000 --> 00:02.000\nA\n\na\n00:02.000 --> 00:03.000\nB" :subtitles
    [[:duplicate-cue-id 7 1]]]

   ;; section 4.4, cue settings
   [(cue " align:middle" "A") :subtitles [[:invalid-setting 3 25]]]
   [(cue " line:1.5 size:101% foo:bar align" "A") :subtitles
    [[:invalid-setting 3 25] [:invalid-setting 3 34] [:invalid-setting 3 44] [:invalid-setting 3 52]]]
   [(cue " align:start align:end" "A") :subtitles [[:duplicate-setting 3 37]]]
   [(cue " region:r" "A") :subtitles [[:unknown-region 3 25]]]
   [(cue " size:50% align:start" "A") :subtitles [[:auto-position 3 1]]]

   ;; section 4.3, regions
   ["WEBVTT\n\nREGION\nid:r width:101%\n\n" :subtitles [[:invalid-region-setting 4 6]]]
   ["WEBVTT\n\nREGION\nid:r lines:2 lines:3\n\n" :subtitles [[:duplicate-region-setting 4 14]]]
   ["WEBVTT\n\nREGION\nwidth:50%\n\n" :subtitles [[:region-without-id 3 1]]]
   ["WEBVTT\n\nREGION\nid:r\n\nREGION\nid:r\n\n" :subtitles [[:duplicate-region-id 6 1]]]

   ;; section 4.2.2, cue text
   [(cue "" "<x>a</x>") :subtitles [[:unknown-tag 4 1] [:unexpected-end-tag 4 5]]]
   [(cue "" "<rt>a</rt>") :subtitles [[:rt-outside-ruby 4 1] [:unexpected-end-tag 4 6]]]
   [(cue "" "<ruby>a</ruby>") :subtitles [[:ruby-without-rt 4 1]]]
   [(cue "" "a <b>b") :subtitles [[:unclosed-tag 4 3]]]
   [(cue "" "a</b>") :subtitles [[:unexpected-end-tag 4 2]]]
   [(cue "" "<c.>a</c>") :subtitles [[:invalid-class 4 1]]]
   [(cue "" "<v>a</v>") :subtitles [[:missing-annotation 4 1]]]
   [(cue "" "<b x>a</b>") :subtitles [[:annotation-not-allowed 4 1]]]
   [(cue "" "<lang en-UK>a</lang>") :subtitles [[:invalid-language-tag 4 1]]]
   [(cue "" "Q&A, &nope; &amp &#0; &#x80;") :subtitles
    [[:invalid-character-reference 4 2] [:invalid-character-reference 4 6]
     [:invalid-character-reference 4 13] [:invalid-character-reference 4 18]
     [:invalid-character-reference 4 23]]]
   [(cue "" "a <1:00:00.000>b") :subtitles [[:invalid-timestamp 4 3]]]
   [(cue "" "a <00:02.000>b <00:01.500>c") :subtitles [[:timestamp-out-of-order 4 16]]]
   [(cue "" "a <00:05.000>b") :subtitles [[:timestamp-after-end 4 3]]]

   ;; sections 4.2.3 and 4.5.1, chapters
   [(cue "" "<b>Intro</b>") :chapters
    [[:markup-in-chapter-title 4 1] [:markup-in-chapter-title 4 9]]]
   ["WEBVTT\n\n00:00.000 --> 00:10.000\nOne\n\n00:05.000 --> 00:15.000\nTwo" :chapters
    [[:overlapping-chapters 6 1]]]])

(deftest error-types
  (doseq [[s kind expected] cases]
    (testing (pr-str s)
      (is (= expected (map (juxt :type :line :column) (webvtt/conformance-errors s kind))))))
  (testing "each type has a case"
    (is (= (set (keys webvtt/error-types))
           (set (for [[_ _ errors] cases [type] errors] type))))))

(def conforming
  "A file of every feature, which conforms as subtitles."
  (str "WEBVTT - an example\n\n"
       "REGION\nid:left width:40% lines:2 regionanchor:0%,100%\nviewportanchor:10%,90% scroll:up\n\n"
       "STYLE\n::cue(.loud) { font-weight: bold }\n\n"
       "NOTE a comment\nover two lines\n\n"
       "intro\n"
       "00:00:01.000 --> 00:00:04.000 position:10%,line-left align:start region:left\n"
       "<v.loud Bob>Hi <00:00:02.000><i>there</i>\n\n"
       "00:00:04.000 --> 00:01:00.500 vertical:rl line:-1,end size:50% position:50%\n"
       "Fish &amp; <ruby>漢<rt>kan</rt></ruby> <lang en-GB><b>chips</b></lang>\n\n"
       "00:01:00.000 --> 00:01:01.000\n<v Alice>Oh <c.quiet>no</c></v>\n"))

(deftest conforming-files
  (is (= [] (webvtt/conformance-errors conforming)))
  (is (webvtt/conforming? conforming :captions))
  (is (webvtt/conforming? (str "WEBVTT\n\n00:00.000 --> 01:00.000\nPart one\n\n"
                               "00:00.000 --> 00:30.000\nIntroduction\n\n"
                               "00:30.000 --> 01:00.000\nTopics &amp; people\n\n"
                               "01:00.000 --> 02:00.000\nPart two\n")
                          :chapters))
  (testing "metadata is any text"
    (let [s (cue "" "{\"a\": \"<b> & c\"}")]
      (is (webvtt/conforming? s :metadata))
      (is (not (webvtt/conforming? s))))))

(deftest kinds
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (webvtt/conformance-errors "WEBVTT\n" :karaoke))))
