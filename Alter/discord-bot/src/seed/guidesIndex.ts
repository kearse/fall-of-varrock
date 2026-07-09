import { ActionRowBuilder, ButtonBuilder, ButtonStyle, EmbedBuilder, TextChannel } from "discord.js";
import { config } from "../config.js";

/**
 * The #guides index: a single message with a card per guide and a row of
 * "Read full guide ↗" link buttons pointing at the website (SITE_URL/guides/<slug>),
 * which is the single source of truth for long-form guides. Idempotent (matched
 * by the header embed title), like the other panels. Slugs MUST match
 * web/scripts/seed-guides.ts.
 */

const HEADER_TITLE = "📖 Guides — Kingdom of Lumbridge";

interface GuideLink {
  title: string;
  emoji: string;
  slug: string;
  summary: string;
}

// Core 5 — keep slugs in sync with the website seed.
export const GUIDES: GuideLink[] = [
  {
    title: "Getting Started",
    emoji: "🧭",
    slug: "getting-started",
    summary: "Spawn, the Recruit Trials, the Lumbridge shop hub, teleports, and your first rank.",
  },
  {
    title: "The War & Campaigns",
    emoji: "⚔️",
    slug: "the-war-and-campaigns",
    summary: "Raids, campaigns, and conquests — how to command troops and split the spoils.",
  },
  {
    title: "Feudal Power & Riches",
    emoji: "👑",
    slug: "feudal-power-and-riches",
    summary: "The rank ladder, what each title unlocks, and how to fund your climb.",
  },
  {
    title: "PKing & the Wilderness",
    emoji: "💀",
    slug: "pking-and-the-wilderness",
    summary: "Wilderness zones, item risk, the Elo ladder, and Blood Money.",
  },
  {
    title: "Making Money",
    emoji: "🪙",
    slug: "making-money",
    summary: "Currencies, the shop economy, bossing, Slayer contracts, and money-makers.",
  },
];

function guideUrl(slug: string): string {
  return `${config.siteUrl}/guides/${slug}`;
}

export async function postGuidesIndexIfMissing(channel: TextChannel): Promise<boolean> {
  const recent = await channel.messages.fetch({ limit: 25 }).catch(() => null);
  const exists = recent?.some(
    (m) => m.author.id === channel.client.user?.id && m.embeds.some((e) => e.title === HEADER_TITLE),
  );
  if (exists) return false;

  const header = new EmbedBuilder()
    .setColor(0xd4af37)
    .setTitle(HEADER_TITLE)
    .setDescription("Everything you need to know, kept up to date on the website. Tap a guide below to read it.");

  const cards = GUIDES.map((g) =>
    new EmbedBuilder().setColor(0x3498db).setTitle(`${g.emoji} ${g.title}`).setDescription(g.summary).setURL(guideUrl(g.slug)),
  );

  // Up to 5 link buttons in one row — exactly our core 5.
  const row = new ActionRowBuilder<ButtonBuilder>();
  for (const g of GUIDES.slice(0, 5)) {
    row.addComponents(new ButtonBuilder().setStyle(ButtonStyle.Link).setEmoji(g.emoji).setLabel(g.title).setURL(guideUrl(g.slug)));
  }

  await channel.send({ embeds: [header, ...cards], components: [row] });
  return true;
}
