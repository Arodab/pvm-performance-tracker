package com.pvmperformance;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GraphicID;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.NPCManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "PvM Performance Tracker",
	description = "Tracks your real vs expected combat performance against any NPC",
	tags = {"pvm", "dps", "combat", "performance", "damage", "accuracy", "boss"}
)
public class PvmPerformancePlugin extends Plugin
{
	private static final int MAX_HISTORY = 500;
	private static final String EXPORT_DIR = "pvm-performance-tracker";
	// Single-NPC bosses taken from the RuneLite hiscore list. Raid/minigame
	// entries (Chambers of Xeric, Theatre of Blood, Tempoross, ...) are also on
	// that list but their names never match a combat NPC, so they're naturally
	// excluded here and handled separately later.
	private static final Set<String> BOSS_NAMES = buildBossNames();
	private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final DateTimeFormatter ROW_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PvmPerformanceOverlay overlay;

	@Inject
	private PvmPerformanceConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private NPCManager npcManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private CombatCalc combatCalc;

	private PvmPerformancePanel panel;
	private NavigationButton navButton;

	// Expected melee max hit for the current loadout, recomputed each tick.
	private int expectedMeleeMaxHit;

	// The fight currently in progress, or null between fights.
	private Fight current;
	// The most recently finished fight, kept so the overlay lingers briefly.
	private Fight lastFinished;
	// Persisted history, most-recent first. Copy-on-write so the panel (EDT) can
	// iterate it safely while combat events (client thread) append to it.
	private final List<Fight> history = new CopyOnWriteArrayList<>();

	// My in-flight projectiles, so each is credited only once (identity set).
	private final Set<Projectile> countedProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
	// npcIndex -> my launched attacks not yet resolved to a hit or a splash.
	// A magic splash carries no caster info, so it only counts as mine when it
	// resolves one of these; that also excludes other players' splashes.
	private final Map<Integer, Integer> pendingMineHits = new HashMap<>();

