package com.pvmperformance;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.NpcID;

/**
 * How far an NPC's defence can be drained, for the few that limit it.
 *
 * <p>A cap is the most that can be taken <em>off</em>, not the level it stops
 * at: the wiki's own example is a cap of 50 against a defence of 250, leaving
 * 200 as the lowest reachable. The Doom of Mokhaiotl bears this out — a cap of
 * 30 on its defence of 90 is documented as a floor of 60.
 *
 * <p>Almost nothing is capped. The wiki keeps no table of this and no category
 * for it, and searching every phrasing of it across every page turns up a
 * handful of monsters, so this is a short hand-checked list rather than
 * anything scraped, and the default is what nearly every monster does: drain
 * all the way to zero.
 *
 * <p>Two known immunities could not be written down here, and both would be
 * read as drainable:
 *
 * <ul>
 * <li>The awakened Forgotten Four share their NPC ids with their post-quest
 * selves — Vardorvis is 12223 either way — so awakened cannot be told apart by
 * id and needs whatever state signals the fight is awakened.
 * <li>Yama is only immune in a contracted fight, which is likewise a mode
 * rather than an id.
 * </ul>
 */
final class DefenceDrainCap
{
	/** The most defence a drain can remove. Absent means no limit. */
	private static final Map<Integer, Integer> CAPS = build();

	private DefenceDrainCap()
	{
	}

	/**
	 * The lowest defence level draining can take this NPC to, given the defence
	 * it starts the fight with — which is the raid-scaled figure where that
	 * applies, since a cap is a number of levels rather than a share of them.
	 */
	static int floor(int npcId, int baseDefence)
	{
		return baseDefence - maxDrain(npcId, baseDefence);
	}

	/** How many levels of defence can be taken off this NPC in total. */
	static int maxDrain(int npcId, int baseDefence)
	{
		final Integer cap = CAPS.get(npcId);
		return cap == null ? baseDefence : Math.min(cap, baseDefence);
	}

	/** Whether draining does nothing at all to this NPC. */
	static boolean isImmune(int npcId)
	{
		return Integer.valueOf(0).equals(CAPS.get(npcId));
	}

	private static Map<Integer, Integer> build()
	{
		final Map<Integer, Integer> caps = new HashMap<>();

		// Verzik Vitur, in every phase and every mode: her defence cannot be
		// lowered at all. The transitions are left out, being unattackable.
		for (int id : new int[]{
			NpcID.VERZIK_INITIAL, NpcID.VERZIK_PHASE1, NpcID.VERZIK_PHASE2, NpcID.VERZIK_PHASE3,
			NpcID.VERZIK_INITIAL_BASE, NpcID.VERZIK_INITIAL_QUICKSTART,
			NpcID.VERZIK_INITIAL_STORY, NpcID.VERZIK_PHASE1_STORY,
			NpcID.VERZIK_PHASE2_STORY, NpcID.VERZIK_PHASE3_STORY,
			NpcID.VERZIK_INITIAL_HARD, NpcID.VERZIK_PHASE1_HARD,
			NpcID.VERZIK_PHASE2_HARD, NpcID.VERZIK_PHASE3_HARD,
			NpcID.VERZIK_INITIAL_HARD_BASE, NpcID.VERZIK_INITIAL_HARD_QUICKSTART,
		})
		{
			caps.put(id, 0);
		}

		// Demonic Brutus. The gameval name is nothing like the wiki's, so these
		// were matched by id: 15628 and 15629 are what the monster data calls
		// Demonic Brutus and its ghost.
		caps.put(NpcID.COWBOSS_HARDMODE, 0);
		caps.put(NpcID.COWBOSS_HARDMODE_GHOST, 0);

		// Doom of Mokhaiotl: 30 off a defence of 90, so 60 is the floor. Its
		// defence also resets on each floor of the delve, which is a matter for
		// whatever tracks the drain rather than for this table.
		caps.put(NpcID.DOM_BOSS, 30);
		caps.put(NpcID.DOM_BOSS_SHIELDED, 30);
		caps.put(NpcID.DOM_BOSS_BURROWED, 30);

		return Collections.unmodifiableMap(caps);
	}
}
