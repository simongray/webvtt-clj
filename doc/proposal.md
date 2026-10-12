# A proposal for webvtt-clj

The library would implement [WebVTT](https://w3c.github.io/webvtt/) in
Clojure and ClojureScript, as `dk.simongray/webvtt-clj` on Clojars with
the root namespace `dk.simongray.webvtt`. It would parse a file as a
browser parses a `<track>` and write one back. Cue text would parse into
Hiccup with its voices and inline timestamps. The errors of a file that
doesn't conform would come back as data.

The first user would be podcast-clj, since WebVTT is the transcript
format that the podcast namespace recommends and Apple Podcasts ingests.
The IIIF captions of glossematics.dk and the captions of the blog's audio
and video posts would follow.

The coordinates are free on Clojars, where a search for "webvtt" or
"vtt" finds nothing. Its subtitle libraries read only SRT (the newest is
a snapshot from 2020).

None of this exists yet, so the examples show the proposed API and
haven't been run.


## The spec

Since 20 May 2026 the [published spec](https://www.w3.org/TR/webvtt1/)
has been a Candidate Recommendation Draft of the Timed Text Working
Group, not the Candidate Recommendation of 2019. It's built from the same
commit as the editor's draft and keeps the section numbers of 2019. The
group now publishes every merged change as a new draft, and its charter
sets no date for a Recommendation.

Only one normative change since 2019 touches files: a cue setting now
needs its colon and a value
([#542](https://github.com/w3c/webvtt/pull/542)). That changes what's
valid but not what's parsed. The library would follow the draft of 20 May
2026 and cite its sections beside each rule, as whatwg-url does.

One open pull request matters.
[#548](https://github.com/w3c/webvtt/pull/548) would let the lines after
`WEBVTT` hold metadata (e.g. a language and a kind) for the parser to
return. It has changes requested, so until it lands the header stays
text in the data.


## Scope

The library covers:

- the parsing of section 6: files (6.1), region settings (6.2), cue
  timings and settings (6.3), cue text (6.4) and chapter titles (6.6)
- the syntax of section 4, for writing files and for checking them,
  with the file types of section 4.6 (captions and subtitles, chapters,
  metadata)
- the data model of section 3, as maps

It leaves out:

- **Rendering and the CSS extensions** (sections 7 and 8). People use the
  library to parse and write files. Browsers and IIIF viewers render
  them.
- **The CSS of STYLE blocks.** It stays text, since checking it would take
  a CSS parser.
- **The DOM construction rules** (section 6.5), except in the test
  harness. Five of the tree's elements are HTML already. A function that
  turns the other three into spans can come when a page needs one.
- **The VTTCue and VTTRegion interfaces** (section 9). Maps take their
  place, and `emit` refuses what their setters refuse (e.g. a position
  over 100%), since such a value wouldn't read back.
- **Bytes.** WebVTT is always UTF-8 and wary-fetch already decodes
  bodies, so `parse` takes a string. It skips a leading BOM.
- **Incremental parsing** of a file that's still arriving. HLS fetches
  whole segments.
- **X-TIMESTAMP-MAP**, which belongs to HLS (see
  [HLS subtitles](#hls-subtitles)).
- **Valid language tags in `<lang>` spans.** That check needs BCP 47
  (entry #11 of the ranking). Until then the check only asks for a tag
  that isn't empty.


## The platform

No platform can parse a WebVTT string with a function call. VTTCue has a
constructor but no parse method. Its `getCueAsHTML` is the only way to
get at the tree of a cue's text. A page can still parse a file
through a detached `<video>` with a `<track>` of a `blob:` URL in hidden
mode (which works in Chromium), but:

- the cues arrive later, with the load event
- a bad file gives a bare error event, and nothing says what was skipped
- the STYLE blocks and the header aren't exposed at all
- Chromium has no VTTRegion and no line or position alignment, so the
  result differs between engines
- Web Workers have no media elements or VTTCue

Node and the JDK have no WebVTT parser either. So whatwg-url's rule of
using the platform where every platform agrees doesn't apply here. The
library is one implementation in cljc, which is what makes its results
the same on every platform.


## The data model

A file parses into a map:

```clojure
(require '[dk.simongray.webvtt :as webvtt])

(webvtt/parse (str "WEBVTT\n\n"
                   "REGION\nid:left width:40%\n\n"
                   "intro\n"
                   "00:00:01.000 --> 00:00:04.000 region:left align:start\n"
                   "<v.loud Bob>Hi <00:00:02.000><i>there</i>"))
;; => {:regions [{:id "left" :width 40}]
;;     :cues    [{:id         "intro"
;;                :start-time 1.0
;;                :end-time   4.0
;;                :region     "left"
;;                :align      :start
;;                :text       "<v.loud Bob>Hi <00:00:02.000><i>there</i>"}]}
```

- **A map has only the keys whose values aren't the spec's defaults.**
  The defaults are in `cue-defaults` and `region-defaults`, so a cue
  without settings stays small. This is how whatwg-url leaves out the
  parts that a URL lacks.
- **Times are seconds**, as in VTTCue, the media element and podcast-clj.
  Writing rounds them to whole milliseconds, the precision of a WebVTT
  timestamp.
- **Settings take the names of VTTCue's attributes** in kebab case, with
  keywords for the enumerations, e.g. :vertical :rl and :position-align
  :line-left. As in VTTCue, :line is a number of lines, or a percentage
  when :snap-to-lines? is false. Other percentages are numbers from 0 to
  100, and a region's anchors are pairs of them.
- **A cue's :region is the id** of one of the :regions.
- **The text of each STYLE block** is in :stylesheets.
- **The header is in :header as written**, from `WEBVTT` to the first
  blank line, when it's more than `WEBVTT`. That's where an HLS segment
  has its X-TIMESTAMP-MAP.
- **The map has no comments**, since the spec's parser drops NOTE blocks.

The text of a cue stays a string, since its meaning depends on the kind
of track. A metadata cue holds any text (often JSON), a chapter cue holds
a title, and only captions and subtitles have markup. Parse it with
`hiccup`, by section 6.4:

```clojure
(webvtt/hiccup "<v.loud Bob>Hi <00:00:02.000><i>there</i>")
;; => [[:v {:voice "Bob" :class ["loud"]}
;;      "Hi " [:timestamp {:time 2.0}] [:i {} "there"]]]
```

- **The elements are named after the tags of cue text**: :c, :i, :b, :u,
  :ruby, :rt, :v and :lang. So `cue-text` writes the tree back as the
  same tags. Five of them (i, b, u, ruby and rt) are HTML elements
  already.
- **Every element has an attribute map**, as in the Hiccup of html-pieces
  and qname-hiccup. It holds :class as a vector (which Hiccup renderers
  accept), :voice on a :v and :lang on a :lang.
- **The tree leaves out the applicable language** that the spec gives
  every node, since it's the :lang of the nearest :lang above it (or
  else the track's).
- **An inline timestamp is an empty :timestamp element**, whose :time is
  when the text after it is spoken. The DOM makes it a processing
  instruction, which Hiccup has no form for.
- **The tree is a vector of nodes**, with adjacent text merged into one
  string.


## The API

The root namespace holds the whole API. These functions parse:

- `parse`, the map of a file, or nil when the text isn't WebVTT (6.1)
- `hiccup`, the node tree of a cue's text (6.4)
- `text`, the plain text of a cue by the rules for extracting a chapter
  title (6.6), which leave out ruby text
- `runs`, the text of a cue split at its inline timestamps, as maps of
  :start-time and :text
- `parse-timestamp`, the seconds of a WebVTT timestamp, or nil (6.3)

These write:

- `emit`, the text of a file map
- `cue-text`, the cue text of a node tree, with &, < and > written as
  character references
- `format-timestamp`, seconds as a WebVTT timestamp

These check:

- `conformance-errors`, the errors of a file for a kind of track
- `conforming?`, whether a file has none

Two maps, `cue-defaults` and `region-defaults`, hold the defaults of
section 3. The functions that read cue text take a cue or its text, as
whatwg-url's functions take a URL map or its text. Only `runs` needs a
cue, since its first run starts at the cue's start time.

The names follow your other libraries: `parse` and `emit` as in
qname-hiccup (and the `emit-vtt` that this replaces), and `hiccup` and
`text` as in html-pieces. The conformance functions use the spec's
words, as whatwg-url's `validation-errors` and `valid?` use the URL
Standard's.

The internal namespaces sit under `impl`, with `^:no-doc`:

- `impl.parser`, the file parsing and the collecting of blocks (6.1)
- `impl.settings`, region settings, cue settings and timestamps (6.2,
  6.3)
- `impl.cue-text`, the tokenizer, the tree and chapter titles (6.4, 6.6)
- `impl.entities`, the named character references of HTML
- `impl.writer`, `emit` and `cue-text`
- `impl.conformance`, the rules of section 4 that the parser doesn't
  enforce

Each state of the tokenizer and each step of the file parsing is a named
function. Each reads a run of text up to its next delimiter rather than
a code point at a time, as html-pieces' tokenizer does. Benchmarks with
criterium and clj-async-profiler, as in whatwg-url, would time the
largest transcript of podcast-clj's corpus and a word-level file with a
timestamp per word. An optimization stays where a benchmark shows that
it matters.

**The library carries the whole table of HTML's named character
references.** The parser decodes references in cue text by HTML's rules.
The table in html-pieces keeps only the names of HTML 4.01 and the legacy
ones, which would fail two of WPT's cue text cases
(`&ClockwiseContourIntegral;` and `&nsubE;`). A dev alias would make the
full table from WHATWG's entities.json into `resources/`, as whatwg-url
makes its Unicode tables, so the library needs nothing but Clojure. HTML
says the list will never change, so the table needs no upkeep.


## Errors and conformance

Like a browser, `parse` never throws on text and reports nothing.
Whatever the parser skips is missing from the map, e.g. a setting it
doesn't know or a tag it ignores.

To check a file, `conformance-errors` runs the same parser and has it
report every forgiving branch it takes, the way whatwg-url's parser
collects validation errors only when asked. It then checks the rules of
section 4 that the parser doesn't enforce, e.g.:

- an end time after the start time, and start times in order
- unique ids of cues and regions, and no `-->` in ids, comments or style
  blocks
- each setting at most once, with a valid value
- region and style blocks before the first cue
- end tags in cue text, which only a lone v or the last rt may leave out
- inline timestamps in order and within their cue

Since one parser does both, the check can't disagree with the parse.
Errors are maps of a :type, a :line and a :column, in the order of the
file:

```clojure
(webvtt/conformance-errors
 "WEBVTT\n\n00:00:02.000 --> 00:00:01.000 align:left align:start\nHi")
;; => [{:type :end-not-after-start :line 3 :column 18}
;;     {:type :duplicate-setting :line 3 :column 42}]
```

The spec names no errors, unlike the URL Standard. So each type takes
its name from the rule it breaks. The docstring that lists the types
cites their sections. Unlike whatwg-url's bare keywords, the errors
carry positions, since a transcript can have a thousand cues. They have
no messages, as podcast-clj's diagnostics have their own.

An optional kind of track, one of the kinds of HTML's `<track>`, picks
the rules for the text of cues:

- :subtitles (the default), :captions and :descriptions check cue text
  (4.2.2)
- :chapters checks chapter titles (4.2.3) and that chapters nest
  (4.5.1)
- :metadata checks only that the text of a cue has no `-->` and no blank
  line (4.2.1)

The library reads the nesting rule as "no two chapters partly overlap",
since the literal wording would forbid the side-by-side chapters of the
spec's own example.

Writing throws an ex-info for a map that wouldn't read back the same,
e.g. one with a blank line in a :text or `-->` in an :id. It still writes
a file that's merely non-conforming (e.g. with cues out of order), which
`conformance-errors` can catch.


## Tests

The tests would pin web-platform-tests at
[38d608e](https://github.com/web-platform-tests/wpt/tree/38d608e1005d6d7f0fecefb3b7b14109ab3c3b1d/webvtt/parsing)
of 12 October 2026, though `webvtt/parsing` hasn't changed since 25
August. As in whatwg-url, `dev/fetch.sh` downloads the test data into
`dev-resources/` (which Git ignores), here with a sparse checkout of
`webvtt/parsing` and of HTML's track element tests at that commit.

- **Cue text.** The five `.dat` files of `cue-text-parsing/dat` hold 78
  cases as html5lib-style trees of the DOM that section 6.5 builds. The
  tests read the files directly. The harness prints the library's tree
  in their notation: a c or a v as a span with a class or a title, a lang
  as a span with a lang, and a timestamp as `<?timestamp …>`. It merges
  the adjacent text that the expected trees keep apart.
- **File parsing.** The 38 `.test` files of `file-parsing/support` state
  their expectations as JavaScript assertions on VTTCue, some of them in
  loops. So the plan is to translate them once into an EDN file in
  `test/`, which names the pinned commit and carries WPT's BSD licence.
  The inputs are the generated `.vtt` files in `tests/support`, since the
  `.test` files write their bodies with Python escapes. The 11 inputs
  that `signature-invalid.html` expects to fail should give nil. Numbers
  compare as JavaScript's SameValue does, since `line:-0` must read as 0
  and Clojure's `=` can't tell 0.0 from -0.0.
- **The track element.** HTML's
  [track element tests](https://github.com/web-platform-tests/wpt/tree/master/html/semantics/embedded-content/media-elements/track/track-element)
  add cases for recovering from bad cues, settings, spans and entities.
  Most of them use structured helpers close to JSON (`assert_cues_match`
  and `assert_cue_fragment`), which translate into EDN the same way.
- **Conformance.** No suite exists. W3C's
  [webvtt.js](https://github.com/w3c/webvtt.js) reports about 60 kinds of
  error with their line and column, but its tests check none of them. It
  also lacks REGION blocks and the colon rule. The library would have its
  own table of small files and their errors, at least one for each type,
  with webvtt.js's messages as a checklist.
- **Generative tests** with test.check, as in whatwg-url. A generated file
  map parses back to itself after `emit`, a valid one has no conformance
  errors, and no random text makes `parse` throw.
- **The gaps that WPT leaves**, which the library's own cases cover:
  - STYLE blocks (`stylesheets.test` checks nothing that a parser
    returns)
  - region widths, beyond one hand-written test
  - numeric references other than `&#32;`, e.g. `&#0;`, C1 controls and
    surrogates
  - nested `<lang>` spans and `<c>` with several classes
  - the header, writing and conformance

Invalid UTF-8 needs no test, since `parse` takes a string. CI would run
it all on the JVM (Java 11 and the current release) and in Node (with
advanced compilation too), as whatwg-url's does. It would skip the
browser, since the library calls nothing that Node lacks.


## Adopting it in podcast-clj

As wary-fetch builds its url namespace on whatwg-url, podcast-clj would
build its reading and writing of WebVTT on webvtt-clj and keep its own
transcript shape. The transcript namespace keeps:

- the transcript shape, `parse` with its sniffing, and the report
- the readers of SRT, JSON, HTML and plain text, the "Name:" prefixes and
  `carry-speakers`
- `turn-marked?`, for hosts like Transistor that write a voice span only
  where the speaker changes. That's a habit of hosts rather than WebVTT,
  so it stays.
- `emit-srt`, `emit-json`, `turns`, `text`, `at` and `matches`

It would lose:

- the timing regex of `vtt-cue`, the voice span regex and the stripping
  of tags from cue text (`timing-pattern` and `strip-tags` stay for SRT,
  sniffing and HTML)
- the filter that drops NOTE, STYLE and REGION blocks
- `vtt-text` and the string building of `emit-vtt`

Each cue that `webvtt/parse` returns becomes a segment in `parse-vtt`,
with the speaker from the first :v of `webvtt/hiccup` and the body from
`webvtt/text`. To write, `emit-vtt` builds each cue's text with
`webvtt/cue-text`, e.g. from `[[:v {:voice speaker} body]]`, and the
file with `webvtt/emit`.

Some transcripts would read differently, mostly where today's reader is
looser than the spec:

- **The parser drops the cues that the spec rejects**, as a browser does.
  In the corpus this changes two cues of an Ausha transcript whose
  milliseconds read 1000, e.g. `00:15:03.1000`. Today's reader drops one
  and ends the other at 328.1 s rather than 329.0 s. The other
  transcripts (from Buzzsprout, Megaphone, Talk Python and Transistor)
  have conforming timings and no tags but voice spans, so they should
  read as they do now. A diagnostic can count the dropped cues, from
  `conformance-errors`.
- **A file without the `WEBVTT` header doesn't parse.** Sniffing already
  reads one with SRT-like timings as SRT.
- **Every voice span counts**, not just the first, so a cue with several
  voices can become a segment for each.
- **Ruby text leaves the body.** Character references decode by HTML's
  rules, e.g. `&amp` without its semicolon.
- **Blocks follow the spec.** A cue whose id starts with NOTE, STYLE or
  REGION (e.g. "NOTES 3") is no longer dropped. A lone CR ends a line,
  and a line of spaces no longer ends a cue.

**Word timing.** For "Word timing from inline WebVTT timestamps", a
segment would get the timed runs of `webvtt/runs` (a word each, in a
word-level transcript), and `emit-vtt` would write them back as
:timestamp elements. The needs report cites section 3.3.3 for those
timestamps, which are in sections 4.2.2 and 6.4.

### HLS subtitles

X-TIMESTAMP-MAP belongs in podcast-clj, next to the playlists that
`format.hls` reads:

- **HLS defines it, not WebVTT**
  ([RFC 8216](https://www.rfc-editor.org/rfc/rfc8216#section-3.5),
  section 3.5). It isn't even conforming WebVTT, since the syntax wants a
  blank line right after the `WEBVTT` line. The draft revision of the RFC
  notes that WebVTT dropped such headers in 2016. PR #548 might bring
  them back.
- **Applying it takes the media stream.** The map ties a cue time to the
  stream's MPEG-2 timestamps, on a 90 kHz clock that wraps at 33 bits
  (about every 26.5 hours). So the offset is against the stream's first
  timestamp, as in hls.js, not against anything in the WebVTT file.
- **Joining segments takes HLS too.** Each segment holds every cue shown
  during it, so a cue that spans segments appears in each, and the join
  must keep only one.

From webvtt-clj, podcast-clj needs only the :header text and
`parse-timestamp` (for the LOCAL time of the map). Its diagnostics
shouldn't count the map as a conformance error.

### Chapters

WebVTT chapter tracks barely matter for podcasts. The `podcast:chapters`
tag links JSON chapters and never mentions WebVTT. Apple Podcasts reads
ID3 and JSON chapters. A search found no podcast app or host that uses
WebVTT's. Chapter tracks matter in video players such as video.js
and JW Player, while IIIF builds chapters from Ranges. The library
supports them anyway, since they only add the rule for chapter titles
and the check that chapters nest. The chapters namespace of podcast-clj
needn't read them.


## Later users

- **glossematics.dk.** A IIIF Canvas carries captions as supplementing
  annotations whose body is a `text/vtt` file with a language (cookbook
  recipes [0219](https://iiif.io/api/cookbook/recipe/0219-using-caption-file/)
  and [0074](https://iiif.io/api/cookbook/recipe/0074-multiple-language-captions/)).
  With webvtt-clj you write and check the files, while the IIIF library
  (entry #7 of the ranking) would build the annotations. Mirador and the
  Universal Viewer hand the file to a `<track>`, so the browser's parser
  reads it.
- **The blog.** You can write a post's captions from its transcript with
  `emit` and check them with `conformance-errors` before publishing.


## A first release

Version 0.1.0 would have the whole API above and pass the WPT and track
element tests on the JVM and in Node. Its README would follow
whatwg-url's, with the MIT licence and a credit for the table of
character references, which WHATWG publishes under CC BY 4.0. The work
would go in this order:

1. timestamps, file parsing and settings, against the file parsing tests
2. cue text and the table of references, against the cue text tests
3. `emit` and `cue-text`, with the generative tests
4. the conformance errors, with their own cases
5. podcast-clj against a local install, with its corpus as the last
   check before the release to Clojars


## Size

The source would be about 1,500 lines of cljc (half of whatwg-url), with
as much again in tests and translated expectations. That's a few days,
or 3 on the scale of podcast-clj's needs report. The ranking's estimate
of 2 to 3 didn't count the conformance checks or the translation of the
tests. Adopting it in podcast-clj, with word timing, is about a day.


## Open questions

1. **The table of character references.** The full table has 2,231
   names (11 KB gzipped). The subset of html-pieces has 365 names (1.6 KB
   gzipped) and fails two WPT cases, for names that no caption file is
   likely to use. The extra 10 KB lands in every browser bundle, where
   podcast-clj's client is the only user so far. In podcast-clj, the full
   table would mean two tables and two copies of HTML's reference
   algorithm, and `&nsubE;` would decode in a transcript but not in show
   notes. It would also go against the size trade-off you made in
   html-pieces. The choices:
   - the full table in webvtt-clj, which I'd pick, since the WPT suite
     passes in full and "parses as a browser does" needs no footnote
   - the same subset as html-pieces, with the two cases as documented
     failures
   - a dependency on html-pieces, which would have to make its
     `character-reference` public and add the MathML names, pulling its
     tokenizer, whatwg-url and data.json into webvtt-clj
   - a small library with the table and the algorithm, shared by both
2. **Seconds or milliseconds?** Seconds match VTTCue and podcast-clj.
   Whole milliseconds would match the format exactly and never round.
3. **Dropped cues in podcast-clj.** Should it only report them, or also
   repair known slips of exporters (like Ausha's 1000 milliseconds)
   before parsing?
4. **Comments.** The parser drops NOTE blocks. Should `emit` be able to
   write one, e.g. a note at the top of a file?
5. **A browser run.** Is Node enough for JavaScript, or should CI also
   run the tests in a headless browser? That would need the test data
   compiled into the build, since a browser can't read it from disk.
6. **Voices in podcast-clj.** Should a cue with several voice spans
   become several segments?
7. **The name of the writer.** The name `emit` matches qname-hiccup and
   podcast-clj, while `write` or `serialize` might read better on their
   own.
