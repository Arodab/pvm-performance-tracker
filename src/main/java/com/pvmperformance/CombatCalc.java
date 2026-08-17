package com.pvmperformance;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Expected combat figures computed from the player's live loadout: max hit, hit
 * chance and DPS against a given NPC, for all three combat styles.
 *
 * <p>Max hit depends only on the attacker (levels, prayer, style, gear strength),
 * not on the target, so it needs no NPC data. Hit chance and DPS additionally
 * need the target's defensive stats from {@link MonsterStatsProvider}, and
 * return -1 when those are unavailable.
 *
 * <p>The combat option the player has actually selected is resolved by
 * {@link #attackStyle()} and drives both the attack type rolled against and the
 * invisible level boosts, so switching a weapon's attack option is reflected
 * here.
 *
 * <p>Formulae and prayer multipliers follow the LlemonDuck dps-calculator
 * (BSD-2). Not yet modelled: void, and set/weapon multipliers (Salve, slayer
 * helm, DHL, crystal, ...).
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

	/**
	 * Expected hit chance (0..1) vs the NPC, or -1 when there is no data or the
	 * style isn't modelled yet (magic).
	 */
	double hitChance(int npcId)
	{
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		if (npc == null)
		{
			return -1;
		}
		final AttackStyle style = attackStyle();
		final AttackType type = style.getAttackType();
		if (type.isMelee())
		{
			return meleeHitChance(style, type, npc);
		}
		if (type == AttackType.RANGED)
		{
			return rangedHitChance(style, npc);
		}
		return magicHitChance(style, npc);
	}

	/** Expected DPS vs the NPC, or -1 when the hit chance or max hit is unavailable. */
	double dps(int npcId)
	{
		final double accuracy = hitChance(npcId);
		final int max = maxHit();
		if (accuracy < 0 || max <= 0)
		{
			return -1;
		}
		return accuracy * (max / 2.0) / (weaponSpeedTicks() * 0.6);
	}

	private double meleeHitChance(AttackStyle style, AttackType type, MonsterStatsProvider.MonsterStats npc)
	{
		final int effAtk = (int) Math.floor(client.getBoostedSkillLevel(Skill.ATTACK) * meleeAccuracyPrayer())
			+ style.attackLevelBonus() + 8;
		final int attRoll = effAtk * (attackBonus(type) + 64);
		final int defBonus = type == AttackType.STAB ? npc.getDefStab()
			: type == AttackType.SLASH ? npc.getDefSlash() : npc.getDefCrush();
		final int defRoll = (npc.getDefenceLevel() + 9) * (defBonus + 64);
		return hitChanceFrom(attRoll, defRoll);
	}

	/**
	 * Magic rolls against the target's magic level rather than its defence
	 * level, and its effective level starts from +9 rather than the +8 melee and
	 * ranged use.
	 */
	private double magicHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc)
	{
		final int effMagic = (int) Math.floor(client.getBoostedSkillLevel(Skill.MAGIC) * magicAccuracyPrayer())
			+ style.attackLevelBonus() + 9;
		final int attRoll = effMagic * (attackBonus(AttackType.MAGIC) + 64);
		final int defRoll = (npc.getMagicLevel() + 9) * (npc.getDefMagic() + 64);
		return hitChanceFrom(attRoll, defRoll);
	}

	private double rangedHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc)
	{
		final int effRanged = (int) Math.floor(client.getBoostedSkillLevel(Skill.RANGED) * rangedAccuracyPrayer())
			+ style.attackLevelBonus() + 8;
		final int attRoll = effRanged * (attackBonus(AttackType.RANGED) + 64);
		final int defRoll = (npc.getDefenceLevel() + 9) * (npc.getDefRanged() + 64);
		return hitChanceFrom(attRoll, defRoll);
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
		if (prayerActive(Prayer.PIETY))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.CHIVALRY))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.INCREDIBLE_REFLEXES))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.IMPROVED_REFLEXES))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.CLARITY_OF_THOUGHT))
		{
			return 1.05;
		}
		return 1.0;
	}

	/**
	 * The combat option the player currently has selected, from the pairing of
	 * the equipped weapon's category with the selected option index.
	 *
	 * <p>Never null: categories missing from {@link WeaponCategory} fall back to
	 * {@link #fallbackStyle()}.
	 */
	private WeaponCategory weaponCategory()
	{
		return WeaponCategory.forVarbit(client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY));
	}

	/** The spell currently set to autocast, or null if none is. */
	private Spell autocastSpell()
	{
		return Spell.forVarbit(client.getVarbitValue(VarbitID.AUTOCAST_SPELL));
	}

	private AttackStyle attackStyle()
	{
		final int varp = client.getVarpValue(VarPlayerID.COM_MODE);
		final WeaponCategory category = weaponCategory();
		if (category != null)
		{
			final AttackStyle style = category.styleFor(varp);
			if (style != null)
			{
				return style;
			}
		}
		return fallbackStyle(varp);
	}

	/**
	 * Best guess for a weapon category we don't have a table for: the attack
	 * type comes from the weapon's dominant attack bonus and the combat style
	 * from the layout the great majority of categories share.
	 */
	private AttackStyle fallbackStyle(int varp)
	{
		final AttackType type = dominantAttackType();
		final CombatStyle style;
		if (type == AttackType.RANGED)
		{
			style = varp == 0 ? CombatStyle.ACCURATE
				: varp == 1 ? CombatStyle.RAPID
				: varp == 3 ? CombatStyle.LONGRANGE : CombatStyle.DEFENSIVE;
		}
		else if (type == AttackType.MAGIC)
		{
			style = varp == 3 ? CombatStyle.LONGRANGE : CombatStyle.ACCURATE;
		}
		else
		{
			style = varp == 0 ? CombatStyle.ACCURATE
				: varp == 1 ? CombatStyle.AGGRESSIVE
				: varp == 2 ? CombatStyle.CONTROLLED : CombatStyle.DEFENSIVE;
		}
		return new AttackStyle(varp, "Unknown", type, style);
	}

	/** The attack type the equipped weapon is strongest in. */
	private AttackType dominantAttackType()
	{
		final ItemEquipmentStats w = weaponStats();
		if (w == null)
		{
			return AttackType.CRUSH; // unarmed punches are crush
		}
		final int melee = Math.max(w.getAstab(), Math.max(w.getAslash(), w.getAcrush()));
		if (w.getArange() > melee && w.getArange() >= w.getAmagic())
		{
			return AttackType.RANGED;
		}
		if (w.getAmagic() > melee && w.getAmagic() > w.getArange())
		{
			return AttackType.MAGIC;
		}
		if (w.getAstab() >= w.getAslash() && w.getAstab() >= w.getAcrush())
		{
			return AttackType.STAB;
		}
		return w.getAslash() >= w.getAcrush() ? AttackType.SLASH : AttackType.CRUSH;
	}

	/** Sum of the worn gear's attack bonus for the type being rolled. */
	private int attackBonus(AttackType type)
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
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
				switch (type)
				{
					case STAB:
						total += e.getAstab();
						break;
					case SLASH:
						total += e.getAslash();
						break;
					case CRUSH:
						total += e.getAcrush();
						break;
					case RANGED:
						total += e.getArange();
						break;
					default:
						total += e.getAmagic();
						break;
				}
			}
		}
		return total;
	}

	private ItemEquipmentStats weaponStats()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
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
		final int ticks = speed <= 0 ? 4 : speed;
		final AttackStyle style = attackStyle();
		if (style.getAttackType() == AttackType.MAGIC)
		{
			return castSpeedTicks(ticks);
		}
		// Rapid fires a tick sooner; it is the only style that alters attack speed.
		return style.getCombatStyle() == CombatStyle.RAPID ? Math.max(1, ticks - 1) : ticks;
	}

	/**
	 * Casting runs on its own clock: the weapon's speed applies only to powered
	 * staves and salamanders, which fire their own attack rather than a spell.
	 * Everything else casts in 5 ticks, or 4 for the harmonised staff on the
	 * standard spellbook.
	 */
	private int castSpeedTicks(int weaponTicks)
	{
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER)
		{
			return weaponTicks;
		}
		final Spell spell = autocastSpell();
		if (weaponItemId() == ItemID.NIGHTMARE_STAFF_HARMONISED
			&& spell != null && spell.getSpellbook() == Spellbook.STANDARD)
		{
			return 4;
		}
		return 5;
	}

	/** Max hit for the current loadout and weapon style, or 0 if unavailable. */
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
		final int effective = (int) Math.floor(level * meleePrayer()) + attackStyle().strengthLevelBonus() + 8;
		return maxHitFromStrength(effective, equipmentBonus(true));
	}

	private int rangedMaxHit()
	{
		final int level = client.getBoostedSkillLevel(Skill.RANGED);
		if (level <= 0)
		{
			return 0;
		}
		final int effective = (int) Math.floor(level * rangedPrayer()) + attackStyle().strengthLevelBonus() + 8;
		return maxHitFromStrength(effective, equipmentBonus(false));
	}

	private static int maxHitFromStrength(int effectiveStrength, int strengthBonus)
	{
		return (effectiveStrength * (strengthBonus + 64) + 320) / 640;
	}

	/**
	 * Magic max hit: the autocast spell's base hit for a casting staff, or the
	 * staff's own attack for a powered staff, either way scaled by the magic
	 * damage bonus of the worn gear.
	 *
	 * <p>Returns 0 when nothing is set to autocast and the weapon isn't a
	 * supported powered staff, since the spell being cast manually can't be
	 * read. Formulae from the LlemonDuck dps-calculator (BSD-2).
	 *
	 * <p>The combat option does not enter this: a spell's max hit is the same on
	 * autocast and defensive autocast.
	 */
	private int magicMaxHit()
	{
		final int base = poweredStaffMaxHit();
		if (base > 0)
		{
			return applyMagicDamage(base);
		}
		final Spell spell = autocastSpell();
		return spell == null ? 0 : applyMagicDamage(spell.getBaseMaxHit());
	}

	/**
	 * Scales a base magic hit by the summed magic damage % of the worn gear
	 * (occult, tormented, ancestral, ...). Not modelled: Tumeken's Shadow
	 * tripling its own gear bonus, the tomes' elemental bonuses, and the
	 * smoke staff's standard-spell bonus, so those setups still read low.
	 */
	private int applyMagicDamage(int baseMaxHit)
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return baseMaxHit;
		}
		int percent = 0;
		for (Item item : equipment.getItems())
		{
			final ItemStats stats = itemManager.getItemStats(item.getId());
			if (stats != null && stats.getEquipment() != null)
			{
				percent += stats.getEquipment().getMdmg();
			}
		}
		return (int) Math.floor(baseMaxHit * (1.0 + percent / 100.0));
	}

	/** The staff's own attack, for powered staves, or 0 if this isn't one. */
	private int poweredStaffMaxHit()
	{
		final int magic = client.getBoostedSkillLevel(Skill.MAGIC);
		final int seas = Math.max(1, (magic - 75) / 3 + 20);
		// gameval names these after the cache, so "TOTS" is trident of the seas
		// and "_OR" / "DEADMAN_BLIGHTED_" are the cosmetic and Deadman variants.
		switch (weaponItemId())
		{
			case ItemID.TOTS:
			case ItemID.TOTS_CHARGED:
			case ItemID.TOTS_I_CHARGED:
				return seas;
			case ItemID.TOXIC_TOTS_CHARGED:
			case ItemID.TOXIC_TOTS_I_CHARGED:
				return seas + 3;
			case ItemID.SANGUINESTI_STAFF:
			case ItemID.SANGUINESTI_STAFF_OR:
				return seas + 4;
			case ItemID.TUMEKENS_SHADOW:
			case ItemID.DEADMAN_BLIGHTED_TUMEKENS_SHADOW:
				return seas + 6;
			case ItemID.WARPED_SCEPTRE:
				return Math.max(1, (8 * magic + 96) / 37);
			default:
				return 0;
		}
	}

	private int weaponItemId()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return -1;
		}
		final Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return weapon == null ? -1 : weapon.getId();
	}

	private double meleePrayer()
	{
		if (prayerActive(Prayer.PIETY))
		{
			return 1.23;
		}
		if (prayerActive(Prayer.CHIVALRY))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.ULTIMATE_STRENGTH))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.SUPERHUMAN_STRENGTH))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.BURST_OF_STRENGTH))
		{
			return 1.05;
		}
		return 1.0;
	}

	/**
	 * Whether a prayer is currently active. Reads the prayer's varbit directly;
	 * {@code Client.isPrayerActive} is deprecated and has no replacement overload.
	 */
	private boolean prayerActive(Prayer prayer)
	{
		return client.getVarbitValue(prayer.getVarbit()) == 1;
	}

	/** Magic attack prayer multiplier. The magic prayers boost accuracy only, not damage. */
	private double magicAccuracyPrayer()
	{
		if (prayerActive(Prayer.AUGURY))
		{
			return 1.25;
		}
		if (prayerActive(Prayer.MYSTIC_MIGHT))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.MYSTIC_LORE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.MYSTIC_WILL))
		{
			return 1.05;
		}
		return 1.0;
	}

	/** Ranged attack prayer multiplier. Rigour gives less here than it does to strength. */
	private double rangedAccuracyPrayer()
	{
		if (prayerActive(Prayer.RIGOUR))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.EAGLE_EYE))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.HAWK_EYE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.SHARP_EYE))
		{
			return 1.05;
		}
		return 1.0;
	}

	private double rangedPrayer()
	{
		if (prayerActive(Prayer.RIGOUR))
		{
			return 1.23;
		}
		if (prayerActive(Prayer.EAGLE_EYE))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.HAWK_EYE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.SHARP_EYE))
		{
			return 1.05;
		}
		return 1.0;
	}

	/** Sum of the melee (str) or ranged (rstr) strength bonus across worn gear. */
	private int equipmentBonus(boolean melee)
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
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

	/** Which of the three combat styles the selected combat option attacks with. */
	private Style weaponStyle()
	{
		switch (attackStyle().getAttackType())
		{
			case RANGED:
				return Style.RANGED;
			case MAGIC:
				return Style.MAGIC;
			default:
				return Style.MELEE;
		}
	}
}
