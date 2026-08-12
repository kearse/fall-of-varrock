/*
 * Fall of Varrock — Kit editor overlay (mouse input).
 *
 * Hit-tests left-clicks against the open kit editor window. Items move by PICK-UP-AND-PLACE:
 * click a bank/armoury tile or a filled pack slot to lift it onto the cursor, then click the
 * exact pack slot to place it (occupied slots swap / step aside), or the doll to equip it —
 * the player decides what equips and where every piece sits. Shift-click on a palette tile is
 * the quick add (first free slot); quantities and explicit Equip/Deposit stay on the native
 * right-click menu (LofKitPlugin.onMenuOpened). Esc or right-click drops the held item. Chips
 * switch presets/kits/book/difficulty; tabs, categories and scrolling stay client-side. Every
 * left-click on the window is consumed so it doesn't fall through to the game world; while
 * the native menu is open, ALL clicks pass through untouched (they belong to the menu).
 */
package net.runelite.client.plugins.lofkit;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

class LofKitMouseListener extends MouseAdapter
{
	private final LofKitPlugin plugin;
	private final LofKitOverlay overlay;

	@Inject
	LofKitMouseListener(LofKitPlugin plugin, LofKitOverlay overlay)
	{
		this.plugin = plugin;
		this.overlay = overlay;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		// While the native right-click menu is open, EVERY click belongs to it — swallowing the
		// click that selects a menu row would make the injected options unclickable.
		if (overlay.isMenuOpen())
		{
			return event;
		}
		if (!overlay.isShowing())
		{
			return event;
		}
		// A right-click drops whatever the hand holds (and then behaves natively).
		if (SwingUtilities.isRightMouseButton(event) && overlay.hasHand())
		{
			overlay.clearHand();
			return event;
		}
		if (!SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}

		final int hit = overlay.hitTest(event.getPoint());
		if (hit == LofKitOverlay.OUTSIDE)
		{
			overlay.setDropdownOpen(false); // clicking away closes the kit list
			overlay.setSearchFocused(false);
			overlay.clearHand();
			return event; // let clicks outside the window reach the game
		}

		// Any click that isn't the search field itself takes the typing focus away from it.
		if (hit != LofKitOverlay.SEARCH_BTN)
		{
			overlay.setSearchFocused(false);
		}

		// Any click that isn't the dropdown itself closes its open list.
		if (hit != LofKitOverlay.DD_HEAD && (hit < LofKitOverlay.DD_ITEM_BASE || hit > LofKitOverlay.DD_ITEM_NEW))
		{
			overlay.setDropdownOpen(false);
		}

		if (hit == LofKitOverlay.CLOSE)
		{
			plugin.sendAction("x");
		}
		else if (hit == LofKitOverlay.DD_HEAD)
		{
			// Double-click the title = rename; single click = open/close the kit list.
			if (event.getClickCount() >= 2)
			{
				overlay.setDropdownOpen(false);
				plugin.sendAction("rename");
			}
			else
			{
				overlay.setDropdownOpen(!overlay.isDropdownOpen());
			}
		}
		else if (hit >= LofKitOverlay.DD_ITEM_BASE && hit <= LofKitOverlay.DD_ITEM_NEW)
		{
			final int i = hit - LofKitOverlay.DD_ITEM_BASE;
			if (i < 2)
			{
				plugin.sendAction("p " + i); // preset
			}
			else if (i < 5)
			{
				plugin.sendAction("k " + (i - 2)); // saved kit
			}
			else
			{
				plugin.sendAction("new"); // fresh kit — the server asks for its name
			}
		}
		else if (hit == LofKitOverlay.SAVE_BTN)
		{
			plugin.sendAction("save");
		}
		else if (hit == LofKitOverlay.SEARCH_BTN)
		{
			// The live filter field takes typing focus in BOTH bank and training modes —
			// it only ever filters what's actually available (your bank / the armoury pool).
			overlay.setSearchFocused(true);
		}
		else if (hit == LofKitOverlay.ACTION)
		{
			overlay.clearHand();
			plugin.sendAction(overlay.isTraining() ? "start" : overlay.isLms() ? "done" : "load");
		}
		else if (hit == LofKitOverlay.PAGE_PREV)
		{
			overlay.pageDelta(-1);
		}
		else if (hit == LofKitOverlay.PAGE_NEXT)
		{
			overlay.pageDelta(1);
		}
		else if (hit == LofKitOverlay.CURRENT)
		{
			overlay.clearHand();
			plugin.sendAction("cur");
		}
		else if (hit >= LofKitOverlay.PAL_BASE)
		{
			final int idx = hit - LofKitOverlay.PAL_BASE;
			final int id = overlay.palItemIdAt(idx);
			if (overlay.isLms())
			{
				// LMS: a tile is a whole category pick — no hand involved.
				if (id > 0)
				{
					plugin.sendAction("a " + id);
				}
			}
			else if (overlay.hasHand() && overlay.handFrom() >= 0)
			{
				// Holding a pack item and clicking the bank/armoury area = put it back (deposit).
				plugin.sendAction("ri " + overlay.handFrom());
				overlay.clearHand();
			}
			else if (id > 0 && event.isShiftDown())
			{
				// Shift-click: the one-click quick add (first free slot / auto-equip in training).
				overlay.clearHand();
				plugin.sendAction(overlay.isBank() ? "ai " + id + " 1" : "a " + id);
			}
			else if (id > 0)
			{
				// Pick the item up onto the cursor — the next click decides exactly where it goes.
				// Stackables carry their whole bank stack; non-stackables place one per click.
				final int qty = overlay.palStackableAt(idx) ? Math.max(1, overlay.palQtyAt(idx)) : 1;
				overlay.pickHand(id, qty, -1, overlay.palEquipableAt(idx));
			}
		}
		else if (hit >= LofKitOverlay.CAT_BASE)
		{
			overlay.setBankCategory(hit - LofKitOverlay.CAT_BASE);
		}
		else if (hit >= LofKitOverlay.TAB_BASE)
		{
			overlay.setTab(hit - LofKitOverlay.TAB_BASE);
		}
		else if (hit >= LofKitOverlay.DIFF_BASE)
		{
			plugin.sendAction("d " + (hit - LofKitOverlay.DIFF_BASE));
		}
		else if (hit >= LofKitOverlay.BOOK_BASE)
		{
			plugin.sendAction("b " + (hit - LofKitOverlay.BOOK_BASE));
		}
		else if (hit >= LofKitOverlay.INV_BASE)
		{
			final int slot = hit - LofKitOverlay.INV_BASE;
			if (overlay.hasHand())
			{
				if (overlay.handFrom() >= 0)
				{
					// Rearrange: move/swap the held pack item into this exact slot.
					if (overlay.handFrom() != slot)
					{
						plugin.sendAction("mv " + overlay.handFrom() + " " + slot);
					}
					overlay.clearHand();
				}
				else
				{
					// Place the held bank/armoury item into this EXACT slot. The hand stays
					// loaded (sticky) so supplies can be laid out slot by slot.
					plugin.sendAction("pi " + overlay.handId() + " " + overlay.handQty() + " " + slot);
				}
			}
			else
			{
				// Empty hand: pick the slot's item up to move it (right-click keeps Equip/Deposit).
				final int packed = overlay.invItemAt(slot);
				final int id = packed & 0xFFFF;
				if (id > 0)
				{
					overlay.pickHand(id, (packed >> 16) & 0xFFFF, slot, overlay.invEquipableAt(slot));
				}
			}
		}
		else if (hit >= LofKitOverlay.EQUIP_BASE)
		{
			if (overlay.hasHand())
			{
				if (overlay.handFrom() >= 0)
				{
					// Equip the held pack item (virtualEquip routes it to its correct slot).
					plugin.sendAction("eq " + overlay.handFrom());
					overlay.clearHand();
				}
				else if (overlay.handEquipable())
				{
					plugin.sendAction("ae " + overlay.handId() + " " + overlay.handQty());
					overlay.clearHand();
				}
				// A non-equipable held item just stays on the cursor (the hover explains why).
			}
			else
			{
				plugin.sendAction("re " + (hit - LofKitOverlay.EQUIP_BASE));
			}
		}
		else
		{
			// Window chrome (close is handled above): a stray click drops the hand.
			overlay.clearHand();
		}

		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return swallowIfOnWindow(event);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return swallowIfOnWindow(event);
	}

	private MouseEvent swallowIfOnWindow(MouseEvent event)
	{
		// Right-button events feed the native menu machinery, and while the menu is open every
		// click is the menu's — both must pass through untouched (see mousePressed).
		if (overlay.isMenuOpen() || SwingUtilities.isRightMouseButton(event))
		{
			return event;
		}
		if (overlay.isShowing() && overlay.hitTest(event.getPoint()) != LofKitOverlay.OUTSIDE)
		{
			event.consume();
		}
		return event;
	}
}
