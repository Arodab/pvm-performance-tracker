package com.pvmperformance;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * Resolves the gear effects that multiply the attack roll and the max hit —
 * salve, slayer helm, void, crystal, dragon hunter, demonbane, inquisitor's,
 * twisted bow, obsidian and keris.
 *
 * <p>Multipliers and stacking rules follow the LlemonDuck dps-calculator
 * (BSD-2), (c) Paul Norton. Target-dependent effects read the wiki attribute
 * tags carried by {@link MonsterStatsProvider.MonsterStats}.
 *
 * <p>Item families with many cosmetic variants (crystal armour, slayer helms,
 * salve amulets, void, bow of faerdhinen) are matched by item name rather than
 * by id. Each recolour is a separate id but shares one name, so a name test is
 * both far shorter and more precise: it distinguishes the tiers that matter
 * (imbued, enhanced, inactive) which the ids do not group.
 *
 * <p>Not modelled: revenant weapons, leafy and vampyre bane weapons, the tomes,
 * ahrim's, chinchompa range falloff, and the scorching bow.
 */
@Singleton
class GearBonusCalc
{
	private static final GearBonus SALVE_UNENHANCED_MELEE_RANGED = GearBonus.symmetric(7.0 / 6.0);
	private static final GearBonus SALVE_UNENHANCED_MAGIC = GearBonus.symmetric(1.15);
	private static final GearBonus SALVE_ENHANCED = GearBonus.symmetric(6.0 / 5.0);
	private static final GearBonus BLACK_MASK_MELEE = GearBonus.symmetric(7.0 / 6.0);
	private static final GearBonus BLACK_MASK_RANGED_MAGIC = GearBonus.symmetric(1.15);

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
	GearBonus compute(AttackType type, MonsterStatsProvider.MonsterStats npc, boolean onSlayerTask)
	{
		final Loadout gear = snapshot();
		if (gear == null)
		{
			return GearBonus.NONE;
		}

		GearBonus total = GearBonus.NONE;

		// Salve and the slayer helm deliberately do not stack; salve takes priority.
		final GearBonus salve = salveBonus(type, npc, gear);
		if (salve.isNone())
		{
			total = total.combine(blackMaskBonus(type, gear, onSlayerTask));
		}
		else
		{
			total = total.combine(salve);
		}

		total = total.combine(voidBonus(type, gear));
		total = total.combine(crystalBonus(gear));
		total = total.combine(inquisitorsBonus(type, gear));
		total = total.combine(obsidianBonus(type, gear));
		total = total.combine(dragonHunterBonus(type, npc, gear));
		total = total.combine(demonbaneBonus(type, npc, gear));
		total = total.combine(kerisBonus(type, npc, gear));
		total = total.combine(twistedBowBonus(type, npc, gear));
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
		// "(e)" and "(ei)" are enhanced; "(i)" and "(ei)" are imbued.
		final boolean enhanced = amulet.contains("(e");
		final boolean imbued = amulet.contains("i)");
		if (enhanced)
		{
			return SALVE_ENHANCED;
		}
		if (type.isMelee())
		{
			return SALVE_UNENHANCED_MELEE_RANGED;
		}
		// Unimbued salve does nothing for ranged or magic.
		if (!imbued)
		{
			return GearBonus.NONE;
		}
		return type == AttackType.MAGIC ? SALVE_UNENHANCED_MAGIC : SALVE_UNENHANCED_MELEE_RANGED;
	}

	private GearBonus blackMaskBonus(AttackType type, Loadout gear, boolean onSlayerTask)
	{
		if (!onSlayerTask)
		{
			return GearBonus.NONE;
		}
		final String head = gear.name(EquipmentInventorySlot.HEAD);
		if (head == null || !(head.startsWith("Black mask") || head.contains("slayer helmet") || head.startsWith("Slayer helmet")))
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
				return head.startsWith("Void mage helm")
					? GearBonus.of(1.45, elite ? 1.025 : 1.0) : GearBonus.NONE;
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
		if (isCrystal(gear.name(EquipmentInventorySlot.HEAD), "Crystal helm"))
		{
			accuracy += 0.05;
		}
		if (isCrystal(gear.name(EquipmentInventorySlot.BODY), "Crystal body"))
		{
			accuracy += 0.15;
		}
		if (isCrystal(gear.name(EquipmentInventorySlot.LEGS), "Crystal legs"))
		{
			accuracy += 0.10;
		}
		// No set bonus: each piece stands alone, and damage gains half of accuracy.
		return accuracy == 0.0 ? GearBonus.NONE : GearBonus.of(1.0 + accuracy, 1.0 + accuracy / 2.0);
	}

