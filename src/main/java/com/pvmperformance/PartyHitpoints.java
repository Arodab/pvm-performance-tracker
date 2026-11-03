package com.pvmperformance;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.party.PartyDataService;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;

// The highest hitpoints level in the party, which is what Chambers of Xeric scales its monsters by.
@Singleton
class PartyHitpoints
{
	private final Client client;
	private final PartyService partyService;
	private final PartyDataService partyDataService;

	@Inject
	PartyHitpoints(Client client, PartyService partyService, PartyDataService partyDataService)
	{
		this.client = client;
		this.partyService = partyService;
		this.partyDataService = partyDataService;
	}

	/**
	 * Asks the party to share hitpoints levels. Since RuneLite 1.13.4 nothing is
	 * broadcast unless some plugin subscribes to it, so this no longer leans on
	 * the party plugin being switched on - and it also sends ours to the others.
	 */
	void start(Plugin plugin)
	{
		partyDataService.subscribe(plugin, Skill.HITPOINTS);
	}

	void stop(Plugin plugin)
	{
		partyDataService.unsubscribe(plugin, Skill.HITPOINTS);
	}

	/** The highest hitpoints level known, never below the player's own. */
	int highest()
	{
		int highest = client.getRealSkillLevel(Skill.HITPOINTS);
		for (PartyMember member : partyService.getMembers())
		{
			// The party service keeps each member's last reported levels, with 0 until one has arrived.
			final int max = member.getStatBase(Skill.HITPOINTS);
			if (max > highest)
			{
				highest = max;
			}
		}
		return highest;
	}
}
