import { Db, MongoClient } from "mongodb";

// Singleton Mongo client. In dev, Next.js hot-reloads modules, so we cache the
// client on globalThis to avoid exhausting connections.
const uri = process.env.MONGODB_URI ?? "mongodb://localhost:27017";
const dbName = process.env.MONGODB_DB ?? "lumbridge";

declare global {
  // eslint-disable-next-line no-var
  var _mongoClientPromise: Promise<MongoClient> | undefined;
}

function clientPromise(): Promise<MongoClient> {
  if (!global._mongoClientPromise) {
    const client = new MongoClient(uri, {
      // Reasonable pool for a small community site.
      maxPoolSize: 10,
    });
    global._mongoClientPromise = client.connect();
  }
  return global._mongoClientPromise;
}

export async function getDb(): Promise<Db> {
  const client = await clientPromise();
  return client.db(dbName);
}
