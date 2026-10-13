"""Gives every Mob Battle effect with particles the invisible first-person version (MobBattle_Nothing), so a player
wearing a look, crown, buff light or wand effect doesn't get it in their own camera; everyone else still sees it.

An empty "FirstPersonParticles": [] is not enough: the client falls back to Particles (2026-10-12, user: the Buff
Wand runes and the jade eyes covered a buffed player's view). Vanilla does the same with its own FP systems
(Hazard_Electric_Stun -> Hazard_Electric_Stun_FirstPerson).

Idempotent. Run it after gen-looks.py / gen-crowns.py / any new effect (they already write it, this covers the
hand-made ones: Mark, Swirl, BuffTake, Fx/*, Team).
    python scripts/hide-first-person.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'Server', 'Entity', 'Effects', 'MobBattle')
NOTHING = [{'SystemId': 'MobBattle_Nothing'}]

changed = seen = 0
for folder, _, files in os.walk(ROOT):
    for name in files:
        if not name.endswith('.json'):
            continue
        path = os.path.join(folder, name)
        with open(path, encoding='utf-8') as f:
            text = f.read()
        data = json.loads(text)
        apply = data.get('ApplicationEffects')
        if not isinstance(apply, dict) or not apply.get('Particles'):
            continue
        seen += 1
        if apply.get('FirstPersonParticles') == NOTHING:
            continue
        # Keep the key order: FirstPersonParticles right after Particles.
        new = {}
        for k, v in apply.items():
            if k == 'FirstPersonParticles':
                continue
            new[k] = v
            if k == 'Particles':
                new['FirstPersonParticles'] = NOTHING
        data['ApplicationEffects'] = new
        out = json.dumps(data, indent=2, ensure_ascii=False)
        if text.endswith('\n'):
            out += '\n'
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(out)
        changed += 1
print('%d effects with particles, %d changed' % (seen, changed))
