package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;

// Raid scaling applied to a monster's levels, in one place because two
// callers need the same answer: the model that rolls against a target, and
// the drain tracker that takes a share of its defence.
// The Chambers arithmetic is taken from the GearScape DPS calculator, the
// only working record of it, and credit is owed to them in the submission.
// What is reproduced is the arithmetic of a game mechanic, not their code.
final class RaidScaling
{
	// The few whose magic level is worth rolling against, and so is scaled with
	// the rest of them.
	private static final Set<String> MAGIC_SCALED = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList("deathly ranger", "abyssal portal", "vespula", "vespine soldier")));

	// Olm's hands mitigate two thirds of an off-style hit, leaving a third.
	private static final double MITIGATED = 1.0 / 3.0;

	private RaidScaling()
	{
	}

	// The share of a hit that reaches the target, for the few that shrug most
	// of it off.
	static double damageTaken(int npcId, AttackType type)
	{
		if (npcId == NpcID.OLM_HAND_RIGHT || npcId == NpcID.OLM_HAND_RIGHT_SPAWNING)
		{
			return type == AttackType.MAGIC ? 1 : MITIGATED;
		}
		if (npcId == NpcID.OLM_HAND_LEFT || npcId == NpcID.OLM_HAND_LEFT_SPAWNING)
		{
			return type.isMelee() ? 1 : MITIGATED;
		}
		// The Nightmare's totems take double from magic, which is why they are
		// charged with it. Left out, the expected damage would be half what
		// lands and the player would look twice as unlucky as they were.
		return isNightmareTotem(npcId) && type == AttackType.MAGIC ? 2 : 1;
	}

	private static boolean isNightmareTotem(int npcId)
	{
		switch (npcId)
		{
			case NpcID.NIGHTMARE_TOTEM_1_READY:
			case NpcID.NIGHTMARE_TOTEM_2_READY:
			case NpcID.NIGHTMARE_TOTEM_3_READY:
			case NpcID.NIGHTMARE_TOTEM_4_READY:
			case NpcID.NIGHTMARE_TOTEM_1_CHARGED:
			case NpcID.NIGHTMARE_TOTEM_2_CHARGED:
			case NpcID.NIGHTMARE_TOTEM_3_CHARGED:
			case NpcID.NIGHTMARE_TOTEM_4_CHARGED:
				return true;
			default:
				return false;
		}
	}

	/** Whether this is Olm's mage hand, whose magic level the Chambers halve. */
	static boolean isOlmMageHand(int npcId)
	{
		return npcId == NpcID.OLM_HAND_RIGHT || npcId == NpcID.OLM_HAND_RIGHT_SPAWNING;
	}

	/** A monster's defence level as the raid it stands in leaves it. */
	static int defence(Client client, int base, String name, int partyHitpoints)
	{
		final int toa = tombsRaidLevel(client);
		if (toa > 0)
		{
			return tombs(base, toa);
		}
		return inChambers(client)
			? chambers(base, chambersPartySize(client), partyHitpoints,
				defenceMultiplier(isChallengeMode(client), chambersPartySize(client), name))
			: base;
	}

	// A monster's magic level as the raid leaves it.
	static int magic(Client client, int npcId, int base, String name, int partyHitpoints)
	{
		if (!inChambers(client) || !scalesMagic(name))
		{
			return base;
		}
		final int scaled = chambers(base, chambersPartySize(client), partyHitpoints,
			defenceMultiplier(isChallengeMode(client), chambersPartySize(client), name));
		// The mage hand rolls on half of it.
		return isOlmMageHand(npcId) ? scaled / 2 : scaled;
	}

	/**
	 * Tombs of Amascut: 2% for every five raid levels, additively, so 300 is
	 * +120% and 500 is +200%. The defence level only, magic level does not
	 * move, and defence bonuses are armour rather than levels.
	 */
	static int tombs(int base, int raidLevel)
	{
		return base * (100 + 2 * (raidLevel / 5)) / 100;
	}

	// Chambers of Xeric: the level is taken down by the party's hitpoints and
	// back up by its size, then multiplied for challenge mode. Each step
	// floors, and the order they are applied in changes the answer, so it is
	// kept.
	static int chambers(int base, int partySize, int partyHitpoints, double cmMultiplier)
	{
		final int party = Math.max(1, Math.min(100, partySize));
		final int hp = Math.max(1, Math.min(99, partyHitpoints));
		final int hpTerm = Math.max(55, Math.min(99, 55 + 44 * hp / 99));

		int level = base * hpTerm / 99;
		final int sizeTerm = (int) Math.sqrt(party - 1) + 7 * (party - 1) / 10 + 100;
		level = level * sizeTerm / 100;
		return (int) (level * cmMultiplier);
	}

	/**
	 * Challenge mode raises defence by half, except for Tekton, a fifth in a
	 * small team and rather more in a large one, and the glowing crystal, whose
	 * defence it leaves alone.
	 */
	static double defenceMultiplier(boolean challengeMode, int partySize, String name)
	{
		if (!challengeMode)
		{
			return 1;
		}
		final String lower = name == null ? "" : name.toLowerCase();
		if (lower.startsWith("tekton"))
		{
			return partySize < 4 ? 1.2 : 1.35;
		}
		return lower.startsWith("glowing crystal") ? 1 : 1.5;
	}

	private static boolean scalesMagic(String name)
	{
		if (name == null)
		{
			return false;
		}
		final String lower = name.toLowerCase();
		if (lower.startsWith("tekton") || lower.startsWith("great olm"))
		{
			// Olm's head rolls on defence rather than magic; both hands use it.
			// GearScape halves the mage hand's, which is not reproduced here
			// because the monster data names both claws "Great Olm" and tells
			// them apart only by a version string it does not carry through.
			return !lower.contains("head");
		}
		for (String scaled : MAGIC_SCALED)
		{
			if (lower.startsWith(scaled))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The party size the raid is scaled for, which is not always how many people
	 * are in it: the Chambers can be set to scale as though for more.
	 */
	private static int chambersPartySize(Client client)
	{
		final int scaled = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE_SCALED);
		final int actual = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
		return Math.max(1, Math.min(100, Math.max(scaled, actual)));
	}

	private static boolean isChallengeMode(Client client)
	{
		return client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) == 1;
	}

	private static boolean inChambers(Client client)
	{
		return client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
	}

	/** Reads in the Tombs lobby as well, where nothing is fought. */
	// Set by the plugin each tick from GearBonusCalc, which latches the raid on
	// the way in. The varbit alone cannot answer it: it is never cleared.
	private static int tombsLevel;

	static void setTombsRaidLevel(int level)
	{
		tombsLevel = level;
	}

	private static int tombsRaidLevel(Client client)
	{
		return tombsLevel;
	}
}
