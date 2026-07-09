# Fall of Varrock — AI image prompts & drop-in guide

The site is wired to use AI-generated art the moment you drop the files in. Until a
file exists, the animated blood-moon/ember scene (`HeroScene.tsx`) shows as the
fallback — so nothing ever looks empty.

## Where to put images

Save generated files here (create the folder if missing):

| File | Used for | Recommended size |
| --- | --- | --- |
| `Alter/web/public/img/hero-bg.jpg` | Full-bleed home hero background (behind the logo) | 2400×1350 (16:9), < 500 KB |
| `Alter/web/public/img/og-image.jpg` | Social/Discord share card (optional) | 1200×630 |

Save as **`.jpg`** with those exact names. After adding `hero-bg.jpg`, hard-refresh
the home page — it appears automatically, dimmed under the text scrim.
(Before the file exists you'll see one harmless `404 /img/hero-bg.jpg` in the
network tab — that's expected; the animated scene covers it.)

Want more image slots (feature-section backgrounds, a store banner, per-page
headers)? Ask and I'll wire them the same way.

## Style anchor (paste into every prompt)

> dark high-fantasy concept art, apocalyptic ruined medieval city, RuneScape /
> Old School RuneScape art direction, blood-red moon, drifting embers and ash,
> smoke haze, cinematic wide shot, volumetric light, desaturated with crimson and
> ember-orange glow, painterly but detailed, no text, no watermark, no UI

Aim for **landscape 16:9**. Keep the composition darker in the center/top so the
white headline stays readable (the site also adds a dark gradient scrim).

---

## Hero background — pick one

**A. Varrock square in ruin (recommended)**
> A ruined Varrock town square at night under a huge blood-red moon, the central
> stone fountain cracked and dry, cobblestone streets strewn with rubble and broken
> market stalls, medieval stone buildings gutted by fire with collapsed roofs and
> glowing embers rising, distant Varrock castle silhouette burning on the horizon,
> abandoned and desolate, thin figures of hooded rogues in the shadows, ash falling
> like snow. Dark high-fantasy concept art, RuneScape art direction, cinematic wide
> shot, crimson and ember glow, volumetric moonlight, no text, no UI.

**B. Fallen Varrock castle**
> The great castle of Varrock half-collapsed and burning under a blood-red moon,
> shattered towers and a broken portcullis, banners torn and scorched, smoke pouring
> into a red-black sky, embers and ash in the air, a ruined kingdom, epic and grim.
> Dark high-fantasy matte painting, RuneScape / OSRS style, cinematic wide 16:9,
> crimson and ember light, no text, no watermark.

**C. The last road to Lumbridge**
> A desolate war-torn road winding from ruined Varrock toward a distant, faintly-lit
> Lumbridge castle holding out on the horizon, wrecked wagons and broken palisades,
> undead and rogues lurking in the fog, a blood-red moon overhead, embers drifting,
> the last light of a dying kingdom. Dark fantasy concept art, OSRS art direction,
> cinematic wide shot, crimson glow, no text, no UI.

## Optional — social share card (`og-image.jpg`, 1200×630)
> Fall of Varrock key art: a burning ruined Varrock skyline under a blood-red moon,
> embers and ash, dark crimson atmosphere, leave the lower third darker and clear for
> a title overlay. Dark high-fantasy concept art, cinematic, no text, no watermark.

## Tips
- **Keep the blood moon** — it ties the hero to the site's fallback scene and theme.
- Generate 16:9; export/compress to JPG (TinyPNG / Squoosh) so the page stays fast.
- If you'd rather use a `.png` or `.webp`, save it and tell me — I'll point the code
  at whatever filename you use.
