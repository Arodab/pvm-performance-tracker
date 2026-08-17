package com.pvmperformance;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
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
	private final MonsterStatsProvider monsters;

	@Inject
	CombatCalc(Client client, ItemManager itemManager, MonsterStatsProvider monsters)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.monsters = monsters;
	}

	/** Expected melee hit chance (0..1) vs the NPC, or -1 if not melee / no data. */
	double meleeHitChance(int npcId)
	{
		if (weaponStyle() != Style.MELEE)
		{
			return -1;
		}
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		if (npc == null)
		{
			return -1;
		}
		final int sub = meleeSubType();
		final int effAtk = (int) Math.floor(client.getBoostedSkillLevel(Skill.ATTACK) * meleeAccuracyPrayer())
			+ (client.getVarpValue(VarPlayerID.COM_MODE) == 0 ? 3 : 0) + 8; // accurate style
		final int attRoll = effAtk * (meleeAttackBonus(sub) + 64);
		final int defBonus = sub == 0 ? npc.getDefStab() : sub == 1 ? npc.getDefSlash() : npc.getDefCrush();
		final int defRoll = (npc.getDefenceLevel() + 9) * (defBonus + 64);
		return hitChanceFrom(attRoll, defRoll);
	}

	/** Expected melee DPS vs the NPC, or -1 if not melee / no data. */
	double meleeDps(int npcId)
	{
		final double accuracy = meleeHitChance(npcId);
		if (accuracy < 0)
		{
			return -1;
		}
		final double interval = weaponSpeedTicks() * 0.6;
		return accuracy * (meleeMaxHit() / 2.0) / interval;
	}

	private static double hitChanceFrom(int attRoll, int defRoll)
	{
		if (attRoll > defRoll)
		{
			return 1.0 - (defRoll + 2.0) / (2.0 * (attRoll + 1.0));
		}
		return attRoll / (2.0 * (defRoll + 1.0));
	}

	private double meleeAccuracyPrayer()
	{
		if (client.isPrayerActive(Prayer.PIETY))
		{
			return 1.20;
		}
		if (client.isPrayerActive(Prayer.CHIVALRY))
		{
			return 1.15;
		}
		if (client.isPrayerActive(Prayer.INCREDIBLE_REFLEXES))
		{
			return 1.15;
		}
		if (client.isPrayerActive(Prayer.IMPROVED_REFLEXES))
		{
			return 1.10;
		}
		if (client.isPrayerActive(Prayer.CLARITY_OF_THOUGHT))
		{
			return 1.05;
		}
		return 1.0;
	}

	/** 0 = stab, 1 = slash, 2 = crush, from the weapon's dominant attack bonus. */
	private int meleeSubType()
	{
		final ItemEquipmentStats w = weaponStats();
		if (w == null)
		{
			return 2; // unarmed punches are crush
		}
		if (w.getAstab() >= w.getAslash() && w.getAstab() >= w.getAcrush())
		{
			return 0;
		}
		return w.getAslash() >= w.getAcrush() ? 1 : 2;
	}

	private int meleeAttackBonus(int sub)
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
				final ItemEquipmentStats e = stats.getEquipment();
				total += sub == 0 ? e.getAstab() : sub == 1 ? e.getAslash() : e.getAcrush();
			}
		}
		return total;
	}

	private ItemEquipmentStats weaponStats()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return null;
		}
		final Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null)
		{
			return null;
		}
		final ItemStats stats = itemManager.getItemStats(weapon.getId());
		return stats == null ? null : stats.getEquipment();
	}

	private int weaponSpeedTicks()
	{
		final ItemEquipmentStats w = weaponStats();
		final int speed = w == null ? 4 : w.getAspeed();
		return speed <= 0 ? 4 : speed;
	}

	/** Max hit for the current loadout and weapon style, or 0 if unavailable/magic. */
	int maxHit()
	{
		switch (weaponStyle())
		{
			case RANGED:
				return rangedMaxHit();
			case MAGIC:
				return magicMaxHit();
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

	/**
	 * Powered-staff magic max hit from the magic level. Base value only for now:
	 * magic-damage % gear (occult, tormented, Shadow's tripling, tome of fire)
	 * and standard-spell casting are not applied yet, so it reads low with such
	 * gear. Returns 0 for anything not a supported powered staff. Formulae from
	 * the LlemonDuck dps-calculator (BSD-2).
	 */
	private int magicMaxHit()
	{
		final int magic = client.getBoostedSkillLevel(Skill.MAGIC);
		final int seas = Math.max(1, (magic - 75) / 3 + 20);
		switch (weaponItemId())
		{
			case ItemID.TRIDENT_OF_THE_SEAS:
			case ItemID.TRIDENT_OF_THE_SEAS_E:
			case ItemID.TRIDENT_OF_THE_SEAS_FULL:
				return seas;
			case ItemID.TRIDENT_OF_THE_SWAMP:
			case ItemID.TRIDENT_OF_THE_SWAMP_E:
				return seas + 3;
			case ItemID.SANGUINESTI_STAFF:
			case ItemID.HOLY_SANGUINESTI_STAFF:
				return seas + 4;
			case ItemID.TUMEKENS_SHADOW:
			case ItemID.CORRUPTED_TUMEKENS_SHADOW:
				return seas + 6;
			case ItemID.WARPED_SCEPTRE:
				return Math.max(1, (8 * magic + 96) / 37);
			default:
				return 0;
		}
	}

	private int weaponItemId()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return -1;
		}
		final Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return weapon == null ? -1 : weapon.getId();
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
