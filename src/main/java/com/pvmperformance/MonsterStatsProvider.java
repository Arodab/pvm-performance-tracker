package com.pvmperformance;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPCComposition;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.RuneLite;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * NPC defensive stats for the expected accuracy and DPS. Data is the OSRS Wiki
 * DPS calculator's monsters.json, fetched once and cached on disk, refreshed
 * when the cache ages out.
 */
@Slf4j
@Singleton
class MonsterStatsProvider
{
	private static final String URL = "https://raw.githubusercontent.com/weirdgloop/osrs-dps-calc/main/cdn/json/monsters.json";
	private static final long MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Client client;
	private final DebugLog debug;

	// npcId -> stats; replaced wholesale after a load, so reads need no lock.
	private volatile Map<Integer, MonsterStats> byId = Collections.emptyMap();
	// name -> stats, for the ids the data does not carry. A monster wears several ids as it idles, walks and fights and
	// the source lists one: Tekton appears in game as 7542 as well as the 7540 and 7543 listed. Every miss meant no
	// accuracy, no expected damage and no drain, reading as whole stretches of a raid being ignored.
	private volatile Map<String, MonsterStats> byName = Collections.emptyMap();
	private final Map<Integer, MonsterStats> dynamicCache = new java.util.concurrent.ConcurrentHashMap<>();

	// Ids already reported to the debug log, so an unknown monster says so once rather than on every attack.
	private final Set<Integer> logged = java.util.concurrent.ConcurrentHashMap.newKeySet();

