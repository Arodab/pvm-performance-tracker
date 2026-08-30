package com.pvmperformance;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.plugins.party.messages.StatusUpdate;

// The highest hitpoints level in the party, which is what Chambers of Xeric scales its monsters by.
@Singleton
class PartyHitpoints
{
	private final Client client;

	// party member id -> the maximum hitpoints they last reported.
	private final Map<Long, Integer> reported = new HashMap<>();

	@Inject
	PartyHitpoints(Client client)
	{
		this.client = client;
	}

	/**
	 * Takes a member's maximum hitpoints from a party status update. The updates
	 * carry only what has changed, so most say nothing about hitpoints and every
	 * field has to be read as possibly absent.
	 */
	void onStatusUpdate(StatusUpdate update)
	{
		final Integer max = update.getHealthMax();
		if (max != null && max > 0)
		{
			reported.put(update.getMemberId(), max);
		}
	}

	void forget(long memberId)
	{
		reported.remove(memberId);
	}

	void clear()
	{
		reported.clear();
	}

	/** The highest hitpoints level known, never below the player's own. */
	int highest()
	{
		int highest = client.getRealSkillLevel(Skill.HITPOINTS);
		for (Integer max : reported.values())
		{
			if (max > highest)
			{
				highest = max;
			}
		}
		return highest;
	}
}
