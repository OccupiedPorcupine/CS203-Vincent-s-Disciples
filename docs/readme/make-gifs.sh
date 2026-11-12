#!/usr/bin/env bash
# Turns the videos recorded by docs/readme/record-demos.cjs into docs/readme/demo-<clip>.gif.
# record-demos.cjs runs this itself; to redo just the GIFs (say, at another frame rate):
#   docs/readme/make-gifs.sh <work-dir> [clip ...]
#   FPS=10 docs/readme/make-gifs.sh /tmp/chicky-demos prep
# For each clip the work folder holds <clip>.webm (1280x800), <clip>.trim ("start duration" in
# seconds) and frame-<clip>.png, a browser-window frame with a see-through content area. The video
# is scaled into that frame on a flat cream margin, then made into a GIF with a palette built from
# the clip itself (two passes in one ffmpeg call: palettegen, then paletteuse). Flat colours only:
# gradients dither badly.
# Settings: FPS (default 12), DITHER (default bayer:bayer_scale=5), STATS (palette from the "full"
# clip, the default, or "diff"), MAX_MB (default 5: warns above).
set -euo pipefail

work="${1:?usage: make-gifs.sh <work-dir> [clip ...]}"
shift
clips=("$@")
[ ${#clips[@]} -eq 0 ] && clips=(dashboard forecast prep assistant)

out="$(cd "$(dirname "$0")" && pwd)"
fps="${FPS:-12}"
dither="${DITHER:-bayer:bayer_scale=5}"
# "full" weighs the whole clip, so the static header gradient keeps enough colours; "diff" favours
# what moves.
stats="${STATS:-full}"
max_mb="${MAX_MB:-5}"
# Frames that barely differ from the last are dropped and the last one held instead (GIF frames
# can each have their own delay). Static stretches then cost almost nothing, and the shimmer of the
# video's compression doesn't repaint the whole frame. At most this many frames in a row are dropped.
max_hold=$fps

# Must match FRAME in record-demos.cjs.
canvas_w=960
canvas_h=646
content_w=928
content_h=580
content_x=16
content_y=50

for clip in "${clips[@]}"; do
  read -r start duration < "$work/$clip.trim"
  gif="$out/demo-$clip.gif"
  ffmpeg -v error -y -i "$work/$clip.webm" -i "$work/frame-$clip.png" -filter_complex "
    color=c=0xfffcf5:s=${canvas_w}x${canvas_h}:r=${fps}[bg];
    [0:v]trim=start=${start}:duration=${duration},setpts=PTS-STARTPTS,fps=${fps},scale=${content_w}:${content_h}:flags=lanczos[v];
    [bg][v]overlay=${content_x}:${content_y}:shortest=1[framed];
    [framed][1:v]overlay=0:0,mpdecimate=hi=64*24:lo=64*8:frac=0.2:max=${max_hold},split[a][b];
    [a]palettegen=stats_mode=${stats}:max_colors=256[palette];
    [b][palette]paletteuse=dither=${dither}:diff_mode=rectangle" \
    -fps_mode vfr -loop 0 "$gif"
  bytes=$(stat -f %z "$gif" 2>/dev/null || stat -c %s "$gif")
  printf '%s: %.2f MB, %s s at %s fps\n' "$(basename "$gif")" "$(echo "$bytes / 1048576" | bc -l)" "$duration" "$fps"
  if [ "$bytes" -gt $((max_mb * 1048576)) ]; then
    echo "  over ${max_mb} MB: try FPS=10 or a lower DITHER bayer_scale" >&2
  fi
done
