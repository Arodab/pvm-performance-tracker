package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// Expected dps exists to answer the one question the average hit cannot: which
// of two setups is better when they swing at different speeds. It is built from
// the weapon's own cooldown rather than from a clock, so it owes nothing to the
// fight timer.
public class ExpectedDpsTest
{
	private static final double EPSILON = 1e-9;

	/** A tick is 0.6 seconds, so a 4 tick weapon attacks every 2.4. */
	@Test
	public void itIsTheAverageHitOverTheWeaponsCooldown()
	{
		assertEquals(10.0 / 2.4, CombatCalc.dpsFrom(10.0, 4), EPSILON);
		assertEquals(10.0 / 1.8, CombatCalc.dpsFrom(10.0, 3), EPSILON);
		// A blowpipe on rapid: every two ticks, so 1.2 seconds.
		assertEquals(5.0 / 1.2, CombatCalc.dpsFrom(5.0, 2), EPSILON);
	}

	/**
	 * The case the line is on the overlay for. A scythe hits far harder per swing
	 * than a blowpipe and still loses on sustained damage, which is invisible if
	 * you only compare average hits.
	 */
	@Test
	public void aBiggerAverageHitOnASlowerWeaponCanBeWorse()
	{
		final double slowHardHitter = CombatCalc.dpsFrom(12.0, 5);
		final double fastLightHitter = CombatCalc.dpsFrom(6.0, 2);
		assertTrue(fastLightHitter > slowHardHitter);
		// And the same weapon sped up is strictly better, which is the sanity
		// check that the speed is dividing rather than multiplying.
		assertTrue(CombatCalc.dpsFrom(10.0, 3) > CombatCalc.dpsFrom(10.0, 4));
	}

	/** No figure in means no figure out; -1 is how the overlay knows to hide it. */
	@Test
	public void noAverageHitMeansNoDps()
	{
		assertEquals(-1, CombatCalc.dpsFrom(-1, 4), EPSILON);
		assertEquals(-1, CombatCalc.dpsFrom(10.0, 0), EPSILON);
		assertEquals(-1, CombatCalc.dpsFrom(10.0, -2), EPSILON);
	}

	/** A zero average hit is a real answer, not a missing one. */
	@Test
	public void aZeroAverageHitIsZeroDpsRatherThanNoFigure()
	{
		assertEquals(0.0, CombatCalc.dpsFrom(0.0, 4), EPSILON);
	}
}
