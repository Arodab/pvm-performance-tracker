package com.pvmperformance;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

// Resolves the gear effects that multiply the attack roll and the max hit.
// Multipliers and stacking rules follow the LlemonDuck dps-calculator
// (BSD-2), (c) Paul Norton, with the newer weapons and the raid-specific
// rules taken from the wiki.
@Singleton
class GearBonusCalc
{
	private static final GearBonus SALVE_16 = GearBonus.symmetric(7.0 / 6.0);
	private static final GearBonus SALVE_15 = GearBonus.symmetric(1.15);
	private static final GearBonus SALVE_20 = GearBonus.symmetric(6.0 / 5.0);
	private static final GearBonus BLACK_MASK_MELEE = GearBonus.symmetric(7.0 / 6.0);
	private static final GearBonus BLACK_MASK_RANGED_MAGIC = GearBonus.symmetric(1.15);

	/**
	 * Demons whose demonbane effectiveness isn't 100%, scaling how much of a
	 * demonbane weapon's bonus actually lands. Keyed by monster name.
	 */
	private static final Map<String, Double> DEMONBANE_EFFECTIVENESS = new HashMap<>();

	static
	{
		DEMONBANE_EFFECTIVENESS.put("Duke Sucellus", 0.70);
		DEMONBANE_EFFECTIVENESS.put("Ice demon", 1.15);
		DEMONBANE_EFFECTIVENESS.put("Yama", 1.20);
		DEMONBANE_EFFECTIVENESS.put("Void Flare", 2.00);
	}

	private final Client client;
	private final ItemManager itemManager;

	@Inject
	GearBonusCalc(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/**
	 * The combined multipliers for the current loadout against this target.
	 * A null target skips the effects that depend on what is being fought.
	 */
	GearBonus compute(AttackStyle style, MonsterStatsProvider.MonsterStats npc, Spell spell, boolean onSlayerTask)
	{
		final Loadout gear = snapshot();
		if (gear == null)
		{
			return GearBonus.NONE;
		}
		final AttackType type = style.getAttackType();

		GearBonus total = GearBonus.NONE;

		// Salve and the slayer helm deliberately do not stack; salve takes priority.
		final GearBonus salve = salveBonus(type, npc, gear);
		final GearBonus blackMask = salve.isNone() ? blackMaskBonus(type, gear, onSlayerTask) : GearBonus.NONE;
		final GearBonus dragonHunter = dragonHunterBonus(type, npc, gear);
		final GearBonus demonbane = demonbaneBonus(type, npc, spell, gear);

		total = total.combine(salve);
		// The dragon hunter crossbow and scorching bow add their damage to the
		// black mask's rather than multiplying by it, so those pairs are folded
		// together before joining the rest.
		if (!blackMask.isNone() && stacksAdditivelyWithBlackMask(gear))
		{
			total = total.combine(addDamage(blackMask, dragonHunter.combine(demonbane)));
		}
		else
		{
			total = total.combine(blackMask).combine(dragonHunter).combine(demonbane);
		}

		total = total.combine(voidBonus(type, gear));
		total = total.combine(crystalBonus(gear));
		total = total.combine(inquisitorsBonus(type, gear));
		total = total.combine(obsidianBonus(type, gear));
		total = total.combine(dharoksBonus(type, gear));
		total = total.combine(avariceBonus(npc, gear));
		total = total.combine(kerisBonus(type, npc, gear));
		total = total.combine(twistedBowBonus(type, npc, gear));
		total = total.combine(tomeBonus(type, spell, gear));
		total = total.combine(smokeStaffBonus(type, spell, gear));
		total = total.combine(revenantBonus(type, gear));
		total = total.combine(leafyBonus(type, npc, spell, gear));
		total = total.combine(vampyreBaneBonus(type, npc, gear));
		total = total.combine(ahrimsBonus(style, gear));
		total = total.combine(chinchompaBonus(style, gear));
		return total;
	}

	private GearBonus salveBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		final String amulet = gear.name(EquipmentInventorySlot.AMULET);
		if (amulet == null || !amulet.startsWith("Salve amulet"))
		{
			return GearBonus.NONE;
		}
		if (npc == null || !npc.hasAttribute("undead"))
		{
			return GearBonus.NONE;
		}
		// "(e)" and "(ei)" are enhanced; "(i)" and "(ei)" are imbued. Only the
		// imbued versions do anything at all for ranged and magic, so plain
		// enhanced is 20% on melee and nothing elsewhere.
		final boolean enhanced = amulet.contains("(e");
		final boolean imbued = amulet.contains("i)");
		if (type.isMelee())
		{
			return enhanced ? SALVE_20 : SALVE_16;
		}
		if (!imbued)
		{
			return GearBonus.NONE;
		}
		if (enhanced)
		{
			return SALVE_20;
		}
		return type == AttackType.MAGIC ? SALVE_15 : SALVE_16;
	}

