import { NextRequest, NextResponse } from "next/server";
import { stripeVerify } from "@/lib/payments";
import { getPackage } from "@/lib/store";
import { fulfillOrder } from "@/lib/entitlements";

export const runtime = "nodejs";

export async function POST(req: NextRequest) {
  const raw = await req.text();
  const event = stripeVerify(raw, req.headers.get("stripe-signature"));
  if (!event) return NextResponse.json({ error: "Invalid signature." }, { status: 400 });

  if (event.type === "checkout.session.completed") {
    const session = event.data.object;
    const meta = session.metadata ?? {};
    const pkg = getPackage(meta.packageId);
    if (pkg && meta.orderId && meta.loginUsername && session.payment_status === "paid") {
      await fulfillOrder({
        orderId: meta.orderId,
        loginUsername: meta.loginUsername,
        pkg,
        provider: "stripe",
        providerRef: session.id,
      });
    }
  }
  return NextResponse.json({ received: true });
}
