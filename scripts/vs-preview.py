"""VS screen previews out of the game, from the picture cache (2026-10-17, user: check how every mob looks in the VS
screens without spawning them, "no background, only the mobs").

The server draws the pictures (thumbnails, VS pictures) into its picture cache; this script lays them out the way the
VS screen does (the slot positions come from gen-vs-ui.py) on a plain background and writes PNG files you can flip
through. Nothing in the game changes.

  python scripts/vs-preview.py columns [N|all]      every mob as a team column, N columns per screen (3-8, default 8;
                                                    all = every count from 3 to 8, one folder each)
  python scripts/vs-preview.py halves [K] [--clip]  both halves of a VS (battle royale crowd), K mobs per half (default 9:
                                                    1-3 = the big named slots, 4-9 = medium, 10+ = the crowd mound);
                                                    --clip cuts each side at the slanted split
  python scripts/vs-preview.py sheet [N]            contact sheet: 64 columns of N-column width per image
  python scripts/vs-preview.py all                  every variant above, each in its own folder under --out
  --rule scale                                      halves at one real scale for the whole screen (try it against
                                                    the game's sizes; --giant, --floor tune it)

Run it with python, from any folder: python scripts/vs-preview.py columns all
Options: --out <folder> (default: vs-preview in the repo root), --cache <MobBattleCache folder>, --folder <pictures folder> (instead of
the cache's newest VS folder, e.g. the output of /mobbattle test vsframes), --only <text> (model ids containing it),
--scale <0.1-1> (image size, default 0.5), --labels (model ids under the pictures).
Needs Pillow (pip install pillow). The cache is %APPDATA%/Hytale/UserData/MobBattleCache on Windows, ~/.cache/mobbattle
elsewhere, or where MOBBATTLE_CACHE points; the server log says where ("Picture cache: ...").
Sizes follow the game: halves by each mob's real height against the tallest on screen (--size-power, game 0.5),
small mobs smaller in team columns (--small, game 0.8 blocks); heights come from Assets.zip and the mods folder.
Not shown: team colors, names bar, sky slots for fliers, the variety and tallest-at-the-back ordering.
"""
import argparse
import glob
import importlib.util
import os
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("genvsui", HERE / "gen-vs-ui.py")
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)

BG = (24, 24, 28, 255)

# The game's size rules (VsScreen.java): halves pick one of four sizes by (height / tallest on screen) ^ SIZE_POWER;
# team columns draw a mob under SMALL_MOB blocks smaller (square root of its share, at least SMALL_MIN), baked into the
# _sq picture by the server.
TIER_SIZES = [1.0, 0.75, 0.55, 0.38]
TIER_AT = [0.875, 0.65, 0.465]
GAME_SIZE_POWER = 0.5
GAME_SMALL_MOB, SMALL_MIN = 0.8, 0.5
# a model with no HitBox in its files (the Gobliterator's thrown barrel): an average mob's height, not "no height"
# (that drew it full size and in front of everyone)
DEFAULT_HEIGHT = 1.8


class Progress:
    """Prints "  37% (19/51)" as the screens are written, at most every 5%."""

    def __init__(self, total, what):
        self.total, self.what, self.last = max(1, total), what, -5

    def step(self, done):
        pct = done * 100 // self.total
        if pct >= self.last + 5 or done == self.total:
            self.last = pct
            print(f"  {self.what}: {pct:3d}% ({done}/{self.total})", flush=True)


def model_heights(assets, mods):
    """Model id -> height in blocks (its HitBox, Parent chain followed, times the middle of MinScale/MaxScale),
    read from the game's Assets.zip and the mods (zip/jar files and folders) the way the server sees them."""
    import json
    import zipfile
    raw = {}

    def take(name, data):
        try:
            raw.setdefault(Path(name).stem, json.loads(data.decode("utf-8-sig")))
        except Exception:
            pass

    sources = [Path(m) for m in sorted(glob.glob(str(Path(mods) / "*")))] if mods and Path(mods).is_dir() else []
    if assets and Path(assets).exists():
        sources.append(Path(assets))
    for src in sources:
        if src.is_dir():
            for f in src.glob("Server/Models/**/*.json"):
                take(f.name, f.read_bytes())
        elif src.suffix.lower() in (".zip", ".jar"):
            try:
                z = zipfile.ZipFile(src)
            except Exception:
                continue
            for n in z.namelist():
                if n.startswith("Server/Models/") and n.endswith(".json"):
                    take(n, z.read(n))

    def field(mid, key, depth=0):
        m = raw.get(mid)
        if m is None or depth > 8:
            return None
        if key in m:
            return m[key]
        return field(m.get("Parent"), key, depth + 1) if m.get("Parent") else None

    out = {}
    for mid in raw:
        box = field(mid, "HitBox")
        try:
            h = float(box["Max"]["Y"]) - float(box["Min"]["Y"])
        except Exception:
            continue
        lo, hi = field(mid, "MinScale"), field(mid, "MaxScale")
        scale = ((lo or 1) + (hi or lo or 1)) / 2 if (lo or hi) else 1
        out[mid] = h * scale
    return out


