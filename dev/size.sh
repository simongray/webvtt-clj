#!/bin/sh
# Build the browser bundle of each set of functions that a page may use,
# with advanced compilation, and print its size in bytes, raw and gzipped.
# The base is ClojureScript alone, with str, and data is the base with the
# maps, vectors, keywords and regular expressions that an app has anyway.
# With a name as the argument, e.g. all, it prints what each namespace adds
# to that bundle instead.
set -eu
cd "$(dirname "$0")/.."

w=dk.simongray.webvtt

exports() {
  case $1 in
    base)  echo "{}" ;;
    data)  echo "{m cljs.core/hash-map v cljs.core/vector k cljs.core/keyword r cljs.core/re-find}" ;;
    parse) echo "{parse $w/parse}" ;;
    text)  echo "{parse $w/parse hiccup $w/hiccup text $w/text runs $w/runs}" ;;
    write) echo "{emit $w/emit cue_text $w/cue-text}" ;;
    check) echo "{errors $w/conformance-errors}" ;;
    all)   echo "{parse $w/parse hiccup $w/hiccup text $w/text runs $w/runs emit $w/emit cue_text $w/cue-text errors $w/conformance-errors}" ;;
  esac
}

build() {
  clojure -M:cljs release size --config-merge \
    "{:output-dir \"target/size/$1\" :modules {:main {:exports $(exports "$1")}} $2}"
}

if [ $# -gt 0 ]; then
  build "$1" ":build-hooks [(shadow.cljs.build-report/hook {:print-table true :output-to \"target/size/$1.html\"})]" 2>&1 | grep '^|'
else
  printf '%-6s %8s %8s\n' build raw gzipped
  for name in base data parse text write check all; do
    build "$name" "" > /dev/null 2>&1
    f="target/size/$name/main.js"
    printf '%-6s %8d %8d\n' "$name" "$(wc -c < "$f")" "$(gzip -9 -c "$f" | wc -c)"
  done
fi
