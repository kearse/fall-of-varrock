import bcrypt from "bcryptjs";
import { Collections, AccountDoc } from "./collections";

// Web-created hashes use cost 12 (the game uses 16, which is ~seconds/verify and
// too slow for a web request). jBCrypt in-game verifies any cost, so this is
// fully compatible - a web-registered account logs into the game unchanged.
const WEB_BCRYPT_COST = 12;

// RuneScape-style names: 1-12 chars, letters/digits/space/underscore, no
// leading/trailing space. Mirrors what the game accepts at login.
const NAME_RE = /^[a-z0-9 _]{1,12}$/i;

export function normalizeLogin(input: string): string {
  // Login key the game uses: lowercased, spaces and underscores unified.
  return input.trim().toLowerCase().replace(/ /g, "_");
}

export function validateUsername(input: string): string | null {
  const name = input.trim();
  if (name.length < 1 || name.length > 12) return "Username must be 1-12 characters.";
  if (!NAME_RE.test(name)) return "Only letters, numbers, spaces and underscores allowed.";
  if (name.startsWith(" ") || name.endsWith(" ")) return "No leading or trailing spaces.";
  return null;
}

export function validatePassword(pw: string): string | null {
  if (pw.length < 5 || pw.length > 20) return "Password must be 5-20 characters.";
  return null;
}

export async function findAccount(loginInput: string): Promise<AccountDoc | null> {
  const accounts = await Collections.accounts();
  return accounts.findOne({ loginUsername: normalizeLogin(loginInput) });
}

export async function usernameTaken(loginInput: string): Promise<boolean> {
  const key = normalizeLogin(loginInput);
  const accounts = await Collections.accounts();
  // Taken if it's the current name, or a recently-changed previous name.
  const hit = await accounts.findOne({
    $or: [{ loginUsername: key }, { previousDisplayName: key }],
  });
  return hit != null;
}

export async function verifyPassword(account: AccountDoc, pw: string): Promise<boolean> {
  return bcrypt.compare(pw, account.passwordHash);
}

export async function createAccount(opts: {
  username: string;
  password: string;
  email?: string | null;
}): Promise<AccountDoc> {
  const usernameError = validateUsername(opts.username);
  if (usernameError) throw new Error(usernameError);
  const pwError = validatePassword(opts.password);
  if (pwError) throw new Error(pwError);

  const loginUsername = normalizeLogin(opts.username);
  if (await usernameTaken(loginUsername)) throw new Error("That username is taken.");

  const passwordHash = await bcrypt.hash(opts.password, WEB_BCRYPT_COST);
  const doc: AccountDoc = {
    loginUsername,
    currentDisplayName: opts.username.trim(),
    previousDisplayName: "",
    dateChanged: -1,
    passwordHash,
    email: opts.email ?? null,
    roles: ["player"],
    donorPoints: 0,
    membership: { tier: null, expires: null },
    createdAt: Date.now(),
    source: "web",
  };

  const accounts = await Collections.accounts();
  await accounts.insertOne(doc);
  return doc;
}

export function hasRole(account: Pick<AccountDoc, "roles">, role: string): boolean {
  return account.roles?.includes(role) ?? false;
}

export function isStaff(account: Pick<AccountDoc, "roles">): boolean {
  return hasRole(account, "admin") || hasRole(account, "moderator") || hasRole(account, "developer");
}
