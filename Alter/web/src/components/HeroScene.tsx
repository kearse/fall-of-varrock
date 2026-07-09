// Fallen Varrock - hand-built ruined-city skyline. Broken towers, a cracked
// cathedral, breached walls, a blood moon, drifting smoke, fog and rising embers.
// Pure inline SVG + scoped CSS keyframes; sits behind the hero copy (page adds a
// dark scrim for legibility). Swap for real in-game art later by replacing output.
export function HeroScene({ className = "" }: { className?: string }) {
  const embers: Array<{ x: number; y: number; r: number; dur: number; delay: number; drift: number; o: number }> = [
    { x: 250, y: 470, r: 2.2, dur: 9, delay: 0, drift: 34, o: 0.9 },
    { x: 330, y: 500, r: 1.4, dur: 12, delay: 2.4, drift: -22, o: 0.7 },
    { x: 470, y: 460, r: 1.8, dur: 10, delay: 4.8, drift: 28, o: 0.8 },
    { x: 560, y: 480, r: 1.3, dur: 13, delay: 1.2, drift: -30, o: 0.65 },
    { x: 640, y: 440, r: 2.4, dur: 8, delay: 3.6, drift: 20, o: 0.95 },
    { x: 705, y: 470, r: 1.5, dur: 11, delay: 6.0, drift: -18, o: 0.7 },
    { x: 790, y: 455, r: 2.0, dur: 9.5, delay: 0.8, drift: 26, o: 0.85 },
    { x: 880, y: 490, r: 1.3, dur: 12.5, delay: 5.2, drift: -26, o: 0.6 },
    { x: 960, y: 465, r: 1.9, dur: 10.5, delay: 2.0, drift: 30, o: 0.8 },
    { x: 1060, y: 480, r: 1.4, dur: 13.5, delay: 7.0, drift: -20, o: 0.65 },
    { x: 1150, y: 470, r: 2.1, dur: 9, delay: 3.0, drift: 24, o: 0.85 },
    { x: 1290, y: 495, r: 1.5, dur: 11.5, delay: 5.8, drift: -28, o: 0.7 },
    { x: 150, y: 505, r: 1.6, dur: 12, delay: 4.0, drift: 18, o: 0.7 },
    { x: 615, y: 400, r: 1.6, dur: 8.5, delay: 1.6, drift: 14, o: 0.9 },
    { x: 845, y: 410, r: 1.4, dur: 9.5, delay: 6.6, drift: -16, o: 0.75 },
  ];

  return (
    <svg
      className={className}
      viewBox="0 0 1440 560"
      preserveAspectRatio="xMidYMid slice"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden
    >
      <style>{`
        @keyframes fvEmber {
          0%   { transform: translate(0, 0); opacity: 0; }
          8%   { opacity: var(--fv-o, 0.8); }
          55%  { opacity: var(--fv-o, 0.8); }
          100% { transform: translate(var(--fv-drift, 20px), -300px); opacity: 0; }
        }
        @keyframes fvFlicker {
          0%, 100% { opacity: 0.85; }
          18% { opacity: 0.45; }
          34% { opacity: 0.95; }
          52% { opacity: 0.55; }
          71% { opacity: 1; }
          86% { opacity: 0.6; }
        }
        @keyframes fvSmoke {
          0%   { transform: translate(0, 0) scale(1); opacity: 0; }
          15%  { opacity: 0.5; }
          70%  { opacity: 0.28; }
          100% { transform: translate(-46px, -150px) scale(1.9); opacity: 0; }
        }
        @keyframes fvFogA {
          0%, 100% { transform: translateX(0); }
          50% { transform: translateX(-70px); }
        }
        @keyframes fvFogB {
          0%, 100% { transform: translateX(0); }
          50% { transform: translateX(55px); }
        }
        @keyframes fvMoonPulse {
          0%, 100% { opacity: 0.85; }
          50% { opacity: 1; }
        }
        .fv-ember  { animation: fvEmber var(--fv-dur, 10s) linear infinite; }
        .fv-flick  { animation: fvFlicker 3.2s ease-in-out infinite; }
        .fv-flick2 { animation: fvFlicker 4.1s ease-in-out infinite reverse; }
        .fv-smoke  { animation: fvSmoke 16s ease-out infinite; }
        .fv-fog-a  { animation: fvFogA 26s ease-in-out infinite; }
        .fv-fog-b  { animation: fvFogB 34s ease-in-out infinite; }
        .fv-moon   { animation: fvMoonPulse 9s ease-in-out infinite; }
        @media (prefers-reduced-motion: reduce) {
          .fv-ember, .fv-flick, .fv-flick2, .fv-smoke, .fv-fog-a, .fv-fog-b, .fv-moon { animation: none; }
          .fv-ember { opacity: 0.5; }
        }
      `}</style>

      <defs>
        <linearGradient id="fvSky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#050304" />
          <stop offset="48%" stopColor="#120607" />
          <stop offset="78%" stopColor="#2c0a0b" />
          <stop offset="100%" stopColor="#4a100f" />
        </linearGradient>
        <radialGradient id="fvMoonGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#ef4444" stopOpacity="0.55" />
          <stop offset="35%" stopColor="#dc2626" stopOpacity="0.22" />
          <stop offset="100%" stopColor="#dc2626" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="fvMoonFace" cx="38%" cy="34%" r="72%">
          <stop offset="0%" stopColor="#fca5a5" />
          <stop offset="55%" stopColor="#ef4444" />
          <stop offset="100%" stopColor="#991b1b" />
        </radialGradient>
        <radialGradient id="fvFireGlow" cx="50%" cy="100%" r="80%">
          <stop offset="0%" stopColor="#f59e0b" stopOpacity="0.5" />
          <stop offset="45%" stopColor="#dc2626" stopOpacity="0.2" />
          <stop offset="100%" stopColor="#dc2626" stopOpacity="0" />
        </radialGradient>
        <linearGradient id="fvFog" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#a39a90" stopOpacity="0" />
          <stop offset="100%" stopColor="#a39a90" stopOpacity="0.16" />
        </linearGradient>
        <filter id="fvBlur" x="-60%" y="-60%" width="220%" height="220%">
          <feGaussianBlur stdDeviation="10" />
        </filter>
        <filter id="fvBlurSoft" x="-40%" y="-40%" width="180%" height="180%">
          <feGaussianBlur stdDeviation="4" />
        </filter>
      </defs>

      {/* Sky */}
      <rect width="1440" height="560" fill="url(#fvSky)" />

      {/* Sparse dying stars */}
      <g fill="#e8e2d9">
        {[[90, 60], [230, 118], [370, 48], [540, 96], [760, 40], [930, 88], [1010, 46], [1330, 96], [1402, 52], [60, 168], [480, 170], [860, 140]].map(
          ([x, y], i) => (
            <circle key={i} cx={x} cy={y} r={i % 3 === 0 ? 1.4 : 0.9} opacity={0.22 + (i % 4) * 0.08} />
          )
        )}
      </g>

      {/* Blood moon */}
      <g className="fv-moon">
        <circle cx="1120" cy="128" r="150" fill="url(#fvMoonGlow)" />
        <circle cx="1120" cy="128" r="52" fill="url(#fvMoonFace)" />
        <circle cx="1102" cy="112" r="9" fill="#b91c1c" opacity="0.5" />
        <circle cx="1138" cy="140" r="13" fill="#b91c1c" opacity="0.4" />
        <circle cx="1128" cy="104" r="5" fill="#b91c1c" opacity="0.45" />
        {/* thin cloud shred across the moon */}
        <path d="M1040 118 Q1110 104 1200 122 Q1120 132 1040 126 Z" fill="#0a0405" opacity="0.55" filter="url(#fvBlurSoft)" />
      </g>

      {/* Fire glows behind the skyline (burning districts) */}
      <ellipse cx="620" cy="470" rx="240" ry="120" fill="url(#fvFireGlow)" className="fv-flick" />
      <ellipse cx="1010" cy="480" rx="190" ry="100" fill="url(#fvFireGlow)" className="fv-flick2" opacity="0.8" />
      <ellipse cx="240" cy="490" rx="160" ry="90" fill="url(#fvFireGlow)" className="fv-flick2" opacity="0.6" />

      {/* Rising smoke columns (blurred, drifting) */}
      <g fill="#171112">
        <g className="fv-smoke" style={{ transformOrigin: "620px 430px" }}>
          <ellipse cx="620" cy="410" rx="34" ry="52" filter="url(#fvBlur)" />
          <ellipse cx="600" cy="350" rx="26" ry="40" filter="url(#fvBlur)" />
        </g>
        <g className="fv-smoke" style={{ transformOrigin: "860px 440px", animationDelay: "-7s" }}>
          <ellipse cx="860" cy="420" rx="30" ry="46" filter="url(#fvBlur)" />
          <ellipse cx="880" cy="360" rx="22" ry="36" filter="url(#fvBlur)" />
        </g>
        <g className="fv-smoke" style={{ transformOrigin: "300px 460px", animationDelay: "-12s" }}>
          <ellipse cx="300" cy="440" rx="28" ry="42" filter="url(#fvBlur)" />
        </g>
      </g>

      {/* ---- FAR RUINED SKYLINE (full width, hazy) ---- */}
      <g fill="#190a0b" opacity="0.75">
        <path d="M0 452 h48 v-34 l10 8 v26 h30 v-52 l8 6 4-12 6 14 v44 h42 v-24 l14 -10 v34 h36 v-58 l12 10 v48 h50 v-30 h20 v30 Z" />
        <path d="M1090 452 h34 v-40 l12 8 v32 h44 v-56 l10 8 6-10 6 16 v42 h38 v-26 l16 -8 v34 h44 v-48 l12 10 v38 h48 v-30 h18 v96 h-288 Z" />
        <path d="M0 448 Q240 430 480 446 T960 442 T1440 446 V560 H0 Z" />
      </g>

      {/* ---- MID LAYER: THE FALLEN CITY ---- */}
      <g fill="#0d0607">
        {/* west gate tower - top blown off */}
        <path d="M150 470 v-138 l10 -16 6 22 8 -30 10 24 6 -12 8 34 v116 Z" />
        {/* breached west wall with a gaping hole */}
        <path d="M198 470 v-84 h14 v-12 h12 v12 h14 v-12 h12 v12 h14 v84 Z M264 470 v-60 l14 -14 12 16 v58 Z" />
        <path d="M198 386 h116 v10 h-116 Z" opacity="0" />
        {/* collapsed rowhouses */}
        <path d="M330 470 v-52 l24 -22 24 18 v-30 l8 -6 10 10 v82 Z" />
        <path d="M400 470 v-64 l18 12 6 -20 10 26 v46 Z" />

        {/* === Varrock palace, cracked === */}
        {/* left palace tower, crenellated but chipped */}
        <path d="M470 470 v-172 h11 v-15 h12 v15 h13 v-15 h12 v8 l8 -12 v19 h13 v-15 h11 v187 Z" />
        {/* main keep wall */}
        <path d="M540 470 v-132 h13 v-13 h14 v13 h14 v-13 h14 v13 h14 v-6 l10 -14 4 20 h14 v-13 h14 v13 h13 v132 Z" />
        {/* central great tower - spire snapped mid-height */}
        <path d="M610 338 v-96 l8 -14 8 10 6 -26 8 18 4 -10 10 30 8 -16 6 24 v80 Z" />
        {/* the fallen spire, toppled against the wall */}
        <path d="M672 300 l64 44 -8 10 -62 -38 Z" />
        {/* right palace tower */}
        <path d="M700 470 v-152 h10 v-14 h12 v14 h12 v-14 h12 v14 h12 v-14 h10 v166 Z" />
        {/* breach in the keep - jagged hole (drawn as bite from the base) */}
        <path d="M584 470 v-52 l10 -16 10 10 8 -18 10 22 8 -10 6 24 v40 Z" fill="#050304" />

        {/* === ruined cathedral (east of palace) === */}
        {/* nave */}
        <path d="M800 470 v-96 l52 -34 52 34 v96 Z" />
        {/* bell tower with cracked crown */}
        <path d="M902 470 v-158 h9 v-13 h11 v13 h11 v-8 l9 -14 5 18 h10 v-13 h9 v175 Z" />
        {/* collapsed transept */}
        <path d="M966 470 v-58 l20 -18 8 12 -6 8 16 14 v42 Z" />

        {/* east wall + shattered turret */}
        <path d="M1020 470 v-72 h12 v-11 h11 v11 h12 v-11 h11 v11 h12 v72 Z" />
        <path d="M1096 470 v-110 l8 -20 8 14 6 -24 10 30 6 -8 6 26 v92 Z" />
        {/* east-side burnt houses */}
        <path d="M1160 470 v-44 l20 -20 20 16 v48 Z" />
        <path d="M1206 470 v-58 l12 8 8 -18 10 24 v44 Z" />
        <path d="M1260 470 v-38 l16 -16 18 14 v40 Z" />
      </g>

      {/* Cracks glowing with inner fire on the keep + cathedral */}
      <g stroke="#f59e0b" strokeWidth="1.6" fill="none" strokeLinecap="round" className="fv-flick" opacity="0.8">
        <path d="M566 470 l6 -30 -8 -18 10 -24" />
        <path d="M736 452 l-8 -26 10 -18 -6 -22" />
        <path d="M918 462 l6 -34 -8 -20 8 -26" />
      </g>

      {/* Rose window - shattered, glowing like a dying ember */}
      <g className="fv-flick2">
        <circle cx="852" cy="392" r="17" fill="#dc2626" opacity="0.55" />
        <circle cx="852" cy="392" r="17" fill="none" stroke="#f59e0b" strokeWidth="2" opacity="0.85" />
        <path d="M852 375 v34 M835 392 h34 M840 380 l24 24 M864 380 l-24 24" stroke="#0d0607" strokeWidth="2.4" />
        {/* missing shard */}
        <path d="M852 392 L866 380 A17 17 0 0 0 852 375 Z" fill="#0d0607" />
      </g>

      {/* Burning windows - ember light, flickering */}
      <g fill="#f59e0b">
        <rect x="492" y="330" width="9" height="14" rx="1" className="fv-flick" />
        <rect x="560" y="372" width="9" height="14" rx="1" className="fv-flick2" opacity="0.8" />
        <rect x="630" y="290" width="9" height="15" rx="1" className="fv-flick" />
        <rect x="646" y="356" width="9" height="14" rx="1" className="fv-flick2" />
        <rect x="716" y="348" width="9" height="14" rx="1" className="fv-flick" opacity="0.85" />
        <rect x="1042" y="420" width="8" height="12" rx="1" className="fv-flick2" opacity="0.7" />
      </g>
      {/* Cold dead windows - faint red, the occupiers' light */}
      <g fill="#dc2626" opacity="0.5">
        <rect x="522" y="360" width="8" height="12" rx="1" />
        <rect x="688" y="368" width="8" height="12" rx="1" />
        <rect x="170" y="380" width="8" height="12" rx="1" className="fv-flick2" />
        <rect x="928" y="340" width="8" height="12" rx="1" className="fv-flick" />
        <rect x="1114" y="392" width="8" height="12" rx="1" />
      </g>

      {/* Torn war banner still clinging to the left palace tower */}
      <g>
        <rect x="500" y="266" width="2" height="34" fill="#050304" />
        <path d="M502 270 h24 l-6 6 5 5 -9 2 6 7 -20 3 Z" fill="#7f1d1d" />
      </g>

      {/* Rising embers */}
      <g>
        {embers.map((e, i) => (
          <circle
            key={i}
            cx={e.x}
            cy={e.y}
            r={e.r}
            fill={i % 3 === 0 ? "#f59e0b" : "#ef4444"}
            className="fv-ember"
            style={
              {
                "--fv-dur": `${e.dur}s`,
                "--fv-drift": `${e.drift}px`,
                "--fv-o": e.o,
                animationDelay: `${-e.delay}s`,
              } as React.CSSProperties
            }
          />
        ))}
      </g>

      {/* Drifting fog banks */}
      <g>
        <path className="fv-fog-a" d="M-80 500 Q220 468 560 496 T1180 488 T1560 498 V560 H-80 Z" fill="url(#fvFog)" />
        <path className="fv-fog-b" d="M-80 522 Q360 496 760 518 T1560 512 V560 H-80 Z" fill="url(#fvFog)" opacity="0.8" />
      </g>

      {/* Foreground rubble ridge - broken stakes and debris */}
      <g fill="#040203">
        <path d="M0 512 Q180 496 340 508 L370 486 380 508 Q560 494 720 506 L742 480 756 504 Q940 492 1120 506 L1146 484 1158 504 Q1300 496 1440 508 V560 H0 Z" />
        {/* broken palisade stakes */}
        <path d="M300 508 l4 -34 6 4 2 26 Z M320 506 l3 -22 5 3 1 17 Z" />
        <path d="M1180 506 l5 -30 6 4 1 24 Z M1202 505 l3 -20 5 3 1 15 Z" />
        {/* fallen sword hilt silhouette */}
        <path d="M840 504 l26 -10 2 4 -26 10 Z M858 494 l3 -10 4 1 -3 10 Z" />
      </g>

      {/* Low ember-light wash on the horizon fog */}
      <rect x="0" y="430" width="1440" height="130" fill="url(#fvFog)" opacity="0.5" />
    </svg>
  );
}
