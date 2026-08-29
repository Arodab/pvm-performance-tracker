package com.pvmperformance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// The special attack energy varp does not fall on the tick the attack goes out,
// so matching the two by equality throws the special's expected damage away and
// books the ordinary one instead. This is the arithmetic that decides which
// attack an activation belongs to.
public class SpecialBookingWindowTest
{
	/**
	 * The case that was wrong in game, lifted from the trace: a burning claws
	 * special dropped the energy on tick 45 and its hitsplat landed on 46, so the
	 * equality check read false and booked 4.49 where the special expected 16.14.
	 */
	@Test
	public void theTracedBurningClawsSpecialIsBooked()
	{
		assertTrue(PvmPerformancePlugin.specialWentOutFor(46, 45));
	}

	/** A special whose attack lands on the same tick still counts. */
	@Test
	public void anAttackOnTheEnergyTickIsBooked()
	{
		assertTrue(PvmPerformancePlugin.specialWentOutFor(45, 45));
	}

	/**
	 * The window has to stay at one tick. The fastest weapon here attacks every
	 * two, so accepting two would let the NEXT ordinary attack claim the special
	 * as well - the failure this guards is over-attribution, not under.
	 */
	@Test
	public void theNextAttackOfAFastWeaponIsNotClaimed()
	{
		// Blowpipe on rapid: special on 45, its attack on 46, the next on 48.
		assertTrue(PvmPerformancePlugin.specialWentOutFor(46, 45));
		assertFalse(PvmPerformancePlugin.specialWentOutFor(47, 45));
		assertFalse(PvmPerformancePlugin.specialWentOutFor(48, 45));
	}

	/** An attack thrown before the energy fell cannot be that special's. */
	@Test
	public void anAttackBeforeTheEnergyFellIsNotBooked()
	{
		assertFalse(PvmPerformancePlugin.specialWentOutFor(44, 45));
		assertFalse(PvmPerformancePlugin.specialWentOutFor(0, 45));
	}

	/**
	 * No special has fired yet. The sentinel must not be subtracted from, which
	 * would overflow and read as an enormous gap either way round.
	 */
	@Test
	public void noSpecialYetIsNeverBooked()
	{
		assertFalse(PvmPerformancePlugin.specialWentOutFor(0, Integer.MIN_VALUE));
		assertFalse(PvmPerformancePlugin.specialWentOutFor(46, Integer.MIN_VALUE));
		assertFalse(PvmPerformancePlugin.specialWentOutFor(Integer.MAX_VALUE, Integer.MIN_VALUE));
	}

	/** A long trip must not start booking specials again as the tick count grows. */
	@Test
	public void anOldSpecialIsNotClaimedByALaterAttack()
	{
		for (int later = 47; later < 5000; later += 7)
		{
			assertFalse("tick " + later, PvmPerformancePlugin.specialWentOutFor(later, 45));
		}
	}
}
