import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonInteraction,
  ButtonStyle,
  ChatInputCommandInteraction,
  Client,
  EmbedBuilder,
  TextChannel,
} from "discord.js";
import { ObjectId } from "mongodb";
import { config } from "../config.js";
import { Collections, GiveawayDoc } from "../db.js";
import { CH, findText } from "../guild.js";

/**
 * Giveaways: /giveaway start posts an embed with an Enter button in
 * #enter-giveaway. Entrants must have a linked account (winners are paid
 * in-game). A poller draws winners at endsAt and announces them in
 * #daily-winners / #weekly-winners. Re-roll and early-end supported.
 */

const ENTER_PREFIX = "giveaway:enter:";

/** Parse "30m" / "6h" / "2d" / "90s" into milliseconds. */
export function parseDuration(input: string): number | null {
  const m = input.trim().match(/^(\d+)\s*([smhd])$/i);
  if (!m) return null;
  const n = Number(m[1]);
  const unit = m[2].toLowerCase();
  const mult = unit === "s" ? 1000 : unit === "m" ? 60_000 : unit === "h" ? 3_600_000 : 86_400_000;
  return n * mult;
}

function liveEmbed(g: GiveawayDoc): EmbedBuilder {
  const ends = Math.floor(g.endsAt / 1000);
  return new EmbedBuilder()
    .setColor(0x9b59b6)
    .setTitle(`🎉 Giveaway: ${g.prize}`)
    .setDescription(
      `Click **🎉 Enter** below to join!\n\n` +
        `Ends <t:${ends}:R>\nWinners: **${g.winnersCount}**\nEntries: **${g.entrants.length}**`,
    )
    .setFooter({ text: "You must have a linked account to enter — /link" });
}

function endedEmbed(g: GiveawayDoc): EmbedBuilder {
  const winners = (g.winners ?? []).map((id) => `<@${id}>`).join(", ") || "no valid entries";
  return new EmbedBuilder()
    .setColor(0x95a5a6)
    .setTitle(`🎉 Giveaway Ended: ${g.prize}`)
    .setDescription(`Winners: ${winners}\nEntries: **${g.entrants.length}**`)
    .setFooter({ text: "Kingdom of Lumbridge" });
}

function enterRow(id: string): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder().setCustomId(`${ENTER_PREFIX}${id}`).setLabel("Enter").setEmoji("🎉").setStyle(ButtonStyle.Success),
  );
}

export async function startGiveaway(
  interaction: ChatInputCommandInteraction,
  opts: { prize: string; durationMs: number; winners: number; announce: "daily" | "weekly" },
): Promise<void> {
  const guild = interaction.guild!;
  const channel = findText(guild, CH.enterGiveaway);
  if (!channel) {
    await interaction.editReply(`Couldn't find #${CH.enterGiveaway}. Run /setup first.`);
    return;
  }

  const now = Date.now();
  const giveaways = await Collections.giveaways();
  const base: GiveawayDoc = {
    prize: opts.prize,
    hostId: interaction.user.id,
    channelId: channel.id,
    messageId: "",
    winnersCount: Math.max(1, opts.winners),
    endsAt: now + opts.durationMs,
    entrants: [],
    ended: false,
    announce: opts.announce,
    createdAt: now,
  };
  const { insertedId } = await giveaways.insertOne(base);
  const id = insertedId.toHexString();

  const msg = await channel.send({ embeds: [liveEmbed(base)], components: [enterRow(id)] });
  await giveaways.updateOne({ _id: insertedId }, { $set: { messageId: msg.id } });

  await interaction.editReply(`Giveaway for **${opts.prize}** started in <#${channel.id}> — ends <t:${Math.floor(base.endsAt / 1000)}:R>.`);
}

export async function handleEnterButton(interaction: ButtonInteraction): Promise<void> {
  const id = interaction.customId.slice(ENTER_PREFIX.length);
  await interaction.deferReply({ ephemeral: true });

  const giveaways = await Collections.giveaways();
  const g = await giveaways.findOne({ _id: new ObjectId(id) }).catch(() => null);
  if (!g || g.ended) return void interaction.editReply("This giveaway has ended.");

  // Must be linked — winners are paid in-game.
  const links = await Collections.discordLinks();
  const link = await links.findOne({ discordUserId: interaction.user.id });
  if (!link?.loginUsername) {
    return void interaction.editReply("You must link your game account first — run `/link` (see #link-account).");
  }

  if (g.entrants.includes(interaction.user.id)) {
    return void interaction.editReply("You're already entered. Good luck! 🍀");
  }

  await giveaways.updateOne({ _id: g._id }, { $addToSet: { entrants: interaction.user.id } });
  const fresh = await giveaways.findOne({ _id: g._id });
  if (fresh) await updateMessage(interaction.client, fresh);
  await interaction.editReply("You're entered! 🎉 Good luck.");
}

