package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.NpcID;

/**
 * Which NPCs are worth reading together.
 *
 * <p>A fight stays one NPC — the phases of a boss are kept apart, since which
 * phase a kill goes wrong on is exactly what a breakdown is for, and anyone who
 * wants them added up can add them up. This only names the handful of cases
 * where the parts are not separately meaningful: a room of adds, a boss whose
 * hands come back every phase, an NPC that swaps id for the same fight. A
 * group is an extra label rather than a replacement, so both readings can be
 * exported.
 *
 * <p>Every id here was matched by number against the monster data rather than
 * by the look of its name, which is the only way this stays honest — the
 * baboons of the monkey room are {@code TOA_PATH_APMEKEN_BABOON_*} while
 * {@code TOA_BABA_BABOON} is Ba-Ba's, and grouping by the word "baboon" would
 * have quietly swept one room into another.
 */
final class EncounterGroup
{
	private static final Map<Integer, String> GROUPS = buildGroups();
	private static final Set<Integer> IGNORED = buildIgnored();

	private EncounterGroup()
	{
	}

	/** The group this NPC also belongs to, or null if it stands on its own. */
	static String of(int npcId)
	{
		return GROUPS.get(npcId);
	}

	/** Whether two NPCs are parts of the same thing. */
	static boolean sameGroup(int npcId, int otherId)
	{
		final String group = GROUPS.get(npcId);
		return group != null && group.equals(GROUPS.get(otherId));
	}

	/**
	 * NPCs that should never be tracked at all. Zebak's water jugs die to one
	 * hit and exist to be broken, so counting them as fights would bury the
	 * boss's own figures under a run of one-tick kills.
	 */
	static boolean isIgnored(int npcId)
	{
		return IGNORED.contains(npcId);
	}

	private static Map<Integer, String> buildGroups()
	{
		final Map<Integer, String> groups = new HashMap<>();

		// Chambers of Xeric. The three vanguards are one fight in practice,
		// since they must be brought down together. Vasa's crystals are his
		// healing mechanic. Olm's hands are grouped with each other but not
		// with the head, which is fought on its own terms — and note they
		// respawn each phase rather than transforming, so each phase's hands
		// are a fresh NPC.
		put(groups, "Vanguards",
			NpcID.RAIDS_VANGUARD_DORMANT, NpcID.RAIDS_VANGUARD_WALKING,
			NpcID.RAIDS_VANGUARD_MELEE, NpcID.RAIDS_VANGUARD_RANGED, NpcID.RAIDS_VANGUARD_MAGIC);
		put(groups, "Vasa Nistirio",
			NpcID.RAIDS_VASANISTIRIO_DORMANT, NpcID.RAIDS_VASANISTIRIO_WALKING,
			NpcID.RAIDS_VASANISTIRIO_HEALING, NpcID.RAIDS_VASANISTIRIO_CRYSTAL);
		put(groups, "Great Olm (hands)",
			NpcID.OLM_HAND_LEFT_SPAWNING, NpcID.OLM_HAND_LEFT, NpcID.OLM_HAND_LEFT_DYING,
			NpcID.OLM_HAND_RIGHT_SPAWNING, NpcID.OLM_HAND_RIGHT, NpcID.OLM_HAND_RIGHT_DYING);

		// Tombs of Amascut. Zebak and Kephri change id mid-fight for the same
		// fight; the monkey room's baboons are a room rather than a set of
		// kills.
		put(groups, "Zebak", NpcID.TOA_ZEBAK, NpcID.TOA_ZEBAK_ENRAGED);
		put(groups, "Kephri",
			NpcID.TOA_KEPHRI_BOSS_SHIELDED, NpcID.TOA_KEPHRI_BOSS_WEAK, NpcID.TOA_KEPHRI_BOSS_ENRAGE);
		put(groups, "Baboons",
			NpcID.TOA_PATH_APMEKEN_BABOON_MELEE_1, NpcID.TOA_PATH_APMEKEN_BABOON_MELEE_2,
			NpcID.TOA_PATH_APMEKEN_BABOON_RANGED_1, NpcID.TOA_PATH_APMEKEN_BABOON_RANGED_2,
			NpcID.TOA_PATH_APMEKEN_BABOON_MAGIC_1, NpcID.TOA_PATH_APMEKEN_BABOON_MAGIC_2,
			NpcID.TOA_PATH_APMEKEN_BABOON_SHAMAN, NpcID.TOA_PATH_APMEKEN_BABOON_ZOMBIE,
			NpcID.TOA_PATH_APMEKEN_BABOON_CURSED, NpcID.TOA_PATH_APMEKEN_BABOON_THRALL);

		// Theatre of Blood. The nylocas group by style rather than by size: a
		// big and a small of one colour are the same job. Keeping the styles
		// apart is the point, since killing the wrong colour is the mistake
		// worth seeing.
		put(groups, "Nylocas (melee)",
			NpcID.TOB_NYLOCAS_INCOMING_MELEE, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE,
			NpcID.TOB_NYLOCAS_INCOMING_MELEE_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE_STORY);
		put(groups, "Nylocas (ranged)",
			NpcID.TOB_NYLOCAS_INCOMING_RANGED, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED,
			NpcID.TOB_NYLOCAS_INCOMING_RANGED_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED_STORY);
		put(groups, "Nylocas (magic)",
			NpcID.TOB_NYLOCAS_INCOMING_MAGIC, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC,
			NpcID.TOB_NYLOCAS_INCOMING_MAGIC_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC_STORY);

		// Maiden takes her own adds with her: the matomenos that must be culled
		// and the blood spawns are part of that room and nothing else. Verzik's
		// blood nylocas share the matomenos name but not its id, and stay out.
		put(groups, "The Maiden of Sugadinti",
			NpcID.TOB_MAIDEN_100, NpcID.TOB_MAIDEN_70, NpcID.TOB_MAIDEN_50, NpcID.TOB_MAIDEN_30,
			NpcID.TOB_MAIDEN_100_STORY, NpcID.TOB_MAIDEN_70_STORY,
			NpcID.TOB_MAIDEN_50_STORY, NpcID.TOB_MAIDEN_30_STORY,
			NpcID.TOB_MAIDEN_100_HARD, NpcID.TOB_MAIDEN_70_HARD,
			NpcID.TOB_MAIDEN_50_HARD, NpcID.TOB_MAIDEN_30_HARD,
			NpcID.MAIDEN_ELEMENTAL, NpcID.MAIDEN_ELEMENTAL_STORY, NpcID.MAIDEN_ELEMENTAL_HARD,
			NpcID.MAIDEN_BLOOD_SLUG, NpcID.MAIDEN_BLOOD_SLUG_STORY, NpcID.MAIDEN_BLOOD_SLUG_HARD);

		return Collections.unmodifiableMap(groups);
	}

	private static Set<Integer> buildIgnored()
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			NpcID.TOA_ZEBAK_JUG, NpcID.TOA_ZEBAK_JUG_ROLLING)));
	}

	private static void put(Map<Integer, String> groups, String name, int... npcIds)
	{
		for (int id : npcIds)
		{
			groups.put(id, name);
		}
	}
}
