"""Builds the README artwork in docs/readme/ (hero, footer, feature icons, live button, divider).

Run from anywhere: python3 docs/readme/build-svgs.py
Each SVG embeds its own Kaisei HarunoUmi subset, fetched from Google Fonts with `text=`,
because an SVG shown through <img> on GitHub cannot load web fonts.
"""
import base64, os, re, sys, tempfile, urllib.parse, urllib.request, hashlib

OUT = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(tempfile.gettempdir(), "chicky-readme-fonts")
os.makedirs(OUT, exist_ok=True)
os.makedirs(CACHE, exist_ok=True)
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"


def font_b64(text):
    """Kaisei HarunoUmi subset holding only the glyphs in `text`, base64 woff2."""
    chars = "".join(sorted(set(text)))
    key = hashlib.sha1(chars.encode()).hexdigest()[:12]
    path = os.path.join(CACHE, f"kaisei-{key}.woff2")
    if not os.path.exists(path):
        url = "https://fonts.googleapis.com/css2?family=Kaisei+HarunoUmi&text=" + urllib.parse.quote(chars)
        css = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA})).read().decode()
        woff = re.search(r"url\((https://[^)]+)\)", css).group(1)
        data = urllib.request.urlopen(urllib.request.Request(woff, headers={"User-Agent": UA})).read()
        open(path, "wb").write(data)
    return base64.b64encode(open(path, "rb").read()).decode()


def font_face(text):
    return ("@font-face{font-family:'Kaisei HarunoUmi';font-weight:400;"
            f"src:url(data:font/woff2;base64,{font_b64(text)}) format('woff2')}}")


def write(name, svg):
    svg = re.sub(r"\n\s*\n", "\n", svg.strip()) + "\n"
    open(os.path.join(OUT, name), "w").write(svg)
    print(f"{name:28s} {len(svg.encode())/1024:6.1f} KB")


def smooth(pts, t=0.5):
    """Catmull-Rom through pts as a cubic bezier path (no leading M)."""
    d = []
    for i in range(len(pts) - 1):
        p0 = pts[i - 1] if i > 0 else pts[i]
        p1, p2 = pts[i], pts[i + 1]
        p3 = pts[i + 2] if i + 2 < len(pts) else p2
        c1 = (p1[0] + (p2[0] - p0[0]) * t / 3, p1[1] + (p2[1] - p0[1]) * t / 3)
        c2 = (p2[0] - (p3[0] - p1[0]) * t / 3, p2[1] - (p3[1] - p1[1]) * t / 3)
        d.append(f"C{c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {p2[0]:.1f} {p2[1]:.1f}")
    return " ".join(d)


# ---------------------------------------------------------------- shared art
def bowl(steam_class="steam"):
    """The app icon artwork in its 512 space (tile drawn separately)."""
    return f"""
    <g fill="none" stroke="#fffcf5" stroke-width="14" stroke-linecap="round">
      <path class="{steam_class} s1" d="M206 168c-16-20 16-34 0-56"/>
      <path class="{steam_class} s2" d="M256 160c-16-20 16-34 0-56"/>
      <path class="{steam_class} s3" d="M306 168c-16-20 16-34 0-56"/>
    </g>
    <path d="M132 276c8-52 60-82 124-82s116 30 124 82z" fill="#fff1d6"/>
    <path d="M196 236c22-26 66-30 92-10l-14 22c-20-12-46-10-62 6z" fill="#f5e3d2" stroke="#e9c9a8" stroke-width="4"/>
    <path d="M112 268h288c0 78-62 136-144 136s-144-58-144-136z" fill="#fffcf5"/>
    <rect x="200" y="400" width="112" height="22" rx="11" fill="#fffcf5"/>
    <path d="M136 300h240" stroke="#a9dcd6" stroke-width="12" stroke-linecap="round"/>"""


# ---------------------------------------------------------------- hero
TAG1 = "Know tomorrow’s demand"
TAG2 = "before you start cooking."
SUB1 = "Forecasts, prep lists and an assistant"
SUB2 = "for hawker and F&B owners"
HERO_TEXT = "Chicky" + TAG1 + TAG2 + SUB1 + SUB2 + "Tomorrow47 chicken riceSoldExpectedToday"

# chart geometry
CX0, CX1, TODAY_X = 752, 1188, 1084
sales_y = [236, 226, 232, 214, 186, 200, 226, 220, 200, 172, 150, 176, 184, 168]
step = (TODAY_X - CX0) / (len(sales_y) - 1)
SALES = [(CX0 + i * step, y) for i, y in enumerate(sales_y)]
FORE = [SALES[-1], (TODAY_X + 34, 150), (TODAY_X + 68, 136), (CX1, 118)]
spread = [0, 10, 17, 24]


