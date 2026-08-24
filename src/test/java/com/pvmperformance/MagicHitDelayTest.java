package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * How long a cast with no projectile is given before nothing arriving is taken
 * as a miss.
 *
 * <p>Wiki (Hit delay): {@code MagicDelay = 1 + floor((1 + Distance) / 3)}, with
 * distance measured Chebyshev and, for these spells, from the player to the
 * NPC's south-west tile. Every earlier attempt used a constant here — three
 * ticks, then the weapon's speed — and the traces show why neither works.
 */
public class MagicHitDelayTest
{
	@Test
	public void theTwoCastsLoggedWithTheirDistances()
	{
		// Where the plus-one comes from. A cast queued at distance 2 was due on
		// 552 and its hitsplat arrived on 553; one at distance 6 was due on 454
		// and arrived on 455. Judged a tick early, both were called splashes,
		// and one of them had hit for 58.
		assertEquals(3, PvmPerformancePlugin.magicHitDelay(2));
		assertEquals(4, PvmPerformancePlugin.magicHitDelay(6));
	}

	@Test
	public void theDistantCastsFromTheEarlierTrace()
	{
		// Casts on 408, 413, 418 and 428 landed on 413, 418, 423 and 433: five.
		assertEquals(5, PvmPerformancePlugin.magicHitDelay(8));
		assertEquals(5, PvmPerformancePlugin.magicHitDelay(10));
	}

	@Test
	public void adjacentIsTheShortestDelay()
	{
		assertEquals(2, PvmPerformancePlugin.magicHitDelay(0));
		assertEquals(2, PvmPerformancePlugin.magicHitDelay(1));
	}

	@Test
	public void aCloseCastStillResolvesBeforeTheNextOne()
	{
		assertTrue(PvmPerformancePlugin.magicHitDelay(2) < 5);
	}

	@Test
	public void aNegativeDistanceCannotShortenIt()
	{
		assertEquals(2, PvmPerformancePlugin.magicHitDelay(-3));
	}
}
