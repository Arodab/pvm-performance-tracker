package com.pvmperformance;

import lombok.Getter;

// Running totals for the current trip, so the overlay can show how a session
// went rather than resetting at each kill. One unlucky kill says little.
@Getter
class SessionTotals
{
	private long startMillis;
	private long lastActivityMillis;
	private long firstActivityMillis;
	private int fights;
	private int kills;
	private int damageDealt;
	private int attempts;
	private int hits;
	// Attacks that went out and never resolved, because the target died or
	// changed form in flight. Not misses; see Fight.recordAttackNulled.
	private int nulled;
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
		firstActivityMillis = 0;
		fights = 0;
		kills = 0;
		damageDealt = 0;
		attempts = 0;
		hits = 0;
		nulled = 0;
		attacksMade = 0;
		attacksPrayed = 0;
		attacksPotted = 0;
		attacksSwitched = 0;
		sumActualSetup = 0;
		sumIdealSetup = 0;
		ticksLostEating = 0;
		ticksLostOther = 0;
		combatTicks = 0;
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
		noteActivity(now);
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

	/** Damage from one hitsplat; see {@code Fight.recordDamageDealt}. */
	void recordAttempt(int damage, boolean landedAttack, long now)
	{
		damageDealt += damage;
		if (landedAttack)
		{
			hits++;
		}
		noteActivity(now);
	}

	/**
	 * Adds what this attack was expected to do. The max hit is not accumulated:
	 * it is a property of the loadout rather than something that adds up.
	 */
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

	void recordAttackNulled()
	{
		nulled++;
	}

	/** Attacks that actually got an answer, which is what accuracy divides by. */
	int resolvedAttempts()
	{
		return Math.max(0, attempts - nulled);
	}

	double accuracy()
	{
		final int resolved = resolvedAttempts();
		return resolved == 0 ? 0 : (double) hits / resolved;
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

	/**
	 * How long the trip has run, from its first attack rather than from when the
	 * plugin started.
	 */
	long durationMillis()
	{
		return firstActivityMillis == 0 ? 0
			: Math.max(0, lastActivityMillis - firstActivityMillis);
	}

	// Stamped by the first thing that happens rather than at construction, so the
	// trip reads no time at all until the first attack.
	private void noteActivity(long now)
	{
		if (firstActivityMillis == 0)
		{
			firstActivityMillis = now;
		}
		lastActivityMillis = now;
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