def hero(dark):
    if dark:
        c = dict(bg1="#15222b", bg2="#1a2a32", blobA="#1ba098", blobAo=0.30, blobB="#f5d9bd", blobBo=0.05,
                 frame="#24343d", word="#5fc8be", text="#fffcf5", muted="#b3bfc5", card="#ffffff", cardo=0.04,
                 cardline="#ffffff", cardlineo=0.09, grid="#ffffff", grido=0.07, sales="#efe9dc", wash="#efe9dc",
                 washo=0.12, fore="#2bb8ae", band="#1ba098", bando=0.22, today="#ffffff", todayo=0.22,
                 float="#203640", floatline="#2f4651", fl_label="#b3bfc5", fl_num="#5fc8be", fl_text="#fffcf5",
                 dotstroke="#1a2a32", shadow="#000000", shadowo=0.35)
    else:
        c = dict(bg1="#fffcf5", bg2="#fffaf0", blobA="#a9dcd6", blobAo=0.55, blobB="#f5d9bd", blobBo=0.55,
                 frame="#efe8da", word="#0f7d77", text="#425664", muted="#66767f", card="#ffffff", cardo=0.72,
                 cardline="#ffffff", cardlineo=0.9, grid="#e6e8eb", grido=1, sales="#425664", wash="#425664",
                 washo=0.16, fore="#1ba098", band="#1ba098", bando=0.16, today="#9aa6af", todayo=0.9,
                 float="#ffffff", floatline="#f0ece4", fl_label="#66767f", fl_num="#0f7d77", fl_text="#425664",
                 dotstroke="#ffffff", shadow="#425664", shadowo=0.14)

    sales_d = f"M{SALES[0][0]:.1f} {SALES[0][1]:.1f} " + smooth(SALES)
    area_d = sales_d + f" L{TODAY_X:.1f} 300 L{CX0:.1f} 300 Z"
    fore_d = f"M{FORE[0][0]:.1f} {FORE[0][1]:.1f} " + smooth(FORE)
    upper = [(x, y - s) for (x, y), s in zip(FORE, spread)]
    lower = [(x, y + s) for (x, y), s in zip(FORE, spread)][::-1]
    band_d = (f"M{upper[0][0]:.1f} {upper[0][1]:.1f} " + smooth(upper)
              + f" L{lower[0][0]:.1f} {lower[0][1]:.1f} " + smooth(lower) + " Z")
    ex, ey = FORE[-1]
    T = 9  # seconds per cycle

    css = f"""
{font_face(HERO_TEXT)}
text{{font-family:'Kaisei HarunoUmi',serif;font-weight:400}}
.steam{{opacity:.75}}
.pop,.ring{{transform-box:fill-box;transform-origin:center}}
.ring{{opacity:0}}
  .steam{{animation:steam 3.6s ease-in-out infinite;opacity:0}}
  .s2{{animation-delay:-1.2s}} .s3{{animation-delay:-2.4s}}
  .cycle{{animation:cycle {T}s ease-in-out infinite}}
  .sales{{stroke-dasharray:1;animation:draw {T}s cubic-bezier(.45,0,.25,1) infinite}}
  .wash{{animation:wash {T}s ease-out infinite}}
  .today{{animation:today {T}s ease-out infinite}}
  .reveal{{stroke-dasharray:1;animation:reveal {T}s cubic-bezier(.3,0,.2,1) infinite}}
  .pop{{animation:pop {T}s cubic-bezier(.34,1.56,.64,1) infinite}}
  .ring{{animation:ring {T}s ease-out infinite}}
  .card{{animation:card {T}s cubic-bezier(.2,.8,.2,1) infinite}}
  .bob{{animation:bob 6s ease-in-out infinite}}
@media (prefers-reduced-motion:reduce){{*{{animation:none!important}}.steam{{opacity:.75}}.sales,.reveal{{stroke-dasharray:none}}}}
@keyframes steam{{0%{{opacity:0;transform:translateY(34px)}}35%{{opacity:.8}}100%{{opacity:0;transform:translateY(-46px)}}}}
@keyframes cycle{{0%,90%{{opacity:1}}96%,100%{{opacity:0}}}}
@keyframes draw{{0%,4%{{stroke-dashoffset:1}}25%,100%{{stroke-dashoffset:0}}}}
@keyframes wash{{0%,19%{{opacity:0}}31%,100%{{opacity:1}}}}
@keyframes today{{0%,18%{{opacity:0}}25%,100%{{opacity:1}}}}
@keyframes reveal{{0%,25%{{stroke-dashoffset:1}}37%,100%{{stroke-dashoffset:0}}}}
@keyframes pop{{0%,36%{{transform:scale(0)}}41%,100%{{transform:scale(1)}}}}
@keyframes ring{{0%,43%{{opacity:0;transform:scale(1)}}44%{{opacity:.8;transform:scale(1)}}58%{{opacity:0;transform:scale(2.8)}}62%{{opacity:0;transform:scale(1)}}63%{{opacity:.8;transform:scale(1)}}77%,100%{{opacity:0;transform:scale(2.8)}}}}
@keyframes card{{0%,40%{{opacity:0;transform:translateY(14px)}}47%,100%{{opacity:1;transform:translateY(0)}}}}
@keyframes bob{{0%,100%{{transform:translateY(0)}}50%{{transform:translateY(-4px)}}}}
"""

    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 420" width="1280" height="420" role="img" aria-labelledby="t d">
  <title id="t">Chicky</title>
  <desc id="d">Chicky: know tomorrow's demand before you start cooking. Forecasts, prep lists and an assistant for hawker and F&amp;B owners.</desc>
  <style>{css}</style>
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{c['bg1']}"/><stop offset="1" stop-color="{c['bg2']}"/></linearGradient>
    <radialGradient id="blobA" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="{c['blobA']}" stop-opacity="{c['blobAo']}"/><stop offset="1" stop-color="{c['blobA']}" stop-opacity="0"/></radialGradient>
    <radialGradient id="blobB" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="{c['blobB']}" stop-opacity="{c['blobBo']}"/><stop offset="1" stop-color="{c['blobB']}" stop-opacity="0"/></radialGradient>
    <linearGradient id="tile" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1ba098"/><stop offset="1" stop-color="#0f7d77"/></linearGradient>
    <linearGradient id="wash" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{c['wash']}" stop-opacity="{c['washo']}"/><stop offset="1" stop-color="{c['wash']}" stop-opacity="0"/></linearGradient>
    <clipPath id="frame"><rect width="1280" height="420" rx="28"/></clipPath>
    <mask id="foreMask" maskUnits="userSpaceOnUse" x="0" y="0" width="1280" height="420">
      <path class="reveal" pathLength="1" d="{fore_d} l44 -16" fill="none" stroke="#fff" stroke-width="72"/>
    </mask>
    <filter id="soft" x="-20%" y="-20%" width="140%" height="160%"><feDropShadow dx="0" dy="6" stdDeviation="10" flood-color="{c['shadow']}" flood-opacity="{c['shadowo']}"/></filter>
  </defs>

  <g clip-path="url(#frame)">
    <rect width="1280" height="420" fill="url(#bg)"/>
    <circle cx="90" cy="40" r="360" fill="url(#blobA)"/>
    <circle cx="1240" cy="430" r="380" fill="url(#blobB)"/>
    <circle cx="880" cy="-40" r="260" fill="url(#blobA)" opacity=".6"/>
  </g>
  <rect x=".75" y=".75" width="1278.5" height="418.5" rx="27.5" fill="none" stroke="{c['frame']}" stroke-width="1.5"/>

  <!-- icon + wordmark -->
  <g transform="translate(72 52)">
    <rect width="108" height="108" rx="26" fill="url(#tile)"/>
    <g transform="scale(.2109)">{bowl()}</g>
  </g>
  <text x="204" y="140" font-size="108" fill="{c['word']}" letter-spacing="-1">Chicky</text>
  <text x="72" y="238" font-size="46" fill="{c['text']}">{TAG1}</text>
  <text x="72" y="294" font-size="46" fill="{c['text']}">{TAG2}</text>
  <text x="72" y="346" font-size="28" fill="{c['muted']}">{SUB1}</text>
  <text x="72" y="383" font-size="28" fill="{c['muted']}">{SUB2.replace('&', '&amp;')}</text>

  <!-- chart card -->
  <rect x="716" y="52" width="504" height="300" rx="20" fill="{c['card']}" fill-opacity="{c['cardo']}" stroke="{c['cardline']}" stroke-opacity="{c['cardlineo']}" stroke-width="1.5" filter="url(#soft)"/>
  <g font-size="15" fill="{c['muted']}">
    <line x1="752" x2="772" y1="86" y2="86" stroke="{c['sales']}" stroke-width="2.5" stroke-linecap="round"/>
    <text x="780" y="91">Sold</text>
    <line x1="830" x2="852" y1="86" y2="86" stroke="{c['fore']}" stroke-width="2.5" stroke-dasharray="6 5" stroke-linecap="round"/>
    <text x="860" y="91">Expected</text>
  </g>
  <g stroke="{c['grid']}" stroke-opacity="{c['grido']}" stroke-width="1">
    <line x1="752" x2="1188" y1="140" y2="140"/><line x1="752" x2="1188" y1="200" y2="200"/><line x1="752" x2="1188" y1="260" y2="260"/>
  </g>
  <g class="cycle">
    <g class="today">
      <line x1="{TODAY_X}" x2="{TODAY_X}" y1="116" y2="300" stroke="{c['today']}" stroke-opacity="{c['todayo']}" stroke-width="1.5" stroke-dasharray="3 5"/>
      <text x="{TODAY_X}" y="104" font-size="15" fill="{c['muted']}" text-anchor="middle">Today</text>
    </g>
    <path class="wash" d="{area_d}" fill="url(#wash)"/>
    <path class="sales" pathLength="1" d="{sales_d}" fill="none" stroke="{c['sales']}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
    <g mask="url(#foreMask)">
      <path d="{band_d}" fill="{c['band']}" fill-opacity="{c['bando']}"/>
      <path d="{fore_d}" fill="none" stroke="{c['fore']}" stroke-width="3" stroke-dasharray="8 7" stroke-linecap="round"/>
    </g>
    <circle class="ring" cx="{ex:.1f}" cy="{ey:.1f}" r="7" fill="none" stroke="{c['fore']}" stroke-width="2"/>
    <circle class="pop" cx="{ex:.1f}" cy="{ey:.1f}" r="6.5" fill="{c['fore']}" stroke="{c['dotstroke']}" stroke-width="2.5"/>
    <g class="card"><g class="bob">
      <rect x="968" y="272" width="262" height="92" rx="16" fill="{c['float']}" stroke="{c['floatline']}" stroke-width="1.5" filter="url(#soft)"/>
      <circle cx="994" cy="300" r="5" fill="{c['fore']}"/>
      <text x="1008" y="305" font-size="16" fill="{c['fl_label']}">Tomorrow</text>
      <text x="988" y="344" font-size="32" fill="{c['fl_num']}">47<tspan font-size="24" fill="{c['fl_text']}" dx="8">chicken rice</tspan></text>
    </g></g>
  </g>
