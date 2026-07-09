"use client";

import { useRef, useState } from "react";
import {
  VOTE_SITES,
  voteUrl,
  siteIcon,
  siteCooldownHours,
  siteRewardLabel,
} from "@/lib/vote";

/** Ghost cards shown while no toplist callbacks are configured yet. */
const PLACEHOLDER_SLOTS = [
  { icon: "🗡️", name: "Toplist slot I" },
  { icon: "🛡️", name: "Toplist slot II" },
  { icon: "🏆", name: "Toplist slot III" },
];

export function VotePanel({ defaultName }: { defaultName: string }) {
  const [username, setUsername] = useState(defaultName);
  const [nudge, setNudge] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const name = username.trim();
  const ready = name.length > 0;
  const hasSites = VOTE_SITES.length > 0;

  function promptForName() {
    setNudge(true);
    inputRef.current?.focus();
  }

  return (
    <div className="space-y-6">
      {/* ---- Username bar ---- */}
      <div
        className={`card mx-auto max-w-2xl transition-colors ${
          nudge && !ready ? "border-lumbridge-gold/60" : ""
        }`}
      >
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <div className="flex-1">
            <label className="label" htmlFor="vote-username">
              Your login name
            </label>
            <input
              id="vote-username"
              ref={inputRef}
              className="input"
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
                if (e.target.value.trim()) setNudge(false);
              }}
              maxLength={12}
              placeholder="e.g. Zezima"
              autoComplete="off"
            />
          </div>
          <div className="pb-0.5 text-center sm:text-right">
            {ready ? (
              <span className="chip text-lumbridge-green">
                ✓ Voting as <strong className="ml-1">{name}</strong>
              </span>
            ) : (
              <span
                className={`chip ${
                  nudge ? "text-lumbridge-gold" : "text-lumbridge-parchmentdark"
                }`}
              >
                Enter your name to unlock voting
              </span>
            )}
          </div>
        </div>
        <p className="mt-3 text-xs text-lumbridge-parchment/55">
          Use your exact in-game login. Rewards are claimed in-game with{" "}
          <code className="rounded bg-black/40 px-1 py-0.5 text-lumbridge-ember">::claimvote</code>{" "}
          after the toplist confirms your vote.
        </p>
      </div>

      {/* ---- Vote site cards ---- */}
      {hasSites ? (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {VOTE_SITES.map((site) => {
            const cardInner = (
              <>
                <div className="mb-4 flex items-center gap-3">
                  <div className="grid h-12 w-12 place-items-center rounded-xl border border-lumbridge-gold/20 bg-lumbridge-gold/[0.06] text-2xl">
                    {siteIcon(site)}
                  </div>
                  <div>
                    <h3 className="font-bold text-white">{site.name}</h3>
                    <p className="text-xs text-lumbridge-parchment/50">
                      every {siteCooldownHours(site)} hours
                    </p>
                  </div>
                </div>
                <div className="mb-4 flex flex-wrap gap-2">
                  <span className="chip text-lumbridge-ember">{siteRewardLabel(site)}</span>
                  <span className="chip text-lumbridge-parchmentdark">
                    {siteCooldownHours(site)}h cooldown
                  </span>
                </div>
                <span
                  className={`mt-auto w-full text-center ${ready ? "btn-primary" : "btn-ghost"}`}
                >
                  {ready ? "Vote now →" : "Enter name to vote"}
                </span>
              </>
            );

            return ready ? (
              <a
                key={site.id}
                href={voteUrl(site, name)}
                target="_blank"
                rel="noopener noreferrer"
                className="card card-hover flex h-full flex-col"
              >
                {cardInner}
              </a>
            ) : (
              <button
                key={site.id}
                type="button"
                onClick={promptForName}
                className="card card-hover flex h-full flex-col text-left"
              >
                {cardInner}
              </button>
            );
          })}
        </div>
      ) : (
        <div className="space-y-3">
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {PLACEHOLDER_SLOTS.map((slot) => (
              <div
                key={slot.name}
                className="card flex h-full flex-col opacity-60"
                aria-hidden="true"
              >
                <div className="mb-4 flex items-center gap-3">
                  <div className="grid h-12 w-12 place-items-center rounded-xl border border-dashed border-lumbridge-gold/25 bg-black/20 text-2xl grayscale">
                    {slot.icon}
                  </div>
                  <div>
                    <h3 className="font-bold text-lumbridge-parchment/70">{slot.name}</h3>
                    <p className="text-xs text-lumbridge-parchment/40">every 12 hours</p>
                  </div>
                </div>
                <div className="mb-4 flex flex-wrap gap-2">
                  <span className="chip text-lumbridge-parchmentdark">+ vote points</span>
                </div>
                <span className="btn-ghost mt-auto w-full text-center">Opens at launch</span>
              </div>
            ))}
          </div>
          <p className="text-center text-sm text-lumbridge-parchment/55">
            Toplist callbacks go live at launch. Check back soon, soldier.
          </p>
        </div>
      )}
    </div>
  );
}
