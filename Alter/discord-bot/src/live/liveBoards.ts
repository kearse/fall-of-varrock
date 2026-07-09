import net from "node:net";
import { Client, EmbedBuilder, Message, TextChannel } from "discord.js";
import { config } from "../config.js";
import { Collections } from "../db.js";
import { CH, findText } from "../guild.js";
import { topByTotalLevel } from "../hiscores.js";

/**
 * Self-refreshing dashboards, edited in place every LIVE_BOARD_MS:
 *  - #server-status — world UP/DOWN (TCP probe of the game port; no game code
 *    needed) + live online count from the `online` collection.
 *  - #hiscores      — top-10 by total level from the shared save.
 *
 * Each board is a single pinned bot message that the bot finds (by title) or
 * creates on first run, then edits — so the channel never fills with spam.
 */

const STATUS_TITLE = "📡 Server Status";
const HISCORES_TITLE = "🏆 Hiscores — Total Level";

/** TCP-connect to the game world; resolves true if the port accepts a connection. */
function probeWorld(host: string, port: number, timeoutMs = 4000): Promise<boolean> {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    let settled = false;
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      sock.destroy();
      resolve(ok);
    };
    sock.setTimeout(timeoutMs);
    sock.once("connect", () => finish(true));
    sock.once("timeout", () => finish(false));
    sock.once("error", () => finish(false));
    sock.connect(port, host);
  });
}

async function findOrCreate(channel: TextChannel, title: string): Promise<Message | null> {
  const recent = await channel.messages.fetch({ limit: 20 }).catch(() => null);
  const existing = recent?.find(
    (m) => m.author.id === channel.client.user?.id && m.embeds.some((e) => e.title === title),
  );
  if (existing) return existing;
  const placeholder = new EmbedBuilder().setTitle(title).setDescription("Loading…");
  const msg = await channel.send({ embeds: [placeholder] }).catch(() => null);
  if (msg) await msg.pin().catch(() => {});
  return msg;
}

async function statusEmbed(): Promise<EmbedBuilder> {
  const up = await probeWorld(config.gameHost, config.gamePort);
  const online = await Collections.online()
    .then((c) => c.countDocuments({}))
    .catch(() => 0);
  return new EmbedBuilder()
    .setColor(up ? 0x2ecc71 : 0xe74c3c)
    .setTitle(STATUS_TITLE)
    .addFields(
      { name: "World", value: up ? "🟢 Online" : "🔴 Offline", inline: true },
      { name: "Players online", value: up ? String(online) : "—", inline: true },
    )
    .setFooter({ text: "Kingdom of Lumbridge • updates every minute" })
    .setTimestamp(new Date());
}

async function hiscoresEmbed(): Promise<EmbedBuilder> {
  const rows = await topByTotalLevel(10).catch(() => []);
  const medal = (n: number) => (n === 0 ? "🥇" : n === 1 ? "🥈" : n === 2 ? "🥉" : `**${n + 1}.**`);
  return new EmbedBuilder()
    .setColor(0xf1c40f)
    .setTitle(HISCORES_TITLE)
    .setURL(`${config.siteUrl}/hiscores`)
    .setDescription(
      rows.length
        ? rows.map((r, n) => `${medal(n)} ${r.displayName} — **${r.totalLevel.toLocaleString()}** (cb ${r.combat})`).join("\n")
        : "No ranked players yet.",
    )
    .setFooter({ text: "Kingdom of Lumbridge • updates every minute" })
    .setTimestamp(new Date());
}

let statusMsg: Message | null = null;
let hiscoresMsg: Message | null = null;
let running = false;

async function tick(client: Client): Promise<void> {
  if (running) return;
  running = true;
  try {
    const guild = client.guilds.cache.first() ?? (await client.guilds.fetch(config.guildId).catch(() => null));
    if (!guild) return;

    const statusCh = findText(guild, CH.serverStatus);
    if (statusCh) {
      if (!statusMsg || statusMsg.channelId !== statusCh.id) statusMsg = await findOrCreate(statusCh, STATUS_TITLE);
      if (statusMsg) await statusMsg.edit({ embeds: [await statusEmbed()] }).catch(() => (statusMsg = null));
    }

    const hiscoresCh = findText(guild, CH.hiscores);
    if (hiscoresCh) {
      if (!hiscoresMsg || hiscoresMsg.channelId !== hiscoresCh.id) hiscoresMsg = await findOrCreate(hiscoresCh, HISCORES_TITLE);
      if (hiscoresMsg) await hiscoresMsg.edit({ embeds: [await hiscoresEmbed()] }).catch(() => (hiscoresMsg = null));
    }
  } catch (e) {
    console.error("[liveBoards] tick error:", e);
  } finally {
    running = false;
  }
}

export function startLiveBoards(client: Client): void {
  void tick(client);
  setInterval(() => void tick(client), config.liveBoardMs);
  console.log(`[liveBoards] refreshing #${CH.serverStatus} + #${CH.hiscores} every ${config.liveBoardMs}ms`);
}
