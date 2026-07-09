import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { createGuide } from "@/lib/guides";
import { requireStaff, isResponse } from "@/lib/guard";

export const runtime = "nodejs";

const schema = z.object({
  title: z.string().min(2).max(140),
  summary: z.string().max(300).default(""),
  body: z.string().min(1).max(40000),
  category: z.string().max(40).default("General"),
});

export async function POST(req: NextRequest) {
  const auth = await requireStaff();
  if (isResponse(auth)) return auth;

  let body: unknown;
  try { body = await req.json(); } catch { return NextResponse.json({ error: "Invalid body." }, { status: 400 }); }
  const parsed = schema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: "Title and body are required." }, { status: 400 });

  const doc = await createGuide({ ...parsed.data, authorName: auth.currentDisplayName });
  return NextResponse.json({ ok: true, slug: doc.slug });
}
