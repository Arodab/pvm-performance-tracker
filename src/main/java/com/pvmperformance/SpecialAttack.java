package com.pvmperformance;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import net.runelite.api.gameval.ItemID;

// Weapons whose special attack changes the max hit, and by how much.
//
// The second constructor argument after the name is the ATTACK ROLL multiplier,
// not a hit chance: the wiki's "attack roll modifier" of 100% doubles the roll,
// which is worth much less than double the accuracy once the roll is already
// well clear of the target's defence. CombatCalc feeds it through attackRoll()
// for that reason rather than scaling the finished chance.
enum SpecialAttack
{
	// Melee
	DRAGON_DAGGER(new int[]{ItemID.DRAGON_DAGGER, ItemID.DRAGON_DAGGER_P,
		ItemID.DRAGON_DAGGER_P_, ItemID.DRAGON_DAGGER_P__}, "Puncture", 23, 20, 1.15, 1.15),
	// Each claw hit is half the one before, with the last two equal. The claws
	// carry no attack roll modifier but roll accuracy up to four times, which
	// specAverageHit models rather than this table - see cascadingAccuracy.
	DRAGON_CLAWS(ItemID.DRAGON_CLAWS, "Slice and Dice", 1, 1, 1.0, 0.5, 0.25, 0.25),
	// Burning claws, whose gameval name is BONE_CLAWS - matched on the id (29577),
	// which is why this was missing for so long. Burning barrage is three hits
	// with a cascade of its own: the maxima below are the first outcome's
	// 25-25-50 split of a total reaching 175% of the max hit. Unlike the dragon
	// claws each outcome has its own damage RANGE rather than just a ceiling, so
	// CombatCalc models it separately again.
	BURNING_CLAWS(ItemID.BONE_CLAWS, "Burning barrage", 1, 1, 0.4375, 0.4375, 0.875),
	DRAGON_WARHAMMER(ItemID.DRAGON_WARHAMMER, "Smash", 1, 1, 1.5),
	ARMADYL_GODSWORD(ItemID.AGS, "Judgement", 2, 1, 1.375),
	BANDOS_GODSWORD(ItemID.BGS, "Warstrike", 2, 1, 1.21),
	SARADOMIN_GODSWORD(ItemID.SGS, "Healing Blade", 2, 1, 1.1),
	ZAMORAK_GODSWORD(ItemID.ZGS, "Ice Cleave", 2, 1, 1.1),
	ABYSSAL_DAGGER(ItemID.ABYSSAL_DAGGER, "Abyssal Puncture", 5, 4, 0.85, 0.85),
	BARRELCHEST_ANCHOR(ItemID.BRAIN_ANCHOR, "Sunder", 2, 1, 1.1),
	// The second halberd hit only lands on targets big enough to be hit twice.
	DRAGON_HALBERD(ItemID.DRAGON_HALBERD, "Sweep", 1, 1, 1.1, 1.1),
	CRYSTAL_HALBERD(ItemID.CRYSTAL_HALBERD, "Sweep", 1, 1, 1.1, 1.1),
	// Disrupt is a guaranteed hit rolling between 50% and 150% of the normal
	// max, so it expects a FULL max hit rather than half of the 1.5 below.
	VOIDWAKER(ItemID.VOIDWAKER, "Voidwaker", 1, 1, 1.5),
	// Wrath of Amascut: doubles accuracy and adds 25% damage, raid only.
	KERIS_CORRUPTION(ItemID.KERIS_PARTISAN_CORRUPTION, "Wrath of Amascut", 2, 1, 1.25),
	BLESSED_SWORD(new int[]{ItemID.BLESSED_SARADOMIN_SWORD,
		ItemID.BLESSED_SARADOMIN_SWORD_DEGRADED}, "Saradomin's Lightning", 1, 1, 1.25),
	// Eviscerate carries no damage multiplier: it lifts the fang's own narrowed
	// roll back to the true max rather than raising the max, so it is a multiple
	// of the figure before the passive rather than after it, and CombatCalc has
	// it. Its other half is the 50% attack roll modifier, which is here.
	OSMUMTENS_FANG(new int[]{ItemID.OSMUMTENS_FANG, ItemID.OSMUMTENS_FANG_ORNAMENT}, "Eviscerate", 3, 2),

