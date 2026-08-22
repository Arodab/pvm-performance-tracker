package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.junit.Test;

// Osmumten's fang rolls between 15% and 85% of the true max hit, and the
// top of that range is the max less the raised minimum, not 85% of the max.
public class FangMaxHitTest
{
	/** The two figures a real loadout was checked against in game. */
	@Test
	public void matchesTheFiguresObservedInGame()
	{
		// Stab, and the same setup on lunge, whose +3 strength lifts the true max.
		assertEquals(24, CombatCalc.fangMaxHit(28));
		assertEquals(25, CombatCalc.fangMaxHit(29));
	}

	/** The wiki's own worked example, which is the case where the two agree. */
	@Test
	public void matchesTheWikiExample()
	{
		// "if the fang's true max hit was 60, it would roll between 9 and 51"
		assertEquals(9, 60 * 15 / 100);
		assertEquals(51, CombatCalc.fangMaxHit(60));
	}

	/**
	 * The bug that was fixed, stated directly: scaling by 0.85 is a different
	 * function, and agrees only where 15% of the max happens to be whole.
	 */
	@Test
	public void differsFromScalingByPointEightFive()
	{
		assertNotEquals((int) (28 * 0.85), CombatCalc.fangMaxHit(28));
		int disagreements = 0;
		for (int max = 1; max <= 120; max++)
		{
			if ((int) (max * 0.85) != CombatCalc.fangMaxHit(max))
			{
				disagreements++;
			}
		}
		// Only the multiples of 20 agree, so the wrong figure is the common case.
		assertEquals(114, disagreements);
	}

	/** The narrowing is symmetric, which is why no average is adjusted for it. */
	@Test
	public void leavesTheAverageOnHalfTheTrueMax()
	{
		for (int max = 1; max <= 120; max++)
		{
			final int min = max * 15 / 100;
			assertEquals(max, min + CombatCalc.fangMaxHit(max));
		}
	}
}
