import { Collections, OrderDoc } from "./collections";
import { StorePackage } from "./store";

/**
 * Records a paid order and queues the matching entitlement for in-game delivery.
 * Idempotent on orderId so a webhook delivered twice never grants twice.
 */
export async function fulfillOrder(opts: {
  orderId: string;
  loginUsername: string;
  pkg: StorePackage;
  provider: OrderDoc["provider"];
  providerRef: string;
}): Promise<{ created: boolean }> {
  const orders = await Collections.orders();
  const entitlements = await Collections.entitlements();

  // Idempotency guard.
  const existing = await orders.findOne({ orderId: opts.orderId });
  if (existing && existing.status === "paid") return { created: false };

  const now = Date.now();
  await orders.updateOne(
    { orderId: opts.orderId },
    {
      $set: {
        orderId: opts.orderId,
        loginUsername: opts.loginUsername,
        provider: opts.provider,
        providerRef: opts.providerRef,
        packageId: opts.pkg.id,
        amount: opts.pkg.priceCents,
        currency: "usd",
        status: "paid",
        paidAt: now,
      },
      $setOnInsert: { createdAt: now },
    },
    { upsert: true }
  );

  await entitlements.insertOne({
    loginUsername: opts.loginUsername,
    kind: opts.pkg.entitlement.kind,
    payload: opts.pkg.entitlement.payload as any,
    orderId: opts.orderId,
    applied: false,
    createdAt: now,
  });

  return { created: true };
}

export async function getPurchaseHistory(loginUsername: string): Promise<OrderDoc[]> {
  const orders = await Collections.orders();
  return orders.find({ loginUsername, status: "paid" }).sort({ paidAt: -1 }).toArray();
}
