/*
 * Fall of Varrock — Companions side panel.
 *
 * A three-screen panel driven by the server's ~LOFCMP~ message channel (distance-independent):
 *   1. ROSTER  — one card per companion (Sir <name>, style, combat, HP, order-all + per-companion orders).
 *   2. DETAIL  — click a card: full levels, attack-style buttons, autocast (mage), auto-loot / retaliate
 *                toggles, rename, the worn-equipment grid, and (ranged) the quiver / ammo count.
 *   3. PICKER  — click a gear slot: a searchable list of bank items that fit that slot (server-filtered),
 *                click to equip; the worn item can be returned to the bank.
 */
package net.runelite.client.plugins.companions;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

@Slf4j
class CompanionsPanel extends PluginPanel
{
	/** Order-all buttons + their command args. Index MUST match the server CompanionOrders ordinal
	 *  (FOLLOW, TRAIN, DEPLOY, RETURN, ATTACK) — the active highlight compares by ordinal. */
	private static final String[] ORDER_LABELS = {"Follow", "Train", "Deploy", "Recall", "Attack"};
	private static final String[] ORDER_ARGS = {"follow", "train", "deploy", "return", "attack"};

	/** Worn-equipment grid layout (5 rows x 3 cols). Cell value = equipment slot id, or -1 for a gap. */
	private static final int[] GRID = {
		-1, 0, -1,    // .      head    .
		1, 2, 13,     // cape   amulet  ammo
		3, 4, 5,      // weapon body    shield
		-1, 7, -1,    // .      legs    .
		9, 10, 12,    // hands  feet    ring
	};
	private static final Map<Integer, String> SLOT_NAMES = new HashMap<>();
	static
	{
		SLOT_NAMES.put(0, "Head"); SLOT_NAMES.put(1, "Cape"); SLOT_NAMES.put(2, "Amulet");
		SLOT_NAMES.put(3, "Weapon"); SLOT_NAMES.put(4, "Body"); SLOT_NAMES.put(5, "Shield");
		SLOT_NAMES.put(7, "Legs"); SLOT_NAMES.put(9, "Hands"); SLOT_NAMES.put(10, "Feet");
		SLOT_NAMES.put(12, "Ring"); SLOT_NAMES.put(13, "Ammo");
	}

	private static final String[] SKILL_LABELS = {"Attack", "Strength", "Defence", "Hitpoints", "Ranged", "Magic", "Prayer"};

	/** Standard-spellbook combat spells the autocast selector offers: {enum name, display, level req}. */
	private static final String[][] SPELLS = {
		{"WIND_STRIKE", "Wind Strike", "1"}, {"WATER_STRIKE", "Water Strike", "5"},
		{"EARTH_STRIKE", "Earth Strike", "9"}, {"FIRE_STRIKE", "Fire Strike", "13"},
		{"WIND_BOLT", "Wind Bolt", "17"}, {"WATER_BOLT", "Water Bolt", "23"},
		{"EARTH_BOLT", "Earth Bolt", "29"}, {"FIRE_BOLT", "Fire Bolt", "35"},
		{"WIND_BLAST", "Wind Blast", "41"}, {"WATER_BLAST", "Water Blast", "47"},
		{"EARTH_BLAST", "Earth Blast", "53"}, {"FIRE_BLAST", "Fire Blast", "59"},
		{"WIND_WAVE", "Wind Wave", "62"}, {"WATER_WAVE", "Water Wave", "65"},
		{"EARTH_WAVE", "Earth Wave", "70"}, {"FIRE_WAVE", "Fire Wave", "75"},
		{"WIND_SURGE", "Wind Surge", "81"}, {"WATER_SURGE", "Water Surge", "85"},
		{"EARTH_SURGE", "Earth Surge", "90"}, {"FIRE_SURGE", "Fire Surge", "95"},
	};

	/** Actions the panel asks the plugin to perform (each maps to a server command). */
	interface Actions
	{
		void order(String arg);                 // "follow" / "train 2" / ...
		void equip(int slot, int itemId);
		void unequip(int slot, int equipSlot);
		void style(int slot, int style);
		void spell(int slot, String spellName); // enum name, or "auto"
		void retaliate(int slot);
		void loot(int slot);
		void rename(int slot, String name);
		void requestGear(int slot, int equipSlot);
	}

	private enum View { ROSTER, DETAIL, PICKER }

	private final Actions actions;
	private final ItemManager itemManager;
	private final JPanel content = new JPanel(new BorderLayout());

