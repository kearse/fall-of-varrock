/*
 * Fall of Varrock — command list window.
 *
 * Typing ::commands (or reading the Book of Commands) used to dump every command into the chat
 * box as a messy wall of text. Now the server streams the player's available commands — grouped
 * by the rank that unlocks each one — and this plugin renders them in a clean, tabbed window
 * (one tab per rank) styled like the teleport portal.
 *
 * Transport: the server sends the list as ChatMessageType.CONSOLE chat lines, each prefixed with
 * "FOV_CMDS:" (see the server-side BookOfCommandsPlugin) — the same hidden channel the companion
 * system streams its machine data on. CONSOLE lines are delivered to the ChatMessage event (fires
 * exactly once per line, so the window opens instantly) but are NEVER rendered in the chat box and
 * live in their own line buffer — so they cannot flood, evict, or otherwise disturb the player's
 * visible chat. No filtering, no hiding, no chat mutation of any kind.
 *
 * Hard-won transport rules (each earlier variant caused a live glitch — do NOT regress):
 *   1. NEVER send this data as GAME_MESSAGE — even display-filtered, each hidden line still
 *      consumed a slot in the game-message buffer and silently EVICTED the player's oldest game
 *      messages ("::commands deletes my chat").
 *   2. NEVER parse in the chatFilterCheck hook — it re-runs over the whole retained history on
 *      every chatbox rebuild (open-lag / tab-reset / reopen bugs).
 *   3. NEVER mass-remove lines from the chat buffers (removeMessageNode) — it wiped the chat.
 *
 * Wire format (one line each):
 *   FOV_CMDS:open|&lt;RankTitle&gt;
 *   FOV_CMDS:row|&lt;order&gt;|&lt;RoleLabel&gt;|&lt;::cmd&gt;|&lt;desc&gt;    (repeated; desc may be empty)
 *   FOV_CMDS:end
 *
 * The window is closed with the X button (or by clicking outside it). Local test without the
 * server: type ::cmdspanel to open a sample window.
 */
package net.runelite.client.plugins.lofcommands;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Fall of Varrock Commands",
	description = "Shows ::commands as a clean tabbed window grouped by rank, instead of a wall of chat text.",
	tags = {"lof", "commands", "help", "panel", "rank"},
	enabledByDefault = true
)
public class LofCommandsPlugin extends Plugin
{
	/** Machine prefix the server tags command-list lines with (must match BookOfCommandsPlugin). */
	static final String PREFIX = "FOV_CMDS:";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LofCommandsOverlay overlay;

	@Inject
	private LofCommandsConfig config;

	private LofCommandsMouseListener mouseListener;
	private MouseWheelListener wheelListener;

	/** Committed tabs (what the window draws) + the buffer we fill while a batch streams in.
	 *  Both are only ever touched on the client thread, so no locking needed. */
	private final List<Tab> tabs = new ArrayList<>();
	private final List<Row> buffer = new ArrayList<>();
	private String rankTitle = "";
	private String bufferRank = "";