async function updateMessage(client: Client, g: GiveawayDoc): Promise<void> {
  const channel = (await client.channels.fetch(g.channelId).catch(() => null)) as TextChannel | null;
  if (!channel) return;
  const msg = await channel.messages.fetch(g.messageId).catch(() => null);
  if (!msg) return;
  await msg
    .edit(g.ended ? { embeds: [endedEmbed(g)], components: [] } : { embeds: [liveEmbed(g)], components: [enterRow(g._id!.toString())] })
    .catch(() => {});
}

function drawWinners(entrants: string[], count: number): string[] {
  const pool = [...entrants];
  const winners: string[] = [];
  while (winners.length < count && pool.length) {
    const i = Math.floor(Math.random() * pool.length);
    winners.push(pool.splice(i, 1)[0]);
  }
  return winners;
}

async function endGiveaway(client: Client, g: GiveawayDoc): Promise<void> {
  await finalize(client, g, drawWinners(g.entrants, g.winnersCount));
}

/** Persists winners, updates the giveaway message, and announces them. */
async function finalize(client: Client, g: GiveawayDoc, winners: string[]): Promise<void> {
  const giveaways = await Collections.giveaways();
  await giveaways.updateOne({ _id: g._id }, { $set: { ended: true, winners } });
  const ended = { ...g, ended: true, winners };
  await updateMessage(client, ended);

  // Announce in the winners channel.
  const guild = client.guilds.cache.first() ?? (await client.guilds.fetch(config.guildId).catch(() => null));
  if (!guild) return;
  const announceCh = findText(guild, ended.announce === "weekly" ? CH.weeklyWinners : CH.dailyWinners);
  if (!announceCh) return;
  const mention = winners.length ? winners.map((id) => `<@${id}>`).join(", ") : "_no valid entries_";
  await announceCh
    .send({
      content: winners.length ? `Congratulations ${mention}! 🎉` : undefined,
      embeds: [
        new EmbedBuilder()
          .setColor(0x2ecc71)
          .setTitle(`🎉 Winner${winners.length === 1 ? "" : "s"}: ${ended.prize}`)
          .setDescription(`${mention}\n\nFrom **${ended.entrants.length}** entr${ended.entrants.length === 1 ? "y" : "ies"}. Winners: open a ticket to claim if not paid automatically.`)
          .setFooter({ text: "Kingdom of Lumbridge" }),
      ],
    })
    .catch(() => {});
}

let polling = false;

export async function pollGiveaways(client: Client): Promise<void> {
  if (polling) return;
  polling = true;
  try {
    const giveaways = await Collections.giveaways();
    const due = await giveaways.find({ ended: false, endsAt: { $lte: Date.now() } }).toArray();
    for (const g of due) await endGiveaway(client, g);
  } catch (e) {
    console.error("[giveaways] poll error:", e);
  } finally {
    polling = false;
  }
}

export function startGiveawayPoller(client: Client): void {
  setInterval(() => void pollGiveaways(client), 15_000);
  console.log("[giveaways] poller started (15s)");
}

/** Ends the most recent active giveaway now (for /giveaway end). */
export async function endLatest(client: Client): Promise<string> {
  const giveaways = await Collections.giveaways();
  const g = await giveaways.find({ ended: false }).sort({ createdAt: -1 }).limit(1).next();
  if (!g) return "No active giveaway to end.";
  await endGiveaway(client, g);
  return `Ended the giveaway for **${g.prize}** and drew winners.`;
}

/** Re-rolls winners of the most recently ended giveaway (for /giveaway reroll). */
export async function rerollLatest(client: Client): Promise<string> {
  const giveaways = await Collections.giveaways();
  const g = await giveaways.find({ ended: true }).sort({ createdAt: -1 }).limit(1).next();
  if (!g) return "No ended giveaway to re-roll.";
  if (!g.entrants.length) return "That giveaway had no entries to re-roll.";
  await finalize(client, g, drawWinners(g.entrants, g.winnersCount)); // re-announces
  return `Re-rolled **${g.prize}**.`;
}
