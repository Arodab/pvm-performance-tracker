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

		// Top half: what the loadout in hand does against this target. These hold
		// still through a fight, so they read as the stats of what is being used
		// rather than as a running score.
		final double expAcc = plugin.getExpectedAccuracy();
		if (expAcc >= 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Accuracy")
				.right(String.format("%.1f%%", expAcc * 100))
				.build());
		}

		final double expAvgHit = plugin.getExpectedAverageHit();
		if (expAvgHit >= 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Avg hit")
				.right(String.format("%.2f", expAvgHit))
				.build());
		}

		// Bottom half: what has actually happened, against what the model said to
		// expect of it. The expected side is a running total of each attack's own
		// figure, so swapping weapons mid-fight adds each weapon's own share.
		panelComponent.getChildren().add(LineComponent.builder().left("").right("").build());

		final int damage = trip ? session.getDamageDealt() : fight.getDamageDealt();
		final double expDamage = trip
			? session.getSumExpectedAverageHit() : fight.getSumExpectedAverageHit();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Damage")
			.right(expDamage > 0
				? String.format("%s / %.0f", QuantityFormatter.formatNumber(damage), expDamage)
				: QuantityFormatter.formatNumber(damage))
			.build());

		final int hits = trip ? session.getHits() : fight.getHits();
		final double expHits = trip
			? session.getSumExpectedAccuracy() : fight.getSumExpectedAccuracy();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Hits")
			.right(expHits > 0
				? String.format("%d / %.1f", hits, expHits)
				: String.valueOf(hits))
			.build());

		final double lostShare = trip ? session.ticksLostShare() : fight.ticksLostShare();
		if (lostShare >= 0)
		{
			final int lost = trip ? session.getTicksLost() : fight.getTicksLost();
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Ticks lost")
				.right(String.format("%d (%.0f%%)", lost, lostShare * 100))
				.build());

			final int eating = trip ? session.getTicksLostEating() : fight.getTicksLostEating();
			if (eating > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("  to eating")
					.right(String.valueOf(eating))
					.build());
			}
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
