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
	public void theKillingBlowsNeighbourDoesNotDragTheAverageDown()
	{
		// Two attacks, one landed for 10, the second nulled by the kill. Its
		// damage of nothing must not halve the average hit, because it never
		// had a target left to deal any.
		final Fight f = fight();
		f.recordAttackMade(false);
		f.recordAttackMade(false);
		f.recordDamageDealt(10, 0L, true);
		f.recordAttackNulled();
		assertEquals(1, f.resolvedAttempts());
		assertEquals(10.0, f.averageHit(), 1e-9);
	}

	@Test
	public void butItStillCountsAgainstTheAccuracy()
	{
		// Changed deliberately, on the owner's call. Nulling used to strike the
		// attack from the accuracy as well, which read as one attack that hit
		// for 10 - a flat 100%. On screen that left Hits at 13 of 17 while
		// prayed, potted and switches all read out of 18, and the odd one out
		// was the one nobody could reproduce by counting. An attack that was
		// thrown is now always in the denominator.
		final Fight f = fight();
		f.recordAttackMade(false);
		f.recordAttackMade(false);
		f.recordDamageDealt(10, 0L, true);
		f.recordAttackNulled();
		assertEquals(2, f.resolvedTargets());
		assertEquals(0.5, f.accuracy(), 1e-9);
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
	public void nullingAnOrdinaryMissReadsAsBetterThanPerfect()
	{
		// Why the plugin now nulls only when the target DIED. A room of one-attack fights, each a miss whose answer
		// arrived after its fight had ended: every attempt was erased while the hits from the fights that landed stayed,
		// and the room reported more hits than attempts. A real export reads 81 hits against 80 attempts this way, which
		// is an accuracy of 101.3% and cannot happen.
		final Encounter room = new Encounter("Tightrope", null, 0L);
		final Fight landed = fight();
		landed.recordAttackMade(false);
		landed.recordDamageDealt(10, 0L, true);
		room.add(landed);
		for (int miss = 0; miss < 3; miss++)
		{
			final Fight f = fight();
			f.recordAttackMade(false);
			f.recordAttackNulled();
			room.add(f);
		}
		// What it did: three misses erased, leaving one hit against one attempt.
		assertEquals(1, room.getAttempts());
		assertEquals(1, room.getHits());
		// What it must be once a miss keeps its attempt: one hit in four.
		final Encounter fixed = new Encounter("Tightrope", null, 0L);
		final Fight hit = fight();
		hit.recordAttackMade(false);
		hit.recordDamageDealt(10, 0L, true);
		fixed.add(hit);
		for (int miss = 0; miss < 3; miss++)
		{
			final Fight f = fight();
			f.recordAttackMade(false);
			fixed.add(f);
		}
		assertEquals(4, fixed.getAttempts());
		assertEquals(1, fixed.getHits());
		assertEquals(0.25, fixed.accuracy(), 1e-9);
	}

	@Test
	public void nothingGoesNegativeIfMoreAreNulledThanMade()
	{
		final Fight f = fight();
		f.recordAttackNulled();
		f.recordAttackNulled();
		assertEquals(0, f.resolvedAttempts());
		assertEquals(0.0, f.averageHit(), 1e-9);
	}
}
