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
	private final GearBonusCalc gearBonuses;
	private final PvmPerformanceConfig config;

	@Inject
	CombatCalc(Client client, ItemManager itemManager, MonsterStatsProvider monsters,
		GearBonusCalc gearBonuses, PvmPerformanceConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.monsters = monsters;
		this.gearBonuses = gearBonuses;
		this.config = config;
	}

	/** The gear multipliers for the current loadout against this target. */
	private GearBonus gearBonus(int npcId)
	{
		return gearBonuses.compute(attackStyle(), monsters.get(npcId), autocastSpell(), config.assumeSlayerTask());
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
		final double gear = gearBonus(npcId).getAccuracy();
		if (type.isMelee())
		{
			return meleeHitChance(style, type, npc, gear);
		}
		if (type == AttackType.RANGED)
		{
			return rangedHitChance(style, npc, gear);
		}
		return magicHitChance(style, npc, gear);
	}

	/**
	 * Expected damage per attack made against the NPC, counting misses as zero,
	 * or -1 when the hit chance or max hit is unavailable.
	 *
	 * <p>A landed hit rolls uniformly up to the max, so it averages half of it;
	 * multiplying by the hit chance folds the misses back in. Unlike DPS this
	 * doesn't depend on attack speed, so it compares directly against the
	 * measured damage-per-attack without the fight's pace getting in the way.
	 */
	double averageHit(int npcId)
	{
		final double accuracy = hitChance(npcId);
		// Averages, not best cases: a keris crit or an ahrim's proc raises the max
		// hit but only lifts sustained damage by a few percent.
		final double averageMax = baseMaxHit() * gearBonus(npcId).getExpectedDamage();
		if (accuracy < 0 || averageMax <= 0)
		{
			return -1;
		}
		return accuracy * (averageMax / 2.0);
	}

	private double meleeHitChance(AttackStyle style, AttackType type, MonsterStatsProvider.MonsterStats npc, double gear)
	{
		final int effAtk = (int) Math.floor(client.getBoostedSkillLevel(Skill.ATTACK) * meleeAccuracyPrayer())
			+ style.attackLevelBonus() + 8;
		final int attRoll = (int) (effAtk * (attackBonus(type) + 64) * gear);
		final int defBonus = type == AttackType.STAB ? npc.getDefStab()
			: type == AttackType.SLASH ? npc.getDefSlash() : npc.getDefCrush();
		final int defRoll = (npc.getDefenceLevel() + 9) * (defBonus + 64);
		return meleeHitChanceFrom(attRoll, defRoll);
	}

	/**
	 * Magic rolls against the target's magic level rather than its defence
	 * level, and its effective level starts from +9 rather than the +8 melee and
	 * ranged use.
	 */
	private double magicHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc, double gear)
	{
		final int effMagic = (int) Math.floor(client.getBoostedSkillLevel(Skill.MAGIC) * magicAccuracyPrayer())
			+ style.attackLevelBonus() + 9;
		final int attRoll = (int) (effMagic * (attackBonus(AttackType.MAGIC) + 64) * gear);
		final int defRoll = (npc.getMagicLevel() + 9) * (npc.getDefMagic() + 64);
		if (!hasConflictionGauntlets())
		{
			return hitChanceFrom(attRoll, defRoll);
		}
		return conflictionHitChance(attRoll, defRoll);
	}

	/**
	 * Confliction gauntlets give the double accuracy roll only on the attack
	 * following a splash, so the rate depends on how often you are splashing —
	 * which itself depends on the rate. Averaged over a fight it settles into a
	 * steady state.
	 *
	 * <p>Attacks alternate between two states: a normal roll after a hit, and a
	 * doubled roll after a miss. Missing at {@code 1-p} moves you into the
	 * doubled state and missing again at {@code 1-q} keeps you there, so the
	 * share of attacks that get the bonus settles at {@code (1-p)/(1-p+q)}. The
	 * returned figure is the two states weighted by that share.
	 *
	 * <p>With no bonus ({@code q == p}) this reduces to {@code p}, as it should.
	 */
	private static double conflictionHitChance(int attRoll, int defRoll)
	{
		final double p = hitChanceFrom(attRoll, defRoll);
		final double q = 1.0 - sharedDefenceMissChance(attRoll, defRoll);
		final double doubledShare = (1.0 - p) / (1.0 - p + q);
		return (1.0 - doubledShare) * p + doubledShare * q;
	}

	/**
	 * Whether the confliction gauntlets are worn and able to work: their effect
	 * is disabled entirely by a two-handed weapon.
	 */
	private boolean hasConflictionGauntlets()
	{
		if (equippedItemId(EquipmentInventorySlot.GLOVES) != ItemID.CONFLICTION_GAUNTLETS)
		{
			return false;
		}
		final ItemEquipmentStats weapon = weaponStats();
		return weapon == null || !weapon.isTwoHanded();
	}

	private double rangedHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc, double gear)
	{
		final int effRanged = (int) Math.floor(client.getBoostedSkillLevel(Skill.RANGED) * rangedAccuracyPrayer())
			+ style.attackLevelBonus() + 8;
		final int attRoll = (int) (effRanged * (attackBonus(AttackType.RANGED) + 64) * gear);
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

	/**
	 * Hit chance for a melee attack, taking Osmumten's fang's second accuracy
	 * roll into account.
	 *
	 * <p>An ordinary attack rolls once from 0..attRoll against one roll from
	 * 0..defRoll and hits if it is the higher. The fang rolls twice and lands if
	 * either succeeds, but what gets re-rolled differs: inside the Tombs of
	 * Amascut the target's defence is re-rolled too, making the two attempts
	 * independent, while outside it both attacks are compared against a single
	 * defence roll.
	 */
	private double meleeHitChanceFrom(int attRoll, int defRoll)
	{
		if (!isFangEquipped())
		{
			return hitChanceFrom(attRoll, defRoll);
		}
		if (gearBonuses.inTombsOfAmascut())
		{
			final double miss = 1.0 - hitChanceFrom(attRoll, defRoll);
			return 1.0 - miss * miss;
		}
		return 1.0 - sharedDefenceMissChance(attRoll, defRoll);
	}

	/**
	 * Probability that two attack rolls both fail against the same defence roll.
	 *
	 * <p>For a fixed defence roll d, one attack misses with probability
	 * (d+1)/(attRoll+1), so two miss with the square of that; averaging the
	 * square over d gives the closed forms below. The sum of squares over the
	 * defence range is what turns into the (d+2)(2d+3)/6 term. When the defence
	 * roll can exceed the attack roll, every d past attRoll misses outright and
	 * contributes 1.
	 */
	private static double sharedDefenceMissChance(int attRoll, int defRoll)
	{
		final double a = attRoll;
		final double d = defRoll;
		if (defRoll <= attRoll)
		{
			return (d + 2.0) * (2.0 * d + 3.0) / (6.0 * (a + 1.0) * (a + 1.0));
		}
		final double capped = (a + 2.0) * (2.0 * a + 3.0) / (6.0 * (a + 1.0));
		return (capped + (d - a)) / (d + 1.0);
	}

	private boolean isFangEquipped()
	{
		final int weapon = weaponItemId();
		return weapon == ItemID.OSMUMTENS_FANG || weapon == ItemID.OSMUMTENS_FANG_ORNAMENT;
	}

	private double meleeAccuracyPrayer()
	{
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_DECIMATE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_STRENGTH))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
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
		if (type == AttackType.MAGIC)
		{
			// The shadow multiplies the magic accuracy of everything else worn.
			total *= gearBonuses.shadowMultiplier();
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

	/**
	 * Max hit for the current loadout against this target, or 0 if unavailable.
	 * The target matters because salve, dragon hunter, demonbane and the rest
	 * only apply against the monsters they are meant for; pass -1 for the figure
	 * before any target-dependent gear effects.
	 */
	int maxHit(int npcId)
	{
		return (int) (baseMaxHit() * gearBonus(npcId).getDamage());
	}

	/** The equipped weapon's special attack, or null if it has none that hits. */
	SpecialAttack specialAttack()
	{
		return SpecialAttack.forItem(weaponItemId());
	}

	/**
	 * The most one special attack activation can deal against this target, or 0
	 * when the weapon has no damaging spec. Multi-hit specs are totalled.
	 *
	 * <p>Two weapons can't be expressed as a fixed multiple of the normal max
	 * hit and are computed here instead: the abyssal bludgeon scales with the
	 * prayer points the player is currently missing, and the volatile nightmare
	 * staff ignores the normal hit entirely in favour of a magic-level curve.
	 */
	int specialAttackMaxHit(int npcId)
	{
		final int weapon = weaponItemId();
		if (weapon == ItemID.ABYSSAL_BLUDGEON)
		{
			final int missingPrayer = Math.max(0,
				client.getRealSkillLevel(Skill.PRAYER) - client.getBoostedSkillLevel(Skill.PRAYER));
			return (int) (maxHit(npcId) * (1.0 + 0.005 * missingPrayer));
		}
		if (weapon == ItemID.NIGHTMARE_STAFF_VOLATILE || weapon == ItemID.DEADMAN_BLIGHTED_VOLATILE_STAFF)
		{
			final int magic = client.getBoostedSkillLevel(Skill.MAGIC);
			// The spec is its own attack, not a spell, so no ancient bonus applies.
			return applyMagicDamage(Math.min(58, 58 * magic / 99 + 1), null);
		}
		final SpecialAttack spec = specialAttack();
		return spec == null ? 0 : spec.maxTotal(maxHit(npcId));
	}

	private int baseMaxHit()
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
			return applyMagicDamage(base, null);
		}
		final Spell spell = autocastSpell();
		return spell == null ? 0 : applyMagicDamage(spell.getBaseMaxHit(), spell);
	}

	/**
	 * Scales a base magic hit by the summed magic damage % of the worn gear
	 * (occult, tormented, ancestral, ...), which Tumeken's Shadow multiplies.
	 * Not modelled: the tomes' elemental bonuses and the smoke staff's
	 * standard-spell bonus, so those setups still read low.
	 */
	private int applyMagicDamage(int baseMaxHit, Spell spell)
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return baseMaxHit;
		}
		// Magic damage is a float: several items carry fractions of a percent, so
		// summing into an int would truncate each one away.
		double percent = 0;
		for (Item item : equipment.getItems())
		{
			final ItemStats stats = itemManager.getItemStats(item.getId());
			if (stats != null && stats.getEquipment() != null)
			{
				percent += stats.getEquipment().getMdmg();
			}
		}
		percent += gearBonuses.virtusAncientDamagePercent(spell);
		// The shadow's multiplied magic damage is capped at 100%.
		final int multiplier = gearBonuses.shadowMultiplier();
		percent *= multiplier;
		if (multiplier > 1)
		{
			percent = Math.min(100.0, percent);
		}
		// Prayer magic damage is not worn equipment, so the shadow doesn't
		// multiply it and the equipment cap doesn't apply to it.
		percent += magicDamagePrayerPercent();
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
		return equippedItemId(EquipmentInventorySlot.WEAPON);
	}

	private int equippedItemId(EquipmentInventorySlot slot)
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return -1;
		}
		final Item item = equipment.getItem(slot.getSlotIdx());
		return item == null ? -1 : item.getId();
	}

	private double meleePrayer()
	{
		if (prayerActive(Prayer.RP_DECIMATE))
		{
			return 1.27;
		}
		if (prayerActive(Prayer.RP_ANCIENT_STRENGTH))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
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

	/**
	 * Magic attack prayer multiplier.
	 *
	 * <p>The Ruinous Powers are checked first. They are a separate prayer book,
	 * so they cannot be active alongside the standard ones, and Intensify is
	 * mutually exclusive with the other offensive Ruinous prayers.
	 */
	private double magicAccuracyPrayer()
	{
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_VAPORISE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_WILL))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.AUGURY))
		{
			return 1.25;
		}
		if (prayerActive(Prayer.MYSTIC_VIGOUR))
		{
			return 1.18;
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

	/**
	 * Magic damage granted by prayer, in percent. Unlike the other styles, some
	 * magic prayers add damage as well as accuracy.
	 */
	private double magicDamagePrayerPercent()
	{
		if (prayerActive(Prayer.RP_VAPORISE))
		{
			return 4.0;
		}
		if (prayerActive(Prayer.RP_ANCIENT_WILL))
		{
			return 3.0;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 2.0;
		}
		if (prayerActive(Prayer.MYSTIC_VIGOUR))
		{
			return 3.0;
		}
		return 0.0;
	}

	/** Ranged attack prayer multiplier. Rigour gives less here than it does to strength. */
	private double rangedAccuracyPrayer()
	{
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_ANNIHILATE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_SIGHT))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.RIGOUR))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.DEADEYE))
		{
			return 1.18;
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
		if (prayerActive(Prayer.RP_ANNIHILATE))
		{
			return 1.27;
		}
		if (prayerActive(Prayer.RP_ANCIENT_SIGHT))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.RIGOUR))
		{
			return 1.23;
		}
		if (prayerActive(Prayer.DEADEYE))
		{
			return 1.18;
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
