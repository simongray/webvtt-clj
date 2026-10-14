(ns dk.simongray.webvtt.bench
  "Benchmarks of a caption file and a word-level transcript, on the JVM by
  criterium and in Node by the clock, and flame graphs of the JVM by
  clj-async-profiler.

      clojure -M:bench -m dk.simongray.webvtt.bench
      clojure -M:cljs release bench && node target/bench.js

  In a REPL with the :bench alias, (run) gives the mean of each case in
  microseconds, and (profile :parse-words 10) the file of a flame graph."
  (:require #?@(:clj [[clj-async-profiler.core :as prof]
                      [criterium.core :as criterium]])
            [dk.simongray.webvtt :as webvtt]))

(defn captions
  "A caption file of `n` cues of two lines, each with a speaker's voice."
  [n]
  (str "WEBVTT\n\n"
       (apply str (for [i (range n)]
                    (str i "\n"
                         (webvtt/format-timestamp (* i 2.5)) " --> "
                         (webvtt/format-timestamp (+ 2.4 (* i 2.5))) " align:start\n"
                         "<v Speaker " (mod i 3) ">So this is the line that a speaker says,\n"
                         "and this is the rest of it &amp; more.\n\n")))))

(defn words
  "A word-level transcript of `n` cues of six words, with a timestamp
  before each word."
  [n]
  (str "WEBVTT\n\n"
       (apply str (for [i (range n)]
                    (str (webvtt/format-timestamp (* i 2.0)) " --> "
                         (webvtt/format-timestamp (+ 1.9 (* i 2.0))) "\n"
                         "<v Speaker " (mod i 3) ">"
                         (apply str (for [k (range 6)]
                                      (str "<" (webvtt/format-timestamp (+ 0.001 (* i 2.0) (* k 0.3))) ">word ")))
                         "\n\n")))))

(def cases
  "Each case by name, as a function to time."
  (let [captions (captions 3000)
        words    (words 3000)
        file     (webvtt/parse captions)
        cue      (first (:cues (webvtt/parse words)))]
    {:parse-captions #(webvtt/parse captions)
     :parse-words    #(webvtt/parse words)
     :hiccup         #(webvtt/hiccup cue)
     :runs           #(webvtt/runs cue)
     :emit           #(webvtt/emit file)
     :conformance    #(webvtt/conformance-errors captions)}))

#?(:cljs
   (defn- mean
     "The mean time of a call of `f` in seconds, over a second of calls
     after a second of warming up."
     [f]
     (let [now   #(js/performance.now)
           calls (fn [ms]
                   (let [end (+ (now) ms)]
                     (loop [n 0]
                       (if (< (now) end)
                         (do (f) (recur (inc n)))
                         n))))]
       (calls 1000)
       (let [start (now)
             n     (calls 1000)]
         (/ (- (now) start) n 1000)))))

(defn run
  "The mean time of each case, or of those named `ks`, in microseconds."
  ([]
   (run (keys cases)))
  ([ks]
   (into (sorted-map)
         (for [k    ks
               :let [mean #?(:clj  (first (:mean (criterium/quick-benchmark* (cases k) {})))
                             :cljs (mean (cases k)))]]
           [k (/ (Math/round (* mean 1e9)) 1000.0)]))))

#?(:clj
   (defn profile
     "The file of a flame graph of the case `k`, run for `seconds` by
     clj-async-profiler."
     [k seconds]
     (let [f   (cases k)
           end (+ (System/currentTimeMillis) (* 1000 seconds))]
       (prof/profile (while (< (System/currentTimeMillis) end) (f))))))

(defn -main
  "Print the mean time of each case."
  [& _]
  (doseq [[k us] (run)]
    (println (str (name k) (apply str (repeat (- 16 (count (name k))) " ")) us " µs"))))
