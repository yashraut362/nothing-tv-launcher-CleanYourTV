#!/usr/bin/env python3
"""Turn Lawnicons (https://github.com/LawnchairLauncher/lawnicons, Apache-2.0) into two small text
assets the launcher draws from, so the APK carries path data instead of 10,000 drawables.

    tools/lawnicons.py <lawnicons checkout> <launcher assets dir>

tools/lawnicons-tv.txt adds what Lawnicons lacks for TVs: aliases for TV builds of streaming apps,
words for OEM system apps, and a TV glyph of our own.

Writes, under <assets>/lawnicons/:
  icons.txt   one glyph per line: name, viewBox "x y w h", then one field per stroke:
              "<width> <cap> <join> <path data>"  (cap: b butt, r round, s square; join: m miter, r round, b bevel)
  map.txt     "a:<package>" (TV alias), "c:<package>/<activity>", "p:<package>", "n:<name>" or
              "k:<word>" (a word in the app's name) -> glyph name, one per line
  LICENSE     Lawnicons' licence, which Apache-2.0 asks us to ship with the artwork

Lawnicons glyphs are outlines: strokes on a 192 grid, no fills. Elements are path, circle, ellipse
and rect, with stroke attributes set on the element, a <g> or the <svg>. A file that breaks those
rules (transforms, inline styles, solid fills) is skipped and its apps fall back to an initial.
"""

import re
import shutil
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

NS = "{http://www.w3.org/2000/svg}"
CAPS = {"butt": "b", "round": "r", "square": "s"}
JOINS = {"miter": "m", "round": "r", "bevel": "b"}
INHERITED = ("stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill")


def num(v, default=0.0):
    if v is None:
        return default
    return float(re.sub(r"(px)$", "", v.strip()))


def fmt(x):
    s = f"{x:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def ellipse_d(cx, cy, rx, ry):
    # Two arcs; a single arc cannot draw a full ellipse.
    return (f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0"
            f"a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0z")


def rect_d(x, y, w, h, rx, ry):
    if rx <= 0 and ry <= 0:
        return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
    rx = ry if rx <= 0 else rx
    ry = rx if ry <= 0 else ry
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    return (f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(ry)}"
            f"v{fmt(h - 2 * ry)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(ry)}"
            f"h{fmt(-(w - 2 * rx))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(-ry)}"
            f"v{fmt(-(h - 2 * ry))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(-ry)}z")


class Skip(Exception):
    pass


def convert(svg_file):
    root = ET.parse(svg_file).getroot()
    vb = root.get("viewBox")
    if vb:
        box = [num(v) for v in re.split(r"[\s,]+", vb.strip())]
    else:
        box = [0.0, 0.0, num(root.get("width"), 192), num(root.get("height"), 192)]
    strokes = []

    def walk(el, inherited):
        style = dict(inherited)
        if el.get("transform") or el.get("style"):
            raise Skip("transform or style")
        for a in INHERITED:
            if el.get(a) is not None:
                style[a] = el.get(a)
        tag = el.tag.replace(NS, "")
        d = None
        if tag == "path":
            d = el.get("d")
        elif tag == "circle":
            r = num(el.get("r"))
            d = ellipse_d(num(el.get("cx")), num(el.get("cy")), r, r)
        elif tag == "ellipse":
            d = ellipse_d(num(el.get("cx")), num(el.get("cy")), num(el.get("rx")), num(el.get("ry")))
        elif tag == "rect":
            d = rect_d(num(el.get("x")), num(el.get("y")), num(el.get("width")), num(el.get("height")),
                       num(el.get("rx")), num(el.get("ry")))
        elif tag in ("svg", "g"):
            for child in el:
                walk(child, style)
            return
        elif tag in ("defs", "title", "desc"):
            return
        else:
            raise Skip(f"element {tag}")
        if not d:
            return
        fill = style.get("fill", "black")
        if fill not in ("none", "transparent"):
            raise Skip("solid fill")
        if style.get("stroke", "none") in ("none", "transparent"):
            return
        width = num(style.get("stroke-width"), 1.0)
        cap = CAPS.get(style.get("stroke-linecap", "butt"), "b")
        join = JOINS.get(style.get("stroke-linejoin", "miter"), "m")
        strokes.append(f"{fmt(width)} {cap} {join} {' '.join(d.split())}")

    walk(root, {})
    if not strokes:
        raise Skip("no strokes")
    return " ".join(fmt(v) for v in box), strokes


def norm(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


def main(src, assets):
    src, out = Path(src), Path(assets) / "lawnicons"
    out.mkdir(parents=True, exist_ok=True)

    glyphs, skipped = {}, Counter()
    for f in sorted((src / "svgs").glob("*.svg")):
        try:
            glyphs[f.stem] = convert(f)
        except (Skip, ET.ParseError, ValueError) as e:
            skipped[str(e).split(":")[0]] += 1

    by_component, by_package, by_name = {}, defaultdict(Counter), defaultdict(Counter)
    item = re.compile(r'<item component="ComponentInfo\{([^/}]+)/([^}]*)\}" drawable="([^"]+)"(?: name="([^"]*)")?')
    for pkg, activity, drawable, name in item.findall((src / "app/assets/appfilter.xml").read_text()):
        if drawable not in glyphs:
            continue
        if activity.startswith("."):
            activity = pkg + activity
        by_component.setdefault(f"{pkg}/{activity}", drawable)
        by_package[pkg][drawable] += 1
        if name:
            by_name[norm(name)][drawable] += 1

    aliases, words = {}, {}
    for line in (Path(__file__).parent / "lawnicons-tv.txt").read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        key, rest = line.split("\t", 1)
        if key.startswith("g:"):
            fields = rest.split("\t")
            glyphs[key[2:]] = (fields[0], fields[1:])
        elif key.startswith("a:"):
            aliases[key[2:]] = rest.strip()
        elif key.startswith("k:"):
            words[key[2:]] = rest.strip()
    for target in list(aliases.values()) + list(words.values()):
        if target not in glyphs:
            sys.exit(f"lawnicons-tv.txt names a glyph that doesn't exist: {target}")

    with open(out / "icons.txt", "w") as f:
        for name in sorted(glyphs):
            box, strokes = glyphs[name]
            f.write("\t".join([name, box] + strokes) + "\n")
    with open(out / "map.txt", "w") as f:
        for pkg in sorted(aliases):
            f.write(f"a:{pkg}\t{aliases[pkg]}\n")
        for word in sorted(words):
            f.write(f"k:{word}\t{words[word]}\n")
        for key in sorted(by_component):
            f.write(f"c:{key}\t{by_component[key]}\n")
        for pkg in sorted(by_package):
            f.write(f"p:{pkg}\t{by_package[pkg].most_common(1)[0][0]}\n")
        for name in sorted(by_name):
            if name:
                f.write(f"n:{name}\t{by_name[name].most_common(1)[0][0]}\n")
    shutil.copy(src / "LICENSE", out / "LICENSE")

    print(f"{len(glyphs)} glyphs, skipped {sum(skipped.values())} {dict(skipped)}")
    print(f"{len(by_component)} activities, {len(by_package)} packages, {len(by_name)} names")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
