import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonInteraction,
  ButtonStyle,
  ChannelType,
  EmbedBuilder,
  GuildMember,
  PermissionFlagsBits,
  TextChannel,
  ThreadAutoArchiveDuration,
} from "discord.js";

/**
 * Button-based support tickets (the Roat-Pkz style "click to open a ticket").
 *
 * A persistent panel in #support-ticket has an "Open a ticket" button. Clicking
 * it creates a PRIVATE thread named ticket-<user>, adds the user, pings the
 * Support role, and posts a Close button. Staff see private threads via their
 * Manage Threads permission.
 *
 * Bot needs in #support-ticket: Create Private Threads, Send Messages in Threads,
 * Manage Threads, Read Message History.
 */

export const TICKET_OPEN_ID = "ticket:open";
export const TICKET_CLOSE_ID = "ticket:close";
const SUPPORT_ROLE_NAME = "📣 Support";
const TICKET_PANEL_TITLE = "🎫 Support Tickets";

function panel() {
  const embed = new EmbedBuilder()
    .setColor(0x16a085)
    .setTitle(TICKET_PANEL_TITLE)
    .setDescription(
      "Need help with your account, a purchase, or a bug? Click **Open a ticket** below " +
        "to start a private conversation with the staff team.\n\nPlease don't open duplicate tickets.",
    )
    .setFooter({ text: "Kingdom of Lumbridge" });
  const row = new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder().setCustomId(TICKET_OPEN_ID).setLabel("Open a ticket").setEmoji("🎫").setStyle(ButtonStyle.Primary),
  );
  return { embeds: [embed], components: [row] };
}

/** Posts the ticket panel in `channel` unless one is already there (idempotent). */
export async function postTicketPanelIfMissing(channel: TextChannel): Promise<boolean> {
  const recent = await channel.messages.fetch({ limit: 25 }).catch(() => null);
  const exists = recent?.some(
    (m) => m.author.id === channel.client.user?.id && m.embeds.some((e) => e.title === TICKET_PANEL_TITLE),
  );
  if (exists) return false;
  await channel.send(panel());
  return true;
}

export async function handleTicketButton(interaction: ButtonInteraction): Promise<void> {
  if (interaction.customId === TICKET_OPEN_ID) return openTicket(interaction);
  if (interaction.customId === TICKET_CLOSE_ID) return closeTicket(interaction);
}

async function openTicket(interaction: ButtonInteraction) {
  const channel = interaction.channel;
  if (!channel || channel.type !== ChannelType.GuildText) {
    return void interaction.reply({ content: "Tickets can only be opened from the support channel.", ephemeral: true });
  }
  await interaction.deferReply({ ephemeral: true });

  const user = interaction.user;
  const safeName = user.username.toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 20) || "player";

  // Reuse an open ticket if the user already has one.
  const active = (await channel.threads.fetchActive().catch(() => null))?.threads;
  const mine = active?.find((t) => t.name === `ticket-${safeName}` && !t.archived);
  if (mine) return void interaction.editReply(`You already have an open ticket: <#${mine.id}>`);

  const thread = await channel.threads
    .create({
      name: `ticket-${safeName}`,
      type: ChannelType.PrivateThread,
      autoArchiveDuration: ThreadAutoArchiveDuration.OneDay,
      invitable: false,
      reason: `Support ticket for ${user.tag}`,
    })
    .catch(() => null);

  if (!thread) {
    return void interaction.editReply(
      "Couldn't create a ticket — the bot is missing the **Create Private Threads** / **Manage Threads** permission here.",
    );
  }

  await thread.members.add(user.id).catch(() => {});

  const supportRole = interaction.guild?.roles.cache.find((r) => r.name === SUPPORT_ROLE_NAME);
  const closeRow = new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder().setCustomId(TICKET_CLOSE_ID).setLabel("Close ticket").setEmoji("🔒").setStyle(ButtonStyle.Danger),
  );
  await thread.send({
    content: `${user} ${supportRole ? supportRole.toString() : ""}`.trim(),
    embeds: [
      new EmbedBuilder()
        .setColor(0x16a085)
        .setTitle("🎫 Ticket opened")
        .setDescription("Describe your issue and a staff member will be with you shortly. Click **Close ticket** when done."),
    ],
    components: [closeRow],
  });

  await interaction.editReply(`Your ticket is ready: <#${thread.id}>`);
}

async function closeTicket(interaction: ButtonInteraction) {
  const thread = interaction.channel;
  if (!thread || !thread.isThread()) {
    return void interaction.reply({ content: "This isn't a ticket thread.", ephemeral: true });
  }
  const member = interaction.member as GuildMember | null;
  const isStaff = member?.permissions.has(PermissionFlagsBits.ManageThreads) ?? false;
  const isOwner = thread.name === `ticket-${interaction.user.username.toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 20)}`;
  if (!isStaff && !isOwner) {
    return void interaction.reply({ content: "Only staff or the ticket owner can close this.", ephemeral: true });
  }

  await interaction.reply({ content: `🔒 Ticket closed by ${interaction.user}. Archiving…` });
  await thread.setLocked(true).catch(() => {});
  await thread.setArchived(true).catch(() => {});
}
