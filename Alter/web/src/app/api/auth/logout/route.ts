import { NextResponse } from "next/server";
import { destroySession } from "@/lib/session";

export const runtime = "nodejs";

export async function POST() {
  destroySession();
  return NextResponse.json({ ok: true });
}