</svg>"""


# ---------------------------------------------------------------- footer
def wave_path(y, amp, period=640, phase=0, width=2560, bottom=120):
    import math
    pts = []
    for i in range(0, width + 1, 16):
        pts.append((i, y + amp * math.sin(2 * math.pi * (i / period) + phase)))
    d = f"M0 {bottom} L" + " L".join(f"{x:.0f} {yy:.1f}" for x, yy in pts) + f" L{width} {bottom} Z"
    return d


def footer(dark):
    if dark:
        stops = ("#1ba098", "#7fa69c", "#d9b896")
        ops = (0.16, 0.32, 0.62)
    else:
        stops = ("#a9dcd6", "#dfe5dc", "#f5e3d2")
        ops = (0.5, 0.75, 1)
    layers = [
        (46, 10, 640, 0.0, 34, ops[0]),
        (66, 9, 427, 1.3, 26, ops[1]),
        (86, 8, 640, 2.4, 20, ops[2]),
    ]
    masks, rects, css = [], [], []
    for i, (y, amp, period, phase, dur, op) in enumerate(layers):
        # period must divide 1280 for a seamless -1280 loop
        period = 1280 / round(1280 / period)
        masks.append(f'<mask id="m{i}" maskUnits="userSpaceOnUse" x="0" y="0" width="1280" height="120">'
                     f'<path class="w{i}" d="{wave_path(y, amp, period, phase)}" fill="#fff"/></mask>')
        rects.append(f'<rect width="1280" height="120" fill="url(#g)" opacity="{op}" mask="url(#m{i})"/>')
        direction = "" if i % 2 == 0 else " reverse"
        css.append(f".w{i}{{animation:slide {dur}s linear infinite{direction}}}")
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 120" width="1280" height="120" role="img" aria-label="Decorative waves">
  <style>
  {' '.join(css)}
@media (prefers-reduced-motion:reduce){{*{{animation:none!important}}}}
@keyframes slide{{from{{transform:translateX(0)}}to{{transform:translateX(-1280px)}}}}
  </style>
  <defs>
    <linearGradient id="g" x1="0" x2="1" y1="0" y2="0"><stop offset="0" stop-color="{stops[0]}"/><stop offset=".55" stop-color="{stops[1]}"/><stop offset="1" stop-color="{stops[2]}"/></linearGradient>
    {''.join(masks)}
  </defs>
  {''.join(rects)}
</svg>"""