	private GearBonus blackMaskBonus(AttackType type, Loadout gear, boolean onSlayerTask)
	{
		if (!onSlayerTask)
		{
			return GearBonus.NONE;
		}
		final String head = gear.name(EquipmentInventorySlot.HEAD);
		if (head == null || !(head.startsWith("Black mask") || head.toLowerCase().contains("slayer helmet")))
		{
			return GearBonus.NONE;
		}
		if (type.isMelee())
		{
			return BLACK_MASK_MELEE;
		}
		// Only the imbued versions do anything for ranged and magic.
		return head.endsWith("(i)") ? BLACK_MASK_RANGED_MAGIC : GearBonus.NONE;
	}

	private GearBonus voidBonus(AttackType type, Loadout gear)
	{
		final String body = gear.name(EquipmentInventorySlot.BODY);
		final String legs = gear.name(EquipmentInventorySlot.LEGS);
		final String gloves = gear.name(EquipmentInventorySlot.GLOVES);
		final String head = gear.name(EquipmentInventorySlot.HEAD);
		if (body == null || legs == null || gloves == null || head == null)
		{
			return GearBonus.NONE;
		}
		final boolean elite = body.startsWith("Elite void top") && legs.startsWith("Elite void robe");
		final boolean regular = body.startsWith("Void knight top") && legs.startsWith("Void knight robe");
		if ((!elite && !regular) || !gloves.startsWith("Void knight gloves"))
		{
			return GearBonus.NONE;
		}
		// The helm has to match the style being used for the set to do anything.
		switch (type)
		{
			case MAGIC:
				// Regular mage void is accuracy only; elite adds 5% damage.
				return head.startsWith("Void mage helm")
					? GearBonus.of(1.45, elite ? 1.05 : 1.0) : GearBonus.NONE;
			case RANGED:
				return head.startsWith("Void ranger helm")
					? GearBonus.of(1.1, elite ? 1.125 : 1.1) : GearBonus.NONE;
			default:
				return head.startsWith("Void melee helm")
					? GearBonus.symmetric(1.1) : GearBonus.NONE;
		}
	}

	/** Crystal armour only does anything alongside a crystal bow or bow of faerdhinen. */
	private GearBonus crystalBonus(Loadout gear)
	{
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		if (weapon == null || weapon.contains("inactive")
			|| !(weapon.equals("Crystal bow") || weapon.startsWith("Bow of faerdhinen")))
		{
			return GearBonus.NONE;
		}
		double accuracy = 0.0;
		// The uncharged pieces share the name with "(inactive)" appended, so an
		// exact match is what excludes them.
		if ("Crystal helm".equals(gear.name(EquipmentInventorySlot.HEAD)))
		{
			accuracy += 0.05;
		}
		if ("Crystal body".equals(gear.name(EquipmentInventorySlot.BODY)))
		{
			accuracy += 0.15;
		}
		if ("Crystal legs".equals(gear.name(EquipmentInventorySlot.LEGS)))
		{
			accuracy += 0.10;
		}
		// No set bonus: each piece stands alone, and damage gains half of accuracy.
		return accuracy == 0.0 ? GearBonus.NONE : GearBonus.of(1.0 + accuracy, 1.0 + accuracy / 2.0);
	}

