import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { createThread, getCategory } from "@/lib/forum";
import { requireUser, isResponse } from "@/lib/guard";
import { isStaff } from "@/lib/accounts";

export const runtime = "nodejs";

const schema = z.object({
  categorySlug: z.string(),
  title: z.string().min(3).max(140),
  body: z.string().min(1).max(20000),
});

export async function POST(req: NextRequest) {
  const auth = await requireUser();
  if (isResponse(auth)) return auth;

  let body: unknown;
  try { body = await req.json(); } catch { return NextResponse.json({ error: "Invalid body." }, { status: 400 }); }
  const parsed = schema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: "Title and body are required." }, { status: 400 });

  const category = await getCategory(parsed.data.categorySlug);
  if (!category) return NextResponse.json({ error: "Category not found." }, { status: 404 });
  if (category.minRoleToPost === "staff" && !isStaff(auth)) {
    return NextResponse.json({ error: "Only staff can post here." }, { status: 403 });
  }

  const result = await createThread({
    categorySlug: parsed.data.categorySlug,
    title: parsed.data.title,
    body: parsed.data.body,
    authorName: auth.currentDisplayName,
  });
  if ("error" in result) return NextResponse.json({ error: result.error }, { status: 400 });
  return NextResponse.json({ ok: true, threadId: result.threadId });
}
