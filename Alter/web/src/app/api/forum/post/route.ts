import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { createPost } from "@/lib/forum";
import { requireUser, isResponse } from "@/lib/guard";

export const runtime = "nodejs";

const schema = z.object({
  threadId: z.string(),
  body: z.string().min(1).max(20000),
});

export async function POST(req: NextRequest) {
  const auth = await requireUser();
  if (isResponse(auth)) return auth;

  let body: unknown;
  try { body = await req.json(); } catch { return NextResponse.json({ error: "Invalid body." }, { status: 400 }); }
  const parsed = schema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: "A reply body is required." }, { status: 400 });

  const result = await createPost({
    threadId: parsed.data.threadId,
    body: parsed.data.body,
    authorName: auth.currentDisplayName,
  });
  if ("error" in result) return NextResponse.json({ error: result.error }, { status: 400 });
  return NextResponse.json({ ok: true });
}
