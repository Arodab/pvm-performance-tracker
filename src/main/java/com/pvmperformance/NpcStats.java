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

	void add(Fight fight)
	{
		fights++;
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
		totalAttempts += fight.getAttempts();
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

	/** Mean damage per attack made across every fight, counting misses as zero. */
	double avgHit()
	{
		return totalAttempts == 0 ? 0 : (double) totalDamageDealt / totalAttempts;
	}

	double accuracy()
	{
		return totalAttempts == 0 ? 0 : (double) totalHits / totalAttempts;
	}
}
