package com.pvmperformance;

import lombok.Getter;

// The offensive prayer a player intends to hold up, chosen in the config
// and used as the standard the efficiency figure measures against.
@Getter
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
	MYSTIC_LORE("Mystic Lore", 1.10, 1.0, 1.0),
	MYSTIC_MIGHT("Mystic Might", 1.15, 1.0, 2.0),
	MYSTIC_VIGOUR("Mystic Vigour", 1.18, 1.0, 3.0),
	// Augury gained a 4% magic damage boost in May 2024. Without it the prayer
	// changes only accuracy, and against a target already near 100% it looked
	// like Augury did nothing at all.
	AUGURY("Augury", 1.25, 1.0, 4.0),

	// Ruinous Powers
	ANCIENT_STRENGTH("Ancient Strength", 1.20, 1.20),
	ANCIENT_SIGHT("Ancient Sight", 1.20, 1.20),
	ANCIENT_WILL("Ancient Will", 1.20, 1.0, 3.0),
	TRINITAS("Trinitas", 1.15, 1.15, 2.0),
	DECIMATE("Decimate", 1.30, 1.27),
	ANNIHILATE("Annihilate", 1.30, 1.27),
	VAPORISE("Vaporise", 1.30, 1.0, 4.0),
	INTENSIFY("Intensify", 1.50, 1.0);

	private final String displayName;
	private final double attackMultiplier;
	private final double strengthMultiplier;
	// Magic damage, as a percentage added to the worn total rather than a
	// multiplier. Only a few prayers give any; for the rest this is zero.
	private final double magicDamagePercent;

	PrayerChoice(String displayName, double attackMultiplier, double strengthMultiplier)
	{
		this(displayName, attackMultiplier, strengthMultiplier, 0.0);
	}

	PrayerChoice(String displayName, double attackMultiplier, double strengthMultiplier,
		double magicDamagePercent)
	{
		this.displayName = displayName;
		this.attackMultiplier = attackMultiplier;
		this.strengthMultiplier = strengthMultiplier;
		this.magicDamagePercent = magicDamagePercent;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
