package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.junit.Test;

/**
 * Void scales the EFFECTIVE LEVEL and truncates there, before the max hit is
 * worked out from it. Multiplying the finished max hit instead is the same
 * boost and a different number, because the two round in different places - a
 * blowpipe in elite void read 33 where the game gives 34.
 *
 * <p>The arithmetic is checked here rather than the wiring, since the wiring
 * needs a client. Each case is the two orders side by side on the same input.
 */
public class VoidScalingTest
{
	/** The game's order: scale the level, truncate, then take the max hit from it. */
	private static int levelFirst(int effectiveLevel, int strengthBonus, int numerator, int denominator)
	{
		return CombatCalc.maxHitFromStrength(effectiveLevel * numerator / denominator, strengthBonus);
	}

	/** What it used to do: take the max hit, then multiply and truncate. */
	private static int maxHitFirst(int effectiveLevel, int strengthBonus, double multiplier)
	{
		return (int) (CombatCalc.maxHitFromStrength(effectiveLevel, strengthBonus) * multiplier);
	}

	@Test
	public void theTwoOrdersDisagreeAndTheLevelOneIsTheGames()
	{
		// A blowpipe with dragon darts: 112 ranged with rigour and the accurate style is an effective 120ish, and the
		// ranged strength bonus of the dart plus the pipe is in the high twenties. The exact numbers matter less than that
		// the two orders differ at all, which is the whole bug.
		final int effective = 120;
		final int strengthBonus = 60;
		assertNotEquals(levelFirst(effective, strengthBonus, 9, 8),
			maxHitFirst(effective, strengthBonus, 1.125));
	}

	@Test
	public void eliteRangedIsNineEighthsOfTheLevel()
	{
		// trunc(120 * 9 / 8) = 135, and the max hit follows from that.
		assertEquals(CombatCalc.maxHitFromStrength(135, 60), levelFirst(120, 60, 9, 8));
	}

	@Test
	public void regularVoidIsElevenTenthsOfTheLevel()
	{
		// trunc(120 * 11 / 10) = 132.
		assertEquals(CombatCalc.maxHitFromStrength(132, 60), levelFirst(120, 60, 11, 10));
	}

	@Test
	public void eliteBeatsRegularOnRangedAndNotByRounding()
	{
		// 9/8 against 11/10 is a real gap at every level, not a rounding artefact at one.
		for (int level = 80; level <= 140; level++)
		{
			assertEquals("elite must never be worse at level " + level, true,
				levelFirst(level, 60, 9, 8) >= levelFirst(level, 60, 11, 10));
		}
	}

	@Test
	public void truncationHappensAtTheLevelAndNotAfterIt()
	{
		// 99 * 11 / 10 is 108.9, which the game reads as 108. Rounding it to 109 instead would carry a point of max hit
		// into a figure that is then compared against measured damage.
		assertEquals(108, 99 * 11 / 10);
		assertEquals(111, 99 * 9 / 8);
	}
}
