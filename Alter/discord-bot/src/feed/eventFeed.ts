import { Client, EmbedBuilder, TextChannel } from "discord.js";
import { channelForEventKind, config } from "../config.js";
import { Collections, DiscordEventDoc } from "../db.js";

/**
 * Polls the `discord_events` queue the game writes to (via the Kotlin
 * DiscordBridge) and posts each as an embed in the channel mapped to its kind.
 * Marks `posted: true` so each event is delivered exactly once.
 */

const COLOR: Record<string, number> = {
  level99: 0xf1c40f,
  levelMilestone: 0x3498db,
  achievement: 0x2ecc71,
  drop: 0x9b59b6,
  pk: 0xe74c3c,
  boss: 0xe67e22,
  raid: 0xe67e22,
  war: 0xc0392b,
  status: 0x95a5a6,
  boot: 0x2ecc71,
  shutdown: 0x95a5a6,
};

const EMOJI: Record<string, string> = {
  level99: "🎉",
  levelMilestone: "📈",
  achievement: "🏆",
  drop: "💎",
  pk: "⚔️",
  boss: "🐲",
  raid: "🏰",
  war: "🚩",
  status: "📡",
  boot: "🟢",
  shutdown: "🔴",
};

function buildEmbed(ev: DiscordEventDoc): EmbedBuilder {
  const emoji = EMOJI[ev.kind] ?? "📰";
  const embed = new EmbedBuilder()
    .setColor(COLOR[ev.kind] ?? 0x5865f2)
    .setTitle(`${emoji} ${ev.title}`)
    .setTimestamp(new Date(ev.createdAt));
  if (ev.description) embed.setDescription(ev.description);
  if (ev.player) embed.setAuthor({ name: ev.player });
  if (ev.fields?.length) embed.addFields(ev.fields.slice(0, 25));
  embed.setFooter({ text: "Kingdom of Lumbridge" });
  return embed;
}

let running = false;

export async function pollEvents(client: Client): Promise<void> {
  if (running) return; // never overlap a slow poll with the next tick
  running = true;
  try {
    const events = await Collections.discordEvents();
    const batch = await events
      .find({ posted: { $ne: true } })
      .sort({ createdAt: 1 })
      .limit(20)
      .toArray();

    for (const ev of batch) {
      const channelId = channelForEventKind(ev.kind);
      if (!channelId) {
        // No channel configured for this kind — mark posted so it isn't retried forever.
        await events.updateOne({ _id: ev._id }, { $set: { posted: true, postedAt: Date.now() } });
        continue;
      }
      const channel = await client.channels.fetch(channelId).catch(() => null);
      if (channel && channel.isTextBased()) {
        await (channel as TextChannel).send({ embeds: [buildEmbed(ev)] }).catch((e) => {
          console.error(`[eventFeed] send failed for ${ev.kind}:`, e?.message ?? e);
        });
      }
      await events.updateOne({ _id: ev._id }, { $set: { posted: true, postedAt: Date.now() } });
    }
  } catch (e) {
    console.error("[eventFeed] poll error:", e);
  } finally {
    running = false;
  }
}

export function startEventFeed(client: Client): void {
  setInterval(() => void pollEvents(client), config.feedPollMs);
  console.log(`[eventFeed] polling discord_events every ${config.feedPollMs}ms`);
}
