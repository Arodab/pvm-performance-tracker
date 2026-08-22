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
		// None of these varbits is reliably cleared on leaving, so a fight in
		// Prifddinas was being filed under Theatre of Blood on the strength of a
		// progress value left over from a raid. Requiring an instance is not the
		// whole answer - a house is instanced too - but it keeps the open world
		// clean while a varbit that actually clears is identified.
		if (!client.isInInstancedRegion())
		{
			return null;
		}
		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
		{
			return CHAMBERS_OF_XERIC;
		}
		// The wave, not TOB_PROGRESS. That one runs 0 to 5 and counts how far
		// the player has got through the Theatre's stages at some point, not
		// whether they are in it now: it reads non-zero for anyone who has ever
		// been near the place, and filed every goblin and guard ever killed
		// under Theatre of Blood. The wave only moves while a raid is under way.
		if (client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_VAL) > 0)
		{
			return THEATRE_OF_BLOOD;
		}
		// The party status, not the raid level. The level is never cleared on
		// leaving, so it reads set for the rest of the session; the status is 1
		// inside and 0 everywhere else, a house included.
		if (client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS) > 0)
		{
			return TOMBS_OF_AMASCUT;
		}
		return null;
	}
}
