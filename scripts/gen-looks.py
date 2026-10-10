"""
Jade and Shimmer looks in every palette color, with their deaths (run with: python scripts/gen-looks.py).
Built from the test looks the user picked (scripts/gen-testlooks.py, 2026-10-10): jade = JadeOnce_C (appearing:
JadeAppearC held), jade death = ShatterJadeA, shimmer = ShimmerRunes (Shimmer_A veins + eye flames + the Coffer
goblin runes), shimmer death = MeltA. Green keeps the exact jade colors and Purple the exact shimmer colors that
were tuned in game; the other colors are derived from the palette (Teams.PALETTE).

Writes, per color <C>:
  Server/Entity/ModelVFX/MobBattle/Look/MobBattle_Look_{Jade,JadeAppear,Shimmer}_<C>.json
  Server/Entity/ModelVFX/MobBattle/Death/MobBattle_Death_{Jade,Shimmer}_<C>.json
  Server/Entity/Effects/MobBattle/Look/MobBattle_Look_{Jade,JadeAppear,Shimmer}_<C>.json   (Infinite)
  Server/Entity/Effects/MobBattle/Fx/Death/MobBattle_Fx_Death_{Jade,Shimmer}_<C>.json       (WandFx death looks)
  Server/Entity/Effects/MobBattle/Fx/Buff/MobBattle_Fx_Buff{Shimmer,Runes}_<C>.json         (Buffs: the buff's look;
      Runes = only the runes and eye flames, for a mob with a look of its own, e.g. a jade summon)
  Server/Particles/MobBattle/Look/MobBattle_Look_EyeFlame_<C>.particlesystem (+ spawner in Spawners/Look/)
  Server/Particles/MobBattle/Look/MobBattle_Look_Runes_<C>.particlesystem (+ 4 spawners), not for Purple:
      Purple uses the vanilla Coffer_Goblin_Void_Glow itself.
  Common/Particles/Textures/MobBattle/Runes_<C>.png: the vanilla LightningVoid.png (purple) hue-shifted.
The vanilla files are read (read-only) from the pre-release Assets.zip. Needs Pillow.
"""
import colorsys
import copy
import io
import json
import os
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS_ZIP = os.path.join(os.environ['APPDATA'], 'Hytale', 'install', 'pre-release', 'package', 'game', 'latest', 'Assets.zip')
VOID_HANDS = 'Server/Particles/NPC/Void_Spectre/Spawners/Void_Hands.particlespawner'
RUNES_SYSTEM = 'Server/Particles/NPC/Goblin/Coffer/Coffer_Goblin_Void_Glow.particlesystem'
RUNES_SPAWNERS = 'Server/Particles/NPC/Goblin/Coffer/Spawners/'
RUNES_TEXTURE = 'Common/Particles/Textures/Shapes/LightningVoid.png'
VANILLA_RUNES = 'Coffer_Goblin_Void_Glow'

VFX_LOOK = os.path.join(ROOT, 'Server', 'Entity', 'ModelVFX', 'MobBattle', 'Look')
VFX_DEATH = os.path.join(ROOT, 'Server', 'Entity', 'ModelVFX', 'MobBattle', 'Death')
FX_LOOK = os.path.join(ROOT, 'Server', 'Entity', 'Effects', 'MobBattle', 'Look')
FX_DEATH = os.path.join(ROOT, 'Server', 'Entity', 'Effects', 'MobBattle', 'Fx', 'Death')
FX_BUFF = os.path.join(ROOT, 'Server', 'Entity', 'Effects', 'MobBattle', 'Fx', 'Buff')
PARTICLES = os.path.join(ROOT, 'Server', 'Particles', 'MobBattle', 'Look')
SPAWNERS = os.path.join(ROOT, 'Server', 'Particles', 'MobBattle', 'Spawners', 'Look')
TEXTURES = os.path.join(ROOT, 'Common', 'Particles', 'Textures', 'MobBattle')
TEXTURE_PATH = 'Particles/Textures/MobBattle/Runes_%s.png'
# Size of the runes over the head (the Coffer Feastmaster uses 1.5, which covered big mobs; 1.0 picked in game, 2026-10-10).
RUNE_SCALE = 1.0

