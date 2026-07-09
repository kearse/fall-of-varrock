import { LegalPage, type LegalSection } from "@/components/LegalPage";

export const metadata = { title: "Terms of Service · Fall of Varrock" };

const SECTIONS: LegalSection[] = [
  {
    h: "Acceptance & eligibility",
    body: [
      "By creating an account or playing Fall of Varrock (the \"Service\"), you agree to these Terms of Service. If you do not agree, do not use the Service.",
      "You must be at least 13 years old (or the minimum digital-consent age in your country) to use the Service. If you are under 18, you confirm a parent or guardian has reviewed and agreed to these terms on your behalf.",
    ],
  },
  {
    h: "About the Service",
    body: [
      "Fall of Varrock is a free, fan-made private game server and community website. It is not affiliated with, endorsed by, or sponsored by Jagex Ltd. \"RuneScape\" and \"Old School RuneScape\" are trademarks of Jagex Ltd.",
      "The Service is provided for entertainment only. We may add, change, suspend, or discontinue any part of it - including worlds, content, and features - at any time, with or without notice.",
    ],
  },
  {
    h: "Your account",
    body: [
      "A single set of credentials works across the game, this website, and the forum. You are responsible for everything that happens under your account.",
      [
        "Keep your password secure and do not share your account.",
        "Do not buy, sell, trade, or transfer accounts for real-world value.",
        "Do not register accounts using someone else's identity or impersonate staff.",
        "Notify us promptly if you believe your account has been compromised.",
      ],
    ],
  },
  {
    h: "Acceptable use",
    body: [
      "To keep the realm fair and fun, you agree not to:",
      [
        "Use bots, macros, auto-clickers, or any third-party automation.",
        "Exploit bugs, duplication glitches, or unintended mechanics instead of reporting them.",
        "Engage in real-world trading - exchanging in-game items, currency, or accounts for money or goods outside the Service.",
        "Harass, threaten, defraud, or post hateful, illegal, or sexually explicit content.",
        "Attempt to disrupt, overload, reverse-engineer for attack, or gain unauthorized access to our servers or other players' accounts.",
      ],
      "Violations may result in warnings, mutes, item removal, or permanent bans at our discretion.",
    ],
  },
  {
    h: "Virtual items & currency",
    body: [
      "All in-game items, currencies, ranks, donator points, and membership perks are virtual. They have no real-world monetary value, are not your property, and cannot be redeemed for cash.",
      "We may modify, reset, or remove virtual items and balances as needed to operate, balance, or secure the game. Membership and donator perks are a license to access features, not ownership of them.",
    ],
  },
  {
    h: "Donations & purchases",
    body: [
      "Donations and membership purchases are optional and help cover hosting and development. Perks are designed to be cosmetic or convenience-based and are not intended to be pay-to-win.",
      "Purchases are governed by our Refund Policy. Payments are processed by third-party providers (e.g. Stripe, PayPal, Coinbase Commerce) subject to their own terms.",
    ],
  },
  {
    h: "Intellectual property & your content",
    body: [
      "Original code, art, names, and content we create for Fall of Varrock belong to us. RuneScape assets and references remain the property of Jagex Ltd.",
      "You retain ownership of content you post (forum posts, guides, screenshots), but grant us a non-exclusive, royalty-free license to host, display, and distribute it within the Service.",
    ],
  },
  {
    h: "Moderation & termination",
    body: [
      "We may suspend or terminate any account, and remove any content, at any time for any reason - including suspected rule-breaking - without obligation to provide notice or a refund. You may stop using the Service at any time.",
    ],
  },
  {
    h: "Disclaimers & limitation of liability",
    body: [
      "The Service is provided \"as is\" and \"as available\", without warranties of any kind. We do not guarantee the Service will be uninterrupted, secure, or error-free, or that progress and virtual items will be preserved.",
      "To the fullest extent permitted by law, we are not liable for any indirect, incidental, or consequential damages, or for loss of data or virtual items, arising from your use of the Service. Where liability cannot be excluded, it is limited to the amount you paid us in the preceding 12 months.",
    ],
  },
  {
    h: "Changes to these terms",
    body: [
      "We may update these Terms from time to time. Material changes will be announced on the website or forum. Continued use after changes take effect means you accept the revised Terms.",
    ],
  },
  {
    h: "Contact",
    body: [
      "Questions about these Terms can be raised on the community forum's Support board or our Discord server.",
    ],
  },
];

export default function TermsPage() {
  return (
    <LegalPage
      title="Terms of Service"
      updated="June 22, 2026"
      intro="These terms govern your use of the Fall of Varrock game, website, and forum. Please read them carefully."
      sections={SECTIONS}
    />
  );
}
