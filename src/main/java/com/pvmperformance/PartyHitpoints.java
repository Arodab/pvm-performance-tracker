package com.pvmperformance;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;

// The highest hitpoints level in the party, which is what Chambers of Xeric scales its monsters by.
@Singleton
class PartyHitpoints
{
	private final Client client;
	private final PartyService partyService;

	@Inject
	PartyHitpoints(Client client, PartyService partyService)
	{
		this.client = client;
		this.partyService = partyService;
	}

	/**
	 * The highest hitpoints level known, never below the player's own.
	 *
	 * <p>Read straight off the party service's members, which RuneLite keeps up
	 * to date from each member's broadcasts. The old StatusUpdate message this
	 * used to listen for was removed from the client, and with it the plugin no
	 * longer compiled. A member who has not reported yet reads as zero, which
	 * the own-level floor already covers; one who leaves drops out of the list.
	 */
	int highest()
	{
		int highest = client.getRealSkillLevel(Skill.HITPOINTS);
		if (!partyService.isInParty())
		{
			return highest;
		}
		for (PartyMember member : partyService.getMembers())
		{
			final int base = member.getStatBase(Skill.HITPOINTS);
			if (base > highest)
			{
				highest = base;
			}
		}
		return highest;
	}
}
