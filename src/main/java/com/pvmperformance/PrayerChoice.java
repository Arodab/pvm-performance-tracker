package com.pvmperformance;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The offensive prayer a player intends to hold up, chosen in the config and
 * used as the standard the efficiency figure measures against.
 *
 * <p>It has to be chosen rather than detected. Which prayers a player has
 * unlocked isn't readable, so the plugin cannot tell whether someone praying
 * Eagle Eye is doing the best they can or forgetting Rigour.
 *
 * <p>Multipliers are the attack and strength ones for the style; they are
 * applied to whichever effective level the style rolls on, so one enum serves
 * all three. Public because the config returns it, and a config proxy cannot
 * reach a package-private type.
 */
@Getter
@RequiredArgsConstructor
public enum PrayerChoice
{
	NONE("None", 1.0, 1.0),

	// Melee
	BURST_OF_STRENGTH("Burst of Strength", 1.0, 1.05),
	CLARITY_OF_THOUGHT("Clarity of Thought", 1.05, 1.0),
	SUPERHUMAN_STRENGTH("Superhuman Strength", 1.0, 1.10),
	IMPROVED_REFLEXES("Improved Reflexes", 1.10, 1.0),
	ULTIMATE_STRENGTH("Ultimate Strength", 1.0, 1.15),
	INCREDIBLE_REFLEXES("Incredible Reflexes", 1.15, 1.0),
	CHIVALRY("Chivalry", 1.15, 1.18),
	PIETY("Piety", 1.20, 1.23),

	// Ranged
	SHARP_EYE("Sharp Eye", 1.05, 1.05),
	HAWK_EYE("Hawk Eye", 1.10, 1.10),
	EAGLE_EYE("Eagle Eye", 1.15, 1.15),
	DEADEYE("Deadeye", 1.18, 1.18),
	RIGOUR("Rigour", 1.20, 1.23),

	// Magic
	MYSTIC_WILL("Mystic Will", 1.05, 1.0),
	MYSTIC_LORE("Mystic Lore", 1.10, 1.0),
	MYSTIC_MIGHT("Mystic Might", 1.15, 1.0),
	MYSTIC_VIGOUR("Mystic Vigour", 1.18, 1.0),
	AUGURY("Augury", 1.25, 1.0),

	// Ruinous Powers
	ANCIENT_STRENGTH("Ancient Strength", 1.20, 1.20),
	ANCIENT_SIGHT("Ancient Sight", 1.20, 1.20),
	ANCIENT_WILL("Ancient Will", 1.20, 1.0),
	TRINITAS("Trinitas", 1.15, 1.15),
	DECIMATE("Decimate", 1.30, 1.27),
	ANNIHILATE("Annihilate", 1.30, 1.27),
	VAPORISE("Vaporise", 1.30, 1.0),
	INTENSIFY("Intensify", 1.50, 1.0);

	private final String displayName;
	private final double attackMultiplier;
	private final double strengthMultiplier;

	@Override
	public String toString()
	{
		return displayName;
	}
}
