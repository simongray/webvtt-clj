(ns dk.simongray.webvtt.generative-test
  "Properties of the library over random files, cue text and text: what
  emit and cue-text write reads back the same, and no text makes the
  parser throw."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.webvtt :as webvtt]))

(def runs
  "The number of random cases of each property."
  200)

(defn passes?
  "Whether the `property` holds for the random cases, with the result
  printed when it doesn't."
  [property]
  (let [result (tc/quick-check runs property)]
    (or (:pass? result)
        (do (println (pr-str (select-keys result [:shrunk :fail]))) false))))

(defn text-of
  "A generator of text of one to `n` characters from `chars`."
  [chars n]
  (gen/fmap str/join (gen/vector (gen/elements chars) 1 n)))

(def text-chars
  "The characters of random text, markup and line breaks among them."
  (seq "ab æ漢<>&;#.-:\n\r\t"))

(def name-chars
  "The characters of random names, such as classes, voices and ids."
  (seq "abcxyz019_-"))

(def timestamp-gen
  "A generator of the seconds of a timestamp, as the parser reads them."
  (gen/fmap #(webvtt/parse-timestamp (webvtt/format-timestamp (/ % 1000.0)))
            (gen/choose 0 360000000)))

(def percentage-gen
  "A generator of percentages."
  (gen/double* {:min 0 :max 100 :NaN? false :infinite? false}))

(defn joined
  "The `nodes` of a tree with each string that follows a string joined to
  it, as the parser has them."
  [nodes]
  (reduce (fn [acc node]
            (if (and (string? node) (string? (peek acc)))
              (conj (pop acc) (str (peek acc) node))
              (conj acc node)))
          []
          nodes))

(defn element-gen
  "A generator of elements, whose children `inner` generates."
  [inner]
  (gen/let [tag      (gen/elements [:c :i :b :u :v :lang :ruby])
            classes  (gen/vector (gen/such-that #(not (str/ends-with? % "--")) (text-of name-chars 4))
                                 0 2)
            voice    (text-of name-chars 6)
            children (gen/vector inner 0 3)
            rt       (gen/vector (text-of text-chars 4) 0 1)]
    (into [tag (cond-> {}
                 (seq classes)  (assoc :class classes)
                 (= :v tag)     (assoc :voice voice)
                 (= :lang tag)  (assoc :lang voice))]
          (joined (cond-> children
                    (= :ruby tag) (into (for [s rt] [:rt {} s])))))))

(def nodes-gen
  "A generator of trees of cue text, as the parser has them."
  (gen/fmap joined
            (gen/vector (gen/recursive-gen element-gen
                                           (gen/one-of [(text-of text-chars 6)
                                                        (gen/fmap (fn [t] [:timestamp {:time t}])
                                                                  timestamp-gen)]))
                        0 4)))

(deftest cue-text-reads-back
  (is (passes? (prop/for-all [nodes nodes-gen]
                 (= nodes (webvtt/hiccup (webvtt/cue-text nodes)))))))

(defn without-defaults
  "The map `m` without the attributes whose values are their `defaults`, and
  without nil."
  [defaults m]
  (into {} (remove (fn [[k v]]
                     (or (nil? v)
                         (let [d (get defaults k)]
                           (if (and (number? d) (number? v)) (== d v) (= d v))))))
        m))

(def region-gen
  "A generator of regions without ids."
  (gen/let [width   (gen/one-of [(gen/return nil) percentage-gen])
            lines   (gen/one-of [(gen/return nil) (gen/choose 0 20)])
            anchor  (gen/one-of [(gen/return nil) (gen/tuple percentage-gen percentage-gen)])
            port    (gen/one-of [(gen/return nil) (gen/tuple percentage-gen percentage-gen)])
            scroll? gen/boolean]
    (without-defaults webvtt/region-defaults
                      {:width width :lines lines :region-anchor anchor :viewport-anchor port
                       :scroll (when scroll? :up)})))

(defn cue-gen
  "A generator of cues, in the regions of the `region-ids`."
  [region-ids]
  (gen/let [start    timestamp-gen
            end      timestamp-gen
            id       (gen/one-of [(gen/return "")
                                  (gen/such-that #(not (str/includes? % "-->"))
                                                 (text-of (concat name-chars "  :>") 8))])
            nodes    nodes-gen
            vertical (gen/elements [nil :rl :lr])
            line     (gen/one-of [(gen/return nil)
                                  (gen/tuple (gen/one-of [(gen/fmap double (gen/choose -20 20))
                                                          (gen/double* {:NaN? false :infinite? false})])
                                             (gen/return true)
                                             (gen/elements [:start :center :end]))
                                  (gen/tuple percentage-gen (gen/return false)
                                             (gen/elements [:start :center :end]))])
            position (gen/one-of [(gen/return nil)
                                  (gen/tuple percentage-gen
                                             (gen/elements [:auto :line-left :center :line-right]))])
            size     (gen/one-of [(gen/return nil) percentage-gen])
            align    (gen/elements [nil :start :center :end :left :right])
            region   (gen/elements (cons nil region-ids))]
    (let [[line snap? line-align]  line
          [position position-align] position]
      (without-defaults webvtt/cue-defaults
                        {:start-time start :end-time end :id (str/trim id)
                         :text (webvtt/cue-text nodes)
                         :vertical vertical :line line :snap-to-lines? snap? :line-align line-align
                         :position position :position-align position-align
                         :size size :align align :region region}))))

(def lines-gen
  "A generator of lines of text without an arrow, separated by line feeds."
  (gen/fmap #(str/join "\n" %)
            (gen/vector (gen/such-that #(not (str/includes? % "-->"))
                                       (text-of (remove #{\newline \return} text-chars) 10))
                        1 3)))

(def file-gen
  "A generator of files, as the parser has them."
  (gen/let [header      (gen/one-of [(gen/return nil)
                                     (gen/fmap #(str "WEBVTT " %) (text-of name-chars 8))
                                     (gen/fmap #(str "WEBVTT\n" %) lines-gen)])
            ids         (gen/set (text-of name-chars 4) {:max-elements 3})
            regions     (gen/vector region-gen (count ids))
            stylesheets (gen/vector lines-gen 0 2)
            cues        (gen/vector (cue-gen (vec ids)) 0 5)]
    (without-defaults {:regions [] :stylesheets [] :cues []}
                      {:header      header
                       :regions     (mapv #(assoc %1 :id %2) regions ids)
                       :stylesheets stylesheets
                       :cues        cues})))

(deftest files-read-back
  (is (passes? (prop/for-all [file file-gen]
                 (= file (webvtt/parse (webvtt/emit file)))))))

(def chaos-gen
  "A generator of files of the characters that WebVTT gives a meaning to,
  in random order."
  (gen/fmap #(str "WEBVTT\n\n" %)
            (text-of (seq "0123:.-> \t\n\n\n\r\f\u0000aNOTESTYLREGIONid&#;<>/vcibu%,") 200)))

(deftest any-text
  (is (passes? (prop/for-all [s chaos-gen]
                 (let [file (webvtt/parse s)]
                   (doseq [cue (:cues file)]
                     (webvtt/hiccup cue)
                     (webvtt/text cue)
                     (webvtt/runs cue))
                   (webvtt/conformance-errors s)
                   (webvtt/conformance-errors s :chapters)
                   (= file (webvtt/parse (webvtt/emit file))))))))
