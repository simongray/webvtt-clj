(ns dk.simongray.webvtt-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.webvtt :as webvtt]))

(def example
  (str "WEBVTT - an example\n\n"
       "REGION\nid:left width:40% lines:2 regionanchor:0%,100% viewportanchor:10%,90% scroll:up\n\n"
       "STYLE\n::cue(.loud) { font-weight: bold }\n\n"
       "NOTE a comment, which the parser drops\n\n"
       "intro\n"
       "00:00:01.000 --> 00:00:04.000 position:10%,line-left align:start region:left\n"
       "<v.loud Bob>Hi <00:00:02.000><i>there</i>\n\n"
       "00:00:04.000 --> 00:01:00.500 vertical:rl line:-1,end size:50%\n"
       "Fish &amp; <ruby>漢<rt>kan</rt></ruby>"))

(deftest parse
  (testing "a file"
    (is (= {:header      "WEBVTT - an example"
            :regions     [{:id "left" :width 40.0 :lines 2 :viewport-anchor [10.0 90.0] :scroll :up}]
            :stylesheets ["::cue(.loud) { font-weight: bold }"]
            :cues        [{:id "intro" :start-time 1.0 :end-time 4.0 :position 10.0
                           :position-align :line-left :align :start :region "left"
                           :text "<v.loud Bob>Hi <00:00:02.000><i>there</i>"}
                          {:start-time 4.0 :end-time 60.5 :vertical :rl :line -1.0
                           :line-align :end :size 50.0
                           :text "Fish &amp; <ruby>漢<rt>kan</rt></ruby>"}]}
           (webvtt/parse example))))
  (testing "the header of an HLS segment"
    (is (= "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000"
           (:header (webvtt/parse "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n")))))
  (testing "what isn't WebVTT"
    (is (nil? (webvtt/parse "")))
    (is (nil? (webvtt/parse "1\n00:00:01,000 --> 00:00:02,000\nSRT")))
    (is (= {} (webvtt/parse (str (char 0xFEFF) "WEBVTT\r\n\r\n"))) "a BOM and CRLF")))

(deftest cue-text
  (testing "the tree"
    (is (= [[:v {:voice "Bob" :class ["loud"]} "Hi " [:timestamp {:time 2.0}] [:i {} "there"]]]
           (webvtt/hiccup "<v.loud Bob>Hi <00:00:02.000><i>there</i>")))
    (is (= [[:lang {:lang "da"} "hej " [:c {:class ["a" "b"]} "med " [:u {} "dig"]]]]
           (webvtt/hiccup "<lang da>hej <c.a.b>med <u>dig</u></c></lang>")))
    (is (= [] (webvtt/hiccup {:start-time 0.0 :end-time 1.0}))))
  (testing "the plain text, without ruby text"
    (is (= "Fish & 漢" (webvtt/text (second (:cues (webvtt/parse example))))))
    (is (= "Hi there" (webvtt/text "<v Bob>Hi <00:00:02.000><i>there</i>"))))
  (testing "runs between inline timestamps"
    (is (= [{:start-time 1.0 :text "Never "}
            {:start-time 1.5 :text "drink "}
            {:start-time 2.0 :text "liquid nitrogen"}]
           (webvtt/runs {:start-time 1.0 :end-time 3.0
                         :text "Never <00:00:01.500>drink <00:00:02.000><b>liquid</b> nitrogen"})))
    (is (= [{:start-time 2.0 :text "late"}]
           (webvtt/runs {:start-time 1.0 :end-time 3.0 :text "<00:00:02.000>late"})))))

(deftest timestamps
  (is (= 62.5 (webvtt/parse-timestamp "00:01:02.500")))
  (is (= 62.5 (webvtt/parse-timestamp "01:02.500")))
  (is (= 3600.0 (webvtt/parse-timestamp "1:00:00.000")))
  (is (nil? (webvtt/parse-timestamp "00:01:02,500")))
  (is (nil? (webvtt/parse-timestamp "00:01:02.500 ")))
  (is (= "00:01:02.500" (webvtt/format-timestamp 62.5)))
  (is (= "100:00:00.001" (webvtt/format-timestamp 360000.0006))))

(deftest writing
  (testing "a file reads back as it was"
    (let [file (webvtt/parse example)]
      (is (= file (webvtt/parse (webvtt/emit file))))))
  (testing "a minimal file"
    (is (= "WEBVTT\n" (webvtt/emit {})))
    (is (= "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n"
           (webvtt/emit {:cues [{:start-time 1 :end-time 2 :text "Hi"}]}))))
  (testing "cue text from Hiccup"
    (is (= "<v Bob>Fish &amp; chips</v>" (webvtt/cue-text [[:v {:voice "Bob"} "Fish & chips"]])))
    (is (= "<i>a</i><00:00:01.000>b" (webvtt/cue-text [[:i "a"] [:timestamp {:time 1}] "b"]))
        "an element without its attribute map")
    (is (= "a\n&#10;b" (webvtt/cue-text ["a\n\nb"]))
        "a blank line, which would end the cue")
    (is (= [[:v {:voice "Bob Smith" :class ["x"]} "a < b\n\nc"]]
           (webvtt/hiccup (webvtt/cue-text [:v {:voice " Bob  Smith " :class ["x"]} "a < b\n\nc"])))))
  (testing "what can't be written so that it reads back the same"
    (doseq [file [{:cues [{:start-time 0 :end-time 1 :text "a\n\nb"}]}
                  {:cues [{:start-time 0 :end-time 1 :text "a --> b"}]}
                  {:cues [{:start-time 0 :end-time 1 :id "a\nb"}]}
                  {:cues [{:start-time -1 :end-time 1}]}
                  {:cues [{:start-time 0 :end-time 1 :region "nowhere"}]}
                  {:cues [{:start-time 0 :end-time 1 :line-align :end}]}
                  {:cues [{:start-time 0 :end-time 1 :size 120}]}
                  {:regions [{:id "a b"}]}
                  {:stylesheets [""]}
                  {:header "WEBVTTX"}]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (webvtt/emit file))
          (pr-str file)))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (webvtt/cue-text [[:rt "kan"]])))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (webvtt/cue-text [[:span "no such element"]])))))
