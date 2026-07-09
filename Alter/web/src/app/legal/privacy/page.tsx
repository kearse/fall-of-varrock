import { LegalPage, type LegalSection } from "@/components/LegalPage";

export const metadata = { title: "Privacy Policy · Fall of Varrock" };

const SECTIONS: LegalSection[] = [
  {
    h: "Overview",
    body: [
      "This policy explains what information Fall of Varrock collects, how we use it, and the choices you have. We aim to collect as little as possible to run the game and community.",
    ],
  },
  {
    h: "Information we collect",
    body: [
      [
        "Account data: your username, a securely hashed password, and an optional email address.",
        "Gameplay data: stats, progress, inventory, ranks, and other in-game state needed to save your character.",
        "Community content: forum posts, guides, messages, and profile details you choose to share.",
        "Optional links: your Discord ID if you link your account for roles or notifications.",
        "Technical data: IP address, browser/client information, and basic logs used for security and debugging.",
      ],
      "We do not collect payment card numbers - see \"Payments\" below.",
    ],
  },
  {
    h: "How we use information",
    body: [
      [
        "Operate your account and save your in-game progress.",
        "Display hiscores, profiles, and forum activity.",
        "Deliver donor points, membership, and purchased items to your account.",
        "Provide support and respond to reports and appeals.",
        "Protect the Service against fraud, abuse, cheating, and security threats.",
      ],
    ],
  },
  {
    h: "Payments",
    body: [
      "Purchases are handled by third-party processors (Stripe, PayPal, and Coinbase Commerce). They collect and process your payment details directly under their own privacy policies. We receive only confirmation of payment and the information needed to deliver your purchase (such as your username and order ID). We never see or store full card numbers.",
    ],
  },
  {
    h: "Cookies & sessions",
    body: [
      "We use a single sign-on cookie to keep you logged in across the website and forum. We do not use third-party advertising or cross-site tracking cookies.",
    ],
  },
  {
    h: "How information is shared",
    body: [
      "We do not sell your personal information. We share data only with:",
      [
        "Payment processors, to complete purchases you make.",
        "Discord, if you choose to link your account.",
        "Service providers (e.g. hosting) that help us run the Service under confidentiality obligations.",
        "Authorities, where required by law or to protect users and the Service.",
      ],
    ],
  },
  {
    h: "Data retention",
    body: [
      "We keep account and gameplay data while your account is active so your progress is preserved. Logs are kept for a limited period for security. You can request deletion of your account and associated personal data (some records may be retained where legally required).",
    ],
  },
  {
    h: "Your rights",
    body: [
      "Depending on where you live, you may have the right to access, correct, export, or delete your personal data, and to object to certain processing. To exercise these rights, contact us through the forum's Support board or Discord.",
    ],
  },
  {
    h: "Children",
    body: [
      "The Service is not directed to children under 13 (or the minimum digital-consent age in your country). If you believe a child has provided us personal data without consent, contact us and we will remove it.",
    ],
  },
  {
    h: "Security",
    body: [
      "Passwords are stored only as salted bcrypt hashes, never in plain text, and access to data is restricted. No system is perfectly secure, so we cannot guarantee absolute security - please use a unique password.",
    ],
  },
  {
    h: "Changes & contact",
    body: [
      "We may update this policy and will announce material changes on the website or forum. For privacy questions or requests, reach us via the community forum's Support board or our Discord server.",
    ],
  },
];

export default function PrivacyPage() {
  return (
    <LegalPage
      title="Privacy Policy"
      updated="June 22, 2026"
      intro="Your privacy matters. Here is what we collect and why, in plain language."
      sections={SECTIONS}
    />
  );
}
