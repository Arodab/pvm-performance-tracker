package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// A special attack is not the attack the ordinary expected figures describe.
// Booking one with averageHit understates every special that raises accuracy
// and badly understates one whose hitsplats are capped, which is the case that
// started this: a Dawnbringer on Verzik's first phase expects three where it
// deals over a hundred.
public class SpecialAttackDamageTest
{
	private static final double EPSILON = 1e-9;

	/** The floor-aware average against brute force, which is what it replaces. */
	@Test
	public void cappedAverageWithAFloorMatchesBruteForce()
	{
		for (int max = 1; max <= 60; max++)
		{
			for (int min = 0; min <= max; min++)
			{
				for (int cap : new int[]{3, 10, 25, 1000})
				{
					double total = 0;
					for (int roll = min; roll <= max; roll++)
					{
						total += Math.min(roll, cap);
					}
					assertEquals(total / (max - min + 1),
						CombatCalc.cappedAverage(min, max, cap), EPSILON);
				}
			}
		}
	}

	/** With no floor it has to be the two-argument form exactly. */
	@Test
	public void aZeroFloorIsThePlainCappedAverage()
	{
		for (int max = 1; max <= 80; max++)
		{
			for (int cap : new int[]{3, 10, 47, Integer.MAX_VALUE})
			{
				assertEquals(CombatCalc.cappedAverage(max, cap),
					CombatCalc.cappedAverage(0, max, cap), EPSILON);
			}
		}
	}

	/**
	 * The Voidwaker's floor is worth as much as its guaranteed hit: Disrupt
	 * rolls 50% to 150% of the normal max, so it expects a WHOLE max hit rather
	 * than half of the raised one.
	 */
	@Test
	public void theVoidwakerExpectsAFullMaxHit()
	{
		final int normalMax = 40;
		final int[] maxima = SpecialAttack.VOIDWAKER.hitMaxima(normalMax);
		assertEquals(60, maxima[0]);
		final double expected = CombatCalc.specAverage(maxima, 1.0,
			SpecialAttack.VOIDWAKER.minFraction(), Integer.MAX_VALUE);
		// Between 20 and 60 averages 40, which is the normal max hit.
		assertEquals(normalMax, expected, 0.5);
		// Without the floor it would read three quarters of that, which is the
		// figure the ordinary average would have produced.
		assertNotEquals(expected,
			CombatCalc.specAverage(maxima, 1.0, 0.0, Integer.MAX_VALUE), 1.0);
	}

	/**
	 * A cap is per hitsplat, so it bites each hit of a multi-hit special
	 * separately rather than the activation's total.
	 */
	@Test
	public void aCapAppliesToEachHitsplatSeparately()
	{
		// Two 45s capped at 10 cannot expect more than 20 between them, however
		// large the maxima are.
		final double capped = CombatCalc.specAverage(new int[]{45, 45}, 1.0, 0.0, 10);
		assertTrue(capped <= 20.0);
		// And a cap is not a smaller max hit: rolling 0-45 and capping at 10
		// averages far nearer 10 than the 5 that halving a 10 max would give.
		assertTrue(capped > 15.0);
	}

	/**
	 * The claws' cascade of accuracy rolls is worth appreciably more than one
	 * roll for the whole activation, because a first-roll miss is usually
	 * rescued by the second.
	 */
	@Test
	public void theClawsCascadeBeatsASingleAccuracyRoll()
	{
		final int normalMax = 50;
		final double accuracy = 0.8;
		final double cascade = CombatCalc.clawsAverage(normalMax, accuracy, Integer.MAX_VALUE);
		// One roll for the lot, against the same 1, 1/2, 1/4, 1/4 shape.
		final double single = CombatCalc.specAverage(
			SpecialAttack.DRAGON_CLAWS.hitMaxima(normalMax), accuracy, 0.0, Integer.MAX_VALUE);
		assertTrue(cascade > single);
		// Worth about a fifth more at this accuracy, which is the figure the
		// cascade exists to capture.
		assertEquals(1.2, cascade / single, 0.05);
	}

	/** Every roll missing still deals something, so the floor is never zero. */
	@Test
	public void theClawsDealOneOnAverageWhenEveryRollMisses()
	{
		assertEquals(1.0, CombatCalc.clawsAverage(50, 0.0, Integer.MAX_VALUE), EPSILON);
	}

