package com.pvmperformance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The scythe's extra hits stopped applying because the name was matched with
 * the wiki's capitalisation and the item composition uses its own. Both of
 * these came out of a real log line: "Scythe of Vitur - Scythe, SLASH
 * AGGRESSIVE, 5 tick".
 */
public class ScytheNameTest
{
	@Test
	public void theNameTheItemCompositionActuallyReturns()
	{
		assertTrue(CombatCalc.isScythe("Scythe of Vitur"));
	}

	@Test
	public void theWikisCapitalisation()
	{
		assertTrue(CombatCalc.isScythe("Scythe of vitur"));
	}

	@Test
	public void theOrnamentKitsPutAWordInFront()
	{
		// Anchoring at the start failed for these even with the case right.
		assertTrue(CombatCalc.isScythe("Holy scythe of vitur"));
		assertTrue(CombatCalc.isScythe("Sanguine scythe of vitur"));
	}

	@Test
	public void theUnchargedForm()
	{
		assertTrue(CombatCalc.isScythe("Scythe of vitur (uncharged)"));
	}

	@Test
	public void otherWeaponsAreNotScythes()
	{
		assertFalse(CombatCalc.isScythe("Abyssal whip"));
		assertFalse(CombatCalc.isScythe("Noxious halberd"));
		assertFalse(CombatCalc.isScythe(null));
	}
}
