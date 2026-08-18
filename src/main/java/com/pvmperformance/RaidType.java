package com.pvmperformance;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * The raid the player is inside, if any. Read from the game's own varbits
 * rather than inferred from what is being fought, so entering a raid is known
 * before the first NPC and leaving it is known at once.
 */
enum RaidType
{
	CHAMBERS_OF_XERIC("Chambers of Xeric"),
	THEATRE_OF_BLOOD("Theatre of Blood"),
	TOMBS_OF_AMASCUT("Tombs of Amascut");

	private final String displayName;

	RaidType(String displayName)
	{
		this.displayName = displayName;
	}

	String getDisplayName()
	{
		return displayName;
	}

	/** The raid the player is in, or null outside one. */
	static RaidType current(Client client)
	{
		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
		{
			return CHAMBERS_OF_XERIC;
		}
		// Party status counts the lobby as well; the wave progress only moves
		// once the raid itself is under way.
		if (client.getVarbitValue(VarbitID.TOB_PROGRESS) > 0)
		{
			return THEATRE_OF_BLOOD;
		}
		if (client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) > 0)
		{
			return TOMBS_OF_AMASCUT;
		}
		return null;
	}
}
