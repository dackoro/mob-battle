"""Writes Common/UI/Custom/MobBattle/VsScreen.ui (VS screen layout, 2026-10-16). Run: python scripts/gen-vs-ui.py

Fighters hang on each half's inner edge (the slant), placed for a 1920x1080 screen. Each slot has three sizes (TIERS),
one shown per fighter by its real height against the tallest fighter on screen (user: a skeleton came out as big as
the Gobliterator): VsScreen.tierOf. The slant and backgrounds come from scripts/gen-vs-assets.py.
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "Common/UI/Custom/MobBattle/VsScreen.ui"
BOX = 1920 * 0.58          # a half's box at rest (FlexWeight 580 of 1000)
TIERS = [1.0, 0.85, 0.72, 0.6]  # VsScreen.tierOf: picture size per tier (height ratio ^ 0.35, as the reference card)
# slot: (center x in the left half's screen coords, feet y, full size); index = strength order
NAMED = [(500, 1080, 880), (250, 1080, 720), (800, 1080, 640)]   # all on the bottom edge (user: no gap under the first)
NAMED_DRAW = [0, 1, 2]
# a crowd (user, 2026-10-16: grow like a huddle from the ground, nobody floating; the back rows tiny, far away): five
# rows in perspective, each clearly smaller and well higher than the one in front (a high horizon, so a big crowd
# fills the half up to the middle; user: too much empty space at the top), overlapping it. Index = fill
# order (front row first, center first); 0-23 for everyone, 24-27 high in the air for flying mobs only (VsScreen.SKY).
# (feet y, size, how many) per row, front to back. A mound (user, 2026-10-16: flat rows looked like a line-up, and the
# scales did not match): rows narrower as they go back, so the crowd peaks in the middle, each row about 10% smaller
# and well higher than the one in front, neighbours about half a picture apart, a small fixed jitter so it is no grid.
# x1.3 (user, 2026-10-16: big like the columns; they may overlap and be cut), neighbours closer so it is no wider
ROWS = [(1080, 470, 5), (975, 420, 5), (875, 385, 4), (780, 345, 4), (690, 310, 3), (610, 280, 2), (535, 255, 1)]
ROW_CENTER = 450          # the half's middle (left half, screen x)
ROW_STEP = 0.43           # neighbours this many pictures apart
# a natural crowd, not an exact triangle (user, 2026-10-16): each row shifted sideways a little, and every mob nudged
# (x, y) and resized by a fixed pseudo-random amount (the same layout every time)
ROW_SHIFT = [0, -34, 26, -18, 38, -26, 14]
JITTER = [(0, 0, 1.0), (-34, 14, 0.94), (28, -16, 1.06), (-16, 9, 0.97), (40, -20, 1.04), (-28, -10, 0.92),
          (18, 20, 1.08), (-40, 6, 0.96), (30, 12, 1.03), (-10, -14, 0.95), (24, 4, 1.07), (-22, 18, 0.98),
          (36, -6, 0.93), (-30, -18, 1.05), (12, 16, 0.99)]
# 4-9 fighters (VsScreen.MEDIUM; user 2026-10-16: small crowds looked tiny next to a big mob): big, spread over the
# half in two loose rows; index = fill order (strongest at the front center), drawn back row first
# (user, 2026-10-16: as big as the team columns; overlapping and cut at the edges is fine)
# rows well apart in height (user, 2026-10-16: they covered each other's faces); VsScreen puts the tallest mobs in the
# back slots (BACK_FIRST below), so their heads show over the ones in front
MEDIUM = [(450, 1080, 760), (200, 1080, 680), (720, 1080, 680), (330, 900, 600), (610, 900, 600),
          (80, 960, 560), (850, 960, 540), (480, 730, 520), (230, 720, 490)]
MEDIUM_DRAW = sorted(range(len(MEDIUM)), key=lambda i: (MEDIUM[i][1], -abs(MEDIUM[i][0] - 450)))
SKY_SLOTS = [(250, 470, 230), (640, 440, 220), (420, 330, 190), (780, 320, 180)]


def crowd_slots():
    """Slots in fill order (the middle column fills up the rows before the edges, so even a small crowd is a mound)
    and the drawing order (back rows first, by feet height; in a row the middle one last, on top)."""
    cells = []
    k = 0
    for r, (feet, size, count) in enumerate(ROWS):
        step = size * ROW_STEP
        for i in range(count):
            dx = (i - (count - 1) / 2) * step
            jx, jy, js = JITTER[k % len(JITTER)] if r or i else (0, 0, 1.0)
            k += 1
            score = abs(dx) / step + r * 0.75
            x = ROW_CENTER + ROW_SHIFT[r % len(ROW_SHIFT)] + dx + jx
            cells.append((score, r, abs(dx), (round(x), min(1080, feet + jy), round(size * js))))
    cells.sort(key=lambda c: (c[0], c[1]))
    slots = [c[3] for c in cells]
    draw = sorted(range(len(cells)), key=lambda i: (cells[i][3][1], -cells[i][2]))
    sky = list(range(len(slots), len(slots) + len(SKY_SLOTS)))
    slots += SKY_SLOTS
    return slots, sky + draw


CROWD, CROWD_DRAW = crowd_slots()
GROUND_COUNT = sum(c for _, _, c in ROWS)   # VsScreen.GROUND: 0 .. GROUND_COUNT-1; SKY: the rest
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
    named = "".join(slot(S, f"{S}N{i}", *NAMED[i], ind) for i in NAMED_DRAW)
    crowd = "".join(slot(S, f"{S}C{i}", *CROWD[i], ind) for i in CROWD_DRAW)
    medium = "".join(slot(S, f"{S}M{i}", *MEDIUM[i], ind) for i in MEDIUM_DRAW)
    side_mode = "Left" if S == "L" else "Right"
    return f"""    Group #{S}Half {{
      FlexWeight: 1;

      // the half's background (Bg_<Color>_{S}.png: glow, streaks, particles, the slanted edge), stretched to the box
      AssetImage #{S}Bg {{ Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0); }}

      // 1-3 fighters, big and overlapping (the strongest at the back, the others in front and lower)
      Group #{S}Named {{
        Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
{named}      }}

      // 4-9 fighters: big, spread over the half
      Group #{S}Medium {{
        Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
        Visible: false;
{medium}      }}

      // a big crowd: a mound of 24 on the ground + 4 in the sky
      Group #{S}Crowd {{
        Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
        Visible: false;
{crowd}      }}

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


