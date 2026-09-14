package com.pvmperformance;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * One throw is one attack however many it catches, and the accuracy divides by
 * what it reached rather than by what was thrown. Chinchompas into a pile of
 * adds is the case: five npcs, one throw, and three of them take damage is
 * three of five - not three of one, and not three attacks.
 */
public class AoeAttributionTest
{
	private static Fight fight()
	{
		return new Fight("target", NpcIds.GOBLIN, 1, 100, 0L, null, 0);
	}

	/** One throw at {@code caught} npcs, of which {@code landed} take damage. */
	private static Fight throwAt(int caught, int landed)
	{
		final Fight f = fight();
		f.recordAttackMade(false);
		// The one it was aimed at, which recordAttackMade already counted as a target.
		f.recordDamageDealt(landed > 0 ? 10 : 0, 0L, landed > 0);
		for (int extra = 1; extra < caught; extra++)
		{
			final boolean hit = extra < landed;
			f.recordExtraTarget(hit ? 10 : 0, 0L, true, hit);
		}
		return f;
	}

	@Test
	public void oneThrowIsOneAttackHoweverManyItCatches()
	{
		final Fight f = throwAt(5, 3);
		assertEquals("one throw", 1, f.getAttacksMade());
		assertEquals("one attempt", 1, f.getAttempts());
		assertEquals("five npcs reached", 5, f.resolvedTargets());
		assertEquals("three of them took damage", 3, f.getHits());
		assertEquals(0.6, f.accuracy(), 1e-9);
	}

	@Test
	public void everythingItCaughtLendsItsDamageToTheSameAttack()
	{
		final Fight f = throwAt(5, 3);
		assertEquals(30, f.getDamageDealt());
		// Damage divides by the THROW, not by the targets: this is what one attack is worth, which is what the expected
		// side is a sum over the same targets for.
		assertEquals(30.0, f.averageHit(), 1e-9);
	}

	@Test
	public void singleTargetCombatIsUnchanged()
	{
		// The whole point: with one target an attack, targets and attempts are the same number and every figure reads as
		// it always did.
		final Fight f = fight();
		f.recordAttackMade(false);
		f.recordDamageDealt(10, 0L, true);
		f.recordAttackMade(false);
		f.recordDamageDealt(0, 0L, false);
		assertEquals(2, f.getAttempts());
		assertEquals(2, f.resolvedTargets());
		assertEquals(1, f.getHits());
		assertEquals(0.5, f.accuracy(), 1e-9);
		assertEquals(5.0, f.averageHit(), 1e-9);
	}

	@Test
	public void aBurstOnOneNpcIsOneTarget()
	{
		// A scythe lands three splats on the same enemy and they are one blow, so the second and third bring damage and
		// nothing else - the same rule that already makes them one landed attack.
		final Fight f = fight();
		f.recordAttackMade(false);
		f.recordDamageDealt(10, 0L, true);
		f.recordExtraTarget(6, 0L, true, true);
		f.recordExtraTarget(3, 0L, false, false);
		f.recordExtraTarget(1, 0L, false, false);
		assertEquals(2, f.resolvedTargets());
		assertEquals(2, f.getHits());
		assertEquals(20, f.getDamageDealt());
	}

	@Test
	public void aFightFromAnOlderHistoryFileStillReadsItsAccuracy()
	{
		// Gson fills the fields it finds and leaves the rest at zero, so a kill saved before targets existed comes back
		// with none. Divided by that it would report 0% accuracy for every fight already on disk.
		final Fight old = fight();
		old.recordAttackMade(false);
		old.recordAttackMade(false);
		old.recordDamageDealt(10, 0L, true);
		final Gson gson = new Gson();
		final Fight loaded = gson.fromJson(
			gson.toJson(old).replaceAll("\"targets\":[0-9]+,?", ""), Fight.class);
		assertEquals(2, loaded.getAttempts());
		assertEquals(2, loaded.resolvedTargets());
		assertEquals(0.5, loaded.accuracy(), 1e-9);
	}

	@Test
	public void aRoomSumsTheTargetsItsFightsReached()
	{
		final Encounter room = new Encounter("Tightrope", null, 0L);
		room.add(throwAt(5, 3));
		room.add(throwAt(4, 1));
		assertEquals(2, room.getAttempts());
		assertEquals(9, room.getTargets());
		assertEquals(4, room.getHits());
		assertEquals(4.0 / 9.0, room.accuracy(), 1e-9);
		// And it can never read above 100%, which is what the old denominator did once hits outnumbered attacks.
		assertEquals(true, room.accuracy() <= 1.0);
	}
}