def tier_size(height, tallest, power, sizes=None):
    """The game's tierOf: the share of the slot's size a fighter of this height gets. {@code sizes}: other tier sizes
    to try (the thresholds sit halfway between them, as the game's)."""
    if not height or not tallest:
        return 1.0
    sizes = sizes or TIER_SIZES
    at = TIER_AT if sizes == TIER_SIZES else [(a + b) / 2 for a, b in zip(sizes, sizes[1:])]
    f = min(1.0, height / max(0.1, tallest)) ** power
    for t, limit in enumerate(at):
        if f >= limit:
            return sizes[t]
    return sizes[-1]


def column_shrink(height, small):
    """Extra shrink of a column picture when trying another SMALL_MOB than the game's (already baked in the picture)."""
    def scale(h, limit):
        return 1.0 if not h or h >= limit else max(SMALL_MIN, (h / limit) ** 0.5)
    return scale(height, small) / scale(height, GAME_SMALL_MOB)
LINE = (70, 70, 80, 255)


def cache_root(arg):
    if arg:
        return Path(arg)
    env = os.environ.get("MOBBATTLE_CACHE")
    if env:
        return Path(env)
    win = Path(os.path.expandvars(r"%APPDATA%")) / "Hytale" / "UserData" / "MobBattleCache"
    return win if win.exists() else Path.home() / ".cache" / "mobbattle"


def vs_folder(root):
    """The newest VS picture folder (p384r<render>s<style>)."""
    best, key = None, None
    for d in (root / "vs").glob("p*"):
        m = re.fullmatch(r"p(\d+)(?:r(\d+))?(?:s(\d+))?", d.name)
        if not m or not d.is_dir():
            continue
        k = (int(m.group(2) or 0), int(m.group(3) or 0), int(m.group(1)))
        if key is None or k > key:
            best, key = d, k
    if best is None:
        sys.exit(f"No VS pictures under {root / 'vs'}: start the server once so it draws them.")
    return best


def font(size):
    for f in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def models(folder, suffix, only):
    out = []
    for f in sorted(folder.glob("*" + suffix)):
        m = f.name[: -len(suffix)]
        if m.startswith("player_") or (only and only.lower() not in m.lower()):
            continue
        out.append(m)
    return out


