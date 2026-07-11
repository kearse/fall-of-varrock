"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";

export function AuthForm({ mode, next }: { mode: "login" | "register"; next?: string }) {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [email, setEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const isRegister = mode === "register";

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/auth/${mode}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(
          isRegister ? { username, password, email } : { username, password }
        ),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Something went wrong.");
        return;
      }
      // UX: return the user to where they were (e.g. the store), never off-site.
      const dest = next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
      router.push(dest);
      router.refresh();
    } catch {
      setError("Network error. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-md">
      <div className="card relative overflow-hidden p-7 sm:p-8">
        {/* Ember hairline across the top of the card */}
        <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-lumbridge-gold/60 to-transparent" />

        <h1 className="font-display text-2xl font-bold text-lumbridge-parchment">
          {isRegister ? "Create your account" : "Welcome back"}
        </h1>
        <p className="mb-6 mt-1.5 text-sm text-lumbridge-parchment/55">
          {isRegister
            ? "Enlist once, fight everywhere. Site, forum and game, one login."
            : "Log in to rejoin the reclamation of Varrock."}
        </p>

        <form onSubmit={submit} className="space-y-4">
          <div>
            <label className="label" htmlFor="username">Username</label>
            <input
              id="username"
              className="input"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              maxLength={12}
              autoComplete="username"
              placeholder="Your in-game name"
              required
            />
          </div>
          {isRegister && (
            <div>
              <label className="label" htmlFor="email">Email (optional, for recovery)</label>
              <input
                id="email"
                type="email"
                className="input"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                placeholder="you@example.com"
              />
            </div>
          )}
          <div>
            <label className="label" htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              className="input"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={isRegister ? "new-password" : "current-password"}
              required
            />
          </div>
          {error && (
            <p className="rounded-xl border border-red-500/30 bg-red-500/10 px-3.5 py-2.5 text-sm text-red-400">
              {error}
            </p>
          )}
          <button className="btn-gold w-full py-3 text-base" disabled={busy}>
            {busy
              ? "Please wait..."
              : isRegister
                ? "Create account"
                : "Log in"}
          </button>
        </form>

        <div className="rule-gold my-5" />
        <p className="text-center text-sm text-lumbridge-parchment/60">
          {isRegister ? (
            <>Already enlisted? <Link href="/login" className="link-gold">Log in</Link></>
          ) : (
            <>New recruit? <Link href="/register" className="link-gold">Create an account</Link></>
          )}
        </p>
      </div>
      <p className="mt-4 text-center text-xs text-lumbridge-parchment/40">
        This account works both on the website and in-game.
      </p>
    </div>
  );
}
