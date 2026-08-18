package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * Raid scaling applied to a monster's levels, in one place because two callers
 * need the same answer: the model that rolls against a target, and the drain
 * tracker that takes a share of its defence.
 *
 * <p>The Chambers arithmetic is taken from the GearScape DPS calculator, which
 * is the only working record of it — the wiki says only that stats scale, the
 * spreadsheet it descends from has not been updated in a year, and the
 * BSD-2 calculator this project's formulas otherwise come from does not model
 * the Chambers at all. Credit belongs to GearScape and is owed in the
 * submission. What is reproduced is the arithmetic of a game mechanic rather
 * than any of their code.
 */
final class RaidScaling
{
	// The few whose magic level is worth rolling against, and so is scaled with
	// the rest of them.
	private static final Set<String> MAGIC_SCALED = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList("deathly ranger", "abyssal portal", "vespula", "vespine soldier")));

	private RaidScaling()
	{
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
			? chambers(client, base, chambersDefenceMultiplier(client, name), partyHitpoints) : base;
	}

	/**
	 * A monster's magic level as the raid leaves it.
	 *
	 * <p>The Tombs do not scale it — a twisted bow reads the same max hit
	 * against the Wardens at invocation 0 and 500, and its magic level of 190
	 * could not have stayed under the bow's cap of 250 through a +200% scaling.
	 * The Chambers scale it, but only for the few whose magic is worth rolling
	 * against.
	 */
	static int magic(Client client, int base, String name, int partyHitpoints)
	{
		if (!inChambers(client) || !scalesMagic(name))
		{
			return base;
		}
		return chambers(client, base, chambersDefenceMultiplier(client, name), partyHitpoints);
	}

	/**
	 * Tombs of Amascut: 2% for every five raid levels, additively, so 300 is
	 * +120% and 500 is +200%. The defence level only — magic level does not
	 * move, and defence bonuses are armour rather than levels.
	 */
	private static int tombs(int base, int raidLevel)
	{
		return base * (100 + 2 * (raidLevel / 5)) / 100;
	}

	/**
	 * Chambers of Xeric: the level is taken down by the party's hitpoints and
	 * back up by its size, then multiplied for challenge mode. Each step floors,
	 * and the order they are applied in changes the answer, so it is kept.
	 *
	 * <p>The hitpoints term is the party's <em>highest</em> hitpoints level,
	 * which comes from {@link PartyHitpoints}: the game exposes only the
	 * player's own, and the party plugin's broadcasts are where the rest of it
	 * is. At 99 the term saturates, so for most raiders it changes nothing.
	 */
	private static int chambers(Client client, int base, double cmMultiplier, int partyHitpoints)
	{
		final int party = chambersPartySize(client);
		final int hp = Math.max(1, Math.min(99, partyHitpoints));
		final int hpTerm = Math.max(55, Math.min(99, 55 + 44 * hp / 99));

		int level = base * hpTerm / 99;
		final int sizeTerm = (int) Math.sqrt(party - 1) + 7 * (party - 1) / 10 + 100;
		level = level * sizeTerm / 100;
		return (int) (level * cmMultiplier);
	}

	/**
	 * Challenge mode raises defence by half, except for Tekton — a fifth in a
	 * small team and rather more in a large one — and the glowing crystal, whose
	 * defence it leaves alone.
	 */
	private static double chambersDefenceMultiplier(Client client, String name)
	{
		if (client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) != 1)
		{
			return 1;
		}
		final String lower = name == null ? "" : name.toLowerCase();
		if (lower.startsWith("tekton"))
		{
			return chambersPartySize(client) < 4 ? 1.2 : 1.35;
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

	private static boolean inChambers(Client client)
	{
		return client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
	}

	/** Reads in the Tombs lobby as well, where nothing is fought. */
	private static int tombsRaidLevel(Client client)
	{
		return client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL);
	}
}
