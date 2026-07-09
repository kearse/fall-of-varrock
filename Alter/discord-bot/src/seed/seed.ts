import { ActionRowBuilder, ButtonBuilder, ButtonStyle, EmbedBuilder, Guild, TextChannel } from "discord.js";
import { findText } from "../guild.js";
import { SEED, SeedEntry } from "./content.js";

/**
 * Posts the seed content pack into the info channels. Idempotent: a channel is
 * skipped if it already has a bot message whose embed title matches the entry
 * (so re-running /seed won't duplicate). To refresh a channel, delete its
 * message and run /seed again.
 */

export interface SeedResult {
  posted: string[];
  skipped: string[];
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

async function alreadyPosted(channel: TextChannel, title: string): Promise<boolean> {
  const recent = await channel.messages.fetch({ limit: 30 }).catch(() => null);
  if (!recent) return false;
  return recent.some(
    (m) => m.author.id === channel.client.user?.id && m.embeds.some((e) => e.title === title),
  );
}

export async function seedContent(guild: Guild): Promise<SeedResult> {
  const result: SeedResult = { posted: [], skipped: [], missing: [] };

  for (const entry of SEED) {
    const channel = findText(guild, entry.channel);
    if (!channel) {
      result.missing.push(entry.channel);
      continue;
    }
    if (await alreadyPosted(channel, entry.title)) {
      result.skipped.push(entry.channel);
      continue;
    }
    const embed = new EmbedBuilder()
      .setColor(entry.color)
      .setTitle(entry.title)
      .setDescription(entry.body)
      .setFooter({ text: "Kingdom of Lumbridge" });
    await channel
      .send({ embeds: [embed], components: buildComponents(entry) })
      .then(() => result.posted.push(entry.channel))
      .catch(() => result.missing.push(entry.channel));
  }
  return result;
}
