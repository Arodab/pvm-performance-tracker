package com.pvmperformance;

/**
 * The enchanted crossbow bolts whose effects change expected damage, with their
 * PvM activation rates. The hard Kandarin diary raises every rate by a tenth of
 * itself.
 *
 * <p>Only the three that matter for boss damage are listed. Opal, pearl and
 * dragonstone add a small flat hit whose formula depends on the ranged level and
 * the target, and the rest of the enchantments are utility rather than damage.
 *
 * <p>The effects differ in shape, which is why the damage maths lives in
 * {@code CombatCalc} rather than here: ruby replaces the hit outright with a
 * share of the target's health, diamond raises the max and skips the accuracy
 * roll, and onyx only adds damage to a hit that already landed.
 */
enum EnchantedBolt
{
	/** Blood forfeit: 20% of the target's current health, capped, ignoring accuracy. */
	RUBY(0.06),
	/** Armour piercing: 15% higher max and a guaranteed hit. */
	DIAMOND(0.10),
	/** Life leech: 20% more damage, but not against the undead. */
	ONYX(0.11);

	private static final int RUBY_DAMAGE_CAP = 100;

	private final double baseChance;

	EnchantedBolt(double baseChance)
	{
		this.baseChance = baseChance;
	}

	double chance(boolean kandarinHardDiary)
	{
		return kandarinHardDiary ? baseChance * 1.1 : baseChance;
	}

	static int rubyDamage(int targetCurrentHp)
	{
		return Math.min(RUBY_DAMAGE_CAP, targetCurrentHp / 5);
	}

	/**
	 * The bolt loaded in the ammo slot, or null if it isn't one of these. Matches
	 * on name so the plain and dragon variants of each are both covered.
	 */
	static EnchantedBolt forAmmoName(String ammoName)
	{
		if (ammoName == null || !ammoName.endsWith("bolts (e)"))
		{
			return null;
		}
		if (ammoName.startsWith("Ruby"))
		{
			return RUBY;
		}
		if (ammoName.startsWith("Diamond"))
		{
			return DIAMOND;
		}
		return ammoName.startsWith("Onyx") ? ONYX : null;
	}
}
