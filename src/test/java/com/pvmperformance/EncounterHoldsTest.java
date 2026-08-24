package com.pvmperformance;

import net.runelite.api.gameval.NpcID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * A room is ended by the kill that closed it, and the kill arrives as a loot
 * drop from whichever part of the boss happened to be last. These cover the
 * question that asks: is this NPC what the room is about.
 */
public class EncounterHoldsTest
{
	private static Fight fight(int npcId)
	{
		return new Fight("target", npcId, 1, 100, 0L, null, 0);
	}

	@Test
	public void anEmptyRoomHoldsNothing()
	{
		assertFalse(new Encounter("The Hueycoatl", null, 0L).holds(NpcID.HUEY_HEAD));
	}

	@Test
	public void aKillOnAnotherPartOfTheSameBossIsThisRoomsKill()
	{
		// The fight is usually open on a body segment while the loot drops from
		// a head, which is the case the group lookup exists for.
		final Encounter room = new Encounter("The Hueycoatl", null, 0L);
		room.add(fight(NpcID.HUEY_BODY_PART));
		assertTrue(room.holds(NpcID.HUEY_HEAD));
	}

	@Test
	public void aKillOnTheTargetItselfIsThisRoomsKill()
	{
		final Encounter room = new Encounter("Goblin", null, 0L);
		room.add(fight(NpcIds.GOBLIN));
		assertTrue(room.holds(NpcIds.GOBLIN));
	}

	@Test
	public void somethingDyingBesideTheBossDoesNotCloseTheRoom()
	{
		final Encounter room = new Encounter("The Hueycoatl", null, 0L);
		room.add(fight(NpcID.HUEY_BODY_PART));
		assertFalse(room.holds(NpcIds.GOBLIN));
	}
}
