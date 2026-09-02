import { NextRequest, NextResponse } from "next/server";
import { POSTBACK_ADAPTERS, creditVote, postbackSecretOk } from "@/lib/vote-postback";

export const runtime = "nodejs";

/**
 * Toplist vote postback endpoint. Each supported toplist gets a stable URL to
 * paste into its dashboard's callback field:
 *
 *   https://fallofvarrock.com/api/vote/postback/rsps-list
 *
 * The `[site]` segment selects the adapter (lib/vote-postback.ts) that knows
 * that toplist's parameter names and secret; the credit path is shared. Both
 * GET and form POST are accepted since toplists differ (rsps-list sends both).
 */
async function handle(req: NextRequest, siteId: string): Promise<NextResponse> {
  const adapter = POSTBACK_ADAPTERS[siteId];
  if (!adapter) {
    return NextResponse.json({ error: "Unknown vote site." }, { status: 404 });
  }

  // Merge query-string and form-body params; sites vary in where they put them.
  const params = new URLSearchParams(req.nextUrl.search);
  if (req.method === "POST") {
    const contentType = req.headers.get("content-type") ?? "";
    if (contentType.includes("form")) {
      try {
        const form = await req.formData();
        form.forEach((value, key) => {
          if (typeof value === "string" && !params.has(key)) params.set(key, value);
        });
      } catch {
        // Malformed body — fall through to whatever the query string held.
      }
    }
  }

  const { secret, voted, userid } = adapter.parse(params);
  // One line per postback in `server.sh logs web` so "did the toplist ever call
  // us, and why didn't it credit?" is answerable without a debugger.
  const tag = `[vote] ${siteId} ${req.method} userid=${userid ?? "-"}`;

  if (!postbackSecretOk(adapter, secret)) {
    console.warn(`${tag} -> rejected: invalid secret (params: ${[...params.keys()].join(",")})`);
    return NextResponse.json({ error: "Invalid secret." }, { status: 403 });
  }

  if (!voted) {
    // Failed/duplicate vote on the toplist's side — acknowledged, nothing to credit.
    console.info(`${tag} -> voted=0, nothing credited`);
    return NextResponse.json({ received: true, credited: false });
  }

  if (!userid?.trim()) {
    console.warn(`${tag} -> missing userid (params: ${[...params.keys()].join(",")})`);
    return NextResponse.json({ received: true, credited: false, reason: "missing userid" });
  }

  const result = await creditVote(adapter.siteId, userid);
  console.info(`${tag} -> ${JSON.stringify(result)}`);
  return NextResponse.json({ received: true, ...result });
}

export async function GET(req: NextRequest, ctx: { params: { site: string } }) {
  return handle(req, ctx.params.site);
}

export async function POST(req: NextRequest, ctx: { params: { site: string } }) {
  return handle(req, ctx.params.site);
}
