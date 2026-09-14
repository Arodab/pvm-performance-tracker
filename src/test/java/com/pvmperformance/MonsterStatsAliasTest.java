package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * The stand-in ids, which are transcription and nothing else. Each one says
 * "the wiki data has no entry for the form this monster is FOUGHT in, so read
 * the form it is listed under instead", and getting one wrong is silent: the
 * figures still appear, they are just another monster's.
 *
 * <p>Every mapping here was checked against the entry it points at before it was
 * written down. What the tests can still catch is a later edit pointing one
 * somewhere else, or an alias that quietly points at another alias.
 */
public class MonsterStatsAliasTest
{
	private static final int[] ALIASED =
	{
		NpcID.OLM_HAND_RIGHT, NpcID.OLM_HAND_RIGHT_DYING, NpcID.OLM_HAND_LEFT, NpcID.OLM_HAND_LEFT_DYING,
		NpcID.OLM_HEAD, NpcID.RAIDS_STONEGUARDIANS_RIGHT, NpcID.RAIDS_STONEGUARDIANS_LEFT_DEAD,
		NpcID.RAIDS_STONEGUARDIANS_RIGHT_DEAD, NpcID.VERZIK_PHASE1, NpcID.VERZIK_PHASE1_HARD,
		NpcID.VERZIK_PHASE1_STORY, NpcID.TOB_XARPUS_STATIC, NpcID.TOB_XARPUS_FEEDING,
		NpcID.TOB_XARPUS_STATIC_HARD, NpcID.TOB_XARPUS_FEEDING_HARD, NpcID.TOB_XARPUS_STATIC_STORY,
		NpcID.TOB_XARPUS_FEEDING_STORY, NpcID.RAIDS_TEKTON_WALKING_STANDARD, NpcID.RAIDS_TEKTON_FIGHTING_STANDARD,
		NpcID.RAIDS_TEKTON_FIGHTING_ENRAGED, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE,
		NpcID.TOA_WARDEN_TUMEKEN_PHASE2_RANGE, NpcID.TOB_NYLOCAS_FIGHTING_MELEE, NpcID.TOB_NYLOCAS_FIGHTING_RANGED,
		NpcID.TOB_NYLOCAS_FIGHTING_MAGIC, NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE,
		NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED, NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC,
		NpcID.TOB_MAIDEN_70_HARD, NpcID.TOB_MAIDEN_30_STORY,
	};

	@Test
	public void theOlmIsReadFromTheFormHeRisesIn()
	{
		// The three parts disagree on every defence they have, so each points at its OWN entry and never at a sibling.
		assertEquals(NpcID.OLM_HAND_LEFT_SPAWNING, MonsterStatsProvider.aliasFor(NpcID.OLM_HAND_LEFT));
		assertEquals(NpcID.OLM_HAND_RIGHT_SPAWNING, MonsterStatsProvider.aliasFor(NpcID.OLM_HAND_RIGHT));
		assertEquals(NpcID.OLM_HEAD_SPAWNING, MonsterStatsProvider.aliasFor(NpcID.OLM_HEAD));
		assertNotEquals(MonsterStatsProvider.aliasFor(NpcID.OLM_HAND_LEFT),
			MonsterStatsProvider.aliasFor(NpcID.OLM_HAND_RIGHT));
	}

	@Test
	public void verziksFirstPhaseIsNotHerThrone()
	{
		// The data's "Phase 1" is the id she sits in before the fight; the fought form is the next one along. Her phases
		// differ by an order of magnitude in defence, so this is the mapping least survivable if it drifts.
		assertEquals(NpcID.VERZIK_INITIAL, MonsterStatsProvider.aliasFor(NpcID.VERZIK_PHASE1));
		assertEquals(NpcID.VERZIK_INITIAL_HARD, MonsterStatsProvider.aliasFor(NpcID.VERZIK_PHASE1_HARD));
		assertEquals(NpcID.VERZIK_INITIAL_STORY, MonsterStatsProvider.aliasFor(NpcID.VERZIK_PHASE1_STORY));
	}

	@Test
	public void tektonKeepsHisEnragedBonusesApartFromHisStandardOnes()
	{
		assertEquals(NpcID.RAIDS_TEKTON_WAITING, MonsterStatsProvider.aliasFor(NpcID.RAIDS_TEKTON_FIGHTING_STANDARD));
		assertEquals(NpcID.RAIDS_TEKTON_WALKING_ENRAGED,
			MonsterStatsProvider.aliasFor(NpcID.RAIDS_TEKTON_FIGHTING_ENRAGED));
		assertNotEquals(MonsterStatsProvider.aliasFor(NpcID.RAIDS_TEKTON_FIGHTING_STANDARD),
			MonsterStatsProvider.aliasFor(NpcID.RAIDS_TEKTON_FIGHTING_ENRAGED));
	}

	@Test
	public void aNylocasKeepsItsColourAndItsSize()
	{
		// Colour decides which defences apply and size decides how many times a scythe hits, so a settled nylocas must
		// point at its own colour AND its own size, never at the other one.
		assertEquals(NpcID.TOB_NYLOCAS_INCOMING_MELEE, MonsterStatsProvider.aliasFor(NpcID.TOB_NYLOCAS_FIGHTING_MELEE));
		assertEquals(NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE,
			MonsterStatsProvider.aliasFor(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE));
		assertEquals(NpcID.TOB_NYLOCAS_INCOMING_MAGIC, MonsterStatsProvider.aliasFor(NpcID.TOB_NYLOCAS_FIGHTING_MAGIC));
		assertNotEquals(MonsterStatsProvider.aliasFor(NpcID.TOB_NYLOCAS_FIGHTING_MELEE),
			MonsterStatsProvider.aliasFor(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE));
	}

	@Test
	public void bothGuardiansReadFromTheOneThatIsListed()
	{
		assertEquals(NpcID.RAIDS_STONEGUARDIANS_LEFT,
			MonsterStatsProvider.aliasFor(NpcID.RAIDS_STONEGUARDIANS_RIGHT));
		// The one that IS listed must not be aliased: it would send the lookup somewhere it never needed to go.
		assertEquals(-1, MonsterStatsProvider.aliasFor(NpcID.RAIDS_STONEGUARDIANS_LEFT));
	}

	@Test
	public void nothingUnlistedIsAliased()
	{
		assertEquals(-1, MonsterStatsProvider.aliasFor(NpcIds.GOBLIN));
		assertEquals(-1, MonsterStatsProvider.aliasFor(-1));
	}

	@Test
	public void noAliasPointsAtAnotherAlias()
	{
		// A chain would resolve to nothing: the lookup follows exactly one hop by design.
		for (int npcId : ALIASED)
		{
			final int target = MonsterStatsProvider.aliasFor(npcId);
			assertTrue("id " + npcId + " has no alias", target > 0);
			assertEquals("alias for " + npcId + " points at another alias", -1,
				MonsterStatsProvider.aliasFor(target));
		}
	}
}