	// Ranged
	// Dark bow figures are for dragon arrows; other arrows spec for 1.3x.
	DARK_BOW(new int[]{ItemID.DARKBOW, ItemID.DARKBOW_GREEN, ItemID.DARKBOW_BLUE,
		ItemID.DARKBOW_YELLOW, ItemID.DARKBOW_WHITE}, "Descent of Darkness", 1, 1, 1.5, 1.5),
	MAGIC_SHORTBOW(new int[]{ItemID.MAGIC_SHORTBOW, ItemID.MAGIC_SHORTBOW_I}, "Snapshot", 143, 100, 1.0, 1.0),
	WEBWEAVER_BOW(ItemID.WILD_CAVE_WEBWEAVER_CHARGED, "Swarm", 2, 1, 0.6, 0.6, 0.6, 0.6),

	// Magic
	// These three carry no damage multipliers: their spec damage is not a
	// multiple of the normal max hit, so CombatCalc computes it. The bludgeon
	// scales with missing prayer points and the volatile staff off the magic
	// level.
	ABYSSAL_BLUDGEON(ItemID.ABYSSAL_BLUDGEON, "Penance", 1, 1),
	VOLATILE_STAFF(new int[]{ItemID.NIGHTMARE_STAFF_VOLATILE,
		ItemID.DEADMAN_BLIGHTED_VOLATILE_STAFF}, "Immolate", 3, 2),
	// The Dawnbringer, whose gameval name is the room it belongs to rather than
	// the staff - matched on the id, not the name. Pulsate is a flat 75-150 that
	// cannot miss: wiki, "magic damage gear bonuses, prayer and base magic
	// level/boosts do not affect the damage", and Mod Ash confirms there is no
	// accuracy roll either. It is also the one hit Verzik's first phase does not
	// cap, which is why ignoresDamageCap exists.
	DAWNBRINGER(ItemID.VERZIK_SPECIAL_WEAPON, "Pulsate", 1, 1),

	// ---------------------------------------------------------------------
	// Added 2026-08-26, after an audit against the wiki's Special attacks page
	// found the table held 21 of the 73 specials that modify a roll. Every id
	// below was resolved from the wiki's item id and looked up by NUMBER in the
	// gameval dump: a good half of these names are not what they look like -
	// the dragon sword is DRAGON_SHORTSWORD, the armadyl crossbow is ACB, the
	// dogsword is ECHO_GODSWORD, the sunlight spear is WEAPON_OF_SOL, Seercull
	// is DAGANOTH_CAVE_MAGIC_SHORTBOW and the blue moon spear is FROSTMOON_SPEAR.
	// ---------------------------------------------------------------------

