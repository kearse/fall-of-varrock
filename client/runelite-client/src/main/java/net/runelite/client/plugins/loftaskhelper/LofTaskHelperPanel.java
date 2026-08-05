/*
 * Fall of Varrock — the Task Helper sidebar tab.
 *
 * Styled like the Quest Journal: a "current contract" line up top, then one card per roster task
 * (LofTask). Click a card to unfold what the task is — where its monsters hunt and Vannaka's
 * terms — and a Track button that points the helper's arrows and highlights at it. The live
 * contract's card is tagged with the kill count and coloured gold; the tracked pick is tagged ◆.
 */
package net.runelite.client.plugins.loftaskhelper;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumSet;
import java.util.Set;
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
	/** Header colour for the live contract's card (matches the OSRS "in progress" gold). */
	private static final Color CONTRACT_GOLD = new Color(0xc8, 0xa9, 0x50);

	/** Header colour for the tracked pick (matches the default arrow cyan). */
	private static final Color TRACKED_CYAN = new Color(0x00, 0xE5, 0xFF);

	private final LofTaskHelperPlugin plugin;

	private final JLabel contractLine = new JLabel();
	private final JPanel taskList = new JPanel();

	/** Which cards are unfolded (survives rebuilds). */
	private final Set<LofTask> expanded = EnumSet.noneOf(LofTask.class);

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

		JLabel subtitle = new JLabel("<html>Vannaka's contract roster — what each task is, where"
			+ " it hunts, and its terms. Track one and the arrows guide you there.</html>");
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		subtitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
		header.add(subtitle);

		contractLine.setFont(FontManager.getRunescapeSmallFont());
		contractLine.setAlignmentX(Component.LEFT_ALIGNMENT);
		contractLine.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
		contractLine.setToolTipText("::slayertele teleports you to your contract's hunting ground.");
		header.add(contractLine);

		add(header, BorderLayout.NORTH);

		taskList.setLayout(new BoxLayout(taskList, BoxLayout.Y_AXIS));
		taskList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(taskList, BorderLayout.CENTER);

		rebuild();
	}

	/** Repaint the whole tab from live client state. Must run on the Swing thread. */
	void rebuild()
	{
		LofTask contract = plugin.contractRosterTask();
		String count = plugin.contractCount();
		if (count != null)
		{
			String name = contract != null ? contract.getDisplayName() : plugin.getContractDisplayName();
			contractLine.setText("<html>Contract: <b>" + (name != null ? name : "?") + "</b> — " + count + "</html>");
			contractLine.setForeground(CONTRACT_GOLD);
		}
		else
		{
			contractLine.setText("No active contract — see Vannaka in Lumbridge for one.");
			contractLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}

		taskList.removeAll();
		for (LofTask task : LofTask.values())
		{
			taskList.add(buildCard(task, task == contract, count));
			taskList.add(Box.createVerticalStrut(6));
		}
		taskList.revalidate();
		taskList.repaint();
	}

	private JPanel buildCard(LofTask task, boolean isContract, String contractCount)
	{
		boolean tracked = plugin.getSelectedTask() == task;
		boolean open = expanded.contains(task);

		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));

		// --- header row: name + contract/tracked/rank tag --------------------------------
		JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);

		JLabel name = new JLabel(task.getDisplayName());
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(isContract ? CONTRACT_GOLD : tracked ? TRACKED_CYAN : Color.WHITE);
		headerRow.add(name, BorderLayout.CENTER);

		JLabel tag = new JLabel(headerTag(task, isContract, contractCount, tracked));
		tag.setFont(FontManager.getRunescapeSmallFont());
		tag.setForeground(isContract ? CONTRACT_GOLD
			: tracked ? TRACKED_CYAN : ColorScheme.MEDIUM_GRAY_COLOR);
		headerRow.add(tag, BorderLayout.EAST);

		card.add(headerRow, BorderLayout.NORTH);

		// --- unfolded body ---------------------------------------------------------------
		if (open)
		{
			JPanel body = new JPanel();
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setOpaque(false);
			body.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

			body.add(wrapped(task.getWhere(), ColorScheme.LIGHT_GRAY_COLOR));
			body.add(Box.createVerticalStrut(4));
			body.add(wrapped(task.terms(), ColorScheme.MEDIUM_GRAY_COLOR));
			if (task.getRankGate() != null)
			{
				body.add(wrapped("Contract needs " + task.getRankGate() + " rank.", ColorScheme.MEDIUM_GRAY_COLOR));
			}

			JButton trackButton = new JButton(tracked ? "Stop tracking" : "Track this task");
			trackButton.setFocusPainted(false);
			trackButton.setToolTipText(task.getHuntingGround() != null
				? "Tracking points the guidance arrows (scene + minimap) at this hunting ground and highlights the monsters."
				: "No mapped hunting ground, so no arrow — tracking still highlights the monsters when you find them.");
			trackButton.addActionListener(e -> plugin.setSelectedTask(tracked ? null : task));
			JPanel buttonRow = new JPanel(new BorderLayout());
			buttonRow.setOpaque(false);
			buttonRow.setBorder(BorderFactory.createEmptyBorder(7, 0, 0, 0));
			buttonRow.add(trackButton, BorderLayout.CENTER);
			body.add(buttonRow);

			card.add(body, BorderLayout.CENTER);
		}

		// The whole card folds/unfolds on click (buttons consume their own clicks).
		MouseAdapter toggle = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (open)
				{
					expanded.remove(task);
				}
				else
				{
					expanded.add(task);
				}
				rebuild();
			}
		};
		card.addMouseListener(toggle);
		headerRow.addMouseListener(toggle);
		name.addMouseListener(toggle);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		// BoxLayout: stop the card stretching vertically.
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	private String headerTag(LofTask task, boolean isContract, String contractCount, boolean tracked)
	{
		if (isContract)
		{
			return (contractCount != null ? contractCount : "") + (tracked ? " ◆" : "");
		}
		if (tracked)
		{
			return "◆ tracked";
		}
		return task.getRankGate() != null ? task.getRankGate() : "";
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
}
