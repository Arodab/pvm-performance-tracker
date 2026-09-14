package com.pvmperformance;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

class PvmPerformanceOverlay extends OverlayPanel
{
	/**
	 * The panel is only as wide as it needs to be. A single fight's lines fit
	 * the standard width; a whole trip's do not - "Damage 83,955 / 100,811"
	 * wrapped onto a second line and the box became a wall of half sentences.
	 * Measured every frame from the text actually about to be drawn, since what
	 * is on it changes with the mode, the loadout and the size of the numbers.
	 */
	private static final int MIN_WIDTH = 129;
	/** Past this a long name is better wrapped than allowed to cross the screen. */
	private static final int MAX_WIDTH = 400;
	/** The panel's own border, plus the gap that keeps the two columns apart. */
	private static final int PADDING = 14;

	private final PvmPerformancePlugin plugin;
	private final PvmPerformanceConfig config;

	// Set at the top of each render and used by line(); the overlay is drawn on one thread.
	private FontMetrics metrics;
	private int contentWidth;

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

		metrics = graphics.getFontMetrics();
		contentWidth = 0;

		final Fight fight = plugin.getDisplayFight();
		if (fight == null)
		{
			return null;
		}
		// A finished fight is left up so the kill can be read, but it was left up FOREVER: the last fight of a trip sat on
		// screen until the next one, which outside a boss trip is the rest of the session. It now goes when the fight
		// timeout says the fighting is over, which is the same clock that ended the fight itself.
		if (fight.isEnded()
			&& System.currentTimeMillis() - fight.getEndMillis() > config.fightTimeoutTicks() * 600L)
		{
			return null;
		}
		if (config.overlayBossesOnly() && !plugin.isBoss(fight))
		{
			return null;
		}

		// Expected-only asks for the loadout's figures and nothing else. Otherwise it is the whole trip, the raid so far, or
		// the room being fought - the room by default, since a single fight would blink between a room's adds.
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

