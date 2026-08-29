package com.pvmperformance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// A projectile is only mine if my weapon was off cooldown when it set off. The
// speed that check uses is the trap: read live it is the wrong weapon's, or the
// default, and a wrongly rejected attack vanishes from the expected side while
// its hitsplat still counts damage.
public class ProjectileOwnershipTest
{
	/**
	 * The case that was wrong in game, lifted from the trace: a blowpipe firing
	 * every two ticks, last attack on 39, next on 41. Judged against the four
	 * ticks the live reading gave - the claws switched to, or the default from a
	 * weapon not readable mid-switch - it was rejected as too early to be mine.
	 */
	@Test
	public void theTracedBlowpipeShotIsMine()
	{
		assertFalse(PvmPerformancePlugin.tooSoonToBeMine(41, 39, 2));
		// What the live reading did, and why the hit went missing.
		assertTrue(PvmPerformancePlugin.tooSoonToBeMine(41, 39, 4));
		// The earlier occurrence, same shape.
		assertFalse(PvmPerformancePlugin.tooSoonToBeMine(33, 31, 2));
	}

	/** A shot that really is too soon is still refused. */
	@Test
	public void aShotInsideTheCooldownIsStillRefused()
	{
		assertTrue(PvmPerformancePlugin.tooSoonToBeMine(40, 39, 2));
		assertTrue(PvmPerformancePlugin.tooSoonToBeMine(39, 39, 2));
	}

	/** Exactly on cooldown is mine: the weapon is free on that tick. */
	@Test
	public void exactlyOnCooldownIsMine()
	{
		for (int speed = 1; speed <= 6; speed++)
		{
			assertFalse("speed " + speed,
				PvmPerformancePlugin.tooSoonToBeMine(100 + speed, 100, speed));
		}
	}

	/**
	 * Before any attack has been seen there is no cooldown to be inside, and the
	 * sentinel must not be added to - which would overflow and refuse everything.
	 * This is the first attack of a fight, which is half the reported symptom.
	 */
	@Test
	public void theFirstAttackEverIsMine()
	{
		assertFalse(PvmPerformancePlugin.tooSoonToBeMine(0, Integer.MIN_VALUE, 4));
		assertFalse(PvmPerformancePlugin.tooSoonToBeMine(41, Integer.MIN_VALUE, 4));
	}
}