	private static boolean isCrystal(String name, String piece)
	{
		// The uncharged pieces share the name with "(inactive)" appended.
		return name != null && name.equals(piece);
	}

	private GearBonus inquisitorsBonus(AttackType type, Loadout gear)
	{
		if (type != AttackType.CRUSH)
		{
			return GearBonus.NONE;
		}
		final boolean helm = gear.id(EquipmentInventorySlot.HEAD) == ItemID.INQUISITORS_HELM;
		final boolean body = gear.id(EquipmentInventorySlot.BODY) == ItemID.INQUISITORS_BODY;
		final boolean legs = gear.id(EquipmentInventorySlot.LEGS) == ItemID.INQUISITORS_SKIRT;
		if (helm && body && legs)
		{
			return GearBonus.symmetric(1.025); // full set beats the per-piece total
		}
		final double bonus = (helm ? 0.005 : 0.0) + (body ? 0.005 : 0.0) + (legs ? 0.005 : 0.0);
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
		if (type == AttackType.RANGED && weapon == ItemID.DRAGONHUNTER_XBOW)
		{
			return GearBonus.of(1.30, 1.25);
		}
		return GearBonus.NONE;
	}

	private GearBonus demonbaneBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("demon") || !type.isMelee())
		{
			return GearBonus.NONE;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		if (weapon == ItemID.ARCLIGHT || weapon == ItemID.EMBERLIGHT)
		{
			return GearBonus.symmetric(1.7);
		}
		final String name = gear.name(EquipmentInventorySlot.WEAPON);
		if ("Darklight".equals(name) || "Silverlight".equals(name))
		{
			return GearBonus.of(1.0, 1.6);
		}
		return GearBonus.NONE;
	}

	private GearBonus kerisBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("kalphite") || !type.isMelee())
		{
			return GearBonus.NONE;
		}
		return isKeris(gear.id(EquipmentInventorySlot.WEAPON)) ? GearBonus.of(1.0, 1.33) : GearBonus.NONE;
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

	/**
	 * The twisted bow scales off the target's magic, and turns into a penalty
	 * against low-magic targets. The magic considered is capped at 250, raised
	 * to 350 inside the Chambers of Xeric.
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
		return GearBonus.of(twistedBowFactor(magic, true), twistedBowFactor(magic, false));
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

	private boolean inChambersOfXeric()
	{
		return client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
	}

	/**
	 * Whether the player is in a Tombs of Amascut raid, read from the raid level
	 * varbit. This also reads set in the lobby, which is harmless: nothing is
	 * being fought there, so no multiplier is applied anyway.
	 */
	private boolean inTombsOfAmascut()
	{
		return client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) > 0;
	}

	private Loadout snapshot()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		return equipment == null ? null : new Loadout(equipment);
	}

	/** The worn items, resolved once so each effect doesn't re-read the container. */
	private final class Loadout
	{
		private final ItemContainer equipment;
		private final String[] names = new String[EquipmentInventorySlot.AMMO.getSlotIdx() + 1];

		private Loadout(ItemContainer equipment)
		{
			this.equipment = equipment;
		}

		private int id(EquipmentInventorySlot slot)
		{
			final Item item = equipment.getItem(slot.getSlotIdx());
			return item == null ? -1 : item.getId();
		}

		private String name(EquipmentInventorySlot slot)
		{
			final int idx = slot.getSlotIdx();
			if (idx < 0 || idx >= names.length)
			{
				return null;
			}
			if (names[idx] == null)
			{
				final int id = id(slot);
				names[idx] = id < 0 ? "" : itemManager.getItemComposition(id).getName();
			}
			return names[idx].isEmpty() ? null : names[idx];
		}
	}
}
