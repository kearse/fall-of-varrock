/**
 * One-off migration: import existing JSON player saves into MongoDB so no progress
 * is lost when switching the game's saveFormat from JSON to MONGO.
 *
 * Reads ../data/saves/details/* and ../data/saves/accounts/* (relative to the web
 * app) and upserts them into the shared `details` and `accounts` collections.
 *
 * Run:  npx tsx scripts/migrate-saves.ts          (uses MONGODB_URI / MONGODB_DB)
 *       npx tsx scripts/migrate-saves.ts --dry     (report only, no writes)
 */
import { MongoClient } from "mongodb";
import { promises as fs } from "fs";
import path from "path";

const URI = process.env.MONGODB_URI ?? "mongodb://localhost:27017";
const DB = process.env.MONGODB_DB ?? "lumbridge";
const DRY = process.argv.includes("--dry");

// Must match the game's Client.normalizeLogin and the web's normalizeLogin.
const normalizeLogin = (s: string) => s.trim().toLowerCase().replace(/ /g, "_");

const SAVES = path.join(process.cwd(), "..", "data", "saves");

async function readJsonDir(dir: string): Promise<Map<string, any>> {
  const out = new Map<string, any>();
  let names: string[];
  try {
    names = await fs.readdir(dir);
  } catch {
    return out; // dir doesn't exist (no saves yet)
  }
  for (const name of names) {
    const full = path.join(dir, name);
    const stat = await fs.stat(full);
    if (!stat.isFile()) continue;
    try {
      const raw = await fs.readFile(full, "utf8");
      out.set(name, JSON.parse(raw));
    } catch (e) {
      console.warn(`  ! skipped ${name}: ${(e as Error).message}`);
    }
  }
  return out;
}

async function main() {
  console.log(`Migrating JSON saves -> MongoDB (${DB}) ${DRY ? "[DRY RUN]" : ""}`);
  console.log(`Saves dir: ${SAVES}`);

  const detailsFiles = await readJsonDir(path.join(SAVES, "details"));
  const accountFiles = await readJsonDir(path.join(SAVES, "accounts"));
  console.log(`Found ${detailsFiles.size} detail saves, ${accountFiles.size} account records.`);

  if (detailsFiles.size === 0 && accountFiles.size === 0) {
    console.log("Nothing to migrate.");
    return;
  }

  const client = new MongoClient(URI);
  await client.connect();
  const db = client.db(DB);
  const detailsCol = db.collection("details");
  const accountsCol = db.collection("accounts");

  let detailsCount = 0;
  let accountsCount = 0;

  // Details (the heavy save blob). Key by normalized loginUsername.
  for (const [name, doc] of detailsFiles) {
    const key = normalizeLogin(doc.loginUsername ?? name);
    doc.loginUsername = key;
    if (!DRY) {
      await detailsCol.updateOne({ loginUsername: key }, { $set: doc }, { upsert: true });
    }
    detailsCount++;
  }

  // Accounts (credentials + meta). Merge passwordHash from the details blob.
  const keys = new Set<string>([
    ...[...accountFiles.keys()].map((n) => normalizeLogin(n)),
    ...[...detailsFiles.values()].map((d, i) =>
      normalizeLogin(d.loginUsername ?? [...detailsFiles.keys()][i])
    ),
  ]);

  for (const key of keys) {
    const acct = accountFiles.get(key) ?? accountFiles.get([...accountFiles.keys()].find((n) => normalizeLogin(n) === key) ?? "") ?? {};
    const details = detailsFiles.get(key) ?? [...detailsFiles.values()].find((d) => normalizeLogin(d.loginUsername ?? "") === key);
    const passwordHash = acct.passwordHash ?? details?.passwordHash ?? null;

    const accountDoc = {
      loginUsername: key,
      currentDisplayName: acct.currentDisplayName ?? key,
      previousDisplayName: acct.previousDisplayName ?? "",
      dateChanged: acct.dateChanged ?? -1,
      passwordHash,
      email: acct.email ?? null,
      roles: acct.roles ?? ["player"],
      donorPoints: acct.donorPoints ?? 0,
      membership: acct.membership ?? { tier: null, expires: null },
      createdAt: acct.createdAt ?? Date.now(),
      source: "game" as const,
    };

    if (!DRY) {
      await accountsCol.updateOne({ loginUsername: key }, { $set: accountDoc }, { upsert: true });
    }
    accountsCount++;
    if (!passwordHash) console.warn(`  ! ${key}: no password hash found (account cannot log in until reset)`);
  }

  await client.close();
  console.log(`Done. Upserted ${detailsCount} details, ${accountsCount} accounts. ${DRY ? "(dry run — no writes)" : ""}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
