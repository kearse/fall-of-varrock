package org.alter.plugins.content.mechanics.prayer

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.plugin.*

class PrayersPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    private companion object {
        /** OSRS retribution explosion graphic. */
        const val RETRIBUTION_GFX = 437

        /** Tiles around the dying player the retribution damage can reach. */
        const val RETRIBUTION_RADIUS = 3
    }

    init {
        /**
         * Retribution: on death, deal up to floor(25% of the dead player's Prayer level)
         * damage to their killer if the killer is adjacent-ish (3 tiles), before prayers
         * are cleared by the death itself.
         */
        onPlayerPreDeath {
            if (Prayers.isActive(player, Prayer.RETRIBUTION)) {
                player.graphic(RETRIBUTION_GFX)
                val killer = player.damageMap.getMostDamage()
                if (killer != null && !killer.isDead() && killer.tile.isWithinRadius(player.tile, RETRIBUTION_RADIUS)) {
                    val maxDamage = player.getSkills().getBaseLevel(Skills.PRAYER) / 4
                    if (maxDamage > 0) {
                        killer.hit(damage = world.random(maxDamage))
                    }
                }
            }
        }

        onPlayerDeath {
            Prayers.deactivateAll(player)
        }

        /**
         * Deactivate all prayers on log out.
         */
        onLogout {
            Prayers.deactivateAll(player)
        }

        /**
         * Activate prayers.
         */
        Prayer.values.forEach { prayer ->
            onButton(interfaceId = 541, component = prayer.child) {
                if (org.alter.plugins.content.minigames.duel.DuelArena.rulesOf(player)?.noPrayer == true) {
                    player.message("Prayer is disabled in this duel.")
                    return@onButton
                }
                player.queue {
                    Prayers.toggle(player, this, prayer)
                }
            }
        }

        /**
         * Prayer drain.
         */
        onLogin {
            player.timers[Prayers.PRAYER_DRAIN] = 1
        }

        onTimer(Prayers.PRAYER_DRAIN) {
            player.timers[Prayers.PRAYER_DRAIN] = 1
            Prayers.drainPrayer(player)
        }

        /**
         * Toggle quick-prayers.
         */
        onButton(interfaceId = 160, component = 19) {
            if (org.alter.plugins.content.minigames.duel.DuelArena.rulesOf(player)?.noPrayer == true) {
                player.message("Prayer is disabled in this duel.")
                return@onButton
            }
            val opt = player.getInteractingOption()
            Prayers.toggleQuickPrayers(player, opt)
        }

        /**
         * Select quick-prayer.
         */
        onButton(interfaceId = 77, component = 4) {
            val slot = player.getInteractingSlot()
            val prayer = Prayer.values.firstOrNull { prayer -> prayer.quickPrayerSlot == slot } ?: return@onButton
            Prayers.selectQuickPrayer(this, prayer)
        }

        /**
         * Accept selected quick-prayer.
         */
        onButton(interfaceId = 77, component = 5) {
            player.openInterface(InterfaceDestination.PRAYER)
        }
    }
}
