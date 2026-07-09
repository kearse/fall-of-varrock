import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { moderateThread } from "@/lib/forum";
import { requireStaff, isResponse } from "@/lib/guard";

export const runtime = "nodejs";

const schema = z.object({
  threadId: z.string(),
  action: z.enum(["pin", "unpin", "lock", "unlock", "delete"]),
});

export async function POST(req: NextRequest) {
  const auth = await requireStaff();
  if (isResponse(auth)) return auth;

  let body: unknown;
  try { body = await req.json(); } catch { return NextResponse.json({ error: "Invalid body." }, { status: 400 }); }
  const parsed = schema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: "Bad request." }, { status: 400 });

  const ok = await moderateThread(parsed.data.threadId, parsed.data.action);
  return NextResponse.json({ ok });
}
