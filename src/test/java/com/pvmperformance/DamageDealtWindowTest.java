package com.pvmperformance;

import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * A hitpoints experience drop lands on the tick the attack goes out, but only an
 * attack booked from its animation knows that tick exactly. The other two work
 * back from a lag, and both lags were seen a tick out in one log: a cast booked
 * from its hitsplat worked back to 72 for an attack thrown on 71, and a
 * projectile at a flat two ticks worked back to 129 for a drop on 128.
 */
public class DamageDealtWindowTest
{
	private static boolean dealt(Set<Integer> drops, int attackTick)
	{
		return drops.contains(attackTick)
			|| drops.contains(attackTick - 1)
			|| drops.contains(attackTick + 1);
	}

	private static Set<Integer> drops(int... ticks)
	{
		final Set<Integer> set = new HashSet<>();
		for (int tick : ticks)
		{
			set.add(tick);
		}
		return set;
	}

	@Test
	public void anExactTickIsTheOrdinaryCase()
	{
		assertTrue(dealt(drops(56, 61, 66, 71), 61));
	}

	@Test
	public void aCastBookedFromItsHitsplatLandsATickLate()
	{
		// Thrown on 71, worked back to 72 from an estimated hit delay.
		assertTrue(dealt(drops(71), 72));
	}

	@Test
	public void aProjectileLandsATickEarly()
	{
		// Drop on 128, worked back to 129 from the flat projectile lag.
		assertTrue(dealt(drops(128), 129));
	}

	@Test
	public void anAttackThatDealtNothingIsStillAMiss()
	{
		// The window must not turn every attack into a hit: casts every five
		// ticks, one of them splashing, and the splash has to survive it.
		assertFalse(dealt(drops(56, 61, 66), 71));
	}

	@Test
	public void theWindowCannotReachTheNextAttacksDrop()
	{
		// Nothing swings faster than two ticks, so a neighbour's drop is always
		// at least two away.
		assertFalse(dealt(drops(60), 58));
	}
}