	// Melee
	ANCIENT_GODSWORD(ItemID.ANCIENT_GODSWORD, "Blood Sacrifice", 2, 1, 1.10),
	ARKAN_BLADE(ItemID.ARKAN_BLADE, "Flames of Ralos", 3, 2, 1.50),
	BRINE_SABRE(ItemID.OLAF2_BRINE_SABRE, "Liquify", 2, 1, 1.0),
	DRAGON_MACE(ItemID.DRAGON_MACE, "Shatter", 5, 4, 1.50),
	DRAGON_SWORD(ItemID.DRAGON_SHORTSWORD, "Wild Stab", 5, 4, 1.25),
	DRAGON_LONGSWORD(ItemID.DRAGON_LONGSWORD, "Cleave", 1, 1, 1.25),
	DRAGON_SCIMITAR(ItemID.DRAGON_SCIMITAR, "Sever", 5, 4, 1.0),
	ELDER_MAUL(ItemID.ELDER_MAUL, "Pulverize", 5, 4, 1.0),
	ABYSSAL_WHIP(ItemID.ABYSSAL_WHIP, "Energy Drain", 5, 4, 1.0),
	ABYSSAL_TENTACLE(ItemID.ABYSSAL_TENTACLE, "Binding Tentacle", 5, 4, 1.0),
	DUAL_MACUAHUITL(ItemID.DUAL_MACUAHUITL, "Blood Infusion", 1, 1, 1.25),
	// The melee hit only; Saradomin's Lightning also splashes for magic damage
	// that is not a multiple of this max hit and is not counted.
	SARADOMIN_SWORD(ItemID.SARADOMIN_SWORD, "Saradomin's Lightning", 1, 1, 1.10),
	THE_DOGSWORD(ItemID.ECHO_GODSWORD, "Echo slash", 2, 1, 1.375),
	// "Roll (up to max roll -5) + 5", so the max is unchanged and the floor is a
	// flat five rather than a share - see flatMinimum.
	GRANITE_HAMMER(ItemID.GRANITE_HAMMER, "Hammer Blow", 3, 2, 1.0),
	// Quick Smash modifies neither roll; it is an instant attack. Here so that a
	// spec still books as a spec rather than falling through to the ordinary hit.
	GRANITE_MAUL(ItemID.GRANITE_MAUL, "Quick Smash", 1, 1, 1.0),
	STATIUS_WARHAMMER(ItemID.STATIUS_WARHAMMER, "Smash", 1, 1, 1.50),
	VESTAS_LONGSWORD(ItemID.VESTAS_LONGSWORD, "Feint", 1, 1, 1.20),
	// Behead scales its attack roll 12-60% and its minimum hit 6-30% with the
	// souls stacked on the axe. The stack is not readable, so both are modelled
	// at the BOTTOM of their range - understating a stacked axe rather than
	// overstating an empty one.
	SOULREAPER_AXE(ItemID.SOULREAPER, "Behead", 28, 25, 1.0),
	SUNLIGHT_SPEAR(ItemID.WEAPON_OF_SOL, "Sol Slam", 1, 1, 1.0),
	// Seeking Lunge also raises the attack roll 70%, but only while the target is
	// below a health threshold, so only the damage half is counted here.
	SUNSPEAR(ItemID.SUNSPEAR, "Seeking Lunge", 1, 1, 1.70),
	// Virulence's floor is the damage the poison it cures would have dealt,
	// which is not readable, so only the attack it makes is counted.
	NOXIOUS_HALBERD(ItemID.NOXIOUS_HALBERD, "Virulence", 1, 1, 1.0),
	DINHS_BULWARK(ItemID.DINHS_BULWARK, "Shield Bash", 6, 5, 1.0),
	// Four accuracy rolls, and unlike the claws the damage depends on how many
	// SUCCEEDED rather than which was first - a binomial, not a cascade.
	CRIMSON_KISTEN(ItemID.CRIMSON_KISTEN, "Brutal Swing", 1, 1, 1.70),
	// Break Shackles scales with the target's remaining bind ticks, which is not
	// readable, so it is scored as an ordinary attack that happens to be a spec.
	BLUE_MOON_SPEAR(ItemID.FROSTMOON_SPEAR, "Break Shackles", 1, 1, 1.0),
	// Bear Down's extra 20 damage arrives over six seconds as its own hitsplats,
	// so it is left out for the same reason burning claws' burn is.
	URSINE_CHAINMACE(ItemID.WILD_CAVE_URSINE_CHARGED, "Bear Down", 2, 1, 1.0),
	KERIS_SUN(ItemID.KERIS_PARTISAN_SUN, "Tumeken's Light", 1, 1, 1.0),

