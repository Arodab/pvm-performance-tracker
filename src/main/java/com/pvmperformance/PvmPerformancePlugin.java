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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.widgets.Widget;
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
import net.runelite.client.util.Text;

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
	// Eating and drinking, the one thing worth telling apart from idle time.
	// Getting this wrong only mislabels part of the breakdown; the total tick
	// loss is measured from the attacks themselves and is unaffected.
	private static final Set<Integer> CONSUME_ANIMATIONS = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList(AnimationID.HUMAN_EAT, AnimationID.HUMAN_KILLERWATT_ELECTRICSHOCK)));
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
	// The tick an attack was last seen going out on, set from the events that
	// prove one happened rather than from what the player looks like.
	private int attackObservedTick = -1;
	// Whether the intended prayer was up at any point during this tick. Reading
	// it once at the end of the tick misses a flick, which is off again by then
	// but was up while the server resolved the attack.
	private boolean prayedThisTick;

	// Running totals for the trip, shown instead of the current fight when the
	// overlay is set to whole-trip mode.
	private final SessionTotals session = new SessionTotals(System.currentTimeMillis());

	// A boss just killed, watched for its respawn so the wait for the next kill
	// can be timed, and where it respawned once it has. Bosses only: on a slayer
	// task a second spawn across the room is not the same fight coming back, and
	// timing the walk to it would say nothing.
	private int respawnWatchNpcId = -1;
	private int respawnNpcIndex = -1;
	private int respawnTick;

	// The room the fights are adding up to, and the raid the rooms are adding
	// up to. Both are views over the fights rather than separate counters, so
	// the three widths cannot disagree.
	private Encounter currentEncounter;
	private Raid currentRaid;
	private RaidType raidType;
	private int raidCounter;

	// The id the target is wearing now, which is not the id the fight opened on
	// once a boss transforms. Kept from the change events rather than looked up,
	// so no scan is needed to know whether the target can be fought at all.
	private int targetLiveId = -1;

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
		respawnWatchNpcId = -1;
		respawnNpcIndex = -1;
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		final Actor actor = event.getActor();
		final Hitsplat hitsplat = event.getHitsplat();
		final long now = System.currentTimeMillis();

		if (actor instanceof NPC && hitsplat.isMine() && !EncounterGroup.isIgnored(((NPC) actor).getId()))
		{
			final NPC npc = (NPC) actor;
			if (current == null || current.isEnded() || current.getTargetIndex() != npc.getIndex())
			{
				startFight(npc, now);
			}
			current.recordDamageDealt(hitsplat.getAmount(), now);
			if (current.isScored())
			{
				session.recordAttempt(hitsplat.getAmount(), now);
				sampleExpected(current);
			}
			// A landed hit resolves one of my pending attacks so it can't later
			// be mistaken for another player's splash.
			consumePending(npc.getIndex());
			// Only melee lands on the tick it was thrown. A ranged or magic hit
			// arrives late, so its tick comes from the projectile instead.
			if (combatCalc.isMeleeEquipped())
			{
				recordAttackObserved();
			}
		}
		else if (actor == client.getLocalPlayer() && current != null && !current.isEnded())
		{
			// Damage on us during an active fight is attributed to it.
			current.recordDamageTaken(hitsplat.getAmount(), now);
		}
	}

	/**
	 * Prints how the current loadout was resolved, so a player hitting a wrong
	 * figure can report why it is wrong rather than only that it is. Also writes
	 * the game's own combat option data for the equipped weapon to the log,
	 * which is what identifies a weapon category this plugin has wrong.
	 */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"loadout".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		final Fight shown = getDisplayFight();
		final int targetId = shown == null ? -1 : shown.getTargetId();
		for (String line : combatCalc.describeLoadout(targetId))
		{
			client.addChatMessage(ChatMessageType.CONSOLE, "PvM Performance", line, null);
		}
	}

	/**
	 * Catches a spell cast by hand onto an NPC. The autocast varbit only knows
	 * about autocasting, so without this a spell clicked while holding a powered
	 * staff would be reported as the staff's own attack.
	 *
	 * <p>The clicked widget carries the spell's name but no id this could be
	 * keyed by, so the name is what identifies it.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_NPC || !client.isWidgetSelected())
		{
			return;
		}
		final Widget selected = client.getSelectedWidget();
		if (selected == null || selected.getName() == null)
		{
			return;
		}
		final Spell spell = Spell.forDisplayName(Text.removeTags(selected.getName()));
		if (spell != null)
		{
			combatCalc.recordManualCast(spell);
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		// Magic and ranged attacks fire a projectile before impact. Recognising
		// mine lets a fight begin on my first cast even if it splashes, and lets
		// the splash that follows be attributed to me rather than to others.
		final Projectile projectile = event.getProjectile();
		final Player me = client.getLocalPlayer();
		final Actor target = projectile.getTargetActor();
		if (me == null || !(target instanceof NPC))
		{
			return;
		}
		if (!countedProjectiles.add(projectile))
		{
			return; // this projectile was already counted on an earlier frame
		}
		if (!isProjectileMine(projectile, me, target))
		{
			return;
		}

		final NPC npc = (NPC) target;
		if (EncounterGroup.isIgnored(npc.getId()))
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (current == null || current.isEnded() || current.getTargetIndex() != npc.getIndex())
		{
			startFight(npc, now);
		}
		pendingMineHits.merge(npc.getIndex(), 1, Integer::sum);
		// The projectile is created on the tick the attack was fired.
		recordAttackObserved();
	}

	/**
	 * Whether this projectile came from me. The projectile names its own source
	 * actor, which settles it outright and, unlike anything positional, cannot
	 * confuse me with another player standing on my tile.
	 *
	 * <p>Falls back to the tile and target test only when the projectile names
	 * no source at all, which is the weaker rule this replaced.
	 */
	private boolean isProjectileMine(Projectile projectile, Player me, Actor target)
	{
		final Actor source = projectile.getSourceActor();
		if (source != null)
		{
			return source == me;
		}
		// Spell projectiles name no source, so the tile it left from is all
		// there is. Requiring the target to match whatever the player was
		// interacting with as well is what dropped casts: that lapses between
		// the cast going out and the projectile appearing, and every cast it
		// dropped took its splash with it.
		final WorldPoint from = projectile.getSourcePoint();
		if (from == null || !from.equals(me.getWorldLocation()))
		{
			return false;
		}
		// Still not anyone standing on me: it has to be aimed at what I am
		// fighting, by the interaction or by the fight already under way.
		if (me.getInteracting() == target)
		{
			return true;
		}
		return current != null && !current.isEnded()
			&& current.getTargetIndex() == ((NPC) target).getIndex();
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
		// A splash names no caster, so it only counts as mine when it resolves a
		// projectile I fired. Attribution is exact now that the projectile names
		// its source, so this no longer drops casts the way it did.
		if (current.getTargetIndex() != index || !consumePending(index))
		{
			return;
		}
		final long now = System.currentTimeMillis();
		current.recordSplash(now);
		session.recordAttempt(0, now);
		sampleExpected(current);
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
		session.recordExpected(expectedAccuracy, expectedAverageHit);
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

	/**
	 * Catches the boss just killed coming back, so the wait between the two kills
	 * can be timed. Nothing is counted from here: the tick is only remembered,
	 * and is spent when a fight actually opens on that NPC. A respawn the player
	 * walks away from leaves nothing behind.
	 */
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		final NPC npc = event.getNpc();
		if (npc.getId() != respawnWatchNpcId)
		{
			return;
		}
		respawnWatchNpcId = -1;
		respawnNpcIndex = npc.getIndex();
		respawnTick = client.getTickCount();
	}

	/**
	 * Follows the target through a transform. A boss that changes form keeps its
	 * index and changes its id, and the new id is what says whether it can be
	 * fought at all — Sotetseg wears a separate one for the maze.
	 */
	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		final NPC npc = event.getNpc();
		if (current != null && !current.isEnded() && current.getTargetIndex() == npc.getIndex())
		{
			targetLiveId = npc.getId();
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

	/**
	 * Advances the attack cooldown by a tick and books the tick as an attack, as
	 * wasted, or as still on cooldown.
	 *
	 * <p>An attack is taken from the events that prove one happened, never from
	 * what the player looks like. Animations cannot answer this: they can be
	 * stalled or replaced, so an attack made on the same tick as an eat shows
	 * the eat, and reading the animation there would both miss the attack and,
	 * if the animation were being learned, poison the weapon's entry with it.
	 *
	 * <p>The tick is exact for both styles. A melee hitsplat lands on the tick it
	 * was thrown, and a ranged or magic projectile is created on the tick it was
	 * fired, well before it lands — so the weapon in hand decides which of the
	 * two events to believe.
	 */
	private void trackAttackCooldown()
	{
		final boolean attacked = attackObservedTick == client.getTickCount();
		attackObservedTick = -1;

		if (current == null || current.isEnded())
		{
			attackCooldown = 0;
			return;
		}
		if (attacked)
		{
			// Read on the attack tick: this is when the prayers and boosts are
			// the ones the attack actually rolled with.
			final int targetId = current.getTargetId();
			if (!current.isAttacking())
			{
				// The opening attack: time it against the respawn, if this fight
				// was opened by one.
				final int engaged = current.recordEngaged(client.getTickCount());
				if (engaged > 0)
				{
					session.recordEngaged(engaged);
				}
			}
			final boolean prayed = prayedThisTick || combatCalc.hasOffensivePrayer();
			final boolean boosted = combatCalc.isBoosted();
			final double actualSetup = combatCalc.actualAverageHit(targetId, prayed);
			final double idealSetup = combatCalc.idealAverageHit(targetId);
			current.recordAttackMade(prayed, boosted, actualSetup, idealSetup);
			if (current.isScored())
			{
				session.recordAttackMade(prayed, boosted, actualSetup, idealSetup);
			}
			else
			{
				session.recordTickSpent();
			}
			attackCooldown = Math.max(0, combatCalc.attackSpeedTicks() - 1);
			return;
		}
		// The trip totals take the same ticks as they happen, so the share shown
		// in whole-trip mode moves during a fight rather than only at its end.
		// Both are gated on the fight having started, which is the fight's rule
		// for counting a tick at all.
		if (EncounterGroup.isUnattackable(targetLiveId))
		{
			// Between phases, charging, or dead but still standing. The tick is
			// booked neither lost nor spent: no attack was possible, so counting
			// it either way would move a figure that measures choices.
			attackCooldown = Math.max(0, attackCooldown - 1);
			return;
		}
		final boolean counts = current.isAttacking();
		if (attackCooldown > 0)
		{
			attackCooldown--;
			current.recordTickSpent();
			if (counts)
			{
				session.recordTickSpent();
			}
			return;
		}
		final boolean eating = isConsuming();
		current.recordTickLost(eating);
		if (counts)
		{
			session.recordTickLost(eating);
		}
	}

	/** Whether the player is eating or drinking, which costs attack ticks. */
	private boolean isConsuming()
	{
		final Player me = client.getLocalPlayer();
		return me != null && CONSUME_ANIMATIONS.contains(me.getAnimation());
	}

	/**
	 * Notes that an attack went out this tick. Called only from the two events
	 * that prove one did, and whose tick is exact: a melee hitsplat lands on the
	 * tick it was thrown, and a projectile is created on the tick it was fired.
	 */
	private void recordAttackObserved()
	{
		attackObservedTick = client.getTickCount();
	}

	/**
	 * Notices the intended prayer going up part-way through a tick, so a prayer
	 * switched on and attacked with on the same tick counts.
	 *
	 * <p>Asks the client's own copy of the prayer varbits, which flips the
	 * instant the player clicks. The server has not confirmed the prayer yet at
	 * this point, so its copy would still read as off.
	 *
	 * <p>The opposite case — switched off on the tick the attack goes out, which
	 * the server still resolved with the prayer up — needs nothing here: the
	 * server's copy of the varbit, which is what the model reads, says it was up.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (current == null || current.isEnded() || prayedThisTick)
		{
			return;
		}
		if (combatCalc.hasOffensivePrayerNow())
		{
			prayedThisTick = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		trackRaid(System.currentTimeMillis());
		startFightOnTarget();
		trackAttackCooldown();
		prayedThisTick = false;

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
			if (current.getAttempts() == 0)
			{
				// Opened by targeting and not yet fought. The idle timeout can't
				// judge it, since it would expire while the player is still
				// walking into range and then reopen on the next tick. It ends
				// when they look away instead.
				if (!isTargeting(current.getTargetIndex()))
				{
					finalizeFight(false, System.currentTimeMillis());
				}
			}
			else
			{
				final long idle = System.currentTimeMillis() - current.getLastActivityMillis();
				if (idle > config.fightTimeoutTicks() * 600L)
				{
					finalizeFight(false, current.getLastActivityMillis());
				}
			}
		}
	}

	/** Whether the player is still interacting with this NPC. */
	private boolean isTargeting(int npcIndex)
	{
		final Player me = client.getLocalPlayer();
		final Actor target = me == null ? null : me.getInteracting();
		return target instanceof NPC && ((NPC) target).getIndex() == npcIndex;
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
			respawnWatchNpcId = -1;
			respawnNpcIndex = -1;
		}
	}

	private void startFight(NPC npc, long now)
	{
		if (current != null && !current.isEnded())
		{
			finalizeFight(false, now);
		}
		// getHealth is nullable, and the fight takes an int.
		final Integer maxHp = npcManager.getHealth(npc.getId());
		current = new Fight(npc.getName(), npc.getId(), npc.getIndex(), maxHp == null ? -1 : maxHp, now,
			raidType, raidCounter);
		targetLiveId = npc.getId();
		openEncounterFor(current, now);
		if (npc.getIndex() == respawnNpcIndex)
		{
			// This is the boss whose respawn was watched, so the wait for it can
			// be timed from the tick it appeared rather than from this one.
			current.setEngageFromTick(respawnTick);
			respawnNpcIndex = -1;
		}
	}

	/**
	 * Opens a fight as soon as the player targets something attackable, so the
	 * overlay is up while the first attack is still in the air rather than
	 * appearing when it lands.
	 *
	 * <p>A fight opened this way and never fought is thrown away at the end
	 * rather than recorded, so clicking an NPC and walking off leaves nothing
	 * behind.
	 */
	private void startFightOnTarget()
	{
		if (current != null && !current.isEnded())
		{
			return;
		}
		final Player me = client.getLocalPlayer();
		final Actor target = me == null ? null : me.getInteracting();
		if (!(target instanceof NPC))
		{
			return;
		}
		final NPC npc = (NPC) target;
		if (npc.getCombatLevel() <= 0 && npcManager.getHealth(npc.getId()) == null)
		{
			return; // not something that can be fought
		}
		startFight(npc, System.currentTimeMillis());
	}

	/**
	 * Files a fight under the room it belongs to, continuing the room already
	 * open when it is the same one.
	 *
	 * <p>This is what keeps the overlay still while a room is being fought. The
	 * nylocas are the case it was written for: a barrage that splashes the wrong
	 * colour, or a deliberate hit on it, opens a new fight but not a new room,
	 * so nothing on screen resets. No hitsplat has to be judged AoE or not.
	 */
	private void openEncounterFor(Fight fight, long now)
	{
		// Only a grouped NPC continues a room. Without that, a second Vorkath
		// would join the first — same name, so the same room — and the overlay
		// would quietly turn into a running total of the trip.
		final String name = fight.encounterName();
		if (currentEncounter == null || fight.getGroupName() == null || !currentEncounter.accepts(name))
		{
			if (currentEncounter != null)
			{
				currentEncounter.end(now);
			}
			currentEncounter = new Encounter(name, raidType, now);
			if (currentRaid != null)
			{
				currentRaid.add(currentEncounter);
			}
		}
		currentEncounter.add(fight);
	}

	/**
	 * Opens and closes raids from the game's own varbits, so a run is bounded by
	 * entering and leaving rather than by what happens to be fought.
	 */
	private void trackRaid(long now)
	{
		final RaidType now_ = RaidType.current(client);
		if (now_ == raidType)
		{
			return;
		}
		if (currentRaid != null)
		{
			currentRaid.end(now);
			currentRaid = null;
		}
		// A room does not span two raids, nor a raid and the world outside it.
		if (currentEncounter != null)
		{
			currentEncounter.end(now);
			currentEncounter = null;
		}
		raidType = now_;
		if (raidType != null)
		{
			currentRaid = new Raid(raidType, ++raidCounter, now);
		}
	}

	private void finalizeFight(boolean died, long now)
	{
		current.end(died, now);
		pendingMineHits.clear();
		if (current.getAttempts() == 0)
		{
			// Opened because the player looked at something and then didn't
			// fight it. Nothing happened, so nothing is recorded.
			current = null;
			return;
		}
		if (died && isBoss(current))
		{
			respawnWatchNpcId = current.getTargetId();
			respawnNpcIndex = -1;
		}
		session.recordFightEnded(died, current, now);
		history.add(0, current);
		while (history.size() > MAX_HISTORY)
		{
			history.remove(history.size() - 1);
		}
		lastFinished = current;
		current = null;
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

	/** The room the overlay should display, or null if nothing has been fought. */
	Encounter getDisplayEncounter()
	{
		return currentEncounter;
	}

	/** The raid in progress, or null outside one. */
	Raid getCurrentRaid()
	{
		return currentRaid;
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
					writer.write("level,raid,raidRun,room,"
						+ "started,npc,npcId,maxHp,killed,damageDealt,damageTaken,attempts,hits,"
						+ "accuracyPct,durationSec,dps,avgHit,expMaxHit,expAccuracyPct,expAvgHit,"
						+ "ticksLost,ticksLostPct,ticksLostEating,ticksToEngage,"
						+ "attacksMade,attacksPrayed,attacksBoosted,efficiencyPct\n");
					writeRows(writer, fights);
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

	/**
	* Writes each fight, then the room it belonged to, then the raid the rooms
	* belonged to: the same numbers at three widths, told apart by the level
	* column. Phases are written whole rather than summed away, so which phase
	* a kill went wrong on survives into the file, and anyone who wants only
	* the rooms can filter on one column.
	*
	* <p>The rooms are rebuilt from the fights here rather than stored, so a
	* history written before rooms existed still exports as rooms and there is
	* no second structure on disk to fall out of step with the first.
	*/
	private static void writeRows(Writer writer, List<Fight> fights) throws IOException
	{
		final List<Fight> ordered = new ArrayList<>(fights);
		ordered.sort(Comparator.comparingLong(Fight::getStartMillis));

		List<Encounter> raidRooms = new ArrayList<>();
		Encounter room = null;
		int raidRun = 0;
		String raidName = null;

		for (Fight fight : ordered)
		{
			final boolean newRaid = fight.getRaidId() != raidRun;
			if (newRaid && !raidRooms.isEmpty())
			{
				writer.write(csvRaidRow(raidName, raidRun, raidRooms));
				raidRooms = new ArrayList<>();
			}
			if (room == null || newRaid || fight.getGroupName() == null
				|| !room.accepts(fight.encounterName()))
			{
				if (room != null)
				{
					writer.write(csvRoomRow(raidName, raidRun, room));
				}
				room = new Encounter(fight.encounterName(), null, fight.getStartMillis());
				if (fight.getRaidId() > 0)
				{
					raidRooms.add(room);
				}
			}
			raidRun = fight.getRaidId();
			raidName = fight.getRaidName();
			room.add(fight);
			writer.write(csvFightRow(raidName, raidRun, fight));
		}
		if (room != null)
		{
			writer.write(csvRoomRow(raidName, raidRun, room));
		}
		if (!raidRooms.isEmpty())
		{
			writer.write(csvRaidRow(raidName, raidRun, raidRooms));
		}
	}

	/** The first four columns, which say what the row is a row of. */
	private static String csvScope(String level, String raidName, int raidRun, String room)
	{
		return String.format("%s,%s,%s,\"%s\",", level,
			raidName == null ? "" : raidName,
			raidRun > 0 ? String.valueOf(raidRun) : "",
			room == null ? "" : room.replace('"', '\''));
	}

	private static String csvRoomRow(String raidName, int raidRun, Encounter room)
	{
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(room.getStartMillis()), ZoneId.systemDefault()));
		final double seconds = Math.max(0.6, room.durationMillis() / 1000.0);
		final int attempts = room.getAttempts();
		return csvScope("room", raidName, raidRun, room.getName())
			+ String.format("%s,,,,%b,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,,%s,%s,%d,%s,%d,,%d,%d,%d,%s%n",
				started,
				room.isKilled(),
				room.getDamageDealt(),
				room.getDamageTaken(),
				attempts,
				room.getHits(),
				room.accuracy() * 100,
				room.durationMillis() / 1000.0,
				room.getDamageDealt() / seconds,
				room.averageHit(),
				csvExpected(attempts == 0 ? -1 : room.sumExpectedAccuracy() / attempts * 100, 1),
				csvExpected(attempts == 0 ? -1 : room.sumExpectedAverageHit() / attempts, 2),
				room.getTicksLost(),
				csvExpected(room.ticksLostShare() * 100, 1),
				room.getTicksLostEating(),
				room.getAttacksMade(),
				room.getAttacksPrayed(),
				room.getAttacksBoosted(),
				csvExpected(room.efficiency() * 100, 1));
	}

	private static String csvRaidRow(String raidName, int raidRun, List<Encounter> rooms)
	{
		final Raid raid = new Raid(raidName, raidRun, rooms.get(0).getStartMillis());
		for (Encounter room : rooms)
		{
			raid.add(room);
		}
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(raid.getStartMillis()), ZoneId.systemDefault()));
		final double seconds = Math.max(0.6, raid.durationMillis() / 1000.0);
		final int attempts = raid.getAttempts();
		return csvScope("raid", raidName, raidRun, null)
			+ String.format("%s,,,,,%d,,%d,%d,%.1f,%.1f,%.2f,%.2f,,%s,%s,%d,%s,%d,,%d,%d,%d,%s%n",
				started,
				raid.getDamageDealt(),
				attempts,
				raid.getHits(),
				attempts == 0 ? 0 : 100.0 * raid.getHits() / attempts,
				raid.durationMillis() / 1000.0,
				raid.getDamageDealt() / seconds,
				attempts == 0 ? 0 : (double) raid.getDamageDealt() / attempts,
				csvExpected(attempts == 0 ? -1 : raid.sumExpectedAccuracy() / attempts * 100, 1),
				csvExpected(attempts == 0 ? -1 : raid.sumExpectedAverageHit() / attempts, 2),
				raid.getTicksLost(),
				csvExpected(raid.ticksLostShare() * 100, 1),
				raid.getTicksLostEating(),
				raid.getAttacksMade(),
				raid.getAttacksPrayed(),
				raid.getAttacksBoosted(),
				csvExpected(raid.efficiency() * 100, 1));
	}

	private static String csvFightRow(String raidName, int raidRun, Fight fight)
	{
		return csvScope("phase", raidName, raidRun, fight.encounterName()) + csvRow(fight);
	}

	private static String csvRow(Fight fight)
	{
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(fight.getStartMillis()), ZoneId.systemDefault()));
		return String.format("%s,\"%s\",%d,%d,%b,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%s,%s,%s,%d,%s,%d,%s,%d,%d,%d,%s%n",
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
			fight.getTicksLostEating(),
			fight.getTicksToEngage() == 0 ? "" : String.valueOf(fight.getTicksToEngage()),
			fight.getAttacksMade(),
			fight.getAttacksPrayed(),
			fight.getAttacksBoosted(),
			csvExpected(fight.efficiency() * 100, 1));
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
