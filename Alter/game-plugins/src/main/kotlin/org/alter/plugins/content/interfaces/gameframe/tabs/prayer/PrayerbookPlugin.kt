package org.alter.plugins.content.interfaces.gameframe.tabs.prayer

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*

/**
 *  @author <a href="https://github.com/CloudS3c">Cl0ud</a>
 *  @author <a href="https://www.rune-server.ee/members/376238-cloudsec/">Cl0ud</a>
 *
 */
class PrayerbookPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        // There is no second prayer book (Ruinous Powers) on this world. The command used to be
        // an empty body — typing it did nothing at all (player report 2026-09-02 "prayer book
        // swap doesn't work"). Say so instead. Spellbook swaps are ::spellbook / the Altar of
        // the Occult. Wiring notes for a future Ruinous Powers port:
        //   player.setVarbit(14826, 0/1); ClientScript(2158); ClientScript(915, 5);
        //   IfCloseSub(164, 16); IfSetEvents(541, 41, 0..4, ClickOp1)
        onCommand("prayerbook", description = "Prayer book info") {
            player.message("There is only one prayer book on this world. To change SPELL book, use ::spellbook.")
        }
    }
}
