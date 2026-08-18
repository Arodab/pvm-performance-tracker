package com.pvmperformance;

import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;

/**
 * Which special the Great Olm is running this phase.
 *
 * <p>Olm's phases differ by which of three specials he uses, and a player who
 * handles two of them well and the third badly learns nothing from a single
 * "Great Olm" figure averaging all of them. Naming the phase by its special
 * lets the three be compared against each other across a trip.
 *
 * <p>Each announces itself with something of its own in the scene, and the
 * gameval names say which is which outright. The CoX Additions plugin, which is
 * where this was expected to come from, counts phases by watching Olm's head
 * object spawn and despawn and does not identify the specials at all.
 */
enum OlmPhase
{
	ACID("acid"),
	FLAME("flame"),
	CRYSTAL("crystal");

	private final String label;

	OlmPhase(String label)
	{
		this.label = label;
	}

	String getLabel()
	{
		return label;
	}

	/** The phase an object marks, or null if the object says nothing. */
	static OlmPhase forObject(int objectId)
	{
		if (objectId == ObjectID.OLM_ACID_POOL)
		{
			return ACID;
		}
		if (objectId == ObjectID.OLM_CRYSTAL_BOMB
			|| objectId == ObjectID.OLM_CRYSTAL_ATTACK_SMALL
			|| objectId == ObjectID.OLM_CRYSTAL_ATTACK_LARGE)
		{
			return CRYSTAL;
		}
		return null;
	}

	/** The phase an NPC marks. Only the flame wall, which is an NPC of its own. */
	static OlmPhase forNpc(int npcId)
	{
		return npcId == NpcID.OLM_FIREWALL_NPC ? FLAME : null;
	}

	/** Whether this object is Olm's head, whose spawning begins a phase. */
	static boolean isHeadObject(int objectId)
	{
		return objectId == ObjectID.OLM_HEAD || objectId == ObjectID.OLM_HEAD_SPAWNING;
	}
}
