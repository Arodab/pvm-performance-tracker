package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * A scythe swing is several hits, each rolling its own accuracy and its own
 * damage, and each max is half the one before ROUNDED DOWN.
 *
 * <p>Wiki (Scythe of Vitur): "each hit will deal 50% less damage, (rounded
 * down), than the preceding hit", with a base of 47 giving 47-23-11 for a true
 * maximum of 81, and a base of 48 giving 48-24-12 for 84.
 */
public class ScytheHitsTest
{
	private static int total(int firstMax, int hits)
	{
		int total = 0;
		int max = firstMax;
		for (int hit = 0; hit < hits; hit++)
		{
			total += max;
			max = CombatCalc.nextScytheMax(max);
		}
		return total;
	}

	@Test
	public void theWikisOddExample()
	{
		assertEquals(23, CombatCalc.nextScytheMax(47));
		assertEquals(11, CombatCalc.nextScytheMax(23));
		assertEquals(81, total(47, 3));
	}

	@Test
	public void theWikisEvenExample()
	{
		assertEquals(24, CombatCalc.nextScytheMax(48));
		assertEquals(12, CombatCalc.nextScytheMax(24));
		assertEquals(84, total(48, 3));
	}

	@Test
	public void theMaxFromTheReportedFight()
	{
		// 51 max at Hespori: 51 + 25 + 12, which is 88 and not the 89 that
		// halving without rounding gives.
		assertEquals(88, total(51, 3));
	}

	@Test
	public void aTwoByTwoTargetTakesTwoOfThem()
	{
		assertEquals(76, total(51, 2));
	}

	@Test
	public void anOrdinaryWeaponIsOneHit()
	{
		assertEquals(51, total(51, 1));
	}

	@Test
	public void aLandedAttackIsAtLeastOneRollConnecting()
	{
		// The measured side counts one landed attack per swing however many of
		// the hits connect, so the expectation beside it must be the chance
		// that any did - not the sum of the chances, which reads three to one
		// against the player.
		assertEquals(0.75, CombatCalc.landChance(0.5, 2), 1e-9);
		assertEquals(0.875, CombatCalc.landChance(0.5, 3), 1e-9);
	}

	@Test
	public void oneHitIsJustItsAccuracy()
	{
		assertEquals(0.849, CombatCalc.landChance(0.849, 1), 1e-9);
	}

	@Test
	public void anUnknownAccuracyStaysUnknown()
	{
		assertEquals(-1.0, CombatCalc.landChance(-1.0, 3), 1e-9);
	}
}
