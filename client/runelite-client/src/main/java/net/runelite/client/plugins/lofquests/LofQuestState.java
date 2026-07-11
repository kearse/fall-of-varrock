/*
 * Fall of Varrock — display state of a custom quest, coloured like the OSRS quest list.
 */
package net.runelite.client.plugins.lofquests;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
enum LofQuestState
{
	LOCKED("Locked", new Color(0x9f, 0x9f, 0x9f)),
	NOT_STARTED("Not started", new Color(0xff, 0x40, 0x40)),
	IN_PROGRESS("In progress", new Color(0xff, 0xd2, 0x00)),
	FINISHED("Completed", new Color(0x11, 0xc7, 0x11)),
	/** Announced content that isn't in the game yet — shown dimmed at the bottom of the journal. */
	FUTURE("Coming soon", new Color(0x80, 0x80, 0x95));

	private final String label;
	private final Color color;
}
