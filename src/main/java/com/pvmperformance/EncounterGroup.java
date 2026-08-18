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
	private static final Set<Integer> UNSCORED = buildUnscored();
	private static final Set<Integer> UNATTACKABLE = buildUnattackable();

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

	/**
	 * NPCs that are part of the room but must not be scored: the time spent on
	 * them counts, the damage does not.
	 *
	 * <p>Kephri's healing scarabs are the case. They have to be killed and the
	 * ticks are real, but at 10 hitpoints every hit on one is a max hit that
	 * lands, so counting them would drag the room's accuracy towards 100% and
	 * its average hit towards the weapon's ceiling — flattering exactly the
	 * player who spent the longest on them.
	 */
	static boolean isUnscored(int npcId)
	{
		return UNSCORED.contains(npcId);
	}

	/**
	 * Forms an NPC wears while it cannot be fought — a transition, a charge, a
	 * death that has not despawned yet. No tick is lost while the target is one
	 * of these, because no attack was possible.
	 *
	 * <p>This only catches bosses that change id to say so. Bloat does not: he
	 * keeps one id whether he is walking or down, so his walking phase can only
	 * be told from his animation and is not covered here.
	 */
	static boolean isUnattackable(int npcId)
	{
		return UNATTACKABLE.contains(npcId);
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
		put(groups, "Tekton",
			NpcID.RAIDS_TEKTON_WAITING, NpcID.RAIDS_TEKTON_HAMMERING,
			NpcID.RAIDS_TEKTON_WALKING_STANDARD, NpcID.RAIDS_TEKTON_FIGHTING_STANDARD,
			NpcID.RAIDS_TEKTON_WALKING_ENRAGED, NpcID.RAIDS_TEKTON_FIGHTING_ENRAGED);
		// Vespula's portal is a separate NPC and is what most teams actually
		// kill, so ungrouped the room's damage would file itself under the
		// portal and leave Vespula looking untouched.
		put(groups, "Vespula",
			NpcID.RAIDS_VESPULA_FLYING, NpcID.RAIDS_VESPULA_WALKING, NpcID.RAIDS_VESPULA_ENRAGED,
			NpcID.RAIDS_VESPULA_PORTAL,
			NpcID.RAIDS_VESPULA_VESPINE_FLYING, NpcID.RAIDS_VESPULA_VESPINE_WALKING);
		put(groups, "Guardians",
			NpcID.RAIDS_STONEGUARDIANS_LEFT, NpcID.RAIDS_STONEGUARDIANS_RIGHT);
		put(groups, "Lizardman shamans",
			NpcID.RAIDS_LIZARDSHAMAN_A, NpcID.RAIDS_LIZARDSHAMAN_B, NpcID.RAIDS_LIZARDSHAMAN_BLOCKER);
		put(groups, "Skeletal mystics",
			NpcID.RAIDS_SKELETONMYSTIC_A, NpcID.RAIDS_SKELETONMYSTIC_B, NpcID.RAIDS_SKELETONMYSTIC_C);
		put(groups, "Tightrope",
			NpcID.RAIDS_TIGHTROPE_RANGER, NpcID.RAIDS_TIGHTROPE_MAGE);
		put(groups, "Great Olm (hands)",
			NpcID.OLM_HAND_LEFT_SPAWNING, NpcID.OLM_HAND_LEFT, NpcID.OLM_HAND_LEFT_DYING,
			NpcID.OLM_HAND_RIGHT_SPAWNING, NpcID.OLM_HAND_RIGHT, NpcID.OLM_HAND_RIGHT_DYING);

		// Tombs of Amascut. Zebak and Kephri change id mid-fight for the same
		// fight; the monkey room's baboons are a room rather than a set of
		// kills.
		put(groups, "Zebak", NpcID.TOA_ZEBAK, NpcID.TOA_ZEBAK_ENRAGED);
		// Akkha takes his shadows and his enrage with him: the shadows are the
		// same fight interrupted, not four separate kills.
		put(groups, "Akkha",
			NpcID.AKKHA_SPAWN, NpcID.AKKHA_MELEE, NpcID.AKKHA_RANGE, NpcID.AKKHA_MAGE,
			NpcID.AKKHA_ENRAGE_SPAWN, NpcID.AKKHA_ENRAGE_INITIAL, NpcID.AKKHA_ENRAGE,
			NpcID.AKKHA_SHADOW, NpcID.AKKHA_SHADOW_ENRAGE);
		// Kephri takes her whole room: her own forms, the scarabs that come with
		// them, and the eggs — which are grouped rather than ignored because
		// killing them is a real job for anyone whose gear cannot outpace them.
		put(groups, "Kephri",
			NpcID.TOA_KEPHRI_BOSS_SHIELDED, NpcID.TOA_KEPHRI_BOSS_WEAK, NpcID.TOA_KEPHRI_BOSS_ENRAGE,
			NpcID.TOA_KEPHRI_SHIELD_SCARAB, NpcID.TOA_KEPHRI_GUARDIAN_MELEE,
			NpcID.TOA_KEPHRI_GUARDIAN_RANGED, NpcID.TOA_KEPHRI_GUARDIAN_MAGE,
			NpcID.TOA_KEPHRI_SCARAB_RANGEKITE,
			NpcID.KEPHRI_EGG_EXPLODE, NpcID.KEPHRI_EGG_HATCH);
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

	private static Set<Integer> buildUnscored()
	{
		return Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(
			NpcID.TOA_KEPHRI_SHIELD_SCARAB)));
	}

	private static Set<Integer> buildUnattackable()
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			// Sotetseg names his own state: he wears a separate id for the maze.
			NpcID.TOB_SOTETSEG_NONCOMBAT, NpcID.TOB_SOTETSEG_NONCOMBAT_STORY,
			NpcID.TOB_SOTETSEG_NONCOMBAT_HARD,
			// Verzik between phases.
			NpcID.VERZIK_PHASE1_TO2_TRANSITION, NpcID.VERZIK_PHASE2_TO3_TRANSITION,
			NpcID.VERZIK_PHASE1_TO2_TRANSITION_STORY, NpcID.VERZIK_PHASE2_TO3_TRANSITION_STORY,
			NpcID.VERZIK_PHASE1_TO2_TRANSITION_HARD, NpcID.VERZIK_PHASE2_TO3_TRANSITION_HARD,
			// The Wardens before they wake and while they charge.
			NpcID.TOA_WARDEN_ELIDINIS_PHASE1_INACTIVE, NpcID.TOA_WARDEN_TUMEKEN_PHASE1_INACTIVE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE3_INACTIVE, NpcID.TOA_WARDEN_TUMEKEN_PHASE3_INACTIVE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE3_CHARGING, NpcID.TOA_WARDEN_TUMEKEN_PHASE3_CHARGING,
			NpcID.TOA_WARDENS_P1_OBELISK_NPC_INACTIVE,
			// Dead or dying, but still standing in the scene.
			NpcID.TOA_KEPHRI_BOSS_DEAD, NpcID.TOA_ZEBAK_DEAD,
			NpcID.OLM_HAND_LEFT_DYING, NpcID.OLM_HAND_RIGHT_DYING,
			NpcID.RAIDS_STONEGUARDIANS_LEFT_DEAD, NpcID.RAIDS_STONEGUARDIANS_RIGHT_DEAD,
			// The ice demon is only fightable once the brazier is lit.
			NpcID.RAIDS_ICEDEMON_NONCOMBAT,
			// Akkha's stand-ins while his shadows are up, and Kephri's corpse.
			NpcID.AKKHA_ENRAGE_DUMMY, NpcID.AKKHA_SHADOW_ENRAGE_DUMMY,
			NpcID.TOB_MAIDEN_DYING_A, NpcID.TOB_MAIDEN_DYING_B,
			NpcID.TOB_MAIDEN_DYING_A_STORY, NpcID.TOB_MAIDEN_DYING_B_STORY,
			NpcID.TOB_MAIDEN_DYING_A_HARD, NpcID.TOB_MAIDEN_DYING_B_HARD)));
	}

	private static void put(Map<Integer, String> groups, String name, int... npcIds)
	{
		for (int id : npcIds)
		{
			groups.put(id, name);
		}
	}
}
