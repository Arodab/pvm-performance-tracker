package com.pvmperformance;

import lombok.Getter;

/**
 * The actual (measured) performance of a single fight against one NPC. Only
 * observed facts live here: damage from {@code hitsplat.isMine()} splats, hits
 * vs zeros for accuracy, damage taken, and wall-clock duration. The "expected"
 * side (max hit / expected DPS from a combat model) is added in a later phase.
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
}
