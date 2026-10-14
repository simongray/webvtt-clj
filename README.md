webvtt-clj
==========

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/webvtt-clj.svg)](https://clojars.org/dk.simongray/webvtt-clj)

This library implements [WebVTT](https://www.w3.org/TR/webvtt1/), the
format of captions, subtitles and chapters on the web, in Clojure and
ClojureScript. It parses a file the way a browser parses a `<track>`, and
writes one back.

- **The browser's parser.** It follows the parsing algorithm of the spec
  step by step and passes the WebVTT tests of
  [web-platform-tests](https://github.com/web-platform-tests/wpt/tree/master/webvtt),
  so a file reads as it does in a browser.
- **Cue text as Hiccup.** Voices, classes, ruby and inline timestamps come
  out as a tree, e.g. for the speakers and the word timing of a
  transcript.
- **Files that read back.** What the library writes, it reads back as the
  same data.
- **Conformance errors as data.** A file can parse and still not conform.
  You can ask why, with the line and column of each error.
- **The same results everywhere.** One codebase runs on the JVM, in Node
  and in browsers, none of which can parse WebVTT with a function call.

> This library was developed with assistance from frontier LLMs.

Getting started
---------------

The library needs Clojure 1.11+ and Java 11+. Add the latest release from
[Clojars](https://clojars.org/dk.simongray/webvtt-clj) to the `:deps` of
your `deps.edn`:

```clojure
dk.simongray/webvtt-clj {:mvn/version "0.1.0"}
```

For changes that aren't released yet, use the SHA of the latest commit on
`master` instead. Its one dependency is
[langtag-clj](https://github.com/simongray/langtag-clj), which checks the
language tags of `<lang>` spans.

Parsing files
-------------

Parse a file with `webvtt/parse`:

```clojure
(require '[dk.simongray.webvtt :as webvtt])

(webvtt/parse "WEBVTT\n\n00:01.000 --> 00:04.000 align:start\n<v Bob>Hi there")
;; => {:cues [{:start-time 1.0 :end-time 4.0 :align :start
;;             :text "<v Bob>Hi there"}]}
```

A file is a map of its `:header`, `:regions`, `:stylesheets` and `:cues`,
with only the parts it has. A cue only has the settings whose values
aren't the defaults (see `webvtt/cue-defaults`), and its times are in
seconds. For text that isn't WebVTT, `webvtt/parse` returns nil. Like a
browser, it leaves out what it can't read, e.g. a cue whose timings are
malformed.

The header is the text from WEBVTT to the first blank line, which is
where an HLS segment has its X-TIMESTAMP-MAP. Style sheets are kept as
text, and comments are dropped.

Cue text
--------

The text of a cue stays as it's written, since a metadata track can hold
anything in it (e.g. JSON). To read the text of a caption or a subtitle as
a tree of Hiccup, pass the cue or its text to `webvtt/hiccup`:

```clojure
(webvtt/hiccup "<v.loud Bob>Hi <00:00:02.000><i>there</i>")
;; => [[:v {:voice "Bob" :class ["loud"]}
;;      "Hi " [:timestamp {:time 2.0}] [:i {} "there"]]]
```

The elements are named after the tags of cue text: `:c`, `:i`, `:b`, `:u`,
`:ruby`, `:rt`, `:v` and `:lang`. An inline timestamp is an empty
`:timestamp` element, whose time is when the text after it is spoken.

For the plain text, use `webvtt/text`, which leaves out ruby text as the
spec does for the titles of chapters. For the timing of each word,
`webvtt/runs` splits the text at its inline timestamps:

```clojure
(webvtt/runs {:start-time 1.0 :end-time 3.0 :text "Never <00:00:01.500>drink"})
;; => [{:start-time 1.0 :text "Never "} {:start-time 1.5 :text "drink"}]
```

Writing files
-------------

Write a file map as text with `webvtt/emit`, and a tree as cue text with
`webvtt/cue-text`, which escapes what needs it:

```clojure
(webvtt/emit {:cues [{:start-time 1 :end-time 4
                      :text (webvtt/cue-text [[:v {:voice "Bob"} "Fish & chips"]])}]})
;; => "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\n<v Bob>Fish &amp; chips</v>\n"
```

Both throw an ex-info for data that wouldn't read back the same, e.g. a
blank line in the text of a cue, which would end the cue there. For a
timestamp alone, use `webvtt/format-timestamp` and
`webvtt/parse-timestamp`.

Conformance
-----------

To check a file that someone else wrote, ask for its conformance errors:

```clojure
(webvtt/conformance-errors "WEBVTT\n\n00:00:02.000 --> 00:00:01.000 align:left align:start\nHi")
;; => [{:type :end-not-after-start :line 3 :column 1}
;;     {:type :duplicate-setting :line 3 :column 42}]
```

The spec names no errors, so each type is named after the rule it breaks,
and `webvtt/error-types` says which. The rules for the text of cues depend
on the kind of track, as HTML's track element has the kinds:

- `:subtitles` (the default), `:captions` and `:descriptions` check cue
  text
- `:chapters` checks the titles of chapters, and that chapters nest
- `:metadata` lets the text be anything

`webvtt/conforming?` takes the same kinds.

The library leaves out the rendering of cues and the CSS of style sheets,
which are a browser's job, and the VTTCue and VTTRegion interfaces, whose
place the maps take.

Principles
----------

- **Standards first.** The code follows the WebVTT spec of 20 May 2026 and
  cites its sections.
- **Plain data.** Files, cues and regions are maps, and cue text is
  Hiccup, so the functions of Clojure work on all of them.
- **One codebase.** The library is written in `.cljc`, and needs no
  platform code to behave the same everywhere.

Development
-----------

```bash
dev/fetch.sh                                          # once, for the test data and the character references
clojure -X:test                                       # the tests on the JVM
npm install                                           # once, for the Node tests
clojure -M:cljs compile test                          # the tests in Node
clojure -X:tables                                     # the table of character references in resources
clojure -M:bench -m dk.simongray.webvtt.bench         # benchmarks on the JVM
clojure -M:cljs release bench && node target/bench.js # benchmarks in Node
dev/size.sh                                           # the size of each browser bundle
```

The size script builds a browser bundle for each set of functions that a
page may use, e.g. only `parse`, and prints its size. Give it the name of
one, e.g. `dev/size.sh text`, to see what each namespace adds to it.

The fetch script downloads the WebVTT tests of web-platform-tests and the
track element tests of HTML into `dev-resources/` (which Git ignores), at
the commit that the library follows. Their expectations are JavaScript, so
`test/wpt` has them translated into EDN.

The public functions are all in the root namespace. The internal ones are
under `impl`:

- `impl.parser`, the parsing of a file and its blocks
- `impl.settings`, timestamps, and the settings of cues and regions
- `impl.cue-text`, the tokenizer and the tree of cue text
- `impl.entities`, the character references of HTML
- `impl.writer`, which writes files and cue text
- `impl.conformance`, the rules of the syntax that the parser forgives
- `impl.chars`, the characters that the algorithms look for

License
-------

The webvtt-clj project is licensed under the [MIT licence](LICENSE). The
table of character references in `resources/` is made from WHATWG's
[entities.json](https://html.spec.whatwg.org/entities.json), under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The
expectations in `test/wpt` are translated from web-platform-tests, under
its [3-Clause BSD License](test/wpt/LICENSE.md).
