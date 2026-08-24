package com.pvmperformance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * A cast with no projectile is judged to have missed when nothing says it
 * landed, so how long "nothing yet" lasts is the whole of it. Measured: the
 * barrage animation fired on 21, 26 and 31 and its damage landed on 24, 29 and
 * 34, which is three ticks.
 */
public class CastResolvedTest
{
	@Test
	public void theCastTickItselfIsTooEarly()
	{
		// Judged here, every landed barrage would be called a splash.
		assertFalse(PvmPerformancePlugin.castResolved(21, 21));
	}

	@Test
	public void theTicksBeforeTheDamageLandsAreTooEarly()
	{
		assertFalse(PvmPerformancePlugin.castResolved(22, 21));
		assertFalse(PvmPerformancePlugin.castResolved(23, 21));
	}

	@Test
	public void theTickTheDamageLandsOnIsTheAnswer()
	{
		assertTrue(PvmPerformancePlugin.castResolved(24, 21));
	}

	@Test
	public void aLateReadStillResolves()
	{
		// The next cast is five ticks out, so being late is not fatal, but it
		// must not read as "still waiting" and leave the answer unarmed.
		assertTrue(PvmPerformancePlugin.castResolved(26, 21));
	}
}
