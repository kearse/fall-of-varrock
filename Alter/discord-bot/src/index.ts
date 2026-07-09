import { Client, Events, GatewayIntentBits, GuildMember, Message, Partials } from "discord.js";
import { config } from "./config.js";
import { handleCommand } from "./commands.js";
import { registerCommands } from "./registerCommands.js";
import { startEventFeed } from "./feed/eventFeed.js";
import { startNewsFeed } from "./feed/newsFeed.js";
import { syncAllRoles } from "./roles/sync.js";
import { handleTicketButton } from "./tickets/tickets.js";
import { startLiveBoards } from "./live/liveBoards.js";
import { handleEnterButton, startGiveawayPoller } from "./giveaways/giveaways.js";
import { handleSelfRoleButton, maybeAddSuggestionVotes, welcomeMember } from "./engagement/engagement.js";
import { closeDb } from "./db.js";

const client = new Client({
  // GuildMembers is privileged — enable it in the Developer Portal → Bot.
  // GuildMessages lets us auto-react in #suggestions (no MessageContent needed).
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMembers, GatewayIntentBits.GuildMessages],
  partials: [Partials.GuildMember],
});

client.once(Events.ClientReady, async (c) => {
  console.log(`[bot] logged in as ${c.user.tag}`);

  await registerCommands().catch((e) => console.error("[register] failed:", e));

  startEventFeed(client);
  startNewsFeed(client);
  startLiveBoards(client);
  startGiveawayPoller(client);

  // Periodic role reconciliation across all linked members.
  const guild = await client.guilds.fetch(config.guildId).catch(() => null);
  if (guild) {
    const run = () => syncAllRoles(guild).then((r) => console.log(`[roleSync] checked ${r.checked}, updated ${r.updated}`)).catch((e) => console.error("[roleSync]", e));
    void run();
    setInterval(run, config.roleSyncMs);
  } else {
    console.error(`[bot] could not fetch guild ${config.guildId} — check DISCORD_GUILD_ID and that the bot is invited.`);
  }
});

client.on(Events.InteractionCreate, async (interaction) => {
  try {
    if (interaction.isButton()) {
      const id = interaction.customId;
      if (id.startsWith("ticket:")) await handleTicketButton(interaction);
      else if (id.startsWith("giveaway:enter:")) await handleEnterButton(interaction);
      else if (id.startsWith("selfrole:")) await handleSelfRoleButton(interaction);
      return;
    }
    if (interaction.isChatInputCommand()) await handleCommand(interaction);
  } catch (e) {
    console.error("[interaction]", e);
    if (interaction.isRepliable()) {
      const msg = "Something went wrong handling that.";
      if (interaction.deferred || interaction.replied) await interaction.editReply(msg).catch(() => {});
      else await interaction.reply({ content: msg, ephemeral: true }).catch(() => {});
    }
  }
});

// New member joins: greet them, and re-grant roles if they linked before.
client.on(Events.GuildMemberAdd, async (member: GuildMember) => {
  await welcomeMember(member).catch(() => {});
  const { Collections } = await import("./db.js");
  const links = await Collections.discordLinks();
  const link = await links.findOne({ discordUserId: member.id });
  if (link?.loginUsername) {
    const { syncMemberRoles } = await import("./roles/sync.js");
    await syncMemberRoles(member, link.loginUsername, { grantBaseRoles: true });
  }
});

// Auto-add 👍/👎 to posts in #suggestions.
client.on(Events.MessageCreate, async (message: Message) => {
  await maybeAddSuggestionVotes(message).catch(() => {});
});

async function shutdown(signal: string) {
  console.log(`[bot] ${signal} — shutting down`);
  client.destroy();
  await closeDb().catch(() => {});
  process.exit(0);
}
process.on("SIGINT", () => void shutdown("SIGINT"));
process.on("SIGTERM", () => void shutdown("SIGTERM"));

client.login(config.token).catch((e) => {
  console.error("[bot] login failed:", e);
  process.exit(1);
});
