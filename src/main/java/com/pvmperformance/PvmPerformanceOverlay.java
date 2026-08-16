package com.pvmperformance;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

class PvmPerformanceOverlay extends OverlayPanel
{
	private final PvmPerformancePlugin plugin;
	private final PvmPerformanceConfig config;

	@Inject
	PvmPerformanceOverlay(PvmPerformancePlugin plugin, PvmPerformanceConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		final Fight fight = plugin.getDisplayFight();
		if (fight == null)
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(fight.getTargetName())
			.build());

		final int maxHit = plugin.getExpectedMeleeMaxHit();
		if (maxHit > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Max hit")
				.right("~" + maxHit)
				.build());
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Damage")
			.right(QuantityFormatter.formatNumber(fight.getDamageDealt()))
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("DPS")
			.right(String.format("%.2f", fight.dps()))
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Accuracy")
			.right(String.format("%d/%d (%.1f%%)", fight.getHits(), fight.getAttempts(), fight.accuracy() * 100))
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Taken")
			.right(QuantityFormatter.formatNumber(fight.getDamageTaken()))
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Time")
			.right(String.format("%.1fs", fight.durationMillis() / 1000.0))
			.build());

		return super.render(graphics);
	}
}
