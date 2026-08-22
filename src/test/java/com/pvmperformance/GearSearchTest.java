package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.EquipmentInventorySlot;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import org.junit.Test;

/**
 * The search is the one part of gear switching that needs no Client: it takes
 * what a loadout is worth as an argument. These use a scorer where a higher
 * item id is simply better, so the arithmetic never enters into it.
 */
public class GearSearchTest
{
	// An empty loadout needs no ItemManager as long as nothing asks for a name,
	// and the scorer here asks only for ids.
	private static Loadout empty()
	{
		return Loadout.worn(null, null);
	}

	private static GearSearch.Score higherIdIsBetter()
	{
		return gear ->
		{
			double total = 0;
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				total += Math.max(0, gear.id(slot));
			}
			return total;
		};
	}

	private static List<GearSearch.Candidate> carrying(EquipmentInventorySlot slot, int itemId)
	{
		return Collections.singletonList(new GearSearch.Candidate(slot, itemId));
	}

	@Test
	public void wornIsReturnedByIdentityWhenNothingBeatsIt()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.HEAD, 500);
		// The search does not copy when it has nothing to say. Callers must
		// still compare by sameItems, not by reference — the worn loadout is
		// rebuilt every tick, so this identity does not survive one.
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.HEAD, 100),
			false, higherIdIsBetter()));
	}

	@Test
	public void aBetterItemIsSwappedIn()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.HEAD, 100);
		final Loadout best = GearSearch.best(worn, carrying(EquipmentInventorySlot.HEAD, 500),
			false, higherIdIsBetter());
		assertEquals(500, best.id(EquipmentInventorySlot.HEAD));
	}

	@Test
	public void severalSlotsImproveTogether()
	{
		final Loadout worn = empty()
			.with(EquipmentInventorySlot.HEAD, 100)
			.with(EquipmentInventorySlot.BODY, 100);
		final Loadout best = GearSearch.best(worn, Arrays.asList(
			new GearSearch.Candidate(EquipmentInventorySlot.HEAD, 500),
			new GearSearch.Candidate(EquipmentInventorySlot.BODY, 700)),
			false, higherIdIsBetter());
		assertEquals(500, best.id(EquipmentInventorySlot.HEAD));
		assertEquals(700, best.id(EquipmentInventorySlot.BODY));
	}

	@Test
	public void theWeaponIsNeverJudged()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.WEAPON, 100);
		// The whole point of pinning the weapon: a better one in the bag is not
		// a missed switch, because which weapon to bring is not being scored.
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.WEAPON, 900),
			false, higherIdIsBetter()));
	}

	@Test
	public void aTwoHanderLeavesNoShieldSlotToFill()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.WEAPON, 100);
		// Advice the game would not let the player take is not advice.
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.SHIELD, 900),
			true, higherIdIsBetter()));
	}

	@Test
	public void aShieldIsJudgedWhenTheWeaponIsOneHanded()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.WEAPON, 100);
		final Loadout best = GearSearch.best(worn, carrying(EquipmentInventorySlot.SHIELD, 900),
			false, higherIdIsBetter());
		assertEquals(900, best.id(EquipmentInventorySlot.SHIELD));
	}

	@Test
	public void anItemAlreadyWornInThatSlotIsNotASwitch()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.HEAD, 500);
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.HEAD, 500),
			false, higherIdIsBetter()));
	}
}
