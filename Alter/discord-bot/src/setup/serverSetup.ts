import {
  CategoryChannel,
  ChannelType,
  Guild,
  OverwriteResolvable,
  PermissionFlagsBits,
  Role,
} from "discord.js";
import { MANAGED_ROLES, BOT_ROLE_NAME } from "../roles/roleMap.js";
import { postTicketPanelIfMissing } from "../tickets/tickets.js";
import { SELF_ROLES } from "../guild.js";
import { TextChannel } from "discord.js";
import { AccessTier, CategoryDef, ChannelDef, FEED_ENV, LAYOUT } from "./layout.js";

const STAFF_KEYS = ["king", "minister", "community_manager", "senior_mod", "lord", "developer", "support"];

export interface SetupResult {
  rolesCreated: string[];
  channelsCreated: string[];
  feeds: { key: string; envVar: string; channelId: string; channelName: string }[];
  warnings: string[];
}

/**
 * Idempotently builds the Fall of Varrock server layout. Safe to re-run:
 * roles/categories/channels are matched by name and reused, never duplicated.
 */
export async function runSetup(guild: Guild): Promise<SetupResult> {
  const result: SetupResult = { rolesCreated: [], channelsCreated: [], feeds: [], warnings: [] };

  const byName = new Map<string, Role>();
  for (const role of guild.roles.cache.values()) byName.set(role.name, role);

  // 1) Bot's own integration role — created/kept high so it can manage others.
  let botRole = byName.get(BOT_ROLE_NAME);
  if (!botRole) {
    botRole = await guild.roles.create({ name: BOT_ROLE_NAME, color: 0x5865f2, hoist: false });
    byName.set(BOT_ROLE_NAME, botRole);
    result.rolesCreated.push(BOT_ROLE_NAME);
  }
  // Give the bot user this role so channel overwrites that reference it apply.
  const me = await guild.members.fetchMe();
  if (!me.roles.cache.has(botRole.id)) {
    await me.roles.add(botRole).catch(() => result.warnings.push("Could not add bot role to self (missing Manage Roles?)."));
  }

  // 2) Managed roles (in reverse so each new role lands just under the previous,
  //    preserving the highest-first order from roleMap).
  const roleByKey = new Map<string, Role>();
  for (const def of [...MANAGED_ROLES].reverse()) {
    let role = byName.get(def.name);
    if (!role) {
      role = await guild.roles.create({ name: def.name, color: def.color, hoist: def.hoist, mentionable: false });
      byName.set(def.name, role);
      result.rolesCreated.push(def.name);
    }
    roleByKey.set(def.key, role);
  }

  // Self-assignable notification roles (toggled by buttons in #roles). Cosmetic,
  // not touched by role-sync (they're not in MANAGED_ROLES).
  for (const sr of SELF_ROLES) {
    if (!byName.get(sr.name)) {
      const role = await guild.roles.create({ name: sr.name, color: sr.color, mentionable: true });
      byName.set(sr.name, role);
      result.rolesCreated.push(sr.name);
    }
  }

  // Position the bot role above everything it manages.
  const targetPos = Math.max(...[...roleByKey.values()].map((r) => r.position)) + 1;
  await botRole.setPosition(targetPos).catch(() => result.warnings.push("Could not raise bot role position."));

  const linkedRole = roleByKey.get("linked")!;
  const staffRoles = STAFF_KEYS.map((k) => roleByKey.get(k)).filter((r): r is Role => !!r);

  const overwritesFor = (tier: AccessTier, readOnly: boolean): OverwriteResolvable[] => {
    const ow: OverwriteResolvable[] = [];
    const everyone = guild.roles.everyone.id;
    if (tier === "public") {
      if (readOnly) {
        ow.push({ id: everyone, deny: [PermissionFlagsBits.SendMessages] });
      }
    } else if (tier === "linked") {
      ow.push({ id: everyone, deny: [PermissionFlagsBits.ViewChannel] });
      ow.push({
        id: linkedRole.id,
        allow: [PermissionFlagsBits.ViewChannel],
        ...(readOnly ? { deny: [PermissionFlagsBits.SendMessages] } : {}),
      });
    } else {
      // staff
      ow.push({ id: everyone, deny: [PermissionFlagsBits.ViewChannel] });
      for (const r of staffRoles) ow.push({ id: r.id, allow: [PermissionFlagsBits.ViewChannel] });
    }
    // Bot always sees & speaks.
    ow.push({ id: botRole!.id, allow: [PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages] });
    return ow;
  };

  // 3) Categories + channels.
  const channelByName = new Map<string, CategoryChannel>();
  for (const ch of guild.channels.cache.values()) {
    if (ch.type === ChannelType.GuildCategory) channelByName.set(ch.name, ch as CategoryChannel);
  }

  for (const catDef of LAYOUT) {
    let category = channelByName.get(catDef.name);
    if (!category) {
      category = await guild.channels.create({
        name: catDef.name,
        type: ChannelType.GuildCategory,
        permissionOverwrites: overwritesFor(catDef.tier, false),
      });
      channelByName.set(catDef.name, category);
      result.channelsCreated.push(catDef.name);
    }
    await ensureChannels(guild, category, catDef, overwritesFor, result);
  }

  return result;
}

async function ensureChannels(
  guild: Guild,
  category: CategoryChannel,
  catDef: CategoryDef,
  overwritesFor: (tier: AccessTier, readOnly: boolean) => OverwriteResolvable[],
  result: SetupResult,
) {
  const existing = new Map<string, string>(); // name -> id, within this category
  for (const ch of guild.channels.cache.values()) {
    if ("parentId" in ch && ch.parentId === category.id) existing.set(ch.name, ch.id);
  }

  for (const def of catDef.channels) {
    // Discord lowercases text channel names; match accordingly.
    const wanted = def.type === "text" ? def.name.toLowerCase() : def.name;
    let id = existing.get(wanted) ?? existing.get(def.name);
    if (!id) {
      const created = await guild.channels.create({
        name: def.name,
        type: def.type === "voice" ? ChannelType.GuildVoice : ChannelType.GuildText,
        parent: category.id,
        permissionOverwrites: overwritesFor(def.tier, def.readOnly ?? false),
      });
      id = created.id;
      result.channelsCreated.push(`${catDef.name} / ${def.name}`);
    }
    if (def.feedKey) {
      result.feeds.push({
        key: def.feedKey,
        envVar: FEED_ENV[def.feedKey],
        channelId: id,
        channelName: def.name,
      });
    }
    if (def.ticketPanel) {
      const ch = await guild.channels.fetch(id).catch(() => null);
      if (ch && ch.type === ChannelType.GuildText) {
        await postTicketPanelIfMissing(ch as TextChannel).catch(() =>
          result.warnings.push(`Could not post the ticket panel in #${def.name}.`),
        );
      }
    }
  }
}