	// Ranged
	TOXIC_BLOWPIPE(new int[]{ItemID.TOXIC_BLOWPIPE,
		ItemID.TOXIC_BLOWPIPE_LOADED}, "Toxic Siphon", 2, 1, 1.50),
	ZARYTE_CROSSBOW(ItemID.ZARYTE_XBOW, "Evoke", 2, 1, 1.0),
	ARMADYL_CROSSBOW(ItemID.ACB, "Armadyl Eye", 2, 1, 1.0),
	DRAGON_CROSSBOW(ItemID.XBOWS_CROSSBOW_DRAGON, "Annihilate", 1, 1, 1.20),
	LIGHT_BALLISTA(new int[]{ItemID.LIGHT_BALLISTA, ItemID.HEAVY_BALLISTA}, "Concentrated Shot", 5, 4, 1.25),
	DRAGON_THROWNAXE(ItemID.DRAGON_THROWNAXE, "Momentum Throw", 5, 4, 1.0),
	RUNE_THROWNAXE(ItemID.RUNE_THROWNAXE, "Chainhit", 1, 1, 1.0),
	ECLIPSE_ATLATL(ItemID.ECLIPSE_ATLATL, "Eclipse", 3, 2, 1.0),
	// Powershot and Soulshot cannot miss, but both roll damage IGNORING void and
	// prayer, which this max hit already includes. Scored at the ordinary max, so
	// they read a little high wherever those are up.
	MAGIC_LONGBOW(new int[]{ItemID.MAGIC_LONGBOW, ItemID.TRAIL_COMPOSITE_BOW_MAGIC}, "Powershot", 1, 1, 1.0),
	SEERCULL(ItemID.DAGANOTH_CAVE_MAGIC_SHORTBOW, "Soulshot", 1, 1, 1.0),
	DRAGON_KNIFE(new int[]{ItemID.DRAGON_KNIFE, ItemID.DRAGON_KNIFE_P,
		ItemID.DRAGON_KNIFE_P_, ItemID.DRAGON_KNIFE_P__}, "Duality", 1, 1, 1.0, 1.0),
	SCORCHING_BOW(ItemID.SCORCHING_BOW, "Scorching shackles", 1, 1, 1.0),
	MORRIGANS_THROWNAXE(ItemID.MORRIGANS_THROWNAXE, "Hamstring", 3, 2, 1.50),
	MORRIGANS_JAVELIN(ItemID.MORRIGANS_JAVELIN, "Phantom Strike", 3, 2, 1.0),
	TONALZTICS_OF_RALOS(ItemID.TONALZTICS_OF_RALOS_CHARGED, "Division", 3, 2, 0.75, 0.75),
	// Both roll their damage off the wielder's Defence rather than an attack
	// style, and both are flat - see fixedMax.
	DRAGONFIRE_SHIELD(new int[]{ItemID.DRAGONFIRE_SHIELD, ItemID.DRAGONFIRE_WARD}, "Dragonfire", 1, 1),
	ANCIENT_WYVERN_SHIELD(ItemID.WYVERN_SHIELD, "Frozen breath", 1, 1),

	// Magic
	EYE_OF_AYAK(ItemID.EYE_OF_AYAK, "Soul Rend", 2, 1, 1.30),
	ACCURSED_SCEPTRE(ItemID.WILD_CAVE_ACCURSED_CHARGED, "Condemn", 3, 2, 1.0),
	PURGING_STAFF(ItemID.PURGING_STAFF, "Scatter ashes", 1, 1, 1.0),
	// Invocate scales off the magic level like the volatile staff, and its own
	// max is worked out in CombatCalc for the same reason.
	ELDRITCH_STAFF(ItemID.NIGHTMARE_STAFF_ELDRITCH, "Invocate", 1, 1);

	private static final Map<Integer, SpecialAttack> BY_ITEM = new HashMap<>();

	static
	{
		for (SpecialAttack spec : values())
		{
			for (int id : spec.itemIds)
			{
				BY_ITEM.put(id, spec);
			}
		}
	}

	private final int[] itemIds;
	@Getter
	private final String displayName;
	// The attack roll modifier as a RATIONAL rather than a double. The game's
	// own arithmetic is integer, so the roll is scaled as roll * n / d against
	// the finished roll, which is exact where a double multiply can land a point
	// either side of it after truncation. Same lesson as fangMaxHit, which is
	// written max - max * 15 / 100 for the same reason.
	private final int attackRollNumerator;
	private final int attackRollDenominator;
	private final double[] hitMultipliers;

