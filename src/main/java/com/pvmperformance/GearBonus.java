package com.pvmperformance;

import lombok.Getter;

// The multipliers contributed by a gear effect; effects stack multiplicatively. Ported from the LlemonDuck dps-
// calculator (BSD-2), (c) Paul Norton, which carries one damage figure; the split into two is new here.
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
