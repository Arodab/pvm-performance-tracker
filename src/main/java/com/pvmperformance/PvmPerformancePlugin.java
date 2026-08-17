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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
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
import net.runelite.api.gameval.SpotanimID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
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
	private static final List<String> BOSS_DISPLAY_NAMES = buildBossDisplayNames();
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

	@Inject
	private MonsterStatsProvider monsterStats;

	@Inject
	private ClientThread clientThread;

	private PvmPerformancePanel panel;
	private NavigationButton navButton;

	// Expected figures for the current loadout, recomputed each tick.
	private int expectedMaxHit;
	private double expectedAccuracy = -1;
	private double expectedAverageHit = -1;
	private int expectedSpecMaxHit;
	private SpecialAttack specialAttack;
	// Which target the figures above were computed against, so an attack is only
	// sampled with figures that were actually meant for it.
	private int expectedForNpcId = -1;
	// Ticks left before the weapon can attack again; 0 means it is ready.
	private int attackCooldown;
	// weapon item id -> the animations seen to actually produce an attack with
	// it. Learned from corroborated attacks, so detection stops depending on a
	// hand-maintained list as soon as a weapon has swung once.
	private final Map<Integer, Set<Integer>> attackAnimations = new HashMap<>();
	// The tick damage was last taken on, which is when a block animation plays.
	private int lastDamageTakenTick = -1;

	// Running totals for the trip, shown instead of the current fight when the
	// overlay is set to whole-trip mode.
	private final SessionTotals session = new SessionTotals(System.currentTimeMillis());

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
		monsterStats.startUp();
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
			session.recordAttempt(hitsplat.getAmount(), now);
			sampleExpected(current);
			// A landed hit resolves one of my pending attacks so it can't later
			// be mistaken for another player's splash. When there was none, no
			// projectile was involved, so this was melee and landed on the tick
			// it was thrown — which makes the animation playing now an attack.
			if (!consumePending(npc.getIndex()))
			{
				learnAttackAnimation();
			}
		}
		else if (actor == client.getLocalPlayer())
		{
			// Taking a hit plays a block animation, which must not be read as an
			// attack. Noting the tick catches every weapon's, present or future.
			lastDamageTakenTick = client.getTickCount();
			if (current != null && !current.isEnded())
			{
				// Damage on us during an active fight is attributed to it.
				current.recordDamageTaken(hitsplat.getAmount(), now);
			}
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
		// A projectile is created on the tick the attack is fired, so whatever
		// animation is playing now is this weapon's ranged or magic attack.
		learnAttackAnimation();
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
		// gameval's name for the splash graphic shown when a spell misses.
		if (!(actor instanceof NPC) || !actor.hasSpotAnim(SpotanimID.FAILEDSPELL_IMPACT))
		{
			return;
		}
		final int index = ((NPC) actor).getIndex();
		if (consumePending(index) && current.getTargetIndex() == index)
		{
			final long now = System.currentTimeMillis();
			current.recordSplash(now);
			session.recordAttempt(0, now);
			sampleExpected(current);
		}
	}

	/**
	 * Records what the model expected of the attack that just resolved. Sampling
	 * per attack rather than once per fight keeps the figures honest when a spec
	 * weapon is swapped in partway: the mean then reflects the blend actually
	 * wielded instead of whichever weapon happened to be held at one instant.
	 */
	private void sampleExpected(Fight fight)
	{
		if (expectedForNpcId != fight.getTargetId())
		{
			// The cached figures were computed for something else, most likely
			// because this is the opening attack of the fight.
			return;
		}
		fight.recordExpected(expectedMaxHit, expectedAccuracy, expectedAverageHit);
		session.recordExpected(expectedMaxHit, expectedAccuracy, expectedAverageHit);
	}

	/** The trip totals shown when the overlay is set to whole-trip mode. */
	SessionTotals getSession()
	{
		return session;
	}

	/** Starts the trip totals over, from the side panel. */
	void resetSession()
	{
		session.reset(System.currentTimeMillis());
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

	/**
	 * Advances the attack cooldown by a tick and books the tick as an attack, as
	 * wasted, or as still on cooldown.
	 *
	 * <p>Reading the animation rather than the landed hit is what makes this work
	 * for every style: a melee hit lands on the tick it is thrown, but a ranged
	 * or magic one lands however many ticks the projectile takes to fly, which
	 * varies with distance and would invent tick loss that never happened. The
	 * animation plays on the tick the attack is made, whatever the style, and
	 * whether or not the attack goes on to hit.
	 */
	private void trackAttackCooldown()
	{
		if (current == null || current.isEnded())
		{
			attackCooldown = 0;
			return;
		}
		if (attackCooldown > 0)
		{
			attackCooldown--;
			current.recordTickSpent();
			return;
		}
		// Off cooldown: either an attack goes out this tick, or it is wasted.
		if (isAttackingTarget())
		{
			current.recordAttackMade();
			attackCooldown = Math.max(0, combatCalc.attackSpeedTicks() - 1);
		}
		else
		{
			final Player me = client.getLocalPlayer();
			final boolean eating = me != null && AttackAnimations.isConsuming(me.getAnimation());
			current.recordTickLost(eating);
		}
	}

	/**
	 * Whether the player is attacking the NPC this fight is about. Idle and
	 * walking leave the animation at -1 because they are pose animations, so any
	 * other animation while targeting the NPC is an attack, barring the things
	 * on the blocklist such as eating or being hit.
	 */
	private boolean isAttackingTarget()
	{
		final Player me = client.getLocalPlayer();
		if (me == null)
		{
			return false;
		}
		final Actor target = me.getInteracting();
		if (!(target instanceof NPC) || ((NPC) target).getIndex() != current.getTargetIndex())
		{
			return false;
		}
		final int animation = me.getAnimation();
		final Set<Integer> known = attackAnimations.get(combatCalc.equippedWeaponId());
		if (known != null && !known.isEmpty())
		{
			// This weapon has been seen to attack, so accept nothing else.
			return known.contains(animation);
		}
		// Not seen yet: fall back to any action animation, minus the ones known
		// to be something else, and minus a block from a hit taken this tick.
		return AttackAnimations.couldBeAttack(animation) && lastDamageTakenTick != client.getTickCount();
	}

	/**
	 * Remembers the animation that was playing when an attack provably happened,
	 * so it can be recognised on its own from then on.
	 *
	 * <p>Only called where the tick is certain: a melee hitsplat lands on the
	 * tick it was thrown, and a projectile is created on the tick it was fired.
	 */
	private void learnAttackAnimation()
	{
		final Player me = client.getLocalPlayer();
		if (me == null || me.getAnimation() == -1)
		{
			return;
		}
		attackAnimations.computeIfAbsent(combatCalc.equippedWeaponId(), id -> new HashSet<>())
			.add(me.getAnimation());
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		trackAttackCooldown();

		// Drop projectiles that have landed so the set doesn't retain them.
		countedProjectiles.removeIf(p -> p.getRemainingCycles() <= 0);

		final Fight shown = getDisplayFight();
		specialAttack = combatCalc.specialAttack();
		if (shown != null)
		{
			// Pass the target so salve, dragon hunter and the rest can apply.
			expectedMaxHit = combatCalc.maxHit(shown.getTargetId());
			expectedAccuracy = combatCalc.hitChance(shown.getTargetId());
			expectedAverageHit = combatCalc.averageHit(shown.getTargetId());
			expectedSpecMaxHit = combatCalc.specialAttackMaxHit(shown.getTargetId());
			expectedForNpcId = shown.getTargetId();
		}
		else
		{
			expectedMaxHit = combatCalc.maxHit(-1);
			expectedAccuracy = -1;
			expectedAverageHit = -1;
			expectedSpecMaxHit = combatCalc.specialAttackMaxHit(-1);
			expectedForNpcId = -1;
		}

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
		session.recordFightStarted(now);
	}

	private void finalizeFight(boolean died, long now)
	{
		current.end(died, now);
		session.recordFightEnded(died, current, now);
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

	/** Expected max hit for the current loadout/style (0 if unknown). */
	int getExpectedMaxHit()
	{
		return expectedMaxHit;
	}

	/** Expected hit chance vs the shown target (0..1), or -1 if unavailable. */
	double getExpectedAccuracy()
	{
		return expectedAccuracy;
	}

	/** Expected damage per attack vs the shown target, or -1 if unavailable. */
	double getExpectedAverageHit()
	{
		return expectedAverageHit;
	}

	/** Max total damage of one special attack activation (0 if the weapon has none). */
	int getExpectedSpecMaxHit()
	{
		return expectedSpecMaxHit;
	}

	/** The equipped weapon's special attack, or null if it has none that hits. */
	SpecialAttack getSpecialAttack()
	{
		return specialAttack;
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

	boolean isBoss(Fight fight)
	{
		return fight != null && BOSS_NAMES.contains(normalizeName(fight.getTargetName()));
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

	private static List<String> buildBossDisplayNames()
	{
		final List<String> names = new ArrayList<>();
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			if (skill.getType() == HiscoreSkillType.BOSS)
			{
				names.add(skill.getName());
			}
		}
		Collections.sort(names);
		return names;
	}

	/** All hiscore boss display names, sorted; used by the panel's boss selector. */
	List<String> getBossDisplayNames()
	{
		return BOSS_DISPLAY_NAMES;
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
					writer.write("started,npc,npcId,maxHp,killed,damageDealt,damageTaken,attempts,hits,"
						+ "accuracyPct,durationSec,dps,avgHit,expMaxHit,expAccuracyPct,expAvgHit,"
						+ "ticksLost,ticksLostPct,ticksLostEating\n");
					for (Fight fight : fights)
					{
						writer.write(csvRow(fight));
					}
				}
				final String path = out.getAbsolutePath();
				setStatus("Exported " + fights.size() + " fights → " + path);
				clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"PvM Performance: exported " + fights.size() + " fights to " + path, null));
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
		return String.format("%s,\"%s\",%d,%d,%b,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%s,%s,%s,%d,%s,%d%n",
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
			fight.dps(),
			fight.averageHit(),
			csvExpected(fight.expectedMaxHit(), 1),
			csvExpected(fight.expectedAccuracy() * 100, 1),
			csvExpected(fight.expectedAverageHit(), 2),
			fight.getTicksLost(),
			csvExpected(fight.ticksLostShare() * 100, 1),
			fight.getTicksLostEating());
	}

	/**
	 * Expected figures are left blank rather than written as a number when the
	 * model had nothing to say — no target stats, or a fight recorded before
	 * these columns existed — so a reader can tell "unknown" from a real zero.
	 */
	private static String csvExpected(double value, int decimals)
	{
		return value < 0 ? "" : String.format("%." + decimals + "f", value);
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
