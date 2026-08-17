package com.pvmperformance;

import lombok.Getter;

/**
 * Running totals for the current trip, so the overlay can show how a whole
 * session went rather than resetting at each kill. A single unlucky kill says
 * little; forty of them say something.
 *
 * <p>Fed from the same events as {@link Fight} rather than by summing finished
 * fights, so the figures include the kill in progress.
 */
@Getter
class SessionTotals
{
	private long startMillis;
	private long lastActivityMillis;
	private int fights;
	private int kills;
	private int damageDealt;
	private int attempts;
	private int hits;
	private int ticksLost;
	private int combatTicks;

	private double sumExpectedMaxHit;
	private int expectedMaxHitSamples;
	private double sumExpectedAccuracy;
	private double sumExpectedAverageHit;
	private int expectedTargetSamples;

	SessionTotals(long now)
	{
		this.startMillis = now;
		this.lastActivityMillis = now;
	}

	void reset(long now)
	{
		startMillis = now;
		lastActivityMillis = now;
		fights = 0;
		kills = 0;
		damageDealt = 0;
		attempts = 0;
		hits = 0;
		ticksLost = 0;
		combatTicks = 0;
		sumExpectedMaxHit = 0;
		expectedMaxHitSamples = 0;
		sumExpectedAccuracy = 0;
		sumExpectedAverageHit = 0;
		expectedTargetSamples = 0;
	}

	void recordFightStarted(long now)
	{
		fights++;
		lastActivityMillis = now;
	}

	void recordFightEnded(boolean died, Fight fight, long now)
	{
		if (died)
		{
			kills++;
		}
		// Tick loss is only meaningful per fight, since the gap between one
		// fight's last attack and the next fight's first is travel, not waste.
		ticksLost += fight.getTicksLost();
		combatTicks += fight.getCombatTicks();
		lastActivityMillis = now;
	}

	void recordAttempt(int damage, long now)
	{
		damageDealt += damage;
		attempts++;
		if (damage > 0)
		{
			hits++;
		}
		lastActivityMillis = now;
	}

	void recordExpected(int maxHit, double accuracy, double averageHit)
	{
		if (maxHit > 0)
		{
			sumExpectedMaxHit += maxHit;
			expectedMaxHitSamples++;
		}
		if (accuracy >= 0 && averageHit >= 0)
		{
			sumExpectedAccuracy += accuracy;
			sumExpectedAverageHit += averageHit;
			expectedTargetSamples++;
		}
	}

	long durationMillis()
	{
		return Math.max(0, lastActivityMillis - startMillis);
	}

	double accuracy()
	{
		return attempts == 0 ? 0 : (double) hits / attempts;
	}

	double averageHit()
	{
		return attempts == 0 ? 0 : (double) damageDealt / attempts;
	}

	double expectedMaxHit()
	{
		return expectedMaxHitSamples == 0 ? -1 : sumExpectedMaxHit / expectedMaxHitSamples;
	}

	double expectedAccuracy()
	{
		return expectedTargetSamples == 0 ? -1 : sumExpectedAccuracy / expectedTargetSamples;
	}

	double expectedAverageHit()
	{
		return expectedTargetSamples == 0 ? -1 : sumExpectedAverageHit / expectedTargetSamples;
	}

	double ticksLostShare()
	{
		return combatTicks <= 0 ? -1 : (double) ticksLost / combatTicks;
	}
}
