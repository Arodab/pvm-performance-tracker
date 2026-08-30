package com.pvmperformance;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

// Combat spells and their base max hits, keyed by the value of
// VarbitID.AUTOCAST_SPELL while the spell is set to autocast.
// Ported from the LlemonDuck dps-calculator (BSD-2), (c) Paul Norton.
@Getter
@RequiredArgsConstructor
enum Spell
{
	// ancient spells
	ICE_BARRAGE(46, "Ice Barrage", 30, Spellbook.ANCIENT),
	BLOOD_BARRAGE(45, "Blood Barrage", 29, Spellbook.ANCIENT),
	SHADOW_BARRAGE(44, "Shadow Barrage", 28, Spellbook.ANCIENT),
	SMOKE_BARRAGE(43, "Smoke Barrage", 27, Spellbook.ANCIENT),
	ICE_BLITZ(42, "Ice Blitz", 26, Spellbook.ANCIENT),
	BLOOD_BLITZ(41, "Blood Blitz", 25, Spellbook.ANCIENT),
	SHADOW_BLITZ(40, "Shadow Blitz", 24, Spellbook.ANCIENT),
	SMOKE_BLITZ(39, "Smoke Blitz", 23, Spellbook.ANCIENT),
	ICE_BURST(38, "Ice Burst", 22, Spellbook.ANCIENT),
	BLOOD_BURST(37, "Blood Burst", 21, Spellbook.ANCIENT),
	SHADOW_BURST(36, "Shadow Burst", 18, Spellbook.ANCIENT),
	SMOKE_BURST(35, "Smoke Burst", 17, Spellbook.ANCIENT),
	ICE_RUSH(34, "Ice Rush", 16, Spellbook.ANCIENT),
	BLOOD_RUSH(33, "Blood Rush", 15, Spellbook.ANCIENT),
	SHADOW_RUSH(32, "Shadow Rush", 14, Spellbook.ANCIENT),
	SMOKE_RUSH(31, "Smoke Rush", 13, Spellbook.ANCIENT),

	// standard spells
	FIRE_SURGE(51, "Fire Surge", 24, Spellbook.STANDARD),
	EARTH_SURGE(50, "Earth Surge", 23, Spellbook.STANDARD),
	WATER_SURGE(49, "Water Surge", 22, Spellbook.STANDARD),
	WIND_SURGE(48, "Wind Surge", 21, Spellbook.STANDARD),
	FIRE_WAVE(16, "Fire Wave", 20, Spellbook.STANDARD),
	EARTH_WAVE(15, "Earth Wave", 19, Spellbook.STANDARD),
	WATER_WAVE(14, "Water Wave", 18, Spellbook.STANDARD),
	WIND_WAVE(13, "Wind Wave", 17, Spellbook.STANDARD),
	FIRE_BLAST(12, "Fire Blast", 16, Spellbook.STANDARD),
	EARTH_BLAST(11, "Earth Blast", 15, Spellbook.STANDARD),
	WATER_BLAST(10, "Water Blast", 14, Spellbook.STANDARD),
	WIND_BLAST(9, "Wind Blast", 13, Spellbook.STANDARD),
	FIRE_BOLT(8, "Fire Bolt", 12, Spellbook.STANDARD),
	EARTH_BOLT(7, "Earth Bolt", 11, Spellbook.STANDARD),
	WATER_BOLT(6, "Water Bolt", 10, Spellbook.STANDARD),
	WIND_BOLT(5, "Wind Bolt", 9, Spellbook.STANDARD),
	FIRE_STRIKE(4, "Fire Strike", 8, Spellbook.STANDARD),
	EARTH_STRIKE(3, "Earth Strike", 6, Spellbook.STANDARD),
	WATER_STRIKE(2, "Water Strike", 4, Spellbook.STANDARD),
	WIND_STRIKE(1, "Wind Strike", 2, Spellbook.STANDARD),

	// standard, but need a specific staff to autocast
	FLAMES_OF_ZAMORAK(20, "Flames of Zamorak", 20, Spellbook.STANDARD),
	CLAWS_OF_GUTHIX(-1, "Claws of Guthix", 20, Spellbook.STANDARD),
	SARADOMIN_STRIKE(-1, "Saradomin Strike", 20, Spellbook.STANDARD),
	CRUMBLE_UNDEAD(17, "Crumble Undead", 15, Spellbook.STANDARD),
	IBAN_BLAST(47, "Iban Blast", 25, Spellbook.STANDARD),
	MAGIC_DART(18, "Magic Dart", 10, Spellbook.STANDARD),

	// arceuus
	INFERIOR_DEMONBANE(53, "Inferior Demonbane", 16, Spellbook.ARCEUUS),
	SUPERIOR_DEMONBANE(54, "Superior Demonbane", 23, Spellbook.ARCEUUS),
	DARK_DEMONBANE(55, "Dark Demonbane", 30, Spellbook.ARCEUUS),
	GHOSTLY_GRASP(56, "Ghostly Grasp", 12, Spellbook.ARCEUUS),
	SKELETAL_GRASP(57, "Skeletal Grasp", 17, Spellbook.ARCEUUS),
	UNDEAD_GRASP(58, "Undead Grasp", 24, Spellbook.ARCEUUS);

