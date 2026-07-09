import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { findAccount, verifyPassword } from "@/lib/accounts";
import { createSession } from "@/lib/session";

export const runtime = "nodejs";

const schema = z.object({ username: z.string(), password: z.string() });

export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid request body." }, { status: 400 });
  }

  const parsed = schema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json({ error: "Missing username or password." }, { status: 400 });
  }

  const account = await findAccount(parsed.data.username);
  // Constant-ish response to avoid leaking which usernames exist.
  if (!account || !(await verifyPassword(account, parsed.data.password))) {
    return NextResponse.json({ error: "Invalid username or password." }, { status: 401 });
  }

  await createSession(account);
  return NextResponse.json({
    ok: true,
    account: { name: account.currentDisplayName, roles: account.roles },
  });
}
