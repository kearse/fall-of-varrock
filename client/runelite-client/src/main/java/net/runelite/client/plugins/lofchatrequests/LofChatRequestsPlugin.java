/*
 * Fall of Varrock — clickable chat trade/duel requests.
 *
 * Why: the war-rank system broadcasts titled, colour-tagged appearance names
 * ("<col=c0c0c0>Knight Zak</col>", see server Title.kt). The vanilla engine resolves a
 * chatbox "Accept trade" click by rebuilding a Username from the clicked name and
 * comparing its *cleaned* form against every local player's appearance name — but a
 * col-tagged name blows the 12-char clean-name cap, so its cleaned form is null and can
 * never match. The click always ends in "Unable to find <player>", and no packet is sent,
 * so the server can't fix its side of it.
 *
 * Fix: rewrite the chat line's menu entry into a direct player op by index — the same
 * OPPLAYER packet the engine would send after a successful name lookup — resolving the
 * requester ourselves. The server puts the plain username in the message's name field,
 * and a titled display name is "<Rank> <username>", so a tag-stripped exact match with a
 * titled-suffix fallback finds them. Requests from players no longer in the local player
 * list still fall through to the vanilla path (and its "Unable to find" message), same
 * as stock OSRS.
 *
 * While here: duel challenges arrive on the same chat type (101) as trade requests, so
 * the engine labels them "Accept trade" and would send the Trade op. When the clicked
 * line is a duel challenge ("... wishes to duel with you."), relabel the entry to
 * "Accept challenge" and send the Challenge op (slot 6) instead.
 */
package net.runelite.client.plugins.lofchatrequests;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Fall of Varrock Chat Requests",
	description = "Makes trade and duel requests in the chatbox clickable for players with titled names.",
	tags = {"lof", "trade", "duel", "request", "chat"},
	enabledByDefault = true
)
public class LofChatRequestsPlugin extends Plugin
{
	private static final String ACCEPT_TRADE = "Accept trade";
	private static final String ACCEPT_CHALLENGE = "Accept challenge";
	private static final String DUEL_REQ_TEXT = "wishes to duel with you";

	/** Server-side right-click slots: "Trade with" is 4 (OSRSPlugin), "Challenge" is 6 (DuelArenaPlugin). */
	private static final MenuAction TRADE_OPTION = MenuAction.PLAYER_FOURTH_OPTION;
	private static final MenuAction CHALLENGE_OPTION = MenuAction.PLAYER_SIXTH_OPTION;

	@Inject
	private Client client;

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		final MenuEntry entry = event.getMenuEntry();
		if (entry.getType() != MenuAction.CC_OP && entry.getType() != MenuAction.CC_OP_LOW_PRIORITY)
		{
			return;
		}
		final String option = entry.getOption();
		if ((!ACCEPT_TRADE.equals(option) && !ACCEPT_CHALLENGE.equals(option))
			|| WidgetUtil.componentToInterface(entry.getParam1()) != InterfaceID.CHATBOX)
		{
			return;
		}

		final String target = Text.standardize(entry.getTarget());
		if (target.isEmpty())
		{
			return;
		}

		final Player requester = resolveRequester(target);
		if (requester == null)
		{
			return;
		}

		final boolean duel = ACCEPT_CHALLENGE.equals(option) || isDuelRequest(entry.getParam1());
		entry.setOption(duel ? ACCEPT_CHALLENGE : ACCEPT_TRADE)
			.setType(duel ? CHALLENGE_OPTION : TRADE_OPTION)
			.setIdentifier(requester.getId())
			.setParam0(0)
			.setParam1(0);
	}

	/**
	 * Finds the requesting player by display name. The clicked target is the plain
	 * username the server sent; a titled player renders as "&lt;Rank&gt; &lt;username&gt;",
	 * so prefer an exact match but accept a titled-suffix match.
	 */
	private Player resolveRequester(String target)
	{
		final Player local = client.getLocalPlayer();
		final String titledSuffix = " " + target;
		Player suffixMatch = null;
		for (Player p : client.getPlayers())
		{
			if (p == null || p == local || p.getName() == null)
			{
				continue;
			}
			final String name = Text.standardize(p.getName());
			if (name.equals(target))
			{
				return p;
			}
			if (suffixMatch == null && name.endsWith(titledSuffix))
			{
				suffixMatch = p;
			}
		}
		return suffixMatch;
	}

	/**
	 * True if the clicked chat line is a duel challenge rather than a trade request.
	 * The line's rendered text lives on the static line component and/or its dynamic
	 * message-contents sibling (the 4-children-per-line layout ChatHistoryPlugin also
	 * relies on), so check both.
	 */
	private boolean isDuelRequest(int lineComponentId)
	{
		final Widget lineWidget = client.getWidget(lineComponentId);
		if (lineWidget == null)
		{
			return false;
		}
		if (containsDuelText(lineWidget.getText()))
		{
			return true;
		}
		final Widget parent = lineWidget.getParent();
		if (parent == null || parent.getId() != ComponentID.CHATBOX_MESSAGE_LINES)
		{
			return false;
		}
		final int first = WidgetUtil.componentToId(ComponentID.CHATBOX_FIRST_MESSAGE);
		final Widget contents = parent.getChild((WidgetUtil.componentToId(lineComponentId) - first) * 4 + 1);
		return contents != null && containsDuelText(contents.getText());
	}

	private static boolean containsDuelText(String text)
	{
		return text != null && Text.removeTags(text).contains(DUEL_REQ_TEXT);
	}
}
