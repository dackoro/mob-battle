"""Writes Common/UI/Custom/MobBattle/VsScreen.ui (VS screen layout, 2026-10-16). Run: python scripts/gen-vs-ui.py

Fighters hang on each half's inner edge (the slant), placed for a 1920x1080 screen. Each slot has three sizes (TIERS),
one shown per fighter by its real height against the tallest fighter on screen (playtest: a skeleton came out as big as
the Gobliterator): VsScreen.tierOf. The slant and backgrounds come from scripts/gen-vs-assets.py.
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "Common/UI/Custom/MobBattle/VsScreen.ui"
BOX = 1920 * 0.58          # a half's box at rest (FlexWeight 580 of 1000)
# VsScreen.tierOf: picture size per tier, by (height / tallest) ^ 0.5 (playtest, 2026-10-17 after offline previews: with a
# 60 % floor a chicken or a pig stood as big as a bear; 100/85/72/60 % until then)
TIERS = [1.0, 0.75, 0.55, 0.38]
# slot: (center x in the left half's screen coords, feet y, full size); index = strength order
NAMED = [(500, 1080, 880), (250, 1080, 720), (800, 1080, 640)]   # all on the bottom edge (playtest: no gap under the first)
NAMED_DRAW = [0, 1, 2]
# a crowd (playtest, 2026-10-17: a jumbled group filling the half, not a perfect triangle with empty corners): rows front
# to back, each clearly smaller and higher than the one in front, each spread from the screen edge to the slanted
# split (the narrower of the two halves, since the right half uses the same slots mirrored), every mob nudged and
# resized by a fixed pseudo-random amount (the same layout every time). (feet y, size, how many) per row; 24 in all.
# Then (playtest, 2026-10-17: too much empty space, they should interleave; the back rows too small for mobs that are not
# that big): six rows close together, each staggered half a place from the one in front, so the back ones show in
# the gaps, and a soft perspective (the last row 80 % of the first).
# Then (playtest, 2026-10-17: a tall Kweebec Rootbreaker at the back still looked small and covered): an even softer
# perspective (the last row 88 % of the first) and the back rows a little higher, so the front rows cover them less.
CROWD_ROWS = [(1080, 470, 5), (1006, 460, 4), (936, 450, 5), (871, 440, 4), (811, 422, 3), (756, 414, 3)]
CROWD_EDGE = 10           # room left at the screen edge
CROWD_SPLIT_ROOM = 40     # room left before the split (a mob there is cut by the half's mask)
SPLIT_TOP, SPLIT_BOTTOM = 1100, 820   # gen-vs-assets.py TOP / BOTTOM: the split's screen x at the top and the bottom
# 4-9 fighters (VsScreen.MEDIUM; playtest 2026-10-16: small crowds looked tiny next to a big mob): big, spread over the
# half in two loose rows; index = fill order (strongest at the front center), drawn back row first
# (playtest, 2026-10-16: as big as the team columns; overlapping and cut at the edges is fine)
# rows well apart in height (playtest, 2026-10-16: they covered each other's faces); VsScreen puts the tallest mobs in the
# back slots (BACK_FIRST below), so their heads show over the ones in front
MEDIUM = [(450, 1080, 760), (200, 1080, 680), (720, 1080, 680), (330, 900, 600), (610, 900, 600),
          (80, 960, 560), (850, 960, 540), (480, 730, 520), (230, 720, 490)]
MEDIUM_DRAW = sorted(range(len(MEDIUM)), key=lambda i: (MEDIUM[i][1], -abs(MEDIUM[i][0] - 450)))
SKY_SLOTS = [(250, 470, 230), (640, 440, 220), (420, 330, 190), (780, 320, 180)]


def crowd_slots():
    """Slots in fill order (the middle of the front row first, then outwards and backwards, so a small crowd is a
    group in front) and the drawing order (back rows first, by feet height; in a row the middle one last, on top)."""
    import random
    rnd = random.Random(3)

    def inner(y):
        # the left half reaches the split; the right half (same slots mirrored) reaches 1920 - split: the narrower one
        split = SPLIT_TOP + (SPLIT_BOTTOM - SPLIT_TOP) * y / 1080
        return min(split, 1920 - split)

    cells = []
    for r, (feet, size, count) in enumerate(CROWD_ROWS):
        x0 = CROWD_EDGE + size * 0.25
        x1 = inner(feet - size * 0.55) - CROWD_SPLIT_ROOM - size * 0.25
        mid = (x0 + x1) / 2
        if r % 2:
            # staggered: half a place in from both ends, so its mobs stand in the front row's gaps
            gap = (x1 - x0) / max(1, count)
            xs = [x0 + gap / 2 + i * gap for i in range(count)]
        else:
            gap = (x1 - x0) / max(1, count - 1)
            xs = [x0 + i * gap for i in range(count)] if count > 1 else [mid]
        for x in xs:
            jx, jy, js = rnd.uniform(-0.12, 0.12) * size, rnd.uniform(-0.05, 0.05) * size, rnd.uniform(0.93, 1.07)
            score = abs(x - mid) / max(1.0, gap) + r * 0.9
            cells.append((score, r, abs(x - mid), (round(x + jx), min(1080, round(feet + jy)), round(size * js))))
    cells.sort(key=lambda c: (c[0], c[1]))
    slots = [c[3] for c in cells]
    draw = sorted(range(len(cells)), key=lambda i: (cells[i][3][1], -cells[i][2]))
    sky = list(range(len(slots), len(slots) + len(SKY_SLOTS)))
    slots += SKY_SLOTS
    return slots, sky + draw


CROWD, CROWD_DRAW = crowd_slots()
GROUND_COUNT = sum(c for _, _, c in CROWD_ROWS)   # VsScreen.GROUND: 0 .. GROUND_COUNT-1; SKY: the rest
# the battle kind's title right under the VS letters (gen-vs-assets.py TITLE_CANVAS 1900x240, same aspect)
TITLE_TOP = 706
TITLE_BOX = (760, 96)

# the VS logo (VsLogo.png, with its flash), 600 wide
try:
    from PIL import Image
    _w, _h = Image.open(Path(__file__).resolve().parent.parent / "Common/Icons/MobBattle/Vs/VsLogo.png").size
except Exception:
    _w, _h = 1225, 1021
VS_LOGO = (round(600), round(600 * _h / _w))


def slot(side, name, cx, feet, size, ind):
    align = "Right" if side == "L" else "Left"
    out = []
    # the soft halo behind the fighter (Glow_L|R.png, set by the code), centered on its body
    g = round(size * 0.95)
    gy = feet - size * 0.45
    out.append(f"""{ind}Group {{
{ind}  Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
{ind}  LayoutMode: Bottom;
{ind}  Group {{
{ind}    Anchor: (Height: {g}, Bottom: {round(1080 - gy - g / 2)});
{ind}    LayoutMode: {align};
{ind}    AssetImage #{name}G {{ Anchor: (Width: {g}, Height: {g}, {align}: {round(BOX - (cx + g / 2))}); Visible: false; }}
{ind}  }}
{ind}}}
""")
    for t, f in enumerate(TIERS):
        s = round(size * f)
        edge = round(BOX - (cx + s / 2))
        out.append(f"""{ind}Group {{
{ind}  Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
{ind}  LayoutMode: Bottom;
{ind}  Group {{
{ind}    Anchor: (Height: {s}, Bottom: {1080 - feet});
{ind}    LayoutMode: {align};
{ind}    AssetImage #{name}T{t} {{ Anchor: (Width: {s}, Height: {s}, {align}: {edge}); Visible: false; }}
{ind}  }}
{ind}}}
""")
    return "".join(out)


def bar(side, ind):
    if side == "L":
        return f"""{ind}// name bar texts (the bar itself is baked into the background): small colored team label, the names in big white letters
{ind}Group {{
{ind}  Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
{ind}  LayoutMode: Top;
{ind}  Group #LBarFill {{
{ind}    Anchor: (Height: 118);
{ind}    LayoutMode: Top;
{ind}    Padding: (Left: 40, Top: 16);
{ind}    Label #LTeam {{
{ind}      Anchor: (Height: 36, Width: 740);
{ind}      Style: (FontSize: 28, FontName: "Secondary", TextColor: #ff6e6e, RenderBold: true, RenderUppercase: true,
{ind}              HorizontalAlignment: Start, VerticalAlignment: Center);
{ind}    }}
{ind}    Label #LNames {{
{ind}      Anchor: (Height: 64, Width: 740);
{ind}      Style: (FontSize: 46, FontName: "Secondary", TextColor: #ffffff, RenderBold: true, RenderUppercase: true,
{ind}              ShrinkTextToFit: true, MinShrinkTextToFitFontSize: 14, HorizontalAlignment: Start, VerticalAlignment: Center);
{ind}    }}
{ind}  }}
{ind}}}
"""
    return f"""{ind}Group {{
{ind}  Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
{ind}  LayoutMode: Top;
{ind}  Group #RBarFill {{
{ind}    Anchor: (Height: 118);
{ind}    LayoutMode: Top;
{ind}    Padding: (Right: 40, Top: 16);
{ind}    Group {{
{ind}      Anchor: (Height: 36);
{ind}      LayoutMode: Right;
{ind}      Label #RTeam {{
{ind}        Anchor: (Width: 960);
{ind}        Style: (FontSize: 28, FontName: "Secondary", TextColor: #82beff, RenderBold: true, RenderUppercase: true,
{ind}                HorizontalAlignment: End, VerticalAlignment: Center);
{ind}      }}
{ind}    }}
{ind}    Group {{
{ind}      Anchor: (Height: 64);
{ind}      LayoutMode: Right;
{ind}      Label #RNames {{
{ind}        Anchor: (Width: 960);
{ind}        Style: (FontSize: 46, FontName: "Secondary", TextColor: #ffffff, RenderBold: true, RenderUppercase: true,
{ind}                ShrinkTextToFit: true, MinShrinkTextToFitFontSize: 14, HorizontalAlignment: End, VerticalAlignment: Center);
{ind}      }}
{ind}    }}
{ind}  }}
{ind}}}
"""


def half(S):
    ind = "        "
    glows = "".join(f"{ind}AssetImage #{S}G{i} {{ Visible: false; }}\n" for i in range(GENERIC_SLOTS))
    pics = "".join(f"{ind}AssetImage #{S}P{i} {{ Visible: false; }}\n" for i in range(GENERIC_SLOTS))
    side_mode = "Left" if S == "L" else "Right"
    return f"""    Group #{S}Half {{
      FlexWeight: 1;
      // each half cut at the slant (playtest, 2026-10-17: as Smash; overlapping fighters crossed into the other
      // half): white where the half shows, stretched to the box like its background (gen-vs-assets.py --masks)
      {HALF_MASK_LINE.format(S=S)}

      // the half's background (Bg_<Color>_{S}.png: glow, streaks, particles, the slanted edge), stretched to the box
      AssetImage #{S}Bg {{ Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0); }}

      // the fighters (2026-10-17, recordings: 40 slots x 4 sizes x nested groups were ~1300 elements, and every slide
      // step re-laid them all out, so the client drew no frame for the whole slide): one set of generic slots, a halo
      // and a picture each, all halos first; VsScreen places each one (Anchor: position and size from the layout in
      // vs-slots.json, made by this script) in drawing order, so slot 0 is the furthest back.
      Group #{S}Slots {{
        Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
{glows}{pics}      }}

