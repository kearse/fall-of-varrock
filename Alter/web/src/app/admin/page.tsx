import { redirect } from "next/navigation";
import { getCurrentAccount } from "@/lib/session";
import { isStaff } from "@/lib/accounts";
import { NewsComposer } from "@/components/admin/NewsComposer";
import { GuideComposer } from "@/components/admin/GuideComposer";

export const metadata = { title: "Admin · Fall of Varrock" };

export default async function AdminPage() {
  const account = await getCurrentAccount();
  if (!account) redirect("/login");
  if (!isStaff(account)) redirect("/");

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      <div>
        <h1 className="text-3xl font-bold text-lumbridge-gold">Staff Admin</h1>
        <p className="text-sm text-lumbridge-parchment/60">
          Signed in as {account.currentDisplayName} ({account.roles.join(", ")})
        </p>
      </div>

      <section className="card">
        <h2 className="mb-4 text-xl font-semibold text-lumbridge-gold">Post an update</h2>
        <p className="mb-4 text-sm text-lumbridge-parchment/60">
          Publishes to the News page and posts to Discord (if a webhook is configured). You can
          also do this in-game with <code className="rounded bg-black/40 px-1">::update Title | Body</code>.
        </p>
        <NewsComposer />
      </section>

      <section className="card">
        <h2 className="mb-4 text-xl font-semibold text-lumbridge-gold">Publish a guide</h2>
        <GuideComposer />
      </section>
    </div>
  );
}
