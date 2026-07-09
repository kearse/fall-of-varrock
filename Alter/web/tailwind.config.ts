import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // "Fall of Varrock" — Fallen Crimson & Ash. Near-black ruined-city base,
        // blood-crimson primary, ember-amber warm secondary, ash-toned text.
        // Legacy `lumbridge-*` names are KEPT and remapped (gold -> crimson) so every
        // existing component re-themes at once with no per-file class changes.
        lumbridge: {
          stone: "#0b0708",        // page background (deepest)
          stone2: "#140d0e",       // surfaces / cards
          stone3: "#1e1416",       // raised surfaces
          bronze: "#3a2226",       // warm dark borders
          parchment: "#e8e2d9",    // ash text
          parchmentdark: "#a39a90",// muted ash
          // PRIMARY accent = crimson (legacy `gold` names point here)
          gold: "#dc2626",
          goldlight: "#ef4444",
          // semantic aliases
          crimson: "#dc2626",
          crimsonlight: "#ef4444",
          crimsondark: "#b91c1c",
          // WARM secondary = ember (for gradient warmth + highlights)
          ember: "#f59e0b",
          emberlight: "#fbbf24",
          ash: "#e8e2d9",
          ashdark: "#a39a90",
          // functional
          green: "#22c55e",
          blood: "#ef4444",
          bloodlight: "#f87171",
          // legacy neon names kept as harmless aliases (a few utilities still name them)
          violet: "#dc2626",
          indigo: "#b91c1c",
          cyan: "#f59e0b",
          pink: "#ef4444",
        },
      },
      fontFamily: {
        display: ["Space Grotesk", "Inter", "system-ui", "sans-serif"],
        body: ["Inter", "system-ui", "sans-serif"],
      },
      boxShadow: {
        glow: "0 0 0 1px rgba(220,38,38,0.35), 0 8px 40px -10px rgba(220,38,38,0.5)",
        "glow-cyan": "0 0 0 1px rgba(245,158,11,0.4), 0 8px 40px -8px rgba(245,158,11,0.5)",
        "glow-pink": "0 0 0 1px rgba(239,68,68,0.4), 0 8px 40px -8px rgba(239,68,68,0.5)",
        glass: "inset 0 1px 0 rgba(255,255,255,0.05), 0 10px 40px -12px rgba(0,0,0,0.85)",
      },
      backgroundImage: {
        // Title accent: crimson -> ember. Buttons: crimson -> deep crimson.
        "brand-gradient": "linear-gradient(120deg, #ef4444 0%, #f59e0b 100%)",
        "gold-gradient": "linear-gradient(120deg, #ef4444 0%, #b91c1c 100%)",
        "ember-gradient": "linear-gradient(120deg, #f59e0b 0%, #dc2626 100%)",
      },
      keyframes: {
        "fade-up": { "0%": { opacity: "0", transform: "translateY(24px)" }, "100%": { opacity: "1", transform: "translateY(0)" } },
        "scale-in": { "0%": { opacity: "0", transform: "scale(0.94)" }, "100%": { opacity: "1", transform: "scale(1)" } },
        "gradient-x": { "0%,100%": { backgroundPosition: "0% 50%" }, "50%": { backgroundPosition: "100% 50%" } },
        aurora: {
          "0%": { transform: "translate(-10%, -10%) rotate(0deg)" },
          "50%": { transform: "translate(10%, 10%) rotate(180deg)" },
          "100%": { transform: "translate(-10%, -10%) rotate(360deg)" },
        },
        float: { "0%,100%": { transform: "translateY(0)" }, "50%": { transform: "translateY(-12px)" } },
        shimmer: { "0%": { transform: "translateX(-150%)" }, "100%": { transform: "translateX(150%)" } },
        "pulse-dot": { "0%,100%": { opacity: "1" }, "50%": { opacity: "0.35" } },
        "spin-slow": { to: { transform: "rotate(360deg)" } },
      },
      animation: {
        "fade-up": "fade-up 0.6s cubic-bezier(0.16,1,0.3,1) both",
        "scale-in": "scale-in 0.5s cubic-bezier(0.16,1,0.3,1) both",
        "gradient-x": "gradient-x 6s ease infinite",
        aurora: "aurora 22s linear infinite",
        "aurora-slow": "aurora 34s linear infinite",
        float: "float 6s ease-in-out infinite",
        shimmer: "shimmer 2.5s ease-in-out infinite",
        "pulse-dot": "pulse-dot 2s ease-in-out infinite",
        "spin-slow": "spin-slow 14s linear infinite",
      },
    },
  },
  plugins: [],
};

export default config;
