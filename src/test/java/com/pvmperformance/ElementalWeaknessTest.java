package com.pvmperformance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Wiki (Elemental weakness): each point is worth 1% magic damage and 1% magic
 * accuracy, and it applies only to the standard spellbook's elemental ladders -
 * "strike, bolt, blast, wave, and surge spells". Ancient spells are excluded
 * outright, which is why a barrage gets none of it however weak the target is.
 */
public class ElementalWeaknessTest
{
	@Test
	public void theWholeElementalLadderCounts()
	{
		assertTrue(Spell.EARTH_STRIKE.isElement("earth"));
		assertTrue(Spell.EARTH_BOLT.isElement("earth"));
		assertTrue(Spell.EARTH_BLAST.isElement("earth"));
		assertTrue(Spell.EARTH_WAVE.isElement("earth"));
		assertTrue(Spell.EARTH_SURGE.isElement("earth"));
	}

	@Test
	public void theWrongElementGetsNothing()
	{
		// The Hueycoatl is earth 60, so a fire surge there is an ordinary cast.
		assertFalse(Spell.FIRE_SURGE.isElement("earth"));
		assertFalse(Spell.WIND_WAVE.isElement("water"));
	}

	@Test
	public void anAncientSpellIsNeverElemental()
	{
		// The case that matters most here: barraging a target with a weakness
		// must not pick the bonus up.
		assertFalse(Spell.BLOOD_BARRAGE.isElement("earth"));
		assertFalse(Spell.ICE_BARRAGE.isElement("water"));
		assertFalse(Spell.FIRE_SURGE.isElement(null));
	}

	@Test
	public void matchingIsCaseInsensitiveBothWays()
	{
		// The element comes from the monster data, which is not ours to trust
		// the casing of.
		assertTrue(Spell.WATER_SURGE.isElement("WATER"));
		assertTrue(Spell.WATER_SURGE.isElement("Water"));
	}
}
