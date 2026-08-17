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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Supplies NPC defensive stats (defence level, per-style defence bonuses) for
 * the expected accuracy/DPS calc. Data is the OSRS Wiki DPS calculator's
 * monsters.json (maintained, keyed by NPC id, CC-BY-SA game facts); it is
 * fetched once and cached on disk, refreshed when the cache ages out.
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

	// npcId -> stats; replaced wholesale after a load, so reads need no lock.
	private volatile Map<Integer, MonsterStats> byId = Collections.emptyMap();

	@Inject
	MonsterStatsProvider(OkHttpClient httpClient, Gson gson, ScheduledExecutorService executor)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.executor = executor;
	}

	void startUp()
	{
		executor.execute(this::loadOrFetch);
	}

	MonsterStats get(int npcId)
	{
		return byId.get(npcId);
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
					m.skills.defence, m.skills.magic,
					m.defensive.stab, m.defensive.slash, m.defensive.crush,
					m.defensive.magic, m.defensive.standard));
			}
			byId = map;
			log.debug("PvM Performance: loaded {} monster stat entries", map.size());
		}
	}

	@Getter
	static class MonsterStats
	{
		private final int defenceLevel;
		private final int magicLevel;
		private final int defStab;
		private final int defSlash;
		private final int defCrush;
		private final int defMagic;
		private final int defRanged;

		MonsterStats(int defenceLevel, int magicLevel, int defStab, int defSlash, int defCrush, int defMagic, int defRanged)
		{
			this.defenceLevel = defenceLevel;
			this.magicLevel = magicLevel;
			this.defStab = defStab;
			this.defSlash = defSlash;
			this.defCrush = defCrush;
			this.defMagic = defMagic;
			this.defRanged = defRanged;
		}
	}

	private static class MonsterJson
	{
		int id;
		Skills skills;
		Defensive defensive;

		static class Skills
		{
			@SerializedName("def")
			int defence;
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
