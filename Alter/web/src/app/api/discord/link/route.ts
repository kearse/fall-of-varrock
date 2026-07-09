import { NextResponse } from "next/server";
import { randomBytes } from "crypto";
import { getCurrentAccount } from "@/lib/session";
import { Collections } from "@/lib/collections";

export const runtime = "nodejs";

/**
 * Discord account linking, website side.
 *
 *  GET  → current link status for the logged-in user.
 *  POST → generate (or refresh) a one-time code; the user types it in Discord
 *         with `/link <code>` and the bot stamps discordUserId on the doc.
 *
 * The bot reads/writes the same `discord_links` collection (see
 * discord-bot/src/linking/link.ts).
 */

function genCode(): string {
  // 8 unambiguous chars (no 0/O/1/I), uppercase.
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = randomBytes(8);
  let out = "";
  for (let i = 0; i < 8; i++) out += alphabet[bytes[i] % alphabet.length];
  return out;
}

export async function GET() {
  const account = await getCurrentAccount();
  if (!account) return NextResponse.json({ error: "Not logged in" }, { status: 401 });

  const links = await Collections.discordLinks();
  const doc = await links.findOne({ loginUsername: account.loginUsername });
  return NextResponse.json({
    linked: !!doc?.discordUserId,
    discordTag: doc?.discordTag ?? null,
    pendingCode: doc?.discordUserId ? null : doc?.code ?? null,
  });
}

export async function POST() {
  const account = await getCurrentAccount();
  if (!account) return NextResponse.json({ error: "Not logged in" }, { status: 401 });

  const links = await Collections.discordLinks();
  const existing = await links.findOne({ loginUsername: account.loginUsername });
  if (existing?.discordUserId) {
    return NextResponse.json({ error: "Already linked", discordTag: existing.discordTag }, { status: 409 });
  }

  const code = genCode();
  await links.updateOne(
    { loginUsername: account.loginUsername },
    {
      $set: { code, createdAt: Date.now() },
      $unset: { discordUserId: "", discordTag: "", linkedAt: "" },
      $setOnInsert: { loginUsername: account.loginUsername },
    },
    { upsert: true },
  );

  return NextResponse.json({
    code,
    instructions: "In the Fall of Varrock Discord, run /link " + code,
  });
}
