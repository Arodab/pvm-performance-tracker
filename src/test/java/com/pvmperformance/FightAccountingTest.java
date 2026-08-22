package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Tick accounting and what a room does with the fights under it. The branches
 * here decide whether a player is told they wasted time, so being wrong is
 * worse than being silent.
 */
public class FightAccountingTest
{
	private static Fight fight(int npcId)
	{
		return new Fight("target", npcId, 1, 100, 0L, null, 0);
	}

	private static Fight fought(int npcId, int damage)
	{
		final Fight f = fight(npcId);
		f.recordAttackMade(true);
		f.recordAttackResolved(true, true, 10, 10);
		f.recordDamageDealt(damage, 0L, damage > 0);
		return f;
	}

	@Test
	public void nothingIsCountedBeforeTheFirstAttack()
	{
		// Walking into range is not wasted combat time.
		final Fight f = fight(NpcIds.GOBLIN);
		f.recordTickLost(false);
		f.recordTickSpent();
		assertEquals(0, f.getTicksLost());
		assertEquals(0, f.getCombatTicks());
		assertEquals(-1, f.ticksLostShare(), 0.0001);
	}

	@Test
	public void aTickOnCooldownIsSpentAndNotLost()
	{
		final Fight f = fought(NpcIds.GOBLIN, 5);
		f.recordTickSpent();
		f.recordTickSpent();
		assertEquals(0, f.getTicksLost());
		assertEquals(3, f.getCombatTicks());
		assertEquals(0.0, f.ticksLostShare(), 0.0001);
	}

	@Test
	public void eatingIsSeparatedFromIdling()
	{
		final Fight f = fought(NpcIds.GOBLIN, 5);
		f.recordTickLost(true);
		f.recordTickLost(false);
		assertEquals(1, f.getTicksLostEating());
		assertEquals(2, f.getTicksLost());
		assertEquals(2.0 / 3.0, f.ticksLostShare(), 0.0001);
	}

	@Test
	public void aRespawnIsTimedOnlyWhenItWasWatched()
	{
		final Fight untimed = fight(NpcIds.GOBLIN);
		assertEquals(0, untimed.recordEngaged(50));
		assertEquals(0, untimed.getTicksToEngage());

		final Fight timed = fight(NpcIds.GOBLIN);
		timed.setEngageFromTick(100);
		assertEquals(7, timed.recordEngaged(107));
		assertEquals(7, timed.getTicksToEngage());
	}

	@Test
	public void aWaitPastAMinuteIsNotAboutTheFight()
	{
		final Fight f = fight(NpcIds.GOBLIN);
		f.setEngageFromTick(100);
		// The player went to bank; timing it says nothing about the kill.
		assertEquals(0, f.recordEngaged(1000));
		assertEquals(0, f.getTicksToEngage());
	}

	@Test
	public void anUnscoredFightLendsItsTimeButNotItsDamage()
	{
		final Encounter room = new Encounter("Kephri", null, 0L);
		final Fight boss = fought(NpcIds.KEPHRI, 40);
		final Fight scarab = fought(NpcIds.KEPHRI_SCARAB, 10);
		boss.recordTickLost(false);
		scarab.recordTickLost(false);
		room.add(boss);
		room.add(scarab);

		assertTrue("the scarab must not be scored", scarab.isScored() == false);
		// Damage, hits and attempts come from the boss alone.
		assertEquals(40, room.getDamageDealt());
		assertEquals(1, room.getAttempts());
		assertEquals(1, room.getHits());
		// Ticks come from both, so the time spent below still shows.
		assertEquals(4, room.getCombatTicks());
		assertEquals(2, room.getTicksLost());
	}

	@Test
	public void aRoomIsTheSumOfItsPhases()
	{
		final Encounter room = new Encounter("Wardens", null, 0L);
		room.add(fought(NpcIds.GOBLIN, 30));
		room.add(fought(NpcIds.GOBLIN, 20));
		assertEquals(50, room.getDamageDealt());
		assertEquals(2, room.getAttempts());
		assertEquals(25.0, room.averageHit(), 0.0001);
		assertEquals(1.0, room.accuracy(), 0.0001);
		// Both attacks were prayed and potted, so nothing was given away.
		assertEquals(1.0, room.efficiency(), 0.0001);
	}

	@Test
	public void aMissDragsTheRoomsAccuracyDownButNotItsAttempts()
	{
		final Encounter room = new Encounter("Zebak", null, 0L);
		room.add(fought(NpcIds.GOBLIN, 12));
		room.add(fought(NpcIds.GOBLIN, 0));
		assertEquals(2, room.getAttempts());
		assertEquals(1, room.getHits());
		assertEquals(0.5, room.accuracy(), 0.0001);
		assertEquals(6.0, room.averageHit(), 0.0001);
	}

	@Test
	public void aRoomOnlyCountsAsKilledIfSomethingDied()
	{
		final Encounter room = new Encounter("Akkha", null, 0L);
		final Fight f = fought(NpcIds.GOBLIN, 5);
		room.add(f);
		assertTrue(!room.isKilled());
		f.end(true, 10L);
		assertTrue(room.isKilled());
	}

	@Test
	public void aFightTakesItsGroupAndScoringFromTheTables()
	{
		assertEquals("Kephri", fight(NpcIds.KEPHRI_SCARAB).encounterName());
		assertEquals("target", fight(NpcIds.GOBLIN).encounterName());
		// A label set for a phase wins over the group it would otherwise take.
		final Fight labelled = fight(NpcIds.OLM_LEFT);
		labelled.setEncounterLabel("Great Olm (hands) - acid");
		assertEquals("Great Olm (hands) - acid", labelled.encounterName());
	}
}
