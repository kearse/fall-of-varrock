import { AccountDoc } from "../db.js";

/**
 * Single source of truth for how game account state maps to Discord roles.
 * Edit this file to change colours, names or thresholds — the rest of the bot
 * (setup + role sync) reads from here.
 *
 * `key` is a stable internal id used to find the Discord role by name; `name`
 * is the literal Discord role name. Order matters: highest first (mirrors the
 * Discord role list and SERVER_LAYOUT.md).
 */
export interface ManagedRole {
  key: string;
  name: string;
  color: number; // hex int, e.g. 0xd4af37
  hoist: boolean; // shown as a separate group in the member list
  /** Returns true if this account should hold the role. */
  applies: (a: AccountDoc) => boolean;
}

const hasRole = (a: AccountDoc, r: string) => a.roles?.includes(r) ?? false;
const tier = (a: AccountDoc) => a.membership?.tier ?? null;

export const MANAGED_ROLES: ManagedRole[] = [
  { key: "king", name: "👑 King", color: 0xd4af37, hoist: true, applies: (a) => hasRole(a, "owner") },
  { key: "minister", name: "⚔️ Minister", color: 0xc0392b, hoist: true, applies: (a) => hasRole(a, "admin") },
  { key: "community_manager", name: "🎙️ Community Manager", color: 0x9b59b6, hoist: true, applies: (a) => hasRole(a, "community_manager") },
  { key: "senior_mod", name: "🛡️ Senior Mod", color: 0x1abc9c, hoist: true, applies: (a) => hasRole(a, "senior_mod") },
  { key: "lord", name: "🗡️ Lord", color: 0x2980b9, hoist: true, applies: (a) => hasRole(a, "moderator") },
  { key: "developer", name: "🧙 Developer", color: 0x8e44ad, hoist: true, applies: (a) => hasRole(a, "developer") },
  { key: "support", name: "📣 Support", color: 0x16a085, hoist: true, applies: (a) => hasRole(a, "support") || hasRole(a, "helper") },

  { key: "diamond", name: "💎 Diamond Donor", color: 0xb9f2ff, hoist: true, applies: (a) => tier(a) === "diamond" },
  { key: "gold", name: "🥇 Gold Donor", color: 0xf1c40f, hoist: true, applies: (a) => tier(a) === "gold" },
  { key: "silver", name: "🥈 Silver Donor", color: 0xbdc3c7, hoist: true, applies: (a) => tier(a) === "silver" },
  { key: "bronze", name: "🥉 Bronze Donor", color: 0xcd7f32, hoist: true, applies: (a) => tier(a) === "bronze" },
  { key: "member", name: "⭐ Member", color: 0xe67e22, hoist: false, applies: (a) => tier(a) != null },

  // "linked" and "player" are applied at link-time, not by account state, so
  // they have no `applies` predicate that the sync would use to REVOKE them.
  { key: "linked", name: "🔗 Linked", color: 0x2ecc71, hoist: false, applies: () => true },
  { key: "player", name: "🎮 Player", color: 0x95a5a6, hoist: true, applies: () => true },
];

/** The bot's own integration role name (created/positioned above managed roles). */
export const BOT_ROLE_NAME = "🤖 Varrock Bot";

/**
 * Roles the sync engine OWNS: it will add them when `applies` is true and remove
 * them when false. "linked"/"player" are excluded — they are granted once at
 * link time and never auto-revoked (a player who unlinks loses them explicitly).
 */
export const SYNCED_ROLE_KEYS = MANAGED_ROLES.filter(
  (r) => r.key !== "linked" && r.key !== "player",
).map((r) => r.key);
