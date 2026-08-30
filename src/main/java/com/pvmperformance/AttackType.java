package com.pvmperformance;

// The damage type an attack rolls against: the three melee sub-types plus ranged and magic. Selects which equipment
// attack bonus is used for the attack roll and which of the target's defensive bonuses answers it. Ported from the
// LlemonDuck dps-calculator (BSD-2), (c) Paul Norton.
enum AttackType
{
	STAB,
	SLASH,
	CRUSH,
	MAGIC,
	RANGED;

	boolean isMelee()
	{
		return this == STAB || this == SLASH || this == CRUSH;
	}
}
