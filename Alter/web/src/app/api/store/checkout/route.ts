import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { requireUser, isResponse } from "@/lib/guard";
import { getPackage } from "@/lib/store";
import { Collections } from "@/lib/collections";
import { fulfillOrder } from "@/lib/entitlements";
import {
  newOrderId, CheckoutContext,
  stripeConfigured, stripeCreateCheckout,
  coinbaseConfigured, coinbaseCreateCharge,
  paypalConfigured, paypalCreateOrder,
} from "@/lib/payments";

export const runtime = "nodejs";

const schema = z.object({
  packageId: z.string(),
  provider: z.enum(["stripe", "paypal", "coinbase", "dev"]),
});

export async function POST(req: NextRequest) {
  const auth = await requireUser();
  if (isResponse(auth)) return auth;

  let body: unknown;
  try { body = await req.json(); } catch { return NextResponse.json({ error: "Invalid body." }, { status: 400 }); }
  const parsed = schema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: "Bad request." }, { status: 400 });

  const pkg = getPackage(parsed.data.packageId);
  if (!pkg) return NextResponse.json({ error: "Unknown package." }, { status: 404 });

  const orderId = newOrderId();
  const baseUrl = new URL(req.url).origin;
  const ctx: CheckoutContext = { pkg, orderId, loginUsername: auth.loginUsername, baseUrl };

  // Record a pending order up front (audit + success page).
  const orders = await Collections.orders();
  await orders.insertOne({
    orderId, loginUsername: auth.loginUsername, provider: parsed.data.provider === "dev" ? "stripe" : parsed.data.provider,
    providerRef: "", packageId: pkg.id, amount: pkg.priceCents, currency: "usd",
    status: "pending", createdAt: Date.now(),
  } as any);

  try {
    switch (parsed.data.provider) {
      case "dev": {
        // Local testing only - instantly grants without real payment.
        if (process.env.STORE_DEV_CHECKOUT !== "1") {
          return NextResponse.json({ error: "Dev checkout is disabled." }, { status: 403 });
        }
        await fulfillOrder({ orderId, loginUsername: auth.loginUsername, pkg, provider: "stripe", providerRef: "dev" });
        return NextResponse.json({ url: `${baseUrl}/store/success?order=${orderId}` });
      }
      case "stripe": {
        if (!stripeConfigured()) return NextResponse.json({ error: "Card payments are not configured yet." }, { status: 503 });
        return NextResponse.json({ url: await stripeCreateCheckout(ctx) });
      }
      case "coinbase": {
        if (!coinbaseConfigured()) return NextResponse.json({ error: "Crypto payments are not configured yet." }, { status: 503 });
        return NextResponse.json({ url: await coinbaseCreateCharge(ctx) });
      }
      case "paypal": {
        if (!paypalConfigured()) return NextResponse.json({ error: "PayPal is not configured yet." }, { status: 503 });
        const { approveUrl } = await paypalCreateOrder(ctx);
        return NextResponse.json({ url: approveUrl });
      }
    }
  } catch (e) {
    return NextResponse.json({ error: e instanceof Error ? e.message : "Checkout failed." }, { status: 500 });
  }
}
