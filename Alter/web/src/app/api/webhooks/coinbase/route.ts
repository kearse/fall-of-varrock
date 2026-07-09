import { NextRequest, NextResponse } from "next/server";
import { coinbaseVerify } from "@/lib/payments";
import { getPackage } from "@/lib/store";
import { fulfillOrder } from "@/lib/entitlements";

export const runtime = "nodejs";

export async function POST(req: NextRequest) {
  const raw = await req.text();
  const event = coinbaseVerify(raw, req.headers.get("x-cc-webhook-signature"));
  if (!event) return NextResponse.json({ error: "Invalid signature." }, { status: 400 });

  const type = event.event?.type;
  if (type === "charge:confirmed" || type === "charge:resolved") {
    const meta = event.event?.data?.metadata ?? {};
    const pkg = getPackage(meta.packageId);
    if (pkg && meta.orderId && meta.loginUsername) {
      await fulfillOrder({
        orderId: meta.orderId,
        loginUsername: meta.loginUsername,
        pkg,
        provider: "coinbase",
        providerRef: event.event?.data?.id ?? "",
      });
    }
  }
  return NextResponse.json({ received: true });
}
