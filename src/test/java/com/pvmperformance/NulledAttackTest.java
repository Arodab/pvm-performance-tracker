package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * An attack in the air when the target dies is not a miss. It cost a tick and
 * was thrown with whatever was up, so it stays in attacksMade, but it has no
 * outcome to be right or wrong about — and left in the denominator it hands
 * back a perfect kill as a failure.
 */
public class NulledAttackTest
{
	private static Fight fight()
	{
		return new Fight("target", NpcIds.GOBLIN, 1, 100, 0L, null, 0);
	}

	@Test
	public void anOrdinaryFightIsUnaffected()
	{
		final Fight f = fight();
		f.recordAttackMade(false);
		f.recordAttackMade(false);
		f.recordDamageDealt(10, 0L, true);
		assertEquals(2, f.resolvedAttempts());
		assertEquals(0.5, f.accuracy(), 1e-9);
		assertEquals(5.0, f.averageHit(), 1e-9);
	}

	@Test
	public void theKillingBlowsNeighbourIsNotAMiss()
	{
		// Two attacks, one landed for 10, the second nulled by the kill. Counted
		// as a miss that is 50% accuracy and 5 average; it should read as one
		// attack that hit for 10.
		final Fight f = fight();
		f.recordAttackMade(false);
		f.recordAttackMade(false);
		f.recordDamageDealt(10, 0L, true);
		f.recordAttackNulled();
		assertEquals(1, f.resolvedAttempts());
		assertEquals(1.0, f.accuracy(), 1e-9);
		assertEquals(10.0, f.averageHit(), 1e-9);
	}

	@Test
	public void theAttackItselfStillHappened()
	{
		// It cost a tick and was thrown with whatever prayer and gear were up,
		// so the efficiency side must still see it.
		final Fight f = fight();
		f.recordAttackMade(false);
		f.recordAttackNulled();
		assertEquals(1, f.getAttacksMade());
		assertEquals(0, f.resolvedAttempts());
	}

	@Test
	public void nothingGoesNegativeIfMoreAreNulledThanMade()
	{
		final Fight f = fight();
		f.recordAttackNulled();
		f.recordAttackNulled();
		assertEquals(0, f.resolvedAttempts());
		assertEquals(0.0, f.accuracy(), 1e-9);
	}
}