{bar(S, "      ")}
      // "+N" for the fighters not shown: a badge low by the screen edge (MoreBadge.png + the number)
      Group {{
        Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
        LayoutMode: Bottom;
        Group {{
          Anchor: (Height: 84, Bottom: 34);
          LayoutMode: {side_mode};
          Group #{S}MoreBox {{
            Anchor: (Width: 236, Height: 84, {side_mode}: 40);
            Visible: false;
            AssetImage #{S}MoreBadge {{ Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0); }}
            Label #{S}More {{
              Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
              Style: (FontSize: 46, FontName: "Secondary", TextColor: #ffffff, RenderBold: true,
                      HorizontalAlignment: Center, VerticalAlignment: Center);
            }}
          }}
        }}
      }}
    }}
"""


# 3+ teams (playtest, 2026-10-16: like Smash's 4-8 player screen): one column per team, 3 to 8 columns, each its team's
# color (set by the code) under ColumnOverlay.png, a header (small colored "P1 · TEAM", the representative's name) and
# the representative big at the bottom, at one of the four sizes. Panels start hidden and are revealed one by one.
COLUMN_COUNTS = range(3, 9)


def column_size(n):
    """Picture size in an n-column screen: bigger than the column, Smash-like; the column cuts it (COLUMN_CLIP)."""
    return 1100 if n <= 4 else 1000


# the picture stands on the column's bottom edge (playtest, 2026-10-16: centered on the head with the feet at the bottom;
# VsScreen.columnFrame puts the feet on the picture's bottom edge and pushes a tall mob down until its head is clear of
# the header, fliers float at a fixed height). It is one face-framed square per mob, wider than the column: the column's
# #..Clip group has a mask (MaskTexturePath, white = keep), and a Group with a mask hides whatever its children draw
# outside it (playtest test 2026-10-17). It replaced six pre-cut widths per mob (VsScreen.COLUMN_CROP, ~38 MB per client);
# a scrolling container did NOT clip (2026-10-16).
CLIP_MASK = "Masks/Clip.png"   # relative to the .ui: Common/UI/Custom/MobBattle/Masks/Clip.png


def columns(counts=COLUMN_COUNTS):
    out = []
    for n in counts:
        size = column_size(n)
        name_font = max(24, min(44, round(1920 / n / 11)))
        panels = []
        for i in range(n):
            p = f"C{n}P{i}"   # ids are letters and digits only (an underscore broke the parse)
            panels.append(f"""      Group #{p} {{
        FlexWeight: 1;
        // the column rises from the bottom of the screen (playtest, 2026-10-16: they popped in): #..Pad shrinks and #..Body
        // grows (FlexWeights set by VsScreen, never 0: a 0 takes its content's size); the content hangs from the
        // body's top, so it moves up with it (below the screen = unseen)
        // Top -6 (playtest, 2026-10-18: a thin see-through line on top of the risen columns): #..Pad never gets below
        // weight 1, so it kept ~1 px over the body; now that px sits above the screen and the content starts 5 down
        // Bottom -6 and a 1086 content (playtest, 2026-10-29: a ~1 px see-through line under the columns): the body and
        // the picture's feet run 6 px past the screen's bottom edge
        Group #{p}In {{
          Anchor: (Left: 0, Right: 0, Top: -6, Bottom: -6);
          Visible: false;
          LayoutMode: Top;
          Group #{p}Pad {{ FlexWeight: 1000; }}
          Group #{p}Body {{
            FlexWeight: 1;
            Background: #000000;
            Group {{
              Anchor: (Left: 0, Right: 0, Top: 5, Height: 1086);
              // the picture: the mob's face-framed square, standing on the bottom of the 1080 column content (a picture
              // taller than 1080 is cut at the bottom), centered across and wider than the column; the mask cuts it
              // to the column (overflow hidden)
              // the picture: the mob's square already cut to this column's width (VsScreen.makeCut), as wide as the column and
              // {size} tall, standing on the bottom. No mask: a masked group hid its picture a moment after it showed
              // (playtest, 2026-10-29: with or without centering, with the pictures sent at join too; without the mask it stayed)
              Group #{p}Clip {{
                Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
                AssetImage #{p}Pic {{ Anchor: (Left: 0, Right: 0, Top: {max(0, 1086 - size)}, Height: {size}); Visible: false; }}
              }}
              // a mob with no column picture yet (its square picture or icon, playtest 2026-10-16: an icon stretched to the
              // column's height): square, as wide as the column, standing on its bottom
              Group {{
                Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
                LayoutMode: Top;
                Group {{ Anchor: (Height: {1086 - round(1920 / n)}); }}
                Group {{
                  Anchor: (Height: {round(1920 / n)});
                  LayoutMode: Center;
                  AssetImage #{p}Sq {{ Anchor: (Width: {round(1920 / n)}, Height: {round(1920 / n)}); Visible: false; }}
                }}
              }}
              AssetImage #{p}Over {{ Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0); }}
              Group {{
                Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
                LayoutMode: Top;
                Group {{
                  Anchor: (Height: 130);
                  LayoutMode: Top;
                  Padding: (Left: 16, Top: 14, Right: 8);
                  Label #{p}Tag {{
                    Anchor: (Height: 32);
                    Style: (FontSize: 24, FontName: "Secondary", TextColor: #ffffff, RenderBold: true, RenderUppercase: true,
                            HorizontalAlignment: Start, VerticalAlignment: Center);
                  }}
                  Label #{p}Name {{
                    Anchor: (Height: 60);
                    Style: (FontSize: {name_font}, FontName: "Secondary", TextColor: #ffffff, RenderBold: true, RenderUppercase: true,
                            HorizontalAlignment: Start, VerticalAlignment: Center);
                  }}
                }}
              }}
            }}
          }}
        }}
      }}
