"""VS screen backgrounds and logo (2026-10-16, user: match the VS card made for the clip, art-source/Renders/VS/vs_card.py).

Writes into Common/Icons/MobBattle/Vs/:
  Bg_<Color>_L.png / Bg_<Color>_R.png for every team palette color: one half of the screen with the slanted split,
      a radial glow of the color, light diagonal streaks and small floating particles (embers on the left, snow-like
      dots on the right); the right half also carries the bright seam along its slanted edge.
  VsLogo.png: big italic white VS, thick dark outline, red and blue offset shadows, a pale flash behind it.
  Glow_L.png / Glow_R.png: the soft halo put behind each fighter (warm / cold).
  Unknown.png: the big "?" shown where a mob or player has no picture yet (`--unknown` writes only this one).
  The name bar (dark, its edge on the slant, a light line under it) is baked into each half.

Geometry (in a 1920x1080 screen): the split runs from (1100, 0) to (820, 1080). Each half is a box HALF of the screen
wide (VsScreen.ui FlexWeight 580 of 1000), anchored to its own screen edge; the picture is stretched to that box, so
it is drawn at BOX_W x BOX_H with the same aspect. Run with plain `python scripts/gen-vs-assets.py` (Pillow).
"""
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Not under UI/Custom: the client packs every PNG there into one UI texture atlas, which ours filled (2026-10-16,
# "Texture atlas rejected image" for vanilla UI). AssetImage loads Icons/... paths on their own, as the Memories page.
OUT = Path(__file__).resolve().parent.parent / "Common/Icons/MobBattle/Vs"
W, H = 1920, 1080
HALF = 0.58
TOP, BOTTOM = 1100, 820            # the split at the top and the bottom of the screen
BOX = W * HALF                     # one half's box width on screen
BOX_W, BOX_H = 800, round(800 * H / BOX)   # baked size (stretched to the box in game)
FONT_BLACK = "C:/Windows/Fonts/impact.ttf"
BAR = 118                          # name bar height on a 1080 screen (VsScreen.ui labels sit on it)

PALETTE = {  # Teams.PALETTE
    "Red": "#FF4B3E", "Blue": "#4B9BFF", "Green": "#4BFF6A", "Yellow": "#FFD23E", "Orange": "#FF8C1A",
    "Purple": "#A24BFF", "Pink": "#FF6BD5", "Cyan": "#3EF0FF", "White": "#F2F2F2", "Black": "#2A2A2A",
    "Brown": "#8B5A2B", "Lime": "#B6FF3E", "Gray": "#808080", "Magenta": "#FF00FF",
}


def rgb(hexs):
    v = int(hexs[1:], 16)
    return (v >> 16) & 255, (v >> 8) & 255, v & 255


def mix(a, b, t):
    return tuple(round(a[i] * (1 - t) + b[i] * t) for i in range(3))


def half(color, left):
    """One half at BOX_W x BOX_H, its screen box mapped: left box = [0, BOX], right box = [W - BOX, W]."""
    base = rgb(color)
    light = mix(base, (0, 0, 0), 0.18)
    dark = mix(base, (0, 0, 0), 0.78)
    sx = BOX_W / BOX                     # screen px -> baked px
    x0 = 0 if left else W - BOX          # box's screen x
    bg = Image.new("RGB", (BOX_W, BOX_H), dark)
    px = bg.load()
    cx = (W * 0.27 if left else W * 0.73) - x0
    cy = H * 0.5
    rmax = math.hypot(W, H) * 0.5
    for y in range(BOX_H):
        sy = y / sx
        for x in range(BOX_W):
            t = min(1.0, math.hypot(x / sx - cx, sy - cy) / rmax) ** 1.25
            px[x, y] = mix(light, dark, t)
    bg = bg.convert("RGBA")
    rnd = random.Random(7 if left else 11)
    streaks = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(streaks)
    for _ in range(22):
        x = rnd.randint(-200, BOX_W + 200)
        w = rnd.randint(3, 13)
        d.line([(x, 0), (x - 380 * sx, BOX_H)], fill=(255, 255, 255, rnd.randint(10, 26)), width=w)
    bg.alpha_composite(streaks)
    dots = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(dots)
    tint = mix((255, 190, 90), base, 0.25) if left else (220, 238, 255)
    for _ in range(110):
        x, y, r = rnd.randint(0, BOX_W), rnd.randint(0, BOX_H), rnd.choice([1.5, 2, 2, 3, 4])
        d.ellipse([x - r, y - r, x + r, y + r], fill=tint + (rnd.randint(90, 220),))
    bg.alpha_composite(dots.filter(ImageFilter.GaussianBlur(1.2)))
    bg.alpha_composite(dots)
    # the name bar (as the card: dark, 118 px of 1080, its edge on the slant) and a 6 px line in a light tint
    bar_h = round(BAR * BOX_H / H)
    line_h = max(2, round(6 * BOX_H / H))
    bar = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    db = ImageDraw.Draw(bar)
    db.rectangle([0, 0, BOX_W, bar_h], fill=(12, 10, 18, 235))
    db.rectangle([0, bar_h, BOX_W, bar_h + line_h], fill=mix(base, (255, 255, 255), 0.35) + (255,))
    bg.alpha_composite(bar)
    # the slanted edge (transparent beyond), in baked px
    top, bottom = (TOP - x0) * sx, (BOTTOM - x0) * sx
    mask = Image.new("L", bg.size, 0)
    pts = [(0, 0), (top, 0), (bottom, BOX_H), (0, BOX_H)] if left else [(top, 0), (BOX_W, 0), (BOX_W, BOX_H), (bottom, BOX_H)]
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    out = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    if not left:
        seam = Image.new("RGBA", bg.size, (0, 0, 0, 0))
        ImageDraw.Draw(seam).line([(top, 0), (bottom, BOX_H)], fill=(255, 255, 255, 255), width=6)
        glow = seam.filter(ImageFilter.GaussianBlur(8))
        out.alpha_composite(glow)
        out.alpha_composite(seam)
    return out


MASKS = Path(__file__).resolve().parent.parent / "Common/UI/Custom/MobBattle/Masks"
SEAM_ROOM = 14   # baked px the right mask reaches past the slant, so the right half's bright seam is not cut


def half_mask(left):
    """A half's clip mask (VsScreen.ui #LHalf / #RHalf MaskTexturePath, 2026-10-17, user: each side cut at the slanted
    split, as Smash): white where the half shows, transparent beyond its slanted edge; stretched to the half's box like
    its background, so the two always meet on the background's own slant."""
    x0 = 0 if left else W - BOX
    sx = BOX_W / BOX
    top, bottom = (TOP - x0) * sx, (BOTTOM - x0) * sx
    if left:
        pts = [(0, 0), (top, 0), (bottom, BOX_H), (0, BOX_H)]
    else:
        pts = [(top - SEAM_ROOM, 0), (BOX_W, 0), (BOX_W, BOX_H), (bottom - SEAM_ROOM, BOX_H)]
    alpha = Image.new("L", (BOX_W, BOX_H), 0)
    ImageDraw.Draw(alpha).polygon(pts, fill=255)
    out = Image.new("RGBA", (BOX_W, BOX_H), (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def write_masks():
    MASKS.mkdir(parents=True, exist_ok=True)
    for left in (True, False):
        p = MASKS / f"Half{'L' if left else 'R'}.png"
        half_mask(left).save(p, optimize=True)
        print(p, p.stat().st_size, "bytes")


def logo():
    k = 2   # drawn at twice the card's size: the game stretches it to about 640 px wide
    f = ImageFont.truetype(FONT_BLACK, 360 * k)
    txt = Image.new("RGBA", (900 * k, 520 * k), (0, 0, 0, 0))
    d = ImageDraw.Draw(txt)
    pos = (180 * k, 40 * k)
    d.text((pos[0] - 14 * k, pos[1] + 6 * k), "VS", font=f, fill=(255, 70, 70, 255), stroke_width=16 * k, stroke_fill=(255, 70, 70, 255))
    d.text((pos[0] + 14 * k, pos[1] + 6 * k), "VS", font=f, fill=(80, 160, 255, 255), stroke_width=16 * k, stroke_fill=(80, 160, 255, 255))
    d.text(pos, "VS", font=f, fill=(255, 255, 255, 255), stroke_width=12 * k, stroke_fill=(15, 10, 25, 255))
    txt = txt.transform(txt.size, Image.AFFINE, (1, 0.22, -60 * k, 0, 1, 0), Image.BICUBIC)
    txt = txt.crop(txt.getbbox())
    # a pale flash behind it, as the card
    pad = 90 * k
    out = Image.new("RGBA", (txt.width + 2 * pad, txt.height + 2 * pad), (0, 0, 0, 0))
    flash = Image.new("RGBA", out.size, (0, 0, 0, 0))
    ImageDraw.Draw(flash).ellipse([pad * 0.6, pad * 0.7, out.width - pad * 0.6, out.height - pad * 0.7], fill=(255, 245, 220, 130))
    out.alpha_composite(flash.filter(ImageFilter.GaussianBlur(35 * k)))
    out.alpha_composite(txt, (pad, pad))
    return out.crop(out.getbbox())


TITLE_CANVAS = (1900, 240)   # VsScreen.ui #KindTitle keeps this aspect
TITLES = {  # Kind.key -> (en, es): the battle kind under the VS, in the VS's own style (user, 2026-10-16)
    "duel": ("DUEL", "DUELO"),
    "royale": ("BATTLE ROYALE", "BATTLE ROYALE"),
    "teams": ("TEAM BATTLE", "BATALLA DE EQUIPOS"),
    "allvsone": ("EVERYONE VS ONE", "TODOS CONTRA UNO"),
}


def title(text):
    """Impact, white with a dark outline, red and blue offset shadows, slanted: the VS logo's look, smaller."""
    k = 2
    f = ImageFont.truetype(FONT_BLACK, 110 * k)
    w = int(ImageDraw.Draw(Image.new("RGBA", (1, 1))).textlength(text, font=f)) + 140 * k
    im = Image.new("RGBA", (w, 190 * k), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pos = (60 * k, 20 * k)
    d.text((pos[0] - 6 * k, pos[1] + 3 * k), text, font=f, fill=(255, 70, 70, 255), stroke_width=7 * k, stroke_fill=(255, 70, 70, 255))
    d.text((pos[0] + 6 * k, pos[1] + 3 * k), text, font=f, fill=(80, 160, 255, 255), stroke_width=7 * k, stroke_fill=(80, 160, 255, 255))
    d.text(pos, text, font=f, fill=(255, 255, 255, 255), stroke_width=5 * k, stroke_fill=(15, 10, 25, 255))
    im = im.transform(im.size, Image.AFFINE, (1, 0.22, -25 * k, 0, 1, 0), Image.BICUBIC)
    im = im.crop(im.getbbox())
    # every title on the same canvas, centered: one fixed AssetImage box shows them all without stretching
    canvas = Image.new("RGBA", TITLE_CANVAS, (0, 0, 0, 0))
    canvas.alpha_composite(im, ((TITLE_CANVAS[0] - im.width) // 2, (TITLE_CANVAS[1] - im.height) // 2))
    return canvas


UNKNOWN_SIZE = 384   # VsScreen.PICTURE: the same square as a mob's picture, so it fills any slot a mob would


def unknown():
    """A mob with no picture yet (2026-10-17, user: not the game's small red X; a big "?" the size of a mob, in the VS
    style, so the team color behind it still shows): white Impact "?", dark outline, red and blue offset shadows,
    slanted, standing on the bottom edge like a mob's feet, on a transparent square."""
    k = 3
    s = UNKNOWN_SIZE * k
    f = ImageFont.truetype(FONT_BLACK, int(s * 0.95))
    txt = Image.new("RGBA", (s * 2, s * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(txt)
    pos = (s // 2, s // 4)
    d.text((pos[0] - 14 * k, pos[1] + 6 * k), "?", font=f, fill=(255, 70, 70, 255), stroke_width=12 * k, stroke_fill=(255, 70, 70, 255))
    d.text((pos[0] + 14 * k, pos[1] + 6 * k), "?", font=f, fill=(80, 160, 255, 255), stroke_width=12 * k, stroke_fill=(80, 160, 255, 255))
    d.text(pos, "?", font=f, fill=(255, 255, 255, 255), stroke_width=9 * k, stroke_fill=(15, 10, 25, 255))
    txt = txt.transform(txt.size, Image.AFFINE, (1, 0.18, -40 * k, 0, 1, 0), Image.BICUBIC)
    txt = txt.crop(txt.getbbox())
    # about as tall as a mob in its square (86 %), centered, its foot on the bottom edge
    h = int(s * 0.86)
    w = max(1, round(txt.width * h / txt.height))
    txt = txt.resize((w, h), Image.LANCZOS)
    canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    canvas.alpha_composite(txt, ((s - w) // 2, s - h))
    return canvas.resize((UNKNOWN_SIZE, UNKNOWN_SIZE), Image.LANCZOS)


def column_overlay():
    """One team column of the 3+ team screen (Smash-like, user 2026-10-16), drawn over the team's color: a dark header
    band, soft light streaks, specks, darker sides, and a soft dark shade rising from the bottom."""
    w, h = 480, 1080
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    fog_layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    fx = fog_layer.load()
    for y in range(h):
        fog = max(0.0, (y - h * 0.55) / (h * 0.45)) ** 1.8                # fog from the bottom, smooth start
        for x in range(w):
            side = min(x, w - 1 - x) / (w / 2)
            a = round(110 * (1 - min(1, side * 3)) ** 2)              # dark edges, all the way down
            if a:
                px[x, y] = (0, 0, 0, a)
            if fog > 0:
                fx[x, y] = (6, 4, 10, round(120 * fog))   # a soft dark shade (user: the white fog was too strong)
    im.alpha_composite(fog_layer)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w, 130], fill=(8, 6, 12, 238))                 # header band
    rnd = random.Random(5)
    streaks = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ds = ImageDraw.Draw(streaks)
    for _ in range(8):
        x = rnd.randint(-100, w + 100)
        ds.line([(x, 130), (x - 200, h)], fill=(255, 255, 255, rnd.randint(8, 18)), width=rnd.randint(3, 10))
    for _ in range(70):
        x, y, r = rnd.randint(0, w), rnd.randint(140, h), rnd.choice([1.5, 2, 2, 3])
        ds.ellipse([x - r, y - r, x + r, y + r], fill=(255, 210, 150, rnd.randint(80, 200)))
    im.alpha_composite(streaks)
    return im


def glow(left):
    """A soft colored glow put behind each fighter (warm on the left, cold on the right, as the card's halos): one
    shared picture instead of a halo baked into every mob picture."""
    size = 256
    color = (255, 150, 90) if left else (120, 190, 255)
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = im.load()
    for y in range(size):
        for x in range(size):
            d = math.hypot((x - size / 2) / (size / 2), (y - size / 2) / (size / 2))
            a = max(0.0, 1 - d) ** 1.6
            px[x, y] = color + (round(150 * a),)
    return im


def badge():
    """The "+N" badge: a dark slanted pill with a light border, the number goes on it (VsScreen.ui #xMore)."""
    w, h = 460, 160
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    slant = 36
    pts = [(slant + 6, 6), (w - 6, 6), (w - slant - 6, h - 6), (6, h - 6)]
    d.polygon(pts, fill=(12, 10, 18, 225))
    d.line(pts + [pts[0]], fill=(255, 255, 255, 230), width=7)
    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).polygon([(x + 8, y + 8) for x, y in pts], fill=(0, 0, 0, 120))
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(6)))
    out.alpha_composite(im)
    return out


def royale_bg():
    """Bg_Royale.png (2026-10-16, user: a royale has no sides, so one background, not split): the whole screen at
    1280x720 (stretched in game), a gold arena glow in the middle of a deep violet, light streaks, gold embers on the
    left and pale sparks on the right, and the name bar across the whole width with a gold line."""
    bw, bh = 1280, 720
    base, gold = (52, 22, 84), (255, 190, 80)
    light, dark = mix(gold, base, 0.45), mix(base, (0, 0, 0), 0.55)
    bg = Image.new("RGB", (bw, bh), dark)
    px = bg.load()
    cx, cy, rmax = bw * 0.5, bh * 0.58, math.hypot(bw, bh) * 0.55
    for y in range(bh):
        for x in range(bw):
            t = min(1.0, math.hypot((x - cx) * 0.8, y - cy) / rmax) ** 0.9
            px[x, y] = mix(light, dark, t)
    bg = bg.convert("RGBA")
    rnd = random.Random(23)
    streaks = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(streaks)
    for _ in range(30):
        x = rnd.randint(-300, bw + 300)
        w = rnd.randint(2, 9)
        lean = 250 if x < bw / 2 else -250    # both sides lean toward the middle: the crowds rush in
        d.line([(x, 0), (x + lean, bh)], fill=(255, 255, 255, rnd.randint(8, 22)), width=w)
    bg.alpha_composite(streaks)
    dots = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(dots)
    for _ in range(170):
        x, y, r = rnd.randint(0, bw), rnd.randint(0, bh), rnd.choice([1, 1.5, 2, 2, 3])
        tint = (255, 200, 110) if x < bw / 2 else (235, 225, 255)
        d.ellipse([x - r, y - r, x + r, y + r], fill=tint + (rnd.randint(90, 220),))
    bg.alpha_composite(dots.filter(ImageFilter.GaussianBlur(1.0)))
    bg.alpha_composite(dots)
    bar_h = round(BAR * bh / H)
    line_h = max(2, round(6 * bh / H))
    bar = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    db = ImageDraw.Draw(bar)
    db.rectangle([0, 0, bw, bar_h], fill=(12, 10, 18, 235))
    db.rectangle([0, bar_h, bw, bar_h + line_h], fill=(255, 205, 110, 255))
    bg.alpha_composite(bar)
    return bg


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    import sys
    if "--masks" in sys.argv:   # only the halves' clip masks
        write_masks()
        return
    if "--unknown" in sys.argv:   # only the "?" picture, the rest untouched
        p = OUT / "Unknown.png"
        unknown().save(p, optimize=True)
        print(p, p.stat().st_size, "bytes")
        return
    total = 0
    for name, color in PALETTE.items():
        for left in (True, False):
            p = OUT / f"Bg_{name}_{'L' if left else 'R'}.png"
            half(color, left).save(p, optimize=True)
            total += p.stat().st_size
    p = OUT / "Bg_Royale.png"
    royale_bg().save(p, optimize=True)
    total += p.stat().st_size
    p = OUT / "VsLogo.png"
    logo().save(p, optimize=True)
    total += p.stat().st_size
    for key, texts in TITLES.items():
        for lang, text in zip(("en", "es"), texts):
            p = OUT / f"Kind_{key}_{lang}.png"
            title(text).save(p, optimize=True)
            total += p.stat().st_size
    p = OUT / "ColumnOverlay.png"
    column_overlay().save(p, optimize=True)
    total += p.stat().st_size
    p = OUT / "Unknown.png"
    unknown().save(p, optimize=True)
    total += p.stat().st_size
    p = OUT / "MoreBadge.png"
    badge().save(p, optimize=True)
    total += p.stat().st_size
    for left in (True, False):
        p = OUT / f"Glow_{'L' if left else 'R'}.png"
        glow(left).save(p, optimize=True)
        total += p.stat().st_size
    print(f"{len(PALETTE) * 2 + 4} files, {total / 1048576:.2f} MB")


if __name__ == "__main__":
    main()