	private GearBonus inquisitorsBonus(AttackType type, Loadout gear)
	{
		if (type != AttackType.CRUSH)
		{
			return GearBonus.NONE;
		}
		// The July 2026 update folded the old separate set effect into the
		// hauberk and plateskirt, so the pieces are now purely additive: 0.5%
		// for the helm and 1% each for the other two, 2.5% for all three.
		double bonus = 0.0;
		if (gear.id(EquipmentInventorySlot.HEAD) == ItemID.INQUISITORS_HELM)
		{
			bonus += 0.005;
		}
		if (gear.id(EquipmentInventorySlot.BODY) == ItemID.INQUISITORS_BODY)
		{
			bonus += 0.01;
		}
		if (gear.id(EquipmentInventorySlot.LEGS) == ItemID.INQUISITORS_SKIRT)
		{
			bonus += 0.01;
		}
		return bonus == 0.0 ? GearBonus.NONE : GearBonus.symmetric(1.0 + bonus);
	}

	private GearBonus obsidianBonus(AttackType type, Loadout gear)
	{
		if (!type.isMelee() || !isObsidianWeapon(gear.id(EquipmentInventorySlot.WEAPON)))
		{
			return GearBonus.NONE;
		}
		GearBonus total = GearBonus.NONE;
		if (gear.id(EquipmentInventorySlot.HEAD) == ItemID.OBSIDIAN_HELMET
			&& gear.id(EquipmentInventorySlot.BODY) == ItemID.OBSIDIAN_PLATEBODY
			&& gear.id(EquipmentInventorySlot.LEGS) == ItemID.OBSIDIAN_PLATELEGS)
		{
			total = total.combine(GearBonus.symmetric(1.1));
		}
		if (gear.id(EquipmentInventorySlot.AMULET) == ItemID.JEWL_BESERKER_NECKLACE)
		{
			total = total.combine(GearBonus.of(1.0, 1.2));
		}
		return total;
	}

	private static boolean isObsidianWeapon(int weapon)
	{
		return weapon == ItemID.TZHAAR_SPLITSWORD
			|| weapon == ItemID.TZHAAR_MACE
			|| weapon == ItemID.TZHAAR_MAUL
			|| weapon == ItemID.TZHAAR_MAUL_T;
	}

