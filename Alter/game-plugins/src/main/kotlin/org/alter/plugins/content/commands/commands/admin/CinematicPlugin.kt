package org.alter.plugins.content.commands.commands.admin

import net.rsprot.protocol.game.outgoing.camera.CamLookAt
import net.rsprot.protocol.game.outgoing.camera.CamMode
import net.rsprot.protocol.game.outgoing.camera.CamMoveTo
import net.rsprot.protocol.game.outgoing.camera.CamReset
import net.rsprot.protocol.game.outgoing.camera.CamRotateTo
import net.rsprot.protocol.game.outgoing.camera.CamShake
import net.rsprot.protocol.game.outgoing.camera.OculusSync
import net.rsprot.protocol.game.outgoing.camera.util.CameraEaseFunction
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Server-driven **cinematic camera** for shooting promo footage / cutscenes.
 *
 * The rev-228 protocol's camera packets ([CamMoveTo]/[CamLookAt]/…) address tiles in
 * **build-area-local** space (0..104 within the 104x104 scene loaded around the player), NOT world
 * coordinates — so every command converts the world tile the admin types into local space the same
 * way the engine does for map flags: `local = world - (buildArea.zone shl 3)`.
 * (See SynchronizationTask, which uses `tile.x - (buildArea.zoneX shl 3)`.)
 *
 * Targets must lie inside the scene currently loaded around you (~52 tiles in any direction), which
 * is always true for the commander watching their own army march past.
 *
 *  - `::cam moveto x z [h] [rate] [rate2]` — glide the camera EYE to a tile/height.
 *  - `::cam lookat x z [h] [rate] [rate2]` — point the camera AT a tile/height.
 *  - `::cam follow [back] [height] [lookH]` — auto-track YOU each tick (the marching shot); `off` to stop.
 *  - `::cam turn pitch yaw [cycles]` — smoothly rotate to an absolute pitch/yaw.
 *  - `::cam shake [axis] [random] [amp] [rate]` — handheld shake (e.g. for impacts).
 *  - `::cam mode n` / `::cam reset` — set camera mode / hand control back to the player.
 *  - `::campos` — print your current tile + build-local coords, for composing shots.
 */
class CinematicPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    // Declared ABOVE init (Kotlin init-order: a property used in init must exist first).
    private val camFollow = TimerKey()
    private val camOn = AttributeKey<Boolean>()
    private val camBack = AttributeKey<Int>()
    private val camHeight = AttributeKey<Int>()
    private val camLookH = AttributeKey<Int>()

    init {
        onCommand("cam", Privilege.ADMIN_POWER, description = "Cinematic camera: ::cam <moveto|lookat|follow|turn|shake|mode|reset>") {
            val a = player.getCommandArgs()
            if (a.isEmpty()) { usage(player); return@onCommand }
            try {
                when (a[0].lowercase()) {
                    "moveto" -> {
                        val (lx, lz) = player.toLocal(a[1].toInt(), a[2].toInt())
                        player.write(CamMoveTo(lx, lz, a.getOrNull(3)?.toIntOrNull() ?: 1000, a.getOrNull(4)?.toIntOrNull() ?: 10, a.getOrNull(5)?.toIntOrNull() ?: 10))
                        player.message("<col=4f9b4f>Camera eye → (${a[1]}, ${a[2]}).</col>")
                    }
                    "lookat" -> {
                        val (lx, lz) = player.toLocal(a[1].toInt(), a[2].toInt())
                        player.write(CamLookAt(lx, lz, a.getOrNull(3)?.toIntOrNull() ?: 200, a.getOrNull(4)?.toIntOrNull() ?: 10, a.getOrNull(5)?.toIntOrNull() ?: 10))
                        player.message("<col=4f9b4f>Camera looking → (${a[1]}, ${a[2]}).</col>")
                    }
                    "follow" -> {
                        if (a.getOrNull(1)?.lowercase() == "off") {
                            stopFollow(player)
                            player.message("<col=4f9b4f>Follow-cam OFF.</col>")
                        } else {
                            player.attr[camBack] = a.getOrNull(1)?.toIntOrNull() ?: 12
                            player.attr[camHeight] = a.getOrNull(2)?.toIntOrNull() ?: 850
                            player.attr[camLookH] = a.getOrNull(3)?.toIntOrNull() ?: 180
                            player.attr[camOn] = true
                            sendFollow(player)
                            player.timers[camFollow] = 1
                            player.message("<col=4f9b4f>Follow-cam ON — tracks you as the army marches. <col=0000ff>::cam follow off</col><col=4f9b4f> to stop.</col>")
                        }
                    }
                    "free" -> {
                        if (a.getOrNull(1)?.lowercase() == "off") {
                            player.write(OculusSync(0))
                            player.message("<col=4f9b4f>Free camera OFF.</col>")
                        } else {
                            stopFollow(player)
                            player.write(OculusSync(1))
                            player.message("<col=4f9b4f>FREE CAMERA ON — fly the camera with your keyboard/mouse, detached from your character. Press <col=ffae00>ESC</col><col=4f9b4f> to exit (or <col=0000ff>::cam free off</col><col=4f9b4f>).</col>")
                        }
                    }
                    "turn" -> player.write(CamRotateTo(a[1].toInt(), a[2].toInt(), a.getOrNull(3)?.toIntOrNull() ?: 50, CameraEaseFunction.EASE_IN_OUT_SINE.id))
                    "shake" -> player.write(CamShake(a.getOrNull(1)?.toIntOrNull() ?: 2, a.getOrNull(2)?.toIntOrNull() ?: 15, a.getOrNull(3)?.toIntOrNull() ?: 4, a.getOrNull(4)?.toIntOrNull() ?: 7))
                    "mode" -> player.write(CamMode(a.getOrNull(1)?.toIntOrNull() ?: 0))
                    "reset" -> {
                        stopFollow(player)
                        player.write(OculusSync(0))
                        player.write(CamReset)
                        player.message("<col=4f9b4f>Camera reset — control returned to you.</col>")
                    }
                    else -> usage(player)
                }
            } catch (e: Exception) {
                usage(player)
            }
        }

        onCommand("campos", Privilege.ADMIN_POWER, description = "Print your tile + build-local coords for composing camera shots") {
            val t = player.tile
            val (lx, lz) = player.toLocal(t.x, t.z)
            player.message("Tile <col=ffae00>${t.x}, ${t.z}, ${t.height}</col>  |  build-local <col=ffae00>$lx, $lz</col>")
        }

        // Per-player tick: re-aim the camera at the (moving) commander while follow-cam is on.
        onTimer(camFollow) {
            if (player.attr[camOn] != true) return@onTimer
            sendFollow(player)
            player.timers[camFollow] = 1
        }
    }

    /** World tile → build-area-local tile, matching the engine's map-flag conversion. */
    private fun Player.toLocal(x: Int, z: Int): Pair<Int, Int> =
        (x - (buildArea.zoneX shl 3)) to (z - (buildArea.zoneZ shl 3))

    /** Place the eye [back] tiles south of and [height] above the player, looking at them — a tracking shot. */
    private fun sendFollow(p: Player) {
        val back = p.attr[camBack] ?: 12
        val height = p.attr[camHeight] ?: 850
        val lookH = p.attr[camLookH] ?: 180
        val t = p.tile
        val (ex, ez) = p.toLocal(t.x, t.z - back)
        val (lx, lz) = p.toLocal(t.x, t.z)
        // Low rates so the camera glides and lags slightly behind, instead of snapping each tick.
        p.write(CamMoveTo(ex, ez, height, 4, 4))
        p.write(CamLookAt(lx, lz, lookH, 4, 4))
    }

    private fun stopFollow(p: Player) {
        p.attr[camOn] = false
        p.timers.remove(camFollow)
    }

    private fun usage(p: Player) {
        p.message("<col=ffae00>Cinematic camera commands:</col>")
        p.message("::cam free  — DETACHED fly-camera (fly with keyboard/mouse, ESC to exit)")
        p.message("::cam moveto x z [h] [rate] [rate2]  — move the camera eye")
        p.message("::cam lookat x z [h] [rate] [rate2]  — aim the camera")
        p.message("::cam follow [back] [height] [lookH]  |  ::cam follow off")
        p.message("::cam turn pitch yaw [cycles]  |  ::cam shake  |  ::cam mode n  |  ::cam reset")
        p.message("::campos  — print your tile for composing shots")
    }
}
