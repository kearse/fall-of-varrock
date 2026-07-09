import type { Metadata } from "next";
import "./globals.css";
import { SiteHeader } from "@/components/SiteHeader";
import { SiteFooter } from "@/components/SiteFooter";
import { getSession } from "@/lib/session";

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL || "http://localhost:3000"),
  title: "Fall of Varrock",
  description:
    "Fall of Varrock is a free Old School RuneScape private server. Varrock has fallen and Gielinor lies in ruin. Join the war campaigns, raise companions, climb the feudal ranks, and reclaim the realm.",
  openGraph: {
    title: "Fall of Varrock",
    description: "A free OSRS private server. The city has fallen. Reclaim the ruins.",
    url: "/",
    siteName: "Fall of Varrock",
    type: "website",
  },
  twitter: {
    card: "summary_large_image",
    title: "Fall of Varrock",
    description: "A free OSRS private server. The city has fallen. Reclaim the ruins.",
  },
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const session = await getSession();
  return (
    <html lang="en">
      <body>
        <SiteHeader
          session={session}
          forumUrl={process.env.FORUM_URL || "/forum"}
          guidesUrl={process.env.FORUM_URL ? `${process.env.FORUM_URL}/category/13` : "/guides"}
        />
        <main className="mx-auto w-full max-w-6xl px-4 py-10">{children}</main>
        <SiteFooter />
      </body>
    </html>
  );
}
