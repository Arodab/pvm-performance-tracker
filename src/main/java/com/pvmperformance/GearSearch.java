package com.pvmperformance;

import java.util.List;
import net.runelite.api.EquipmentInventorySlot;

// The best gear the player could have been wearing for the weapon in hand.
//
// The weapon is taken as given and every other slot is judged. That is a
// deliberate choice rather than a simplification: which weapon to bring is
// where the ambiguity lives — a halberd carried for reach, a spec weapon held
// for a phase — and holding it fixed removes every per-boss exception at once.
// A player who brought the wrong weapon knows to distrust the figure; one who
// brought the right weapon and missed the other switches is still caught.
final class GearSearch
{
	// Two passes rather than one, so a slot judged early can be reconsidered
	// once a later one has changed what the loadout is worth. It converges
	// almost always in the first pass; the second is cheap insurance.
	private static final int MAX_PASSES = 2;
	// Expected damage is a double. Anything under this is arithmetic noise
	// rather than a switch worth making, and calling it a miss would mark a
	// player down for nothing. Shared with the caller, which holds a search's
	// answer across ticks and needs the same threshold to decide it still
	// stands.
	static final double MEANINGFUL = 1e-6;

	/** One thing that could go in one slot. */
	static final class Candidate
	{
		private final EquipmentInventorySlot slot;
		private final int itemId;

		Candidate(EquipmentInventorySlot slot, int itemId)
		{
			this.slot = slot;
			this.itemId = itemId;
		}
	}

	/** What a loadout is worth, which only the combat model can answer. */
	interface Score
	{
		double of(Loadout gear);
	}

	private GearSearch()
	{
	}

	/**
	 * The best loadout reachable from what is worn by swapping in the
	 * candidates, or the worn loadout itself when nothing improves on it —
	 * returned by identity, so a caller can test for "nothing was missed"
	 * without comparing figures.
	 */
	static Loadout best(Loadout worn, List<Candidate> candidates, boolean twoHandedWeapon, Score score)
	{
		Loadout best = worn;
		double bestScore = score.of(worn);
		for (int pass = 0; pass < MAX_PASSES; pass++)
		{
			boolean improved = false;
			for (Candidate candidate : candidates)
			{
				// The weapon is not judged, and a two-hander leaves no shield
				// slot to judge — offering one would be advice the game will
				// not let the player take.
				if (candidate.slot == EquipmentInventorySlot.WEAPON
					|| (twoHandedWeapon && candidate.slot == EquipmentInventorySlot.SHIELD))
				{
					continue;
				}
				final Loadout swapped = best.with(candidate.slot, candidate.itemId);
				if (swapped == best)
				{
					continue; // already in that slot
				}
				final double swappedScore = score.of(swapped);
				if (swappedScore > bestScore + MEANINGFUL)
				{
					best = swapped;
					bestScore = swappedScore;
					improved = true;
				}
			}
			if (!improved)
			{
				break;
			}
		}
		return best;
	}
}
