package com.pvmperformance;

import java.util.Arrays;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;

// A set of worn items, by slot.
final class Loadout
{
	private static final int SLOTS = EquipmentInventorySlot.AMMO.getSlotIdx() + 1;

	private final ItemManager itemManager;
	private final int[] ids;
	private final String[] names = new String[SLOTS];

	private Loadout(ItemManager itemManager, int[] ids)
	{
		this.itemManager = itemManager;
		this.ids = ids;
	}

	/** What the player is wearing, or an empty loadout if that cannot be read. */
	static Loadout worn(ItemManager itemManager, ItemContainer equipment)
	{
		final int[] ids = new int[SLOTS];
		Arrays.fill(ids, -1);
		if (equipment != null)
		{
			for (int slot = 0; slot < SLOTS; slot++)
			{
				final Item item = equipment.getItem(slot);
				ids[slot] = item == null ? -1 : item.getId();
			}
		}
		return new Loadout(itemManager, ids);
	}

	/**
	 * This loadout with one slot swapped, leaving this one untouched. A search
	 * walks candidates by making these, so it must not disturb what it came
	 * from.
	 */
	Loadout with(EquipmentInventorySlot slot, int itemId)
	{
		final int idx = slot.getSlotIdx();
		if (idx < 0 || idx >= SLOTS || ids[idx] == itemId)
		{
			return this;
		}
		final int[] swapped = ids.clone();
		swapped[idx] = itemId;
		return new Loadout(itemManager, swapped);
	}

	/**
	 * Whether two loadouts hold the same item in every slot. Compared by what
	 * is in them and not by identity, because the worn loadout is rebuilt every
	 * tick: two objects describing the same gear are the normal case, not the
	 * exception.
	 */
	boolean sameItems(Loadout other)
	{
		return other != null && Arrays.equals(ids, other.ids);
	}

	int id(EquipmentInventorySlot slot)
	{
		final int idx = slot.getSlotIdx();
		return idx < 0 || idx >= SLOTS ? -1 : ids[idx];
	}

	String name(EquipmentInventorySlot slot)
	{
		final int idx = slot.getSlotIdx();
		if (idx < 0 || idx >= SLOTS)
		{
			return null;
		}
		if (names[idx] == null)
		{
			final int id = ids[idx];
			names[idx] = id < 0 ? "" : itemManager.getItemComposition(id).getName();
		}
		return names[idx].isEmpty() ? null : names[idx];
	}
}
