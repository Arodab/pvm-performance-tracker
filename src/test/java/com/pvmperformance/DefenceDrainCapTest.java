package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The cap is the most that can be taken <em>off</em>, not the level draining
 * stops at. Getting that backwards would invert every entry silently, so it is
 * the first thing pinned down here.
 */
public class DefenceDrainCapTest
{
	@Test
	public void aCapIsAnAmountRemovedAndNotAFloor()
	{
		// The wiki's own example: a cap of 50 against 250 leaves 200.
		// Doom is the case in the table: 30 off a defence of 90 leaves 60.
		assertEquals(30, DefenceDrainCap.maxDrain(NpcIds.DOOM, 90));
		assertEquals(60, DefenceDrainCap.floor(NpcIds.DOOM, 90));
	}

	@Test
	public void anImmuneTargetLosesNothing()
	{
		assertTrue(DefenceDrainCap.isImmune(NpcIds.VERZIK_P1));
		assertEquals(0, DefenceDrainCap.maxDrain(NpcIds.VERZIK_P1, 20));
		assertEquals(20, DefenceDrainCap.floor(NpcIds.VERZIK_P1, 20));
	}

	@Test
	public void everythingElseDrainsToNothing()
	{
		assertFalse(DefenceDrainCap.isImmune(NpcIds.GOBLIN));
		assertEquals(150, DefenceDrainCap.maxDrain(NpcIds.GOBLIN, 150));
		assertEquals(0, DefenceDrainCap.floor(NpcIds.GOBLIN, 150));
	}

	@Test
	public void aCapNeverTakesMoreThanIsThere()
	{
		// A target whose defence is already below the cap cannot go negative.
		assertEquals(10, DefenceDrainCap.maxDrain(NpcIds.DOOM, 10));
		assertEquals(0, DefenceDrainCap.floor(NpcIds.DOOM, 10));
	}
}
