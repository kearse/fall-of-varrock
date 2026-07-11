import { ActionRowBuilder, ButtonBuilder, ButtonStyle, EmbedBuilder, Guild, Message, TextChannel } from "discord.js";
import { findText } from "../guild.js";
import { SEED, SeedEntry } from "./content.js";

/**
 * Posts the seed content pack into the info channels. Idempotent AND
 * self-refreshing: a channel that already has a bot message whose embed title
 * matches the entry gets that message EDITED with the current copy + buttons
 * (so re-running /seed after changing content.ts, SITE_URL or DOWNLOAD_* fixes
 * every channel in place — no deleting needed). Channels without a match get a
 * fresh post.
 */

export interface SeedResult {
  posted: string[];
  updated: string[];
  missing: string[]; // channels that don't exist (run /setup first)
}

function buildComponents(entry: SeedEntry): ActionRowBuilder<ButtonBuilder>[] {
  const links = (entry.links ?? []).filter((l) => l.url && /^https?:\/\//.test(l.url));
  if (!links.length) return [];
  const row = new ActionRowBuilder<ButtonBuilder>();
  for (const l of links.slice(0, 5)) {
    const btn = new ButtonBuilder().setStyle(ButtonStyle.Link).setLabel(l.label).setURL(l.url);
    if (l.emoji) btn.setEmoji(l.emoji);
    row.addComponents(btn);
  }
  return [row];
}

async function findExisting(channel: TextChannel, title: string): Promise<Message | null> {
  const recent = await channel.messages.fetch({ limit: 30 }).catch(() => null);
  if (!recent) return null;
  return (
    recent.find(
      (m) => m.author.id === channel.client.user?.id && m.embeds.some((e) => e.title === title),
    ) ?? null
  );
}

export async function seedContent(guild: Guild): Promise<SeedResult> {
  const result: SeedResult = { posted: [], updated: [], missing: [] };

  for (const entry of SEED) {
    const channel = findText(guild, entry.channel);
    if (!channel) {
      result.missing.push(entry.channel);
      continue;
    }
    const embed = new EmbedBuilder()
      .setColor(entry.color)
      .setTitle(entry.title)
      .setDescription(entry.body)
      .setFooter({ text: "Fall of Varrock" });
    const payload = { embeds: [embed], components: buildComponents(entry) };

    const existing = await findExisting(channel, entry.title);
    if (existing) {
      await existing
        .edit(payload)
        .then(() => result.updated.push(entry.channel))
        .catch(() => result.missing.push(entry.channel));
    } else {
      await channel
        .send(payload)
        .then(() => result.posted.push(entry.channel))
        .catch(() => result.missing.push(entry.channel));
    }
  }
  return result;
}
