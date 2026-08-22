package com.pvmperformance;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

// One run of a raid: its rooms, in the order they were fought.
@Getter
class Raid
{
	private final RaidType type;
	private final String name;
	private final int id;
	private final long startMillis;
	private final List<Encounter> encounters = new ArrayList<>();
	private long endMillis;

	Raid(RaidType type, int id, long now)
	{
		this(type, type.getDisplayName(), id, now);
	}

	/** Rebuilt from a history file, which stores the raid's name and not its type. */
	Raid(String name, int id, long now)
	{
		this(null, name, id, now);
	}

	private Raid(RaidType type, String name, int id, long now)
	{
		this.type = type;
		this.name = name;
		this.id = id;
		this.startMillis = now;
	}

	void add(Encounter encounter)
	{
		encounters.add(encounter);
	}

	void end(long now)
	{
		endMillis = now;
	}

	int getDamageDealt()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getDamageDealt();
		}
		return total;
	}

	int getAttempts()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttempts();
		}
		return total;
	}

	int getHits()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getHits();
		}
		return total;
	}

	double sumExpectedAverageHit()
	{
		double total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.sumExpectedAverageHit();
		}
		return total;
	}

	double sumExpectedAccuracy()
	{
		double total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.sumExpectedAccuracy();
		}
		return total;
	}

	int getAttacksMade()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksMade();
		}
		return total;
	}

	int getAttacksPrayed()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksPrayed();
		}
		return total;
	}

	int getAttacksSwitched()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksSwitched();
		}
		return total;
	}

	int getAttacksPotted()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksPotted();
		}
		return total;
	}

	double efficiency()
	{
		double actual = 0;
		double ideal = 0;
		for (Encounter encounter : encounters)
		{
			for (Fight fight : encounter.getFights())
			{
				if (fight.isScored())
				{
					actual += fight.getSumActualSetup();
					ideal += fight.getSumIdealSetup();
				}
			}
		}
		return ideal <= 0 ? -1 : actual / ideal;
	}

	int getTicksLost()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getTicksLost();
		}
		return total;
	}

	int getTicksLostEating()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getTicksLostEating();
		}
		return total;
	}

	int getCombatTicks()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getCombatTicks();
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
		for (Encounter encounter : encounters)
		{
			last = Math.max(last, encounter.getStartMillis() + encounter.durationMillis());
		}
		return Math.max(0, (endMillis > 0 ? endMillis : last) - startMillis);
	}
}