# ---------------------------------------------------------------- icons
TILE = "#d9efec"
INK = "#0f7d77"
ACC = "#1ba098"


def icon(name, label, body):
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64" role="img" aria-label="{label}">
  <rect width="64" height="64" rx="16" fill="{TILE}"/>
  <g fill="none" stroke="{INK}" stroke-width="2.75" stroke-linecap="round" stroke-linejoin="round">{body}
  </g>
</svg>"""
    write(f"icon-{name}.svg", svg)


ICONS = [
    ("forecast", "Demand forecast", f"""
    <path d="M14 49h36" stroke-opacity=".35"/>
    <path d="M14 40l7-6 6 3 7-10"/>
    <path d="M34 27l6-4 9-7" stroke="{ACC}" stroke-dasharray="3.2 4"/>
    <circle cx="49.5" cy="15.5" r="3.2" fill="{ACC}" stroke="none"/>"""),
    ("prep", "Prep list", f"""
    <rect x="13" y="15" width="25" height="35" rx="4"/>
    <path d="M21 15v-2.5h9V15"/>
    <path d="M18 25l2.2 2.2 3.8-4"/><path d="M27.5 25.5h5.5"/>
    <path d="M18 34l2.2 2.2 3.8-4"/><path d="M27.5 34.5h5.5"/>
    <path d="M18.5 43.5h5"/><path d="M27.5 43.5h5.5"/>
    <path d="M50 12l-3 25" stroke="{ACC}"/>
    <path d="M40 37h13c0 6-3 10-6.5 10S40 43 40 37z" stroke="{ACC}" fill="{TILE}"/>"""),
    ("assistant", "Assistant", f"""
    <path d="M18 14h28a6 6 0 0 1 6 6v15a6 6 0 0 1-6 6H29l-9 8v-8h-2a6 6 0 0 1-6-6V20a6 6 0 0 1 6-6z"/>
    <path d="M22 25v5M27 21.5v12M32 24v7M37 20v15M42 25.5v4" stroke="{ACC}"/>"""),
    ("documents", "Stall documents", f"""
    <path d="M16 13h17l9 9v13"/><path d="M16 13v37h17"/>
    <path d="M33 13v9h9"/>
    <path d="M21 28h12M21 34h8"/>
    <circle cx="42" cy="42" r="7.5" fill="{TILE}" stroke="{ACC}"/>
    <path d="M47.5 47.5l4.5 4.5" stroke="{ACC}"/>"""),
    ("calendar", "Calendar", f"""
    <rect x="12" y="16" width="34" height="31" rx="5"/>
    <path d="M12 25h34"/><path d="M21 12v7M37 12v7"/>
    <g fill="{INK}" stroke="none"><circle cx="20" cy="32" r="1.8"/><circle cx="27" cy="32" r="1.8"/><circle cx="34" cy="32" r="1.8"/><circle cx="20" cy="39" r="1.8"/><circle cx="27" cy="39" r="1.8"/></g>
    <path d="M45 33c4 5 7 8.5 7 12a7 7 0 0 1-14 0c0-3.5 3-7 7-12z" fill="{TILE}" stroke="{ACC}"/>
    <path d="M42.5 45.5a2.8 2.8 0 0 0 2.6 2.6" stroke="{ACC}" stroke-width="2"/>"""),
    ("phone", "Phone app and reminders", f"""
    <rect x="15" y="11" width="22" height="42" rx="5"/>
    <path d="M23 46h6"/>
    <path d="M44 16a7 7 0 0 1 7 7v5.5l2.5 4H34.5l2.5-4V23a7 7 0 0 1 7-7z" fill="{TILE}" stroke="{ACC}"/>
    <path d="M41.5 36.5a2.6 2.6 0 0 0 5 0" stroke="{ACC}"/>"""),
]


# ---------------------------------------------------------------- button
BTN_TEXT = "Try Chicky live"


def button(dark):
    ring = ('<rect x="4.75" y="2.75" width="258.5" height="54.5" rx="27.25" fill="none" stroke="#5fc8be" stroke-opacity=".45" stroke-width="1.5"/>'
            if dark else "")
    shadow = "#000000" if dark else "#0f7d77"
    sop = 0.45 if dark else 0.28
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 268 68" width="268" height="68" role="img" aria-label="Try Chicky live">
  <style>
{font_face(BTN_TEXT + "→")}
text{{font-family:'Kaisei HarunoUmi',serif;font-weight:400}}
  .arrow{{animation:nudge 3s ease-in-out infinite}}
  .shine{{animation:shine 5s ease-in-out infinite}}
@media (prefers-reduced-motion:reduce){{*{{animation:none!important}}}}
@keyframes nudge{{0%,60%,100%{{transform:translateX(0)}}75%{{transform:translateX(5px)}}88%{{transform:translateX(1px)}}}}
@keyframes shine{{0%,55%{{transform:translateX(-120px)}}85%,100%{{transform:translateX(360px)}}}}
  </style>
  <defs>
    <linearGradient id="fill" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#138a83"/><stop offset="1" stop-color="#0f7d77"/></linearGradient>
    <linearGradient id="sheen" x1="0" x2="1" y1="0" y2="0"><stop offset="0" stop-color="#fffcf5" stop-opacity="0"/><stop offset=".5" stop-color="#fffcf5" stop-opacity=".22"/><stop offset="1" stop-color="#fffcf5" stop-opacity="0"/></linearGradient>
    <clipPath id="pill"><rect x="4" y="2" width="260" height="56" rx="28"/></clipPath>
    <filter id="sh" x="-10%" y="-20%" width="120%" height="160%"><feDropShadow dx="0" dy="3" stdDeviation="3" flood-color="{shadow}" flood-opacity="{sop}"/></filter>
  </defs>
  <rect x="4" y="2" width="260" height="56" rx="28" fill="url(#fill)" filter="url(#sh)"/>
  <g clip-path="url(#pill)"><g transform="skewX(-22)"><rect class="shine" x="0" y="0" width="56" height="60" fill="url(#sheen)" style="transform:translateX(-120px)"/></g></g>
  {ring}
  <text x="44" y="37" font-size="20" fill="#fffcf5">{BTN_TEXT}</text>
  <text class="arrow" x="205" y="37" font-size="20" fill="#fffcf5">→</text>
</svg>"""