	// Elemental spells rose to the strongest of their tier the caster has
	// unlocked, in the May 2024 rework: a wind strike at magic 13 hits for 8, not
	// 2. The ladders are the four unlock levels against the max hit they carry.
	private static final int[] STRIKE_LEVELS = {1, 5, 9, 13};
	private static final int[] STRIKE_HITS = {2, 4, 6, 8};
	private static final int[] BOLT_LEVELS = {17, 23, 29, 35};
	private static final int[] BOLT_HITS = {9, 10, 11, 12};
	private static final int[] BLAST_LEVELS = {41, 47, 53, 59};
	private static final int[] BLAST_HITS = {13, 14, 15, 16};
	private static final int[] WAVE_LEVELS = {62, 65, 70, 75};
	private static final int[] WAVE_HITS = {17, 18, 19, 20};
	private static final int[] SURGE_LEVELS = {81, 85, 90, 95};
	private static final int[] SURGE_HITS = {21, 22, 23, 24};

	/**
	 * Whether this is an elemental spell of the given element, which is what
	 * elemental weakness turns on. Only the standard spellbook's ladders count -
	 * strike, bolt, blast, wave, surge - and the name carries both halves, so no
	 * second table is needed. The ancient spells share none of these endings,
	 * which is what keeps a barrage out of it.
	 */
	boolean isElement(String element)
	{
		if (element == null)
		{
			return false;
		}
		final String name = displayName.toLowerCase(Locale.ROOT);
		return name.startsWith(element.toLowerCase(Locale.ROOT) + " ")
			&& (name.endsWith(" strike") || name.endsWith(" bolt") || name.endsWith(" blast")
			|| name.endsWith(" wave") || name.endsWith(" surge"));
	}

	/** The max hit at this magic level. Only the elemental spells move. */
	int maxHitAt(int magicLevel)
	{
		final int[] levels;
		final int[] hits;
		final String name = displayName;
		if (name.endsWith("Strike") && isElemental(name))
		{
			levels = STRIKE_LEVELS;
			hits = STRIKE_HITS;
		}
		else if (name.endsWith("Bolt") && isElemental(name))
		{
			levels = BOLT_LEVELS;
			hits = BOLT_HITS;
		}
		else if (name.endsWith("Blast") && isElemental(name))
		{
			levels = BLAST_LEVELS;
			hits = BLAST_HITS;
		}
		else if (name.endsWith("Wave") && isElemental(name))
		{
			levels = WAVE_LEVELS;
			hits = WAVE_HITS;
		}
		else if (name.endsWith("Surge") && isElemental(name))
		{
			levels = SURGE_LEVELS;
			hits = SURGE_HITS;
		}
		else
		{
			return baseMaxHit;
		}
		int best = baseMaxHit;
		for (int i = 0; i < levels.length; i++)
		{
			if (magicLevel >= levels[i])
			{
				best = Math.max(best, hits[i]);
			}
		}
		return best;
	}

	// Wind, Water, Earth and Fire only. Saradomin Strike and the god spells
	// share the suffixes but not the mechanic.
	private static boolean isElemental(String name)
	{
		return name.startsWith("Wind ") || name.startsWith("Water ")
			|| name.startsWith("Earth ") || name.startsWith("Fire ");
	}

	private static final Map<Integer, Spell> BY_VARBIT = new HashMap<>();
	private static final Map<String, Spell> BY_DISPLAY_NAME = new HashMap<>();

	static
	{
		for (Spell spell : values())
		{
			if (spell.varbitValue >= 0)
			{
				BY_VARBIT.put(spell.varbitValue, spell);
			}
			BY_DISPLAY_NAME.put(spell.displayName.toLowerCase(Locale.ROOT), spell);
		}
	}

	/** Value of {@code VarbitID.AUTOCAST_SPELL}, or -1 when the spell can't be autocast. */
	private final int varbitValue;

	private final String displayName;

	/** Max hit before magic damage bonuses. */
	private final int baseMaxHit;

	private final Spellbook spellbook;

	/**
	 * Whether this spell reaches its target without a projectile the client
	 * shows. The ancient area spells land on the tick they are cast and give
	 * none, so their attacks are taken from the hitsplat instead.
	 */
	boolean landsWithoutProjectile()
	{
		return displayName.endsWith("Burst") || displayName.endsWith("Barrage");
	}

	/** The autocast spell for a varbit value, or null when nothing is set to autocast. */
	static Spell forVarbit(int varbitValue)
	{
		return BY_VARBIT.get(varbitValue);
	}

	/**
	 * The spell with this name, or null if it is not a combat spell. Reads a
	 * manual cast off the widget clicked, which carries a name but no id.
	 */
	static Spell forDisplayName(String name)
	{
		if (name == null)
		{
			return null;
		}
		return BY_DISPLAY_NAME.get(name.toLowerCase(Locale.ROOT));
	}
}
