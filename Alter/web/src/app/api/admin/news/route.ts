import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { createNews } from "@/lib/news";
import { requireStaff, isResponse } from "@/lib/guard";

export const runtime = "nodejs";

const schema = z.object({
  title: z.string().min(2).max(140),
  body: z.string().max(20000),
  category: z.enum(["update", "announcement", "patch", "event"]).default("update"),
  postToDiscord: z.boolean().default(true),
});

export async function POST(req: NextRequest) {
  const auth = await requireStaff();
  if (isResponse(auth)) return auth;

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid body." }, { status: 400 });
  }

  const parsed = schema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json({ error: "Title and body are required." }, { status: 400 });
  }

  const doc = await createNews({
    title: parsed.data.title,
    body: parsed.data.body,
    category: parsed.data.category,
    authorName: auth.currentDisplayName,
    pushToDiscord: parsed.data.postToDiscord,
  });

  return NextResponse.json({
    ok: true,
    slug: doc.slug,
    pushToDiscord: doc.pushToDiscord,
    discordPosted: doc.discordPosted,
  });
}
