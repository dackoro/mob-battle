"""Creative inventory tab icon for Mob Battle, in the style of the game's own tabs.

The game ships two 88x88 pictures per top tab: <Name>.png (flat grey) and <Name>Active.png
(gold, same shape). This draws the Battle Wand as one shape (open ring, gem, emblem with a
cross, shaft with a knot and a pommel), with the gaps cut out where parts overlap, and writes
both pictures to Common/Icons/ItemCategories/.

    python scripts/gen-creative-tab-icon.py [--preview <png>]

Needs numpy and Pillow.
"""
import argparse
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SIZE = 88
SS = 4  # supersampling
OUT = os.path.join("Common", "Icons", "ItemCategories")
GREY = (94, 99, 106)  # the game's inactive tab grey
GOLD_HI = np.array([255, 226, 150])
GOLD_LO = np.array([226, 170, 72])
GOLD_RIM = np.array([213, 154, 48])
GAP = 2.8  # blocks between overlapping parts


def shape():
    n = SIZE * SS
    img = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(img)

    def p(x, y):
        return (x * SS, y * SS)

    def circle(c, r, fill):
        d.ellipse([p(c[0] - r, c[1] - r), p(c[0] + r, c[1] + r)], fill=fill)

    def line(a, b, w, fill):
        d.line([p(*a), p(*b)], fill=fill, width=int(w * SS))
        circle(a, w / 2, fill)
        circle(b, w / 2, fill)

    def diamond(c, r, fill):
        d.polygon([p(c[0], c[1] - r), p(c[0] + r, c[1]), p(c[0], c[1] + r), p(c[0] - r, c[1])], fill=fill)

    def band(c, half, w):  # a carved line across the shaft (which runs at -45 degrees)
        k = math.cos(math.radians(45)) * half
        line((c[0] - k, c[1] - k), (c[0] + k, c[1] + k), w, 0)

    # open ring, its gap where the gem sits
    rc, rr, rw = (55, 31), 22, 8
    d.arc([p(rc[0] - rr, rc[1] - rr), p(rc[0] + rr, rc[1] + rr)], start=-5, end=275, fill=255, width=int(rw * SS))
    for ang in (-5, 275):
        t = math.radians(ang)
        circle((rc[0] + (rr - rw / 2) * math.cos(t), rc[1] + (rr - rw / 2) * math.sin(t)), rw / 2, 255)

    # shaft, pommel and knot
    a, b, k = (9, 79), (38, 50), (22, 66)
    line(a, b, 9, 255)
    circle(a, 7.5, 255)
    circle(k, 6.2, 255)
    band((a[0] + 6.6, a[1] - 6.6), 5.6, 1.9)
    band((k[0] - 4.6, k[1] + 4.6), 5.6, 1.7)
    band((k[0] + 4.6, k[1] - 4.6), 5.6, 1.7)

    # emblem with a carved cross
    e = (39, 48)
    circle(e, 11.5 + GAP, 0)
    circle(e, 11.5, 255)
    line((e[0] - 5.8, e[1] - 5.8), (e[0] + 5.8, e[1] + 5.8), 2.6, 0)
    line((e[0] - 5.8, e[1] + 5.8), (e[0] + 5.8, e[1] - 5.8), 2.6, 0)

    # gem with an inner facet
    g = (71, 16)
    diamond(g, 13 + GAP, 0)
    diamond(g, 13, 255)
    diamond(g, 7.5, 0)
    diamond(g, 5.3, 255)

    return img.resize((SIZE, SIZE), Image.LANCZOS)


def colored(mask, active):
    alpha = np.array(mask).astype(float) / 255
    out = np.zeros((SIZE, SIZE, 4), np.uint8)
    if active:
        # brighter toward the middle, a warmer rim
        yy, xx = np.mgrid[0:SIZE, 0:SIZE]
        dist = (np.hypot(xx - 50, yy - 38) / 62)[..., None]
        c = GOLD_HI * (1 - dist) + GOLD_LO * dist
        inner = np.array(mask.filter(ImageFilter.MinFilter(3))).astype(float) / 255
        rim = (np.clip(alpha - inner, 0, 1) * 0.6)[..., None]
        out[..., :3] = np.clip(c * (1 - rim) + GOLD_RIM * rim, 0, 255).astype(np.uint8)
    else:
        out[..., :3] = GREY
    out[..., 3] = (alpha * 255).round().astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", help="also write both icons on a dark background, 3x, to this PNG")
    args = ap.parse_args()
    mask = shape()
    grey, gold = colored(mask, False), colored(mask, True)
    os.makedirs(OUT, exist_ok=True)
    grey.save(os.path.join(OUT, "MobBattle.png"))
    gold.save(os.path.join(OUT, "MobBattleActive.png"))
    print("wrote", os.path.join(OUT, "MobBattle.png"), "and MobBattleActive.png")
    if args.preview:
        sheet = Image.new("RGBA", (SIZE * 2 + 30, SIZE + 20), (31, 41, 61, 255))
        sheet.alpha_composite(grey, (10, 10))
        sheet.alpha_composite(gold, (SIZE + 20, 10))
        sheet.resize((sheet.width * 3, sheet.height * 3), Image.LANCZOS).save(args.preview)


if __name__ == "__main__":
    main()
