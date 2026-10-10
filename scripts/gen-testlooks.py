"""
TEST looks for the MobBattle particle and ModelVFX experiments (run with: python -I scripts/gen-testlooks.py):
Server/Particles/MobBattle/Spawners/Test/<name>.particlespawner   4 eye spawners (EyeDot = steady glowing dot,
    EyeFlame = copy of the vanilla void eye flame, recolored), Jade and Violet.
Server/Particles/MobBattle/Test/<name>.particlesystem            one system per spawner (CullDistance 64).
Server/Entity/ModelVFX/MobBattle/Test/<id>.json                  10 ModelVFX looks (jade and shimmer).
Server/Entity/Effects/MobBattle/Test/<id>.json                   14 entity effects: a ModelVFX and/or tints and the eye particles.
Every file gets a "$Comment" first key starting with "TEST look (scripts/gen-testlooks.py): ".
The vanilla flame is read (read-only) from the pre-release Assets.zip.
"""
import copy
import json
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREFIX = 'TEST look (scripts/gen-testlooks.py): '
ASSETS_ZIP = os.path.join(os.environ['APPDATA'], 'Hytale', 'install', 'pre-release', 'package', 'game', 'latest', 'Assets.zip')
VOID_HANDS = 'Server/Particles/NPC/Void_Spectre/Spawners/Void_Hands.particlespawner'

SPAWN_DIR = os.path.join(ROOT, 'Server', 'Particles', 'MobBattle', 'Spawners', 'Test')
SYSTEM_DIR = os.path.join(ROOT, 'Server', 'Particles', 'MobBattle', 'Test')
VFX_DIR = os.path.join(ROOT, 'Server', 'Entity', 'ModelVFX', 'MobBattle', 'Test')
FX_DIR = os.path.join(ROOT, 'Server', 'Entity', 'Effects', 'MobBattle', 'Test')

COLORS = {'Jade': '#7dffa8', 'Violet': '#e2c2ff'}
EYE_KINDS = ['EyeDot_Jade', 'EyeFlame_Jade', 'EyeDot_Violet', 'EyeFlame_Violet']


def comment(text):
    return PREFIX + text


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(data, indent=2) + '\n')


def color_of(kind):
    return COLORS[kind.split('_')[1]]


def eye_dot(kind):
    color = color_of(kind)
    return {
        '$Comment': comment('steady glowing %s eye dot, follows the eye node' % color_of_name(kind)),
        'RenderMode': 'BlendAdd',
        'ParticleRotationInfluence': 'Billboard',
        'LinearFiltering': True,
        'LightInfluence': 0,
        'ParticleRotateWithSpawner': True,
        'MaxConcurrentParticles': 6,
        'ParticleLifeSpan': {'Min': 0.25, 'Max': 0.25},
        'SpawnRate': {'Min': 20, 'Max': 20},
        'TotalParticles': {'Min': -1, 'Max': -1},
        'InitialVelocity': {
            'Speed': {'Min': 0, 'Max': 0},
            'Yaw': {'Min': 0, 'Max': 0},
            'Pitch': {'Min': 0, 'Max': 0},
        },
        'Particle': {
            'Texture': 'Particles/Textures/Basic/Glow.png',
            'FrameSize': {'Width': 32, 'Height': 32},
            'ScaleRatioConstraint': 'None',
            'InitialAnimationFrame': {
                'Scale': {'X': {'Min': 0.22, 'Max': 0.22}, 'Y': {'Min': 0.22, 'Max': 0.22}},
                'Opacity': 0.9,
                'Color': color,
                'FrameIndex': {'Min': 0, 'Max': 0},
            },
            'Animation': {
                '0': {'Opacity': 0.9},
                '100': {'Opacity': 0.6},
            },
        },
    }


def recolor(node, color):
    """Sets every "Color" value found anywhere inside node. Returns how many were set."""
    n = 0
    if isinstance(node, dict):
        for key, value in node.items():
            if key == 'Color':
                node[key] = color
                n += 1
            else:
                n += recolor(value, color)
    elif isinstance(node, list):
        for value in node:
            n += recolor(value, color)
    return n


def eye_flame(kind):
    color = color_of(kind)
    with zipfile.ZipFile(ASSETS_ZIP) as z:
        src = json.loads(z.read(VOID_HANDS).decode('utf-8-sig'))
    src.pop('$Comment', None)
    n = recolor(src['Particle'], color)
    print('  %s: %d Color value(s) set to %s' % (kind, n, color))
    data = {'$Comment': comment('copy of the vanilla void eye flame, recolored %s, follows the eye node' % color_of_name(kind))}
    data.update(src)
    return data


def color_of_name(kind):
    return kind.split('_')[1].lower()


# ---- ModelVFX -------------------------------------------------------------

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
    'PostColorOpacity': 0.75,
}