# 3+ teams (user, 2026-10-16: like Smash's 4-8 player screen): one column per team, 3 to 8 columns, each its team's
# color (set by the code) under ColumnOverlay.png, a header (small colored "P1 · TEAM", the representative's name) and
# the representative big at the bottom, at one of the four sizes. Panels start hidden and are revealed one by one.
COLUMN_COUNTS = range(3, 9)


def column_size(n):
    """Picture size in an n-column screen: bigger than the column, Smash-like; the column cuts it (COLUMN_CLIP)."""
    return 1100 if n <= 4 else 1000


# the picture stands on the column's bottom edge (user, 2026-10-16: centered on the head with the feet at the bottom;
# VsScreen.columnFrame puts the feet on the picture's bottom edge and pushes a tall mob down until its head is clear of
# the header, fliers float at a fixed height)
# a scrolling container clips what it holds (the only clipping this UI has): each column's picture sits in one the size
# of the column, holding one overlay group of the same size, so nothing ever scrolls
COLUMN_CLIP = True


# VsScreen.COLUMN_CROP / columnVariant: the pictures come pre-cut to this share of their square's width (the clip
# below did not clip in game, user 2026-10-16), so each image is drawn exactly that wide, never stretched
# one per column count (VsScreen.columnVariant): exactly the column's share (1920 / n) of column_size(n). The image is
# anchored to the column's own edges (Left 0, Right 0), so it ends exactly where the column does at any screen width
# (user, 2026-10-16: a fixed width 5 % narrower left a hard edge inside the column; a wider screen stretches it ~2 %)
COLUMN_CROP = {n: round(1920 / n / column_size(n), 3) for n in range(3, 9)}


def column_crop(n):
    return COLUMN_CROP[n]