	private List<CompanionRow> rows = new ArrayList<>();
	private View view = View.ROSTER;
	private int detailSlot = -1;
	// Active picker request (so a stale ~LOFGEAR~ reply for another slot is ignored).
	private int pickerSlot = -1;
	private int pickerEquipSlot = -1;
	private List<GearOption> pickerOptions = new ArrayList<>();
	private String pickerFilter = "";

	CompanionsPanel(Actions actions, ItemManager itemManager)
	{
		this.actions = actions;
		this.itemManager = itemManager;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		add(content, BorderLayout.CENTER);
		renderRoster();
	}

	// ---- data feed --------------------------------------------------------------------------------

	void update(final List<CompanionRow> newRows)
	{
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				rows = newRows;
				if (view == View.DETAIL && rowForSlot(detailSlot) == null)
				{
					view = View.ROSTER; // the companion we were viewing is gone
				}
				refresh();
			}
			catch (Exception e)
			{
				log.error("Companions panel render failed", e);
			}
		});
	}

	void showGearOptions(int slot, int equipSlot, List<GearOption> options)
	{
		SwingUtilities.invokeLater(() ->
		{
			// Only accept the reply for the picker the user actually opened.
			if (view == View.PICKER && slot == pickerSlot && equipSlot == pickerEquipSlot)
			{
				pickerOptions = options;
				refresh();
			}
		});
	}

	private void refresh()
	{
		switch (view)
		{
			case DETAIL: renderDetail(detailSlot); break;
			case PICKER: renderPicker(); break;
			default: renderRoster();
		}
	}

	private CompanionRow rowForSlot(int slot)
	{
		for (CompanionRow r : rows)
		{
			if (r.slot == slot)
			{
				return r;
			}
		}
		return null;
	}

	// ---- ROSTER view ------------------------------------------------------------------------------

	private void renderRoster()
	{
		content.removeAll();
		final JPanel page = vbox();

		page.add(header("Companions", null));

		final JLabel hint = small("Order ALL:");
		hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
		page.add(hint);
		final JPanel allButtons = new JPanel(new GridLayout(0, 3, 3, 3));
		for (int i = 0; i < ORDER_LABELS.length; i++)
		{
			final String arg = ORDER_ARGS[i];
			allButtons.add(button(ORDER_LABELS[i], () -> actions.order(arg)));
		}
		allButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
		allButtons.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		page.add(allButtons);

		if (!rows.isEmpty())
		{
			// ::companion loot (no slot) flips every companion to the OPPOSITE of the first one's
			// state, so the label mirrors the first row — that's the state the click inverts.
			page.add(button("Auto-loot ALL to bank: " + onOff(rows.get(0).autoLoot),
				() -> actions.order("loot")));
			page.add(spacer(8));
		}

		if (rows.isEmpty())
		{
			final JLabel empty = small("No companions. Recruit at General Zo.");
			empty.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
			page.add(empty);
		}
		else
		{
			for (CompanionRow r : rows)
			{
				page.add(rosterCard(r));
			}
		}

		content.add(scroll(page), BorderLayout.CENTER);
		content.revalidate();
		content.repaint();
	}

	private JPanel rosterCard(CompanionRow r)
	{
		final JPanel card = card();

		final JLabel name = new JLabel("Sir " + r.name);
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.BRAND_ORANGE);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel stat = small(r.styleName() + " • Combat " + r.combat + " • HP " + r.hpPercent + "%");
		stat.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JButton manage = button("Manage Sir " + r.name, () -> openDetail(r.slot));
		manage.setAlignmentX(Component.LEFT_ALIGNMENT);
		manage.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		card.add(name);
		card.add(stat);
		card.add(manage);
		// whole card opens the detail too
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				openDetail(r.slot);
			}
		});
		return card;
	}

	private void openDetail(int slot)
	{
		detailSlot = slot;
		view = View.DETAIL;
		refresh();
	}

	// ---- DETAIL view ------------------------------------------------------------------------------

	private void renderDetail(int slot)
	{
		final CompanionRow r = rowForSlot(slot);
		if (r == null)
		{
			view = View.ROSTER;
			renderRoster();
			return;
		}
		content.removeAll();
		final JPanel page = vbox();

		page.add(header("Sir " + r.name, () -> { view = View.ROSTER; refresh(); }));
		page.add(small(r.styleName() + " • Combat " + r.combat + " • HP " + r.curHp + "/" + r.maxHp));

		// Levels grid
		page.add(sectionLabel("Levels"));
		final JPanel levels = new JPanel(new GridLayout(0, 2, 4, 2));
		levels.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		levels.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int i = 0; i < SKILL_LABELS.length && i < r.levels.length; i++)
		{
			levels.add(small(SKILL_LABELS[i] + ": " + r.levels[i]));
		}
		page.add(levels);

		// Per-companion orders
		page.add(sectionLabel("Orders"));
		final JPanel orders = new JPanel(new GridLayout(1, 0, 3, 0));
		orders.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int i = 0; i < ORDER_LABELS.length; i++)
		{
			final String arg = ORDER_ARGS[i] + " " + (r.slot + 1);
			final JButton b = button(ORDER_LABELS[i].substring(0, 3), () -> actions.order(arg));
			b.setToolTipText(ORDER_LABELS[i]);
			final boolean active = i == r.orders;
			if (active)
			{
				b.setBackground(ColorScheme.BRAND_ORANGE);
			}
			orders.add(b);
		}
		page.add(orders);

		// Attack style OR autocast
		if (r.archetype == 2) // mage
		{
			page.add(sectionLabel("Autocast"));
			page.add(autocastSelector(r));
		}
		else if (r.styleLabels.length > 0)
		{
			page.add(sectionLabel("Attack style"));
			final JPanel styles = new JPanel(new GridLayout(0, 2, 3, 2));
			styles.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int i = 0; i < r.styleLabels.length; i++)
			{
				final int sv = i;
				final JButton b = button(r.styleLabels[i], () -> actions.style(r.slot, sv));
				if (sv == r.styleVarp)
				{
					b.setBackground(ColorScheme.BRAND_ORANGE);
				}
				styles.add(b);
			}
			page.add(styles);
		}

		// Toggles
		page.add(sectionLabel("Behaviour"));
		final JPanel toggles = new JPanel(new GridLayout(0, 1, 0, 2));
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		toggles.add(button("Auto-retaliate: " + onOff(r.retaliate), () -> actions.retaliate(r.slot)));
		toggles.add(button("Auto-loot to bank: " + onOff(r.autoLoot), () -> actions.loot(r.slot)));
		toggles.add(button("Rename", () -> promptRename(r)));
		page.add(toggles);

		// Ammo (ranged)
		if (r.archetype == 1)
		{
			page.add(sectionLabel("Quiver"));
			final String ammo = r.ammoId > 0
				? (r.ammoName != null ? r.ammoName : "Ammo") + " x" + r.ammoQty
				: "Empty — stock arrows in your bank";
			final JLabel ammoLabel = small(ammo);
			ammoLabel.setForeground(r.ammoQty <= 0 ? new Color(200, 90, 90) : ColorScheme.LIGHT_GRAY_COLOR);
			page.add(ammoLabel);
		}

		// Worn equipment
		page.add(sectionLabel("Equipment (click a slot)"));
		page.add(gearGrid(r));

		content.add(scroll(page), BorderLayout.CENTER);
		content.revalidate();
		content.repaint();
	}

	private JPanel autocastSelector(CompanionRow r)
	{
		final JPanel box = new JPanel(new GridLayout(0, 1, 0, 2));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		final int magic = r.levels.length > 5 ? r.levels[5] : 1;

		final JButton auto = button("Auto (level-scaled)", () -> actions.spell(r.slot, "auto"));
		box.add(auto);

		for (String[] s : SPELLS)
		{
			final int req = Integer.parseInt(s[2]);
			if (magic < req)
			{
				continue; // can't cast yet
			}
			final String enumName = s[0];
			final JButton b = button(s[1], () -> actions.spell(r.slot, enumName));
			if (enumName.equals(r.autocast))
			{
				b.setBackground(ColorScheme.BRAND_ORANGE);
			}
			box.add(b);
		}
		return box;
	}

	private JPanel gearGrid(CompanionRow r)
	{
		final JPanel grid = new JPanel(new GridLayout(5, 3, 2, 2));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
		for (int cell : GRID)
		{
			final JLabel slotLabel = new JLabel();
			slotLabel.setHorizontalAlignment(SwingConstants.CENTER);
			slotLabel.setPreferredSize(new Dimension(34, 30));
			if (cell < 0)
			{
				slotLabel.setOpaque(false);
				grid.add(slotLabel);
				continue;
			}
			slotLabel.setOpaque(true);
			slotLabel.setBackground(ColorScheme.DARK_GRAY_COLOR);
			slotLabel.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
			final int itemId = cell < r.equipment.length ? r.equipment[cell] : -1;
			final int qty = cell < r.equipQty.length ? r.equipQty[cell] : 0;
			if (itemId > 0)
			{
				final AsyncBufferedImage img = itemManager.getImage(itemId, qty, qty > 1);
				img.addTo(slotLabel);
				slotLabel.setToolTipText(SLOT_NAMES.getOrDefault(cell, "item") + " — click to change");
			}
			else
			{
				slotLabel.setToolTipText(SLOT_NAMES.getOrDefault(cell, "") + " (empty) — click to equip");
			}
			final int equipSlot = cell;
			slotLabel.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					openPicker(r.slot, equipSlot);
				}
			});
			grid.add(slotLabel);
		}
		return grid;
	}

	private void promptRename(CompanionRow r)
	{
		final String input = JOptionPane.showInputDialog(this, "New name for Sir " + r.name + " (max 12):", r.name);
		if (input != null && !input.trim().isEmpty())
		{
			actions.rename(r.slot, input.trim());
		}
	}

	// ---- PICKER view ------------------------------------------------------------------------------

	private void openPicker(int slot, int equipSlot)
	{
		pickerSlot = slot;
		pickerEquipSlot = equipSlot;
		pickerOptions = new ArrayList<>();
		pickerFilter = "";
		view = View.PICKER;
		actions.requestGear(slot, equipSlot); // server replies over ~LOFGEAR~
		refresh();
	}

	private void renderPicker()
	{
		final CompanionRow r = rowForSlot(pickerSlot);
		content.removeAll();
		final JPanel page = vbox();

		final String slotName = SLOT_NAMES.getOrDefault(pickerEquipSlot, "Slot");
		page.add(header(slotName + " from bank", () -> { view = View.DETAIL; refresh(); }));

		// If the slot currently has an item, offer to unequip it back to the bank.
		if (r != null && pickerEquipSlot < r.equipment.length && r.equipment[pickerEquipSlot] > 0)
		{
			page.add(button("Unequip " + slotName + " → bank", () ->
			{
				actions.unequip(pickerSlot, pickerEquipSlot);
				view = View.DETAIL;
				refresh();
			}));
		}

		final JTextField search = new JTextField(pickerFilter);
		search.setToolTipText("Search bank items");
		search.setAlignmentX(Component.LEFT_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			private void changed()
			{
				pickerFilter = search.getText().toLowerCase();
				rebuildPickerList(page, r);
			}
			public void insertUpdate(DocumentEvent e) { changed(); }
			public void removeUpdate(DocumentEvent e) { changed(); }
			public void changedUpdate(DocumentEvent e) { changed(); }
		});
		page.add(spacer(6));
		page.add(search);
		page.add(spacer(4));

		final JPanel results = new JPanel();
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		results.setName("results");
		results.setAlignmentX(Component.LEFT_ALIGNMENT);
		page.add(results);
		fillPickerResults(results, r);

		content.add(scroll(page), BorderLayout.CENTER);
		content.revalidate();
		content.repaint();
		SwingUtilities.invokeLater(search::requestFocusInWindow);
	}

	/** Re-filter the results without rebuilding the whole picker (keeps the search box focused). */
	private void rebuildPickerList(JPanel page, CompanionRow r)
	{
		for (Component c : page.getComponents())
		{
			if (c instanceof JPanel && "results".equals(c.getName()))
			{
				final JPanel results = (JPanel) c;
				results.removeAll();
				fillPickerResults(results, r);
				results.revalidate();
				results.repaint();
				return;
			}
		}
	}

	private void fillPickerResults(JPanel results, CompanionRow r)
	{
		if (pickerOptions.isEmpty())
		{
			results.add(small("No matching items in your bank."));
			return;
		}
		int shown = 0;
		for (GearOption o : pickerOptions)
		{
			if (!pickerFilter.isEmpty() && !o.name.toLowerCase().contains(pickerFilter))
			{
				continue;
			}
			results.add(pickerRow(o, o.name));
			shown++;
		}
		if (shown == 0)
		{
			results.add(small("No items match \"" + pickerFilter + "\"."));
		}
	}

	private JPanel pickerRow(GearOption o, String nm)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(34, 30));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		final AsyncBufferedImage img = itemManager.getImage(o.itemId, o.qty, o.qty > 1);
		img.addTo(icon);
		row.add(icon, BorderLayout.WEST);

		final JLabel text = small(nm + (o.qty > 1 ? " (x" + o.qty + ")" : ""));
		if (!o.wearable)
		{
			text.setText("<html>" + nm + "<br><font color='#cc5a5a'>level too low</font></html>");
		}
		row.add(text, BorderLayout.CENTER);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!o.wearable)
				{
					return;
				}
				actions.equip(pickerSlot, o.itemId);
				view = View.DETAIL;
				refresh();
			}
		});
		return row;
	}

	// ---- small UI helpers -------------------------------------------------------------------------

	private JPanel header(String titleText, Runnable back)
	{
		final JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		if (back != null)
		{
			bar.add(button("←", back), BorderLayout.WEST);
		}
		final JLabel title = new JLabel(titleText);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setBorder(BorderFactory.createEmptyBorder(0, back != null ? 6 : 0, 0, 0));
		bar.add(title, BorderLayout.CENTER);
		return bar;
	}

	private JLabel sectionLabel(String text)
	{
		final JLabel l = small(text);
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private JLabel small(String text)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private JButton button(String text, Runnable onClick)
	{
		final JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusable(false);
		b.setMargin(new java.awt.Insets(2, 4, 2, 4));
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.addActionListener(e -> onClick.run());
		return b;
	}

	private JPanel card()
	{
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 6, 0),
			BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR),
				BorderFactory.createEmptyBorder(6, 8, 6, 8))));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	private JPanel vbox()
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		return p;
	}

	private Component spacer(int h)
	{
		return javax.swing.Box.createRigidArea(new Dimension(0, h));
	}

	private JScrollPane scroll(JPanel page)
	{
		final JScrollPane sp = new JScrollPane(page,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getVerticalScrollBar().setUnitIncrement(16);
		return sp;
	}

	private static String onOff(boolean b)
	{
		return b ? "ON" : "OFF";
	}

	// ---- data rows --------------------------------------------------------------------------------

	/** One bank item that fits the slot being picked. ({@code name} resolved on the client thread.) */
	static final class GearOption
	{
		final int itemId;
		final int qty;
		final boolean wearable;
		final String name;

		GearOption(int itemId, int qty, boolean wearable, String name)
		{
			this.itemId = itemId;
			this.qty = qty;
			this.wearable = wearable;
			this.name = name != null ? name : ("Item " + itemId);
		}
	}

	static final class CompanionRow
	{
		final int slot;
		final String name;
		final int archetype;        // 0 melee, 1 ranged, 2 mage
		final int combat;
		final int curHp;
		final int maxHp;
		final int hpPercent;
		final int orders;
		final int styleVarp;
		final String autocast;      // current effective spell enum name, or "-"
		final boolean autoLoot;
		final boolean retaliate;
		final int ammoId;
		final int ammoQty;
		final String ammoName;      // resolved on the client thread (null if no ammo)
		final int[] levels;         // atk,str,def,hp,range,mage,pray
		final int[] equipment;      // indexed by equipment slot id; itemId or -1
		final int[] equipQty;       // parallel to equipment
		final String[] styleLabels; // attack-style button labels (empty for mage)

		CompanionRow(int slot, String name, int archetype, int combat, int curHp, int maxHp, int orders,
			int styleVarp, String autocast, boolean autoLoot, boolean retaliate, int ammoId, int ammoQty,
			String ammoName, int[] levels, int[] equipment, int[] equipQty, String[] styleLabels)
		{
			this.slot = slot;
			this.name = name;
			this.archetype = archetype;
			this.combat = combat;
			this.curHp = curHp;
			this.maxHp = maxHp;
			this.hpPercent = maxHp > 0 ? Math.round(100f * curHp / maxHp) : 0;
			this.orders = orders;
			this.styleVarp = styleVarp;
			this.autocast = autocast;
			this.autoLoot = autoLoot;
			this.retaliate = retaliate;
			this.ammoId = ammoId;
			this.ammoQty = ammoQty;
			this.ammoName = ammoName;
			this.levels = levels;
			this.equipment = equipment;
			this.equipQty = equipQty;
			this.styleLabels = styleLabels;
		}

		String styleName()
		{
			switch (archetype)
			{
				case 1: return "Ranged";
				case 2: return "Mage";
				default: return "Melee";
			}
		}
	}
}
