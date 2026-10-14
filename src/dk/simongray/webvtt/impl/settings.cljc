(ns ^:no-doc dk.simongray.webvtt.impl.settings
  "Timestamps, and the settings of regions and cues (WebVTT, sections 6.2
  and 6.3).

  A cue or a region is a map of only the attributes that differ from the
  defaults of the data model in section 3, which are cue-defaults and
  region-defaults. Times and percentages are doubles, and the lines of a
  region are an integer."
  (:require [clojure.string :as str]
            [dk.simongray.webvtt.impl.chars :as chars]))

;; WebVTT, sections 3.3 and 6.1, "cue creation"
(def cue-defaults
  "The attributes of a new cue, which a cue map leaves out."
  {:id             ""
   :vertical       :horizontal
   :snap-to-lines? true
   :line           :auto
   :line-align     :start
   :position       :auto
   :position-align :auto
   :size           100.0
   :align          :center
   :text           ""})

;; WebVTT, sections 3.4 and 6.1, "region creation"
(def region-defaults
  "The attributes of a new region, which a region map leaves out."
  {:id              ""
   :width           100.0
   :lines           3
   :region-anchor   [0.0 100.0]
   :viewport-anchor [0.0 100.0]
   :scroll          :none})

(defn default?
  "Whether `v` is the value that `defaults` give the attribute `k`, with
  numbers compared by value, e.g. 100 for 100.0."
  [defaults k v]
  (let [d (get defaults k)]
    (if (and (number? d) (number? v))
      (== d v)
      (= d v))))

(defn put
  "The cue or region `m` with its attribute `k` set to `v`, or left out
  when `v` is its default in `defaults`."
  [defaults m k v]
  (if (default? defaults k v)
    (dissoc m k)
    (assoc m k v)))

(def put-cue
  "Set an attribute of a cue, as put does with the defaults of a cue."
  (partial put cue-defaults))

(def put-region
  "Set an attribute of a region, as put does with the defaults of a
  region."
  (partial put region-defaults))

