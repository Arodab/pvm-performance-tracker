package com.pvmperformance;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A pair of multipliers contributed by a gear effect: one applied to the attack
 * roll and one to the max hit. Effects stack multiplicatively.
 *
 * <p>Ported from the LlemonDuck dps-calculator (BSD-2), (c) Paul Norton.
 */
@Getter
@RequiredArgsConstructor
class GearBonus
{
	static final GearBonus NONE = new GearBonus(1.0, 1.0);

	private final double accuracy;
	private final double damage;

	static GearBonus of(double accuracy, double damage)
	{
		return new GearBonus(accuracy, damage);
	}

	/** An effect that boosts the attack roll and the max hit by the same factor. */
	static GearBonus symmetric(double bonus)
	{
		return new GearBonus(bonus, bonus);
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
		return new GearBonus(accuracy * other.accuracy, damage * other.damage);
	}

	boolean isNone()
	{
		return accuracy == 1.0 && damage == 1.0;
	}
}
