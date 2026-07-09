import {
  ChatInputCommandInteraction,
  EmbedBuilder,
  GuildMember,
  PermissionFlagsBits,
  SlashCommandBuilder,
} from "discord.js";
import { config } from "./config.js";
import { Collections } from "./db.js";
import { getProfile, topByTotalLevel } from "./hiscores.js";
import { linkByCode, unlink, whoami } from "./linking/link.js";
import { syncAllRoles } from "./roles/sync.js";
import { runSetup } from "./setup/serverSetup.js";
import { postTicketPanelIfMissing } from "./tickets/tickets.js";
import { seedContent } from "./seed/seed.js";
import { postGuidesIndexIfMissing } from "./seed/guidesIndex.js";
import { postRolesPanelIfMissing } from "./engagement/engagement.js";
import { endLatest, parseDuration, rerollLatest, startGiveaway } from "./giveaways/giveaways.js";
import { CH, findText } from "./guild.js";
import { ChannelType, TextChannel } from "discord.js";

/** Command definitions, in the JSON shape Discord's API expects. */
export const COMMANDS = [
  new SlashCommandBuilder()
    .setName("link")
    .setDescription("Link your Discord to your Fall of Varrock account")
    .addStringOption((o) => o.setName("code").setDescription("The link code from the website").setRequired(true)),
  new SlashCommandBuilder().setName("unlink").setDescription("Unlink your game account from Discord"),
  new SlashCommandBuilder().setName("whoami").setDescription("Show which game account you're linked to"),
  new SlashCommandBuilder()
    .setName("player")
    .setDescription("Look up a player's stats")
    .addStringOption((o) => o.setName("name").setDescription("In-game display name").setRequired(true)),
  new SlashCommandBuilder()
    .setName("hiscores")
    .setDescription("Top players by total level")
    .addIntegerOption((o) => o.setName("limit").setDescription("How many (1-15)").setMinValue(1).setMaxValue(15)),
  new SlashCommandBuilder().setName("online").setDescription("Who's online right now"),
  new SlashCommandBuilder()
    .setName("sync")
    .setDescription("(Staff) Re-sync all donor/member/staff roles")
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageRoles.toString()),
  new SlashCommandBuilder()
    .setName("setup")
    .setDescription("(Admin) Build/repair the server channels & roles")
    .setDefaultMemberPermissions(PermissionFlagsBits.Administrator.toString()),
  new SlashCommandBuilder()
    .setName("ticketpanel")
    .setDescription("(Staff) Post the 'Open a ticket' panel in this channel")
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageThreads.toString()),
  new SlashCommandBuilder()
    .setName("seed")
    .setDescription("(Admin) Post the info-channel content pack (idempotent)")
    .setDefaultMemberPermissions(PermissionFlagsBits.Administrator.toString()),
  new SlashCommandBuilder()
    .setName("giveaway")
    .setDescription("(Staff) Run giveaways")
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild.toString())
    .addSubcommand((s) =>
      s
        .setName("start")
        .setDescription("Start a giveaway in #enter-giveaway")
        .addStringOption((o) => o.setName("prize").setDescription("What's being given away").setRequired(true))
        .addStringOption((o) => o.setName("duration").setDescription("e.g. 30m, 6h, 2d").setRequired(true))
        .addIntegerOption((o) => o.setName("winners").setDescription("How many winners (default 1)").setMinValue(1).setMaxValue(20))
        .addStringOption((o) =>
          o
            .setName("type")
            .setDescription("Which winners channel to announce in")
            .addChoices({ name: "daily", value: "daily" }, { name: "weekly", value: "weekly" }),
        ),
    )
    .addSubcommand((s) => s.setName("end").setDescription("End the most recent active giveaway now"))
    .addSubcommand((s) => s.setName("reroll").setDescription("Re-roll winners of the last ended giveaway")),
].map((c) => c.toJSON());