def column_screen(folder, ids, n, labels, heights=None, small=GAME_SMALL_MOB, size=None):
    """One team-column screen: n columns of 1920/n, each the mob's face-framed square at column_size(n), standing on
    the bottom, centered, cut to the column (the in-game mask)."""
    W, H = 1920, 1080
    cw = W / n
    size = size or ui.column_size(n)
    img = Image.new("RGBA", (W, H), BG)
    d = ImageDraw.Draw(img)
    f = font(22)
    for i, m in enumerate(ids):
        x0, x1 = round(i * cw), round((i + 1) * cw)
        sq = Image.open(folder / f"{m}_sq.png").convert("RGBA").resize((size, size), Image.BILINEAR)
        k = column_shrink((heights or {}).get(m), small)
        if k < 0.999:
            # drawn smaller in the same square, standing on its bottom, as the server's columnFrame does
            small_sq = sq.resize((round(size * k), round(size * k)), Image.BILINEAR)
            sq = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            sq.alpha_composite(small_sq, ((size - small_sq.width) // 2, size - small_sq.height))
        left = round((x1 - x0 - size) / 2)              # negative: the square is wider than the column
        crop = sq.crop((-left, 0, -left + (x1 - x0), size))
        img.alpha_composite(crop, (x0, H - size))
        if i:
            d.line([(x0, 0), (x0, H)], fill=LINE, width=2)
        if labels:
            d.rectangle([x0, 0, x1, 34], fill=(0, 0, 0, 170))
            d.text((x0 + 8, 6), m[:22], fill=(240, 240, 240, 255), font=f)
    return img


def layout(k):
    """The slots a half uses for k fighters (VsScreen.layoutFor): named 1-3, medium 4-9, the crowd mound 10+."""
    if k <= 3:
        return ui.NAMED[:k], list(range(k))
    if k <= 9:
        return ui.MEDIUM[:k], [i for i in ui.MEDIUM_DRAW if i < k]
    slots = ui.CROWD[: min(k, ui.GROUND_COUNT)]
    draw = [i for i in ui.CROWD_DRAW if i < len(slots)]
    return slots, draw


# The split between the halves (gen-vs-assets.py TOP / BOTTOM): screen x at the top and at the bottom of the screen.
SPLIT_TOP, SPLIT_BOTTOM = 1100, 820


# The game's one scale (VsScreen.place, 2026-10-18): each picture's square in body heights (<model>_v0.ratio, saved
# by the server when it draws it), the screen's scale = the largest share x ratio, clamped to SCALE_MIN..SCALE_MAX.
DEFAULT_RATIO, SCALE_MIN, SCALE_MAX = 1.15, 1.0, 1.3


def ratio_of(folder, m):
    f = folder / f"{m}_v0.ratio"
    try:
        return float(f.read_text().split()[0])   # "ratio" or "ratio top bottom left right px" (VS_STYLE 5)
    except (OSError, ValueError):
        return DEFAULT_RATIO


def share_of(height, tallest, power):
    return max(0.3, min(1.0, height / max(0.1, tallest)) ** power)


# --rule scale (2026-10-18, user: the 3 and 9 layouts broke the proportions, a fire golem looked smaller than a rat and
# a bird, giants should show only their face and part of the body): one pixels-per-block for the whole screen, no
# per-slot sizes (the slots only give where each one stands), each body measured on its own picture (the opaque rows,
# so a model that draws far more than its hitbox or a long animal is measured as drawn), the screen's reference height
# = the tallest body, but at most GIANT x the median one (a giant then stays at true size and is pushed down so its
# head shows under TOP, its feet off the screen), and a body never smaller than FLOOR of the reference.
# Then (user, same day: the bears looked tiny next to a giant, a goblin as small as a rat because both hit the floor):
# the height under the reference is compressed with --size-power (0.5: a body half as tall gets 71 %), so order and
# differences stay but small ones are not crushed; giants above the reference keep growing in a straight line (cropped).
GIANT, FLOOR, TOP, TARGET_SHARE = 1.5, 0.15, 150, 0.85
# Then (user, same day: Shenron looked better towering over the rest than pushed down and filling the background, and
# with only small mobs on screen they were blown up): a giant grows at most to GIANT_MAX x the reference height, so it
# stands on its slot and towers (pushed down only if its top would pass TOP), and the reference is never under MIN_REF
# blocks (a screen of fish no longer zooms them to a human's size).
GIANT_MAX, MIN_REF = 1.6, 1.6
# And (user: the scale worked much better for crowds than for 2-4 a side, where every picture is big): with 1-3 a side
# a giant grows only to GIANT_MAX_FEW, and no picture is shown above MAX_ZOOM x its own pixels (a koi drawn 1000 px wide
# from a 384 px picture was a blur).
GIANT_MAX_FEW, MAX_ZOOM = 1.45, 3.0
_SPAN = {}


def body_span(folder, m):
    """(top, bottom) of the opaque part of the picture, as fractions of its height."""
    return body_box(folder, m)[:2]


def body_box(folder, m):
    """(top, bottom, left, right) of the opaque part of the left picture, as fractions of its size."""
    key = (str(folder), m)
    if key not in _SPAN:
        try:
            a = Image.open(folder / f"{m}_v0_L.png").convert("RGBA").getchannel("A")
            box = a.point(lambda v: 255 if v > 40 else 0).getbbox()
            _SPAN[key] = (box[1] / a.height, box[3] / a.height, box[0] / a.width, box[2] / a.width) if box \
                else (0.0, 1.0, 0.0, 1.0)
        except Exception:
            _SPAN[key] = (0.0, 1.0, 0.0, 1.0)
    return _SPAN[key]


# --rule scale: each half laid out in its own room (2026-10-18, user: the left side's inner mob was always cut by the
# split, the right had empty space by it). The split runs from x SPLIT_TOP at the top to SPLIT_BOTTOM at the bottom, so
# at the floor the left half has 820 px and the right 1100: the slots (made for a 1113 px box, mirrored around the
# screen's middle) are spread over the room each half really has at the height of each body, and a body still over the
# line is pushed back until at most EDGE_CUT of its width is cut.
EDGE_CUT, EDGE_GAP = 0.12, 8


def split_x(y):
    """Where the slanted split is at screen height y."""
    return SPLIT_TOP + (SPLIT_BOTTOM - SPLIT_TOP) * max(0.0, min(1.0, y / 1080))


def paste_clipped(layer, pic, x, y):
    """alpha_composite that accepts a picture partly off the canvas."""
    x, y = round(x), round(y)
    left, top = max(0, -x), max(0, -y)
    right, bottom = min(pic.width, layer.width - x), min(pic.height, layer.height - y)
    if right > left and bottom > top:
        layer.alpha_composite(pic.crop((left, top, right, bottom)), (x + left, y + top))


def halves_screen(folder, left_ids, right_ids, labels, clip=False, heights=None, power=GAME_SIZE_POWER, sizes=None,
                  rule="game", giant=GIANT, floor=FLOOR):
    """Both halves: the left side's pictures (_v0_L) on the left, the right side's (_v0_R) mirrored on the right.
    clip: each side cut at the slanted split, as a mask on each half would do (the game draws them overlapping today)."""
    W, H = 1920, 1080
    img = Image.new("RGBA", (W, H), BG)
    split = [(SPLIT_TOP, 0), (SPLIT_BOTTOM, H)]
    heights = {m: (heights or {}).get(m) or DEFAULT_HEIGHT for m in left_ids + right_ids}
    tallest = max(list(heights.values()) + [0])
    scale = SCALE_MIN
    ppb, target, real = 0, 0, {}
    if rule == "scale":
        # square side in blocks (ratio x hitbox height = what the render spans) and the body drawn in it
        for m in left_ids + right_ids:
            t, b = body_span(folder, m)
            sq = ratio_of(folder, m) * heights[m]
            real[m] = (sq, max(0.05, sq * (b - t)), t)
        bodies = sorted(v[1] for v in real.values())
        ref = max(MIN_REF, min(bodies[-1], giant * bodies[len(bodies) // 2]))
        # the reference body as tall as the middle slot size of the smaller layout on screen (one scale for both halves;
        # the biggest slot made a pack of 9 too big for its half)
        target = min(sorted(sz for _, _, sz in layout(len(ids))[0])[len(layout(len(ids))[0]) // 2]
                     for ids in (left_ids, right_ids) if ids) * TARGET_SHARE
        ppb = target / ref   # (kept for reference: pixels per block of the reference height)
    if not sizes and rule != "scale":
        for m in left_ids + right_ids:
            scale = max(scale, share_of(heights[m], tallest, power) * ratio_of(folder, m))
        scale = min(SCALE_MAX, scale)
    for side, ids in (("L", left_ids), ("R", right_ids)):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        names = ImageDraw.Draw(layer)
        f = font(18)
        slots, draw = layout(len(ids))
        # as the game (VsScreen.tallestBack): the tallest take the slots drawn first, the smallest end up in front
        by_height = sorted(ids, key=lambda m: -heights.get(m, 0))
        at = {slot: by_height[j] for j, slot in enumerate(draw) if j < len(by_height)}
        for i in draw:
            if i not in at:
                continue
            m = at[i]
            cx, feet, size = slots[i]
            top = None
            if rule == "scale":
                sq, body, t = real[m]
                rel = body / ref
                few = max(len(left_ids), len(right_ids)) <= 3
                share = max(floor, min(GIANT_MAX_FEW if few else GIANT_MAX, min(1.0, rel) ** power * max(1.0, rel)))
                size = max(8, round(target * share * sq / body))
                native = Image.open(folder / f"{m}_v0_{side}.png").width
                size = min(size, round(native * MAX_ZOOM))
                top = feet - size
                if top + t * size < TOP:   # a giant: its head under TOP, its feet off the screen
                    top = TOP - t * size
            elif sizes:   # the old rules: four fixed sizes, every picture filling its own square
                size = round(size * tier_size(heights.get(m), tallest, power, sizes))
            else:       # the game's rules: one scale for every fighter
                size = round(size * share_of(heights[m], tallest, power) * ratio_of(folder, m) / scale)
            pic = Image.open(folder / f"{m}_v0_{side}.png").convert("RGBA").resize((size, size), Image.BILINEAR)
            x = cx - size // 2 if side == "L" else W - cx - size // 2
            if top is not None:
                _, _, bl, br = body_box(folder, m)
                edge = split_x(top + size * 0.6)   # a little below the middle of the body: where it is widest
                if side == "L":
                    x = cx * edge / ui.BOX - size / 2
                    inner = x + size * (br - EDGE_CUT * (br - bl))   # the body's inner edge, less what may be cut
                    if inner > edge - EDGE_GAP:
                        x -= inner - (edge - EDGE_GAP)
                else:
                    room = W - edge
                    x = W - cx * room / ui.BOX - size / 2
                    inner = x + size * (1 - br + EDGE_CUT * (br - bl))   # mirrored picture: its left body edge
                    if inner < edge + EDGE_GAP:
                        x += (edge + EDGE_GAP) - inner
                # the screen's outer edge too (a big body in an outer slot went off the side): at most EDGE_CUT cut
                if side == "L":
                    outer = x + size * (bl + EDGE_CUT * (br - bl))
                    if outer < 0:
                        x -= outer
                else:
                    outer = x + size * (1 - bl - EDGE_CUT * (br - bl))
                    if outer > W:
                        x -= outer - W
                paste_clipped(layer, pic, x, top)
            elif feet - size >= 0:
                layer.alpha_composite(pic, (round(x), feet - size))
            else:
                layer.alpha_composite(pic.crop((0, size - feet, size, size)), (round(x), 0))
            if labels:
                lx = round(x + size / 2 - 60)
                names.text((max(0, lx), min(H - 22, feet - 22)), m[:18], fill=(255, 255, 255, 255), font=f,
                           stroke_width=2, stroke_fill=(0, 0, 0, 255))
        if clip:
            mask = Image.new("L", (W, H), 0)
            poly = [(0, 0)] + split + [(0, H)] if side == "L" else split + [(W, H), (W, 0)]
            ImageDraw.Draw(mask).polygon(poly, fill=255)
            layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", (W, H), 0), mask))
        img.alpha_composite(layer)
    ImageDraw.Draw(img).line(split if clip else [(W // 2, 0), (W // 2, H)], fill=LINE, width=2)
    return img


def save(img, path, scale):
    if scale != 1:
        img = img.resize((round(img.width * scale), round(img.height * scale)), Image.BILINEAR)
    try:
        img.convert("RGB").save(path)
    except OSError:
        # open in an image viewer (Windows keeps it locked): next to it, and carry on
        alt = Path(path).with_name(Path(path).stem + "-nuevo.png")
        img.convert("RGB").save(alt)
        print(f"  {Path(path).name} is open in another program: saved as {alt.name}", flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["columns", "halves", "sheet", "all"])
    ap.add_argument("count", nargs="?", help="columns: 3-8 or all (one folder per count); halves: mobs a side")
    ap.add_argument("--out", default=str(HERE.parent / "vs-preview"),
                    help="output folder (default: vs-preview in the repo root, ignored by git)")
    ap.add_argument("--cache")
    ap.add_argument("--folder", help="a folder holding the pictures themselves (e.g. /mobbattle test vsframes output)")
    ap.add_argument("--only")
    ap.add_argument("--mobs", help="exact model ids, comma separated, in this order (halves: the first half goes left)")
    ap.add_argument("--scale", type=float, default=0.5)
    ap.add_argument("--labels", action="store_true")
    ap.add_argument("--clip", action="store_true", help="halves: cut each side at the slanted split")
    ap.add_argument("--size-power", type=float, default=GAME_SIZE_POWER,
                    help="halves: size by height, (height / tallest) ^ this (game: 0.5; 0 = all the same size)")
    ap.add_argument("--tiers", help="halves: the slot sizes to try, e.g. 1,0.85,0.72,0.6 (game: 1,0.75,0.55,0.38)")
    ap.add_argument("--rule", choices=["scale", "game"], default="scale",
                    help="halves: scale = the game's sizes (one scale for the whole screen, VsScreen since 2026-10-18); "
                         "game = the sizes before (per-slot sizes), to compare")
    ap.add_argument("--giant", type=float, default=GIANT, help="--rule scale: reference height at most this x the median body")
    ap.add_argument("--floor", type=float, default=FLOOR, help="--rule scale: no body smaller than this share of the reference")
    ap.add_argument("--column-size", type=int, help="columns: picture size in px of 1080 (game: 1100 up to 4 columns, else 1000)")
    ap.add_argument("--small", type=float, default=GAME_SMALL_MOB,
                    help="columns: mobs under this many blocks are drawn smaller (game: 0.8)")
    ap.add_argument("--assets", default=os.path.expandvars(r"%APPDATA%\Hytale\install\release\package\game\latest\Assets.zip"),
                    help="the game's Assets.zip (for the mobs' real heights)")
    ap.add_argument("--mods", default=os.path.expandvars(r"%APPDATA%\Hytale\UserData\Mods"),
                    help="the mods folder (for the mobs' real heights)")
    a = ap.parse_args()
    folder = Path(a.folder) if a.folder else vs_folder(cache_root(a.cache))
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    print("pictures:", folder)
    print("writing to:", out.resolve())
    print("reading the mobs' heights...", flush=True)
    heights = model_heights(a.assets, a.mods)
    print(f"  {len(heights)} models with a height", flush=True)

    if a.mode == "all":
        # every variant, each in its folder: halves of 3 (duel), 6 and 9 (medium), 12 and 24 (crowd), all cut at the
        # split, and team columns of 3 to 8
        import subprocess
        me = [sys.executable, str(Path(__file__).resolve())]
        common = ["--scale", str(a.scale), "--rule", a.rule, "--giant", str(a.giant), "--floor", str(a.floor)] + (["--folder", a.folder] if a.folder else []) + (["--only", a.only] if a.only else [])
        for k in (3, 6, 9, 12, 24):
            subprocess.run(me + ["halves", str(k), "--clip", "--labels", "--out", str(out / f"mitades-{k}")] + common, check=True)
        subprocess.run(me + ["columns", "all", "--out", str(out / "columnas")] + common, check=True)
        print("all variants ->", out.resolve())
        return
    if a.mode == "columns":
        ids = models(folder, "_sq.png", a.only)
        counts = range(3, 9) if a.count == "all" else [max(3, min(8, int(a.count or 8)))]
        for n in counts:
            dest = out / f"columns{n}" if a.count == "all" else out
            dest.mkdir(parents=True, exist_ok=True)
            bar = Progress((len(ids) + n - 1) // n, f"{n} columns")
            for s in range(0, len(ids), n):
                p = dest / f"columns{n}_{s // n + 1:03d}.png"
                save(column_screen(folder, ids[s:s + n], n, True, heights, a.small, a.column_size), p, a.scale)
                bar.step(s // n + 1)
            print(f"{(len(ids) + n - 1) // n} screens of {n} columns, {len(ids)} mobs -> {dest}")
    elif a.mode == "sheet":
        n = max(3, min(8, int(a.count or 8)))
        ids = models(folder, "_sq.png", a.only)
        per, cw = 64, round(1920 / n * 0.3)
        for s in range(0, len(ids), per):
            chunk = ids[s:s + per]
            sheet = Image.new("RGB", (16 * (cw + 4), 4 * (324 + 16)), (20, 20, 24))
            dr = ImageDraw.Draw(sheet)
            for i, m in enumerate(chunk):
                r, c = divmod(i, 16)
                one = column_screen(folder, [m], n, False).crop((0, 0, round(1920 / n), 1080))
                one = one.resize((cw, 324), Image.BILINEAR)
                sheet.paste(one.convert("RGB"), (c * (cw + 4), r * 340))
                dr.text((c * (cw + 4) + 1, r * 340 + 325), m[:13], fill=(230, 230, 230), font=font(11))
            p = out / f"sheet{n}_{s // per + 1:02d}.png"
            sheet.save(p)
        print(f"{(len(ids) + per - 1) // per} sheets, {len(ids)} mobs -> {out}")
    else:
        k = max(1, min(ui.GROUND_COUNT, int(a.count or 9)))
        ids = models(folder, "_v0_L.png", a.only)
        ids = [m for m in ids if (folder / f"{m}_v0_R.png").exists()]
        if a.mobs:
            want = [m.strip() for m in a.mobs.split(",") if m.strip()]
            ids = [m for m in want if m in ids]
            k = max(1, (len(ids) + 1) // 2) if not a.count else k
        screens = 0
        bar = Progress((len(ids) + 2 * k - 1) // (2 * k), f"{k} a side")
        for s in range(0, len(ids), 2 * k):
            chunk = ids[s:s + 2 * k]
            p = out / f"halves{k}_{screens + 1:03d}.png"
            sizes = [float(x) for x in a.tiers.split(",")] if a.tiers else None
            save(halves_screen(folder, chunk[:k], chunk[k:], a.labels, a.clip, heights, a.size_power, sizes,
                               a.rule, a.giant, a.floor), p, a.scale)
            screens += 1
            bar.step(screens)
        print(f"{screens} screens, {k} mobs a side, {len(ids)} mobs -> {out}")


if __name__ == "__main__":
    main()
