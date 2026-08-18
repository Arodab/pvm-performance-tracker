package com.pvmperformance;

/**
 * How much of a raid the overlay reports on at once. Public because the config
 * proxy returns it, and a type it cannot reach throws on every read.
 */
public enum RaidScope
{
	ROOM("Current room"),
	RAID("Whole raid");

	private final String label;

	RaidScope(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
