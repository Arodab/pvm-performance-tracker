package com.pvmperformance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Wiki: the Corporeal Beast has "a 50% damage reduction against any melee and
 * ranged weapon that is not a Corpbane weapon. These weapons must also be on the
 * stab attack style." Magic is exempt outright.
 *
 * <p>Matched on name because the spears and halberds run to thirty-odd items
 * across every metal, and a list of ids would be a table where a missing entry
 * silently halves the figure.
 */
public class CorpbaneTest
{
	@Test
	public void everySpear()
	{
		assertTrue(CombatCalc.isCorpbaneWeapon("Rune spear"));
		assertTrue(CombatCalc.isCorpbaneWeapon("Zamorakian spear"));
		assertTrue(CombatCalc.isCorpbaneWeapon("Guthan's warspear"));
		assertTrue(CombatCalc.isCorpbaneWeapon("Leaf-bladed spear"));
	}

	@Test
	public void everyHalberd()
	{
		assertTrue(CombatCalc.isCorpbaneWeapon("Dragon halberd"));
		assertTrue(CombatCalc.isCorpbaneWeapon("Crystal halberd"));
		assertTrue(CombatCalc.isCorpbaneWeapon("Noxious halberd"));
	}

	@Test
	public void theTwoNamedOnes()
	{
		assertTrue(CombatCalc.isCorpbaneWeapon("Osmumten's fang"));
		assertTrue(CombatCalc.isCorpbaneWeapon("Thunder khopesh"));
	}

	@Test
	public void theWeaponsPeopleWronglyBring()
	{
		assertFalse(CombatCalc.isCorpbaneWeapon("Abyssal whip"));
		assertFalse(CombatCalc.isCorpbaneWeapon("Scythe of Vitur"));
		assertFalse(CombatCalc.isCorpbaneWeapon("Twisted bow"));
		assertFalse(CombatCalc.isCorpbaneWeapon(null));
	}
}
