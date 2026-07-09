import { cookies } from "next/headers";
import { SignJWT, jwtVerify } from "jose";
import { Collections, AccountDoc } from "./collections";
import { normalizeLogin } from "./accounts";

const COOKIE = "kol_session";
const MAX_AGE = 60 * 60 * 24 * 14; // 14 days

function secret(): Uint8Array {
  const s = process.env.AUTH_SECRET;
  if (!s || s.length < 16) {
    throw new Error("AUTH_SECRET is missing or too short - set it in .env.local");
  }
  return new TextEncoder().encode(s);
}

export interface SessionPayload {
  sub: string; // loginUsername
  name: string; // display name
  roles: string[];
}

export async function createSession(account: AccountDoc): Promise<void> {
  const token = await new SignJWT({
    name: account.currentDisplayName,
    email: account.email ?? undefined,
    roles: account.roles,
  })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(account.loginUsername)
    .setIssuedAt()
    .setExpirationTime(`${MAX_AGE}s`)
    .sign(secret());

  cookies().set(COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    // Secure in production, unless explicitly serving over plain HTTP locally
    // (e.g. local Docker). Set AUTH_COOKIE_INSECURE=1 for http-only deployments.
    secure: process.env.NODE_ENV === "production" && process.env.AUTH_COOKIE_INSECURE !== "1",
    path: "/",
    maxAge: MAX_AGE,
    // For cross-subdomain SSO with NodeBB (e.g. ".fallofvarrock.com" so the
    // cookie is shared with forum.fallofvarrock.com). Unset locally.
    domain: process.env.AUTH_COOKIE_DOMAIN || undefined,
  });
}

export function destroySession(): void {
  cookies().set(COOKIE, "", { httpOnly: true, path: "/", maxAge: 0, domain: process.env.AUTH_COOKIE_DOMAIN || undefined });
}

export async function getSession(): Promise<SessionPayload | null> {
  const raw = cookies().get(COOKIE)?.value;
  if (!raw) return null;
  try {
    const { payload } = await jwtVerify(raw, secret());
    return {
      sub: payload.sub as string,
      name: (payload.name as string) ?? (payload.sub as string),
      roles: (payload.roles as string[]) ?? [],
    };
  } catch {
    return null;
  }
}

/** Loads the full, fresh account doc for the logged-in user (or null). */
export async function getCurrentAccount(): Promise<AccountDoc | null> {
  const session = await getSession();
  if (!session) return null;
  const accounts = await Collections.accounts();
  return accounts.findOne({ loginUsername: normalizeLogin(session.sub) });
}