""")
        # Right -8: the client floors each FlexWeight width, so n columns left up to n-1 px of the world showing at the
        # right edge (playtest screenshots 2026-10-17, 6 and 7 teams); the last column now runs a few px past the screen
        # (negative anchors as vanilla Common.ui)
        out.append(f"""    Group #Cols{n} {{
      Anchor: (Left: 0, Right: -8, Top: 0, Bottom: 0);
      LayoutMode: Left;
      Visible: false;
{"".join(panels)}    }}
""")
    return f"""  // 3+ teams: one column per team (VsScreen columns)
  Group #Cols {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    Visible: false;
{"".join(out)}  }}

"""


def back_first():
    """Slot indices in drawing order (first = furthest back, last = on top), for VsScreen.MEDIUM_BACK_FIRST /
    GROUND_BACK_FIRST: the tallest fighters take the first ones, the smallest end up in front (playtest, 2026-10-17: big
    mobs behind, small ones in front; sorting by row only put the tallest of a row on top of its neighbours)."""
    medium = list(MEDIUM_DRAW)
    ground = [i for i in CROWD_DRAW if i < GROUND_COUNT]
    return medium, ground


# The column screens moved to VsCols<n>.ui (2026-10-16), but a server already running the older code still appends
# VsScreen.ui and sets #Cols/#ColTitle in it: with dev mode the .ui goes live at once, and a missing id disconnects the
# player ("Failed to apply CustomUI HUD commands", playtest 2026-10-16). Keep them here until every server runs the new
# code, then set this to False and run the script again.
LEGACY_COLUMNS_IN_MAIN = False


def legacy_columns():
    if not LEGACY_COLUMNS_IN_MAIN:
        return ""
    return columns() + f"""  Group #ColTitle {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    LayoutMode: Top;
    Visible: false;
    Group {{
      Anchor: (Height: {round(TITLE_BOX[1] * 0.8)}, Top: 136);
      LayoutMode: Center;
      AssetImage #ColKindTitle {{ Anchor: (Width: {round(TITLE_BOX[0] * 0.8)}, Height: {round(TITLE_BOX[1] * 0.8)}); }}
    }}
  }}

