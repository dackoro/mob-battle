"""
Generates the crown and Cleanse looks in every palette color (run with: python scripts/gen-crowns.py).

Source: the game's gold crown, Server/Particles/Status_Effect/Crown_Gold in Assets.zip (Effect_Crown_Gold: rays of
light coming down from above the head, a glow, sparks and stars; generic textures, so the gold is only in the
"Color" values). For each palette color (Teams.PALETTE) it writes:

  Server/Particles/MobBattle/Spawners/MobBattle_Crown_<Color>_<part>.particlespawner   the 9 spawners, recolored
  Server/Particles/MobBattle/MobBattle_Crown_<Color>.particlesystem                    the whole crown
  Server/Particles/MobBattle/MobBattle_Light_<Color>.particlesystem                    only the light from above
  Server/Entity/Effects/MobBattle/Fx/Crown/MobBattle_Fx_Crown_<Color>.json             lone target crown (20 s)
  Server/Entity/Effects/MobBattle/Fx/Crown/MobBattle_Fx_CrownBuff_<Color>.json         buffed mobs (until removed)
  Server/Entity/Effects/MobBattle/Fx/Cleanse/MobBattle_Fx_Cleanse_<Color>.json         Cleanse Wand look
  Server/Entity/Effects/MobBattle/Fx/Buff/MobBattle_Fx_BuffLight_<Color>.json          Buff Wand: only the light (buffLight)

Both crown effects also come in sizes for size-buffed mobs: <name>_S150, _S200, _S250, _S300 (particle Scale 1.5 to
3; the crown is attached to the Head node but its particles don't grow with the entity scale). Buffs and WandFx
pick the one nearest to the mob's scale (WandFx.crownSize).

The Cleanse and Buff light effects come in mob heights: <name>_H1, _H3, _H4, _H6, _H8 (no suffix = 2 blocks). The
light is attached to the entity root, not the Head node: a tilted head (Goblin_Feastmaster's leans ~40 degrees) turned
the system's 3-block "up" offset sideways and the walk animation swung it around, so the light fell behind the mob.
The root stays upright and moves with the mob; the offset puts the light where the head would be (height - 0.5).
WandFx.lightHeight picks the one nearest to the mob's hitbox top (scale included).

Gold (the game's own crown, Effect_Crown_Gold) gets the two crown effects too. Black is near invisible: the
particles blend additively.
"""
import json
import os
import re
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.expandvars(r'%APPDATA%\Hytale\install\pre-release\package\game\latest\Assets.zip')
SOURCE = 'Server/Particles/Status_Effect/Crown_Gold/'
LIGHT_PARTS = ('Crown_Gold_Rays', 'Crown_Gold_Ray_Gradient', 'Crown_Gold_Rays_Sparks')

# Teams.PALETTE
PALETTE = {
    'Red': '#FF4B3E', 'Blue': '#4B9BFF', 'Green': '#4BFF6A', 'Yellow': '#FFD23E', 'Orange': '#FF8C1A',
    'Purple': '#A24BFF', 'Pink': '#FF6BD5', 'Cyan': '#3EF0FF', 'White': '#F2F2F2', 'Black': '#2A2A2A',
    'Brown': '#8B5A2B', 'Lime': '#B6FF3E', 'Gray': '#808080', 'Magenta': '#FF00FF',
}

particles = os.path.join(ROOT, 'Server', 'Particles', 'MobBattle')
spawners = os.path.join(particles, 'Spawners')
crown_fx = os.path.join(ROOT, 'Server', 'Entity', 'Effects', 'MobBattle', 'Fx', 'Crown')
# Cleanse/Buff light heights in blocks (WandFx.LIGHT_HEIGHTS); 2 has no suffix.
HEIGHTS = (1, 2, 3, 4, 6, 8)
cleanse_fx = os.path.join(ROOT, 'Server', 'Entity', 'Effects', 'MobBattle', 'Fx', 'Cleanse')
marks = os.path.join(ROOT, 'Server', 'Entity', 'Effects', 'MobBattle', 'Fx', 'Mark')
buff_fx = os.path.join(ROOT, 'Server', 'Entity', 'Effects', 'MobBattle', 'Fx', 'Buff')
os.makedirs(buff_fx, exist_ok=True)
os.makedirs(crown_fx, exist_ok=True)
os.makedirs(cleanse_fx, exist_ok=True)


def rgb(h):
    return [int(h[i:i + 2], 16) for i in (1, 3, 5)]


def recolor(vanilla, palette):
    """Keeps how pale and how bright the gold was, in the palette color: white stays white, gold becomes the color."""
    v, p = rgb(vanilla), rgb(palette)
    white = min(v) / 255
    bright = max(v) / 255
    out = [round((c * (1 - white) + 255 * white) * bright) for c in p]
    return '#' + ''.join('%02x' % max(0, min(255, c)) for c in out)


def write(path, data):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(data if isinstance(data, str) else json.dumps(data, indent=2) + '\n')


SIZES = (150, 200, 250, 300)


