package com.pvmperformance;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Expected combat figures computed from the player's live loadout. This is the
 * start of the "expected" engine; only the melee max hit is implemented so far.
 *
 * <p>Max hit depends only on the attacker (levels, prayer, style, gear strength),
 * not on the target, so it needs no NPC data. Accuracy and expected DPS come
 * later and will require the target's defensive stats.
 *
 * <p>Formulae follow the standard OSRS combat maths; the LlemonDuck dps-calculator
 * (BSD-2) was used as a reference. Not yet modelled: void, the controlled style
 * (+1), and set/weapon multipliers (Salve, slayer helm, DHL, etc.).
 */
class CombatCalc
{
	private final Client client;
	private final ItemManager itemManager;

	@Inject
	CombatCalc(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/** Melee max hit for the current loadout, or 0 if it can't be determined. */
	int meleeMaxHit()
	{
		final int strengthLevel = client.getBoostedSkillLevel(Skill.STRENGTH);
		if (strengthLevel <= 0)
		{
			return 0;
		}
		final int styleBonus = client.getVarpValue(VarPlayerID.COM_MODE) == 1 ? 3 : 0; // aggressive
		final int effectiveStrength = (int) Math.floor(strengthLevel * meleeStrengthPrayer()) + styleBonus + 8;
		final int strengthBonus = equipmentStrengthBonus();
		return (int) (0.5 + effectiveStrength * (strengthBonus + 64) / 640.0);
	}

	private double meleeStrengthPrayer()
	{
		if (client.isPrayerActive(Prayer.PIETY))
		{
			return 1.23;
		}
		if (client.isPrayerActive(Prayer.CHIVALRY))
		{
			return 1.18;
		}
		if (client.isPrayerActive(Prayer.ULTIMATE_STRENGTH))
		{
			return 1.15;
		}
		if (client.isPrayerActive(Prayer.SUPERHUMAN_STRENGTH))
		{
			return 1.10;
		}
		if (client.isPrayerActive(Prayer.BURST_OF_STRENGTH))
		{
			return 1.05;
		}
		return 1.0;
	}

	private int equipmentStrengthBonus()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return 0;
		}
		int total = 0;
		for (Item item : equipment.getItems())
		{
			final ItemStats stats = itemManager.getItemStats(item.getId());
			if (stats != null && stats.getEquipment() != null)
			{
				total += stats.getEquipment().getStr();
			}
		}
		return total;
	}
}
