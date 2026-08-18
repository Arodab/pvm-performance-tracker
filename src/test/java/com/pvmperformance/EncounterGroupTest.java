package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The tables, where the errors are transcription rather than arithmetic: an id
 * in the wrong room, or one that got a name-based guess instead of a checked
 * number.
 */
public class EncounterGroupTest
{
	@Test
	public void nylocasGroupByStyleAndNotBySize()
	{
		assertEquals("Nylocas (melee)", EncounterGroup.of(NpcIds.NYLO_MELEE_SMALL));
		assertEquals("Nylocas (melee)", EncounterGroup.of(NpcIds.NYLO_MELEE_BIG_FIGHTING));
		assertTrue(EncounterGroup.sameGroup(NpcIds.NYLO_MELEE_SMALL, NpcIds.NYLO_MELEE_BIG_FIGHTING));
		// Different colours are different jobs and must not merge.
		assertFalse(EncounterGroup.sameGroup(NpcIds.NYLO_MELEE_SMALL, NpcIds.NYLO_MAGIC_SMALL));
	}

	@Test
	public void aNylocasThatHasSettledStillBelongsToItsColour()
	{
		// The bug this is here to stop coming back: only the incoming forms were
		// listed, and a nylocas changes id when it reaches a pillar and turns to
		// fight, which is when most of them are killed.
		assertEquals(EncounterGroup.of(NpcIds.NYLO_MELEE_SMALL),
			EncounterGroup.of(NpcIds.NYLO_MELEE_BIG_FIGHTING));
	}

	@Test
	public void kephrisRoomIsHersAndHerScarabsDoNotScore()
	{
		assertEquals("Kephri", EncounterGroup.of(NpcIds.KEPHRI));
		assertEquals("Kephri", EncounterGroup.of(NpcIds.KEPHRI_SCARAB));
		// Grouped so the time counts, unscored so the guaranteed max hits do not.
		assertTrue(EncounterGroup.isUnscored(NpcIds.KEPHRI_SCARAB));
		assertFalse(EncounterGroup.isUnscored(NpcIds.KEPHRI));
	}

	@Test
	public void anUnscoredNpcIsAlwaysGroupedWithSomething()
	{
		// Unscored without a group would be worse than either: the time spent on
		// it would be booked against the boss as ticks lost.
		for (int npcId = 0; npcId < 30000; npcId++)
		{
			if (EncounterGroup.isUnscored(npcId))
			{
				assertNotNull("unscored npc " + npcId + " needs a group",
					EncounterGroup.of(npcId));
			}
		}
	}

	@Test
	public void zebaksJugsAreNotTrackedAtAll()
	{
		assertTrue(EncounterGroup.isIgnored(NpcIds.ZEBAK_JUG));
		assertNull(EncounterGroup.of(NpcIds.ZEBAK_JUG));
	}

	@Test
	public void aDormantTotemCannotBeFought()
	{
		assertTrue(EncounterGroup.isUnattackable(NpcIds.TOTEM_DORMANT));
		assertFalse(EncounterGroup.isUnattackable(NpcIds.TOTEM));
	}

	@Test
	public void bothNightmaresShareTheirTotemsButNotTheirBosses()
	{
		assertTrue(EncounterGroup.isNightmareTotem(NpcIds.TOTEM));
		// A totem names no boss; the boss in the room decides.
		assertNull(EncounterGroup.nightmareBossName(NpcIds.TOTEM));
		assertEquals("Phosani's Nightmare", EncounterGroup.nightmareBossName(NpcIds.PHOSANI));
		assertEquals("The Nightmare", EncounterGroup.nightmareBossName(NpcIds.NIGHTMARE));
	}

	@Test
	public void ordinaryMonstersAreNotInAnyTable()
	{
		assertNull(EncounterGroup.of(NpcIds.GOBLIN));
		assertFalse(EncounterGroup.isIgnored(NpcIds.GOBLIN));
		assertFalse(EncounterGroup.isUnscored(NpcIds.GOBLIN));
		assertFalse(EncounterGroup.isUnattackable(NpcIds.GOBLIN));
	}

	@Test
	public void olmsHeadIsNotGroupedWithItsHands()
	{
		assertFalse(EncounterGroup.sameGroup(NpcIds.OLM_HEAD, NpcIds.OLM_LEFT));
		assertTrue(EncounterGroup.sameGroup(NpcIds.OLM_LEFT, NpcIds.OLM_RIGHT));
	}
}