;; HTML, "rules for parsing floating-point number values", for text that
;; WebVTT has already checked: a sign, digits and a fraction at most. The
;; platform rounds to the nearest double as HTML does, while HTML has no
;; -0 and no infinity.
(defn float-value
  "The number of the text `s`, or nil when it's beyond what a double
  holds."
  [s]
  (let [x (parse-double s)]
    (when (and x (< (abs x) ##Inf))
      (+ x 0.0))))

(defn integer-value
  "The number of the ASCII digits `s`: a long, or a double when it's too
  large for a long to hold exactly on both platforms."
  [s]
  (let [x (parse-double s)]
    (if (< x 9007199254740992)
      (long x)
      x)))

;; WebVTT, section 6.3, "collect a WebVTT timestamp", which reads each unit
;; as a run of ASCII digits and checks how many there are, rather than by a
;; regular expression, which dev/dk/simongray/webvtt/bench.cljc showed to be
;; a third of the parse

(defn digits-value
  "The number of the ASCII digits from `i` to `j` in the text `s`, added up
  as doubles, or parsed when there are too many for that to be exact."
  ^double [s ^long i ^long j]
  (if (< 15 (- j i))
    (parse-double (subs s i j))
    (loop [k i acc 0.0]
      (if (< k j)
        (recur (inc k) (+ (* 10.0 acc) (- (chars/code-at s k) 48)))
        acc))))

(defn after-colon
  "The index after the two ASCII digits that follow a colon at `i` in the
  text `s`, or nil when there's no colon there or the digits aren't two."
  [s ^long i]
  (when (= \: (chars/char-at s i))
    (let [j (chars/digits-end s (inc i))]
      (when (= 2 (- j (inc i)))
        j))))

(defn unit-ends
  "Where each unit of the WebVTT timestamp at `i` in the text `s` ends, or
  nil when the units aren't as section 6.3 collects them. The units are:

  - the first digits
  - the two digits after a colon
  - the two digits after another colon, or nil when there's no third unit
  - the three digits after a dot"
  [s ^long i]
  (let [a (chars/digits-end s i)]
    (when (< i a)
      (let [hours? (or (not= 2 (- a i)) (< 59 (digits-value s i a)))]
        (when-let [b (after-colon s a)]
          (let [c   (when (or hours? (= \: (chars/char-at s b)))
                      (after-colon s b))
                dot (or c b)
                d   (chars/digits-end s (inc dot))]
            (when (and (or c (not hours?))
                       (= \. (chars/char-at s dot))
                       (= 3 (- d (inc dot))))
              [a b c d])))))))

(defn seconds
  "The seconds of the timestamp of `h` hours, `m` minutes, `s` seconds and
  `ms` milliseconds, added up in the order of section 6.3, so that the
  double is the one that a browser gets."
  ^double [^double h ^double m ^double s ^double ms]
  #_{:clj-kondo/ignore [:redundant-nested-call]}
  (+ (+ (+ (* (* h 60) 60) (* m 60)) s) (/ ms 1000.0)))

(defn timestamp
  "The seconds of the WebVTT timestamp at `i` in the text `s`, and the
  index just past it, as a pair, or nil when there's none (section 6.3)."
  [s ^long i]
  (when-let [[a b c d] (unit-ends s i)]
    (let [[h m sec] (if c
                      [(digits-value s i a) (digits-value s (inc a) b) (digits-value s (inc b) c)]
                      [0.0 (digits-value s i a) (digits-value s (inc a) b)])
          dot       (or c b)]
      (when (and (<= m 59) (<= sec 59))
        [(seconds h m sec (digits-value s (inc dot) d)) d]))))

;; WebVTT, section 4.1, a WebVTT percentage, and section 6.2, "parse a
;; percentage string"
(defn percentage
  "The number of the percentage `s`, e.g. 40.0 for 40%, or nil when it
  isn't one from 0% to 100%."
  [s]
  (when (re-matches #"[0-9]+(?:\.[0-9]+)?%" s)
    (when-let [x (float-value (subs s 0 (dec (count s))))]
      (when (<= x 100)
        x))))

(defn setting
  "The name and value of the setting `token`, as a pair, or nil when it
  has no colon, or its first colon starts or ends it (sections 6.2 and
  6.3)."
  [token]
  (when-let [i (str/index-of token ":")]
    (when (< 0 i (dec (count token)))
      [(subs token 0 i) (subs token (inc i))])))

(defn settings
  "The settings of the text `s`, as pairs of a name and a value."
  [s]
  (keep setting (chars/words s)))

(defn split-at-comma
  "The text `s` before and after its first comma, as a pair, with nil
  after when it has none."
  [s]
  (if-let [i (str/index-of s ",")]
    [(subs s 0 i) (subs s (inc i))]
    [s nil]))

(defn anchor
  "The point of the anchor `s`, e.g. [0.0 100.0] for 0%,100%, or nil when
  it isn't two percentages separated by a comma."
  [s]
  (let [[x y] (split-at-comma s)
        x     (percentage x)
        y     (some-> y percentage)]
    (when (and x y)
      [x y])))

;; WebVTT, section 6.2, "collect WebVTT region settings"
(defn region-setting
  "The `region` with its setting named `k` set to `v`, or as it is when `v`
  isn't a valid value for that setting."
  [region [k v]]
  (case k
    "id"             (put-region region :id v)
    "width"          (if-let [x (percentage v)] (put-region region :width x) region)
    "lines"          (if (re-matches #"[0-9]+" v)
                       (put-region region :lines (integer-value v))
                       region)
    "regionanchor"   (if-let [xy (anchor v)] (put-region region :region-anchor xy) region)
    "viewportanchor" (if-let [xy (anchor v)] (put-region region :viewport-anchor xy) region)
    "scroll"         (if (= "up" v) (put-region region :scroll :up) region)
    region))

(defn region-settings
  "The region that the settings in the text `s` of a REGION block
  describe."
  [s]
  (reduce region-setting {} (settings s)))

;; WebVTT, section 6.3, "parse the WebVTT cue settings": a cue drops out of
;; its region when it's vertical, has a line or isn't of full size
(defn vertical-setting
  "The `cue` with the writing direction `v`, and out of its region when
  it's vertical."
  [cue v]
  (let [cue (case v
              "rl" (assoc cue :vertical :rl)
              "lr" (assoc cue :vertical :lr)
              cue)]
    (cond-> cue (:vertical cue) (dissoc :region))))

(defn line-number
  "The number of the line position `s` when it's a line number rather than
  a percentage, i.e. digits with an optional sign and fraction, or nil."
  [s]
  (when (re-matches #"-?[0-9]+(?:\.[0-9]+)?" s)
    (float-value s)))

(defn line-setting
  "The `cue` with the line and line alignment of the setting value `v`, or
  as it is when `v` isn't one."
  [cue v]
  (let [[linepos linealign] (split-at-comma v)
        percent?            (str/ends-with? linepos "%")
        number              (when (re-find #"[0-9]" linepos)
                              (if percent? (percentage linepos) (line-number linepos)))
        align               (case linealign
                              nil      ::unchanged
                              "start"  :start
                              "center" :center
                              "end"    :end
                              ::invalid)]
    (if (or (nil? number) (= ::invalid align))
      cue
      (-> (if (= ::unchanged align) cue (put-cue cue :line-align align))
          (assoc :line number)
          (put-cue :snap-to-lines? (not percent?))
          (dissoc :region)))))

(defn position-setting
  "The `cue` with the position and position alignment of the setting
  value `v`, or as it is when `v` isn't one."
  [cue v]
  (let [[colpos colalign] (split-at-comma v)
        number            (percentage colpos)
        align             (case colalign
                            nil          ::unchanged
                            "line-left"  :line-left
                            "center"     :center
                            "line-right" :line-right
                            ::invalid)]
    (if (or (nil? number) (= ::invalid align))
      cue
      (-> (if (= ::unchanged align) cue (put-cue cue :position-align align))
          (assoc :position number)))))

(defn size-setting
  "The `cue` with the size of the setting value `v`, and out of its region
  unless it's 100%, or as it is when `v` isn't a size."
  [cue v]
  (if-let [x (percentage v)]
    (cond-> (put-cue cue :size x)
      (not= 100.0 x) (dissoc :region))
    cue))

(defn align-setting
  "The `cue` with the text alignment of the setting value `v`, or as it is
  when `v` isn't one."
  [cue v]
  (case v
    ("start" "center" "end" "left" "right") (put-cue cue :align (keyword v))
    cue))

(defn cue-setting
  "The `cue` with its setting named `k` set to `v`, where the `regions` are
  those that a region setting can name."
  [regions cue [k v]]
  (case k
    "region"   (if (some #(= v (:id %)) regions)
                 (assoc cue :region v)
                 (dissoc cue :region))
    "vertical" (vertical-setting cue v)
    "line"     (line-setting cue v)
    "position" (position-setting cue v)
    "size"     (size-setting cue v)
    "align"    (align-setting cue v)
    cue))

;; WebVTT, section 6.3, "collect WebVTT cue timings and settings"
(defn arrow-end
  "The index just past the arrow at `i` in the text `s`, after any ASCII
  whitespace, or nil when there's no arrow there."
  [s i]
  (let [i (chars/skip-whitespace s i)]
    (when (str/starts-with? (subs s i) "-->")
      (+ i 3))))

(defn timings-and-settings
  "The cue that the timings and settings of the `line` describe, or nil
  when its timings don't parse. The `regions` are those that a region
  setting can name."
  [line regions]
  (when-let [[start i] (timestamp line (chars/skip-whitespace line 0))]
    (when-let [i (arrow-end line i)]
      (when-let [[end i] (timestamp line (chars/skip-whitespace line i))]
        (reduce (partial cue-setting regions)
                {:start-time start :end-time end}
                (settings (subs line i)))))))