	@Provides
	LofCommandsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofCommandsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseListener = new LofCommandsMouseListener(this, overlay);
		mouseManager.registerMouseListener(mouseListener);
		wheelListener = event ->
		{
			if (overlay.handleScroll(event.getPoint(), event.getWheelRotation()))
			{
				event.consume();
			}
			return event;
		};
		mouseManager.registerMouseWheelListener(wheelListener);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		if (mouseListener != null)
		{
			mouseManager.unregisterMouseListener(mouseListener);
			mouseListener = null;
		}
		if (wheelListener != null)
		{
			mouseManager.unregisterMouseWheelListener(wheelListener);
			wheelListener = null;
		}
		overlay.setVisible(false);
		tabs.clear();
		buffer.clear();
	}

	/**
	 * Consume a command-list line the moment it ARRIVES. ChatMessage fires exactly once per line,
	 * so the window opens instantly. CONSOLE is the transport channel (never rendered, own line
	 * buffer); GAMEMESSAGE is also accepted briefly for back-compat with a server that hasn't
	 * redeployed yet — the prefix gate makes that harmless.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.CONSOLE && event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		final String message = event.getMessage();
		if (message == null || !message.startsWith(PREFIX))
		{
			return;
		}
		handle(message.substring(PREFIX.length()));
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// Dev aid: ::cmdspanel opens a sample window locally (no server round-trip).
		if ("cmdspanel".equalsIgnoreCase(event.getCommand()))
		{
			handle("open|Owner");
			handle("row|0|Player|::commands|open your command list");
			handle("row|0|Player|::players|show players online");
			handle("row|0|Player|::title|set your chat title");
			handle("row|2|Moderator|::mute|mute a player");
			handle("row|2|Moderator|::ban|ban a player");
			handle("row|2|Moderator|::kick|kick a player");
			handle("row|3|Administrator|::item|spawn an item");
			handle("row|3|Administrator|::npc|spawn an npc");
			handle("row|4|Developer|::reboot|restart the server");
			handle("row|5|Owner|::givepower|grant a rank power");
			handle("end");
		}
	}

	/** Parse one batch line (already stripped of the {@link #PREFIX}). */
	private void handle(String body)
	{
		final int bar = body.indexOf('|');
		final String sub = bar < 0 ? body : body.substring(0, bar);
		final String rest = bar < 0 ? "" : body.substring(bar + 1);

		switch (sub)
		{
			case "open":
				bufferRank = rest;
				buffer.clear();
				break;
			case "row":
			{
				// order | role | cmd | desc(may contain further '|')
				final String[] f = rest.split("\\|", 4);
				if (f.length >= 3)
				{
					buffer.add(new Row(f[1], f[2], f.length >= 4 ? f[3] : ""));
				}
				break;
			}
			case "end":
				commit();
				break;
			default:
				break;
		}
	}

	/** Turn the streamed rows into rank tabs and open the window. A batch now fires exactly once
	 *  per ::commands (arrival-based capture + purge — no rebuild re-triggers), so every commit is
	 *  a genuine player request: open fresh on the first tab. */
	private void commit()
	{
		// Group by UNIQUE role (first-seen order) so interleaved rows can't create duplicate tabs.
		final Map<String, Tab> byRole = new LinkedHashMap<>();
		for (Row r : buffer)
		{
			byRole.computeIfAbsent(r.role, Tab::new).rows.add(new Entry(r.cmd, r.desc));
		}
		tabs.clear();
		tabs.addAll(byRole.values());
		rankTitle = bufferRank;
		if (!tabs.isEmpty())
		{
			overlay.setActiveTab(0);
			overlay.setVisible(true);
		}
	}

	/** Dismiss the window (X / click-away / picking a command). */
	void close()
	{
		overlay.setVisible(false);
	}

	/** Put "&lt;cmd&gt; " into the chat input (unsent) so the player can add arguments and press enter. */
	void insertCommand(String cmd)
	{
		final String text = cmd + " ";
		clientThread.invoke(() ->
		{
			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, text);
			client.runScript(ScriptID.CHAT_PROMPT_INIT);
		});
	}

	boolean isEnabled()
	{
		return config.enabled();
	}

	List<Tab> getTabs()
	{
		return tabs;
	}

	String getRankTitle()
	{
		return rankTitle;
	}

	/** A rank's tab: the label plus its commands. */
	static final class Tab
	{
		final String role;
		final List<Entry> rows = new ArrayList<>();

		Tab(String role)
		{
			this.role = role;
		}
	}

	/** One command row. */
	static final class Entry
	{
		final String cmd;
		final String desc;

		Entry(String cmd, String desc)
		{
			this.cmd = cmd;
			this.desc = desc;
		}
	}

	/** A row as streamed, before it's grouped into tabs. */
	private static final class Row
	{
		final String role;
		final String cmd;
		final String desc;

		Row(String role, String cmd, String desc)
		{
			this.role = role;
			this.cmd = cmd;
			this.desc = desc;
		}
	}
}
