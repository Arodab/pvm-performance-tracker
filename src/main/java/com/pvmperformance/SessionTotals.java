package com.pvmperformance;

import lombok.Getter;

// Running totals for the current trip, so the overlay can show how a whole
// session went rather than resetting at each kill. A single unlucky kill
// says little; forty of them say something.
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
	private int attacksMade;
	private int attacksPrayed;
	private int attacksPotted;
	// Attacks thrown with nothing better available to switch into. The gap to
	// attacksMade is the number that missed at least one switch.
	private int attacksSwitched;
	private double sumActualSetup;
	private double sumIdealSetup;
	private int ticksLostEating;
	private int ticksLostOther;
	private int combatTicks;
	// How long the player took to attack each respawned boss this trip. The best
	// of them is the interesting half: it is roughly the wait that could not be
	// avoided, so the distance from it to the average is what was actually lost.
	private int engagements;
	private int sumTicksToEngage;
	private int bestTicksToEngage;

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
		attacksMade = 0;
		attacksPrayed = 0;
		attacksPotted = 0;
		attacksSwitched = 0;
		sumActualSetup = 0;
		sumIdealSetup = 0;
		ticksLostEating = 0;
		ticksLostOther = 0;
		combatTicks = 0;
		engagements = 0;
		sumTicksToEngage = 0;
		bestTicksToEngage = 0;
		sumExpectedAccuracy = 0;
		sumExpectedAverageHit = 0;
	}

	void recordFightEnded(boolean died, Fight fight, long now)
	{
		fights++;
		if (died)
		{
			kills++;
		}
		lastActivityMillis = now;
	}

	// Books a tick that passed with the weapon off cooldown and no attack
	// made.
	void recordTickLost(boolean eating)
	{
		if (eating)
		{
			ticksLostEating++;
		}
		else
		{
			ticksLostOther++;
		}
		combatTicks++;
	}

	/** A tick that passed while the weapon was still on cooldown. */
	void recordTickSpent()
	{
		combatTicks++;
	}

	/** Ticks between a boss respawning and the player's first attack on it. */
	void recordEngaged(int ticks)
	{
		engagements++;
		sumTicksToEngage += ticks;
		if (bestTicksToEngage == 0 || ticks < bestTicksToEngage)
		{
			bestTicksToEngage = ticks;
		}
	}

	/** Mean ticks taken to attack a respawned boss, or 0 if none were timed. */
	double avgTicksToEngage()
	{
		return engagements == 0 ? 0 : (double) sumTicksToEngage / engagements;
	}

	/** Damage from one hitsplat; see {@code Fight.recordDamageDealt}. */
	void recordAttempt(int damage, boolean landedAttack, long now)
	{
		damageDealt += damage;
		if (landedAttack)
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
	/** How well one attack was set up, sampled on the tick it was made. */
	void recordAttackMade(boolean potted)
	{
		attacksMade++;
		combatTicks++;
		attempts++;
		if (potted)
		{
			attacksPotted++;
		}
	}

	void recordAttackResolved(boolean prayed, boolean switched, double actualSetup, double idealSetup)
	{
		if (prayed)
		{
			attacksPrayed++;
		}
		if (switched)
		{
			attacksSwitched++;
		}
		if (actualSetup >= 0 && idealSetup > 0)
		{
			sumActualSetup += actualSetup;
			sumIdealSetup += idealSetup;
		}
	}

	double efficiency()
	{
		return sumIdealSetup <= 0 ? -1 : sumActualSetup / sumIdealSetup;
	}

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