def crown_effect(system, comment, infinite, size=100):
    fx = {'$Comment': comment if size == 100 else comment.replace(' Generated', ' Size x%g for size-buffed mobs. Generated' % (size / 100))}
    if infinite:
        fx['Infinite'] = True
    else:
        fx['Duration'] = 20.0
    particle = {'SystemId': system, 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Head'}
    if size != 100:
        particle['Scale'] = size / 100
    # Nothing in first person: on a player the crown on the Head node sits in the camera (2026-10-12).
    fx['ApplicationEffects'] = {'Particles': [particle], 'FirstPersonParticles': []}
    fx['OverlapBehavior'] = 'Overwrite'
    fx['Debuff'] = False
    return fx


z = zipfile.ZipFile(ASSETS)
parts = {n.split('/')[-1][:-len('.particlespawner')]: z.read(n).decode('utf-8')
         for n in z.namelist() if n.startswith(SOURCE) and n.endswith('.particlespawner')}
system = json.loads(z.read(SOURCE + 'Effect_Crown_Gold.particlesystem').decode('utf-8'))

for color, hexc in PALETTE.items():
    def spawner_id(part):
        return 'MobBattle_' + part.replace('Crown_Gold', 'Crown_' + color)

    for part, text in parts.items():
        text = re.sub(r'("Color"\s*:\s*")(#[0-9a-fA-F]{6})"', lambda m: m.group(1) + recolor(m.group(2), hexc) + '"', text)
        write(os.path.join(spawners, spawner_id(part) + '.particlespawner'), text)
    whole = [dict(s, SpawnerId=spawner_id(s['SpawnerId'])) for s in system['Spawners']]
    light = [s for s in whole if any(s['SpawnerId'] == spawner_id(p) for p in LIGHT_PARTS)]
    write(os.path.join(particles, 'MobBattle_Crown_%s.particlesystem' % color),
          {'$Comment': 'The game\'s gold crown (Effect_Crown_Gold) in %s. Generated by scripts/gen-crowns.py.' % color, 'Spawners': whole})
    write(os.path.join(particles, 'MobBattle_Light_%s.particlesystem' % color),
          {'$Comment': 'Only the light coming down from above of the crown, in %s (Cleanse Wand). Generated by scripts/gen-crowns.py.' % color,
           'Spawners': light})
    for size in (100,) + SIZES:
        suffix = '' if size == 100 else '_S%d' % size
        write(os.path.join(crown_fx, 'MobBattle_Fx_Crown_%s%s.json' % (color, suffix)),
              crown_effect('MobBattle_Crown_' + color, 'Everyone vs one: %s crown over the lone target (/mobbattle crown). Generated by scripts/gen-crowns.py.' % color, False, size))
        write(os.path.join(crown_fx, 'MobBattle_Fx_CrownBuff_%s%s.json' % (color, suffix)),
              crown_effect('MobBattle_Crown_' + color, 'Buffed mob (kill streak, champion): %s crown until the buff goes (/mobbattle buff crown). Generated by scripts/gen-crowns.py.' % color, True, size))
    mark = json.load(open(os.path.join(marks, 'MobBattle_Fx_Mark_%s.json' % color), encoding='utf-8'))
    for height in HEIGHTS:
        suffix = '' if height == 2 else '_H%d' % height
        light_particle = {'SystemId': 'MobBattle_Light_' + color, 'TargetEntityPart': 'Entity',
                          'PositionOffset': {'Y': height - 0.5}}
        write(os.path.join(cleanse_fx, 'MobBattle_Fx_Cleanse_%s%s.json' % (color, suffix)), {
            '$Comment': 'Cleanse Wand in %s (/mobbattle cleanseColor), mob about %d blocks tall: the crown light from above, burst, aura and rune at the feet. Generated by scripts/gen-crowns.py.' % (color, height),
            'Duration': 2.5,
            'ApplicationEffects': {
                'EntityTopTint': mark['ApplicationEffects']['EntityTopTint'],
                'EntityBottomTint': mark['ApplicationEffects']['EntityBottomTint'],
                'Particles': [
                    light_particle,
                    {'SystemId': 'MobBattle_Mark_Burst_' + color, 'TargetEntityPart': 'Entity', 'TargetNodeName': 'Pelvis'},
                    {'SystemId': 'MobBattle_Mark_Aura_' + color, 'TargetEntityPart': 'Entity'},
                    {'SystemId': 'MobBattle_Mark_Rune_' + color, 'TargetEntityPart': 'Entity'},
                ],
                # No sound here: CleanseInteraction plays it on every click (an effect put on again within a moment
                # did not replay its sound on quick clicks).
            },
            'OverlapBehavior': 'Overwrite',
            'Debuff': False,
        })

        write(os.path.join(buff_fx, 'MobBattle_Fx_BuffLight_%s%s.json' % (color, suffix)), {
            '$Comment': 'Buff Wand in %s (/mobbattle buffLight), mob about %d blocks tall: only the light of the crown coming down on the mob; the buff lands with it '
                        '(BuffGiveInteraction). No tint. Generated by scripts/gen-crowns.py.' % (color, height),
            'Duration': 1.0,
            'ApplicationEffects': {
                'Particles': [light_particle],
            },
            'OverlapBehavior': 'Overwrite',
            'Debuff': False,
        })

for size in (100,) + SIZES:
    suffix = '' if size == 100 else '_S%d' % size
    write(os.path.join(crown_fx, 'MobBattle_Fx_Crown_Gold%s.json' % suffix),
          crown_effect('Effect_Crown_Gold', 'Everyone vs one: the game\'s gold crown over the lone target (/mobbattle crown gold, default). Generated by scripts/gen-crowns.py.', False, size))
    write(os.path.join(crown_fx, 'MobBattle_Fx_CrownBuff_Gold%s.json' % suffix),
          crown_effect('Effect_Crown_Gold', 'Buffed mob: the game\'s gold crown until the buff goes (/mobbattle buff crown gold). Generated by scripts/gen-crowns.py.', True, size))
print('crowns and cleanse looks for', len(PALETTE), 'colors + gold')