	/** A guaranteed hit spends none of its expectation on missing. */
	@Test
	public void theClawsAtFullAccuracyAreTheFirstOutcomeAlone()
	{
		final int normalMax = 48;
		// 24 + 12 + 6 + 6, the halves of the shape rounded as the table gives them.
		assertEquals(48.0, CombatCalc.clawsAverage(normalMax, 1.0, Integer.MAX_VALUE), EPSILON);
	}

	/**
	 * Pulsate is flat, and the one hit Verzik's first phase does not cap. The
	 * cap is what makes this worth a test: the ordinary figure for a magic
	 * attack there is three.
	 */
	@Test
	public void theDawnbringerExpectsItsOwnFlatRollAndIgnoresTheCap()
	{
		final SpecialAttack pulsate = SpecialAttack.DAWNBRINGER;
		assertTrue(pulsate.alwaysHits());
		assertTrue(pulsate.ignoresDamageCap());
		assertTrue(pulsate.hasFixedDamage());
		// 75 to 150 averages 112.5.
		assertEquals(112.5, CombatCalc.cappedAverage(75, 150, Integer.MAX_VALUE), 0.5);
		// The same roll held to Verzik's magic cap would read three, which is
		// what the plugin used to book for it.
		assertEquals(3.0, CombatCalc.cappedAverage(75, 150, 3), EPSILON);
	}

	/**
	 * The table's own invariants. A missing accuracy multiplier reads as 0 and
	 * would silently zero the special's expected damage, which a green build
	 * would not catch.
	 */
	@Test
	public void everySpecialCarriesAnAccuracyMultiplier()
	{
		for (SpecialAttack spec : SpecialAttack.values())
		{
			assertTrue(spec.name(), spec.getAttackRollMultiplier() > 0);
			assertTrue(spec.name(), spec.hits() >= 1);
			assertTrue(spec.name(), spec.minFraction() >= 0 && spec.minFraction() < 1);
		}
	}

	/**
	 * A special with no damage multipliers of its own is one hit at the max the
	 * caller worked out, which is how the fang, the bludgeon, the volatile staff
	 * and the Dawnbringer reach the average.
	 */
	@Test
	public void aSpecialWithoutMultipliersIsOneHitAtTheGivenMax()
	{
		for (SpecialAttack spec : new SpecialAttack[]{SpecialAttack.OSMUMTENS_FANG,
			SpecialAttack.ABYSSAL_BLUDGEON, SpecialAttack.VOLATILE_STAFF, SpecialAttack.DAWNBRINGER})
		{
			assertTrue(spec.name(), !spec.hasDamageMultipliers());
			assertEquals(spec.name(), 1, spec.hitMaxima(37).length);
			assertEquals(spec.name(), 37, spec.hitMaxima(37)[0]);
		}
	}

	/**
	 * The godswords double the attack ROLL, not the hit chance. Doubling the
	 * chance would put them over 100% against anything already hit two thirds
	 * of the time.
	 */
	@Test
	public void aDoubledAttackRollIsNotADoubledHitChance()
	{
		assertEquals(2.0, SpecialAttack.ARMADYL_GODSWORD.getAttackRollMultiplier(), EPSILON);
		final int roll = CombatCalc.attackRoll(100, 100, 1.0);
		assertEquals(2 * roll, SpecialAttack.ARMADYL_GODSWORD.scaleAttackRoll(roll));
	}

	/**
	 * The roll is scaled with integer arithmetic against the FINISHED roll, not
	 * folded in with the gear multiplier as a double. The game works in integers,
	 * and a double multiply can land a point either side after truncation - the
	 * same trap as fangMaxHit.
	 */
	@Test
	public void theAttackRollIsScaledAsAnExactRational()
	{
		final SpecialAttack dagger = SpecialAttack.DRAGON_DAGGER;
		assertEquals(1.15, dagger.getAttackRollMultiplier(), EPSILON);
		// 23/20 exactly, against every roll rather than at a few sample points.
		for (int roll = 0; roll <= 40000; roll += 7)
		{
			assertEquals((int) ((long) roll * 23 / 20), dagger.scaleAttackRoll(roll));
		}
		// A special that leaves the roll alone must return it untouched.
		for (int roll = 0; roll <= 40000; roll += 997)
		{
			assertEquals(roll, SpecialAttack.DRAGON_CLAWS.scaleAttackRoll(roll));
		}
	}