def columns(counts=COLUMN_COUNTS):
    out = []
    for n in counts:
        size = column_size(n)
        name_font = max(24, min(44, round(1920 / n / 11)))
        panels = []
        for i in range(n):
            p = f"C{n}P{i}"   # ids are letters and digits only (an underscore broke the parse)
            # each tier: picture standing on the bottom of the 1080 column content (spacer above it; a picture taller
            # than 1080 is cut at the bottom), centered across
            tiers = "".join(f"""                  Group {{
                    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
                    LayoutMode: Top;
                    Group {{ Anchor: (Height: {max(0, 1080 - round(size * f))}); }}
                    Group {{
                      Anchor: (Height: {round(size * f)});
                      AssetImage #{p}T{t} {{ Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0); Visible: false; }}
                    }}
                  }}
""" for t, f in enumerate(TIERS))
            panels.append(f"""      Group #{p} {{
        FlexWeight: 1;
        // the column rises from the bottom of the screen (user, 2026-10-16: they popped in): #..Pad shrinks and #..Body
        // grows (FlexWeights set by VsScreen, never 0: a 0 takes its content's size); the content hangs from the
        // body's top, so it moves up with it (below the screen = unseen)
        Group #{p}In {{
          Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
          Visible: false;
          LayoutMode: Top;
          Group #{p}Pad {{ FlexWeight: 1000; }}
          Group #{p}Body {{
            FlexWeight: 1;
            Background: #000000;
            Group {{
              Anchor: (Left: 0, Right: 0, Top: 0, Height: 1080);
              // the picture, already cut to the column's width by VsScreen (COLUMN_CROP)
              Group {{
                Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
{tiers}              }}
              // a mob with no column picture yet (its square picture or icon, user 2026-10-16: an icon stretched to the
              // column's height): square, as wide as the column, standing on its bottom
              Group {{
                Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
                LayoutMode: Top;
                Group {{ Anchor: (Height: {1080 - round(1920 / n)}); }}
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
        out.append(f"""    Group #Cols{n} {{
      Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
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
    """Slot indices from the back (highest feet) to the front, for VsScreen.MEDIUM_BACK_FIRST / GROUND_BACK_FIRST."""
    medium = sorted(range(len(MEDIUM)), key=lambda i: (MEDIUM[i][1], i))
    ground = sorted(range(GROUND_COUNT), key=lambda i: (CROWD[i][1], i))
    return medium, ground


# The column screens moved to VsCols<n>.ui (2026-10-16), but a server already running the older code still appends
# VsScreen.ui and sets #Cols/#ColTitle in it: with dev mode the .ui goes live at once, and a missing id disconnects the
# player ("Failed to apply CustomUI HUD commands", user 2026-10-16). Keep them here until every server runs the new
# code, then set this to False and run the script again.
LEGACY_COLUMNS_IN_MAIN = True


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


def main():
    ui = f"""// Mob Battle VS screen (VsScreen.java; vsScreen in the countdown, /mobbattle test vs). GENERATED by
// scripts/gen-vs-ui.py: edit that and run it again. Look of the VS card made for the clip
// (art-source/Renders/VS/vs_card.py, user 2026-10-16): two halves split on a slant, each in its team color with light
// streaks and floating particles, a dark name bar on top, the fighters big and overlapping, a big VS.
// Layers on top of each other (no LayoutMode = overlay): each layer splits the width by FlexWeight between its half
// (0 -> 580 thousandths while sliding in, so the halves overlap where the slant is) and a transparent rest. Fighters
// and labels hang on each half's inner edge, so they slide in with it. Pictures, colors, texts and which size of
// each slot shows are set by the code.
Group #Vs {{
  Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);

  // a plain battle royale: one background for the whole screen (Bg_Royale.png, the halves' own ones hidden; user
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

{legacy_columns()}  Group #Flash {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    Background: #ffffff(0.7);
    Visible: false;
  }}

  // the battle kind in the VS's own style, right under it (Kind_<kind>_<lang>.png, set by the code; user 2026-10-16)
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
"""
    OUT.write_text(ui, encoding="utf-8", newline="\n")
    print(OUT, len(ui.splitlines()), "lines")
    # 3+ teams: one small document per column count (VsScreen appends only the one it shows; user 2026-10-16: with
    # everything in one file the client built ~570 pictures' worth of elements for every VS and froze)
    for n in COLUMN_COUNTS:
        cols = f"""// Mob Battle VS screen, {n} team columns (VsScreen.java). GENERATED by scripts/gen-vs-ui.py: edit that and run it
// again. The halves screen is VsScreen.ui; only one of them is appended.
Group #Vs {{
  Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);

{columns([n])}  Group #Flash {{
    Anchor: (Left: 0, Right: 0, Top: 0, Bottom: 0);
    Background: #ffffff(0.7);
    Visible: false;
  }}

  // the kind's title right under the column headers, over the space above the faces (user, 2026-10-16: in the middle
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
    if "--depth" in sys.argv:
        for order in back_first():
            print(", ".join(map(str, order)))
    else:
        main()