	SpecialAttack(int itemId, String displayName, int attackRollNumerator,
		int attackRollDenominator, double... hitMultipliers)
	{
		this(new int[]{itemId}, displayName, attackRollNumerator, attackRollDenominator,
			hitMultipliers);
	}

	/**
	 * One special, every item that carries it. A weapon's poisoned, ornamented
	 * and recoloured variants are the same special with different ids, and
	 * listing them here rather than as constants of their own means a new
	 * variant is an edit to one line instead of an entry that can silently
	 * disagree with its siblings. Same shape as RuneLite's own SpecialWeapon.
	 */
	SpecialAttack(int[] itemIds, String displayName, int attackRollNumerator,
		int attackRollDenominator, double... hitMultipliers)
	{
		this.itemIds = itemIds;
		this.displayName = displayName;
		this.attackRollNumerator = attackRollNumerator;
		this.attackRollDenominator = attackRollDenominator;
		this.hitMultipliers = hitMultipliers;
	}

	/**
	 * This special's attack roll, scaled from the ordinary one with integer
	 * arithmetic. Applied to the FINISHED roll rather than folded in with the
	 * gear multiplier, because that is the order the game works in.
	 */
	int scaleAttackRoll(int attackRoll)
	{
		return attackRoll / attackRollDenominator * attackRollNumerator
			+ attackRoll % attackRollDenominator * attackRollNumerator / attackRollDenominator;
	}

	/** Every item carrying this special. Package-private for the table's tests. */
	int[] getItemIds()
	{
		return itemIds;
	}

	/**
	 * What the special multiplies the attack roll by, as a plain figure. For
	 * display and for the table's own invariants; {@link #scaleAttackRoll} is
	 * what the arithmetic uses.
	 */
	double getAttackRollMultiplier()
	{
		return (double) attackRollNumerator / attackRollDenominator;
	}

	/**
	 * Whether the special connects regardless of the accuracy roll, which makes
	 * the multiplier above moot.
	 *
	 * <p>Two of them: the Voidwaker's Disrupt is a true strike, and Mod Ash
	 * confirmed the Dawnbringer's Pulsate rolls no accuracy at all.
	 */
	boolean alwaysHits()
	{
		switch (this)
		{
			case VOIDWAKER:
			case DAWNBRINGER:
			case MAGIC_LONGBOW:
			case SEERCULL:
			case SUNLIGHT_SPEAR:
				return true;
			default:
				return false;
		}
	}

	/**
	 * The share of a hit's own max that its damage roll cannot fall below.
	 *
	 * <p>Nearly every special rolls from zero like an ordinary attack, so this
	 * is zero for all but the Voidwaker: Disrupt rolls between 50% and 150% of
	 * the normal max, which against the 1.5x max in the table above is a floor
	 * of a third of it. That floor is worth as much as the guaranteed hit -
	 * without it the spec expects three quarters of a max hit where it actually
	 * expects a whole one.
	 */
	double minFraction()
	{
		switch (this)
		{
			case VOIDWAKER:
				return 1.0 / 3.0;
			// Pulsate's 75 floor against its 150 ceiling.
			case DAWNBRINGER:
				return 0.5;
			// Behead's floor at an empty axe; it reaches 30% on a full stack,
			// which is not readable.
			case SOULREAPER_AXE:
				return 0.06;
			// Hamstring and Feint both roll from below their normal max: -50% to
			// +50% and -80% to +20% of it.
			case MORRIGANS_THROWNAXE:
				return 1.0 / 3.0;
			case VESTAS_LONGSWORD:
				return 1.0 / 6.0;
			default:
				return 0.0;
		}
	}

	/**
	 * A floor on the damage roll in hitpoints rather than as a share of the max.
	 *
	 * <p>Only the granite hammer, whose Hammer Blow rolls "up to max roll -5,
	 * plus 5" - a flat five whatever the max hit is, which {@link #minFraction}
	 * cannot express.
	 */
	int flatMinimum()
	{
		return this == GRANITE_HAMMER ? 5 : 0;
	}

