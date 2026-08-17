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
	private int ticksLostEating;
	private int ticksLostOther;
	private int combatTicks;

	// Running totals of what each attack was expected to do, so the measured
	// side can be compared against the sum of every weapon's own contribution.
	private double sumExpectedAccuracy;
	private double sumExpectedAverageHit;

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
		ticksLostEating = 0;
		ticksLostOther = 0;
		combatTicks = 0;
		sumExpectedAccuracy = 0;
		sumExpectedAverageHit = 0;
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
		ticksLostEating += fight.getTicksLostEating();
		ticksLostOther += fight.getTicksLostOther();
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

	/**
	 * Adds what this attack was expected to do. The max hit isn't accumulated:
	 * it is a property of the loadout rather than something that adds up, and
	 * the overlay shows it live for whatever is held now.
	 */
	void recordExpected(double accuracy, double averageHit)
	{
		if (accuracy >= 0 && averageHit >= 0)
		{
			sumExpectedAccuracy += accuracy;
			sumExpectedAverageHit += averageHit;
		}
	}

	long durationMillis()
	{
		return Math.max(0, lastActivityMillis - startMillis);
	}

	int getTicksLost()
	{
		return ticksLostEating + ticksLostOther;
	}

	double ticksLostShare()
	{
		return combatTicks <= 0 ? -1 : (double) getTicksLost() / combatTicks;
	}
}