"""


# generic fighter slots per half (VsScreen.GENERIC_SLOTS): the largest layout, the crowd (24 ground + 4 sky)
GENERIC_SLOTS = 28
# each half cut at the slant with a mask (on) or not (off); A/B test of the slide freeze (2026-10-17)
import os as _os
HALF_MASK_LINE = 'MaskTexturePath: "Masks/Half{S}.png";' if _os.environ.get("VS_HALF_MASK", "on") == "on" else '// (no mask)'
PRELOAD = 64   # VsScreen.PRELOAD: more distinct pictures than two full crowds (2 x 28)


def preload():
    return "".join(f"    AssetImage #Pre{i} {{ Anchor: (Left: 0, Top: 0, Width: 384, Height: 384); Visible: false; }}" + chr(10) for i in range(PRELOAD))


def write_slots():
    """src/main/resources/dev/dackoro/mobbattle/vs-slots.json: every layout's slots (center x in the left half, feet y,
    full size; 1920x1080 screen) and their drawing order (furthest back first), the half's box width and the sizes."""
    import json
    data = {
        "box": BOX,
        "tiers": TIERS,
        "named": [list(t) for t in NAMED], "namedDraw": list(NAMED_DRAW),
        "medium": [list(t) for t in MEDIUM], "mediumDraw": list(MEDIUM_DRAW),
        "crowd": [list(t) for t in CROWD], "crowdDraw": list(CROWD_DRAW), "groundCount": GROUND_COUNT,
    }
    out = Path(__file__).resolve().parent.parent / "src/main/resources/dev/dackoro/mobbattle/vs-slots.json"
    out.write_text(json.dumps(data, indent=1), encoding="utf-8", newline="\n")
    print(out)


