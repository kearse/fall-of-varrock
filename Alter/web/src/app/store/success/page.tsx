import Link from "next/link";
import { Collections } from "@/lib/collections";
import { getPackage, formatPrice } from "@/lib/store";

export const dynamic = "force-dynamic";

export default async function SuccessPage({ searchParams }: { searchParams: { order?: string } }) {
  const orderId = searchParams.order;
  let line: string | null = null;
  if (orderId) {
    const orders = await Collections.orders();
    const order = await orders.findOne({ orderId });
    if (order) {
      const pkg = getPackage(order.packageId);
      line = pkg ? `${pkg.name} - ${formatPrice(pkg.priceCents)}` : order.packageId;
    }
  }

  return (
    <div className="mx-auto max-w-lg text-center">
      <div className="card space-y-4">
        <div className="text-4xl">✅</div>
        <h1 className="text-2xl font-bold text-lumbridge-gold">Thank you!</h1>
        {line && <p className="text-lumbridge-parchment/80">{line}</p>}
        <p className="text-sm text-lumbridge-parchment/70">
          Your purchase is queued. <strong>Log into the game</strong> and it will be delivered to you
          automatically on login. Donor points and membership apply instantly; items go to your bank.
        </p>
        <p className="text-xs text-lumbridge-parchment/60">
          Bought membership for the <strong>Discord role</strong>? It syncs only after your Discord is
          linked — generate a link code on <Link href="/account" className="underline">your account page</Link>{" "}
          and use <code>/link</code> in our Discord.
        </p>
        <div className="flex justify-center gap-3">
          <Link href="/store" className="btn-ghost">Back to store</Link>
          <Link href="/account/purchases" className="btn-gold">My purchases</Link>
        </div>
      </div>
    </div>
  );
}
