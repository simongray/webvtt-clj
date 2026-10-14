#!/bin/sh
# Download the test data of web-platform-tests, and the named character
# references of HTML that the table in resources is made from, into
# dev-resources/. The tests are WPT's WebVTT parsing tests and HTML's track
# element tests, at the commit that the library follows.
set -eu

wpt=38d608e1005d6d7f0fecefb3b7b14109ab3c3b1d

cd "$(dirname "$0")/.."
mkdir -p dev-resources
rm -rf dev-resources/wpt

# a sparse, blobless checkout of that one commit, since WPT is large
git init -q dev-resources/wpt
git -C dev-resources/wpt remote add origin https://github.com/web-platform-tests/wpt.git
git -C dev-resources/wpt sparse-checkout set \
  webvtt/parsing html/semantics/embedded-content/media-elements/track/track-element
git -C dev-resources/wpt fetch -q --depth 1 --filter=blob:none origin "$wpt"
git -C dev-resources/wpt checkout -q FETCH_HEAD

# the HTML Standard says that this list will never change
curl -sSfL -o dev-resources/entities.json https://html.spec.whatwg.org/entities.json
