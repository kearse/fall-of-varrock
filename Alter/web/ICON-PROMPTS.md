# Fall of Varrock - icon art spec

Generate these, drop them at the listed paths, and ping me to wire each set (same
loop as the logos / feature cards). Make every icon **square (1:1) on a solid pure-black
background** so I can key the black to transparent, matching the feature-card art.

## Shared style anchor (paste into EVERY prompt)

> A single centered game icon on a solid pure black background. Dark obsidian and
> basalt material with glowing molten-lava cracks and red-orange ember light, brushed
> steel/silver edges and rivets, dark-fantasy RuneScape private server UI style, high
> detail, dramatic rim light, 1:1 square, no text, no words, no watermark, no border.

Keep the subject tight and centered with a little padding (it renders small).

---

## 1. Store category icons (4)  -> `Alter/web/public/img/`

| Filename | Subject prompt (append to the style anchor) |
| --- | --- |
| `store-bonds.png` | an ornate premium "bond" token: a rounded obsidian-and-steel coin/tablet with a glowing lava rune sigil carved in its center |
| `store-membership.png` | a members' medallion on a chain: an obsidian shield-badge with lava filigree and a single faceted red gem at its heart |
| `store-donator.png` | an overflowing treasure chest of glowing gold coins and red gems, obsidian and steel banding with lava glow spilling out |
| `store-bundles.png` | a bulging loot sack bound with steel chain, obsidian cloth with lava cracks, a few glowing coins and a sword hilt poking out |

## 2. Hiscores PvP category icons (5)  -> `Alter/web/public/img/rank/`

(Create the `rank` subfolder.)

| Filename | Subject prompt (append to the style anchor) |
| --- | --- |
| `overall.png` | a laurel-wreathed champion's crest / medallion, obsidian and steel with lava glow |
| `kills.png` | two crossed swords over a horned skull, obsidian blades with molten cutting edges |
| `deaths.png` | a cracked gravestone with a broken sword stuck in it, cold obsidian with fading ember embers |
| `kdr.png` | a balance scale weighing a skull against a sword, obsidian frame with glowing lava pans |
| `elo.png` | a ranked emblem: a pointed shield stamped with three ascending chevrons and a small crown, lava glow |

## 3. Forum section icons (5)  -> `Alter/web/public/img/forum/`

(Create the `forum` subfolder. These become each top-level section's image in NodeBB.)

| Filename | Section | Subject prompt (append to the style anchor) |
| --- | --- | --- |
| `official.png` | Fall of Varrock / Announcements | a herald's proclamation: a crown resting on a furled banner or wax-sealed scroll, obsidian with lava glow |
| `general.png` | General | a round-table emblem or two crossed tankards, obsidian and steel with lava glow |
| `war.png` | The War | a battle standard: crossed war banners over a spearhead, obsidian with molten-lava cloth |
| `media.png` | Media | a framed portrait / an all-seeing eye rune inside a diamond, obsidian with lava glow |
| `support.png` | Support | a life-ring crossed with a helping gauntlet, or a warded shield, obsidian with lava glow |

## 4. OSRS skill icons (23)  -> `Alter/web/public/img/skills/`

For skills we use the **official OSRS skill icons** (players recognize them instantly).
Grab them from the OSRS Wiki (search "<Skill> icon") or your game cache, and save each
as a small PNG (transparent, ~40-64px is plenty) named exactly:

```
attack.png     defence.png    strength.png   hitpoints.png  ranged.png
prayer.png     magic.png      cooking.png    woodcutting.png fletching.png
fishing.png    firemaking.png crafting.png   smithing.png   mining.png
herblore.png   agility.png    thieving.png   slayer.png     farming.png
runecraft.png  hunter.png     construction.png
```

Drop all 23 in `Alter/web/public/img/skills/` and I'll wire them into the hiscores
skill rail and every row.

---

### When you're done
Drop the files in the paths above and tell me which set(s) are ready. I'll:
- key the black -> transparent + optimize the store / PvP / forum icons,
- wire the store cards, the hiscores rail + PvP columns, and set the NodeBB section images,
- and map the 23 skill icons into the hiscores page.