def main():
    ui = f"""// Mob Battle VS screen (VsScreen.java; vsScreen in the countdown, /mobbattle test vs). GENERATED by
// scripts/gen-vs-ui.py: edit that and run it again. Look of the VS card made for the clip
// (art-source/Renders/VS/vs_card.py, playtest 2026-10-16): two halves split on a slant, each in its team color with light
// streaks and floating particles, a dark name bar on top, the fighters big and overlapping, a big VS.
// Layers on top of each other (no LayoutMode = overlay): each layer splits the width by FlexWeight between its half
// (0 -> 580 thousandths while sliding in, so the halves overlap where the slant is) and a transparent rest. Fighters
// and labels hang on each half's inner edge, so they slide in with it. Pictures, colors, texts and which size of
// each slot shows are set by the code.
Group #Vs {{
  Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);

  // the screen's pictures, 1 px each, behind everything (VsScreen sets them): the client loads a picture the first
  // time it draws it, and a royale's ~48 at once froze it 0.8 s right as the halves started to slide, so the crowds
  // popped in instead of sliding (recording 2026-10-17). Drawn here from the start, they load while the screen waits.
  // At their real size, under a fully transparent mask (Masks/Hidden.png): 1 px copies were not enough, the first
  // real-size draw still froze the client 0.7 s at the slide with 24 mobs (recording 2026-10-17).
  Group #Preload {{
    Anchor: (Left: 0, Top: 0, Width: 384, Height: 384);
    MaskTexturePath: "Masks/Hidden.png";
{preload()}  }}

  // "Preparing the battle..." while the client loads the screen (2026-10-18, playtest: during the wait the player could
  // think nothing happened); VsScreen hides it when the screen comes in
  Group #Loading {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    LayoutMode: Bottom;
    Group {{
      Anchor: (Height: 56, Bottom: 170);
      LayoutMode: Center;
      Group {{
        Anchor: (Width: 520, Height: 56);
        Background: #0b0f18(0.75);
        Label #LoadingText {{
          Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
          Text: %server.mobbattle.hud.vs.loading;
          Style: (FontSize: 26, FontName: "Secondary", TextColor: #ffd23e, RenderBold: true, RenderUppercase: true,
                  HorizontalAlignment: Center, VerticalAlignment: Center);
        }}
      }}
    }}
  }}

  // everything you see, hidden while the client loads the pictures through #Preload (2026-10-17, recording: each
  // half's mask hides its pictures until it slides, so they loaded at the first slide frame and the client froze
  // through the slide: the crowd popped in). VsScreen shows it when the slide starts; you see the world until then.
  Group #Show {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    Visible: false;

  // a plain battle royale: one background for the whole screen (Bg_Royale.png, the halves' own ones hidden; playtest
  // 2026-10-16: no sides, so no split), the two crowds slide in over it
  AssetImage #RoyaleBg {{ Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0); Visible: false; }}

  Group #LeftLayer {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    LayoutMode: Left;

{half("L")}
    Group #LRest {{ FlexWeight: 1000; }}
  }}

  Group #RightLayer {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    LayoutMode: Left;

    Group #RRest {{ FlexWeight: 1000; }}

{half("R")}  }}

{legacy_columns()}  // a plain battle royale: one centered title in the top bar instead of each half's names (playtest, 2026-10-17: the
  // two names read like two teams); shown with the clash, as the names
  Group #RoyaleHead {{
    Anchor: (Left: 0, Right: 0, Top: 0, Height: 118);
    LayoutMode: Center;
    Visible: false;
    Label #RoyaleHeadText {{
      Anchor: (Width: 1600, Height: 118);
      Style: (FontSize: 56, FontName: "Secondary", TextColor: #ffffff, RenderBold: true, RenderUppercase: true,
              HorizontalAlignment: Center, VerticalAlignment: Center);
    }}
  }}

  Group #Flash {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    Background: #ffffff(0.7);
    Visible: false;
  }}

  // the battle kind in the VS's own style, right under it (Kind_<kind>_<lang>.png, set by the code; playtest 2026-10-16)
  Group #Title {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    LayoutMode: Top;
    Visible: false;
    Group {{
      Anchor: (Height: {TITLE_BOX[1]}, Top: {TITLE_TOP});
      LayoutMode: Center;
      AssetImage #KindTitle {{ Anchor: (Width: {TITLE_BOX[0]}, Height: {TITLE_BOX[1]}); }}
    }}
  }}

  Group #Center {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    LayoutMode: CenterMiddle;
    Visible: false;

    AssetImage #VsLogo {{ Anchor: (Width: {VS_LOGO[0]}, Height: {VS_LOGO[1]}, Top: 40); }}
  }}
  }}
}}
"""
    OUT.write_text(ui, encoding="utf-8", newline="\n")
    print(OUT, len(ui.splitlines()), "lines")
    # a plain battle royale (one background, no sides): the same screen with the halves not cut at the slant, so the
    # fighters cross the middle (playtest, 2026-10-18: the cut looked wrong with no seam to cut at). VsScreen picks it.
    royale = ui
    for S in ("L", "R"):
        royale = royale.replace(f'MaskTexturePath: "Masks/Half{S}.png";', "// not cut at the slant: a plain battle royale has no seam")
    royale_out = OUT.with_name("VsRoyale.ui")
    royale_out.write_text(royale, encoding="utf-8", newline="\n")
    print(royale_out, len(royale.splitlines()), "lines")
    # 3+ teams: one small document per column count (VsScreen appends only the one it shows; playtest 2026-10-16: with
    # everything in one file the client built ~570 pictures' worth of elements for every VS and froze)
    for n in COLUMN_COUNTS:
        cols = f"""// Mob Battle VS screen, {n} team columns (VsScreen.java). GENERATED by scripts/gen-vs-ui.py: edit that and run it
// again. The halves screen is VsScreen.ui; only one of them is appended.
Group #Vs {{
  Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);

{columns([n])}
  // "Preparing the battle..." while the client loads the screen (2026-10-18, playtest: during the wait the player could
  // think nothing happened); VsScreen hides it when the screen comes in
  Group #Loading {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    LayoutMode: Bottom;
    Group {{
      Anchor: (Height: 56, Bottom: 170);
      LayoutMode: Center;
      Group {{
        Anchor: (Width: 520, Height: 56);
        Background: #0b0f18(0.75);
        Label #LoadingText {{
          Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
          Text: %server.mobbattle.hud.vs.loading;
          Style: (FontSize: 26, FontName: "Secondary", TextColor: #ffd23e, RenderBold: true, RenderUppercase: true,
                  HorizontalAlignment: Center, VerticalAlignment: Center);
        }}
      }}
    }}
  }}

  Group #Flash {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    Background: #ffffff(0.7);
    Visible: false;
  }}

  // the kind's title right under the column headers, over the space above the faces (playtest, 2026-10-16: in the middle
  // it covered them)
  Group #ColTitle {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    LayoutMode: Top;
    Visible: false;
    Group {{
      Anchor: (Height: {round(TITLE_BOX[1] * 0.8)}, Top: 136);
      LayoutMode: Center;
      AssetImage #ColKindTitle {{ Anchor: (Width: {round(TITLE_BOX[0] * 0.8)}, Height: {round(TITLE_BOX[1] * 0.8)}); }}
    }}
  }}
}}
"""
        out = OUT.with_name(f"VsCols{n}.ui")
        out.write_text(cols, encoding="utf-8", newline="\n")
        print(out, len(cols.splitlines()), "lines")


if __name__ == "__main__":
    import sys
    write_slots()
    if "--depth" in sys.argv:
        for order in back_first():
            print(", ".join(map(str, order)))
    else:
        main()