# ---------------------------------------------------------------- divider
def divider():
    import math, random
    random.seed(7)

    def wobble(x0, x1):
        pts, x = [], x0
        while x <= x1:
            pts.append((x, 12 + 1.1 * math.sin(x / 37) + 0.6 * math.sin(x / 11.3) + random.uniform(-.25, .25)))
            x += 20
        return f"M{pts[0][0]:.0f} {pts[0][1]:.1f} " + smooth(pts)

    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 24" width="1280" height="24" role="img" aria-label="Section divider">
  <defs>
    <linearGradient id="l" x1="40" x2="608" y1="0" y2="0" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#1ba098" stop-opacity="0"/><stop offset="1" stop-color="#1ba098"/></linearGradient>
    <linearGradient id="r" x1="672" x2="1240" y1="0" y2="0" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#1ba098"/><stop offset="1" stop-color="#1ba098" stop-opacity="0"/></linearGradient>
  </defs>
  <g fill="none" stroke-width="1.75" stroke-linecap="round">
    <path d="{wobble(40, 608)}" stroke="url(#l)"/>
    <path d="{wobble(672, 1240)}" stroke="url(#r)"/>
  </g>
  <g transform="translate(640 12)">
    <g fill="none" stroke="#1ba098" stroke-width="1.6" stroke-linecap="round" opacity=".8">
      <path d="M-5 -4c-2-2.4 2-4 0-6.6"/><path d="M0 -5c-2-2.4 2-4 0-6.6"/><path d="M5 -4c-2-2.4 2-4 0-6.6"/>
    </g>
    <path d="M-12 -1h24c0 6.5-5.2 11-12 11s-12-4.5-12-11z" fill="#1ba098"/>
  </g>
</svg>"""


if __name__ == "__main__":
    write("hero-light.svg", hero(False))
    write("hero-dark.svg", hero(True))
    write("footer-light.svg", footer(False))
    write("footer-dark.svg", footer(True))
    for n, l, b in ICONS:
        icon(n, l, b)
    write("button-live-light.svg", button(False))
    write("button-live-dark.svg", button(True))
    write("divider.svg", divider())