	@Provides
	PvmPerformanceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PvmPerformanceConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);

		panel = new PvmPerformancePanel(this);
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/pvmperformance/panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PvM Performance Tracker")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		executor.execute(this::loadHistory);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		current = null;
		lastFinished = null;
		history.clear();
		countedProjectiles.clear();
		pendingMineHits.clear();
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		final Actor actor = event.getActor();
		final Hitsplat hitsplat = event.getHitsplat();
		final long now = System.currentTimeMillis();

		if (actor instanceof NPC && hitsplat.isMine())
		{
			final NPC npc = (NPC) actor;
			if (current == null || current.isEnded() || current.getTargetIndex() != npc.getIndex())
			{
				startFight(npc, now);
			}
			current.recordDamageDealt(hitsplat.getAmount(), now);
			// A landed hit resolves one of my pending attacks so it can't later
			// be mistaken for another player's splash.
			consumePending(npc.getIndex());
		}
		else if (actor == client.getLocalPlayer() && current != null && !current.isEnded())
		{
			// Damage on us during an active fight is attributed to it.
			current.recordDamageTaken(hitsplat.getAmount(), now);
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		// Magic/ranged attacks fire a projectile before impact. One that starts
		// on my tile is mine, which lets a fight begin on my first cast (even if
		// it splashes) and lets a later splash be attributed to me, not others.
		final Projectile projectile = event.getProjectile();
		final Player me = client.getLocalPlayer();
		final Actor target = projectile.getTargetActor();
		if (me == null || !(target instanceof NPC))
		{
			return;
		}
		final WorldPoint source = projectile.getSourcePoint();
		if (source == null || !source.equals(me.getWorldLocation()))
		{
			return;
		}
		// The tile alone can't separate two players stacked on it, so also
		// require the projectile to target the NPC I'm actually attacking.
		if (me.getInteracting() != target)
		{
			return;
		}
		if (!countedProjectiles.add(projectile))
		{
			return; // this projectile was already counted on an earlier frame
		}

		final NPC npc = (NPC) target;
		final long now = System.currentTimeMillis();
		if (current == null || current.isEnded() || current.getTargetIndex() != npc.getIndex())
		{
			startFight(npc, now);
		}
		pendingMineHits.merge(npc.getIndex(), 1, Integer::sum);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		// A magic splash produces no hitsplat — only a graphic on the target —
		// so we count it here as a missed attempt. It only counts if it resolves
		// one of my own casts, which excludes other players' splashes.
		if (current == null || current.isEnded())
		{
			return;
		}
		final Actor actor = event.getActor();
		if (!(actor instanceof NPC) || !actor.hasSpotAnim(GraphicID.SPLASH))
		{
			return;
		}
		final int index = ((NPC) actor).getIndex();
		if (consumePending(index) && current.getTargetIndex() == index)
		{
			current.recordSplash(System.currentTimeMillis());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		final NPC npc = event.getNpc();
		if (current != null && !current.isEnded() && current.getTargetIndex() == npc.getIndex())
		{
			finalizeFight(npc.isDead(), System.currentTimeMillis());
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// Drop projectiles that have landed so the set doesn't retain them.
		countedProjectiles.removeIf(p -> p.getRemainingCycles() <= 0);

		expectedMeleeMaxHit = combatCalc.meleeMaxHit();

		if (current != null && !current.isEnded())
		{
			final long idle = System.currentTimeMillis() - current.getLastActivityMillis();
			if (idle > config.fightTimeoutTicks() * 600L)
			{
				finalizeFight(false, current.getLastActivityMillis());
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			if (current != null && !current.isEnded())
			{
				finalizeFight(false, current.getLastActivityMillis());
			}
			countedProjectiles.clear();
			pendingMineHits.clear();
		}
	}

	private void startFight(NPC npc, long now)
	{
		if (current != null && !current.isEnded())
		{
			finalizeFight(false, now);
		}
		current = new Fight(npc.getName(), npc.getId(), npc.getIndex(), npcManager.getHealth(npc.getId()), now);
	}

	private void finalizeFight(boolean died, long now)
	{
		current.end(died, now);
		history.add(0, current);
		while (history.size() > MAX_HISTORY)
		{
			history.remove(history.size() - 1);
		}
		lastFinished = current;
		current = null;
		pendingMineHits.clear();
		if (panel != null)
		{
			panel.refresh();
		}
		saveHistory();
	}

	private Path historyFile()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, EXPORT_DIR), "history.json").toPath();
	}

	private void loadHistory()
	{
		final Path file = historyFile();
		if (!Files.exists(file))
		{
			return;
		}
		try
		{
			final String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			final Fight[] loaded = gson.fromJson(json, Fight[].class);
			if (loaded != null)
			{
				for (Fight fight : loaded)
				{
					if (history.size() < MAX_HISTORY)
					{
						history.add(fight);
					}
				}
			}
		}
		catch (Exception e)
		{
			log.warn("PvM Performance: could not load history", e);
		}
		if (panel != null)
		{
			panel.refresh();
		}
	}

	private void saveHistory()
	{
		// Snapshot now so a later shutdown/clear can't race the write.
		final List<Fight> snapshot = new ArrayList<>(history);
		executor.execute(() ->
		{
			try
			{
				final Path file = historyFile();
				Files.createDirectories(file.getParent());
				Files.write(file, gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException e)
			{
				log.warn("PvM Performance: could not save history", e);
			}
		});
	}

	/** Consumes one of my pending attacks on the NPC; false if none were pending. */
	private boolean consumePending(int npcIndex)
	{
		final Integer pending = pendingMineHits.get(npcIndex);
		if (pending == null || pending <= 0)
		{
			return false;
		}
		pendingMineHits.put(npcIndex, pending - 1);
		return true;
	}

	/** The fight the overlay should display: the active one, else the last finished. */
	Fight getDisplayFight()
	{
		return current != null ? current : lastFinished;
	}

	/** Expected melee max hit for the current loadout (0 if unknown). */
	int getExpectedMeleeMaxHit()
	{
		return expectedMeleeMaxHit;
	}

	/** Per-NPC aggregates in most-recently-fought order; optionally bosses only. */
	List<NpcStats> getNpcStats(boolean bossOnly)
	{
		final Map<String, NpcStats> byName = new LinkedHashMap<>();
		for (Fight fight : history)
		{
			if (bossOnly && !isBoss(fight))
			{
				continue;
			}
			byName.computeIfAbsent(fight.getTargetName(), NpcStats::new).add(fight);
		}
		return new ArrayList<>(byName.values());
	}

	private boolean isBoss(Fight fight)
	{
		return BOSS_NAMES.contains(normalizeName(fight.getTargetName()));
	}

	private static Set<String> buildBossNames()
	{
		final Set<String> names = new HashSet<>();
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			if (skill.getType() == HiscoreSkillType.BOSS)
			{
				names.add(normalizeName(skill.getName()));
			}
		}
		return names;
	}

	private static String normalizeName(String name)
	{
		return name == null ? "" : name.toLowerCase().replace("'", "").replace("’", "").replace("`", "").trim();
	}

	void clearHistory()
	{
		history.clear();
		lastFinished = null;
		saveHistory();
	}

	void exportNpc(String name)
	{
		final List<Fight> fights = new ArrayList<>();
		for (Fight fight : history)
		{
			if (fight.getTargetName().equals(name))
			{
				fights.add(fight);
			}
		}
		writeCsvAsync(sanitize(name), fights);
	}

	void exportAll(boolean bossOnly)
	{
		final List<Fight> fights = new ArrayList<>();
		for (Fight fight : history)
		{
			if (!bossOnly || isBoss(fight))
			{
				fights.add(fight);
			}
		}
		writeCsvAsync(bossOnly ? "bosses" : "all", fights);
	}

	private void writeCsvAsync(String label, List<Fight> fights)
	{
		if (fights.isEmpty())
		{
			setStatus("Nothing to export.");
			return;
		}
		// Disk IO off the client thread.
		executor.execute(() ->
		{
			try
			{
				final File dir = new File(RuneLite.RUNELITE_DIR, EXPORT_DIR);
				Files.createDirectories(dir.toPath());
				final File out = new File(dir, "pvm-" + label + "-" + FILE_TS.format(LocalDateTime.now()) + ".csv");
				try (Writer writer = new BufferedWriter(new java.io.OutputStreamWriter(
					Files.newOutputStream(out.toPath()), StandardCharsets.UTF_8)))
				{
					writer.write("started,npc,npcId,maxHp,killed,damageDealt,damageTaken,attempts,hits,accuracyPct,durationSec,dps\n");
					for (Fight fight : fights)
					{
						writer.write(csvRow(fight));
					}
				}
				setStatus("Exported " + fights.size() + " fights: " + out.getName());
			}
			catch (IOException e)
			{
				log.warn("PvM Performance export failed", e);
				setStatus("Export failed: " + e.getMessage());
			}
		});
	}

	private static String csvRow(Fight fight)
	{
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(fight.getStartMillis()), ZoneId.systemDefault()));
		return String.format("%s,\"%s\",%d,%d,%b,%d,%d,%d,%d,%.1f,%.1f,%.2f%n",
			started,
			fight.getTargetName().replace('"', '\''),
			fight.getTargetId(),
			fight.getMaxHp(),
			fight.isTargetDied(),
			fight.getDamageDealt(),
			fight.getDamageTaken(),
			fight.getAttempts(),
			fight.getHits(),
			fight.accuracy() * 100,
			fight.durationMillis() / 1000.0,
			fight.dps());
	}

	private static String sanitize(String name)
	{
		return name.replaceAll("[^a-zA-Z0-9]+", "_");
	}

	private void setStatus(String text)
	{
		if (panel != null)
		{
			panel.setStatus(text);
		}
	}
}
