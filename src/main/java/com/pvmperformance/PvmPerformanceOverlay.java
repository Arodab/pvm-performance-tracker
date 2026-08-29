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

		// Expected-only asks for the loadout's figures and nothing else. Otherwise
		// it is the whole trip, the raid so far, or the room being fought - the room
		// by default, since a single fight would blink between a room's adds.
		final boolean expectedOnly = config.overlayExpectedOnly();
		final SessionTotals session = expectedOnly || !config.overlaySessionTotals()
			? null : plugin.getSession();
		final Raid raid = expectedOnly || session != null || config.raidScope() != RaidScope.RAID
			? null : plugin.getCurrentRaid();
		final Encounter room = session != null || raid != null ? null : plugin.getDisplayEncounter();
		if (!expectedOnly && session == null && raid == null && room == null)
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(title(session, raid, room, fight))
			.build());

		final int maxHit = plugin.getExpectedMaxHit();
		if (maxHit > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Max hit")
				.right(String.valueOf(maxHit))
				.build());
		}

		final SpecialAttack spec = plugin.getSpecialAttack();
		final int specMaxHit = plugin.getExpectedSpecMaxHit();
		if (spec != null && specMaxHit > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Spec max")
				.right(spec.hits() > 1
					? specMaxHit + " (" + spec.hits() + ")"
					: String.valueOf(specMaxHit))
				.build());
		}

		// Top half: what the loadout does against this target. These hold still
		// through a fight, so they read as stats rather than as a running score.
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

		if (expectedOnly)
		{
			return super.render(graphics);
		}

		// Bottom half: what happened, against what the model expected. The expected
		// side is a running total of each attack's own figure, so swapping weapons
		// mid-fight adds each weapon's share.
		panelComponent.getChildren().add(LineComponent.builder().left("").right("").build());

		final int damage = session != null ? session.getDamageDealt()
			: raid != null ? raid.getDamageDealt() : room.getDamageDealt();
		final double expDamage = session != null ? session.getSumExpectedAverageHit()
			: raid != null ? raid.sumExpectedAverageHit() : room.sumExpectedAverageHit();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Damage")
			.right(expDamage > 0
				? String.format("%s / %.0f", QuantityFormatter.formatNumber(damage), expDamage)
				: QuantityFormatter.formatNumber(damage))
			.build());

		final int hits = session != null ? session.getHits()
			: raid != null ? raid.getHits() : room.getHits();
		final double expHits = session != null ? session.getSumExpectedAccuracy()
			: raid != null ? raid.sumExpectedAccuracy() : room.sumExpectedAccuracy();
		// Measured hits against what was expected, then the accuracy those two make.
		// The top half carries the accuracy the LOADOUT should get; this is the one
		// that happened.
		final double realAccuracy = session != null ? session.accuracy()
			: raid != null ? raid.accuracy() : room.accuracy();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Hits")
			.right(expHits > 0
				? String.format("%d / %.1f (%.0f%%)", hits, expHits, realAccuracy * 100)
				: String.valueOf(hits))
			.build());

		// How well the attacks were set up, with the parts shown only when one of
		// them slipped, a clean fight needs no breakdown.
		final double efficiency = session != null ? session.efficiency()
			: raid != null ? raid.efficiency() : room.efficiency();
		if (efficiency >= 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Efficiency")
				.right(String.format("%.0f%%", efficiency * 100))
				.build());

			final int made = session != null ? session.getAttacksMade()
				: raid != null ? raid.getAttacksMade() : room.getAttacksMade();
			final int prayed = session != null ? session.getAttacksPrayed()
				: raid != null ? raid.getAttacksPrayed() : room.getAttacksPrayed();
			final int potted = session != null ? session.getAttacksPotted()
				: raid != null ? raid.getAttacksPotted() : room.getAttacksPotted();
			// Always shown, both of them. Hiding a counter that reads full makes
			// it impossible to tell a perfect run from one that is not counting.
			panelComponent.getChildren().add(LineComponent.builder()
				.left("  prayed")
				.right(prayed + "/" + made)
				.build());
			panelComponent.getChildren().add(LineComponent.builder()
				.left("  potted")
				.right(potted + "/" + made)
				.build());
			final int switched = session != null ? session.getAttacksSwitched()
				: raid != null ? raid.getAttacksSwitched() : room.getAttacksSwitched();
			// The gap to made is the number of attacks that missed at least one
			// switch, read the same way as the two lines above it.
			panelComponent.getChildren().add(LineComponent.builder()
				.left("  switches")
				.right(switched + "/" + made)
				.build());
		}

		final double lostShare = session != null ? session.ticksLostShare()
			: raid != null ? raid.ticksLostShare() : room.ticksLostShare();
		if (lostShare >= 0)
		{
			final int lost = session != null ? session.getTicksLost()
				: raid != null ? raid.getTicksLost() : room.getTicksLost();
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Ticks lost")
				.right(String.format("%d (%.0f%%)", lost, lostShare * 100))
				.build());

			final int eating = session != null ? session.getTicksLostEating()
				: raid != null ? raid.getTicksLostEating() : room.getTicksLostEating();
			if (eating > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("  to eating")
					.right(String.valueOf(eating))
					.build());
			}
		}

		final boolean trip = session != null;
		final long millis = session != null ? session.durationMillis()
			: raid != null ? raid.durationMillis() : room.durationMillis();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Time")
			.right(trip ? formatDuration(millis) : String.format("%.1fs", millis / 1000.0))
			.build());

		return super.render(graphics);
	}

	/**
	 * What is being reported on. A raid names itself and how far in it is; a
	 * room names itself, which for a grouped room is the room rather than
	 * whichever of its NPCs happens to be dying.
	 */
	private static String title(SessionTotals session, Raid raid, Encounter room, Fight fight)
	{
		if (session != null)
		{
			return String.format("Trip: %d kill%s", session.getKills(), session.getKills() == 1 ? "" : "s");
		}
		if (raid != null)
		{
			return String.format("%s: %d room%s", raid.getName(),
				raid.getEncounters().size(), raid.getEncounters().size() == 1 ? "" : "s");
		}
		return room == null ? fight.getTargetName() : room.getName();
	}

	private static String formatDuration(long millis)
	{
		final long seconds = millis / 1000;
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}
}
