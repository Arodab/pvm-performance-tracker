package com.pvmperformance;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

class PvmPerformancePanel extends PluginPanel
{
	private static final String ALL_NPCS = "All NPCs";

	private final PvmPerformancePlugin plugin;

	private final JCheckBox bossOnly = new JCheckBox("Boss only");
	private final JTextField search = new JTextField();
	private final JComboBox<String> bossSelect = new JComboBox<>();
	private final JLabel status = new JLabel(" ");
	private final JPanel list = new JPanel();

	PvmPerformancePanel(PvmPerformancePlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout(0, 8));

		add(buildHeader(), BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.add(list, BorderLayout.NORTH);
		final JScrollPane scroll = new JScrollPane(wrap,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(scroll, BorderLayout.CENTER);

		refresh();
	}

	private JPanel buildHeader()
	{
		final JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("PvM Performance");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		header.add(title);
		header.add(Box.createVerticalStrut(8));

		search.setToolTipText("Filter the list by NPC name");
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refresh();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refresh();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refresh();
			}
		});
		fullWidth(header, search);
		header.add(Box.createVerticalStrut(6));

		bossSelect.addItem(ALL_NPCS);
		for (String boss : plugin.getBossDisplayNames())
		{
			bossSelect.addItem(boss);
		}
		bossSelect.setToolTipText("Jump to a boss (shows its stats, or 'no data' if not fought)");
		bossSelect.addActionListener(e -> refresh());
		fullWidth(header, bossSelect);
		header.add(Box.createVerticalStrut(6));

		final JButton exportAll = new JButton("Export all");
		exportAll.setToolTipText("Export every tracked fight to a CSV file");
		exportAll.addActionListener(e -> plugin.exportAll(bossOnly.isSelected()));

		final JButton clear = new JButton("Clear");
		clear.setToolTipText("Discard the tracked fight history");
		clear.addActionListener(e ->
		{
			plugin.clearHistory();
			refresh();
		});

		final JButton resetTrip = new JButton("New trip");
		resetTrip.setToolTipText("Start the whole-trip overlay totals over. "
			+ "Tracked fight history is untouched");
		resetTrip.addActionListener(e -> plugin.resetSession());

		final JPanel buttons = new JPanel(new GridLayout(1, 3, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(exportAll);
		buttons.add(resetTrip);
		buttons.add(clear);
		fullWidth(header, buttons);
		header.add(Box.createVerticalStrut(4));

		bossOnly.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bossOnly.setForeground(Color.WHITE);
		bossOnly.setToolTipText("Show and export only NPCs above the boss HP threshold");
		bossOnly.addActionListener(e -> refresh());
		fullWidth(header, bossOnly);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setAlignmentX(LEFT_ALIGNMENT);
		header.add(status);

		return header;
	}

	private static void fullWidth(JPanel box, JComponent c)
	{
		c.setAlignmentX(LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		box.add(c);
	}

	void setStatus(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(text);
			status.setToolTipText(text);
		});
	}

	void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			list.removeAll();

			// A specific boss picked in the selector focuses the panel on it.
			final Object selected = bossSelect.getSelectedItem();
			if (selected != null && !ALL_NPCS.equals(selected))
			{
				final String bossName = selected.toString();
				NpcStats match = null;
				for (NpcStats s : plugin.getNpcStats(false))
				{
					if (s.getName().equalsIgnoreCase(bossName))
					{
						match = s;
						break;
					}
				}
				if (match != null)
				{
					list.add(buildRow(match));
				}
				else
				{
					final JLabel none = new JLabel("<html>No data for <b>" + bossName + "</b> yet.</html>");
					none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					none.setBorder(new EmptyBorder(4, 2, 4, 2));
					list.add(none);
				}
				list.revalidate();
				list.repaint();
				return;
			}

			final String query = search.getText().trim().toLowerCase();
			int shown = 0;
			for (NpcStats s : plugin.getNpcStats(bossOnly.isSelected()))
			{
				if (!query.isEmpty() && !s.getName().toLowerCase().contains(query))
				{
					continue;
				}
				list.add(buildRow(s));
				list.add(Box.createVerticalStrut(4));
				shown++;
			}
			if (shown == 0)
			{
				final JLabel empty = new JLabel(query.isEmpty()
					? "<html>No fights tracked yet. Fight an NPC and it will show up here.</html>"
					: "<html>No tracked NPC matches that name.</html>");
				empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.setBorder(new EmptyBorder(4, 2, 4, 2));
				list.add(empty);
			}
			list.revalidate();
			list.repaint();
		});
	}

	/**
	 * The efficiency and tick loss line, left off entirely when neither was
	 * measured, an empty pair of dashes reads as a bad score rather than as no
	 * score, which is what a history recorded before these existed would show.
	 */
	private static String quality(NpcStats s)
	{
		final double efficiency = s.efficiency();
		final double lost = s.ticksLostShare();
		if (efficiency < 0 && lost < 0)
		{
			return "";
		}
		final StringBuilder line = new StringBuilder("<br><font color='#a0a0a0'>");
		if (efficiency >= 0)
		{
			line.append(String.format("%.0f%% efficiency", efficiency * 100));
		}
		if (lost >= 0)
		{
			if (efficiency >= 0)
			{
				line.append(" &middot; ");
			}
			line.append(String.format("%.0f%% ticks lost", lost * 100));
		}
		return line.append("</font>").toString();
	}

	private JPanel buildRow(NpcStats s)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(6, 8, 6, 8));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));

		// Two lines: what was fought, then how well. Efficiency and lost ticks
		// are the two that say where to improve, so they go on the end where the
		// eye lands after the totals rather than being buried among them.
		final String info = String.format(
			"<html><b>%s</b><br><font color='#a0a0a0'>%d fights &middot; %d kills &middot; "
				+ "%.2f avg hit &middot; %.0f%% acc &middot; %s dmg</font>%s</html>",
			s.getName(), s.getFights(), s.getKills(), s.avgHit(), s.accuracy() * 100,
			QuantityFormatter.formatNumber(s.getTotalDamageDealt()),
			quality(s));
		final JLabel label = new JLabel(info);
		label.setForeground(Color.WHITE);
		row.add(label, BorderLayout.CENTER);

		final JButton export = new JButton("CSV");
		export.setToolTipText("Export " + s.getName() + " fights to CSV");
		export.setMargin(new java.awt.Insets(2, 6, 2, 6));
		export.addActionListener(e -> plugin.exportNpc(s.getName()));
		row.add(export, BorderLayout.EAST);

		return row;
	}
}
