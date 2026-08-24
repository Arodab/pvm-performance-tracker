package com.pvmperformance;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * With the trip totals off the overlay shows the room, so a room that has been
 * opened but not fought in must not replace the one before it: a fight opens on
 * merely looking at an NPC, which after a kill is immediate, and the kill just
 * made vanished before it could be read.
 */
public class EncounterHasAttemptsTest
{
	private static Fight fight()
	{
		return new Fight("target", NpcIds.GOBLIN, 1, 100, 0L, null, 0);
	}

	@Test
	public void anEmptyRoomHasNothingInIt()
	{
		assertFalse(new Encounter("Goblin", null, 0L).hasAttempts());
	}

	@Test
	public void aRoomHoldingAFightNothingHappenedInStillHasNothingInIt()
	{
		final Encounter room = new Encounter("Goblin", null, 0L);
		room.add(fight());
		assertFalse(room.hasAttempts());
	}

	@Test
	public void oneAttackIsEnoughToMakeItTheRoomOnShow()
	{
		final Encounter room = new Encounter("Goblin", null, 0L);
		final Fight f = fight();
		f.recordAttackMade(false);
		room.add(f);
		assertTrue(room.hasAttempts());
	}

	@Test
	public void aLaterFightCountsAsMuchAsTheFirst()
	{
		final Encounter room = new Encounter("Goblin", null, 0L);
		room.add(fight());
		final Fight second = fight();
		second.recordAttackMade(false);
		room.add(second);
		assertTrue(room.hasAttempts());
	}
}