# Teams.PALETTE (keep in sync).
PALETTE = {
    'Red': '#FF4B3E', 'Blue': '#4B9BFF', 'Green': '#4BFF6A', 'Yellow': '#FFD23E', 'Orange': '#FF8C1A',
    'Purple': '#A24BFF', 'Pink': '#FF6BD5', 'Cyan': '#3EF0FF', 'White': '#F2F2F2', 'Black': '#2A2A2A',
    'Brown': '#8B5A2B', 'Lime': '#B6FF3E', 'Gray': '#808080', 'Magenta': '#FF00FF',
}


# ---- colors ------------------------------------------------------------------

def rgb(hex_):
    h = hex_.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def hexc(c):
    return '#%02x%02x%02x' % tuple(max(0, min(255, round(v))) for v in c)


def scale(hex_, f):
    """Darker (f < 1): every channel times f."""
    return hexc(v * f for v in rgb(hex_))


def light(hex_, t):
    """Lighter: mixed with white by t."""
    return hexc(v + (255 - v) * t for v in rgb(hex_))


def shades(name):
    """Every color a look needs, derived from the palette color. Green/Purple: the values tuned in game."""
    p = PALETTE[name]
    s = {
        'jade': scale(p, 0.81),          # JadeOnce_C highlight (darker = less glow)
        'jade_crystal': scale(p, 0.83),  # shatter crystal pieces
        'jade_sparks': light(p, 0.3),    # shatter gem sparks
        'vein': light(p, 0.3),           # Shimmer_A veins
        'melt_top': light(p, 0.25),      # MeltA tint
        'melt_bottom': scale(p, 0.36),
        'melt_drops': p,                 # MeltA glass sparks
        'eye': light(p, 0.45),           # eye flames
    }
    if name == 'Green':
        s.update(jade='#3fcf6e', jade_crystal='#4fd37e', jade_sparks='#7dffa0', eye='#7dffa8')
    if name == 'Purple':
        s.update(vein='#c77dff', melt_top='#b98cff', melt_bottom='#3a1a5c', melt_drops='#a24bff', eye='#e2c2ff')
    return s


# ---- files -------------------------------------------------------------------

counts = {}


def write(folder, name, data):
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, name), 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(data, indent=2) + '\n')
    counts[folder] = counts.get(folder, 0) + 1


def with_comment(text, body):
    data = {'$Comment': text + ' Generated by scripts/gen-looks.py.'}
    data.update(body)
    return data


