import crypto from "crypto";
import { StorePackage } from "./store";

export function newOrderId(): string {
  return "ord_" + crypto.randomBytes(12).toString("hex");
}

export interface CheckoutContext {
  pkg: StorePackage;
  orderId: string;
  loginUsername: string;
  baseUrl: string; // e.g. http://localhost:3000
}

// ---------------------------------------------------------------- Stripe ----
export const stripeConfigured = () => !!process.env.STRIPE_SECRET_KEY;

export async function stripeCreateCheckout(ctx: CheckoutContext): Promise<string> {
  const key = process.env.STRIPE_SECRET_KEY!;
  const form = new URLSearchParams();
  form.set("mode", "payment");
  form.set("success_url", `${ctx.baseUrl}/store/success?order=${ctx.orderId}`);
  form.set("cancel_url", `${ctx.baseUrl}/store?cancelled=1`);
  form.set("client_reference_id", ctx.orderId);
  form.set("metadata[orderId]", ctx.orderId);
  form.set("metadata[loginUsername]", ctx.loginUsername);
  form.set("metadata[packageId]", ctx.pkg.id);
  form.set("line_items[0][quantity]", "1");
  form.set("line_items[0][price_data][currency]", "usd");
  form.set("line_items[0][price_data][unit_amount]", String(ctx.pkg.priceCents));
  form.set("line_items[0][price_data][product_data][name]", ctx.pkg.name);

  const res = await fetch("https://api.stripe.com/v1/checkout/sessions", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/x-www-form-urlencoded" },
    body: form,
  });
  if (!res.ok) throw new Error(`Stripe error: ${await res.text()}`);
  const data = await res.json();
  return data.url as string;
}

/** Verify a Stripe webhook signature and return the parsed event, or null. */
export function stripeVerify(rawBody: string, sigHeader: string | null): any | null {
  const secret = process.env.STRIPE_WEBHOOK_SECRET;
  if (!secret || !sigHeader) return null;
  const parts = Object.fromEntries(sigHeader.split(",").map((kv) => kv.split("=")));
  const t = parts["t"];
  const v1 = parts["v1"];
  if (!t || !v1) return null;
  const expected = crypto.createHmac("sha256", secret).update(`${t}.${rawBody}`).digest("hex");
  if (!timingSafeEqual(expected, v1)) return null;
  return JSON.parse(rawBody);
}

// -------------------------------------------------------------- Coinbase ----
export const coinbaseConfigured = () => !!process.env.COINBASE_COMMERCE_API_KEY;

export async function coinbaseCreateCharge(ctx: CheckoutContext): Promise<string> {
  const res = await fetch("https://api.commerce.coinbase.com/charges", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-CC-Api-Key": process.env.COINBASE_COMMERCE_API_KEY!,
      "X-CC-Version": "2018-03-22",
    },
    body: JSON.stringify({
      name: ctx.pkg.name,
      description: ctx.pkg.description,
      pricing_type: "fixed_price",
      local_price: { amount: (ctx.pkg.priceCents / 100).toFixed(2), currency: "USD" },
      metadata: { orderId: ctx.orderId, loginUsername: ctx.loginUsername, packageId: ctx.pkg.id },
      redirect_url: `${ctx.baseUrl}/store/success?order=${ctx.orderId}`,
      cancel_url: `${ctx.baseUrl}/store?cancelled=1`,
    }),
  });
  if (!res.ok) throw new Error(`Coinbase error: ${await res.text()}`);
  const data = await res.json();
  return data.data.hosted_url as string;
}

export function coinbaseVerify(rawBody: string, sigHeader: string | null): any | null {
  const secret = process.env.COINBASE_COMMERCE_WEBHOOK_SECRET;
  if (!secret || !sigHeader) return null;
  const expected = crypto.createHmac("sha256", secret).update(rawBody).digest("hex");
  if (!timingSafeEqual(expected, sigHeader)) return null;
  return JSON.parse(rawBody);
}

// ---------------------------------------------------------------- PayPal ----
export const paypalConfigured = () => !!(process.env.PAYPAL_CLIENT_ID && process.env.PAYPAL_CLIENT_SECRET);

function paypalBase(): string {
  return process.env.PAYPAL_ENV === "live" ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
}

async function paypalToken(): Promise<string> {
  const auth = Buffer.from(`${process.env.PAYPAL_CLIENT_ID}:${process.env.PAYPAL_CLIENT_SECRET}`).toString("base64");
  const res = await fetch(`${paypalBase()}/v1/oauth2/token`, {
    method: "POST",
    headers: { Authorization: `Basic ${auth}`, "Content-Type": "application/x-www-form-urlencoded" },
    body: "grant_type=client_credentials",
  });
  if (!res.ok) throw new Error(`PayPal auth error: ${await res.text()}`);
  return (await res.json()).access_token as string;
}

/** Creates a PayPal order and returns { approveUrl, paypalOrderId }. */
export async function paypalCreateOrder(ctx: CheckoutContext): Promise<{ approveUrl: string; paypalOrderId: string }> {
  const token = await paypalToken();
  const res = await fetch(`${paypalBase()}/v2/checkout/orders`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      intent: "CAPTURE",
      purchase_units: [
        {
          custom_id: ctx.orderId,
          description: ctx.pkg.name,
          amount: { currency_code: "USD", value: (ctx.pkg.priceCents / 100).toFixed(2) },
        },
      ],
      application_context: {
        return_url: `${ctx.baseUrl}/api/store/paypal/capture?order=${ctx.orderId}`,
        cancel_url: `${ctx.baseUrl}/store?cancelled=1`,
      },
    }),
  });
  if (!res.ok) throw new Error(`PayPal order error: ${await res.text()}`);
  const data = await res.json();
  const approve = data.links?.find((l: any) => l.rel === "approve")?.href;
  return { approveUrl: approve, paypalOrderId: data.id };
}

export async function paypalCapture(paypalOrderId: string): Promise<{ paid: boolean; custom_id?: string }> {
  const token = await paypalToken();
  const res = await fetch(`${paypalBase()}/v2/checkout/orders/${paypalOrderId}/capture`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
  });
  if (!res.ok) return { paid: false };
  const data = await res.json();
  const unit = data.purchase_units?.[0];
  const captured = data.status === "COMPLETED";
  return { paid: captured, custom_id: unit?.payments?.captures?.[0]?.custom_id ?? unit?.custom_id };
}

function timingSafeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}
