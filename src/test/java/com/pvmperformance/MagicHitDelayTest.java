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
	public void theWikisWorkedExamples()
	{
		assertEquals(3, PvmPerformancePlugin.magicHitDelay(5));
		assertEquals(4, PvmPerformancePlugin.magicHitDelay(10));
	}

	@Test
	public void theCloseCastsFromTheTrace()
	{
		// Casts on 366 and 371 landed on 369 and 374: three ticks.
		assertEquals(3, PvmPerformancePlugin.magicHitDelay(6));
		assertEquals(3, PvmPerformancePlugin.magicHitDelay(7));
	}

	@Test
	public void theDistantCastsFromTheTrace()
	{
		// Casts on 408, 413, 418 and 428 landed on 413, 418, 423 and 433: five.
		assertEquals(5, PvmPerformancePlugin.magicHitDelay(11));
		assertEquals(5, PvmPerformancePlugin.magicHitDelay(13));
	}

	@Test
	public void adjacentIsTheShortestDelay()
	{
		assertEquals(1, PvmPerformancePlugin.magicHitDelay(0));
		assertEquals(1, PvmPerformancePlugin.magicHitDelay(1));
	}

	@Test
	public void aCloseCastResolvesWellBeforeTheNextOne()
	{
		// The point of the whole change: at three ticks the verdict lands two
		// ticks before a five tick weapon swings again, so the armed accuracy
		// is readable rather than arriving with the next attack.
		assertTrue(PvmPerformancePlugin.magicHitDelay(6) < 5);
	}

	@Test
	public void aNegativeDistanceCannotShortenIt()
	{
		assertEquals(1, PvmPerformancePlugin.magicHitDelay(-3));
	}
}