def recolor(node, fn):
    """Replaces every "Color" value inside node with fn(old)."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key == 'Color' and isinstance(value, str):
                node[key] = fn(value)
            else:
                recolor(value, fn)
    elif isinstance(node, list):
        for value in node:
            recolor(value, fn)


# ---- ModelVFX (same fields and order as the test looks) -------------------------

VFX_BASE = {
    'SwitchTo': 'PostColor',
    'EffectDirection': 'None',
    'AnimationDuration': 0.1,
    'AnimationRange': {'X': 0.0, 'Y': 1.0},
    'CurveType': 'Linear',
    'LoopOption': 'PlayOnce',
    'HighlightColor': '#b6ffcc',
    'HighlightThickness': 0.0,
    'UseBloomOnHighlight': False,
    'UseProgessiveHighlight': False,
    'NoiseScale': {'X': 40.0, 'Y': 40.0},
    'NoiseScrollSpeed': {'X': 0.0, 'Y': 0.0},
    'PostColor': '#3fbf6a',
    'PostColorOpacity': 0.0,
}

# Whole model is highlight (thickness 60, progressive): mix(texture, color, threshold/noise), fullbright + bloom.
JADE = dict(HighlightThickness=60.0, UseProgessiveHighlight=True, UseBloomOnHighlight=True)


def vfx(text, **fields):
    body = copy.deepcopy(VFX_BASE)
    body.update(fields)
    return with_comment(text, body)


def look_vfx(name, s):
    write(VFX_LOOK, 'MobBattle_Look_Jade_%s.json' % name, vfx(
        '%s jade look (JadeOnce_C): fixed threshold 0.3 held for a day, the texture shows through.' % name,
        AnimationDuration=86400.0, AnimationRange={'X': 0.3, 'Y': 0.3}, HighlightColor=s['jade'], **JADE))
    write(VFX_LOOK, 'MobBattle_Look_JadeAppear_%s.json' % name, vfx(
        '%s jade growing over the mob in 1 s (JadeAppearC), ends on the MobBattle_Look_Jade_%s look.' % (name, name),
        AnimationDuration=1.0, AnimationRange={'X': 0.0, 'Y': 0.3}, HighlightColor=s['jade'], **JADE))
    write(VFX_LOOK, 'MobBattle_Look_Shimmer_%s.json' % name, vfx(
        '%s shimmer veins pulsing under the texture (Shimmer_A).' % name,
        AnimationDuration=3.0, AnimationRange={'X': 0.2, 'Y': 0.8}, LoopOption='LoopMirror', HighlightColor=s['vein'],
        HighlightThickness=1.2, UseBloomOnHighlight=True, NoiseScale={'X': 60.0, 'Y': 60.0},
        NoiseScrollSpeed={'X': 0.0, 'Y': 0.03}))
    write(VFX_DEATH, 'MobBattle_Death_Jade_%s.json' % name, vfx(
        '%s jade mob dies: the jade look breaks into pieces (ShatterJadeA, starts on the Jade look).' % name,
        SwitchTo='Disappear', AnimationDuration=0.9, AnimationRange={'X': 0.3, 'Y': 1.0}, HighlightColor=s['jade'], **JADE))
    write(VFX_DEATH, 'MobBattle_Death_Shimmer_%s.json' % name, vfx(
        '%s shimmer mob dies: melts from the head down, rippling edge (MeltA).' % name,
        SwitchTo='Disappear', EffectDirection='TopDown', AnimationDuration=1.4, CurveType='QuartIn',
        HighlightColor=s['vein'], HighlightThickness=2.5, UseBloomOnHighlight=True,
        NoiseScale={'X': 150.0, 'Y': 150.0}, NoiseScrollSpeed={'X': 0.0, 'Y': -0.25}))


# ---- particles -----------------------------------------------------------------

def eye_flame(z, name, s):
    src = json.loads(z.read(VOID_HANDS).decode('utf-8-sig'))
    src.pop('$Comment', None)
    recolor(src['Particle'], lambda _: s['eye'])
    sid = 'MobBattle_Look_EyeFlame_%s' % name
    write(SPAWNERS, sid + '.particlespawner', with_comment(
        'Copy of the vanilla void eye flame (Void_Hands), recolored %s, follows the eye node.' % name, src))
    write(PARTICLES, sid + '.particlesystem', with_comment('%s eye flame (L-Eye and R-Eye of a look).' % name, {
        'Spawners': [{'SpawnerId': sid, 'PositionOffset': {'X': 0, 'Y': 0, 'Z': 0}}],
        'CullDistance': 64,
    }))
    return sid


def rune_texture(png, name):
    """LightningVoid.png with the palette color's hue; saturation and brightness scaled like the palette color."""
    hue, sat, val = colorsys.rgb_to_hsv(*(v / 255 for v in rgb(PALETTE[name])))
    ref_sat = colorsys.rgb_to_hsv(*(v / 255 for v in rgb(PALETTE['Purple'])))[1]
    im = Image.open(io.BytesIO(png)).convert('RGBA')
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            _, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            nr, ng, nb = colorsys.hsv_to_rgb(hue, min(1.0, s * sat / ref_sat), v * val)
            px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    os.makedirs(TEXTURES, exist_ok=True)
    im.save(os.path.join(TEXTURES, 'Runes_%s.png' % name))
    counts[TEXTURES] = counts.get(TEXTURES, 0) + 1


def runes(z, name):
    """The Coffer goblin runes (Coffer_Goblin_Void_Glow) in this color, or the vanilla system for Purple."""
    if name == 'Purple':
        return VANILLA_RUNES
    p = PALETTE[name]
    # The vanilla spawner colors (multiplied over the texture) -> this color.
    colors = {'#ed0909': p, '#f84848': p, '#fff7cc': light(p, 0.8), '#fff8ae': light(p, 0.7), '#1c053a': scale(p, 0.23)}
    system = json.loads(z.read(RUNES_SYSTEM).decode('utf-8-sig'))
    system.pop('$Comment', None)
    for entry in system['Spawners']:
        vanilla = entry['SpawnerId']
        sid = 'MobBattle_Look_Runes_%s_%s' % (vanilla.replace('Coffer_Goblin_', ''), name)
        spawner = json.loads(z.read(RUNES_SPAWNERS + vanilla + '.particlespawner').decode('utf-8-sig'))
        spawner.pop('$Comment', None)
        recolor(spawner, lambda old: colors.get(old.lower(), old))
        if spawner.get('Particle', {}).get('Texture') == 'Particles/Textures/Shapes/LightningVoid.png':
            spawner['Particle']['Texture'] = TEXTURE_PATH % name
        write(SPAWNERS, sid + '.particlespawner', with_comment(
            'Copy of the vanilla %s, recolored %s (shimmer runes).' % (vanilla, name), spawner))
        entry['SpawnerId'] = sid
    sid = 'MobBattle_Look_Runes_%s' % name
    write(PARTICLES, sid + '.particlesystem', with_comment(
        'Copy of the vanilla Coffer_Goblin_Void_Glow (the Coffer goblins\' runes), recolored %s.' % name, system))
    return sid