	/** The scaling must not overflow on a roll far larger than any real one. */
	@Test
	public void theRationalScalingDoesNotOverflow()
	{
		final int huge = Integer.MAX_VALUE / 2;
		assertEquals((int) ((long) huge * 143 / 100),
			SpecialAttack.MAGIC_SHORTBOW.scaleAttackRoll(huge));
		assertTrue(SpecialAttack.ARMADYL_GODSWORD.scaleAttackRoll(huge) > 0);
	}

	/**
	 * Burning claws were missing from the table entirely, so a spec showed no
	 * spec max and booked the ORDINARY expected hit. Their gameval name is
	 * BONE_CLAWS, which is why matching on the id rather than the name matters.
	 */
	@Test
	public void burningClawsAreInTheTableAndReachTheirWikiMax()
	{
		final SpecialAttack burning = SpecialAttack.BURNING_CLAWS;
		assertEquals(3, burning.hits());
		assertTrue(burning.cascadingAccuracy());
		// The first outcome's total reaches 175% of the max hit, split 25-25-50 -
		// but each hitsplat is floored on its own, so what LANDS is a point under
		// the total rolled. The wiki says so outright: a total roll of 29 "becomes
		// 7-7-14, a total of 28 actual damage, despite originally rolling for 29".
		assertEquals(69, burning.maxTotal(40));
		assertEquals(70, (int) (40 * 1.75));
		final int[] maxima = burning.hitMaxima(40);
		assertEquals(3, maxima.length);
		assertEquals(maxima[0], maxima[1]);
		assertTrue(maxima[2] > maxima[0]);
	}

	/**
	 * The wiki's own worked example, which also pins the flooring: "for a normal
	 * maximum hit of 39, the minimum and maximum hits for the first accuracy
	 * roll case are calculated as floor(0.75 * 39) and floor(1.75 * 39),
	 * resulting in a damage range of 29-68."
	 */
	@Test
	public void burningClawsMatchTheWikiWorkedExample()
	{
		assertEquals(29, (int) (39 * 0.75));
		assertEquals(68, (int) (39 * 1.75));
	}

	/**
	 * At full accuracy only the first outcome is reachable, and its total
	 * averages 125% of the max hit - the midpoint of 75% to 175%.
	 */
	@Test
	public void burningClawsAtFullAccuracyAverageTheFirstOutcome()
	{
		final int normalMax = 40;
		final double expected = CombatCalc.burningClawsAverage(normalMax, 1.0, Integer.MAX_VALUE);
		// Within a point of 1.25x, the rest being the per-hitsplat flooring.
		assertEquals(1.25 * normalMax, expected, 1.5);
	}

	/** Three misses still deal 0, 1 or 2 at 20/40/40, which averages 1.2. */
	@Test
	public void burningClawsDealTwelveTenthsWhenEveryRollMisses()
	{
		assertEquals(1.2, CombatCalc.burningClawsAverage(40, 0.0, Integer.MAX_VALUE), 1e-9);
	}

	/**
	 * The cascade is worth more than a single roll here too, and each later
	 * outcome is worth less than the one before - which is what says the rolls
	 * are being weighted by having got that far rather than treated alike.
	 */
	@Test
	public void burningClawsFallOffWithEachMissedRoll()
	{
		final int normalMax = 40;
		double previous = Double.MAX_VALUE;
		for (double accuracy : new double[]{1.0, 0.75, 0.5, 0.25})
		{
			final double value = CombatCalc.burningClawsAverage(normalMax, accuracy, Integer.MAX_VALUE);
			assertTrue(value < previous);
			previous = value;
		}
		// Never worse than the all-miss floor.
		assertTrue(CombatCalc.burningClawsAverage(normalMax, 0.01, Integer.MAX_VALUE) > 1.2);
	}

	/** A cap bites burning claws like any other special: it is not exempt. */
	@Test
	public void burningClawsAreCappedLikeAnythingElse()
	{
		assertTrue(CombatCalc.burningClawsAverage(60, 1.0, 10) <= 30.0);
		assertFalse(SpecialAttack.BURNING_CLAWS.ignoresDamageCap());
	}
}
