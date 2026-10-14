(ns dk.simongray.webvtt.wpt-test
  "The library against the WebVTT parsing tests of web-platform-tests, from
  the files that dev/fetch.sh puts in dev-resources/wpt. The expectations
  of the file parsing tests are in test/wpt/file-parsing.edn, translated
  from their JavaScript."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.webvtt :as webvtt]
            [dk.simongray.webvtt.impl.chars :as chars]
            [dk.simongray.webvtt.test-data :as test-data]))

(def file-parsing
  "Where the file parsing tests are."
  (str test-data/wpt "webvtt/parsing/file-parsing/"))

(def expectations
  "The expectations of the file parsing tests."
  (delay (test-data/edn "test/wpt/file-parsing.edn")))

(defn region-named
  "The region of the `file` that a cue's region setting names by its `id`:
  the last one with that id."
  [file id]
  (last (filter #(= id (:id %)) (:regions file))))

(defn vttregion
  "The attributes of the `region` as VTTRegion has them."
  [region]
  (let [{:keys [region-anchor viewport-anchor] :as r} (merge webvtt/region-defaults region)]
    {:id              (:id r)
     :width           (:width r)
     :lines           (:lines r)
     :regionAnchorX   (first region-anchor)
     :regionAnchorY   (second region-anchor)
     :viewportAnchorX (first viewport-anchor)
     :viewportAnchorY (second viewport-anchor)
     :scroll          (case (:scroll r) :none "" :up "up")}))

(defn vttcue
  "The attributes of the `cue` of the `file` as VTTCue has them."
  [file cue]
  (let [c    (merge webvtt/cue-defaults cue)
        auto #(if (= :auto %) "auto" %)]
    {:id            (:id c)
     :startTime     (:start-time c)
     :endTime       (:end-time c)
     :text          (:text c)
     :region        (some->> (:region c) (region-named file) vttregion)
     :vertical      (case (:vertical c) :horizontal "" :rl "rl" :lr "lr")
     :snapToLines   (:snap-to-lines? c)
     :line          (auto (:line c))
     :lineAlign     (name (:line-align c))
     :position      (auto (:position c))
     :positionAlign (name (:position-align c))
     :size          (:size c)
     :align         (name (:align c))}))

(defn negative-zero?
  "Whether the number `x` is -0."
  [x]
  (and (zero? x) (neg? (/ 1.0 (double x)))))

(defn same-value?
  "Whether `a` and `b` are the same by JavaScript's SameValue, which
  WPT's assert_equals uses: so 1 and 1.0 are, while 0 and -0 aren't."
  [a b]
  (if (and (number? a) (number? b))
    (and (== a b) (= (negative-zero? a) (negative-zero? b)))
    (= a b)))

(defn matches?
  "Whether the attributes `actual` have the values of the attributes
  `expected`, a map of some of them."
  [expected actual]
  (every? (fn [[k v]]
            (let [a (get actual k)]
              (if (map? v)
                (and (map? a) (matches? v a))
                (same-value? v a))))
          expected))

(deftest file-parsing-tests
  (doseq [[name expected] (:cues @expectations)
          :let [s (test-data/text (str file-parsing "tests/support/" name ".vtt"))]
          :when s]
    (testing name
      (let [file (webvtt/parse s)
            cues (:cues file)]
        (is (some? file))
        (is (= (count expected) (count cues)) "the number of cues")
        (doseq [[i e cue] (map vector (range) expected cues)
                :let [actual (vttcue file cue)]]
          (is (matches? e actual)
              (str "cue " i ": " (pr-str e) " but " (pr-str (select-keys actual (keys e))))))))))

(deftest not-webvtt
  (doseq [name (:not-webvtt @expectations)
          :let [s (test-data/text (str file-parsing "support/" name ".vtt"))]
          :when s]
    (is (nil? (webvtt/parse s)) name)))

;; The cue text parsing tests, whose .dat files the tests read as
;; buildtests.py reads them, and whose expected trees are the DOM that
;; section 6.5 builds, printed by common.js

(def cue-text-parsing
  "Where the cue text parsing tests are."
  (str test-data/wpt "webvtt/parsing/cue-text-parsing/"))

(defn unescape
  "The line `s` of a .dat file with the Python escapes that the files use
  decoded: \\n, \\t, \\xhh and \\uhhhh."
  [s]
  (str/replace s #"\\(?:x([0-9a-fA-F]{2})|u([0-9a-fA-F]{4})|(.))"
               (fn [[escape x u c]]
                 (cond
                   (or x u)   (chars/code-point->string #?(:clj  (Long/parseLong (or x u) 16)
                                                           :cljs (js/parseInt (or x u) 16)))
                   (= "n" c)  "\n"
                   (= "t" c)  "\t"
                   (= "\\" c) "\\"
                   :else      escape))))

(defn dat-cases
  "The cases of the .dat file `text`, as maps of the :input and the
  :expected tree."
  [text]
  (let [step (fn [{:keys [state] :as acc} line]
               (cond
                 (str/starts-with? line "#")
                 (cond-> (assoc acc :state line)
                   (= "#document-fragment" line) (update :expected str line "\n"))

                 (= "#data" state)
                 (update acc :input str (unescape line) "\n")

                 (= "#errors" state)
                 acc

                 (and (= "#document-fragment" state) (= "" line))
                 (-> acc
                     (update :cases conj (select-keys acc [:input :expected]))
                     (assoc :state nil :input "" :expected ""))

                 :else
                 (update acc :expected str (unescape line) "\n")))
        done (reduce step {:input "" :expected "" :cases []} (str/split-lines text))
        drop-newline #(subs % 0 (dec (count %)))]
    (for [{:keys [input expected]} (cond-> (:cases done)
                                     (:state done) (conj (select-keys done [:input :expected])))]
      {:input (drop-newline input) :expected (drop-newline expected)})))

(defn dom-lines
  "The lines of the DOM of the `nodes`, at the `depth`, as common.js
  prints a tree."
  [nodes depth]
  (let [indent      (apply str "|" (repeat (dec (* 2 depth)) " "))
        attr-indent (apply str "|" (repeat (inc (* 2 depth)) " "))]
    (mapcat (fn [node]
              (cond
                (string? node)
                [(str indent "\"" node "\"")]

                (= :timestamp (first node))
                [(str indent "<?timestamp " (webvtt/format-timestamp (:time (second node))) ">")]

                :else
                (let [[tag attrs & children] node
                      attributes (cond-> (sorted-map)
                                   (seq (:class attrs)) (assoc "class" (str/join " " (:class attrs)))
                                   (= :v tag)           (assoc "title" (:voice attrs))
                                   (= :lang tag)        (assoc "lang" (:lang attrs)))]
                  (concat [(str indent "<" (case tag (:c :v :lang) "span" (name tag)) ">")]
                          (for [[k v] attributes]
                            (str attr-indent k "=\"" v "\""))
                          (dom-lines children (inc depth))))))
            nodes)))

(defn dom-tree
  "The DOM of the `nodes` of a tree, as common.js prints it."
  [nodes]
  (str/join "\n" (cons "#document-fragment" (dom-lines nodes 1))))

(defn merged
  "The printed `tree` with each text node that follows another text node
  joined to it, as the library's tree has them."
  [tree]
  (let [t (str/replace tree #"(?m)^(\| +)\"(.*)\"\n\1\"" "$1\"$2")]
    (if (= t tree) t (recur t))))

;; The WebVTT tests of HTML's track element, whose expectations are in
;; test/wpt/track-element.edn

(def track-element
  "Where the inputs of the track element tests are."
  (str test-data/wpt "html/semantics/embedded-content/media-elements/track/track-element/resources/"))

(def track-expectations
  "The expectations of the track element tests."
  (delay (test-data/edn "test/wpt/track-element.edn")))

(defn dom
  "The `nodes` of a tree as the DOM that section 6.5 builds, in HTML
  Hiccup."
  [nodes]
  (mapv (fn [node]
          (cond
            (string? node)              node
            (= :timestamp (first node)) [:?timestamp (webvtt/format-timestamp (:time (second node)))]
            :else
            (let [[tag attrs & children] node]
              (into [(case tag (:c :v :lang) :span tag)
                     (cond-> {}
                       (seq (:class attrs)) (assoc :class (str/join " " (:class attrs)))
                       (= :v tag)           (assoc :title (:voice attrs))
                       (= :lang tag)        (assoc :lang (:lang attrs)))]
                    (dom children)))))
        nodes))

(defn joined
  "The HTML Hiccup `nodes` with each string that follows a string joined
  to it."
  [nodes]
  (reduce (fn [acc node]
            (let [node (if (vector? node) (into (subvec node 0 2) (joined (subvec node 2))) node)]
              (if (and (string? node) (string? (peek acc)))
                (conj (pop acc) (str (peek acc) node))
                (conj acc node))))
          []
          nodes))

(defn text-content
  "The textContent of the DOM of the `nodes` of a tree, ruby text
  included."
  [nodes]
  (apply str (for [node nodes]
               (cond
                 (string? node)              node
                 (= :timestamp (first node)) ""
                 :else                       (text-content (drop 2 node))))))

(deftest track-element-tests
  (doseq [[name expected] (:files @track-expectations)
          :let [s (test-data/text (str track-element name ".vtt"))]
          :when s]
    (testing name
      (let [file (webvtt/parse s)
            cues (:cues file)
            {expected-cues :cues :keys [fragments text-content]} expected
            n    (or (:count expected) (count (or expected-cues fragments text-content)))]
        (if (nil? expected)
          (is (nil? file) "not WebVTT")
          (do (is (= n (count cues)) "the number of cues")
              (doseq [[i e cue] (map vector (range) expected-cues cues)
                      :let [actual (vttcue file cue)]]
                (is (matches? e actual)
                    (str "cue " i ": " (pr-str e) " but " (pr-str (select-keys actual (keys e))))))
              (doseq [[i e cue] (map vector (range) fragments cues)]
                (is (= (joined e) (dom (webvtt/hiccup cue))) (str "cue " i)))
              (doseq [[i e cue] (map vector (range) text-content cues)]
                (is (= e (dk.simongray.webvtt.wpt-test/text-content (webvtt/hiccup cue)))
                    (str "cue " i)))))))))

(deftest cue-text-parsing-tests
  (doseq [file  ["entities" "tags" "text" "timestamps" "tree-building"]
          :let  [s (test-data/text (str cue-text-parsing "dat/" file ".dat"))]
          :when s
          {:keys [input expected]} (dat-cases s)]
    (testing (str file ": " (pr-str input))
      (let [cue (first (:cues (webvtt/parse (str "WEBVTT\n\n00:00.000 --> 00:01.000\n" input))))]
        (is (= (merged expected) (dom-tree (webvtt/hiccup cue))))))))