	private GearBonus dragonHunterBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("dragon"))
		{
			return GearBonus.NONE;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		if (type.isMelee() && weapon == ItemID.DRAGONHUNTER_LANCE)
		{
			return GearBonus.symmetric(1.2);
		}
		if (type == AttackType.RANGED && isDragonHunterCrossbow(weapon))
		{
			return GearBonus.of(1.30, 1.25);
		}
		if (type == AttackType.MAGIC && weapon == ItemID.DRAGONHUNTER_WAND)
		{
			return GearBonus.of(1.50, 1.20);
		}
		return GearBonus.NONE;
	}

	private static boolean isDragonHunterCrossbow(int weapon)
	{
		return weapon == ItemID.DRAGONHUNTER_XBOW
			|| weapon == ItemID.DRAGONHUNTER_XBOW_KBD
			|| weapon == ItemID.DRAGONHUNTER_XBOW_VORKATH;
	}

	/** Whether the equipped weapon is one that adds to the black mask instead of multiplying. */
	private boolean stacksAdditivelyWithBlackMask(Loadout gear)
	{
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		return isDragonHunterCrossbow(weapon) || "Scorching bow".equals(gear.name(EquipmentInventorySlot.WEAPON));
	}

	/** Sums two damage bonuses rather than multiplying them; accuracy still multiplies. */
	private static GearBonus addDamage(GearBonus a, GearBonus b)
	{
		if (b.isNone())
		{
			return a;
		}
		return GearBonus.of(
			a.getAccuracy() * b.getAccuracy(),
			1.0 + (a.getDamage() - 1.0) + (b.getDamage() - 1.0));
	}

	/**
	 * Dharok's set effect: the lower the wearer's hitpoints, the harder they
	 * hit. Needs the full set, greataxe included.
	 */
	private GearBonus dharoksBonus(AttackType type, Loadout gear)
	{
		if (!type.isMelee())
		{
			return GearBonus.NONE;
		}
		final boolean set = startsWith(gear.name(EquipmentInventorySlot.HEAD), "Dharok's helm")
			&& startsWith(gear.name(EquipmentInventorySlot.BODY), "Dharok's platebody")
			&& startsWith(gear.name(EquipmentInventorySlot.LEGS), "Dharok's platelegs")
			&& startsWith(gear.name(EquipmentInventorySlot.WEAPON), "Dharok's greataxe");
		if (!set)
		{
			return GearBonus.NONE;
		}
		final int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
		final int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		final int missing = Math.max(0, maxHp - currentHp);
		return GearBonus.of(1.0, 1.0 + (missing / 100.0) * (maxHp / 100.0));
	}

	/** Amulet of avarice: 20% against revenants, which carry no attribute tag. */
	private GearBonus avariceBonus(MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !npc.getName().startsWith("Revenant"))
		{
			return GearBonus.NONE;
		}
		return "Amulet of avarice".equals(gear.name(EquipmentInventorySlot.AMULET))
			? GearBonus.symmetric(1.2) : GearBonus.NONE;
	}

	/**
	 * Demonbane weapons and spells. The listed bonus is scaled by the target's
	 * demonbane effectiveness, which a handful of demons alter, Duke Sucellus
	 * resists it at 70% while Yama and the ice demon take more than the full
	 * amount.
	 */
	private GearBonus demonbaneBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Spell spell, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("demon"))
		{
			return GearBonus.NONE;
		}
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		GearBonus raw = GearBonus.NONE;
		if (type.isMelee() && weapon != null)
		{
			if ("Arclight".equals(weapon) || "Emberlight".equals(weapon))
			{
				raw = GearBonus.symmetric(1.7);
			}
			else if ("Burning claws".equals(weapon))
			{
				raw = GearBonus.symmetric(1.05);
			}
			else if ("Darklight".equals(weapon) || "Silverlight".equals(weapon))
			{
				raw = GearBonus.of(1.0, 1.6);
			}
		}
		else if (type == AttackType.RANGED && "Scorching bow".equals(weapon))
		{
			raw = GearBonus.symmetric(1.3);
		}
		else if (type == AttackType.MAGIC && isDemonbaneSpell(spell))
		{
			raw = GearBonus.of(1.2, 1.25);
		}
		return scaleByDemonbaneEffectiveness(raw, npc);
	}

	private static boolean isDemonbaneSpell(Spell spell)
	{
		return spell == Spell.INFERIOR_DEMONBANE
			|| spell == Spell.SUPERIOR_DEMONBANE
			|| spell == Spell.DARK_DEMONBANE;
	}

	/** Scales only the bonus part, so a 70% bonus at 70% effectiveness becomes 49%. */
	private static GearBonus scaleByDemonbaneEffectiveness(GearBonus raw, MonsterStatsProvider.MonsterStats npc)
	{
		if (raw.isNone())
		{
			return raw;
		}
		final double effectiveness = DEMONBANE_EFFECTIVENESS.getOrDefault(npc.getName(), 1.0);
		if (effectiveness == 1.0)
		{
			return raw;
		}
		return GearBonus.of(
			1.0 + (raw.getAccuracy() - 1.0) * effectiveness,
			1.0 + (raw.getDamage() - 1.0) * effectiveness);
	}

	// The keris family deals 33% more damage to kalphites and scabarites, with
	// a 1/51 chance of tripling it. The triple is reachable, so it counts
	// towards the max hit, but it only averages out to a few percent.
	private GearBonus kerisBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("kalphite") || !type.isMelee())
		{
			return GearBonus.NONE;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		if (!isKeris(weapon))
		{
			return GearBonus.NONE;
		}
		final double damage = weapon == ItemID.KERIS_PARTISAN_AMASCUT ? 1.15 : 1.33;
		final double accuracy = weapon == ItemID.KERIS_PARTISAN_BREACH ? 1.33 : 1.0;
		// The crit triples whatever the passive already potted.
		return GearBonus.split(accuracy, damage * 3.0, damage * (1.0 + 2.0 / 51.0));
	}

	private static boolean isKeris(int weapon)
	{
		return weapon == ItemID.CONTACT_KERIS
			|| weapon == ItemID.CONTACT_KERIS_P
			|| weapon == ItemID.CONTACT_KERIS_P_
			|| weapon == ItemID.CONTACT_KERIS_P__
			|| weapon == ItemID.KERIS_PARTISAN
			|| weapon == ItemID.KERIS_PARTISAN_BREACH
			|| weapon == ItemID.KERIS_PARTISAN_CORRUPTION
			|| weapon == ItemID.KERIS_PARTISAN_SUN
			|| weapon == ItemID.KERIS_PARTISAN_AMASCUT;
	}

	// Osmumten's fang has no entry here on purpose: both of its effects need
	// arithmetic a multiplier cannot express, and both live in CombatCalc.

	/**
	 * The twisted bow scales off the target's magic, and turns into a penalty
	 * against low-magic targets. The magic considered is capped at 250, raised
	 * to 350 inside the Chambers of Xeric; the resulting accuracy caps at 140%
	 * and the damage at 250%.
	 */
	private GearBonus twistedBowBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || type != AttackType.RANGED
			|| gear.id(EquipmentInventorySlot.WEAPON) != ItemID.TWISTED_BOW)
		{
			return GearBonus.NONE;
		}
		final int cap = inChambersOfXeric() ? 350 : 250;
		final int magic = Math.min(cap, Math.max(npc.getMagicLevel(), npc.getOffensiveMagic()));
		return GearBonus.of(
			Math.min(1.40, twistedBowFactor(magic, true)),
			Math.min(2.50, twistedBowFactor(magic, false)));
	}

	/** Both curves have the same shape with different constants. */
	private static double twistedBowFactor(int magic, boolean accuracy)
	{
		final double base = accuracy ? 140.0 : 250.0;
		final double sub = accuracy ? 10.0 : 14.0;
		final double linear = (3.0 * magic - sub) / 100.0;
		final double quadratic = Math.pow((3.0 * magic) / 10.0 - (10.0 * sub), 2.0) / 100.0;
		return (base + linear - quadratic) / 100.0;
	}

	// Tome of fire boosts fire spells and tome of water water spells, both
	// from the standard spellbook only.
	private GearBonus tomeBonus(AttackType type, Spell spell, Loadout gear)
	{
		if (type != AttackType.MAGIC || spell == null || spell.getSpellbook() != Spellbook.STANDARD)
		{
			return GearBonus.NONE;
		}
		final String offHand = gear.name(EquipmentInventorySlot.SHIELD);
		if (offHand == null)
		{
			return GearBonus.NONE;
		}
		final String spellName = spell.getDisplayName();
		if ("Tome of fire".equals(offHand) && spellName.startsWith("Fire"))
		{
			return GearBonus.of(1.0, 1.1);
		}
		if ("Tome of water".equals(offHand) && spellName.startsWith("Water"))
		{
			return GearBonus.symmetric(1.1);
		}
		return GearBonus.NONE;
	}

	private GearBonus smokeStaffBonus(AttackType type, Spell spell, Loadout gear)
	{
		if (type != AttackType.MAGIC || spell == null || spell.getSpellbook() != Spellbook.STANDARD)
		{
			return GearBonus.NONE;
		}
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		return "Smoke battlestaff".equals(weapon) || "Mystic smoke staff".equals(weapon)
			? GearBonus.symmetric(1.1) : GearBonus.NONE;
	}

	// The revenant weapons and their upgrades, which add 50% accuracy and
	// damage against any NPC in the Wilderness and nothing outside it.
	private GearBonus revenantBonus(AttackType type, Loadout gear)
	{
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		if (weapon == null)
		{
			return GearBonus.NONE;
		}
		// The sceptre can bash and the mace and bow can manual cast, so the style
		// has to match the weapon for the passive to apply.
		final boolean matches;
		switch (type)
		{
			case MAGIC:
				matches = weapon.startsWith("Thammaron's sceptre") || weapon.startsWith("Accursed sceptre");
				break;
			case RANGED:
				matches = weapon.startsWith("Craw's bow") || weapon.startsWith("Webweaver bow");
				break;
			default:
				matches = weapon.startsWith("Viggora's chainmace") || weapon.startsWith("Ursine chainmace");
				break;
		}
		return matches && inWilderness() ? GearBonus.symmetric(1.5) : GearBonus.NONE;
	}

	/**
	 * Leafy monsters are immune to everything but leaf-bladed weapons, broad
	 * ammo and magic dart, so anything else zeroes the damage rather than
	 * reducing it. Only the leaf-bladed battleaxe carries a bonus on top.
	 */
	private GearBonus leafyBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Spell spell, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("leafy"))
		{
			return GearBonus.NONE;
		}
		switch (type)
		{
			case MAGIC:
				return spell == Spell.MAGIC_DART ? GearBonus.NONE : GearBonus.symmetric(0.0);
			case RANGED:
				// "Broad bolts", "Amethyst broad bolts", "Broad arrows".
				final String ammo = gear.name(EquipmentInventorySlot.AMMO);
				return ammo != null && ammo.toLowerCase().contains("broad")
					? GearBonus.NONE : GearBonus.symmetric(0.0);
			default:
				final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
				if (weapon == null || !weapon.startsWith("Leaf-bladed"))
				{
					return GearBonus.symmetric(0.0);
				}
				return weapon.equals("Leaf-bladed battleaxe") ? GearBonus.symmetric(1.175) : GearBonus.NONE;
		}
	}

	/**
	 * Vampyrebane weapons. The monster data tags vampyres by tier, "vampyre1"
	 * through "vampyre3", rather than with a single "vampyre" tag, and the tier
	 * decides what can hurt them: blisterwood works on all three, the ivandis
	 * flail only up to tier 2.
	 */
	private GearBonus vampyreBaneBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !type.isMelee())
		{
			return GearBonus.NONE;
		}
		final boolean tier3 = npc.hasAttribute("vampyre3");
		if (!tier3 && !npc.hasAttribute("vampyre1") && !npc.hasAttribute("vampyre2"))
		{
			return GearBonus.NONE;
		}
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		if (weapon == null)
		{
			return GearBonus.NONE;
		}
		if (weapon.startsWith("Blisterwood flail"))
		{
			return GearBonus.of(1.05, 1.25);
		}
		if (weapon.startsWith("Blisterwood sickle"))
		{
			return GearBonus.of(1.05, 1.15);
		}
		if (weapon.startsWith("Ivandis flail"))
		{
			return tier3 ? GearBonus.NONE : GearBonus.of(1.0, 1.20);
		}
		return GearBonus.NONE;
	}

	/**
	 * Full ahrim's plus the amulet of the damned gives a 25% chance of 30% extra
	 * damage, not a flat 30%, which is what the reference models. Since the
	 * October 2024 update it applies to manual casts as well as autocasts, so
	 * the style is not checked beyond it being magic.
	 */
	private GearBonus ahrimsBonus(AttackStyle style, Loadout gear)
	{
		if (style.getAttackType() != AttackType.MAGIC)
		{
			return GearBonus.NONE;
		}
		final boolean set = startsWith(gear.name(EquipmentInventorySlot.WEAPON), "Ahrim's staff")
			&& startsWith(gear.name(EquipmentInventorySlot.HEAD), "Ahrim's hood")
			&& startsWith(gear.name(EquipmentInventorySlot.BODY), "Ahrim's robetop")
			&& startsWith(gear.name(EquipmentInventorySlot.LEGS), "Ahrim's robeskirt")
			&& startsWith(gear.name(EquipmentInventorySlot.AMULET), "Amulet of the damned");
		return set ? GearBonus.split(1.0, 1.3, 1.0 + 0.25 * 0.3) : GearBonus.NONE;
	}

	/**
	 * Chinchompa accuracy depends on how far the target is: each fuse length is
	 * at its best in one band and worse in the others.
	 */
	private GearBonus chinchompaBonus(AttackStyle style, Loadout gear)
	{
		// "Chinchompa", "Red chinchompa", "Black chinchompa".
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		if (style.getAttackType() != AttackType.RANGED || weapon == null
			|| !weapon.toLowerCase().contains("chinchompa"))
		{
			return GearBonus.NONE;
		}
		final int distance = targetDistance();
		if (distance < 0)
		{
			return GearBonus.NONE;
		}
		// Short band is 1-3 tiles, medium 4-6, long beyond that.
		final int band = distance <= 3 ? 0 : distance <= 6 ? 1 : 2;
		final int fuse = style.getCombatStyle() == CombatStyle.ACCURATE ? 0
			: style.getCombatStyle() == CombatStyle.RAPID ? 1 : 2;
		final double[][] accuracy = {
			{1.0, 0.75, 0.5},
			{0.75, 1.0, 0.75},
			{0.5, 0.75, 1.0},
		};
		return GearBonus.of(accuracy[band][fuse], 1.0);
	}

	/** Tiles between the player and whatever they are attacking, or -1 if nothing. */
	private int targetDistance()
	{
		final Player player = client.getLocalPlayer();
		if (player == null)
		{
			return -1;
		}
		final Actor target = player.getInteracting();
		if (target == null)
		{
			return -1;
		}
		return player.getWorldLocation().distanceTo(target.getWorldLocation());
	}

	/**
	 * Extra magic damage from Virtus when casting Ancient Magicks: 3% per piece
	 * on top of the 2% each already carries as a plain stat, so a full set is
	 * 15% rather than 6%. Nothing outside the ancient spellbook benefits.
	 */
	double virtusAncientDamagePercent(Spell spell)
	{
		if (spell == null || spell.getSpellbook() != Spellbook.ANCIENT)
		{
			return 0.0;
		}
		final Loadout gear = snapshot();
		if (gear == null)
		{
			return 0.0;
		}
		double percent = 0.0;
		if (isVirtus(gear.id(EquipmentInventorySlot.HEAD), ItemID.VIRTUS_MASK, ItemID.VIRTUS_MASK_ORNAMENT))
		{
			percent += 3.0;
		}
		if (isVirtus(gear.id(EquipmentInventorySlot.BODY), ItemID.VIRTUS_TOP, ItemID.VIRTUS_TOP_ORNAMENT))
		{
			percent += 3.0;
		}
		if (isVirtus(gear.id(EquipmentInventorySlot.LEGS), ItemID.VIRTUS_LEGS, ItemID.VIRTUS_LEGS_ORNAMENT))
		{
			percent += 3.0;
		}
		return percent;
	}

	private static boolean isVirtus(int worn, int plain, int ornament)
	{
		return worn == plain || worn == ornament;
	}

	/**
	 * Tumeken's Shadow multiplies the magic accuracy and magic damage of the
	 * rest of the loadout, by 3 normally and by 4 inside the Tombs of Amascut.
	 * Returns 1 when the shadow isn't equipped.
	 */
	int shadowMultiplier()
	{
		final Loadout gear = snapshot();
		if (gear == null)
		{
			return 1;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		if (weapon != ItemID.TUMEKENS_SHADOW && weapon != ItemID.DEADMAN_BLIGHTED_TUMEKENS_SHADOW)
		{
			return 1;
		}
		return inTombsOfAmascut() ? 4 : 3;
	}

	boolean inChambersOfXeric()
	{
		return client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
	}

	/**
	 * Whether the player is in a Tombs of Amascut raid, read from the raid level
	 * varbit. This also reads set in the lobby, which is harmless: nothing is
	 * being fought there, so no multiplier is applied anyway.
	 */
	// The party status, not the raid level. The level is the raid's difficulty
	// and is never cleared on leaving, so reading it scaled every NPC in the
	// game once a raid had been done - a Guard in a POH read 128 defence
	// against a base of 80. The status is 1 while in the raid and 0 everywhere
	// else, measured in the open world before and after a raid and in a house.
	void updateRaidState()
	{
		// Nothing to latch: the party status answers directly.
	}

	boolean inTombsOfAmascut()
	{
		return client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS) > 0;
	}

	/** The raid level, or 0 when the Tombs are not actually being fought. */
	int tombsRaidLevel()
	{
		return inTombsOfAmascut() ? client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) : 0;
	}

	private boolean inWilderness()
	{
		return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
	}

	private static boolean startsWith(String name, String prefix)
	{
		return name != null && name.startsWith(prefix);
	}

	/** The worn items, resolved once so each effect does not re-read the container. */
	private Loadout snapshot()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		return equipment == null ? null : Loadout.worn(itemManager, equipment);
	}
}
