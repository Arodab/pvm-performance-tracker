package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.NpcID;

// Which NPCs are worth reading together.
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

	// NPCs that are part of the room but must not be scored: the time spent on
	// them counts, the damage does not.
	static boolean isUnscored(int npcId)
	{
		return UNSCORED.contains(npcId);
	}

	// Forms an NPC wears while it cannot be fought, a transition, a charge, a
	// death that has not despawned yet. No tick is lost while the target is
	// one of these, because no attack was possible.
	static boolean isUnattackable(int npcId)
	{
		return UNATTACKABLE.contains(npcId);
	}

	private static Map<Integer, String> buildGroups()
	{
		final Map<Integer, String> groups = new HashMap<>();

		// Chambers of Xeric. The vanguards are one fight, since they must be
		// brought down together. Vasa's crystals are his healing mechanic. Olm's
		// hands are grouped with each other but not with the head, and they
		// respawn each phase rather than transforming.
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

		// Phosani's Nightmare takes her totems, which are the other half of the
		// fight. Her adds are grouped too but scored out below. The totem ids
		// are shared with The Nightmare, so the boss in the room decides which
		// it is: see labelNightmareTotem. Listing them here is the default for
		// when no boss has been seen.
		put(groups, "Phosani's Nightmare",
			NpcID.NIGHTMARE_CHALLENGE_INITIAL, NpcID.NIGHTMARE_CHALLENGE_BLAST,
			NpcID.NIGHTMARE_CHALLENGE_PHASE_01, NpcID.NIGHTMARE_CHALLENGE_PHASE_02,
			NpcID.NIGHTMARE_CHALLENGE_PHASE_03, NpcID.NIGHTMARE_CHALLENGE_PHASE_04,
			NpcID.NIGHTMARE_CHALLENGE_PHASE_05,
			NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_01, NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_02,
			NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_03, NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_04,
			NpcID.NIGHTMARE_TOTEM_1_READY, NpcID.NIGHTMARE_TOTEM_1_CHARGED,
			NpcID.NIGHTMARE_TOTEM_2_READY, NpcID.NIGHTMARE_TOTEM_2_CHARGED,
			NpcID.NIGHTMARE_TOTEM_3_READY, NpcID.NIGHTMARE_TOTEM_3_CHARGED,
			NpcID.NIGHTMARE_TOTEM_4_READY, NpcID.NIGHTMARE_TOTEM_4_CHARGED,
			NpcID.NIGHTMARE_CHALLENGE_HUSK_MAGIC, NpcID.NIGHTMARE_CHALLENGE_HUSK_RANGED,
			NpcID.NIGHTMARE_CHALLENGE_PARASITE, NpcID.NIGHTMARE_CHALLENGE_PARASITE_WEAK,
			NpcID.NIGHTMARE_CHALLENGE_SLEEPWALKER);

		// The Nightmare, whose totems these also are. Her own forms are grouped
		// so her phases do not split the room.
		put(groups, "The Nightmare",
			NpcID.NIGHTMARE_INITIAL, NpcID.NIGHTMARE_BLAST,
			NpcID.NIGHTMARE_PHASE_01, NpcID.NIGHTMARE_PHASE_02, NpcID.NIGHTMARE_PHASE_03,
			NpcID.NIGHTMARE_WEAK_PHASE_01, NpcID.NIGHTMARE_WEAK_PHASE_02,
			NpcID.NIGHTMARE_WEAK_PHASE_03);

		put(groups, "Yama", NpcID.YAMA, NpcID.YAMA_JUDGE_OF_YAMA, NpcID.LEAGUE6_JUDGE_OF_YAMA);

		// Tombs of Amascut. Zebak and Kephri change id mid-fight for the same
		// fight; the monkey room's baboons are a room rather than a set of kills.
		put(groups, "Zebak", NpcID.TOA_ZEBAK, NpcID.TOA_ZEBAK_ENRAGED);
		// Akkha takes his shadows and his enrage with him: the shadows are the
		// same fight interrupted, not four separate kills.
		put(groups, "Akkha",
			NpcID.AKKHA_SPAWN, NpcID.AKKHA_MELEE, NpcID.AKKHA_RANGE, NpcID.AKKHA_MAGE,
			NpcID.AKKHA_ENRAGE_SPAWN, NpcID.AKKHA_ENRAGE_INITIAL, NpcID.AKKHA_ENRAGE,
			NpcID.AKKHA_SHADOW, NpcID.AKKHA_SHADOW_ENRAGE,
			NpcID.AKKHA_ENRAGE_DUMMY, NpcID.AKKHA_SHADOW_ENRAGE_DUMMY);
		// Kephri takes her whole room: her own forms, the scarabs that come with
		// them, and the eggs, which are grouped rather than ignored because
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

		// Theatre of Blood.
		// The nylocas group by style, big with small: one colour is one job.
		// Both the incoming form and the fighting form it becomes on
		// reaching a pillar are listed, only the incoming ones were, which
		// left every nylocas killed after it settled in reporting itself.
		put(groups, "Nylocas (melee)",
			NpcID.TOB_NYLOCAS_INCOMING_MELEE, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE,
			NpcID.TOB_NYLOCAS_FIGHTING_MELEE, NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE,
			NpcID.TOB_NYLOCAS_INCOMING_MELEE_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE_STORY,
			NpcID.TOB_NYLOCAS_FIGHTING_MELEE_STORY, NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE_STORY,
			NpcID.TOB_NYLOCAS_INCOMING_MELEE_HARD, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE_HARD,
			NpcID.TOB_NYLOCAS_FIGHTING_MELEE_HARD, NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE_HARD);

		put(groups, "Nylocas (ranged)",
			NpcID.TOB_NYLOCAS_INCOMING_RANGED, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED,
			NpcID.TOB_NYLOCAS_FIGHTING_RANGED, NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED,
			NpcID.TOB_NYLOCAS_INCOMING_RANGED_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED_STORY,
			NpcID.TOB_NYLOCAS_FIGHTING_RANGED_STORY, NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED_STORY,
			NpcID.TOB_NYLOCAS_INCOMING_RANGED_HARD, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED_HARD,
			NpcID.TOB_NYLOCAS_FIGHTING_RANGED_HARD, NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED_HARD);

		put(groups, "Nylocas (magic)",
			NpcID.TOB_NYLOCAS_INCOMING_MAGIC, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC,
			NpcID.TOB_NYLOCAS_FIGHTING_MAGIC, NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC,
			NpcID.TOB_NYLOCAS_INCOMING_MAGIC_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC_STORY,
			NpcID.TOB_NYLOCAS_FIGHTING_MAGIC_STORY, NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC_STORY,
			NpcID.TOB_NYLOCAS_INCOMING_MAGIC_HARD, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC_HARD,
			NpcID.TOB_NYLOCAS_FIGHTING_MAGIC_HARD, NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC_HARD);

		// The boss rotates its style rather than being one of them, so it is its
		// own room rather than three.
		put(groups, "Nylocas Vasilias",
			NpcID.NYLOCAS_BOSS_SPAWNING, NpcID.NYLOCAS_BOSS_MELEE, NpcID.NYLOCAS_BOSS_MAGIC,
			NpcID.NYLOCAS_BOSS_RANGED, NpcID.NYLOCAS_BOSS_SPAWNING_STORY, NpcID.NYLOCAS_BOSS_MELEE_STORY,
			NpcID.NYLOCAS_BOSS_MAGIC_STORY, NpcID.NYLOCAS_BOSS_RANGED_STORY,
			NpcID.NYLOCAS_BOSS_SPAWNING_HARD, NpcID.NYLOCAS_BOSS_MELEE_HARD,
			NpcID.NYLOCAS_BOSS_MAGIC_HARD, NpcID.NYLOCAS_BOSS_RANGED_HARD);

		// Hard mode only.
		put(groups, "Nylocas Prinkipas",
			NpcID.NYLOCAS_MINIBOSS_SPAWNING_HARD, NpcID.NYLOCAS_MINIBOSS_MELEE_HARD,
			NpcID.NYLOCAS_MINIBOSS_MAGIC_HARD, NpcID.NYLOCAS_MINIBOSS_RANGED_HARD);

		// Healing off the exhumeds, standing, and staring are one fight.
		put(groups, "Xarpus",
			NpcID.TOB_XARPUS_STATIC, NpcID.TOB_XARPUS_FEEDING, NpcID.TOB_XARPUS_COMBAT,
			NpcID.TOB_XARPUS_STATIC_STORY, NpcID.TOB_XARPUS_FEEDING_STORY, NpcID.TOB_XARPUS_COMBAT_STORY,
			NpcID.TOB_XARPUS_STATIC_HARD, NpcID.TOB_XARPUS_FEEDING_HARD, NpcID.TOB_XARPUS_COMBAT_HARD);

		// Three phases across two wardens, their obelisks and their cores. The
		// four statues named for the path bosses are left out: they are scenery,
		// and nothing here should rest on a name alone.
		put(groups, "Wardens",
			NpcID.TOA_WARDENS_P1_OBELISK_NPC, NpcID.TOA_WARDENS_P2_OBELISK_NPC,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE1, NpcID.TOA_WARDEN_TUMEKEN_PHASE1,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE1_INACTIVE, NpcID.TOA_WARDEN_TUMEKEN_PHASE1_INACTIVE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE,
			NpcID.TOA_WARDEN_TUMEKEN_PHASE2_RANGE, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE3, NpcID.TOA_WARDEN_TUMEKEN_PHASE3,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE3_INACTIVE, NpcID.TOA_WARDEN_TUMEKEN_PHASE3_INACTIVE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE3_CHARGING, NpcID.TOA_WARDEN_TUMEKEN_PHASE3_CHARGING,
			NpcID.TOA_WARDEN_ELIDINIS_CORE, NpcID.TOA_WARDEN_TUMEKEN_CORE, NpcID.TOA_WARDENS_ENERGY);

		put(groups, "Ba-Ba",
			NpcID.TOA_BABA, NpcID.TOA_BABA_DIGGING, NpcID.TOA_BABA_BABOON, NpcID.TOA_BABA_BOULDER,
			NpcID.TOA_BABA_BOULDER_WEAK);

		// The meat tree is the healing mechanic, as Vasa's crystals are.
		put(groups, "Muttadiles",
			NpcID.RAIDS_DOGODILE, NpcID.RAIDS_DOGODILE_SUBMERGED, NpcID.RAIDS_DOGODILE_JUNIOR,
			NpcID.RAIDS_DOGODILE_MEAT_TREE);

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

	/**
	 * Whether this is one of the totems, which both Nightmares share the ids of.
	 * Which fight a totem belongs to is decided by the boss standing in the room
	 * rather than by anything in the totem itself.
	 */
	static boolean isNightmareTotem(int npcId)
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

	/** Which Nightmare this NPC is a form of, or null if it is not one. */
	static String nightmareBossName(int npcId)
	{
		if (isNightmareTotem(npcId))
		{
			return null;
		}
		final String group = GROUPS.get(npcId);
		return "The Nightmare".equals(group) || "Phosani's Nightmare".equals(group) ? group : null;
	}

	private static Set<Integer> buildUnscored()
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			NpcID.TOA_KEPHRI_SHIELD_SCARAB,
			// The Judge is Yama's own add. Grouped so killing it does not reset
			// what is on screen, and unscored so it does not lend its figures to
			// his. Ignoring it outright would have been worse than either: with
			// nothing tracking the Judge, the time spent killing its 400
			// hitpoints would have been booked against Yama as ticks lost.
			NpcID.YAMA_JUDGE_OF_YAMA, NpcID.LEAGUE6_JUDGE_OF_YAMA,
			// Phosani's adds, killed in one hit apiece. Grouped rather than
			// ignored, or the time spent on them would be booked against her as
			// ticks lost; unscored, or their guaranteed max hits would flatter
			// whoever spent longest on them.
			NpcID.NIGHTMARE_CHALLENGE_HUSK_MAGIC, NpcID.NIGHTMARE_CHALLENGE_HUSK_RANGED,
			NpcID.NIGHTMARE_CHALLENGE_PARASITE, NpcID.NIGHTMARE_CHALLENGE_PARASITE_WEAK,
			NpcID.NIGHTMARE_CHALLENGE_SLEEPWALKER)));
	}

	private static Set<Integer> buildUnattackable()
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
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
			// The ice demon is only fightable once the brazier is lit, and
			// Tekton cannot be touched while he is at his anvil healing.
			NpcID.RAIDS_ICEDEMON_NONCOMBAT, NpcID.RAIDS_TEKTON_HAMMERING,
			// Sotetseg while the maze runs. Which of his two ids that is remains
			// unconfirmed; see the note above. The tell is his room reporting no
			// tick loss at all.
			NpcID.TOB_SOTETSEG_NONCOMBAT, NpcID.TOB_SOTETSEG_NONCOMBAT_STORY,
			NpcID.TOB_SOTETSEG_NONCOMBAT_HARD,
			// A totem is only open once the shield behind it is broken.
			NpcID.NIGHTMARE_TOTEM_1_DORMANT, NpcID.NIGHTMARE_TOTEM_2_DORMANT,
			NpcID.NIGHTMARE_TOTEM_3_DORMANT, NpcID.NIGHTMARE_TOTEM_4_DORMANT,
			// Akkha vanishes for his memory blast. That these two ids are what he
			// wears while gone is a guess from the name, and free either way: if
			// he despawns instead, the fight ends on its own and counts nothing.
			NpcID.AKKHA_ENRAGE_DUMMY, NpcID.AKKHA_SHADOW_ENRAGE_DUMMY,
			NpcID.TOB_MAIDEN_DYING_A, NpcID.TOB_MAIDEN_DYING_B,
			NpcID.TOB_MAIDEN_DYING_A_STORY, NpcID.TOB_MAIDEN_DYING_B_STORY,
			NpcID.TOB_MAIDEN_DYING_A_HARD, NpcID.TOB_MAIDEN_DYING_B_HARD,
			NpcID.XARPUS_DEATH, NpcID.XARPUS_DEATH_STORY, NpcID.XARPUS_DEATH_HARD,
			NpcID.TOA_WARDEN_P3_DEATH_ELIDINIS, NpcID.TOA_WARDEN_P3_DEATH_TUMEKEN)));
	}

	private static void put(Map<Integer, String> groups, String name, int... npcIds)
	{
		for (int id : npcIds)
		{
			groups.put(id, name);
		}
	}
}
