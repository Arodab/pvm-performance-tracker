package com.pvmperformance;

// The combat option the player has selected on the combat tab. Determines
// the invisible level boosts applied to the attack and strength rolls, and
// (for rapid) the weapon's attack speed.
// Ported from the LlemonDuck dps-calculator (BSD-2), (c) Paul Norton.
enum CombatStyle
{
	ACCURATE,
	AGGRESSIVE,
	AUTOCAST,
	CONTROLLED,
	DEFENSIVE,
	LONGRANGE,
	RAPID
}
