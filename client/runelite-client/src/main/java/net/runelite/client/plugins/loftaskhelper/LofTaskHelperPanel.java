/*
 * Fall of Varrock — the Task Helper sidebar tab.
 *
 * Styled like the Quest Journal, but it only ever shows the tasks the player is SIGNED to —
 * Vannaka signs at most one combat and one resource contract at a time. The combat card names the
 * target, the kill count, where it hunts and what a kill pays, with a toggle for the guidance
 * arrows + highlights; the resource card shows what's left to gather. An unsigned slot shows a
 * pointer to Vannaka instead of a card.
 */
package net.runelite.client.plugins.loftaskhelper;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

class LofTaskHelperPanel extends PluginPanel
{
	/** Header colour for a signed contract's card (matches the OSRS "in progress" gold). */
	private static final Color CONTRACT_GOLD = new Color(0xc8, 0xa9, 0x50);

	private final LofTaskHelperPlugin plugin;

	private final JPanel taskList = new JPanel();

	LofTaskHelperPanel(LofTaskHelperPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Task Helper");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(title);

		JLabel subtitle = new JLabel("<html>Your signed tasks. Vannaka signs one combat and one"
			+ " resource contract at a time — see him at the trade hub for work.</html>");
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		subtitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
		header.add(subtitle);

		add(header, BorderLayout.NORTH);

		taskList.setLayout(new BoxLayout(taskList, BoxLayout.Y_AXIS));
		taskList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(taskList, BorderLayout.CENTER);

		rebuild();
	}

	/** Repaint the whole tab from live client state. Must run on the Swing thread. */
	void rebuild()
	{
		taskList.removeAll();

		taskList.add(sectionLabel("Combat task"));
		taskList.add(Box.createVerticalStrut(4));
		String count = plugin.contractCount();
		if (count != null)
		{
			taskList.add(combatCard(count));
		}
		else
		{
			taskList.add(wrapped("None signed — ask Vannaka for an assignment.", ColorScheme.MEDIUM_GRAY_COLOR));
		}

		taskList.add(Box.createVerticalStrut(10));
		taskList.add(sectionLabel("Resource task"));
		taskList.add(Box.createVerticalStrut(4));
		if (plugin.getResourceName() != null)
		{
			taskList.add(resourceCard());
		}
		else
		{
			taskList.add(wrapped("None signed — ask Vannaka for a work order.", ColorScheme.MEDIUM_GRAY_COLOR));
		}

		taskList.revalidate();
		taskList.repaint();
	}

	/** The signed combat contract: target, kill count, lore, and the tracking toggle. */
	private JPanel combatCard(String count)
	{
		LofTask roster = plugin.contractRosterTask();
		boolean muted = plugin.isTrackingMuted();

		JPanel body = cardBody();
		if (roster != null)
		{
			body.add(wrapped(roster.getWhere(), ColorScheme.LIGHT_GRAY_COLOR));
			body.add(wrapped(roster.getXpPerKill() + " Slayer xp per kill · ::slayertele jumps there.",
				ColorScheme.MEDIUM_GRAY_COLOR));
		}
		else
		{
			body.add(wrapped("::slayertele jumps to the hunting ground.", ColorScheme.MEDIUM_GRAY_COLOR));
		}

		JButton trackButton = new JButton(muted ? "Track this task" : "Stop tracking");
		trackButton.setFocusPainted(false);
		trackButton.setToolTipText("Tracking points the guidance arrows (scene + minimap) at the"
			+ " hunting ground and highlights the monsters. It switches off on its own when the"
			+ " task completes.");
		trackButton.addActionListener(e -> plugin.setTrackingMuted(!muted));
		JPanel buttonRow = new JPanel(new BorderLayout());
		buttonRow.setOpaque(false);
		buttonRow.setBorder(BorderFactory.createEmptyBorder(7, 0, 0, 0));
		buttonRow.add(trackButton, BorderLayout.CENTER);
		body.add(buttonRow);

		String name = roster != null ? roster.getDisplayName() : plugin.getContractDisplayName();
		return card(name != null ? name : "Your target", count + (muted ? "" : " ◆"), body);
	}

	/** The signed resource contract: what's being gathered and how many remain. */
	private JPanel resourceCard()
	{
		JPanel body = cardBody();
		String skill = plugin.getResourceSkill();
		body.add(wrapped((skill != null ? skill + " work — g" : "G")
			+ "ather them anywhere; the contract completes on its own and pays coin + War Effort.",
			ColorScheme.LIGHT_GRAY_COLOR));

		return card(capitalize(plugin.getResourceName()), plugin.getResourceLeft() + " left", body);
	}

	/** The vertical panel a card's detail rows stack into. */
	private JPanel cardBody()
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		return body;
	}

	/** A finished card: gold name + tag header row over [body], pinned to its preferred height. */
	private JPanel card(String name, String tag, JPanel body)
	{
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));

		JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);

		JLabel nameLabel = new JLabel(name);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(CONTRACT_GOLD);
		headerRow.add(nameLabel, BorderLayout.CENTER);

		JLabel tagLabel = new JLabel(tag);
		tagLabel.setFont(FontManager.getRunescapeSmallFont());
		tagLabel.setForeground(CONTRACT_GOLD);
		headerRow.add(tagLabel, BorderLayout.EAST);

		card.add(headerRow, BorderLayout.NORTH);
		card.add(body, BorderLayout.CENTER);

		// BoxLayout: stop the card stretching vertically.
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	private JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
		label.setForeground(CONTRACT_GOLD);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** A word-wrapping label (html) that plays nicely inside the BoxLayout body. */
	private JLabel wrapped(String text, Color color)
	{
		JLabel label = new JLabel("<html><body style='width:170px'>" + text + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		return label;
	}

	private static String capitalize(String s)
	{
		return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
