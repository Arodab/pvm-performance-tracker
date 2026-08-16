package com.pvmperformance;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

class PvmPerformancePanel extends PluginPanel
{
	private final PvmPerformancePlugin plugin;

	private final JCheckBox bossOnly = new JCheckBox("Boss only");
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

		final JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(exportAll);
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
			final List<NpcStats> stats = plugin.getNpcStats(bossOnly.isSelected());
			if (stats.isEmpty())
			{
				final JLabel empty = new JLabel("<html>No fights tracked yet. Fight an NPC and it will show up here.</html>");
				empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.setBorder(new EmptyBorder(4, 2, 4, 2));
				list.add(empty);
			}
			else
			{
				for (NpcStats s : stats)
				{
					list.add(buildRow(s));
					list.add(Box.createVerticalStrut(4));
				}
			}
			list.revalidate();
			list.repaint();
		});
	}

	private JPanel buildRow(NpcStats s)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(6, 8, 6, 8));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));

		final String info = String.format(
			"<html><b>%s</b><br><font color='#a0a0a0'>%d fights &middot; %d kills &middot; %.2f dps &middot; %.0f%% acc &middot; %s dmg</font></html>",
			s.getName(), s.getFights(), s.getKills(), s.avgDps(), s.accuracy() * 100,
			QuantityFormatter.formatNumber(s.getTotalDamageDealt()));
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
