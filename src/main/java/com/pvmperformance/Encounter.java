package com.pvmperformance;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

// One boss or room: the fights that belong together, kept as a list rather than as a second set of counters.
@Getter
class Encounter
{
	private final String name;
	private final RaidType raid;
	private final long startMillis;
	private final List<Fight> fights = new ArrayList<>();
	private long endMillis;

	Encounter(String name, RaidType raid, long now)
	{
		this.name = name;
		this.raid = raid;
		this.startMillis = now;
	}

	void add(Fight fight)
	{
		fights.add(fight);
	}

	/** Whether a fight on this NPC continues this encounter or starts another. */
	boolean accepts(String candidateName)
	{
		return name.equals(candidateName);
	}

	void end(long now)
	{
		endMillis = now;
	}

	/**
	 * Whether killing this NPC is killing what this room is about. Asked of the
	 * group rather than the id: a boss made of several NPCs is killed at whichever
	 * part is last, and the Hueycoatl drops its loot from a head while the room is
	 * full of fights against body segments.
	 */
	boolean holds(int npcId)
	{
		for (Fight fight : fights)
		{
			if (fight.getTargetId() == npcId || EncounterGroup.sameGroup(npcId, fight.getTargetId()))
			{
				return true;
			}
		}
		return false;
	}

	boolean isEnded()
	{
		return endMillis > 0;
	}

	/**
	 * Whether anything has been thrown in this room yet. A fight opens on merely
	 * looking at something, so a room with no attempts is one nothing happened in.
	 */
	boolean hasAttempts()
	{
		for (Fight fight : fights)
		{
			if (fight.getAttempts() > 0)
			{
				return true;
			}
		}
		return false;
	}

	/** Whether any of this encounter's targets died. */
	boolean isKilled()
	{
		for (Fight fight : fights)
		{
			if (fight.isTargetDied())
			{
				return true;
			}
		}
		return false;
	}

	int getDamageDealt()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getDamageDealt();
			}
		}
		return total;
	}

	int getDamageTaken()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getDamageTaken();
		}
		return total;
	}

	/**
	 * Attacks that got an answer. One nulled by the kill has no outcome to be
	 * right or wrong about, and left in here it reads as a miss that dealt
	 * nothing.
	 */
	int getAttempts()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.resolvedAttempts();
			}
		}
		return total;
	}

	int getHits()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getHits();
			}
		}
		return total;
	}

	double accuracy()
	{
		final int attempts = getAttempts();
		return attempts == 0 ? 0 : (double) getHits() / attempts;
	}

	double averageHit()
	{
		final int attempts = getAttempts();
		return attempts == 0 ? 0 : (double) getDamageDealt() / attempts;
	}

	/** Running total of what each attack was expected to deal, or 0 if untracked. */
	double sumExpectedAverageHit()
	{
		double total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getSumExpectedAverageHit();
			}
		}
		return total;
	}

	double sumExpectedAccuracy()
	{
		double total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getSumExpectedAccuracy();
			}
		}
		return total;
	}

	int getAttacksMade()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksMade();
			}
		}
		return total;
	}

	int getAttacksPrayed()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksPrayed();
			}
		}
		return total;
	}

	int getAttacksSwitched()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksSwitched();
			}
		}
		return total;
	}

	int getAttacksPotted()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksPotted();
			}
		}
		return total;
	}

	double efficiency()
	{
		double actual = 0;
		double ideal = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				actual += fight.getSumActualSetup();
				ideal += fight.getSumIdealSetup();
			}
		}
		return ideal <= 0 ? -1 : actual / ideal;
	}

	int getTicksLostEating()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getTicksLostEating();
		}
		return total;
	}

	int getTicksLost()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getTicksLost();
		}
		return total;
	}

	int getCombatTicks()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getCombatTicks();
		}
		return total;
	}

	double ticksLostShare()
	{
		final int ticks = getCombatTicks();
		return ticks <= 0 ? -1 : (double) getTicksLost() / ticks;
	}

	long durationMillis()
	{
		long last = startMillis;
		for (Fight fight : fights)
		{
			last = Math.max(last, fight.getLastActivityMillis());
		}
		return Math.max(0, (isEnded() ? endMillis : last) - startMillis);
	}
}
