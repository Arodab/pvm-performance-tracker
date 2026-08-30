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

	// npcId -> stats; replaced wholesale after a load, so reads need no lock.
	private volatile Map<Integer, MonsterStats> byId = Collections.emptyMap();
	// name -> stats, for the ids the data does not carry. A monster wears several
	// ids as it idles, walks and fights and the source lists one: Tekton appears
	// in game as 7542 as well as the 7540 and 7543 listed. Every miss meant no
	// accuracy, no expected damage and no drain, reading as whole stretches of a
	// raid being ignored.
	private volatile Map<String, MonsterStats> byName = Collections.emptyMap();
	private final Map<Integer, MonsterStats> dynamicCache = new java.util.concurrent.ConcurrentHashMap<>();

	@Inject
	MonsterStatsProvider(OkHttpClient httpClient, Gson gson, ScheduledExecutorService executor,
		Client client)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.executor = executor;
		this.client = client;
	}

	void startUp()
	{
		executor.execute(this::loadOrFetch);
	}

	MonsterStats get(int npcId)
	{
		final MonsterStats exact = byId.get(npcId);
		if (exact != null)
		{
			return exact;
		}
		if (npcId < 0 || byName.isEmpty())
		{
			return null;
		}
		if (dynamicCache.containsKey(npcId))
		{
			return dynamicCache.get(npcId);
		}
		// The id is unknown, so ask the game its name and look that up. Where a name
		// covers several versions the first wins, which is the ordinary form, so a
		// figure may be a little low but is no longer absent.
		final NPCComposition composition = client.getNpcDefinition(npcId);
		MonsterStats resolved = composition == null ? null : byName.get(normalise(composition.getName()));
		if (resolved != null)
		{
			dynamicCache.put(npcId, resolved);
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

		MonsterStats(String name, int size, int defenceLevel, int magicLevel, int offensiveMagic, int defStab,
			int defSlash, int defCrush, int defMagic, int defRanged, String weaknessElement,
			int weaknessSeverity, Set<String> attributes)
		{
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
