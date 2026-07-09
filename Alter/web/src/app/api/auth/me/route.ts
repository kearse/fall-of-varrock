import { NextResponse } from "next/server";
import { getCurrentAccount } from "@/lib/session";

export const runtime = "nodejs";

export async function GET() {
  const account = await getCurrentAccount();
  if (!account) return NextResponse.json({ account: null });
  return NextResponse.json({
    account: {
      name: account.currentDisplayName,
      roles: account.roles,
      donorPoints: account.donorPoints,
      membership: account.membership,
    },
  });
}