JADE2 = {
    'HighlightColor': '#5dff8a',
    'HighlightThickness': 60.0,
    'UseProgessiveHighlight': True,
    'UseBloomOnHighlight': True,
    'PostColorOpacity': 0.0,
    'AnimationRange': {'X': 0.0, 'Y': 0.5},
    'AnimationDuration': 0.6,
}

JADE3A = {
    'PostColor': '#3fbf6a',
    'PostColorOpacity': 0.8,
    'HighlightColor': '#b6ffcc',
    'HighlightThickness': 1.5,
    'UseBloomOnHighlight': True,
    'LoopOption': 'LoopMirror',
    'AnimationRange': {'X': 0.6, 'Y': 1.0},
    'AnimationDuration': 2.5,
    'NoiseScrollSpeed': {'X': 0.0, 'Y': 0.05},
}

JADE5A = {
    'PostColor': '#3fbf6a',
    'PostColorOpacity': 0.8,
    'EffectDirection': 'BottomUp',
    'AnimationDuration': 1.5,
    'HighlightColor': '#d6ffe0',
    'HighlightThickness': 1.2,
    'UseBloomOnHighlight': True,
}

# (id, overrides, description)
VFX = [
    ('MobBattle_Test_Jade1A', {'PostColor': '#4fd37e', 'PostColorOpacity': 0.7},
     'solid jade, light, texture shows through'),
    ('MobBattle_Test_Jade1B', {'PostColor': '#1f8f4a', 'PostColorOpacity': 0.9},
     'solid jade, dark, almost opaque'),
    ('MobBattle_Test_Jade2A', dict(JADE2),
     'glowing marbled jade (whole model is highlight, bloom)'),
    ('MobBattle_Test_Jade2B', dict(JADE2, HighlightColor='#2fa85a', UseBloomOnHighlight=False),
     'marbled jade without glow'),
    ('MobBattle_Test_Jade3A', dict(JADE3A),
     'living jade: patches breathe, glowing edge moves'),
    ('MobBattle_Test_Jade3B', dict(JADE3A, LoopOption='Loop', AnimationDuration=1.2, HighlightThickness=0.8,
                                   NoiseScale={'X': 70.0, 'Y': 70.0}, NoiseScrollSpeed={'X': 0.0, 'Y': 0.15}),
     'living jade, faster finer flow'),
    ('MobBattle_Test_Jade5A', dict(JADE5A),
     'jade rises from the feet (like vanilla Freeze), then stays'),
    ('MobBattle_Test_Jade5B', dict(JADE5A, EffectDirection='FromCenter', AnimationDuration=1.0, HighlightThickness=2.0),
     'jade spreads from the middle, then stays'),
    ('MobBattle_Test_Shimmer_A', {
        'PostColorOpacity': 0.0,
        'HighlightColor': '#c77dff',
        'HighlightThickness': 1.2,
        'UseBloomOnHighlight': True,
        'LoopOption': 'LoopMirror',
        'AnimationRange': {'X': 0.2, 'Y': 0.8},
        'AnimationDuration': 3.0,
        'NoiseScale': {'X': 60.0, 'Y': 60.0},
        'NoiseScrollSpeed': {'X': 0.0, 'Y': 0.03},
    }, 'purple veins only (no recolor), slow pulse'),
    ('MobBattle_Test_Shimmer_B', {
        'PostColorOpacity': 0.0,
        'HighlightColor': '#d9a6ff',
        'HighlightThickness': 0.6,
        'UseBloomOnHighlight': True,
        'LoopOption': 'Loop',
        'AnimationRange': {'X': 0.1, 'Y': 0.9},
        'AnimationDuration': 1.5,
        'NoiseScale': {'X': 100.0, 'Y': 100.0},
        'NoiseScrollSpeed': {'X': 0.0, 'Y': 0.08},
    }, 'thin fast purple rays running over the body'),
    # v2 (2026-10-10): PlayOnce VFX switch off when their animation ends (Jade1/Jade2 in game);
    # vanilla holds a look with Loop + a fixed range (Hazard_Electric_Stun: X = Y = 0.5).
    ('MobBattle_Test_JadeHold_A', dict(JADE2, LoopOption='Loop', AnimationRange={'X': 0.5, 'Y': 0.5}, AnimationDuration=1.0),
     'Jade2A held for good (Loop, fixed threshold 0.5)'),
    ('MobBattle_Test_JadeHold_B', dict(JADE2, LoopOption='Loop', AnimationRange={'X': 0.8, 'Y': 0.8}, AnimationDuration=1.0),
     'Jade2A held for good, stronger jade (fixed threshold 0.8)'),
    ('MobBattle_Test_JadeAppear', dict(JADE2, EffectDirection='FromCenter', AnimationRange={'X': 0.0, 'Y': 0.5}, AnimationDuration=1.0),
     'Jade2A spreading from the middle (PlayOnce, 1 s), meant to be followed by JadeHold_A'),
    # v3: Hold A/B glowed so much the texture vanished. Lower threshold = lower mix ratio (threshold/noise),
    # so more of the texture shows; darker highlight.
    ('MobBattle_Test_JadeHold_C', dict(JADE2, HighlightColor='#3fcf6e', LoopOption='Loop', AnimationRange={'X': 0.3, 'Y': 0.3}, AnimationDuration=1.0),
     'softer jade, texture shows (threshold 0.3, bloom)'),
    ('MobBattle_Test_JadeHold_D', dict(JADE2, HighlightColor='#3fcf6e', UseBloomOnHighlight=False, LoopOption='Loop', AnimationRange={'X': 0.3, 'Y': 0.3}, AnimationDuration=1.0),
     'softer jade without glow (threshold 0.3, no bloom)'),
    # v4: user liked how C shows the texture but it glows too much; D is see-through and dark.
    # Bloom strength follows the highlight color's brightness, so darker greens glow less.
    ('MobBattle_Test_JadeHold_E', dict(JADE2, HighlightColor='#2c9a55', LoopOption='Loop', AnimationRange={'X': 0.3, 'Y': 0.3}, AnimationDuration=1.0),
     'C with a darker green: same texture, less glow'),
    ('MobBattle_Test_JadeHold_F', dict(JADE2, HighlightColor='#237a45', LoopOption='Loop', AnimationRange={'X': 0.35, 'Y': 0.35}, AnimationDuration=1.0),
     'C with an even darker green and a bit more jade'),
    # v5: E came out too dark: halfway between C (#3fcf6e) and E (#2c9a55).
    ('MobBattle_Test_JadeHold_G', dict(JADE2, HighlightColor='#36b862', LoopOption='Loop', AnimationRange={'X': 0.3, 'Y': 0.3}, AnimationDuration=1.0),
     'between C and E: texture shows, medium glow'),
    # v6: C looked closest but blotchy ("green stains", not "made of jade"). The blotches are the noise:
    # mix ratio = threshold/noise per pixel. A much finer noise averages them into an even grain.
    ('MobBattle_Test_JadeHold_H', dict(JADE2, HighlightColor='#3fcf6e', LoopOption='Loop', AnimationRange={'X': 0.3, 'Y': 0.3}, AnimationDuration=1.0,
                                       NoiseScale={'X': 300.0, 'Y': 300.0}),
     'C with a very fine noise: even jade grain instead of stains'),
    ('MobBattle_Test_JadeHold_I', dict(JADE2, HighlightColor='#3fcf6e', LoopOption='Loop', AnimationRange={'X': 0.4, 'Y': 0.4}, AnimationDuration=1.0,
                                       NoiseScale={'X': 150.0, 'Y': 150.0}),
     'C with a finer noise and a bit more jade'),
    # Even jade with no noise at all: PostColor mixes every pixel the same (Jade1A, which looked great before it
    # switched off), held with a fixed threshold of 1 so the whole model is covered. No glow.
    ('MobBattle_Test_JadeHold_J', {'PostColor': '#4fd37e', 'PostColorOpacity': 0.7, 'LoopOption': 'Loop',
                                   'AnimationRange': {'X': 1.0, 'Y': 1.0}, 'AnimationDuration': 1.0},
     'even jade over the whole model (Jade1A held), texture shows, no glow'),
    # v7: Loop VFX received live switch off after a few seconds (out of view and back: they stay). PlayOnce VFX
    # live work (Burn, Disintegrate, Jade1/2 until their animation ended). So: PlayOnce with a fixed threshold
    # (X = Y) lasting a day. NoiseScrollSpeed moves the noise by time in the shader, not by animation progress.
    ('MobBattle_Test_JadeOnce_C', dict(JADE2, HighlightColor='#3fcf6e', LoopOption='PlayOnce', AnimationRange={'X': 0.3, 'Y': 0.3}, AnimationDuration=86400.0),
     'JadeHold_C as a day-long PlayOnce (should stay when given live)'),
    ('MobBattle_Test_JadeOnce_H', dict(JADE2, HighlightColor='#3fcf6e', LoopOption='PlayOnce', AnimationRange={'X': 0.3, 'Y': 0.3}, AnimationDuration=86400.0,
                                       NoiseScale={'X': 300.0, 'Y': 300.0}),
     'JadeHold_H (fine grain) as a day-long PlayOnce'),
    ('MobBattle_Test_JadeOnce_J', {'PostColor': '#4fd37e', 'PostColorOpacity': 0.7, 'LoopOption': 'PlayOnce',
                                   'AnimationRange': {'X': 1.0, 'Y': 1.0}, 'AnimationDuration': 86400.0},
     'JadeHold_J (even jade, no glow) as a day-long PlayOnce'),
    ('MobBattle_Test_ShimmerOnce', {
        'PostColorOpacity': 0.0, 'HighlightColor': '#c77dff', 'HighlightThickness': 1.2, 'UseBloomOnHighlight': True,
        'LoopOption': 'PlayOnce', 'AnimationRange': {'X': 0.5, 'Y': 0.5}, 'AnimationDuration': 86400.0,
        'NoiseScale': {'X': 60.0, 'Y': 60.0}, 'NoiseScrollSpeed': {'X': 0.0, 'Y': 0.03},
    }, 'Shimmer A as a day-long PlayOnce: fixed threshold, veins move with NoiseScrollSpeed'),
    # v8: how a jade summon could leave ("shatter"). Disappear discards the pixels as the threshold rises; a low
    # NoiseScale makes big pieces (shards). The tint is separate from the VFX, so a jade tint stays while it breaks.
    # Test only: given with the Effect Wand the mob is NOT removed, so it shows again when the animation ends.
    ('MobBattle_Test_ShatterA', {'SwitchTo': 'Disappear', 'EffectDirection': 'FromCenter', 'AnimationRange': {'X': 0.0, 'Y': 1.0},
                                 'AnimationDuration': 0.9, 'HighlightColor': '#7dffa0', 'HighlightThickness': 2.0,
                                 'UseBloomOnHighlight': True, 'NoiseScale': {'X': 12.0, 'Y': 12.0}, 'PostColorOpacity': 0.0},
     'jade shatter: big shards breaking from the middle, glowing jade edges'),
    ('MobBattle_Test_ShatterB', {'SwitchTo': 'Disappear', 'EffectDirection': 'BottomUp', 'AnimationRange': {'X': 0.0, 'Y': 1.0},
                                 'AnimationDuration': 1.3, 'HighlightColor': '#b6ffcc', 'HighlightThickness': 1.5,
                                 'UseBloomOnHighlight': True, 'NoiseScale': {'X': 25.0, 'Y': 25.0}, 'PostColorOpacity': 0.0},
     'jade crumble: smaller pieces falling apart from the feet up'),
    ('MobBattle_Test_ShatterC', {'SwitchTo': 'Disappear', 'EffectDirection': 'None', 'AnimationRange': {'X': 0.0, 'Y': 1.0},
                                 'AnimationDuration': 0.6, 'HighlightColor': '#7dffa0', 'HighlightThickness': 3.0,
                                 'UseBloomOnHighlight': True, 'NoiseScale': {'X': 8.0, 'Y': 8.0}, 'PostColorOpacity': 0.0},
     'jade burst: a few huge shards everywhere at once, fast'),
    # v9: shimmer death: no shards, it melts like liquid. TopDown + very fine noise = a smooth edge running down;
    # NoiseScrollSpeed makes the edge ripple. Purple highlight with bloom.
    ('MobBattle_Test_MeltA', {'SwitchTo': 'Disappear', 'EffectDirection': 'TopDown', 'AnimationRange': {'X': 0.0, 'Y': 1.0},
                              'AnimationDuration': 1.4, 'CurveType': 'QuartIn', 'HighlightColor': '#c77dff', 'HighlightThickness': 2.5,
                              'UseBloomOnHighlight': True, 'NoiseScale': {'X': 150.0, 'Y': 150.0},
                              'NoiseScrollSpeed': {'X': 0.0, 'Y': -0.25}, 'PostColorOpacity': 0.0},
     'shimmer melt: the body drains away from the head down, rippling purple edge'),
    ('MobBattle_Test_MeltB', {'SwitchTo': 'Disappear', 'EffectDirection': 'ToCenter', 'AnimationRange': {'X': 0.0, 'Y': 1.0},
                              'AnimationDuration': 1.2, 'HighlightColor': '#d9a6ff', 'HighlightThickness': 3.0,
                              'UseBloomOnHighlight': True, 'NoiseScale': {'X': 200.0, 'Y': 200.0},
                              'NoiseScrollSpeed': {'X': 0.1, 'Y': -0.3}, 'PostColorOpacity': 0.0},
     'shimmer melt: head and feet flow into the middle and vanish'),
    # v10: ShatterA showed the plain texture + tint while breaking (only one VFX per mob, Disappear has no PostColor),
    # so the jade look "switched off" right before the shards. Trick: in the shader every pixel within
    # HighlightThickness*0.01 of the threshold is "highlight" -> mix(texture, HighlightColor, threshold/noise) with
    # UseProgessiveHighlight, fullbright + bloom. That is exactly how JadeOnce_C looks (thickness 60, threshold 0.3,
    # same color, direction None, NoiseScale 40). Starting the shatter at threshold 0.3 with those values continues
    # the jade look; pieces with noise < threshold are discarded, the rest turn brighter jade as it rises. No tint
    # (JadeOnce_C has none).
    ('MobBattle_Test_ShatterJadeA', {'SwitchTo': 'Disappear', 'EffectDirection': 'None', 'AnimationRange': {'X': 0.3, 'Y': 1.0},
                                     'AnimationDuration': 0.9, 'HighlightColor': '#3fcf6e', 'HighlightThickness': 60.0,
                                     'UseProgessiveHighlight': True, 'UseBloomOnHighlight': True,
                                     'NoiseScale': {'X': 40.0, 'Y': 40.0}, 'PostColorOpacity': 0.0},
     'jade shatter that keeps the JadeOnce_C look while breaking (same noise, pieces fall where the noise is low)'),
    ('MobBattle_Test_ShatterJadeB', {'SwitchTo': 'Disappear', 'EffectDirection': 'FromCenter', 'AnimationRange': {'X': 0.3, 'Y': 1.0},
                                     'AnimationDuration': 0.9, 'HighlightColor': '#3fcf6e', 'HighlightThickness': 60.0,
                                     'UseProgessiveHighlight': True, 'UseBloomOnHighlight': True,
                                     'NoiseScale': {'X': 12.0, 'Y': 12.0}, 'PostColorOpacity': 0.0},
     'jade shatter from the middle with big shards, the pieces stay jade while breaking'),
    # v11 (now that the VFX loss is understood): more shimmer looks, all infinite.
    # v13: appearance that ends exactly on JadeOnce_C (same color/noise, threshold 0 -> 0.3 from the middle).
    ('MobBattle_Test_JadeAppearC', dict(JADE2, HighlightColor='#3fcf6e', EffectDirection='None', LoopOption='PlayOnce',
                                        AnimationRange={'X': 0.0, 'Y': 0.3}, AnimationDuration=1.0),
     'jade appearing (1 s), ends exactly on the JadeOnce_C look: chain JadeAppearC>JadeOnce_C'),
    # v14: slow motion of the two-step shimmer death, to look at it (real one is 0.5 s + 0.3 s).
    ('MobBattle_Test_DeathTurnPurpleSlow', {'PostColor': '#8a3dff', 'PostColorOpacity': 0.9, 'EffectDirection': 'BottomUp',
                                            'AnimationRange': {'X': 0.0, 'Y': 1.0}, 'AnimationDuration': 2.5,
                                            'HighlightColor': '#e2c2ff', 'HighlightThickness': 1.5, 'UseBloomOnHighlight': True},
     'SLOW MOTION shimmer death step 1 (2.5 s)'),
    ('MobBattle_Test_DeathBurstSlow', {'SwitchTo': 'Disappear', 'EffectDirection': 'FromCenter', 'AnimationRange': {'X': 0.0, 'Y': 1.0},
                                       'AnimationDuration': 2.0, 'HighlightColor': '#c77dff', 'HighlightThickness': 3.0,
                                       'UseBloomOnHighlight': True, 'NoiseScale': {'X': 30.0, 'Y': 30.0}, 'PostColorOpacity': 0.0},
     'SLOW MOTION shimmer death step 2 (2 s)'),
    ('MobBattle_Test_ShimmerPaint', dict(JADE2, HighlightColor='#9b4dff', LoopOption='PlayOnce', AnimationRange={'X': 0.3, 'Y': 0.3},
                                         AnimationDuration=86400.0),
     'shimmer painted by the shader like JadeOnce_C, in purple (no tint): marbled glowing purple'),
    ('MobBattle_Test_ShimmerVeinsPaint', {'PostColor': '#6a2bb3', 'PostColorOpacity': 0.5, 'HighlightColor': '#d9a6ff',
                                          'HighlightThickness': 1.2, 'UseBloomOnHighlight': True, 'LoopOption': 'LoopMirror',
                                          'AnimationRange': {'X': 0.55, 'Y': 0.75}, 'AnimationDuration': 3.0,
                                          'NoiseScale': {'X': 60.0, 'Y': 60.0}, 'NoiseScrollSpeed': {'X': 0.0, 'Y': 0.03}},
     'pulsing purple veins with the shader turning part of the body purple (patches below the veins)'),
    # Shimmer death in two steps (two effects, one VFX each): turn purple or gray, then burst into purple liquid.
    ('MobBattle_Test_DeathTurnPurple', {'PostColor': '#8a3dff', 'PostColorOpacity': 0.9, 'EffectDirection': 'BottomUp',
                                        'AnimationRange': {'X': 0.0, 'Y': 1.0}, 'AnimationDuration': 0.5,
                                        'HighlightColor': '#e2c2ff', 'HighlightThickness': 1.5, 'UseBloomOnHighlight': True},
     'shimmer death step 1: the body turns purple from the feet up'),
    ('MobBattle_Test_DeathTurnGray', {'PostColor': '#7d7d86', 'PostColorOpacity': 0.95, 'EffectDirection': 'BottomUp',
                                      'AnimationRange': {'X': 0.0, 'Y': 1.0}, 'AnimationDuration': 0.5,
                                      'HighlightColor': '#c77dff', 'HighlightThickness': 1.5, 'UseBloomOnHighlight': True},
     'shimmer death step 1: the body turns stone gray from the feet up (purple edge)'),
    ('MobBattle_Test_DeathBurst', {'SwitchTo': 'Disappear', 'EffectDirection': 'FromCenter', 'AnimationRange': {'X': 0.0, 'Y': 1.0},
                                   'AnimationDuration': 0.3, 'HighlightColor': '#c77dff', 'HighlightThickness': 3.0,
                                   'UseBloomOnHighlight': True, 'NoiseScale': {'X': 30.0, 'Y': 30.0}, 'PostColorOpacity': 0.0},
     'shimmer death step 2: bursts from the middle into purple liquid'),
]
VFX_DESC = {vid: desc for vid, _, desc in VFX}


