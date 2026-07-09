import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { createAccount } from "@/lib/accounts";
import { createSession } from "@/lib/session";

export const runtime = "nodejs";

const schema = z.object({
  username: z.string(),
  password: z.string(),
  email: z.string().email().optional().or(z.literal("")),
});

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

  try {
    const account = await createAccount({
      username: parsed.data.username,
      password: parsed.data.password,
      email: parsed.data.email || null,
    });
    await createSession(account);
    return NextResponse.json({
      ok: true,
      account: { name: account.currentDisplayName, roles: account.roles },
    });
  } catch (e) {
    const message = e instanceof Error ? e.message : "Registration failed.";
    return NextResponse.json({ error: message }, { status: 400 });
  }
}
