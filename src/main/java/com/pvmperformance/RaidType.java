package com.pvmperformance;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * The raid the player is inside, if any. Read from the game's own varbits rather
 * than inferred from what is being fought, so entering is known before the first
 * NPC and leaving is known at once.
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
		// None of these varbits is reliably cleared on leaving, so a fight in Prifddinas was filed under Theatre of Blood on
		// a leftover progress value. Requiring an instance is not the whole answer - a house is instanced too - but it keeps
		// the open world clean.
		if (!client.isInInstancedRegion())
		{
			return null;
		}
		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
		{
			return CHAMBERS_OF_XERIC;
		}
		// The wave, not TOB_PROGRESS. That runs 0 to 5 and counts how far the player has ever got through the Theatre, not
		// whether they are in it now - it read non-zero for anyone who has been near the place, and filed every goblin ever
		// killed under Theatre of Blood.
		if (client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_VAL) > 0)
		{
			return THEATRE_OF_BLOOD;
		}
		// The party status, not the raid level: the level is never cleared on leaving, while the status is 1 inside and 0
		// everywhere else.
		if (client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS) > 0)
		{
			return TOMBS_OF_AMASCUT;
		}
		return null;
	}
}
