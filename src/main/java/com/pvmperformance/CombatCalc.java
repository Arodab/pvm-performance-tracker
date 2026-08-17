package com.pvmperformance;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Expected combat figures computed from the player's live loadout. This is the
 * start of the "expected" engine: melee and ranged max hit so far.
 *
 * <p>Max hit depends only on the attacker (levels, prayer, style, gear strength),
 * not on the target, so it needs no NPC data. Magic max hit, plus the
 * target-dependent expected accuracy and DPS, come later.
 *
 * <p>Formulae and prayer multipliers follow the LlemonDuck dps-calculator
 * (BSD-2). Not yet modelled: void, the controlled/longrange styles, magic, and
 * set/weapon multipliers (Salve, slayer helm, DHL, crystal, ...).
 */
class CombatCalc
{
	enum Style
	{
		MELEE,
		RANGED,
		MAGIC
	}

	private final Client client;
	private final ItemManager itemManager;

	@Inject
	CombatCalc(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/** Max hit for the current loadout and weapon style, or 0 if unavailable/magic. */
	int maxHit()
	{
		switch (weaponStyle())
		{
			case RANGED:
				return rangedMaxHit();
			case MAGIC:
				return 0; // TODO: spell-based magic max hit
			default:
				return meleeMaxHit();
		}
	}

	private int meleeMaxHit()
	{
		final int level = client.getBoostedSkillLevel(Skill.STRENGTH);
		if (level <= 0)
		{
			return 0;
		}
		final int styleBonus = client.getVarpValue(VarPlayerID.COM_MODE) == 1 ? 3 : 0; // aggressive
		final int effective = (int) Math.floor(level * meleePrayer()) + styleBonus + 8;
		return maxHitFromStrength(effective, equipmentBonus(true));
	}

	private int rangedMaxHit()
	{
		final int level = client.getBoostedSkillLevel(Skill.RANGED);
		if (level <= 0)
		{
			return 0;
		}
		final int styleBonus = client.getVarpValue(VarPlayerID.COM_MODE) == 0 ? 3 : 0; // accurate
		final int effective = (int) Math.floor(level * rangedPrayer()) + styleBonus + 8;
		return maxHitFromStrength(effective, equipmentBonus(false));
	}

	private static int maxHitFromStrength(int effectiveStrength, int strengthBonus)
	{
		return (effectiveStrength * (strengthBonus + 64) + 320) / 640;
	}

	private double meleePrayer()
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

	private double rangedPrayer()
	{
		if (client.isPrayerActive(Prayer.RIGOUR))
		{
			return 1.23;
		}
		if (client.isPrayerActive(Prayer.EAGLE_EYE))
		{
			return 1.15;
		}
		if (client.isPrayerActive(Prayer.HAWK_EYE))
		{
			return 1.10;
		}
		if (client.isPrayerActive(Prayer.SHARP_EYE))
		{
			return 1.05;
		}
		return 1.0;
	}

	/** Sum of the melee (str) or ranged (rstr) strength bonus across worn gear. */
	private int equipmentBonus(boolean melee)
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
				total += melee ? stats.getEquipment().getStr() : stats.getEquipment().getRstr();
			}
		}
		return total;
	}

	/** Rough attack style from the equipped weapon's dominant attack bonus. */
	private Style weaponStyle()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return Style.MELEE;
		}
		final Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null)
		{
			return Style.MELEE; // unarmed
		}
		final ItemStats stats = itemManager.getItemStats(weapon.getId());
		if (stats == null || stats.getEquipment() == null)
		{
			return Style.MELEE;
		}
		final ItemEquipmentStats e = stats.getEquipment();
		final int melee = Math.max(e.getAstab(), Math.max(e.getAslash(), e.getAcrush()));
		final int ranged = e.getArange();
		final int magic = e.getAmagic();
		if (ranged > melee && ranged >= magic)
		{
			return Style.RANGED;
		}
		if (magic > melee && magic > ranged)
		{
			return Style.MAGIC;
		}
		return Style.MELEE;
	}
}
