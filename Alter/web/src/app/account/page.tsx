import Link from "next/link";
import { redirect } from "next/navigation";
import { getCurrentAccount } from "@/lib/session";
import { DiscordLinkPanel } from "@/components/account/DiscordLinkPanel";

export const metadata = { title: "Account · Fall of Varrock" };
export const dynamic = "force-dynamic";

export default async function AccountPage() {
  const account = await getCurrentAccount();
  if (!account) redirect("/login");

  const membershipActive = account.membership?.expires != null && account.membership.expires > Date.now();

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <h1 className="text-3xl font-bold text-lumbridge-gold">{account.currentDisplayName}</h1>

      <div className="grid gap-3 sm:grid-cols-3">
        <div className="card text-center">
          <div className="text-2xl font-bold text-lumbridge-gold">{account.donorPoints ?? 0}</div>
          <div className="text-xs text-lumbridge-parchment/60">Donor points</div>
        </div>
        <div className="card text-center">
          <div className="text-lg font-semibold capitalize text-lumbridge-gold">
            {membershipActive ? account.membership.tier : "-"}
          </div>
          <div className="text-xs text-lumbridge-parchment/60">Membership</div>
        </div>
        <Link href="/account/purchases" className="card flex items-center justify-center text-center hover:border-lumbridge-gold/40">
          <span className="text-sm text-lumbridge-gold">View purchases →</span>
        </Link>
      </div>

      <section className="card">
        <h2 className="mb-3 text-xl font-semibold text-lumbridge-gold">Link Discord</h2>
        <DiscordLinkPanel />
      </section>

      <section className="card">
        <h2 className="mb-2 text-xl font-semibold text-lumbridge-gold">Profile</h2>
        <p className="text-sm text-lumbridge-parchment/70">
          View your public profile and hiscores:{" "}
          <Link href={`/player/${encodeURIComponent(account.currentDisplayName)}`} className="text-lumbridge-gold hover:underline">
            /player/{account.currentDisplayName}
          </Link>
        </p>
      </section>
    </div>
  );
}
