import { LegalPage, type LegalSection } from "@/components/LegalPage";

export const metadata = { title: "Refund Policy · Fall of Varrock" };

const SECTIONS: LegalSection[] = [
  {
    h: "Digital, virtual goods",
    body: [
      "Everything sold for Fall of Varrock - donator points, membership, and item bundles - is digital and virtual. Purchases are delivered to your account in-game, typically on your next login, and have no real-world monetary value.",
    ],
  },
  {
    h: "All sales are final",
    body: [
      "Because purchases are virtual goods that are delivered and consumed in-game, all sales are final and non-refundable once the purchase has been delivered to your account. By completing a purchase you acknowledge this and waive any statutory cooling-off period to the extent permitted by law.",
    ],
  },
  {
    h: "When we will help",
    body: [
      "We will review and, where appropriate, refund or re-deliver in these situations:",
      [
        "Non-delivery: you were charged but the purchase did not arrive in-game after a reasonable time and a relog.",
        "Duplicate or accidental charge: you were billed more than once for the same order.",
        "Unauthorized charge: a payment was made without your permission (please also contact your payment provider).",
      ],
      "Contact us within 14 days of the charge with your username and order ID so we can investigate.",
    ],
  },
  {
    h: "Chargebacks",
    body: [
      "Please contact us before opening a dispute - we can usually resolve issues quickly. Filing a chargeback or payment dispute without contacting us may result in removal of the purchased items and suspension of your account until the matter is resolved.",
    ],
  },
  {
    h: "Memberships",
    body: [
      "Membership grants access to perks for a set period. If billed on a recurring basis, you can cancel at any time to stop future charges; cancellation stops renewals but does not refund the current or past periods. Unused membership time is not refundable.",
    ],
  },
  {
    h: "How to request",
    body: [
      "Open a thread on the forum's Support board or message us on Discord. Include your in-game username, the package purchased, the approximate date, and your order ID (shown on your account's purchase history). Requests are usually reviewed within a few business days.",
    ],
  },
  {
    h: "Contact",
    body: [
      "For any billing question, reach us through the community forum's Support board or our Discord server.",
    ],
  },
];

export default function RefundPage() {
  return (
    <LegalPage
      title="Refund Policy"
      updated="June 22, 2026"
      intro="Donations keep the realm running. This explains how purchases and refunds work for our virtual goods."
      sections={SECTIONS}
    />
  );
}
