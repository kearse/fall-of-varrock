import { NextResponse } from "next/server";
import { AccountDoc } from "./collections";
import { isStaff } from "./accounts";
import { getCurrentAccount } from "./session";

/** For API routes: returns the account or a 401/403 NextResponse to return early. */
export async function requireStaff(): Promise<AccountDoc | NextResponse> {
  const account = await getCurrentAccount();
  if (!account) return NextResponse.json({ error: "Not logged in." }, { status: 401 });
  if (!isStaff(account)) return NextResponse.json({ error: "Staff only." }, { status: 403 });
  return account;
}

export async function requireUser(): Promise<AccountDoc | NextResponse> {
  const account = await getCurrentAccount();
  if (!account) return NextResponse.json({ error: "Not logged in." }, { status: 401 });
  return account;
}

export function isResponse(x: unknown): x is NextResponse {
  return x instanceof NextResponse;
}
