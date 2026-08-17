package com.pvmperformance;

import lombok.Getter;

/**
 * The performance of a single fight against one NPC: the measured side from
 * observed facts — damage from {@code hitsplat.isMine()} splats, hits vs zeros
 * for accuracy, damage taken, and wall-clock duration — alongside the expected
 * side sampled from the combat model once per attack made.
 */
@Getter
class Fight
{
	private final String targetName;
	private final int targetId;
	private final int targetIndex;
	// The NPC's max HP (from NPCManager), or -1 if unknown; used to classify bosses.
	private final int maxHp;
	private final long startMillis;

	private long lastActivityMillis;
	private int damageDealt;
	private int damageTaken;
	// Every resolved hit of mine on the target counts as an attempt; a non-zero
	// one counts as a landed hit. accuracy = hits / attempts.
	private int attempts;
	private int hits;
	private boolean ended;
	private boolean targetDied;
	private long endMillis;

	// The expected figures, sampled once per attack made rather than snapshotted
	// once for the fight. With one loadout throughout, the mean is just that
	// loadout's figure; with a spec weapon swapped in partway it is the actual
	// blend of what was wielded, which no single snapshot could represent.
	private double sumExpectedMaxHit;
	private int expectedMaxHitSamples;
	private double sumExpectedAccuracy;
	private double sumExpectedAverageHit;
	// Accuracy and average hit need target data, so they can be missing while the
	// max hit is known, and are counted separately.
	private int expectedTargetSamples;

	// Ticks the weapon was off cooldown but no attack was made.
	private int ticksLost;
	private int firstAttackTick = -1;
	private int lastAttackTick = -1;

	Fight(String targetName, int targetId, int targetIndex, int maxHp, long now)
	{
		this.targetName = targetName == null ? "NPC" : targetName;
		this.targetId = targetId;
		this.targetIndex = targetIndex;
		this.maxHp = maxHp;
		this.startMillis = now;
		this.lastActivityMillis = now;
	}

	void recordDamageDealt(int amount, long now)
	{
		damageDealt += amount;
		attempts++;
		if (amount > 0)
		{
			hits++;
		}
		lastActivityMillis = now;
	}

	/** A magic splash on the target: a missed attempt with no hitsplat. */
	void recordSplash(long now)
	{
		attempts++;
		lastActivityMillis = now;
	}

	void recordDamageTaken(int amount, long now)
	{
		damageTaken += amount;
		if (now > lastActivityMillis)
		{
			lastActivityMillis = now;
		}
	}

	void end(boolean died, long now)
	{
		ended = true;
		targetDied = died;
		endMillis = now;
	}

	long durationMillis()
	{
		final long stop = ended ? endMillis : lastActivityMillis;
		return Math.max(0, stop - startMillis);
	}

	double durationSeconds()
	{
		// Floor at one tick so a one-shot kill doesn't produce an infinite DPS.
		return Math.max(0.6, durationMillis() / 1000.0);
	}

	double dps()
	{
		return damageDealt / durationSeconds();
	}

	/**
	 * Mean damage per attack made, counting misses as zero. This is the figure
	 * the expected side is compared against, since it depends only on the
	 * loadout and the target rather than on how fast the fight was played.
	 */
	double averageHit()
	{
		return attempts == 0 ? 0 : (double) damageDealt / attempts;
	}

	/** 0..1; share of my resolved attacks that dealt more than 0. */
	double accuracy()
	{
		return attempts == 0 ? 0 : (double) hits / attempts;
	}

	/**
	 * Takes one sample of the expected figures for the attack just resolved.
	 * A negative accuracy or average hit means the target's stats weren't
	 * available, and only the max hit is recorded.
	 */
	void recordExpected(int maxHit, double accuracy, double averageHit)
	{
		if (maxHit > 0)
		{
			sumExpectedMaxHit += maxHit;
			expectedMaxHitSamples++;
		}
		if (accuracy >= 0 && averageHit >= 0)
		{
			sumExpectedAccuracy += accuracy;
			sumExpectedAverageHit += averageHit;
			expectedTargetSamples++;
		}
	}

	/** Mean expected max hit over the attacks made, or -1 if never sampled. */
	double expectedMaxHit()
	{
		return expectedMaxHitSamples == 0 ? -1 : sumExpectedMaxHit / expectedMaxHitSamples;
	}

	/** Mean expected hit chance (0..1) over the attacks made, or -1 if never sampled. */
	double expectedAccuracy()
	{
		return expectedTargetSamples == 0 ? -1 : sumExpectedAccuracy / expectedTargetSamples;
	}

	/** Mean expected damage per attack over the attacks made, or -1 if never sampled. */
	double expectedAverageHit()
	{
		return expectedTargetSamples == 0 ? -1 : sumExpectedAverageHit / expectedTargetSamples;
	}

	/**
	 * Records the tick an attack was made on, counting any ticks the weapon
	 * spent off cooldown before it. A scythe swung on tick 1 could go again on
	 * tick 6, so swinging on tick 7 instead loses one.
	 *
	 * <p>The first attack of a fight sets the baseline and can lose nothing,
	 * since there is no earlier attack to be late relative to.
	 */
	void recordAttackTick(int tick, int weaponSpeedTicks)
	{
		if (lastAttackTick < 0)
		{
			firstAttackTick = tick;
		}
		else if (weaponSpeedTicks > 0)
		{
			final int gap = tick - lastAttackTick;
			if (gap > weaponSpeedTicks)
			{
				ticksLost += gap - weaponSpeedTicks;
			}
		}
		lastAttackTick = tick;
	}

	/**
	 * Ticks lost as a share of the ticks between the first and last attack, so a
	 * long fight and a short one can be compared. -1 until two attacks have been
	 * made, before which no gap exists to judge.
	 */
	double ticksLostShare()
	{
		final int span = attackTickSpan();
		return span <= 0 ? -1 : (double) ticksLost / span;
	}

	/** Ticks between the first and last attack of this fight. */
	int attackTickSpan()
	{
		return firstAttackTick < 0 ? 0 : lastAttackTick - firstAttackTick;
	}
}
