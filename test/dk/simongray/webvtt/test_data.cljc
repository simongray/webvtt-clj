(ns dk.simongray.webvtt.test-data
  "The files of the tests, read the same way on the JVM and in Node: the
  test data that dev/fetch.sh puts in dev-resources, and the expectations
  in test. A file is nil when it isn't there, and the tests of its data
  then check nothing."
  (:require #?(:cljs ["fs" :as fs])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:clj [clojure.java.io :as io])))

(defn text
  "The text of the file at `path`, relative to the root of the project, in
  UTF-8 and with a BOM kept, or nil."
  [path]
  #?(:clj  (let [f (io/file path)]
             (when (.exists f) (slurp f :encoding "UTF-8")))
     :cljs (when (fs/existsSync path)
             (fs/readFileSync path "utf8"))))

(defn edn
  "The data of the EDN file at `path`, relative to the root of the
  project, or nil."
  [path]
  (some-> (text path) edn/read-string))

(def wpt
  "Where dev/fetch.sh checks out web-platform-tests."
  "dev-resources/wpt/")
