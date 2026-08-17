package com.pvmperformance;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import net.runelite.api.gameval.ItemID;

/**
 * Weapons whose special attack changes the max hit, and by how much.
 *
 * <p>Each entry lists one multiplier per hit the spec lands, applied to the
 * weapon's normal max hit. Most multi-hit specs repeat the same figure, but
 * dragon claws deal a decaying sequence, which is why this is a list rather
 * than a multiplier and a count.
 *
 * <p>Deliberately excluded, because their spec damage is not a fixed multiple
 * of the normal max hit and a single number here would be wrong: the abyssal
 * bludgeon (scales with missing prayer points), the volatile nightmare staff
 * (scales off the magic level directly), and the granite maul (an extra attack
 * that does not change the hit). Accuracy-only specs are also left out, since
 * they move the hit chance rather than the max hit.
 */
enum SpecialAttack
{
	// Melee
	DRAGON_DAGGER(ItemID.DRAGON_DAGGER, "Puncture", 1.15, 1.15),
	DRAGON_DAGGER_P(ItemID.DRAGON_DAGGER_P, "Puncture", 1.15, 1.15),
	DRAGON_DAGGER_P_PLUS(ItemID.DRAGON_DAGGER_P_, "Puncture", 1.15, 1.15),
	DRAGON_DAGGER_P_PLUS_PLUS(ItemID.DRAGON_DAGGER_P__, "Puncture", 1.15, 1.15),
	// Each claw hit is half the one before, with the last two equal.
	DRAGON_CLAWS(ItemID.DRAGON_CLAWS, "Slice and Dice", 1.0, 0.5, 0.25, 0.25),
	DRAGON_WARHAMMER(ItemID.DRAGON_WARHAMMER, "Smash", 1.5),
	ARMADYL_GODSWORD(ItemID.AGS, "Judgement", 1.375),
	BANDOS_GODSWORD(ItemID.BGS, "Warstrike", 1.21),
	SARADOMIN_GODSWORD(ItemID.SGS, "Healing Blade", 1.1),
	ZAMORAK_GODSWORD(ItemID.ZGS, "Ice Cleave", 1.1),
	ABYSSAL_DAGGER(ItemID.ABYSSAL_DAGGER, "Abyssal Puncture", 0.85, 0.85),
	BARRELCHEST_ANCHOR(ItemID.BRAIN_ANCHOR, "Sunder", 1.1),
	// The second halberd hit only lands on targets big enough to be hit twice.
	DRAGON_HALBERD(ItemID.DRAGON_HALBERD, "Sweep", 1.1, 1.1),
	CRYSTAL_HALBERD(ItemID.CRYSTAL_HALBERD, "Sweep", 1.1, 1.1),
	VOIDWAKER(ItemID.VOIDWAKER, "Voidwaker", 1.5),
	BLESSED_SWORD(ItemID.BLESSED_SARADOMIN_SWORD, "Saradomin's Lightning", 1.25),
	BLESSED_SWORD_DEGRADED(ItemID.BLESSED_SARADOMIN_SWORD_DEGRADED, "Saradomin's Lightning", 1.25),

	// Ranged
	// Dark bow figures are for dragon arrows; other arrows spec for 1.3x.
	DARK_BOW(ItemID.DARKBOW, "Descent of Darkness", 1.5, 1.5),
	DARK_BOW_GREEN(ItemID.DARKBOW_GREEN, "Descent of Darkness", 1.5, 1.5),
	DARK_BOW_BLUE(ItemID.DARKBOW_BLUE, "Descent of Darkness", 1.5, 1.5),
	DARK_BOW_YELLOW(ItemID.DARKBOW_YELLOW, "Descent of Darkness", 1.5, 1.5),
	DARK_BOW_WHITE(ItemID.DARKBOW_WHITE, "Descent of Darkness", 1.5, 1.5),
	MAGIC_SHORTBOW(ItemID.MAGIC_SHORTBOW, "Snapshot", 1.0, 1.0),
	MAGIC_SHORTBOW_I(ItemID.MAGIC_SHORTBOW_I, "Snapshot", 1.0, 1.0),
	WEBWEAVER_BOW(ItemID.WILD_CAVE_WEBWEAVER_CHARGED, "Swarm", 0.6, 0.6, 0.6, 0.6);

	private static final Map<Integer, SpecialAttack> BY_ITEM = new HashMap<>();

	static
	{
		for (SpecialAttack spec : values())
		{
			BY_ITEM.put(spec.itemId, spec);
		}
	}

	private final int itemId;
	@Getter
	private final String displayName;
	private final double[] hitMultipliers;

	SpecialAttack(int itemId, String displayName, double... hitMultipliers)
	{
		this.itemId = itemId;
		this.displayName = displayName;
		this.hitMultipliers = hitMultipliers;
	}

	/** The spec for an equipped weapon, or null if it has none that changes the max hit. */
	static SpecialAttack forItem(int itemId)
	{
		return BY_ITEM.get(itemId);
	}

	int hits()
	{
		return hitMultipliers.length;
	}

	/** The most the whole activation can deal against a target with this max hit. */
	int maxTotal(int normalMaxHit)
	{
		int total = 0;
		for (double multiplier : hitMultipliers)
		{
			total += (int) (normalMaxHit * multiplier);
		}
		return total;
	}
}
