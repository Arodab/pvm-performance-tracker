package com.pvmperformance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * A tick is lost when the next attack is late. Deciding that needs two clocks
 * kept straight: the attack goes out on one tick and is booked a tick or two
 * later, and how much later depends on what proves it — a hitsplat for melee,
 * a projectile for the rest.
 *
 * <p>Every case here is one attack thrown on tick 0 with a four tick weapon, so
 * the next is due on tick 4. What changes is the lag of the attack being waited
 * for, which is the lag of the weapon in hand and not of the one that threw.
 */
public class AttackDueTest
{
	private static final int MELEE = 1;
	private static final int PROJECTILE = 2;
	private static final int DUE = 4;

	@Test
	public void meleeIntoMeleeIsNeverLateWhileOnTime()
	{
		// The blow lands on the tick it is thrown, so the booking for an attack
		// thrown on 4 arrives on 5. Nothing before that is late.
		assertFalse(PvmPerformancePlugin.attackOverdue(3, DUE, MELEE));
		assertFalse(PvmPerformancePlugin.attackOverdue(4, DUE, MELEE));
		assertTrue(PvmPerformancePlugin.attackOverdue(5, DUE, MELEE));
	}

	@Test
	public void projectileIntoProjectileIsNeverLateWhileOnTime()
	{
		// A projectile surfaces two ticks after it is fired, so the booking for
		// an attack thrown on 4 arrives on 6.
		assertFalse(PvmPerformancePlugin.attackOverdue(5, DUE, PROJECTILE));
		assertTrue(PvmPerformancePlugin.attackOverdue(6, DUE, PROJECTILE));
	}

	/**
	 * The case that was wrong. A whip attack followed by a switch to a trident:
	 * the trident goes out on time, on tick 4, but is not booked until tick 6.
	 * Measured against the whip's one tick lag, tick 5 looked like a tick with
	 * nothing happening in it, and every switch into a projectile weapon booked
	 * a lost tick that never happened.
	 */
	@Test
	public void switchingIntoAProjectileWeaponCostsNothing()
	{
		assertFalse(PvmPerformancePlugin.attackOverdue(5, DUE, PROJECTILE));
	}

	/**
	 * And the other way, which hid the bug: switching into melee shortens the
	 * wait, so the booking arrives before anything could be called late.
	 */
	@Test
	public void switchingIntoMeleeArrivesEarlyRatherThanLate()
	{
		assertFalse(PvmPerformancePlugin.attackOverdue(4, DUE, MELEE));
	}

	@Test
	public void aRealPauseIsStillLate()
	{
		// The forgiveness is one tick of booking lag, not a free tick. Idle past
		// it and both styles report it.
		assertTrue(PvmPerformancePlugin.attackOverdue(9, DUE, MELEE));
		assertTrue(PvmPerformancePlugin.attackOverdue(9, DUE, PROJECTILE));
	}

	@Test
	public void nothingIsDueBeforeTheFirstAttack()
	{
		// No attack yet leaves the due tick at its floor, which must read as
		// overdue rather than wrapping into the far future.
		assertTrue(PvmPerformancePlugin.attackOverdue(0, Integer.MIN_VALUE, PROJECTILE));
	}
}