	/**
	 * The top of a flat damage roll that owes nothing to the player's max hit,
	 * or 0 when the special's damage is built from it.
	 *
	 * <p>Pulsate is 75-150 and the three dragonfire shields roll from zero, off
	 * the wielder's Defence rather than any attack style - which is why none of
	 * them takes a gear multiplier or an accuracy roll.
	 */
	int fixedMax()
	{
		switch (this)
		{
			case DAWNBRINGER:
				return 150;
			case DRAGONFIRE_SHIELD:
				return 25;
			case ANCIENT_WYVERN_SHIELD:
				return 15;
			default:
				return 0;
		}
	}

	/**
	 * Whether every hit rolls accuracy independently and the number that SUCCEED
	 * decides the damage, rather than the first to succeed deciding it.
	 *
	 * <p>Only the crimson kisten. Wiki: four rolls, and one success deals 70-110%
	 * of the max hit, two 90-130%, three 110-150% and four 130-170%. That is a
	 * binomial rather than the claws' cascade, so it needs its own arithmetic.
	 */
	boolean binomialAccuracy()
	{
		return this == CRIMSON_KISTEN;
	}

	/**
	 * Whether this special ignores a per-hitsplat damage cap.
	 *
	 * <p>Only Pulsate, and only one cap exists that it could meet: Verzik's
	 * first phase holds every other hitsplat to ten or three and lets this one
	 * through, which is the whole point of carrying the staff into the room.
	 */
	boolean ignoresDamageCap()
	{
		return this == DAWNBRINGER;
	}

	/**
	 * Whether the damage is fixed rather than built from the player's max hit,
	 * so no gear multiplier, mitigation or damage bonus touches it.
	 */
	boolean hasFixedDamage()
	{
		return fixedMax() > 0;
	}

	/**
	 * Whether accuracy is rolled once per hit until one connects, rather than
	 * once for the activation.
	 *
	 * <p>Only the claws. Wiki: they have no attack roll modifier but roll
	 * accuracy up to four times, and how many rolls it took decides the damage -
	 * the first roll spreads a doubled max over four hitsplats, the second three
	 * quarters of it over three, and so on down to a quarter on one. Four
	 * failures still deal 0 or 2. That is a different shape from every other
	 * special here, so {@code CombatCalc} models it rather than this table.
	 */
	boolean cascadingAccuracy()
	{
		return this == DRAGON_CLAWS || this == BURNING_CLAWS;
	}

	/**
	 * Whether the activation's hits share one accuracy roll rather than rolling
	 * separately.
	 *
	 * <p>Only the abyssal dagger. Wiki: "If the first roll is true or false, so
	 * is the second". That leaves the expected damage alone - both hits still
	 * land a quarter of the time each - but it does change the chance of landing
	 * anything at all, which is what the Hits line counts.
	 */
	boolean sharedAccuracyRoll()
	{
		return this == ABYSSAL_DAGGER;
	}

	/** The spec for an equipped weapon, or null if it has none that changes the max hit. */
	static SpecialAttack forItem(int itemId)
	{
		return BY_ITEM.get(itemId);
	}

	int hits()
	{
		return Math.max(1, hitMultipliers.length);
	}

	/**
	 * Whether this special's damage is a multiple of the normal max hit. False
	 * for the three whose damage is not - the fang, the bludgeon and the
	 * volatile staff - which CombatCalc works out on its own.
	 */
	boolean hasDamageMultipliers()
	{
		return hitMultipliers.length > 0;
	}

	/**
	 * The per-hitsplat maxima of one activation against a target with this
	 * normal max hit. A special with no damage multipliers of its own is one
	 * hit at the max the caller worked out, which is how the fang, the bludgeon
	 * and the volatile staff arrive here.
	 */
	int[] hitMaxima(int normalMaxHit)
	{
		if (hitMultipliers.length == 0)
		{
			return new int[]{normalMaxHit};
		}
		final int[] maxima = new int[hitMultipliers.length];
		for (int hit = 0; hit < hitMultipliers.length; hit++)
		{
			maxima[hit] = (int) (normalMaxHit * hitMultipliers[hit]);
		}
		return maxima;
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
