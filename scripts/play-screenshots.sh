#!/usr/bin/env bash
# Turn raw device captures into Play-listing screenshots.
#
# Phone captures are ~1220x2436 (1:1.997). That clears Play's hard "no longer
# than 2:1" bar by 0.16%, but it is not 9:16, so it does not qualify for the
# large-format featured sections — those want four or more shots at exactly
# 9:16, 1080x1920 minimum.
#
# So we pad rather than scale-to-fill: cropping to 9:16 would cut 236px of UI,
# and since every nfcGuard screen is pure black the padding is invisible.
#
# Text goes through Pango, not -annotate, for two reasons: Adwaita Sans is a
# variable font, so `-font <file>` loads its Regular instance no matter what
# weight you ask for, and Pango does real letter spacing. The app itself uses
# the system grotesque at FontWeight.Black with letterSpacing 1-2.sp, so the
# caption should be a Black-weight grotesque too — matching the app matters
# more than matching the website, since caption and screenshot share a frame.
#
# Output is 1080x1920 PNG24 with no alpha — Play rejects alpha channels.
#
# Usage: bash scripts/play-screenshots.sh <out-dir> <capture> ["HEADLINE"] ["subline"]
#
# Omit both caption arguments to emit an uncaptioned 9:16 shot — the form
# fastlane/F-Droid wants, since it draws its own frame and a band wastes space.
set -euo pipefail

W=1080; H=1920; BAND=420
FAMILY=${NFCGUARD_FONT:-Adwaita Sans}

out_dir=${1:?usage: play-screenshots.sh <out-dir> <capture> [headline] [subline]}
src=${2:?missing capture}
headline=${3:-}
subline=${4:-}

command -v magick >/dev/null || { echo "need ImageMagick 7 (magick)" >&2; exit 1; }
magick -list format | grep -q PANGO || { echo "ImageMagick has no Pango delegate" >&2; exit 1; }
[[ -f "$src" ]] || { echo "no such capture: $src" >&2; exit 1; }

mkdir -p "$out_dir"
dest="$out_dir/$(basename "${src%.*}").png"

# Pango parses its input as markup, so text is data that must be escaped.
esc() { printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }

# letter_spacing is in Pango units: 1024 = 1pt.
#
# The markup goes via `pango:@file`, never inline: passing it as `pango:<span
# ...>` makes ImageMagick mangle escaped entities before Pango ever sees them,
# so a caption containing "&" dies with "Entity did not end with a semicolon"
# even though it was escaped correctly.
text_layer() { # weight size spacing colour text -> path
  local png markup
  png=$(mktemp --suffix=.png); markup=$(mktemp --suffix=.xml)
  printf '<span font="%s" letter_spacing="%s" foreground="%s">%s</span>' \
    "$(printf '%s' "$FAMILY ${1:+$1 }$2" | sed 's/  */ /g')" "$3" "$4" "$(esc "$5")" > "$markup"
  magick -background none -define pango:align=center "pango:@$markup" "$png"
  rm -f "$markup"
  printf '%s' "$png"
}

if [[ -z "$headline" ]]; then
  magick "$src" -resize "x$H" -background black -gravity center -extent "${W}x${H}" \
    -alpha remove -alpha off -depth 8 "PNG24:$dest"
else
  head_png=$(text_layer Black 40 4096 '#FFFFFF' "$headline")
  sub_png=$(text_layer ''    21 1024 '#8A8A8A' "$subline")
  magick "$src" \
    -resize "x$((H - BAND))" -background black -gravity center -extent "${W}x$((H - BAND))" \
    -gravity north -splice "0x$BAND" \
    "$head_png" -gravity north -geometry +0+150 -composite \
    "$sub_png"  -gravity north -geometry +0+250 -composite \
    -alpha remove -alpha off -depth 8 "PNG24:$dest"
  rm -f "$head_png" "$sub_png"
fi

# Fail loudly rather than letting Play reject the upload later.
read -r w h alpha < <(magick identify -format '%w %h %A\n' "$dest")
[[ "$w" == "$W" && "$h" == "$H" ]] || { echo "FAIL $dest is ${w}x${h}, want ${W}x${H}" >&2; exit 1; }
[[ "$alpha" == "Undefined" || "$alpha" == "False" ]] || { echo "FAIL $dest still carries alpha" >&2; exit 1; }
echo "$dest  ${w}x${h}  no-alpha"
