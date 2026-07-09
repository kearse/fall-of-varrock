import { NextRequest, NextResponse } from "next/server";
import { paypalCapture } from "@/lib/payments";
import { getPackage } from "@/lib/store";
import { Collections } from "@/lib/collections";
import { fulfillOrder } from "@/lib/entitlements";

export const runtime = "nodejs";

// PayPal redirects the buyer here (return_url) after they approve. We capture the
// payment, fulfill the order, then redirect to the success page.
export async function GET(req: NextRequest) {
  const url = new URL(req.url);
  const orderId = url.searchParams.get("order");
  const token = url.searchParams.get("token"); // PayPal order id
  const base = url.origin;

  if (!orderId || !token) {
    return NextResponse.redirect(`${base}/store?error=paypal`);
  }

  const result = await paypalCapture(token);
  if (!result.paid) {
    return NextResponse.redirect(`${base}/store?error=paypal`);
  }

  const orders = await Collections.orders();
  const pending = await orders.findOne({ orderId });
  const pkg = pending ? getPackage(pending.packageId) : undefined;
  if (pkg && pending) {
    await fulfillOrder({
      orderId,
      loginUsername: pending.loginUsername,
      pkg,
      provider: "paypal",
      providerRef: token,
    });
  }
  return NextResponse.redirect(`${base}/store/success?order=${orderId}`);
}
