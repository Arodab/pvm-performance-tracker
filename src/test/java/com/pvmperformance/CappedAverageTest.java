package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * A damage cap is not a smaller max hit, and the difference is the whole reason
 * this is its own method. Every roll above the cap collapses onto it, so the
 * average sits near the cap rather than near half of it — treating the
 * Hueycoatl's tail as a nine max hit would understate the damage by nearly half.
 */
public class CappedAverageTest
{
	private static final double EXACT = 1e-9;

	@Test
	public void theCapIsWhatIsLEFTAndNotAnAverageOfIt()
	{
		// The point the cap is often misread on. A 60 max against 10 remaining does not expect 10, and does not expect the
		// uncapped 30: the rolls under 10 still land where they fall and everything from 10 up becomes a 10.
		//   rolls 0..9   -> 0..9      sum 45
		//   rolls 10..60 -> 10 each   sum 510
		assertEquals(555.0 / 61.0, CombatCalc.cappedAverage(60, 10), EXACT);
		assertEquals(9.10, CombatCalc.cappedAverage(60, 10), 0.01);
		// And it can never exceed what is left, whatever the max hit is.
		for (int max = 10; max <= 120; max += 10)
		{
			assertEquals("a cap of 10 must hold at max " + max, true, CombatCalc.cappedAverage(max, 10) <= 10.0);
		}
	}

	@Test
	public void overkillIsTheSameArithmeticAsADamageCap()
	{
		// What a killing blow is worth. An attack with a 60 max against a target on 3 hitpoints cannot deal more than 3,
		// so the expectation is the capped average and not half of 60 - the difference is what was being counted as damage
		// the setup failed to produce, once per kill.
		assertEquals(30.0, CombatCalc.cappedAverage(60, 60), EXACT);
		// Rolls 0..3 keep their value, 4..60 all land on 3: (6 + 57*3) / 61.
		assertEquals(177.0 / 61.0, CombatCalc.cappedAverage(60, 3), EXACT);
		// Just under 3, where the uncapped figure said 30. Ten times over, on the last attack of every kill.
		assertEquals(2.90, CombatCalc.cappedAverage(60, 3), 0.01);
	}

	@Test
	public void aCapAboveTheMaxChangesNothing()
	{
		assertEquals(30.0, CombatCalc.cappedAverage(60, 9999), EXACT);
		assertEquals(30.0, CombatCalc.cappedAverage(60, 60), EXACT);
	}

	@Test
	public void theTailCapSitsNearTheCapAndNotNearHalfOfIt()
	{
		// Rolls 0..9 keep their value and 10..60 all land on 9:
		// (45 + 51*9) / 61.
		assertEquals(504.0 / 61.0, CombatCalc.cappedAverage(60, 9), EXACT);
		// Which is over eight, where half the cap would have been 4.5.
		assertEquals(8.26, CombatCalc.cappedAverage(60, 9), 0.01);
	}

	@Test
	public void theCrushlessTailCapIsWorseStill()
	{
		// Rolls 0..4 keep their value and 5..60 all land on 4, which is 56 of
		// them, not 57 — the count of collapsed rolls is trueMax minus cap.
		// (10 + 56*4) / 61
		assertEquals(234.0 / 61.0, CombatCalc.cappedAverage(60, 4), EXACT);
	}

	@Test
	public void aCapOfZeroLandsNothing()
	{
		assertEquals(0.0, CombatCalc.cappedAverage(60, 0), EXACT);
	}

	@Test
	public void noMaxHitIsNoDamage()
	{
		assertEquals(0.0, CombatCalc.cappedAverage(0, 9), EXACT);
		assertEquals(0.0, CombatCalc.cappedAverage(-1, 9), EXACT);
	}

	@Test
	public void aSmallerMaxRaisesTheAverageTowardsTheCap()
	{
		// The lower the true max, the fewer rolls collapse onto the cap, so a
		// weaker weapon loses proportionally less to it.
		final double strong = CombatCalc.cappedAverage(60, 9);
		final double weak = CombatCalc.cappedAverage(12, 9);
		assertEquals(true, weak < strong);
	}
}
