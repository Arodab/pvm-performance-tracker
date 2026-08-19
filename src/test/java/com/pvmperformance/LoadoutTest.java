package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import net.runelite.api.EquipmentInventorySlot;
import org.junit.Test;

/**
 * A loadout must be swappable without disturbing what it was made from, since a
 * search walks candidates by making them from one another and would otherwise
 * corrupt the very thing it is comparing against.
 */
public class LoadoutTest
{
	private static final int WHIP = 4151;
	private static final int SCYTHE = 22325;

	@Test
	public void anEmptyLoadoutHasNothingInAnySlot()
	{
		final Loadout empty = Loadout.worn(null, null);
		assertEquals(-1, empty.id(EquipmentInventorySlot.WEAPON));
		assertEquals(-1, empty.id(EquipmentInventorySlot.HEAD));
	}

	@Test
	public void swappingASlotLeavesTheOriginalAlone()
	{
		final Loadout worn = Loadout.worn(null, null);
		final Loadout swapped = worn.with(EquipmentInventorySlot.WEAPON, WHIP);
		assertEquals(WHIP, swapped.id(EquipmentInventorySlot.WEAPON));
		assertEquals(-1, worn.id(EquipmentInventorySlot.WEAPON));
	}

	@Test
	public void swapsCompoundWithoutTreadingOnEachOther()
	{
		final Loadout both = Loadout.worn(null, null)
			.with(EquipmentInventorySlot.WEAPON, WHIP)
			.with(EquipmentInventorySlot.HEAD, SCYTHE);
		assertEquals(WHIP, both.id(EquipmentInventorySlot.WEAPON));
		assertEquals(SCYTHE, both.id(EquipmentInventorySlot.HEAD));
	}

	@Test
	public void swappingForWhatIsAlreadyThereCostsNothing()
	{
		final Loadout worn = Loadout.worn(null, null).with(EquipmentInventorySlot.WEAPON, WHIP);
		assertSame(worn, worn.with(EquipmentInventorySlot.WEAPON, WHIP));
		assertNotSame(worn, worn.with(EquipmentInventorySlot.WEAPON, SCYTHE));
	}
}
