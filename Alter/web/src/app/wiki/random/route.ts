import { NextResponse } from "next/server";
import { getAllArticles } from "@/lib/wiki";

export const dynamic = "force-dynamic";

/** MediaWiki-style "Random page". */
export function GET(request: Request) {
  const articles = getAllArticles();
  if (articles.length === 0) return NextResponse.redirect(new URL("/wiki", request.url));
  const pick = articles[Math.floor(Math.random() * articles.length)];
  return NextResponse.redirect(new URL(`/wiki/${pick.slug}`, request.url));
}
