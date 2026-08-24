package com.pvmperformance;

import net.runelite.api.gameval.NpcID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Hespori's flowers are part of its fight and must not be scored: they die to
 * one hit whatever is swung at them, so scoring them marks a player down for
 * doing what the fight asks. The ticks spent on them still count.
 */
public class HesporiGroupTest
{
	@Test
	public void theFlowersBelongToHesporisFight()
	{
		assertTrue(EncounterGroup.sameGroup(NpcID.HESPORI, NpcID.HESPORI_HEALER_ACTIVE));
		assertTrue(EncounterGroup.sameGroup(NpcID.HESPORI, NpcID.HESPORI_HEALER_INACTIVE));
	}

	@Test
	public void theFlowersAreNotScored()
	{
		assertTrue(EncounterGroup.isUnscored(NpcID.HESPORI_HEALER_ACTIVE));
		assertTrue(EncounterGroup.isUnscored(NpcID.HESPORI_HEALER_INACTIVE));
	}

	@Test
	public void hesporiItselfIsScored()
	{
		assertFalse(EncounterGroup.isUnscored(NpcID.HESPORI));
	}

	@Test
	public void theQuestVersionIsCoveredToo()
	{
		assertTrue(EncounterGroup.sameGroup(NpcID.TOBQUEST_HESPORI,
			NpcID.TOBQUEST_HESPORI_HEALER_ACTIVE));
		assertTrue(EncounterGroup.isUnscored(NpcID.TOBQUEST_HESPORI_HEALER_ACTIVE));
	}

	@Test
	public void theFarmingGuildAndTheQuestAreNotOneFight()
	{
		assertFalse(EncounterGroup.sameGroup(NpcID.HESPORI, NpcID.TOBQUEST_HESPORI));
	}
}
