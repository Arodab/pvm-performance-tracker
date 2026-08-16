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

	Fight(String targetName, int targetId, int targetIndex, long now)
	{
		this.targetName = targetName == null ? "NPC" : targetName;
		this.targetId = targetId;
		this.targetIndex = targetIndex;
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

	/** 0..1; share of my resolved attacks that dealt more than 0. */
	double accuracy()
	{
		return attempts == 0 ? 0 : (double) hits / attempts;
	}
}
