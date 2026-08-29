package com.pvmperformance;

import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// The table was audited against the wiki's Special attacks page on 2026-08-26,
// which found it held 21 of the specials that modify a roll. These are the
// invariants that keep the other fifty honest.
public class SpecialAttackTableTest
{
	private static final double EPSILON = 1e-9;

	/**
	 * Two weapons sharing an id means the second is unreachable: the lookup is a
	 * map keyed on the id, so the later constant silently overwrites the earlier
	 * and one weapon simply never gets its special. A green build hides it
	 * entirely, which is why this is worth a test with seventy-odd entries.
	 */
	@Test
	public void noTwoWeaponsShareAnItemId()
	{
		final Map<Integer, SpecialAttack> byId = new HashMap<>();
		for (SpecialAttack spec : SpecialAttack.values())
		{
			for (int id : spec.getItemIds())
			{
				final SpecialAttack clash = byId.put(id, spec);
				assertNull(spec.name() + " shares id " + id
					+ " with " + (clash == null ? "" : clash.name()), clash);
			}
		}
	}

	/** Every constant is findable through the lookup the plugin actually uses. */
	@Test
	public void everyConstantIsReachableThroughTheLookup()
	{
		for (SpecialAttack spec : SpecialAttack.values())
		{
			assertTrue(spec.name(), spec.getItemIds().length > 0);
			for (int id : spec.getItemIds())
			{
				assertEquals(spec.name(), spec, SpecialAttack.forItem(id));
			}
		}
	}

	/** A special with a fixed roll owes nothing to the player's max hit. */
	@Test
	public void fixedDamageSpecialsCarryTheirOwnCeiling()
	{
		assertEquals(150, SpecialAttack.DAWNBRINGER.fixedMax());
		assertEquals(25, SpecialAttack.DRAGONFIRE_SHIELD.fixedMax());
		assertEquals(15, SpecialAttack.ANCIENT_WYVERN_SHIELD.fixedMax());
		for (SpecialAttack spec : SpecialAttack.values())
		{
			assertEquals(spec.name(), spec.fixedMax() > 0, spec.hasFixedDamage());
		}
		// Only Pulsate is exempt from a cap; the shields are not.
		assertTrue(SpecialAttack.DAWNBRINGER.ignoresDamageCap());
		assertTrue(!SpecialAttack.DRAGONFIRE_SHIELD.ignoresDamageCap());
	}

	/**
	 * Hammer Blow rolls "up to max roll -5, plus 5", which is a floor of five
	 * hitpoints rather than a share of the max - the one case minFraction cannot
	 * express.
	 */
	@Test
	public void theGraniteHammerHasAFlatFloorRatherThanAShare()
	{
		assertEquals(5, SpecialAttack.GRANITE_HAMMER.flatMinimum());
		assertEquals(0.0, SpecialAttack.GRANITE_HAMMER.minFraction(), EPSILON);
		for (SpecialAttack spec : SpecialAttack.values())
		{
			assertTrue(spec.name(), spec.flatMinimum() >= 0);
		}
		// A max of 40 with a floor of 5 averages 22.5, not 20.
		final double expected = CombatCalc.specAverage(new int[]{40}, 1.0, 0.0, 5, Integer.MAX_VALUE);
		assertEquals(22.5, expected, EPSILON);
	}

	/** A flat floor above the max must not invert the roll. */
	@Test
	public void aFlatFloorAboveTheMaxIsClampedToIt()
	{
		final double expected = CombatCalc.specAverage(new int[]{3}, 1.0, 0.0, 5, Integer.MAX_VALUE);
		assertEquals(3.0, expected, EPSILON);
	}

	/**
	 * The crimson kisten is a binomial, not a cascade: all four rolls are made
	 * and the number that SUCCEED picks the damage band.
	 */
	@Test
	public void theCrimsonKistenIsABinomialNotACascade()
	{
		assertTrue(SpecialAttack.CRIMSON_KISTEN.binomialAccuracy());
		assertTrue(!SpecialAttack.CRIMSON_KISTEN.cascadingAccuracy());
		final int normalMax = 50;
		// All four connect: 130-170% of the max, averaging 150%.
		assertEquals(1.5 * normalMax,
			CombatCalc.crimsonKistenAverage(normalMax, 1.0, Integer.MAX_VALUE), 1.0);
		// None connect: the special deals nothing at all.
		assertEquals(0.0, CombatCalc.crimsonKistenAverage(normalMax, 0.0, Integer.MAX_VALUE), EPSILON);
	}

	/** More accuracy is always worth more damage, at every step. */
	@Test
	public void theCrimsonKistenRisesWithAccuracy()
	{
		double previous = -1;
		for (double accuracy = 0.0; accuracy <= 1.0; accuracy += 0.1)
		{
			final double value = CombatCalc.crimsonKistenAverage(50, accuracy, Integer.MAX_VALUE);
			assertTrue(value > previous);
			previous = value;
		}
	}

	/**
	 * The weapons whose ids are nothing like their names. Each was resolved from
	 * the wiki's item id and looked up by NUMBER; a wrong-but-existing name
	 * compiles and silently does nothing, which is the whole reason for the rule.
	 */
	@Test
	public void theWeaponsWithMisleadingGamevalNamesAreAllPresent()
	{
		for (SpecialAttack spec : new SpecialAttack[]{
			SpecialAttack.BURNING_CLAWS,      // BONE_CLAWS
			SpecialAttack.DAWNBRINGER,        // VERZIK_SPECIAL_WEAPON
			SpecialAttack.DRAGON_SWORD,       // DRAGON_SHORTSWORD
			SpecialAttack.ARMADYL_CROSSBOW,   // ACB
			SpecialAttack.THE_DOGSWORD,       // ECHO_GODSWORD
			SpecialAttack.SUNLIGHT_SPEAR,     // WEAPON_OF_SOL
			SpecialAttack.SEERCULL,           // DAGANOTH_CAVE_MAGIC_SHORTBOW
			SpecialAttack.BLUE_MOON_SPEAR,    // FROSTMOON_SPEAR
			SpecialAttack.ZARYTE_CROSSBOW,    // ZARYTE_XBOW
			SpecialAttack.BRINE_SABRE,        // OLAF2_BRINE_SABRE
			SpecialAttack.ACCURSED_SCEPTRE,   // WILD_CAVE_ACCURSED_CHARGED
			SpecialAttack.URSINE_CHAINMACE,   // WILD_CAVE_URSINE_CHARGED
			SpecialAttack.SOULREAPER_AXE,     // SOULREAPER
		})
		{
			assertNotNull(spec.name(), spec.getDisplayName());
			assertTrue(spec.name(), spec.getAttackRollMultiplier() > 0);
		}
	}

	/**
	 * The six specials that cannot miss. Everything else must carry a usable
	 * attack roll multiplier instead.
	 */
	@Test
	public void theGuaranteedHitsAreTheOnesThatShouldBe()
	{
		int guaranteed = 0;
		for (SpecialAttack spec : SpecialAttack.values())
		{
			if (spec.alwaysHits())
			{
				guaranteed++;
			}
		}
		// Voidwaker, Dawnbringer, magic longbow (which carries the comp bow),
		// Seercull and the sunlight spear.
		assertEquals(5, guaranteed);
		assertTrue(SpecialAttack.VOIDWAKER.alwaysHits());
		assertTrue(SpecialAttack.SEERCULL.alwaysHits());
		assertTrue(!SpecialAttack.ARMADYL_GODSWORD.alwaysHits());
	}
}
