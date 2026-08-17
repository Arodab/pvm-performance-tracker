package com.pvmperformance;

/**
 * Which spellbook a combat spell belongs to. Only needed to tell standard
 * casting apart from the rest, which the harmonised staff's cast speed depends
 * on.
 *
 * <p>Ported from the LlemonDuck dps-calculator (BSD-2), (c) Paul Norton.
 */
enum Spellbook
{
	STANDARD,
	ANCIENT,
	ARCEUUS
}
