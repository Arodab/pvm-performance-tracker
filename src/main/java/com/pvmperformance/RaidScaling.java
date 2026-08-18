package com.pvmperformance;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * Raid scaling applied to a monster's stats, in one place because two callers
 * need the same answer: the model that rolls against a target's defence, and
 * the drain tracker that takes a share of it.
 */
final class RaidScaling
{
	private RaidScaling()
	{
	}

	/**
	 * A monster's defence level as the raid it stands in leaves it.
	 *
	 * <p>Tombs of Amascut adds 2% for every five raid levels, additively, so 300
	 * is +120% and 500 is +200%. The defence level only: magic level
	 * demonstrably does not move, and defence bonuses are armour rather than
	 * levels. See CombatCalc's magic hit chance for the twisted bow argument.
	 *
	 * <p>Chambers of Xeric scales too — by party size and by the highest
	 * player's combat stats — and is not modelled, because the wiki records that
	 * it happens without recording the formula.
	 */
	static int defence(Client client, int base)
	{
		// Reads in the Tombs lobby as well, where nothing is fought.
		final int raidLevel = client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL);
		if (raidLevel <= 0)
		{
			return base;
		}
		return base * (100 + 2 * (raidLevel / 5)) / 100;
	}
}
