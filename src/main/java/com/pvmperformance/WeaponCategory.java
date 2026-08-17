package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The combat options each weapon category offers, keyed by the value of
 * {@code VarbitID.COMBAT_WEAPON_CATEGORY}. Together with the COM_MODE
 * varplayer this identifies exactly which combat option the player has
 * selected, rather than guessing it from the weapon's bonuses.
 *
 * <p>Ported from the LlemonDuck dps-calculator (BSD-2), (c) Paul Norton, which
 * in turn follows the wiki's Module:CombatStyles. Three entries were corrected
 * against the table in RuneLite's own attack styles plugin (BSD-2), which is
 * derived from the game cache:
 *
 * <ul>
 *   <li>{@link #POLESTAFF} is category 13, not 18 — 18 is {@link #STAFF}, and
 *       the two collided.</li>
 *   <li>{@link #BANNER}'s third option is controlled, not aggressive.</li>
 *   <li>{@link #PARTISAN}'s options had placeholder varp values and styles.</li>
 * </ul>
 *
 * <p>Categories the game has but this table does not (8, 22, 25, 28, 30, ...)
 * are handled by the fallback in {@link CombatCalc}.
 */
@RequiredArgsConstructor
enum WeaponCategory
{
	UNARMED(0, Arrays.asList(
		new AttackStyle(0, "Punch", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Kick", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.CRUSH, CombatStyle.DEFENSIVE)
	)),
	AXE(1, Arrays.asList(
		new AttackStyle(0, "Chop", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Hack", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Smash", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	BLUNT(2, Arrays.asList(
		new AttackStyle(0, "Pound", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Pummel", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.CRUSH, CombatStyle.DEFENSIVE)
	)),
	BOW(3, Arrays.asList(
		new AttackStyle(0, "Accurate", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(1, "Rapid", AttackType.RANGED, CombatStyle.RAPID),
		new AttackStyle(3, "Longrange", AttackType.RANGED, CombatStyle.LONGRANGE)
	)),
	CLAW(4, Arrays.asList(
		new AttackStyle(0, "Chop", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Slash", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Lunge", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	CROSSBOW(5, Arrays.asList(
		new AttackStyle(0, "Accurate", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(1, "Rapid", AttackType.RANGED, CombatStyle.RAPID),
		new AttackStyle(3, "Longrange", AttackType.RANGED, CombatStyle.LONGRANGE)
	)),
	SALAMANDER(6, Arrays.asList(
		new AttackStyle(0, "Scorch", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(1, "Flare", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(2, "Blaze", AttackType.MAGIC, CombatStyle.DEFENSIVE)
	)),
	CHINCHOMPAS(7, Arrays.asList(
		new AttackStyle(0, "Short fuse", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(1, "Medium fuse", AttackType.RANGED, CombatStyle.RAPID),
		new AttackStyle(3, "Long fuse", AttackType.RANGED, CombatStyle.LONGRANGE)
	)),
	SLASH_SWORD(9, Arrays.asList(
		new AttackStyle(0, "Chop", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Slash", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Lunge", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	TWO_HANDED_SWORD(10, Arrays.asList(
		new AttackStyle(0, "Chop", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Slash", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Smash", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	PICKAXE(11, Arrays.asList(
		new AttackStyle(0, "Spike", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Impale", AttackType.STAB, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Smash", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	POLEARM(12, Arrays.asList(
		new AttackStyle(0, "Jab", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(1, "Swipe", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Fend", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	POLESTAFF(13, Arrays.asList(
		new AttackStyle(0, "Bash", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.CRUSH, CombatStyle.DEFENSIVE)
	)),
	SCYTHE(14, Arrays.asList(
		new AttackStyle(0, "Reap", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Chop", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Jab", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	SPEAR(15, Arrays.asList(
		new AttackStyle(0, "Lunge", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(1, "Swipe", AttackType.SLASH, CombatStyle.CONTROLLED),
		new AttackStyle(2, "Pound", AttackType.CRUSH, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	SPIKED(16, Arrays.asList(
		new AttackStyle(0, "Pound", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Pummel", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Spike", AttackType.STAB, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.CRUSH, CombatStyle.DEFENSIVE)
	)),
	STAB_SWORD(17, Arrays.asList(
		new AttackStyle(0, "Stab", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Lunge", AttackType.STAB, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Slash", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	STAFF(18, Arrays.asList(
		new AttackStyle(0, "Bash", AttackType.CRUSH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Focus", AttackType.CRUSH, CombatStyle.DEFENSIVE),
		new AttackStyle(4, "Spell", AttackType.MAGIC, CombatStyle.AUTOCAST),
		new AttackStyle(5, "Spell (defensive)", AttackType.MAGIC, CombatStyle.AUTOCAST)
	)),
	THROWN(19, Arrays.asList(
		new AttackStyle(0, "Accurate", AttackType.RANGED, CombatStyle.ACCURATE),
		new AttackStyle(1, "Rapid", AttackType.RANGED, CombatStyle.RAPID),
		new AttackStyle(3, "Longrange", AttackType.RANGED, CombatStyle.LONGRANGE)
	)),
	WHIP(20, Arrays.asList(
		new AttackStyle(0, "Flick", AttackType.SLASH, CombatStyle.ACCURATE),
		new AttackStyle(1, "Lash", AttackType.SLASH, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Deflect", AttackType.SLASH, CombatStyle.DEFENSIVE)
	)),
	BLADED_STAFF(21, Arrays.asList(
		new AttackStyle(0, "Jab", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Swipe", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Fend", AttackType.CRUSH, CombatStyle.DEFENSIVE),
		new AttackStyle(4, "Spell", AttackType.MAGIC, CombatStyle.AUTOCAST),
		new AttackStyle(5, "Spell (defensive)", AttackType.MAGIC, CombatStyle.AUTOCAST)
	)),
	POWERED_STAFF(23, Arrays.asList(
		new AttackStyle(0, "Accurate", AttackType.MAGIC, CombatStyle.ACCURATE),
		new AttackStyle(1, "Accurate", AttackType.MAGIC, CombatStyle.ACCURATE),
		new AttackStyle(3, "Longrange", AttackType.MAGIC, CombatStyle.LONGRANGE)
	)),
	BANNER(24, Arrays.asList(
		new AttackStyle(0, "Lunge", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Swipe", AttackType.SLASH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Pound", AttackType.CRUSH, CombatStyle.CONTROLLED),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	)),
	BLUDGEON(26, Arrays.asList(
		new AttackStyle(0, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(1, "Pummel", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Smash", AttackType.CRUSH, CombatStyle.AGGRESSIVE)
	)),
	BULWARK(27, Collections.singletonList(
		new AttackStyle(0, "Pummel", AttackType.CRUSH, CombatStyle.ACCURATE)
	)),
	PARTISAN(29, Arrays.asList(
		new AttackStyle(0, "Stab", AttackType.STAB, CombatStyle.ACCURATE),
		new AttackStyle(1, "Lunge", AttackType.STAB, CombatStyle.AGGRESSIVE),
		new AttackStyle(2, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE),
		new AttackStyle(3, "Block", AttackType.STAB, CombatStyle.DEFENSIVE)
	));

	private static final Map<Integer, WeaponCategory> BY_VARBIT = new HashMap<>();

	static
	{
		for (WeaponCategory category : values())
		{
			BY_VARBIT.put(category.varbitValue, category);
		}
	}

	@Getter
	private final int varbitValue;

	@Getter
	private final List<AttackStyle> attackStyles;

	/** The category for a COMBAT_WEAPON_CATEGORY varbit value, or null if unmapped. */
	static WeaponCategory forVarbit(int varbitValue)
	{
		return BY_VARBIT.get(varbitValue);
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