	@Inject
	MonsterStatsProvider(OkHttpClient httpClient, Gson gson, ScheduledExecutorService executor,
		Client client, DebugLog debug)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.executor = executor;
		this.client = client;
		this.debug = debug;
	}

	void startUp()
	{
		executor.execute(this::loadOrFetch);
	}

	/**
	 * Ids the wiki's data does not carry, pointed at the entry that describes
	 * the same thing.
	 *
	 * <p>The data is keyed on whichever id the wiki happened to record, which is
	 * routinely NOT the id a monster wears while it is being fought: the Great
	 * Olm is listed only under the forms he wears while RISING, Verzik's first
	 * phase under the form she sits in before it starts, Tekton under the one he
	 * waits in at his anvil, and a nylocas under the form it walks in rather
	 * than the one it settles into. Every one of those meant no stats at all
	 * while the thing was actually being fought.
	 *
	 * <p>The name fallback below is not a substitute where a monster's versions
	 * DISAGREE, and these are exactly the ones that do. It takes whichever
	 * version the map holds first, so it can answer Verzik's third phase with
	 * her first phase's 20 defence, a normal Tekton with the enraged one's
	 * defensive bonuses, or a big nylocas with a small one's - and a size with
	 * it, which decides how many times a scythe hits. Each line here was checked
	 * against the entry it points at.
	 *
	 * <p>The rule for adding to this: an id belongs here when the data lists the
	 * same monster under a different id AND its versions differ. Where the
	 * versions agree, the name fallback is already right and a line here is only
	 * noise.
	 */
	private static final Map<Integer, Integer> ALIASES = buildAliases();

	private static Map<Integer, Integer> buildAliases()
	{
		final Map<Integer, Integer> aliases = new HashMap<>();

		// The Great Olm, listed only under the ids he wears while rising. The three parts are nothing alike - the head takes
		// 200s across stab, slash and crush where the melee claw takes 50s, and the mage claw's magic defence is 50 against
		// the melee claw's 200 - so the name fallback would be wrong about two targets in three.
		aliases.put(NpcID.OLM_HAND_RIGHT, NpcID.OLM_HAND_RIGHT_SPAWNING);
		aliases.put(NpcID.OLM_HAND_RIGHT_DYING, NpcID.OLM_HAND_RIGHT_SPAWNING);
		aliases.put(NpcID.OLM_HAND_LEFT, NpcID.OLM_HAND_LEFT_SPAWNING);
		aliases.put(NpcID.OLM_HAND_LEFT_DYING, NpcID.OLM_HAND_LEFT_SPAWNING);
		aliases.put(NpcID.OLM_HEAD, NpcID.OLM_HEAD_SPAWNING);

		// The Chambers guardians. Only the left one is listed, as "Guardian (Chambers of Xeric)" - which is not what the
		// game calls it, so the name fallback cannot reach it either and the right guardian had no stats at all.
		aliases.put(NpcID.RAIDS_STONEGUARDIANS_RIGHT, NpcID.RAIDS_STONEGUARDIANS_LEFT);
		aliases.put(NpcID.RAIDS_STONEGUARDIANS_LEFT_DEAD, NpcID.RAIDS_STONEGUARDIANS_LEFT);
		aliases.put(NpcID.RAIDS_STONEGUARDIANS_RIGHT_DEAD, NpcID.RAIDS_STONEGUARDIANS_LEFT);

		// Verzik's first phase. The data's "Phase 1" is the form she sits in before the fight; the one she is fought in is
		// the next id along. Her phases are the widest disagreement in the whole dataset - defence 20, 200 and 150, and
		// sizes 5, 3 and 7 - so answering P1 with P2's figures is not a rounding error.
		aliases.put(NpcID.VERZIK_PHASE1, NpcID.VERZIK_INITIAL);
		aliases.put(NpcID.VERZIK_PHASE1_HARD, NpcID.VERZIK_INITIAL_HARD);
		aliases.put(NpcID.VERZIK_PHASE1_STORY, NpcID.VERZIK_INITIAL_STORY);

		// Xarpus is listed once per mode, under the id of his final phase. The first two phases - lying still, and feeding
		// on the exhumeds - are their own ids and are fought.
		aliases.put(NpcID.TOB_XARPUS_STATIC, NpcID.TOB_XARPUS_COMBAT);
		aliases.put(NpcID.TOB_XARPUS_FEEDING, NpcID.TOB_XARPUS_COMBAT);
		aliases.put(NpcID.TOB_XARPUS_STATIC_HARD, NpcID.TOB_XARPUS_COMBAT_HARD);
		aliases.put(NpcID.TOB_XARPUS_FEEDING_HARD, NpcID.TOB_XARPUS_COMBAT_HARD);
		aliases.put(NpcID.TOB_XARPUS_STATIC_STORY, NpcID.TOB_XARPUS_COMBAT_STORY);
		aliases.put(NpcID.TOB_XARPUS_FEEDING_STORY, NpcID.TOB_XARPUS_COMBAT_STORY);

		// Tekton, listed under the form he waits in and the one he walks back in when enraged. Enraged is a different
		// monster to hit: 280, 290 and 180 against the standard 155, 165 and 105.
		aliases.put(NpcID.RAIDS_TEKTON_WALKING_STANDARD, NpcID.RAIDS_TEKTON_WAITING);
		aliases.put(NpcID.RAIDS_TEKTON_FIGHTING_STANDARD, NpcID.RAIDS_TEKTON_WAITING);
		aliases.put(NpcID.RAIDS_TEKTON_FIGHTING_ENRAGED, NpcID.RAIDS_TEKTON_WALKING_ENRAGED);

		// A second-phase warden alternates between a mage and a ranged form; only the mage one is listed, and they are the
		// same monster.
		aliases.put(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE);
		aliases.put(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_RANGE, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE);

		// The Nylocas, listed under the form each walks in and never the one it settles into to fight - which is the form
		// most of them are killed in. Big and small differ in defence, twenty against one, and in SIZE, which is what says
		// whether a scythe hits twice or once.
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_MELEE, NpcID.TOB_NYLOCAS_INCOMING_MELEE);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_RANGED, NpcID.TOB_NYLOCAS_INCOMING_RANGED);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_MAGIC, NpcID.TOB_NYLOCAS_INCOMING_MAGIC);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_MELEE_STORY, NpcID.TOB_NYLOCAS_INCOMING_MELEE_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_RANGED_STORY, NpcID.TOB_NYLOCAS_INCOMING_RANGED_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_MAGIC_STORY, NpcID.TOB_NYLOCAS_INCOMING_MAGIC_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC_STORY);

		// The Maiden wears an id per health threshold, and outside normal mode none of them is listed. Entry mode is a
		// different monster - 80 defence against 200 - so the mode has to be kept even though the thresholds do not matter.
		aliases.put(NpcID.TOB_MAIDEN_70_HARD, NpcID.TOB_MAIDEN_100_HARD);
		aliases.put(NpcID.TOB_MAIDEN_50_HARD, NpcID.TOB_MAIDEN_100_HARD);
		aliases.put(NpcID.TOB_MAIDEN_30_HARD, NpcID.TOB_MAIDEN_100_HARD);
		aliases.put(NpcID.TOB_MAIDEN_70_STORY, NpcID.TOB_MAIDEN_100_STORY);
		aliases.put(NpcID.TOB_MAIDEN_50_STORY, NpcID.TOB_MAIDEN_100_STORY);
		aliases.put(NpcID.TOB_MAIDEN_30_STORY, NpcID.TOB_MAIDEN_100_STORY);

		// The Chambers rooms that hold several copies of one monster list only the FIRST copy. The shamans matter most: the
		// data calls the listed one "Lizardman shaman (Chambers of Xeric)", which is not what the game calls it, so the two
		// unlisted shamans fell through to the plain "Lizardman shaman" of the open world - 140 defence in place of 210.
		aliases.put(NpcID.RAIDS_LIZARDSHAMAN_B, NpcID.RAIDS_LIZARDSHAMAN_A);
		aliases.put(NpcID.RAIDS_LIZARDSHAMAN_BLOCKER, NpcID.RAIDS_LIZARDSHAMAN_A);

		// The large muttadile out of the water. The wiki's own page gives the large one as "7561,7563" and lists it under
		// the first, so the fought id is unlisted and the fallback could answer it with the small one's 138 defence - and
		// with size 3 in place of 5, which is a scythe hit.
		aliases.put(NpcID.RAIDS_DOGODILE, NpcID.RAIDS_DOGODILE_SUBMERGED);

		// The Nylocas boss is listed under the form it spawns in, never the three it fights in.
		aliases.put(NpcID.NYLOCAS_BOSS_MELEE, NpcID.NYLOCAS_BOSS_SPAWNING);
		aliases.put(NpcID.NYLOCAS_BOSS_RANGED, NpcID.NYLOCAS_BOSS_SPAWNING);
		aliases.put(NpcID.NYLOCAS_BOSS_MAGIC, NpcID.NYLOCAS_BOSS_SPAWNING);
		aliases.put(NpcID.NYLOCAS_BOSS_MELEE_HARD, NpcID.NYLOCAS_BOSS_SPAWNING_HARD);
		aliases.put(NpcID.NYLOCAS_BOSS_RANGED_HARD, NpcID.NYLOCAS_BOSS_SPAWNING_HARD);
		aliases.put(NpcID.NYLOCAS_BOSS_MAGIC_HARD, NpcID.NYLOCAS_BOSS_SPAWNING_HARD);
		aliases.put(NpcID.NYLOCAS_BOSS_MELEE_STORY, NpcID.NYLOCAS_BOSS_SPAWNING_STORY);
		aliases.put(NpcID.NYLOCAS_BOSS_RANGED_STORY, NpcID.NYLOCAS_BOSS_SPAWNING_STORY);
		aliases.put(NpcID.NYLOCAS_BOSS_MAGIC_STORY, NpcID.NYLOCAS_BOSS_SPAWNING_STORY);

		return Collections.unmodifiableMap(aliases);
	}

	/** The id whose entry stands in for this one, or -1 where none does. */
	static int aliasFor(int npcId)
	{
		final Integer alias = ALIASES.get(npcId);
		return alias == null ? -1 : alias;
	}

	MonsterStats get(int npcId)
	{
		final MonsterStats exact = byId.get(npcId);
		if (exact != null)
		{
			return exact;
		}
		final Integer alias = ALIASES.get(npcId);
		if (alias != null)
		{
			final MonsterStats aliased = byId.get(alias);
			if (aliased != null)
			{
				return aliased;
			}
		}
		if (npcId < 0 || byName.isEmpty())
		{
			return null;
		}
		if (dynamicCache.containsKey(npcId))
		{
			return dynamicCache.get(npcId);
		}
		// The id is unknown, so ask the game its name and look that up. Where a name covers several versions the first wins,
		// which is the ordinary form, so a figure may be a little low but is no longer absent.
		final NPCComposition composition = client.getNpcDefinition(npcId);
		final String name = composition == null ? null : composition.getName();
		MonsterStats resolved = composition == null ? null : byName.get(normalise(name));
		if (resolved != null)
		{
			dynamicCache.put(npcId, resolved);
		}
		if (debug.isOn() && logged.add(npcId))
		{
			// Both halves are worth a line. A miss means no accuracy and no expected damage against this target at all; a
			// name match means the figures came from whichever version of that name the data happened to hold first, which
			// is right for a monster that walks and fights under several ids and wrong for one whose forms differ.
			debug.log(resolved == null
					? "NO MONSTER STATS for npc %d ('%s'): no id, no alias and no name match - nothing will be expected "
						+ "against this target"
					: "monster stats for npc %d ('%s') resolved by NAME, not by id",
				npcId, name);
		}
		return resolved;
	}

	private static String normalise(String name)
	{
		return name == null ? "" : Text.removeTags(name).toLowerCase().trim();
	}

	private Path cacheFile()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, "pvm-performance-tracker"), "monsters.json").toPath();
	}

	private void loadOrFetch()
	{
		final Path cache = cacheFile();
		try
		{
			if (Files.exists(cache))
			{
				parse(Files.newBufferedReader(cache, StandardCharsets.UTF_8));
				final long age = System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis();
				if (age < MAX_AGE_MILLIS)
				{
					return; // fresh enough
				}
			}
		}
		catch (Exception e)
		{
			log.warn("PvM Performance: could not read cached monster data", e);
		}
		fetch();
	}

	private void fetch()
	{
		final Request request = new Request.Builder()
			.url(URL)
			.header("User-Agent", "RuneLite plugin pvm-performance-tracker")
			.build();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("PvM Performance: monster data fetch failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						log.warn("PvM Performance: monster data fetch returned {}", r.code());
						return;
					}
					final byte[] bytes = r.body().bytes();
					final Path cache = cacheFile();
					Files.createDirectories(cache.getParent());
					Files.write(cache, bytes);
					parse(new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
				}
				catch (Exception e)
				{
					log.warn("PvM Performance: could not store monster data", e);
				}
			}
		});
	}

	private void parse(Reader reader) throws IOException
	{
		try (Reader r = reader)
		{
			final MonsterJson[] monsters = gson.fromJson(r, MonsterJson[].class);
			if (monsters == null)
			{
				return;
			}
			final Map<Integer, MonsterStats> map = new HashMap<>(monsters.length * 2);
			for (MonsterJson m : monsters)
			{
				if (m == null || m.skills == null || m.defensive == null)
				{
					continue;
				}
				// First version of each id wins; later variants (phases) are ignored for now.
				map.putIfAbsent(m.id, new MonsterStats(
					m.name == null ? "" : m.name, m.size,
					m.skills.hitpoints,
					m.skills.defence, m.skills.magic,
					m.offensive == null ? 0 : m.offensive.magic,
					m.defensive.stab, m.defensive.slash, m.defensive.crush,
					m.defensive.magic, m.defensive.standard,
					m.weakness == null || m.weakness.element == null
						? null : m.weakness.element.toLowerCase(Locale.ROOT).trim(),
					m.weakness == null ? 0 : m.weakness.severity,
					m.attributes == null
						? Collections.emptySet()
						: new HashSet<>(Arrays.asList(m.attributes))));
			}
			byId = map;
			final Map<String, MonsterStats> names = new HashMap<>(map.size() * 2);
			for (MonsterStats stats : map.values())
			{
				names.putIfAbsent(normalise(stats.getName()), stats);
			}
			byName = names;
			dynamicCache.clear();
			logged.clear();
			log.debug("PvM Performance: loaded {} monster stat entries", map.size());
		}
	}

	@Getter
	static class MonsterStats
	{
		/** Used to look up the few monsters that alter demonbane effectiveness. */
		private final String name;
		/** Tiles across, which the colossal blade scales its damage on. */
		private final int size;
		/**
		 * Full hitpoints. RuneLite's own table is the usual source for these and
		 * has nothing at all for the Chambers - maxHp read -1 on all 364 fights of
		 * one raid export - so this is what makes a remaining-health figure
		 * possible in a raid.
		 */
		private final int hitpoints;
		private final int defenceLevel;
		private final int magicLevel;
		/** The monster's offensive magic bonus, which the twisted bow also scales on. */
		private final int offensiveMagic;
		private final int defStab;
		private final int defSlash;
		private final int defCrush;
		private final int defMagic;
		private final int defRanged;
		/** Wiki attribute tags ("undead", "dragon", "demon", ...) driving gear bonuses. */
		private final Set<String> attributes;
		/**
		 * The element this monster is weak to, lower-cased, or null. Wiki
		 * (Elemental weakness): each point is worth 1% magic damage and 1% magic
		 * accuracy to a spell of that element.
		 */
		private final String weaknessElement;
		/** How many points of it, 0 when there is none. */
		private final int weaknessSeverity;

		MonsterStats(String name, int size, int hitpoints, int defenceLevel, int magicLevel, int offensiveMagic,
			int defStab, int defSlash, int defCrush, int defMagic, int defRanged, String weaknessElement,
			int weaknessSeverity, Set<String> attributes)
		{
			this.hitpoints = hitpoints;
			this.weaknessElement = weaknessElement;
			this.weaknessSeverity = weaknessSeverity;
			this.name = name;
			this.size = size;
			this.defenceLevel = defenceLevel;
			this.magicLevel = magicLevel;
			this.offensiveMagic = offensiveMagic;
			this.defStab = defStab;
			this.defSlash = defSlash;
			this.defCrush = defCrush;
			this.defMagic = defMagic;
			this.defRanged = defRanged;
			this.attributes = attributes;
		}

		boolean hasAttribute(String attribute)
		{
			return attributes.contains(attribute);
		}
	}

	private static class MonsterJson
	{
		int id;
		String name;
		int size;
		Skills skills;
		Offensive offensive;
		Defensive defensive;
		String[] attributes;
		Weakness weakness;

		static class Weakness
		{
			String element;
			int severity;
		}

		static class Skills
		{
			@SerializedName("def")
			int defence;
			int magic;
			@SerializedName("hp")
			int hitpoints;
		}

		static class Offensive
		{
			int magic;
		}

		static class Defensive
		{
			int stab;
			int slash;
			int crush;
			int magic;
			int standard;
		}
	}
}
