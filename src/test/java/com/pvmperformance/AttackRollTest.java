package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// The attack roll is floored at zero, so a deeply negative equipment bonus
// reads as "will not hit" rather than as no figure at all.
public class AttackRollTest
{
	/** The case that was seen in game: a spell thrown from a whip setup. */
	@Test
	public void flooredWhenTheEquipmentBonusIsBelowMinusSixtyFour()
	{
		// Magic level 112 with no prayer, and a melee loadout's magic attack.
		assertEquals(0, CombatCalc.attackRoll(121, -70, 1.0));
		assertEquals(0, CombatCalc.attackRoll(121, -65, 1.0));
	}

	/** Exactly -64 is the boundary, and is zero without needing the floor. */
	@Test
	public void isZeroAtTheBoundary()
	{
		assertEquals(0, CombatCalc.attackRoll(121, -64, 1.0));
	}

	/** Everything above the boundary is untouched by the floor. */
	@Test
	public void isUnchangedForAnOrdinaryLoadout()
	{
		assertEquals(121 * 4, CombatCalc.attackRoll(121, -60, 1.0));
		assertEquals(121 * 174, CombatCalc.attackRoll(121, 110, 1.0));
		// The gear multiplier still applies; salve (i) on a magic loadout.
		assertEquals((int) (121 * 174 * 1.15), CombatCalc.attackRoll(121, 110, 1.15));
	}

	/**
	 * The floored roll has to survive the hit chance formula as a real
	 * probability, since that is what the negative one failed to do.
	 */
	@Test
	public void producesAUsableHitChance()
	{
		for (int bonus = -120; bonus <= 200; bonus += 1)
		{
			final int attRoll = CombatCalc.attackRoll(121, bonus, 1.0);
			assertTrue("roll went negative at bonus " + bonus, attRoll >= 0);
		}
	}
}
