package com.pvmperformance;

import lombok.Getter;

// Aggregated performance across every fight against one target, where a
// target is a room when the NPCs of that room are grouped and a single NPC
// otherwise. So the nylocas add up per colour and Kephri's whole room reads
// as Kephri, while a Vorkath is still a Vorkath.
@Getter
class NpcStats
{
	private final String name;
	// Rooms, not sub-fights. One Hueycoatl is a fight against a body and then
	// one against the head, and counting those separately read as "2 fights, 1
	// kill" for a single clean kill.
	private int fights;
	private int kills;
	private int maxHp = -1;
	private long totalDamageDealt;
	private long totalDamageTaken;
	private long totalDurationMillis;
	private int totalAttempts;
	private int totalHits;
	private int ticksLost;
	private int combatTicks;
	private double sumActualSetup;
	private double sumIdealSetup;

	NpcStats(String name)
	{
		this.name = name;
	}

	/**
	 * @param startsRoom whether this fight opens a new room rather than
	 *                   continuing the one before it
	 */
	void add(Fight fight, boolean startsRoom)
	{
		if (startsRoom)
		{
			fights++;
		}
		if (fight.isTargetDied())
		{
			kills++;
		}
		totalDamageTaken += fight.getDamageTaken();
		totalDurationMillis += fight.durationMillis();
		ticksLost += fight.getTicksLost();
		combatTicks += fight.getCombatTicks();
		if (!fight.isScored())
		{
			return;
		}
		maxHp = Math.max(maxHp, fight.getMaxHp());
		totalDamageDealt += fight.getDamageDealt();
		totalAttempts += fight.resolvedAttempts();
		totalHits += fight.getHits();
		sumActualSetup += fight.getSumActualSetup();
		sumIdealSetup += fight.getSumIdealSetup();
	}

	/**
	 * Damage the attacks were set up to deal against what they would have with
	 * the intended prayer and a full boost, or -1 if nothing was comparable.
	 */
	double efficiency()
	{
		return sumIdealSetup <= 0 ? -1 : sumActualSetup / sumIdealSetup;
	}

	/** Share of attackable ticks that went unused, or -1 if none were counted. */
	double ticksLostShare()
	{
		return combatTicks <= 0 ? -1 : (double) ticksLost / combatTicks;
	}

	double avgDps()
	{
		final double seconds = totalDurationMillis / 1000.0;
		return seconds < 0.6 ? 0 : totalDamageDealt / seconds;
	}

	/**
	 * Mean damage per attack across every fight, counting misses as zero.
	 * Denominated on attacks that actually resolved: one nulled by the kill
	 * dealt nothing because there was nothing left to deal it to, not because
	 * it was thrown badly.
	 */
	double avgHit()
	{
		return totalAttempts == 0 ? 0 : (double) totalDamageDealt / totalAttempts;
	}

	double accuracy()
	{
		return totalAttempts == 0 ? 0 : (double) totalHits / totalAttempts;
	}
}
