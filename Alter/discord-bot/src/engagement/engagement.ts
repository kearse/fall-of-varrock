import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonInteraction,
  ButtonStyle,
  EmbedBuilder,
  GuildMember,
  Message,
  TextChannel,
} from "discord.js";
import { CH, SELF_ROLES, findRoleByName, findText } from "../guild.js";

/**
 * Community engagement:
 *  - welcomeMember        — greet new members in #welcome
 *  - postRolesPanelIfMissing / handleSelfRoleButton — self-assign notify roles
 *  - maybeAddSuggestionVotes — auto 👍/👎 on posts in #suggestions
 */

const SELF_ROLE_PREFIX = "selfrole:";
const ROLES_PANEL_TITLE = "🔔 Notification Roles";

export async function welcomeMember(member: GuildMember): Promise<void> {
  const channel = findText(member.guild, CH.welcome) ?? findText(member.guild, "general");
  if (!channel) return;
  await channel
    .send({
      content: `${member} just joined the Kingdom! 👋`,
      embeds: [
        new EmbedBuilder()
          .setColor(0xd4af37)
          .setDescription(
            `Welcome to **Kingdom of Lumbridge**! Link your game account with \`/link\` (see #${CH.linkAccount}), ` +
              `grab your notification roles in #${CH.roles}, and check #${CH.howToPlay} to get started.`,
          ),
      ],
      allowedMentions: { users: [member.id] },
    })
    .catch(() => {});
}

export async function postRolesPanelIfMissing(channel: TextChannel): Promise<boolean> {
  const recent = await channel.messages.fetch({ limit: 25 }).catch(() => null);
  const exists = recent?.some(
    (m) => m.author.id === channel.client.user?.id && m.embeds.some((e) => e.title === ROLES_PANEL_TITLE),
  );
  if (exists) return false;

  const embed = new EmbedBuilder()
    .setColor(0x3498db)
    .setTitle(ROLES_PANEL_TITLE)
    .setDescription("Click a button to toggle a role and choose which pings you receive.");
  const row = new ActionRowBuilder<ButtonBuilder>();
  for (const r of SELF_ROLES) {
    row.addComponents(
      new ButtonBuilder().setCustomId(`${SELF_ROLE_PREFIX}${r.key}`).setLabel(r.name.replace(/^[^\s]+\s/, "")).setEmoji(r.emoji).setStyle(ButtonStyle.Secondary),
    );
  }
  await channel.send({ embeds: [embed], components: [row] });
  return true;
}

export async function handleSelfRoleButton(interaction: ButtonInteraction): Promise<void> {
  const key = interaction.customId.slice(SELF_ROLE_PREFIX.length);
  const def = SELF_ROLES.find((r) => r.key === key);
  const member = interaction.member as GuildMember | null;
  if (!def || !member || !interaction.guild) {
    return void interaction.reply({ content: "That role isn't available.", ephemeral: true });
  }
  const role = findRoleByName(interaction.guild, def.name);
  if (!role) {
    return void interaction.reply({ content: "That role doesn't exist yet — ask staff to run /setup.", ephemeral: true });
  }
  if (member.roles.cache.has(role.id)) {
    await member.roles.remove(role).catch(() => {});
    await interaction.reply({ content: `Removed **${def.name}**.`, ephemeral: true });
  } else {
    await member.roles.add(role).catch(() => {});
    await interaction.reply({ content: `Added **${def.name}**.`, ephemeral: true });
  }
}

export async function maybeAddSuggestionVotes(message: Message): Promise<void> {
  if (message.author.bot) return;
  const channel = message.channel;
  if (!("name" in channel) || channel.name?.toLowerCase() !== CH.suggestions) return;
  await message.react("👍").catch(() => {});
  await message.react("👎").catch(() => {});
}
