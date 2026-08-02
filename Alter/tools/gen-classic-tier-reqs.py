#!/usr/bin/env python3
"""Generate classic tier equip-requirement YAML overrides from item.rscm.

The rev-228 cache only carries equip skill params (434/436) on ~618 items —
rune-tier and above, d'hide, mystic, etc. Sub-rune metal gear has NONE, so the
server has no data to enforce. These overrides supply the classic values.
"""
import re, collections, pathlib

RSCM = "../data/cfg/rscm/item.rscm"
OUT = "../data/cfg/items/itemOverrides/classicTierReqs"

TIER_LEVEL = {"steel": 5, "black": 10, "white": 10, "mithril": 20, "adamant": 30, "rune": 40}

DEF_BASES = [
    "med_helm", "full_helm", "full_helm_t", "full_helm_g",
    "chainbody", "platebody", "platebody_t", "platebody_g",
    "platelegs", "platelegs_t", "platelegs_g",
    "plateskirt", "plateskirt_t", "plateskirt_g",
    "sq_shield", "kiteshield", "kiteshield_t", "kiteshield_g", "boots",
]
ATK_BASES = [
    "dagger", "daggerp", "sword", "longsword", "scimitar", "mace", "warhammer",
    "battleaxe", "2h_sword", "axe", "felling_axe", "halberd",
    "spear", "spearp", "spearkp", "hasta", "hastap", "claws", "pickaxe",
]
RNG_BASES = ["knife", "knifep", "dart", "dartp", "thrownaxe"]
BOTH_BASES = ["defender", "defender_l"]  # attack AND defence at tier level

names = {}
for line in open(RSCM):
    line = line.strip()
    if not line or ":" not in line:
        continue
    name, _, iid = line.rpartition(":")
    names[name] = int(iid)

def variants(tier, base):
    """exact name plus id-suffixed duplicates (p+ / p++ share the base rscm name)"""
    out = []
    pat = re.compile(rf"^{re.escape(tier)}_{re.escape(base)}(_[0-9]+)?$")
    for n, i in names.items():
        if pat.match(n) and "_noted" not in n:
            out.append((n, i))
    return sorted(out, key=lambda t: t[1])

def doc(name, iid, reqs):
    lines = [f"# {name}", f"id: {iid}", "tradeable: true", "", "equipment:", "  skillReqs:"]
    for skill, lvl in reqs:
        lines.append(f'    - skill: "{skill}"')
        lines.append(f"      level: {lvl}")
    return "\n".join(lines)

pathlib.Path(OUT).mkdir(parents=True, exist_ok=True)
total = 0
for tier, lvl in TIER_LEVEL.items():
    docs = []
    for base in DEF_BASES:
        for n, i in variants(tier, base):
            docs.append(doc(n, i, [("defence", lvl)]))
    for base in ATK_BASES:
        for n, i in variants(tier, base):
            docs.append(doc(n, i, [("attack", lvl)]))
    for base in RNG_BASES:
        for n, i in variants(tier, base):
            docs.append(doc(n, i, [("ranged", lvl)]))
    for base in BOTH_BASES:
        for n, i in variants(tier, base):
            docs.append(doc(n, i, [("attack", lvl), ("defence", lvl)]))
    if not docs:
        continue
    header = (
        f"# Classic equip requirements for {tier}-tier gear (level {lvl}).\n"
        f"# GENERATED from item.rscm names — the rev-228 cache carries no skill\n"
        f"# params on sub-rune metal gear, so without these overrides the server\n"
        f"# has nothing to enforce. tradeable: true preserves the cache flag\n"
        f"# (override docs default tradeable to false).\n"
    )
    body = "\n---\n".join(docs)
    p = pathlib.Path(OUT) / f"{tier}.yml"
    p.write_text(header + body + "\n")
    total += len(docs)
    print(f"{tier}: {len(docs)} docs -> {p}")
print("total docs:", total)