export async function handleCommand(i: ChatInputCommandInteraction): Promise<void> {
  switch (i.commandName) {
    case "link":
      return cmdLink(i);
    case "unlink":
      return cmdUnlink(i);
    case "whoami":
      return cmdWhoami(i);
    case "player":
      return cmdPlayer(i);
    case "hiscores":
      return cmdHiscores(i);
    case "online":
      return cmdOnline(i);
    case "sync":
      return cmdSync(i);
    case "setup":
      return cmdSetup(i);
    case "ticketpanel":
      return cmdTicketPanel(i);
    case "seed":
      return cmdSeed(i);
    case "giveaway":
      return cmdGiveaway(i);
  }
}

async function cmdSeed(i: ChatInputCommandInteraction) {
  if (!i.guild) return void i.reply({ content: "Use this in the server.", ephemeral: true });
  await i.deferReply({ ephemeral: true });
  const res = await seedContent(i.guild);
  const guidesCh = findText(i.guild, CH.guides);
  if (guidesCh) await postGuidesIndexIfMissing(guidesCh).catch(() => {});
  const lines = [`Seeded ${res.posted.length} channel(s)${guidesCh ? " + guides index" : ""}.`];
  if (res.skipped.length) lines.push(`Skipped (already posted): ${res.skipped.join(", ")}`);
  if (res.missing.length) lines.push(`⚠️ Missing channels (run /setup first): ${res.missing.join(", ")}`);
  await i.editReply(lines.join("\n"));
}

async function cmdGiveaway(i: ChatInputCommandInteraction) {
  if (!i.guild) return void i.reply({ content: "Use this in the server.", ephemeral: true });
  const sub = i.options.getSubcommand();
  await i.deferReply({ ephemeral: true });

  if (sub === "start") {
    const prize = i.options.getString("prize", true);
    const durationMs = parseDuration(i.options.getString("duration", true));
    if (!durationMs) return void i.editReply("Invalid duration. Use e.g. `30m`, `6h`, `2d`.");
    const winners = i.options.getInteger("winners") ?? 1;
    const announce = (i.options.getString("type") as "daily" | "weekly" | null) ?? "daily";
    return void (await startGiveaway(i, { prize, durationMs, winners, announce }));
  }
  if (sub === "end") return void (await i.editReply(await endLatest(i.client)));
  if (sub === "reroll") return void (await i.editReply(await rerollLatest(i.client)));
}

async function cmdTicketPanel(i: ChatInputCommandInteraction) {
  if (!i.channel || i.channel.type !== ChannelType.GuildText) {
    return void i.reply({ content: "Run this in a normal text channel (ideally #support-ticket).", ephemeral: true });
  }
  await i.deferReply({ ephemeral: true });
  const posted = await postTicketPanelIfMissing(i.channel as TextChannel);
  await i.editReply(posted ? "Ticket panel posted." : "A ticket panel is already in this channel.");
}

async function cmdLink(i: ChatInputCommandInteraction) {
  if (!i.inGuild() || !(i.member instanceof GuildMember)) {
    return void i.reply({ content: "Use this in the server.", ephemeral: true });
  }
  await i.deferReply({ ephemeral: true });
  const res = await linkByCode(i.member, i.options.getString("code", true));
  await i.editReply(res.message);
}

async function cmdUnlink(i: ChatInputCommandInteraction) {
  if (!(i.member instanceof GuildMember)) return void i.reply({ content: "Use this in the server.", ephemeral: true });
  await i.deferReply({ ephemeral: true });
  const res = await unlink(i.member);
  await i.editReply(res.message);
}

async function cmdWhoami(i: ChatInputCommandInteraction) {
  if (!(i.member instanceof GuildMember)) return void i.reply({ content: "Use this in the server.", ephemeral: true });
  const res = await whoami(i.member);
  await i.reply({
    content: res ? `You're linked to **${res.loginUsername}**.` : "You're not linked. Run `/link` with a code from the website.",
    ephemeral: true,
  });
}

