import { REST, Routes } from "discord.js";
import { config } from "./config.js";
import { COMMANDS } from "./commands.js";

/**
 * Registers slash commands to the single guild (instant, vs up to 1h for global).
 * Run with `npm run register`, or it runs automatically on bot startup.
 */
export async function registerCommands(): Promise<void> {
  const rest = new REST({ version: "10" }).setToken(config.token);
  await rest.put(Routes.applicationGuildCommands(config.clientId, config.guildId), { body: COMMANDS });
  console.log(`[register] ${COMMANDS.length} guild commands registered to ${config.guildId}`);
}

// Allow running standalone: `tsx src/registerCommands.ts`
if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("registerCommands.ts")) {
  registerCommands()
    .then(() => process.exit(0))
    .catch((e) => {
      console.error(e);
      process.exit(1);
    });
}
