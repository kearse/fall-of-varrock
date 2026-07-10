/*
 * Kingdom of Lumbridge — the Quest Journal sidebar.
 *
 * One card per custom quest, coloured like the OSRS quest list: click a card to unfold its story
 * ("why am I doing this?"), the step checklist (done ✓ / current ➤ / ahead ○), and what completing
 * it unlocks. In-progress quests get a Track button that drives the guidance arrows; the header
 * button flips the SERVER's arrows too (free play). FUTURE entries render dimmed at the bottom so
 * players can see where the quest line is heading.
 */
package net.runelite.client.plugins.lofquests;

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
import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

class LofQuestsPanel extends PluginPanel
{
	private final LofQuestsPlugin plugin;
	private final Client client;

	private final JPanel questList = new JPanel();
	private final JButton guidanceButton = new JButton();

	/** Which cards are unfolded (survives rebuilds). */
	private final Set<LofQuest> expanded = EnumSet.noneOf(LofQuest.class);

	LofQuestsPanel(LofQuestsPlugin plugin, Client client)
	{
		this.plugin = plugin;
		this.client = client;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Quest Journal");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(title);

		JLabel subtitle = new JLabel("<html>The Fall of Varrock quest line — what you've done,"
			+ " what's next, and what each quest unlocks.</html>");
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		subtitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
		header.add(subtitle);

		guidanceButton.setFocusPainted(false);
		guidanceButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		guidanceButton.addActionListener(e -> plugin.toggleServerGuidance());
		header.add(guidanceButton);

		add(header, BorderLayout.NORTH);

		questList.setLayout(new BoxLayout(questList, BoxLayout.Y_AXIS));
		questList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(questList, BorderLayout.CENTER);

		rebuild();
	}

	/** Repaint the whole journal from live client state. Must run on the Swing thread. */
	void rebuild()
	{
		boolean muted = LofQuestVarps.guideMuted(client);
		guidanceButton.setText(muted ? "Guidance arrows: OFF (free play)" : "Guidance arrows: ON");
		guidanceButton.setToolTipText("Also toggled in-game with ::questguide. Off = no server hint"
			+ " arrows follow you around; quests still progress.");

		questList.removeAll();
		for (LofQuest quest : LofQuest.values())
		{
			questList.add(buildCard(quest));
			questList.add(Box.createVerticalStrut(6));
		}
		questList.revalidate();
		questList.repaint();
	}

	private JPanel buildCard(LofQuest quest)
	{
		LofQuestState state = quest.state(client);
		boolean open = expanded.contains(quest);

		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));

		// --- header row: name (state-coloured) + progress/state tag -------------------
		JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);

		JLabel name = new JLabel(quest.getQuestName());
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(state.getColor());
		headerRow.add(name, BorderLayout.CENTER);

		JLabel tag = new JLabel(headerTag(quest, state));
		tag.setFont(FontManager.getRunescapeSmallFont());
		tag.setForeground(state.getColor());
		headerRow.add(tag, BorderLayout.EAST);

		card.add(headerRow, BorderLayout.NORTH);

		// --- unfolded body -------------------------------------------------------------
		if (open)
		{
			JPanel body = new JPanel();
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setOpaque(false);
			body.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

			body.add(wrapped(quest.getWhy(), ColorScheme.LIGHT_GRAY_COLOR));

			String lockReason = quest.lockReason(client);
			if (lockReason != null)
			{
				body.add(Box.createVerticalStrut(5));
				body.add(wrapped("Locked: " + lockReason, LofQuestState.NOT_STARTED.getColor()));
			}

			if (!quest.getSteps().isEmpty() && state != LofQuestState.LOCKED)
			{
				body.add(Box.createVerticalStrut(7));
				body.add(sectionLabel("Steps"));
				int current = quest.stepOrdinal(client);
				for (LofQuestStep step : quest.getSteps())
				{
					body.add(stepRow(quest, step, current, state));
				}
			}

			if (!quest.getUnlocks().isEmpty())
			{
				body.add(Box.createVerticalStrut(7));
				body.add(sectionLabel(state == LofQuestState.FINISHED ? "Unlocked" : "Unlocks"));
				for (String unlock : quest.getUnlocks())
				{
					Color c = state == LofQuestState.FINISHED
						? LofQuestState.FINISHED.getColor() : ColorScheme.LIGHT_GRAY_COLOR;
					body.add(wrapped("• " + unlock, c));
				}
			}

			if (state == LofQuestState.IN_PROGRESS)
			{
				boolean tracked = plugin.getTrackedQuest() == quest;
				JButton trackButton = new JButton(tracked ? "Stop tracking" : "Track this quest");
				trackButton.setFocusPainted(false);
				trackButton.setToolTipText("Tracking draws this quest's client-side arrows"
					+ " (scene + minimap) at the current objective.");
				trackButton.addActionListener(e -> plugin.setTrackedQuest(tracked ? null : quest));
				JPanel buttonRow = new JPanel(new BorderLayout());
				buttonRow.setOpaque(false);
				buttonRow.setBorder(BorderFactory.createEmptyBorder(7, 0, 0, 0));
				buttonRow.add(trackButton, BorderLayout.CENTER);
				body.add(buttonRow);
			}

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
					expanded.remove(quest);
				}
				else
				{
					expanded.add(quest);
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

	private String headerTag(LofQuest quest, LofQuestState state)
	{
		if (state == LofQuestState.IN_PROGRESS)
		{
			String tracked = plugin.getTrackedQuest() == quest ? " ◆" : "";
			return quest.completedSteps(client) + "/" + quest.getSteps().size() + tracked;
		}
		return state.getLabel();
	}

	private Component stepRow(LofQuest quest, LofQuestStep step, int currentOrdinal, LofQuestState state)
	{
		boolean done = state == LofQuestState.FINISHED || currentOrdinal > step.getOrdinal();
		boolean current = state == LofQuestState.IN_PROGRESS && currentOrdinal == step.getOrdinal();

		String marker = done ? "✓ " : current ? "➤ " : "○ ";
		String text = marker + step.getLabel() + quest.stepProgress(client, step);
		if (current && step.getDetail() != null)
		{
			text += "<br>&nbsp;&nbsp;&nbsp;<i>" + step.getDetail() + "</i>";
		}

		Color color = done ? LofQuestState.FINISHED.getColor()
			: current ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR;
		JLabel row = wrapped(text, color);
		if (current)
		{
			row.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
		}
		return row;
	}

	private JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
		label.setForeground(new Color(0xc8, 0xa9, 0x50)); // parchment gold
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
}