		final String heading = title(session, raid, room, fight);
		contentWidth = Math.max(contentWidth, metrics.stringWidth(heading));
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(heading)
			.build());

		final SpecialAttack spec = plugin.getSpecialAttack();
		final int specMaxHit = plugin.getExpectedSpecMaxHit();
		final boolean hasSpec = spec != null && specMaxHit > 0;

		final int maxHit = plugin.getExpectedMaxHit();
		if (maxHit > 0)
		{
			line(hasSpec ? "Max hit (Spec)" : "Max hit",
				hasSpec ? String.format("%d (%d)", maxHit, specMaxHit) : String.valueOf(maxHit));
		}

		// Top half: what the loadout does against this target. These hold still through a fight, so they read as stats rather
		// than as a running score.
		final double expAcc = plugin.getExpectedAccuracy();
		final double specAcc = plugin.getExpectedSpecAccuracy();
		if (expAcc >= 0)
		{
			line(hasSpec ? "Accuracy (Spec)" : "Accuracy",
				hasSpec && specAcc >= 0 ? String.format("%.1f%% (%.1f%%)", expAcc * 100, specAcc * 100) : String.format("%.1f%%", expAcc * 100));
		}

		final double expAvgHit = plugin.getExpectedAverageHit();
		final double specAvgHit = plugin.getExpectedSpecAverageHit();
		if (expAvgHit >= 0)
		{
			line(hasSpec ? "Avg hit (Spec)" : "Avg hit",
				hasSpec && specAvgHit >= 0 ? String.format("%.2f (%.2f)", expAvgHit, specAvgHit) : String.format("%.2f", expAvgHit));
		}

		// Under Avg hit because it answers the question a bigger average hit cannot: which of two setups is actually better
		// when they swing at different speeds. Off the weapon's own cooldown rather than any clock, so it holds still through
		// a fight like the rest of this half.
		final double expDps = plugin.getExpectedDps();
		if (expDps >= 0)
		{
			line("DPS", String.format("%.2f", expDps));
		}

		if (expectedOnly)
		{
			return super.render(graphics);
		}

		// Bottom half: what happened, against what the model expected. The expected side is a running total of each attack's
		// own figure, so swapping weapons mid-fight adds each weapon's share.
		line("", "");

		final int damage = session != null ? session.getDamageDealt()
			: raid != null ? raid.getDamageDealt() : room.getDamageDealt();
		final double expDamage = session != null ? session.getSumExpectedAverageHit()
			: raid != null ? raid.sumExpectedAverageHit() : room.sumExpectedAverageHit();
		line("Damage",
			expDamage > 0 ? String.format("%s / %.0f", QuantityFormatter.formatNumber(damage), expDamage) : QuantityFormatter.formatNumber(damage));

		final int hits = session != null ? session.getHits()
			: raid != null ? raid.getHits() : room.getHits();
		// Measured hits against what was expected, then the accuracy those two make. The top half carries the accuracy the
		// LOADOUT should get; this is the one that happened.
		final double realAccuracy = session != null ? session.accuracy()
			: raid != null ? raid.accuracy() : room.accuracy();
		// Landed against what was EXPECTED to land, which is the comparison this plugin is for. How many were thrown is
		// already on screen under Efficiency, where prayed, potted and switches all divide by it, so spending this line on
		// it would only repeat that. The percentage is the real one - landed over thrown - so the two figures beside it
		// and the number below it always agree.
		//
		// With an AoE weapon both halves are per npc reached: five adds caught by one throw are five chances, and the
		// expected side is the sum of their five accuracies.
		final double expHits = session != null ? session.getSumExpectedAccuracy()
			: raid != null ? raid.sumExpectedAccuracy() : room.sumExpectedAccuracy();
		line("Hits", expHits > 0
			? String.format("%d / %.1f (%.0f%%)", hits, expHits, realAccuracy * 100)
			: String.valueOf(hits));

		// How well the attacks were set up, with the parts shown only when one of them slipped, a clean fight needs no
		// breakdown.
		final double efficiency = session != null ? session.efficiency()
			: raid != null ? raid.efficiency() : room.efficiency();
		if (efficiency >= 0)
		{
			line("Efficiency", String.format("%.0f%%", efficiency * 100));

			final int made = session != null ? session.getAttacksMade()
				: raid != null ? raid.getAttacksMade() : room.getAttacksMade();
			final int prayed = session != null ? session.getAttacksPrayed()
				: raid != null ? raid.getAttacksPrayed() : room.getAttacksPrayed();
			final int potted = session != null ? session.getAttacksPotted()
				: raid != null ? raid.getAttacksPotted() : room.getAttacksPotted();
			// Always shown, both of them. Hiding a counter that reads full makes it impossible to tell a perfect run from one
			// that is not counting.
			line("  prayed", prayed + "/" + made);
			line("  potted", potted + "/" + made);
			final int switched = session != null ? session.getAttacksSwitched()
				: raid != null ? raid.getAttacksSwitched() : room.getAttacksSwitched();
			// The gap to made is the number of attacks that missed at least one switch, read the same way as the two lines above
			// it.
			line("  switches", switched + "/" + made);
		}

		final double lostShare = session != null ? session.ticksLostShare()
			: raid != null ? raid.ticksLostShare() : room.ticksLostShare();
		if (lostShare >= 0)
		{
			final int lost = session != null ? session.getTicksLost()
				: raid != null ? raid.getTicksLost() : room.getTicksLost();
			line("Ticks lost", String.format("%d (%.0f%%)", lost, lostShare * 100));

			final int eating = session != null ? session.getTicksLostEating()
				: raid != null ? raid.getTicksLostEating() : room.getTicksLostEating();
			if (eating > 0)
			{
				line("  to eating", String.valueOf(eating));
			}
		}

		panelComponent.setPreferredSize(new Dimension(
			Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, contentWidth + PADDING)), 0));
		return super.render(graphics);
	}

	/**
	 * One row, measured as it is added. Both halves are drawn on the same line
	 * with the right one flush to the edge, so the row needs the sum of them.
	 */
	private void line(String left, String right)
	{
		contentWidth = Math.max(contentWidth,
			metrics.stringWidth(left == null ? "" : left) + metrics.stringWidth(right == null ? "" : right));
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.build());
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
}
