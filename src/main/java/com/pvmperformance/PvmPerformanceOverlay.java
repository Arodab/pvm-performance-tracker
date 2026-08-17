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
		// The default width wraps the longer "actual (exp ...)" values onto a second line.
		setPreferredSize(new Dimension(160, 0));
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
		if (config.overlayBossesOnly() && !plugin.isBoss(fight))
		{
			return null;
		}

		// In whole-trip mode the measured side comes from the running totals; the
		// expected max hit stays live, since it describes the loadout worn now.
		final SessionTotals session = config.overlaySessionTotals() ? plugin.getSession() : null;
		final boolean trip = session != null;

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(trip
				? String.format("Trip: %d kill%s", session.getKills(), session.getKills() == 1 ? "" : "s")
				: fight.getTargetName())
			.build());

		final int maxHit = plugin.getExpectedMaxHit();
		if (maxHit > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Max hit")
				.right("~" + maxHit)
				.build());
		}

		final SpecialAttack spec = plugin.getSpecialAttack();
		final int specMaxHit = plugin.getExpectedSpecMaxHit();
		if (spec != null && specMaxHit > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Spec max")
				.right(spec.hits() > 1
					? "~" + specMaxHit + " (" + spec.hits() + ")"
					: "~" + specMaxHit)
				.build());
		}

		final int damage = trip ? session.getDamageDealt() : fight.getDamageDealt();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Damage")
			.right(QuantityFormatter.formatNumber(damage))
			.build());

		final double avgHit = trip ? session.averageHit() : fight.averageHit();
		final double expAvgHit = trip ? session.expectedAverageHit() : plugin.getExpectedAverageHit();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Avg hit")
			.right(expAvgHit >= 0
				? String.format("%.2f (exp %.2f)", avgHit, expAvgHit)
				: String.format("%.2f", avgHit))
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Hits")
			.right(trip
				? String.format("%d/%d", session.getHits(), session.getAttempts())
				: String.format("%d/%d", fight.getHits(), fight.getAttempts()))
			.build());

		final double accuracy = trip ? session.accuracy() : fight.accuracy();
		final double expAcc = trip ? session.expectedAccuracy() : plugin.getExpectedAccuracy();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Accuracy")
			.right(expAcc >= 0
				? String.format("%.0f%% (exp %.0f%%)", accuracy * 100, expAcc * 100)
				: String.format("%.0f%%", accuracy * 100))
			.build());

		final double lostShare = trip ? session.ticksLostShare() : fight.ticksLostShare();
		if (lostShare >= 0)
		{
			final int lost = trip ? session.getTicksLost() : fight.getTicksLost();
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Ticks lost")
				.right(String.format("%d (%.0f%%)", lost, lostShare * 100))
				.build());
		}

		final long millis = trip ? session.durationMillis() : fight.durationMillis();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Time")
			.right(trip ? formatDuration(millis) : String.format("%.1fs", millis / 1000.0))
			.build());

		return super.render(graphics);
	}

	private static String formatDuration(long millis)
	{
		final long seconds = millis / 1000;
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}
}
