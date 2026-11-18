package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The Fractured Archive's weapons (wiki, November 2026).
 *
 * <p>The charged Obligator's thrust makes two damage rolls and keeps the best,
 * plus one more for each of stab and slash defence above crush, and deals 20%
 * or 40% more to a target filling two or three of its tiles. Zorya's flow is
 * +150% accuracy and +60% damage.
 */
public class FracturedArchiveTest
{
	private static final double EXACT = 1e-9;

	/** The sum over k of P(best >= k), walked by brute force over every outcome. */
	private static double bruteBestOf(int max, int rolls, int cap)
	{
		final int outcomes = (int) Math.pow(max + 1, rolls);
		double total = 0;
		for (int i = 0; i < outcomes; i++)
		{
			int best = 0;
			int rest = i;
			for (int r = 0; r < rolls; r++)
			{
				best = Math.max(best, rest % (max + 1));
				rest /= max + 1;
			}
			total += Math.min(best, cap);
		}
		return total / outcomes;
	}

	@Test
	public void oneRollIsTheOrdinaryAverage()
	{
		for (int max = 1; max <= 60; max++)
		{
			assertEquals("max " + max, CombatCalc.cappedAverage(max, Integer.MAX_VALUE),
				CombatCalc.bestOfAverage(max, 1, Integer.MAX_VALUE), EXACT);
			assertEquals("max " + max + " capped", CombatCalc.cappedAverage(max, 10),
				CombatCalc.bestOfAverage(max, 1, 10), EXACT);
		}
	}

	@Test
	public void theBestOfSeveralMatchesEveryOutcomeCounted()
	{
		for (int rolls = 2; rolls <= 4; rolls++)
		{
			for (int max : new int[]{1, 7, 20})
			{
				assertEquals(rolls + " rolls, max " + max, bruteBestOf(max, rolls, Integer.MAX_VALUE),
					CombatCalc.bestOfAverage(max, rolls, Integer.MAX_VALUE), EXACT);
				assertEquals(rolls + " rolls, max " + max + " capped", bruteBestOf(max, rolls, 5),
					CombatCalc.bestOfAverage(max, rolls, 5), EXACT);
			}
		}
	}

	@Test
	public void moreRollsNeverExpectLess()
	{
		for (int rolls = 1; rolls < 4; rolls++)
		{
			assertTrue(CombatCalc.bestOfAverage(50, rolls + 1, Integer.MAX_VALUE)
				> CombatCalc.bestOfAverage(50, rolls, Integer.MAX_VALUE));
		}
		// Two rolls from 0-1 keep a 1 three times in four.
		assertEquals(0.75, CombatCalc.bestOfAverage(1, 2, Integer.MAX_VALUE), EXACT);
	}

	@Test
	public void rollsCountStabAndSlashAboveCrush()
	{
		// Graardor: equally weak to all three, so the minimum of two.
		assertEquals(2, CombatCalc.obligatorRolls(90, 90, 90));
		// The wiki's Phosani's Nightmare: stab and slash both above crush.
		assertEquals(4, CombatCalc.obligatorRolls(150, 150, 40));
		assertEquals(3, CombatCalc.obligatorRolls(150, 20, 40));
		// Equal is not higher.
		assertEquals(3, CombatCalc.obligatorRolls(40, 41, 40));
	}

	@Test
	public void largeTargetsTakeMore()
	{
		assertEquals(1.0, CombatCalc.obligatorSizeMultiplier(1), EXACT);
		assertEquals(1.2, CombatCalc.obligatorSizeMultiplier(2), EXACT);
		assertEquals(1.4, CombatCalc.obligatorSizeMultiplier(3), EXACT);
		assertEquals(1.4, CombatCalc.obligatorSizeMultiplier(5), EXACT);
	}

	@Test
	public void zoryasFlowIsTwoAndAHalfTimesTheRoll()
	{
		final SpecialAttack flow = SpecialAttack.forItem(ItemID.ZORYAS_TOME);
		assertNotNull(flow);
		assertEquals(25000, flow.scaleAttackRoll(10000));
		assertEquals(25002, flow.scaleAttackRoll(10001));
		assertEquals(1.6, flow.hitMaxima(50)[0] / 50.0, 0.001);
	}

	@Test
	public void theNewCategoriesAreFoundByTheirCombatTabName()
	{
		assertEquals(WeaponCategory.EXTENDING_CHAIN_MACE, WeaponCategory.forName("Category: Extending Chain Mace"));
		assertEquals(WeaponCategory.POWERED_BOOK, WeaponCategory.forName("Category: Powered Book"));
		assertEquals("Smash", WeaponCategory.EXTENDING_CHAIN_MACE.styleFor(2).getDisplayName());
		assertEquals("Smash", WeaponCategory.EXTENDING_CHAIN_MACE.styleFor(3).getDisplayName());
		assertEquals("Thrust", WeaponCategory.EXTENDING_CHAIN_MACE.styleFor(0).getDisplayName());
	}
}
