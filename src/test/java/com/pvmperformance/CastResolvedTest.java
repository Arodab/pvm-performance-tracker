package com.pvmperformance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * A cast with no projectile is called a miss when nothing says it landed, so
 * how long "nothing yet" lasts is the whole of it.
 *
 * <p>The gap between a cast and its damage is not a constant: it is three ticks
 * in one trace of blood barrage and five in another of the same spell at the
 * same boss, because a spell's damage is delayed by how far away the target is.
 * Judging at a fixed three armed the gauntlets on casts that landed two ticks
 * later, which showed up as the accuracy rising after a hit.
 */
public class CastResolvedTest
{
	private static final int BARRAGE_SPEED = 5;

	@Test
	public void theCastTickItselfIsTooEarly()
	{
		assertFalse(PvmPerformancePlugin.castResolved(21, 21, BARRAGE_SPEED));
	}

	@Test
	public void theOldThreeTickWindowIsTooEarlyForAFiveTickWeapon()
	{
		// The bug, pinned: casts on 408, 413, 418 and 428 landed on 413, 418,
		// 423 and 433. Judged three ticks out, every one of them read as a miss
		// before its damage arrived.
		assertFalse(PvmPerformancePlugin.castResolved(411, 408, BARRAGE_SPEED));
		assertFalse(PvmPerformancePlugin.castResolved(412, 408, BARRAGE_SPEED));
	}

	@Test
	public void theTickTheNextAttackGoesOutIsTheAnswer()
	{
		assertTrue(PvmPerformancePlugin.castResolved(413, 408, BARRAGE_SPEED));
	}

	@Test
	public void aLateReadStillResolves()
	{
		assertTrue(PvmPerformancePlugin.castResolved(420, 408, BARRAGE_SPEED));
	}

	@Test
	public void aFastWeaponStillWaitsForTheShortestSpellDelay()
	{
		// Floored, so a weapon quicker than any spell can land cannot judge a
		// cast before the damage could have arrived at all.
		assertFalse(PvmPerformancePlugin.castResolved(22, 21, 2));
		assertFalse(PvmPerformancePlugin.castResolved(23, 21, 2));
		assertTrue(PvmPerformancePlugin.castResolved(24, 21, 2));
	}
}
