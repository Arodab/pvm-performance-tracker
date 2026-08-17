package com.pvmperformance;

import lombok.Getter;

/**
 * The multipliers contributed by a gear effect. Effects stack multiplicatively.
 *
 * <p>{@link #damage} is what the max hit can reach and {@link #expectedDamage}
 * is what an average hit deals. They differ for effects where the best case
 * isn't the typical case: procs that only fire some of the time (the keris crit,
 * the ahrim's set), and weapons that roll damage over a narrowed band rather
 * than from zero (Osmumten's fang). For every other effect the two are equal.
 *
 * <p>An ordinary hit rolls uniformly from 0 to the max, so its average is half
 * the max; {@code expectedDamage} is where anything that shifts that average —
 * including a raised minimum hit — belongs.
 *
 * <p>Ported from the LlemonDuck dps-calculator (BSD-2), (c) Paul Norton, which
 * carries one damage figure; the split is this plugin's.
 */
@Getter
class GearBonus
{
	static final GearBonus NONE = new GearBonus(1.0, 1.0, 1.0);

	private final double accuracy;
	private final double damage;
	private final double expectedDamage;

	private GearBonus(double accuracy, double damage, double expectedDamage)
	{
		this.accuracy = accuracy;
		this.damage = damage;
		this.expectedDamage = expectedDamage;
	}

	/** An effect that lands on every hit, so the max and the average agree. */
	static GearBonus of(double accuracy, double damage)
	{
		return new GearBonus(accuracy, damage, damage);
	}

	/** An effect that boosts the attack roll and the damage by the same factor. */
	static GearBonus symmetric(double bonus)
	{
		return of(bonus, bonus);
	}

	/**
	 * An effect whose best case and average differ: {@code maxDamage} is
	 * reachable but {@code expectedDamage} is what it averages out to.
	 */
	static GearBonus split(double accuracy, double maxDamage, double expectedDamage)
	{
		return new GearBonus(accuracy, maxDamage, expectedDamage);
	}

	GearBonus combine(GearBonus other)
	{
		if (other == NONE)
		{
			return this;
		}
		if (this == NONE)
		{
			return other;
		}
		return new GearBonus(
			accuracy * other.accuracy,
			damage * other.damage,
			expectedDamage * other.expectedDamage);
	}

	boolean isNone()
	{
		return accuracy == 1.0 && damage == 1.0 && expectedDamage == 1.0;
	}
}
