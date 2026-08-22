package com.pvmperformance;

import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;

// Which special the Great Olm is running this phase.
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
