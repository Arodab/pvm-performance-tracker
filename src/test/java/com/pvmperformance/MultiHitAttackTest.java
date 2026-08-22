package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

// A special that lands several hitsplats is one attack, not several.
public class MultiHitAttackTest
{
	private static Fight fight()
	{
		return new Fight("Goblin", NpcIds.GOBLIN, 1, 100, 0L, null, 0);
	}

	/** One attack made, four hitsplats: one attempt, one landed hit. */
	@Test
	public void aClawSpecialIsOneAttack()
	{
		final Fight f = fight();
		f.recordAttackMade(true);
		f.recordAttackResolved(true, true, 10, 10);
		f.recordDamageDealt(20, 0L, true);
		f.recordDamageDealt(10, 0L, false);
		f.recordDamageDealt(5, 0L, false);
		f.recordDamageDealt(5, 0L, false);

		assertEquals(1, f.getAttempts());
		assertEquals(1, f.getHits());
		assertEquals(40, f.getDamageDealt());
		assertEquals(1.0, f.accuracy(), 0.0001);
		// The average is per attack, so the whole special is one 40 damage hit.
		assertEquals(40.0, f.averageHit(), 0.0001);
	}

	/**
	 * A claw special that opens with a zero and then connects still landed. The
	 * first hitsplat to deal damage is the one that credits the hit, not simply
	 * the first hitsplat of the burst.
	 */
	@Test
	public void aSpecialThatOpensWithAZeroStillLands()
	{
		final Fight f = fight();
		f.recordAttackMade(true);
		f.recordAttackResolved(true, true, 10, 10);
		f.recordDamageDealt(0, 0L, false);
		f.recordDamageDealt(12, 0L, true);
		f.recordDamageDealt(6, 0L, false);

		assertEquals(1, f.getAttempts());
		assertEquals(1, f.getHits());
		assertEquals(18, f.getDamageDealt());
	}

	/** A special that misses entirely is an attempt with no hit. */
	@Test
	public void aSpecialThatMissesEntirelyIsStillOneAttempt()
	{
		final Fight f = fight();
		f.recordAttackMade(true);
		f.recordAttackResolved(true, true, 10, 10);
		f.recordDamageDealt(0, 0L, false);
		f.recordDamageDealt(0, 0L, false);

		assertEquals(1, f.getAttempts());
		assertEquals(0, f.getHits());
		assertEquals(0.0, f.accuracy(), 0.0001);
	}

	/** A splash is an attack that landed nothing, and must not double count. */
	@Test
	public void aSplashIsTheAttackAlreadyCounted()
	{
		final Fight f = fight();
		f.recordAttackMade(true);
		f.recordAttackResolved(true, true, 10, 10);
		f.recordSplash(0L);

		assertEquals(1, f.getAttempts());
		assertEquals(0, f.getHits());
	}

	/**
	 * The whole point of the change: attempts now equal attacks made, so the
	 * measured and expected sides share a denominator.
	 */
	@Test
	public void attemptsTrackAttacksMade()
	{
		final Fight f = fight();
		for (int attack = 0; attack < 5; attack++)
		{
			f.recordAttackMade(true);
		f.recordAttackResolved(true, true, 10, 10);
			f.recordDamageDealt(7, 0L, true);
			f.recordDamageDealt(3, 0L, false); // a second hitsplat every time
		}
		assertEquals(5, f.getAttacksMade());
		assertEquals(5, f.getAttempts());
		assertEquals(5, f.getHits());
	}
}
