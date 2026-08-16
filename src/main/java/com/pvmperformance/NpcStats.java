package com.pvmperformance;

import lombok.Getter;

/** Aggregated actual performance across every fight against one NPC name. */
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
		maxHp = Math.max(maxHp, fight.getMaxHp());
		totalDamageDealt += fight.getDamageDealt();
		totalDamageTaken += fight.getDamageTaken();
		totalDurationMillis += fight.durationMillis();
		totalAttempts += fight.getAttempts();
		totalHits += fight.getHits();
	}

	double avgDps()
	{
		final double seconds = totalDurationMillis / 1000.0;
		return seconds < 0.6 ? 0 : totalDamageDealt / seconds;
	}

	double accuracy()
	{
		return totalAttempts == 0 ? 0 : (double) totalHits / totalAttempts;
	}
}
