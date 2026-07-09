/** Login key the game uses: lowercased, spaces/underscores unified. Mirrors
 *  web/src/lib/accounts.ts normalizeLogin so all three services agree. */
export function normalizeLogin(input: string): string {
  return input.trim().toLowerCase().replace(/ /g, "_");
}

export function titleCaseName(login: string): string {
  return login.replace(/_/g, " ");
}