# ---- effects -------------------------------------------------------------------

def eyes(flame):
    return [{'SystemId': flame, 'TargetNodeName': 'L-Eye'}, {'SystemId': flame, 'TargetNodeName': 'R-Eye'}]


def chest(system, color):
    return {'SystemId': system, 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': color}


def effect(text, apply, duration=None):
    body = {'Infinite': True} if duration is None else {'Duration': duration}
    body.update({'OverlapBehavior': 'Overwrite', 'Debuff': False, 'ApplicationEffects': apply})
    return with_comment(text, body)


def look_effects(name, s, flame, rune_system):
    runes = [{'SystemId': rune_system, 'TargetNodeName': 'Head', 'Scale': RUNE_SCALE}]
    write(FX_BUFF, 'MobBattle_Fx_BuffShimmer_%s.json' % name, effect(
        'Buff Wand, streak and champion buff in %s (buffColor): the shimmer look while the buff lasts. '
        'Guarded by LookKeeper.' % name,
        {'ModelVFXId': 'MobBattle_Look_Shimmer_%s' % name, 'Particles': eyes(flame) + runes}))
    write(FX_BUFF, 'MobBattle_Fx_BuffRunes_%s.json' % name, effect(
        'The %s buff on a mob with a look of its own (one ModelVFX per mob, e.g. a jade summon): only the shimmer '
        'runes and eye flames.' % name,
        {'Particles': eyes(flame) + runes}))
    write(FX_LOOK, 'MobBattle_Look_Jade_%s.json' % name, effect(
        '%s jade look: the model turns to jade (texture shows), glowing eye flames. Kept by LookKeeper.' % name,
        {'ModelVFXId': 'MobBattle_Look_Jade_%s' % name, 'Particles': eyes(flame)}))
    write(FX_LOOK, 'MobBattle_Look_JadeAppear_%s.json' % name, effect(
        '%s jade growing over the mob, then held: one effect, no gap. LookKeeper re-sends it as '
        'MobBattle_Look_Jade_%s.' % (name, name),
        {'ModelVFXId': 'MobBattle_Look_JadeAppear_%s' % name, 'Particles': eyes(flame)}))
    write(FX_LOOK, 'MobBattle_Look_Shimmer_%s.json' % name, effect(
        '%s shimmer look: veins pulsing under the texture, eye flames, runes over the head. Kept by LookKeeper.' % name,
        {'ModelVFXId': 'MobBattle_Look_Shimmer_%s' % name,
         'Particles': eyes(flame) + runes}))
    write(FX_DEATH, 'MobBattle_Fx_Death_Jade_%s.json' % name, effect(
        '%s jade mob dies (Kill Wand, Snap): the jade breaks into pieces, crystal and gem sparks. No tint.' % name,
        {'ModelVFXId': 'MobBattle_Death_Jade_%s' % name,
         'Particles': [chest('Block_Break_Crystal', s['jade_crystal']), chest('Block_Gem_Sparks', s['jade_sparks'])]},
        duration=0.9))
    write(FX_DEATH, 'MobBattle_Fx_Death_Shimmer_%s.json' % name, effect(
        '%s shimmer mob dies (Kill Wand, Snap): melts from the head down, tinted, glass sparks.' % name,
        {'ModelVFXId': 'MobBattle_Death_Shimmer_%s' % name, 'EntityTopTint': s['melt_top'],
         'EntityBottomTint': s['melt_bottom'],
         'Particles': [{'SystemId': 'Item_Break_GlassMagic', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Pelvis',
                        'Color': s['melt_drops']}]},
        duration=1.4))


# ---- main ----------------------------------------------------------------------

with zipfile.ZipFile(ASSETS_ZIP) as z:
    texture = z.read(RUNES_TEXTURE)
    for name in PALETTE:
        s = shades(name)
        look_vfx(name, s)
        flame = eye_flame(z, name, s)
        if name != 'Purple':
            rune_texture(texture, name)
        look_effects(name, s, flame, runes(z, name))

for folder, n in counts.items():
    print(n, 'files in', os.path.relpath(folder, ROOT))
