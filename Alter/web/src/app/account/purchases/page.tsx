import { redirect } from "next/navigation";
import { getCurrentAccount } from "@/lib/session";
import { getPurchaseHistory } from "@/lib/entitlements";
import { getPackage, formatPrice } from "@/lib/store";

export const metadata = { title: "My Purchases · Fall of Varrock" };
export const dynamic = "force-dynamic";

export default async function PurchasesPage() {
  const account = await getCurrentAccount();
  if (!account) redirect("/login");

  const orders = await getPurchaseHistory(account.loginUsername);

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <h1 className="text-3xl font-bold text-lumbridge-gold">My Purchases</h1>

      <div className="grid gap-3 sm:grid-cols-3">
        <div className="card text-center">
          <div className="text-2xl font-bold text-lumbridge-gold">{account.donorPoints ?? 0}</div>
          <div className="text-xs text-lumbridge-parchment/60">Donor points</div>
        </div>
        <div className="card col-span-2 text-center">
          <div className="text-lg font-semibold capitalize text-lumbridge-gold">
            {account.membership?.expires && account.membership.expires > Date.now()
              ? `${account.membership.tier} (until ${new Date(account.membership.expires).toLocaleDateString()})`
              : "No active membership"}
          </div>
          <div className="text-xs text-lumbridge-parchment/60">Membership</div>
        </div>
      </div>

      <div className="card p-0">
        {orders.length === 0 && <p className="px-4 py-8 text-center text-lumbridge-parchment/50">No purchases yet.</p>}
        {orders.map((o) => {
          const pkg = getPackage(o.packageId);
          return (
            <div key={o.orderId} className="flex items-center justify-between border-b border-white/5 px-4 py-3 last:border-0">
              <div>
                <div className="font-medium">{pkg?.name ?? o.packageId}</div>
                <div className="text-xs text-lumbridge-parchment/50">
                  {new Date(o.paidAt ?? o.createdAt).toLocaleString()} · {o.provider}
                </div>
              </div>
              <div className="text-right text-sm">{formatPrice(o.amount)}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
