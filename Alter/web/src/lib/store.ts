import { EntitlementDoc } from "./collections";

/**
 * Store catalog. Each package maps to one entitlement that the game applies on the
 * player's next login (see the Kotlin RewardDeliveryPlugin). Prices are in cents.
 *
 * Items use rscm names (resolved server-side via getRSCM) or numeric ids.
 */
export type StoreCategory = "Bonds" | "Membership" | "Donator Points" | "Bundles";

export interface StorePackage {
  id: string;
  name: string;
  description: string;
  priceCents: number;
  category: StoreCategory;
  badge?: string;
  perks?: string[]; // shown on membership cards
  entitlement: { kind: EntitlementDoc["kind"]; payload: Record<string, unknown> };
}

// NOTE: membership `tier` values are LOWERCASE to match the Discord role map
// (discord-bot/src/roles/roleMap.ts keys on tier === "bronze" | ... | "diamond").
export const PACKAGES: StorePackage[] = [
  // ---- Bonds (tradeable premium currency - the keystone; see bond-system-spec.md) ----
  // Delivered in-game as the TRADEABLE bond item. Trade it to other players for gold, or claim
  // it (makes it untradeable) and redeem for membership time / Donor Points at the Bond Merchant.
  // "You never have to pay to be a member - buy a bond off another player with gold you earned."
  {
    id: "bond-1",
    name: "1 Bond",
    description: "One tradeable bond. Trade it, or claim it for 30 days of membership or 450 Donor Points.",
    priceCents: 499,
    category: "Bonds",
    entitlement: { kind: "items", payload: { items: [{ item: "item.bond", amount: 1 }] } },
  },
  {
    id: "bond-3",
    name: "3 Bonds",
    description: "Three tradeable bonds at a small discount.",
    priceCents: 1399,
    category: "Bonds",
    entitlement: { kind: "items", payload: { items: [{ item: "item.bond", amount: 3 }] } },
  },
  {
    id: "bond-5",
    name: "5 Bonds",
    description: "Five tradeable bonds - stock up or fund your gold stack.",
    priceCents: 2199,
    category: "Bonds",
    badge: "Popular",
    entitlement: { kind: "items", payload: { items: [{ item: "item.bond", amount: 5 }] } },
  },
  {
    id: "bond-10",
    name: "10 Bonds",
    description: "Ten tradeable bonds at the best per-bond rate.",
    priceCents: 3999,
    category: "Bonds",
    badge: "Best value",
    entitlement: { kind: "items", payload: { items: [{ item: "item.bond", amount: 10 }] } },
  },

  // ---- Membership tiers (time-based perks + Discord role) ----
  {
    id: "membership-bronze-30",
    name: "Bronze Membership",
    description: "30 days of membership perks. Stacks with any time you already have.",
    priceCents: 499,
    category: "Membership",
    perks: ["Bronze Discord role", "Donator zone access", "Yell channel", "4% boosted drops"],
    entitlement: { kind: "membership", payload: { tier: "bronze", days: 30 } },
  },
  {
    id: "membership-silver-30",
    name: "Silver Membership",
    description: "30 days of Silver perks. A step up from Bronze.",
    priceCents: 999,
    category: "Membership",
    perks: ["Silver Discord role", "Everything in Bronze", "Bank command", "6% boosted drops"],
    entitlement: { kind: "membership", payload: { tier: "silver", days: 30 } },
  },
  {
    id: "membership-gold-30",
    name: "Gold Membership",
    description: "30 days of Gold perks. Serious supporters of the realm.",
    priceCents: 1999,
    category: "Membership",
    badge: "Popular",
    perks: ["Gold Discord role", "Everything in Silver", "Teleport wizard", "8% boosted drops"],
    entitlement: { kind: "membership", payload: { tier: "gold", days: 30 } },
  },
  {
    id: "membership-diamond-30",
    name: "Diamond Membership",
    description: "30 days of our top membership tier.",
    priceCents: 3999,
    category: "Membership",
    badge: "Best perks",
    perks: ["Diamond Discord role", "Everything in Gold", "Exclusive zone", "10% boosted drops (cap)"],
    entitlement: { kind: "membership", payload: { tier: "diamond", days: 30 } },
  },

  // ---- Donator points (in-game donor store currency) ----
  {
    id: "donor-500",
    name: "500 Donor Points",
    description: "Spend on the in-game donor store.",
    priceCents: 500,
    category: "Donator Points",
    entitlement: { kind: "donor_points", payload: { points: 500 } },
  },
  {
    id: "donor-1200",
    name: "1,200 Donor Points",
    description: "+20% bonus points - best value starter bundle.",
    priceCents: 1000,
    category: "Donator Points",
    badge: "Popular",
    entitlement: { kind: "donor_points", payload: { points: 1200 } },
  },
  {
    id: "donor-3000",
    name: "3,000 Donor Points",
    description: "+25% bonus points.",
    priceCents: 2500,
    category: "Donator Points",
    entitlement: { kind: "donor_points", payload: { points: 3000 } },
  },
  {
    id: "donor-7000",
    name: "7,000 Donor Points",
    description: "+40% bonus points. For the dedicated benefactor of the realm.",
    priceCents: 5000,
    category: "Donator Points",
    entitlement: { kind: "donor_points", payload: { points: 7000 } },
  },

  // ---- Bundles (delivered to the bank) ----
  {
    id: "starter-kit",
    name: "Adventurer's Starter Kit",
    description: "A purse of 500k coins to get you started, delivered to your bank.",
    priceCents: 300,
    category: "Bundles",
    entitlement: { kind: "items", payload: { items: [{ item: "item.coins_995", amount: 500000 }] } },
  },
];

export const STORE_SECTIONS: StoreCategory[] = ["Bonds", "Membership", "Donator Points", "Bundles"];

export function getPackage(id: string): StorePackage | undefined {
  return PACKAGES.find((p) => p.id === id);
}

export function formatPrice(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}
