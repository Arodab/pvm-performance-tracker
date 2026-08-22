package com.pvmperformance;

import net.runelite.api.Skill;

// The melee boost a player means to be holding, chosen in the config and used
// as the standard the efficiency figure measures against.
//
// Raid doses are deliberately absent here and from the other two: an overload
// or a dose of salts is detected from its own timer while it is running, and
// raises the standard on its own. Offering them as a goal would ask the player
// to declare something the game already says.
public enum MeleeBoost
{
	NONE("None")
		{
			@Override
			int boost(Skill skill, int base)
			{
				return 0;
			}
		},

	SUPER_COMBAT("Super combat")
		{
			@Override
			int boost(Skill skill, int base)
			{
				return base * 15 / 100 + 5;
			}
		},

	// Strength only. Attack is left alone, so a player on super strength is not
	// marked down for an attack boost they never meant to take.
	SUPER_STRENGTH("Super strength")
		{
			@Override
			int boost(Skill skill, int base)
			{
				return skill == Skill.STRENGTH ? base * 15 / 100 + 5 : 0;
			}
		};

	private final String displayName;

	MeleeBoost(String displayName)
	{
		this.displayName = displayName;
	}

	abstract int boost(Skill skill, int base);

	@Override
	public String toString()
	{
		return displayName;
	}
}