def vfx_file(vid, overrides, desc):
    body = copy.deepcopy(VFX_BASE)
    body.update(copy.deepcopy(overrides))
    data = {'$Comment': comment(desc)}
    data.update(body)
    return data


# ---- EntityEffects ----------------------------------------------------------

# (id, ModelVFX id or None, (top, bottom) tint or None, eye kind)
EFFECTS = [
    ('MobBattle_Test_Jade1A', 'MobBattle_Test_Jade1A', ('#7dffa0', '#2f8f55'), 'EyeDot_Jade'),
    ('MobBattle_Test_Jade1B', 'MobBattle_Test_Jade1B', None, 'EyeDot_Jade'),
    ('MobBattle_Test_Jade2A', 'MobBattle_Test_Jade2A', None, 'EyeDot_Jade'),
    ('MobBattle_Test_Jade2B', 'MobBattle_Test_Jade2B', None, 'EyeDot_Jade'),
    ('MobBattle_Test_Jade3A', 'MobBattle_Test_Jade3A', ('#7dffa0', '#2f8f55'), 'EyeDot_Jade'),
    ('MobBattle_Test_Jade3B', 'MobBattle_Test_Jade3B', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_Eyes4A', None, None, 'EyeDot_Jade'),
    ('MobBattle_Test_Eyes4B', None, None, 'EyeFlame_Jade'),
    ('MobBattle_Test_Jade5A', 'MobBattle_Test_Jade5A', ('#7dffa0', '#2f8f55'), 'EyeDot_Jade'),
    ('MobBattle_Test_Jade5B', 'MobBattle_Test_Jade5B', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_Shimmer_A', 'MobBattle_Test_Shimmer_A', None, 'EyeDot_Violet'),
    ('MobBattle_Test_Shimmer_B', 'MobBattle_Test_Shimmer_B', ('#b98cff', '#3a1a5c'), 'EyeFlame_Violet'),
    ('MobBattle_Test_Eyes4C', None, None, 'EyeDot_Violet'),
    ('MobBattle_Test_Eyes4D', None, None, 'EyeFlame_Violet'),
    ('MobBattle_Test_JadeHold_A', 'MobBattle_Test_JadeHold_A', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeHold_B', 'MobBattle_Test_JadeHold_B', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeAppear', 'MobBattle_Test_JadeAppear', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_ShimmerHold', 'MobBattle_Test_Shimmer_A', None, 'EyeFlame_Violet'),
    ('MobBattle_Test_JadeHold_C', 'MobBattle_Test_JadeHold_C', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeHold_D', 'MobBattle_Test_JadeHold_D', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeHold_E', 'MobBattle_Test_JadeHold_E', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeHold_F', 'MobBattle_Test_JadeHold_F', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeHold_G', 'MobBattle_Test_JadeHold_G', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeHold_H', 'MobBattle_Test_JadeHold_H', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeHold_I', 'MobBattle_Test_JadeHold_I', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeHold_J', 'MobBattle_Test_JadeHold_J', ('#7dffa0', '#2f8f55'), 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeOnce_C', 'MobBattle_Test_JadeOnce_C', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeOnce_H', 'MobBattle_Test_JadeOnce_H', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_JadeOnce_J', 'MobBattle_Test_JadeOnce_J', ('#7dffa0', '#2f8f55'), 'EyeFlame_Jade'),
    ('MobBattle_Test_ShimmerOnce', 'MobBattle_Test_ShimmerOnce', None, 'EyeFlame_Violet'),
    ('MobBattle_Test_ShatterA', 'MobBattle_Test_ShatterA', ('#7dffa0', '#2f8f55'), None),
    ('MobBattle_Test_ShatterB', 'MobBattle_Test_ShatterB', ('#7dffa0', '#2f8f55'), None),
    ('MobBattle_Test_ShatterC', 'MobBattle_Test_ShatterC', ('#7dffa0', '#2f8f55'), None),
    ('MobBattle_Test_MeltA', 'MobBattle_Test_MeltA', ('#b98cff', '#3a1a5c'), None),
    ('MobBattle_Test_MeltB', 'MobBattle_Test_MeltB', ('#b98cff', '#3a1a5c'), None),
    ('MobBattle_Test_ShatterJadeA', 'MobBattle_Test_ShatterJadeA', None, None),
    ('MobBattle_Test_ShatterJadeB', 'MobBattle_Test_ShatterJadeB', None, None),
    ('MobBattle_Test_ShimmerB_Hold', 'MobBattle_Test_Shimmer_B', None, 'EyeFlame_Violet'),
    ('MobBattle_Test_ShimmerPaint', 'MobBattle_Test_ShimmerPaint', None, 'EyeFlame_Violet'),
    ('MobBattle_Test_ShimmerVeinsPaint', 'MobBattle_Test_ShimmerVeinsPaint', None, 'EyeFlame_Violet'),
    ('MobBattle_Test_DeathTurnPurple', 'MobBattle_Test_DeathTurnPurple', None, None),
    ('MobBattle_Test_DeathTurnGray', 'MobBattle_Test_DeathTurnGray', None, None),
    ('MobBattle_Test_DeathBurst', 'MobBattle_Test_DeathBurst', ('#b98cff', '#3a1a5c'), None),
    # v12: ShimmerHold + the void runes/lightning of the vanilla Coffer goblins (particles add up, unlike VFX).
    ('MobBattle_Test_JadeAppearC', 'MobBattle_Test_JadeAppearC', None, 'EyeFlame_Jade'),
    # Appear and stay in ONE infinite effect (no second effect = no gap); LookKeeper re-sends JadeOnce_C later.
    ('MobBattle_Test_JadeAppearHold', 'MobBattle_Test_JadeAppearC', None, 'EyeFlame_Jade'),
    ('MobBattle_Test_DeathTurnPurpleSlow', 'MobBattle_Test_DeathTurnPurpleSlow', None, None),
    ('MobBattle_Test_DeathBurstSlow', 'MobBattle_Test_DeathBurstSlow', ('#b98cff', '#3a1a5c'), None),
    ('MobBattle_Test_ShimmerRunes', 'MobBattle_Test_Shimmer_A', None, 'EyeFlame_Violet'),
    ('MobBattle_Test_ShimmerRunesBody', 'MobBattle_Test_Shimmer_A', None, 'EyeFlame_Violet'),
]
# Extra particles per effect (vanilla systems, tinted jade through ModelParticle.Color).
EXTRA_PARTICLES = {
    'MobBattle_Test_ShatterA': [{'SystemId': 'Block_Break_Crystal', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#4fd37e'},
                                {'SystemId': 'Block_Gem_Sparks', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#7dffa0'}],
    'MobBattle_Test_ShatterB': [{'SystemId': 'Item_Break_Glass', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#4fd37e'}],
    'MobBattle_Test_ShatterJadeA': [{'SystemId': 'Block_Break_Crystal', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#4fd37e'},
                                    {'SystemId': 'Block_Gem_Sparks', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#7dffa0'}],
    'MobBattle_Test_ShatterJadeB': [{'SystemId': 'Block_Break_Crystal', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#4fd37e'},
                                    {'SystemId': 'Block_Gem_Sparks', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#7dffa0'}],
    # As the Coffer goblin models: Coffer_Goblin_Void_Glow on Head, Scale 1.5.
    'MobBattle_Test_ShimmerRunes': [{'SystemId': 'Coffer_Goblin_Void_Glow', 'TargetNodeName': 'Head', 'Scale': 1.5}],
    # Same system on the head and on the chest, for more runes over the body.
    'MobBattle_Test_ShimmerRunesBody': [{'SystemId': 'Coffer_Goblin_Void_Glow', 'TargetNodeName': 'Head', 'Scale': 1.5},
                                        {'SystemId': 'Coffer_Goblin_Void_Glow', 'TargetNodeName': 'Chest', 'Scale': 1.5}],
    'MobBattle_Test_DeathBurstSlow': [{'SystemId': 'Potion_Morph_Burst', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#a24bff'},
                                      {'SystemId': 'Water_Splash', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#8a3dff'},
                                      {'SystemId': 'Item_Break_GlassMagic', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#c77dff'}],
    'MobBattle_Test_DeathBurst': [{'SystemId': 'Potion_Morph_Burst', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#a24bff'},
                                  {'SystemId': 'Water_Splash', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#8a3dff'},
                                  {'SystemId': 'Item_Break_GlassMagic', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#c77dff'}],
    'MobBattle_Test_MeltA': [{'SystemId': 'Item_Break_GlassMagic', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Pelvis', 'Color': '#a24bff'}],
    'MobBattle_Test_MeltB': [{'SystemId': 'Item_Break_GlassMagic', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#a24bff'}],
    'MobBattle_Test_ShatterC': [{'SystemId': 'Shield_Shatter', 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Chest', 'Color': '#4fd37e'}],
}
# Effects that last until cleansed; the rest last 60 s. JadeAppear lasts as long as its animation.
INFINITE = {'MobBattle_Test_JadeHold_A', 'MobBattle_Test_JadeHold_B', 'MobBattle_Test_JadeHold_C', 'MobBattle_Test_JadeHold_D', 'MobBattle_Test_JadeHold_E', 'MobBattle_Test_JadeHold_F', 'MobBattle_Test_JadeHold_G', 'MobBattle_Test_JadeHold_H', 'MobBattle_Test_JadeHold_I', 'MobBattle_Test_JadeHold_J', 'MobBattle_Test_JadeOnce_C', 'MobBattle_Test_JadeOnce_H', 'MobBattle_Test_JadeOnce_J', 'MobBattle_Test_ShimmerOnce', 'MobBattle_Test_ShimmerHold', 'MobBattle_Test_JadeAppearHold', 'MobBattle_Test_ShimmerRunes', 'MobBattle_Test_ShimmerRunesBody', 'MobBattle_Test_ShimmerB_Hold', 'MobBattle_Test_ShimmerPaint', 'MobBattle_Test_ShimmerVeinsPaint'}
DURATION = {'MobBattle_Test_JadeAppear': 1.0, 'MobBattle_Test_ShatterA': 0.9, 'MobBattle_Test_ShatterB': 1.3, 'MobBattle_Test_ShatterC': 0.6, 'MobBattle_Test_MeltA': 1.4, 'MobBattle_Test_MeltB': 1.2, 'MobBattle_Test_ShatterJadeA': 0.9, 'MobBattle_Test_ShatterJadeB': 0.9, 'MobBattle_Test_DeathTurnPurple': 0.6, 'MobBattle_Test_DeathTurnGray': 0.6, 'MobBattle_Test_DeathBurst': 0.4, 'MobBattle_Test_JadeAppearC': 1.0, 'MobBattle_Test_DeathTurnPurpleSlow': 2.6, 'MobBattle_Test_DeathBurstSlow': 2.1}


def effect_file(fid, vfx_id, tint, kind):
    app = {}
    if vfx_id:
        app['ModelVFXId'] = vfx_id
    if tint:
        app['EntityTopTint'] = tint[0]
        app['EntityBottomTint'] = tint[1]
    app['Particles'] = [
        {'SystemId': 'MobBattle_Test_' + kind, 'TargetNodeName': 'L-Eye'},
        {'SystemId': 'MobBattle_Test_' + kind, 'TargetNodeName': 'R-Eye'},
    ] if kind else []
    app['Particles'] += EXTRA_PARTICLES.get(fid, [])
    desc = VFX_DESC[vfx_id] if vfx_id else 'eyes only: ' + kind
    data = {'$Comment': comment(desc)}
    if fid in INFINITE:
        data['Infinite'] = True
    else:
        data['Duration'] = DURATION.get(fid, 60)
    data.update({'OverlapBehavior': 'Overwrite', 'Debuff': False, 'ApplicationEffects': app})
    return data


# ---- main -------------------------------------------------------------------

counts = {}


def emit(folder, name, data):
    write(os.path.join(folder, name), data)
    counts[folder] = counts.get(folder, 0) + 1


for kind in EYE_KINDS:
    name = 'MobBattle_Test_' + kind
    emit(SPAWN_DIR, name + '.particlespawner', eye_dot(kind) if kind.startswith('EyeDot') else eye_flame(kind))
    emit(SYSTEM_DIR, name + '.particlesystem', {
        '$Comment': comment('eye particle system %s (L-Eye and R-Eye)' % kind),
        'Spawners': [{'SpawnerId': name, 'PositionOffset': {'X': 0, 'Y': 0, 'Z': 0}}],
        'CullDistance': 64,
    })

for vid, overrides, desc in VFX:
    emit(VFX_DIR, vid + '.json', vfx_file(vid, overrides, desc))

for fid, vfx_id, tint, kind in EFFECTS:
    emit(FX_DIR, fid + '.json', effect_file(fid, vfx_id, tint, kind))

for folder, n in counts.items():
    print(n, 'files in', os.path.relpath(folder, ROOT))
