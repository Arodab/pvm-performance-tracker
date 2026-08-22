package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

// The combat options each weapon category offers, keyed by the value of
// VarbitID.COMBAT_WEAPON_CATEGORY. Together with the COM_MODE varplayer
// this identifies exactly which combat option the player has selected,
// rather than guessing it from the weapon's bonuses.
// Ported from the LlemonDuck dps-calculator (BSD-2), (c) Paul Norton, with
// entries corrected against RuneLite's own attack styles plugin (BSD-2).
@RequiredArgsConstructor
enum WeaponCategory
{
	UNARMED(0, "Unarmed", Arrays.asList(
		new AttackStyle(0, "Punch", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Kick", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.CRUSH, CombatStyle.DEFENSIVE)
	)),
	AXE(1, "Axe", Arrays.asList(
		new AttackStyle(0, "Chop", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Hack", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Smash", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	BLUNT(2, "Blunt", Arrays.asList(
		new AttackStyle(0, "Pound", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Pummel", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.CRUSH, CombatStyle.DEFENSIVE)
	)),
	BOW(3, "Bow", Arrays.asList(
		new AttackStyle(0, "Accurate", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(1, "Rapid", AttackType.RANGED, CombatStyle.RAPID),
		new AttackStyle(3, "Longrange", AttackType.RANGED, CombatStyle.LONGRANGE)
	)),
	CLAW(4, "Claw", Arrays.asList(
		new AttackStyle(0, "Chop", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Slash", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Lunge", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	CROSSBOW(5, "Crossbow", Arrays.asList(
		new AttackStyle(0, "Accurate", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(1, "Rapid", AttackType.RANGED, CombatStyle.RAPID),
		new AttackStyle(3, "Longrange", AttackType.RANGED, CombatStyle.LONGRANGE)
	)),
	SALAMANDER(6, "Salamander", Arrays.asList(
		new AttackStyle(0, "Scorch", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(1, "Flare", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(2, "Blaze", AttackType.MAGIC, CombatStyle.DEFENSIVE)
	)),
	CHINCHOMPAS(7, "Chinchompas", Arrays.asList(
		new AttackStyle(0, "Short fuse", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(1, "Medium fuse", AttackType.RANGED, CombatStyle.RAPID),
		new AttackStyle(3, "Long fuse", AttackType.RANGED, CombatStyle.LONGRANGE)
	)),
	SLASH_SWORD(9, "Slash Sword", Arrays.asList(
		new AttackStyle(0, "Chop", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Slash", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Lunge", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	TWO_HANDED_SWORD(10, "2h Sword", Arrays.asList(
		new AttackStyle(0, "Chop", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Slash", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Smash", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	PICKAXE(11, "Pickaxe", Arrays.asList(
		new AttackStyle(0, "Spike", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Impale", AttackType.STAB, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Smash", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	POLEARM(12, "Polearm", Arrays.asList(
		new AttackStyle(0, "Jab", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(1, "Swipe", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Fend", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	POLESTAFF(13, "Polestaff", Arrays.asList(
		new AttackStyle(0, "Bash", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.CRUSH, CombatStyle.DEFENSIVE)
	)),
	SCYTHE(14, "Scythe", Arrays.asList(
		new AttackStyle(0, "Reap", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Chop", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Jab", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	SPEAR(15, "Spear", Arrays.asList(
		new AttackStyle(0, "Lunge", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(1, "Swipe", AttackType.SLASH, CombatStyle.CONTROLLED),
		new AttackStyle(2, "Pound", AttackType.CRUSH, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	SPIKED(16, "Spiked", Arrays.asList(
		new AttackStyle(0, "Pound", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Pummel", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Spike", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.CRUSH, CombatStyle.DEFENSIVE)
	)),
	STAB_SWORD(17, "Stab Sword", Arrays.asList(
		new AttackStyle(0, "Stab", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Lunge", AttackType.STAB, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Slash", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	STAFF(18, "Staff", Arrays.asList(
		new AttackStyle(0, "Bash", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Focus", AttackType.CRUSH, CombatStyle.DEFENSIVE),
		new AttackStyle(4, "Spell", AttackType.MAGIC, CombatStyle.AUTOCAST),
		new AttackStyle(5, "Spell (defensive)", AttackType.MAGIC, CombatStyle.AUTOCAST)
	)),
	THROWN(19, "Thrown", Arrays.asList(
		new AttackStyle(0, "Accurate", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(1, "Rapid", AttackType.RANGED, CombatStyle.RAPID),
		new AttackStyle(3, "Longrange", AttackType.RANGED, CombatStyle.LONGRANGE)
	)),
	WHIP(20, "Whip", Arrays.asList(
		new AttackStyle(0, "Flick", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Lash", AttackType.SLASH, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Deflect", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	BLADED_STAFF(21, "Bladed Staff", Arrays.asList(
		new AttackStyle(0, "Jab", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Swipe", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Fend", AttackType.CRUSH, CombatStyle.DEFENSIVE),
		new AttackStyle(4, "Spell", AttackType.MAGIC, CombatStyle.AUTOCAST),
		new AttackStyle(5, "Spell (defensive)", AttackType.MAGIC, CombatStyle.AUTOCAST)
	)),
	POWERED_STAFF(24, "Powered Staff", Arrays.asList(
		new AttackStyle(0, "Accurate", AttackType.MAGIC, CombatStyle.ACCURATE),
		new AttackStyle(1, "Accurate", AttackType.MAGIC, CombatStyle.ACCURATE),
		new AttackStyle(3, "Longrange", AttackType.MAGIC, CombatStyle.LONGRANGE)
	)),
	BANNER(25, "Banner", Arrays.asList(
		new AttackStyle(0, "Lunge", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Swipe", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Pound", AttackType.CRUSH, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	BLUDGEON(27, "Bludgeon", Arrays.asList(
		new AttackStyle(0, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(1, "Pummel", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Smash", AttackType.CRUSH, CombatStyle.AGGRESSIVE)
	)),
	BULWARK(28, "Bulwark", Collections.singletonList(
		new AttackStyle(0, "Pummel", AttackType.CRUSH, CombatStyle.ACCURATE)
	)),
	PARTISAN(30, "Partisan", Arrays.asList(
		new AttackStyle(0, "Stab", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Lunge", AttackType.STAB, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	));

	private static final Map<Integer, WeaponCategory> BY_VARBIT = new HashMap<>();
	private static final Map<String, WeaponCategory> BY_NAME = new HashMap<>();

	static
	{
		for (WeaponCategory category : values())
		{
			BY_VARBIT.put(category.varbitValue, category);
			BY_NAME.put(category.gameName.toLowerCase(), category);
		}
	}

	@Getter
	private final int varbitValue;

	/** What the combat tab calls this category. */
	@Getter
	private final String gameName;

	@Getter
	private final List<AttackStyle> attackStyles;

	/** The category for a COMBAT_WEAPON_CATEGORY varbit value, or null if unmapped. */
	static WeaponCategory forVarbit(int varbitValue)
	{
		return BY_VARBIT.get(varbitValue);
	}

	/**
	 * The category the combat tab names, or null if it isn't one of these. This
	 * is the preferred way in: the name comes from the game and so cannot drift,
	 * whereas the varbit ids have already been renumbered once underneath this
	 * table, powered staff moved from 23 to 24, which made every trident
	 * resolve as a banner and be calculated as a melee weapon.
	 */
	static WeaponCategory forName(String gameName)
	{
		if (gameName == null)
		{
			return null;
		}
		// The tab shows it as "Category: Whip".
		final int colon = gameName.indexOf(':');
		final String trimmed = (colon < 0 ? gameName : gameName.substring(colon + 1)).trim();
		return BY_NAME.get(trimmed.toLowerCase());
	}

	/** The option selected by a COM_MODE varplayer value, or null if it isn't one of ours. */
	AttackStyle styleFor(int varpValue)
	{
		for (AttackStyle style : attackStyles)
		{
			if (style.getVarpValue() == varpValue)
			{
				return style;
			}
		}
		return null;
	}
}