async function cmdPlayer(i: ChatInputCommandInteraction) {
  await i.deferReply();
  const name = i.options.getString("name", true);
  const p = await getProfile(name);
  if (!p.found) return void i.editReply(`No account found for **${name}**.`);

  const embed = new EmbedBuilder()
    .setColor(0x2980b9)
    .setTitle(`📊 ${p.displayName}`)
    .setURL(`${config.siteUrl}/hiscores/${encodeURIComponent(p.loginUsername)}`)
    .addFields(
      { name: "Combat", value: String(p.combatLevel), inline: true },
      { name: "Total level", value: p.totalLevel.toLocaleString(), inline: true },
      { name: "Total XP", value: p.totalXp.toLocaleString(), inline: true },
    );

  if (p.membershipTier) embed.addFields({ name: "Membership", value: p.membershipTier, inline: true });
  if (!p.hasSkills) embed.setDescription("_This player hasn't logged into the game yet — no skills saved._");
  else {
    const top = [...p.skills].sort((a, b) => b.level - a.level).slice(0, 8);
    embed.addFields({
      name: "Top skills",
      value: top.map((s) => `**${s.name}** ${s.level}`).join("  •  "),
    });
  }
  embed.setFooter({ text: "Fall of Varrock" });
  await i.editReply({ embeds: [embed] });
}

async function cmdHiscores(i: ChatInputCommandInteraction) {
  await i.deferReply();
  const limit = i.options.getInteger("limit") ?? 10;
  const rows = await topByTotalLevel(limit);
  if (!rows.length) return void i.editReply("No ranked players yet.");

  const medal = (n: number) => (n === 0 ? "🥇" : n === 1 ? "🥈" : n === 2 ? "🥉" : `**${n + 1}.**`);
  const embed = new EmbedBuilder()
    .setColor(0xf1c40f)
    .setTitle("🏆 Hiscores — Total Level")
    .setURL(`${config.siteUrl}/hiscores`)
    .setDescription(
      rows.map((r, n) => `${medal(n)} ${r.displayName} — **${r.totalLevel.toLocaleString()}** (cb ${r.combat})`).join("\n"),
    )
    .setFooter({ text: "Fall of Varrock" });
  await i.editReply({ embeds: [embed] });
}

async function cmdOnline(i: ChatInputCommandInteraction) {
  await i.deferReply();
  const online = await Collections.online();
  const players = await online.find({}).sort({ since: 1 }).limit(100).toArray();
  const embed = new EmbedBuilder()
    .setColor(players.length ? 0x2ecc71 : 0x95a5a6)
    .setTitle(`🟢 ${players.length} online`)
    .setDescription(players.length ? players.map((p) => `• ${p.displayName}`).join("\n") : "Nobody's online right now.")
    .setFooter({ text: "Fall of Varrock" });
  await i.editReply({ embeds: [embed] });
}

async function cmdSync(i: ChatInputCommandInteraction) {
  if (!i.guild) return void i.reply({ content: "Use this in the server.", ephemeral: true });
  await i.deferReply({ ephemeral: true });
  const res = await syncAllRoles(i.guild);
  await i.editReply(`Reconciled ${res.checked} linked member(s); updated ${res.updated}.`);
}

async function cmdSetup(i: ChatInputCommandInteraction) {
  if (!i.guild) return void i.reply({ content: "Use this in the server.", ephemeral: true });
  await i.deferReply({ ephemeral: true });
  const res = await runSetup(i.guild);

  // Fill the info channels, the guides index, and the self-roles panel in one go.
  const seed = await seedContent(i.guild);
  const guidesCh = findText(i.guild, CH.guides);
  if (guidesCh) await postGuidesIndexIfMissing(guidesCh).catch(() => {});
  const rolesCh = findText(i.guild, CH.roles);
  if (rolesCh) await postRolesPanelIfMissing(rolesCh).catch(() => {});

  const lines: string[] = [];
  lines.push(`**Setup complete.** Created ${res.rolesCreated.length} role(s), ${res.channelsCreated.length} channel(s).`);
  lines.push(`Seeded ${seed.posted.length} info channel(s)${seed.skipped.length ? `, ${seed.skipped.length} already had content` : ""}.`);
  if (res.feeds.length) {
    lines.push("\n**Add these to the bot's `.env` so feeds post to the right channels:**", "```");
    for (const f of res.feeds) lines.push(`${f.envVar}=${f.channelId}   # ${f.channelName}`);
    lines.push("```");
  }
  if (res.warnings.length) lines.push("\n⚠️ " + res.warnings.join("\n⚠️ "));
  // Discord caps replies at 2000 chars; this fits comfortably.
  await i.editReply(lines.join("\n"));
}
