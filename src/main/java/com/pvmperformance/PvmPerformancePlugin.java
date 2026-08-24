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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
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
import net.runelite.client.party.events.UserPart;
import net.runelite.client.plugins.party.messages.StatusUpdate;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
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
	// How long an eat holds the attack back. Delays stack within a tick, so a
	// shark chased with a karambwan is three plus two.
	private static final int EAT_TICKS = 3;
	// A combo food adds its own delay on top of the food it chases.
	private static final int KARAMBWAN_TICKS = 2;
	// How long a click on an NPC vouches for a projectile aimed at it. A cast is
	// five ticks, so this covers the one attack the click ordered and lapses
	// before the next would be thrown without a further click.
	private static final int CLICK_ATTRIBUTION_TICKS = 5;
	// How long a counted projectile is remembered. Long enough that no projectile
	// is still in flight when its key is dropped, short enough that the map stays
	// a handful of entries.
	private static final int PROJECTILE_KEY_TICKS = 20;
	// How many of the player's recent tiles a projectile may have been fired
	// from. Long enough to cover a projectile's flight while the player runs.
	private static final int RECENT_TILES = 6;
	// How far a projectile may have left from a tile I am known to have occupied.
	// Two, because that is how far running moves in the tick the shot went out.
	private static final int TILE_SLACK = 2;
	private static final int LOCAL_TILE_SIZE = 128;
	// How far back to look for the loadout that threw a projectile, when a
	// switch has already replaced it. A switch lands within a tick or two.
	private static final int SWITCH_LOOKBACK_TICKS = 4;
	// How many ticks after an attack goes out it is booked. A hitsplat is one
	// tick behind the blow; a projectile surfaces a tick later still. Measured
	// in game rather than reasoned about, and kept here so there is one place to
	// correct if it is ever wrong again.
	private static final int PRAYER_HISTORY_TICKS = 10;
	// The switch history needs the same reach as the prayer one, and for the
	// same reason: the gap between an attack and its booking differs by style.
	private static final int SWITCH_HISTORY_TICKS = 10;
	// How long an attack may stay in the air before its expected figures are
	// given up on. Comfortably past any real flight, so only an attack that
	// truly never landed is dropped by this rather than by the fight ending.
	private static final int PENDING_SAMPLE_TICKS = 10;
	// How many ticks after an attack goes out it is booked. Measured from the
	// trace, not reasoned about: with a flick on the attack tick the mark lands
	// two ticks below the booking tick for a projectile and one below for a
	// melee blow. The projectile's own start cycle cannot answer this - it
	// equals the game cycle at the event, so it carries no history.
	private static final int MELEE_BOOKING_LAG = 1;
	private static final int PROJECTILE_BOOKING_LAG = 2;
	// And three for a cast that has no projectile. Measured, like the other two:
	// the barrage cast animation started on 21, 26 and 31 and its damage landed
	// on 24, 29 and 34. Borrowing melee's one left the prayer being read two
	// ticks after the cast, which counted a prayer raised well after the spell
	// had gone out.
	// None at all for a cast with no projectile, which is booked from the
	// caster's animation and so is seen on the tick it goes out. It was briefly
	// booked from its hitsplat at three ticks, measured — kept here because the
	// measurement stands if that route is ever needed again, but the hitsplat
	// cannot see a cast that misses and the animation can.
	private static final int CAST_BOOKING_LAG = 0;
	// The animations a projectile-less cast is thrown with. Only these spells
	// reach the animation path, so this is a short list and not a table of every
	// weapon. Extend it from the "cast UNKNOWN ANIM" trace rather than by guess.
	private static final Set<Integer> CAST_ANIMATIONS =
		Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(10092)));
	// Casts with no projectile, waiting to be told whether they landed. Usually
	// none or one; two only while a cast thrown at long range is still in the
	// air as the next goes out. A single slot used to hold this, and the new
	// cast overwrote the old one's verdict before it could be given — at range
	// the two coincide exactly, so every splash from far off was lost.
	private final List<PendingCast> castsAwaiting = new ArrayList<>();
	// The last per-tick trace line printed, so an unchanged one is not repeated.
	private String lastTraceLine;
	// TRACE. Magic and hitpoints experience as last seen, so a change can be
	// printed with the tick it arrived on. See onStatChanged.
	private final int[] tracedXp = new int[2];
	// Hitpoints experience as it stood at the end of the previous tick, which is
	// what a cast thrown this tick is judged against. See resolveCasts.
	private int hitpointsXpLastTick;
	// The tick the worn items last changed on.
	private static final int CYCLES_PER_TICK = 30;
	// How far either side of its due tick a projectile may land and still be
	// recognised. One tick each way; nothing swings faster than two.
	private static final int FLIGHT_SLACK_TICKS = 1;
	// How long the player must be looking away before a fight they opened by
	// targeting, and have not yet attacked, is closed.
	private static final int LOOK_AWAY_TICKS = 5;
	private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	/** The column names, kept beside the row builders so the two cannot drift. */
	static final String CSV_HEADER =
		"level,raid,raidRun,room,"
		+ "started,npc,npcId,maxHp,killed,damageDealt,damageTaken,attempts,hits,"
		+ "accuracyPct,durationSec,dps,avgHit,expMaxHit,expAccuracyPct,expAvgHit,"
		+ "ticksLost,ticksLostPct,ticksLostEating,ticksToEngage,"
		+ "attacksMade,attacksPrayed,attacksPotted,attacksSwitched,efficiencyPct\n";

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

	@Inject
	private DefenceDrain drain;

	@Inject
	private PartyHitpoints partyHitpoints;

	@Inject
	private GearBonusCalc gearBonuses;

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
	// The NPC last clicked on, and when. A click is unambiguously mine and
	// always precedes the attack it orders, which is what the interaction is not
	// yet on the opening attack of a fight.
	private int clickedNpcIndex = -1;
	private int clickedNpcTick = -1;
	// Ticks left before the weapon can attack again; 0 means it is ready.
	// The tick the next attack is due on, counted from the tick the last one
	// went out rather than the tick it was booked. A countdown started at the
	// booking measures the gap between bookings instead, and that gap is a tick
	// longer whenever the style changes from melee to a projectile one.
	private int attackDueTick = Integer.MIN_VALUE;
	// Whether the last attack was proven by a projectile, and so is booked two
	// ticks after it went out rather than one. Kept because a switch can leave
	// one weapon's attack in flight while another is already in hand.
	private boolean lastAttackFromProjectile;
	// The tick my last attack of any style went out on. Kept apart from
	// attackDueTick, which is only set once the attack is booked: this one has
	// to be right immediately, because it is what says the weapon is busy.
	private int lastAttackSeenTick = Integer.MIN_VALUE;
	// How far behind the attack the observation that proved it was.
	private int attackObservedLag = MELEE_BOOKING_LAG;
	// The last tick a cast was booked from its animation, so the hitsplat does
	// not book the same cast a second time.
	private int lastCastBookedTick = Integer.MIN_VALUE;
	// Expected figures for attacks that have gone out but not yet landed.
	private final List<PendingSample> pendingSamples = new ArrayList<>();
	// The tick a projectile was last taken as mine, so at most one is taken per
	// tick. A player cannot throw two attacks in one.
	private int acceptedProjectileTick = Integer.MIN_VALUE;
	// What getAnimation returns when nothing is playing.
	private static final int IDLE_ANIMATION = -1;
	// The tick an attack was last seen going out on, set from the events that
	// prove one happened rather than from what the player looks like, and which
	// event proved it. A projectile is a cast or a shot; a hitsplat is a melee
	// blow. Which one it was decides whether a pending manual cast has gone out.
	private int attackObservedTick = -1;
	private boolean attackObservedFromProjectile;
	// Prayer and boost as they stood the instant the attack was proven. Read
	// there rather than at the end of the tick, because the server resolved the
	// attack with what was up when it went out: a potion drunk after the attack
	// but still inside the same tick has already raised the boosted level by the
	// time GameTick runs, and was counting as though the attack had used it.
	private boolean attackObservedPotted;
	// The tick the attack actually left on, which is not always the tick its
	// event surfaces. A projectile's start cycle says when it was fired, and
	// that can be a tick before the plugin hears about it, by which time the
	// player may have switched weapons. Melee has no such gap.
	private int attackOriginTick = -1;
	// The figures as they stood the instant the attack was seen. The per-tick
	// snapshot is written at GameTick, which is the end of the tick and so
	// after any switch made during it; the projectile event fires before that,
	// while the weapon that threw the attack is still in hand.
	private int gearChangedTick = Integer.MIN_VALUE;
	private int attackObservedNpcId = -1;
	private int attackObservedMaxHit;
	private double attackObservedAccuracy = -1;
	private double attackObservedAverageHit = -1;
	// The expected figures as they stood on each of the last few ticks, so an
	// attack can be scored with the loadout that actually threw it.
	private final Map<Integer, double[]> expectedByTick = new HashMap<>();
	// Where the player stood on each of the last few ticks. A projectile names
	// the tile it left, which is where the player was when it was fired, not
	// necessarily where they are by the time the event arrives.
	// Where I stood on each of the last few ticks, in scene coordinates, so a
	// projectile can be judged against the tick it actually set off on.
	private final Map<Integer, LocalPoint> recentTiles = new HashMap<>();
	// Consecutive ticks the player has not been interacting with the open fight.
	private int notTargetingTicks;
	// Per NPC: the tick my last hitsplat on it landed on, and whether the burst
	// it belongs to has already been counted as a landed attack. Keyed by index
	// rather than held once, because one attack can land on several NPCs at the
	// same instant, a chinchomp, a barrage, a scythe reaching three, and those
	// are separate targets whose bursts must not swallow each other.
	private final Map<Integer, Integer> lastHitsplatTick = new HashMap<>();
	private final Set<Integer> burstLanded = new HashSet<>();
	// NPCs whose current burst has already booked an attack. Dragon and burning
	// claws split one special across two ticks, and each hitsplat was booking an
	// attack of its own, so one spec counted as two: two attempts, two expected
	// samples, and an expected hit count above one for a single attack.
	private final Set<Integer> burstBooked = new HashSet<>();
	// Whether the goal prayer was up at any point during each recent tick. Kept
	// as a history rather than a slot or two because the tick an attack went out
	// on is not the tick it is booked on, and the gap differs by style.
	private final Map<Integer, Boolean> prayerUpByTick = new HashMap<>();
	// Whether the gear worn on each recent tick was the best available. Kept as
	// a history for the same reason the prayer is: the tick an attack is booked
	// on is not the tick it went out on, and a switch can land in between.
	private final Map<Integer, Boolean> switchedByTick = new HashMap<>();
	// The attack speed in force on each recent tick, so the wait after an attack
	// is the throwing weapon's and not that of whatever replaced it.
	private final Map<Integer, Integer> speedByTick = new HashMap<>();
	// The tick an eat was last seen on, so the pause it causes is credited to it
	// rather than only the one tick its animation shows for.
	private int lastConsumeTick;
	private int consumeDelay;

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
	// The last room with something in it, kept so a finished kill stays readable
	// while the next room is still empty. See getDisplayEncounter.
	private Encounter lastFinishedEncounter;
	private Raid currentRaid;
	private RaidType raidType;
	private int raidCounter;

	// The id the target is wearing now, which is not the id the fight opened on
	// once a boss transforms. Kept from the change events rather than looked up,
	// so no scan is needed to know whether the target can be fought at all.
	private int targetLiveId = -1;
	private NPC targetNpc;

	// Which Nightmare was last seen, so her totems can be told apart. Both
	// fights use the same totem ids and nothing in a totem says whose it is.
	private String nightmareBoss;

	// Which special Olm is running. Reset when the raid is, since it means
	// nothing outside one.
	private OlmPhase olmPhase;

	// The fight currently in progress, or null between fights.
	private Fight current;
	// The most recently finished fight, kept so the overlay lingers briefly.
	private Fight lastFinished;
	// Persisted history, most-recent first. Copy-on-write so the panel (EDT) can
	// iterate it safely while combat events (client thread) append to it.
	private final List<Fight> history = new CopyOnWriteArrayList<>();

	// My in-flight projectiles, each credited once. Keyed by start cycle, id and
	// target rather than by the Projectile object, whose identity the client
	// recycles. Values are the tick seen, so the map can be aged out.
	private final Map<Long, Integer> countedProjectiles = new HashMap<>();
	// npcIndex -> my launched attacks not yet resolved to a hit or a splash.
	// A magic splash carries no caster info, so it only counts as mine when it
	// resolves one of these; that also excludes other players' splashes.
	private final Map<Integer, List<Integer>> pendingMineHits = new HashMap<>();

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
		recentTiles.clear();
		expectedByTick.clear();
		lastHitsplatTick.clear();
		burstLanded.clear();
		pendingMineHits.clear();
		drain.clear();
		partyHitpoints.clear();
		nightmareBoss = null;
		targetNpc = null;
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
			// Hitsplats arriving together are one attack. A dragon claw special
			// lands four across two ticks and a dark bow two on one tick, so the
			// burst is grouped by adjacency: a gap of more than a tick starts a
			// new one. Nothing swings faster than two ticks, so no two real
			// attacks can be merged by this.
			final int tick = client.getTickCount();
			final Integer seen = lastHitsplatTick.put(npc.getIndex(), tick);
			final boolean newBurst = seen == null || tick - seen > 1;
			if (newBurst)
			{
				burstLanded.remove(npc.getIndex());
				burstBooked.remove(npc.getIndex());
			}
			// The first hitsplat of the burst to actually deal damage is what
			// makes its attack a landed one. A claw special that opens with a
			// zero and then connects still landed once.
			final boolean alreadyLanded = burstLanded.contains(npc.getIndex());
			final boolean landedAttack = hitsplat.getAmount() > 0 && !alreadyLanded;
			// TRACE. Some hits count their damage but not the hit itself, and
			// the only thing that can swallow one is this grouping: a hitsplat
			// within a tick of the last on the same NPC is read as the same
			// attack landing twice. Everything that decides it is printed,
			// read before the add below, because which input is wrong is
			// exactly what is not known. Hitsplat type is in there too — a
			// thrall's or a familiar's damage counting as mine would land on
			// the tick after my own and be grouped into it.
			log.debug("TRACE hitsplat tick {} npc {} amount {} type {} lastSeen {} newBurst {} "
					+ "alreadyLanded {} -> landed {}", tick, npc.getIndex(), hitsplat.getAmount(),
				hitsplat.getHitsplatType(), seen, newBurst, alreadyLanded, landedAttack);
			if (landedAttack)
			{
				burstLanded.add(npc.getIndex());
			}
			// This is the attack landing, so whatever was waiting on this NPC
			// gets its expectation counted now, beside the damage it did.
			resolvePendingSample(npc.getIndex());
			// A cast waiting to be told whether it landed has its answer the
			// moment one of my hitsplats DEALS DAMAGE to the NPC it was aimed
			// at, and the wait is over there and then: nothing later can unsay
			// that it connected.
			//
			// A hitsplat of zero is deliberately treated as a splash rather
			// than as a hit that rolled no damage. It is not strictly true — a
			// spell can roll a hit for nothing — but it is rare, it is the only
			// case hitpoints xp cannot see, and taking it this way makes damage
			// dealt the single question both witnesses answer. Worth revisiting
			// if the gauntlets ever need to be exact.
			if (hitsplat.getAmount() > 0)
			{
				for (PendingCast cast : castsAwaiting)
				{
					// The oldest unanswered cast on this NPC is the one that
					// landed: they resolve in the order they were thrown.
					if (cast.npcIndex == npc.getIndex() && !cast.connected)
					{
						cast.connected = true;
						break;
					}
				}
			}
			// A zero is a miss, which is what arms the confliction gauntlets
			// for the next cast against this same enemy.
			if (combatCalc.usesConflictionGauntlets())
			{
				combatCalc.noteMagicResolved(npc.getIndex(), hitsplat.getAmount() == 0);
			}
			current.recordDamageDealt(hitsplat.getAmount(), now, landedAttack);
			if (current.isScored())
			{
				session.recordAttempt(hitsplat.getAmount(), landedAttack, now);
			}
			// A special's drain is worked out from the hit it landed, so it can
			// only be applied here.
			drain.onMyHitsplat(npc, hitsplat.getAmount());
			// A landed hit resolves one of my pending attacks so it can't later
			// be mistaken for another player's splash. Whether it did is also
			// what says where this hitsplat came from.
			final boolean arrivedFromFlight = consumePending(npc.getIndex());
			// Only melee lands on the tick it was thrown; a ranged or magic hit
			// arrives late, so its tick comes from the projectile. Which weapon
			// is held cannot decide this alone: a spell cast from a melee weapon
			// lands a hitsplat like any other, and one that resolves something
			// already in flight was booked when it was fired.
			// Melee lands on the tick it is thrown, and so do the ancient area
			// spells: a barrage gives no projectile to book from, so its
			// hitsplat is the only thing that says an attack happened. Both are
			// booked here; everything else is booked from its projectile.
			// Melee, plus a projectile-less cast the animation route did not
			// recognise. That route sees misses and this one cannot, so it is
			// the better of the two and books first; this is only here so an
			// unknown cast animation costs the misses rather than everything.
			// How far behind the cast this hitsplat is, which is the hit delay
			// and therefore distance, not a constant. Only this path needs it:
			// a cast booked from its animation and a shot booked from its
			// projectile are both seen on their way out, so how long the damage
			// takes to arrive cannot date them. This one books from the landing
			// itself, so it is exactly as late as the spell was slow — and read
			// at a fixed three, a cast from range had its prayer and its gear
			// taken from two ticks after it was thrown.
			final int castLag = magicHitDelay(castDistance(npc));
			final boolean unbookedCast = combatCalc.castLandsWithoutProjectile()
				&& client.getTickCount() - lastCastBookedTick > castLag;
			final boolean melee = combatCalc.isMeleeEquipped() || unbookedCast;
			final boolean firstOfBurst = !burstBooked.contains(npc.getIndex());
			if (!arrivedFromFlight && melee && burstBooked.add(npc.getIndex()))
			{
				recordAttackObserved(false, npc.getId(), combatCalc.isMeleeEquipped()
					? MELEE_BOOKING_LAG : castLag);
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
		// The form the target is wearing now, not the one the fight opened on.
		// A boss that has transformed is described as it stands, which is what
		// makes a report about a phase worth reading.
		final int targetId = current != null && !current.isEnded() ? targetLiveId
			: shown == null ? -1 : shown.getTargetId();
		for (String line : combatCalc.describeLoadout(targetId))
		{
			client.addChatMessage(ChatMessageType.CONSOLE, "PvM Performance", line, null);
		}
	}

	// Catches a spell cast by hand onto an NPC. The autocast varbit only knows
	// about autocasting, so without this a spell clicked while holding a
	// powered staff would be reported as the staff's own attack.
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		noteConsumed(event);
		// Any click on an NPC, whatever it ordered: attacking it, casting on it,
		// or firing at it. What matters is that I aimed something at that NPC.
		final NPC clicked = event.getMenuEntry().getNpc();
		if (clicked != null)
		{
			clickedNpcIndex = clicked.getIndex();
			clickedNpcTick = client.getTickCount();
		}
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
		if (countedProjectiles.put(projectileKey(projectile, (NPC) target), client.getTickCount()) != null)
		{
			return; // this projectile was already counted on an earlier frame
		}
		if (!isProjectileMine(projectile, me, target))
		{
			return;
		}
		// One attack a tick, because a player cannot throw two. Two people
		// stacked on a tile casting on the same tick get one projectile each,
		// and where they cast the same spell the key above already collapses
		// them — but different spells key differently and both would land here.
		//
		// The booking was already capped, since it turns on a single tick stamp.
		// What was not capped is everything this sets up: the figures the attack
		// is scored against and the pending hit that decides where its hitsplat
		// came from, both of which a neighbour's projectile could overwrite.
		//
		// First one wins. There is nothing to choose between them — that is the
		// whole problem — and taking the first at least means the same one is
		// used for the figures and for the hit.
		if (acceptedProjectileTick == client.getTickCount())
		{
			return;
		}
		acceptedProjectileTick = client.getTickCount();

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
		pendingMineHits.computeIfAbsent(npc.getIndex(), k -> new ArrayList<>())
			.add(landingTick(projectile));
		recordAttackObserved(true, npc.getId());
		// Not always the tick the event surfaces on: the start cycle says when
		// the projectile was actually fired, which for a slow one can be a tick
		// earlier, and the loadout may have changed since.
		attackOriginTick = startTick(projectile);
	}

	// Whether a projectile left a tile the player was standing on recently.


	// Close enough to have been fired by me. Not an exact match, because running
	// covers two tiles a tick while the history records one tile a tick, so the
	// tile an attack was actually thrown from is often never in it: a projectile
	// two tiles east of where the player ended up was refused outright, and with
	// it the whole attack.


	/**
	 * Identifies one cast. Two projectiles sharing a start cycle, an id and a
	 * target are the same cast seen on a later frame; anything else differs in
	 * at least one of the three.
	 */
	private static long projectileKey(Projectile projectile, NPC target)
	{
		return ((long) projectile.getStartCycle() << 32)
			^ ((long) projectile.getId() << 16)
			^ target.getIndex();
	}

	// Whether this projectile came from me. The projectile names its own
	// source actor, which settles it outright and, unlike anything positional,
	// cannot confuse me with another player standing on my tile.
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
		// What the projectile is aimed at decides this, not where it set off
		// from. A slow projectile is in the air for several ticks and players
		// move while it flies, deliberately so at Olm, so the firing tile is
		// often nowhere near the player by the time the event is handled. Inside
		// an instance it is worse than useless: the source point and the
		// player's location are not in the same coordinate space at all, and the
		// two were seen forty tiles apart with everything else matching.
		// Where it set off from is the only thing here that identifies a caster,
		// so it is required rather than consulted last. What it is aimed at
		// cannot stand in for it: in company, every other player casting at the
		// boss is casting at what I am fighting too, and each of their spells
		// was being counted as mine and added to my expected damage.
		//
		// Compared in scene coordinates, which an instance does not translate,
		// against where I stood on the tick it set off. A projectile is counted
		// once, on first sight, which is the tick it was created — so this is
		// asked while the firing tile still means something.
		if (!firedFromWhereIWas(projectile, me))
		{
			return false;
		}
		// And I have to have been able to fire it. Two players stand on one
		// tile as a matter of course, and their spells leave from the same
		// point as mine, so position alone cannot separate them — but a weapon
		// on cooldown cannot have thrown anything, whoever was standing there.
		//
		// The live speed rather than the one that threw the last attack, which
		// errs towards accepting: swapping to something faster shortens the
		// wait, and letting one of my own attacks through wrongly is better
		// than dropping it.
		if (client.getTickCount() < lastAttackSeenTick + combatCalc.attackSpeedTicks())
		{
			return false;
		}
		// And I have to have been doing something. An attack animates, so a
		// player with nothing playing did not throw this — which closes the
		// last gap, a neighbour casting while I stand still with nothing on
		// cooldown.
		//
		// The only thing asked of the animation, and only ever a rejection.
		// Naming the animation of every spell and staff would be a table to
		// maintain where a missing entry silently drops attacks, and that is
		// what sank two earlier designs. This needs no table: it does not care
		// what the animation is, only that there is one. It is also why eating
		// cannot cost an attack either way — an eat is an animation, so this
		// accepts and moves on, whatever the eat does to the attack animation.
		if (me.getAnimation() == IDLE_ANIMATION)
		{
			return false;
		}
		// And aimed at something I am engaged with. Held loosely on purpose:
		// any one of the three will do, because getInteracting alone lapses
		// between a cast leaving and its projectile appearing, and requiring it
		// dropped every splash. It is the check above that does the work.
		final NPC aimedAt = (NPC) target;
		final boolean engaged = (clickedNpcIndex == aimedAt.getIndex()
				&& client.getTickCount() - clickedNpcTick <= CLICK_ATTRIBUTION_TICKS)
			|| me.getInteracting() == target
			|| (current != null && !current.isEnded()
				&& current.getTargetIndex() == aimedAt.getIndex());
		return engaged;
	}

	// Whether the projectile set off from where I was standing when it did. The
	// start cycle says which tick that was, so a projectile still in the air
	// after several ticks of running is judged against the right position.
	private boolean firedFromWhereIWas(Projectile projectile, Player me)
	{
		final int ticksAgo = Math.max(0,
			(client.getGameCycle() - projectile.getStartCycle()) / CYCLES_PER_TICK);
		final LocalPoint then = recentTiles.get(client.getTickCount() - ticksAgo);
		final LocalPoint here = me.getLocalLocation();
		for (LocalPoint p : new LocalPoint[]{then, here})
		{
			if (p != null
				&& Math.abs(projectile.getX1() - p.getX()) <= TILE_SLACK * LOCAL_TILE_SIZE
				&& Math.abs(projectile.getY1() - p.getY()) <= TILE_SLACK * LOCAL_TILE_SIZE)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Watches for what Olm's phase is made of. Each special leaves something of
	 * its own in the scene, and his head spawning is what starts a phase, the
	 * same signal the CoX Additions plugin counts phases by, which is all that
	 * plugin does here: it does not identify the specials.
	 */
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		final int id = event.getGameObject().getId();
		if (OlmPhase.isHeadObject(id))
		{
			// A new phase begins with nothing known about it yet.
			olmPhase = null;
			return;
		}
		final OlmPhase phase = OlmPhase.forObject(id);
		if (phase != null)
		{
			olmPhase = phase;
		}
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		// A splash produces no hitsplat — confirmed by the user, and worth
		// being blunt about because the opposite was written here for two
		// sessions and cost both of them. The zero hitsplats seen arriving
		// during a kill, and read at the time as splashes booking normally,
		// were not splashes.
		//
		// This used to be the only thing that could see a missed cast, which
		// made the whole of it hang on one spotanim id being right and on the
		// splash landing on the NPC the fight happened to be open on. Neither
		// holds for an area spell. Hitpoints xp answers the same question
		// without either assumption — see resolveCasts — and this
		// is now the fast route rather than the only one: it fires the tick the
		// splash is drawn instead of waiting out the cast's resolve window.
		//
		// It only counts if it resolves one of my own casts, which excludes
		// other players' splashes.
		if (current == null || current.isEnded())
		{
			return;
		}
		final Actor actor = event.getActor();
		// TRACE. A splash produces no hitsplat, confirmed by the user, so this
		// handler is the only thing that can see a barrage miss and arm the
		// gauntlets. Logged BEFORE the spotanim check rather than after it: if
		// the id being matched on is the wrong one, a trace behind the check
		// prints nothing and says only that it did not fire. This prints every
		// spotanim that lands on an NPC during a fight, so the splash names
		// itself whatever its id turns out to be.
		if (actor instanceof NPC)
		{
			final StringBuilder ids = new StringBuilder();
			for (ActorSpotAnim spotAnim : actor.getSpotAnims())
			{
				ids.append(ids.length() == 0 ? "" : ",").append(spotAnim.getId());
			}
			log.debug("TRACE graphic tick {} npc {} spotanims [{}] splash85 {} target {}",
				client.getTickCount(), ((NPC) actor).getIndex(), ids,
				actor.hasSpotAnim(SpotanimID.FAILEDSPELL_IMPACT), current.getTargetIndex());
		}
		// gameval's name for the splash graphic shown when a spell misses.
		if (!(actor instanceof NPC) || !actor.hasSpotAnim(SpotanimID.FAILEDSPELL_IMPACT))
		{
			return;
		}
		final int index = ((NPC) actor).getIndex();
		if (current.getTargetIndex() != index)
		{
			// TRACE. A barrage is an area spell, so a splash on a segment the
			// fight is not open on is a real possibility and would be dropped
			// here without a word.
			log.debug("TRACE splash on a different npc: splashed {} fighting {}", index,
				current.getTargetIndex());
			return;
		}
		// A splash names no caster, so ordinarily it only counts as mine when it
		// resolves a projectile I fired.
		//
		// The ancient area spells have no projectile to resolve, and a splash
		// has no hitsplat either, so for those this graphic is the only evidence
		// the cast happened at all — requiring a pending projectile left a
		// splashed barrage counting nothing anywhere. It is booked here instead,
		// which is also why the attack itself is recorded: with no projectile
		// and no hitsplat, nothing else will.
		final boolean noProjectile = combatCalc.castLandsWithoutProjectile();
		if (!noProjectile && !consumePending(index))
		{
			return;
		}
		recordSplash(index, System.currentTimeMillis());
	}

	/**
	 * A cast of mine that missed, however it was found out. The attack itself is
	 * not booked here: a cast with no projectile is booked from the caster's
	 * animation, which is the only thing that sees one before it resolves, so
	 * booking it again would double it.
	 */
	private void recordSplash(int index, long now)
	{
		// Nothing is left waiting on this NPC, whichever route got here first.
		// Both can fire for one cast — the graphic on the tick it is drawn, the
		// xp check when the damage should have landed — and a splash counted
		// twice would leave attempts running ahead of attacks made.
		castsAwaiting.removeIf(cast -> cast.npcIndex == index);
		resolvePendingSample(index);
		if (combatCalc.usesConflictionGauntlets())
		{
			combatCalc.noteMagicResolved(index, true);
		}
		current.recordSplash(now);
		// Gated exactly as a landed hit is: an unscored NPC spends the tick but
		// contributes no damage, accuracy or efficiency, and a splash on one is
		// no more scoreable than a hit on one.
		if (current.isScored())
		{
			session.recordAttempt(0, false, now);
		}
	}

	/**
	 * Judges the casts whose damage should have landed by now.
	 *
	 * <p>A cast with no projectile is the one attack whose failure nothing
	 * reports: no projectile to go unresolved, no hitsplat, and no splash of the
	 * hitsplat kind. So the question is turned around and asked as "did it
	 * land", which two things witness, and they answer different questions.
	 *
	 * <p><b>A hitsplat of mine on the NPC it was aimed at</b> says the cast
	 * connected <i>with that enemy</i>, which is what the confliction gauntlets
	 * turn on — the wiki's wording is "against the same enemy". It ends that
	 * cast's wait where it lands; nothing later can unsay it.
	 *
	 * <p><b>Hitpoints experience</b> says the cast damaged <i>something</i>,
	 * which is the right question for whether it was a splash at all, and needs
	 * no table of per-spell xp: damage of mine always pays it and a splash never
	 * does, a standard spell paying its base magic xp and no hitpoints xp, a
	 * powered staff paying nothing. Read as a total taken at the cast and
	 * compared at the resolve, so xp arriving on any tick between needs no
	 * bookkeeping.
	 */
	// TRACE. Prints an experience change with the tick it arrived on.
	private void traceXpChange(Skill skill, int index)
	{
		final int xp = client.getSkillExperience(skill);
		final int was = tracedXp[index];
		tracedXp[index] = xp;
		if (was > 0 && xp != was)
		{
			log.debug("TRACE xp tick {} {} +{}", client.getTickCount(), skill, xp - was);
		}
	}

	private void resolveCasts(long now)
	{
		final Iterator<PendingCast> waiting = castsAwaiting.iterator();
		while (waiting.hasNext())
		{
			final PendingCast cast = waiting.next();
			if (client.getTickCount() < cast.resolveTick)
			{
				continue;
			}
			waiting.remove();
			if (cast.connected || current == null || current.isEnded())
			{
				continue;
			}
			final boolean damagedSomething =
				client.getSkillExperience(Skill.HITPOINTS) > cast.xpBefore;
			log.debug("TRACE cast did not connect: npc {} tick {} hp xp moved {}", cast.npcIndex,
				client.getTickCount(), damagedSomething);
			if (combatCalc.usesConflictionGauntlets())
			{
				// Missed this enemy, whatever it did to anything else beside it.
				combatCalc.noteMagicResolved(cast.npcIndex, true);
			}
			if (!damagedSomething)
			{
				recordSplash(cast.npcIndex, now);
			}
		}
	}

	/**
	 * Records what the model expected of the attack that just resolved. Sampling
	 * per attack rather than once per fight keeps the figures honest when a spec
	 * weapon is swapped in partway: the mean then reflects the blend actually
	 * wielded instead of whichever weapon happened to be held at one instant.
	 */
	/**
	 * Recomputes the tick's expected figures for the fight on show. Everything
	 * that reads them, the overlay, the attack-tick sample, {@code ::loadout} -
	 * takes this one snapshot, so they cannot disagree with each other within a
	 * tick, and {@code CombatCalc}'s per-tick memo makes the whole set one
	 * evaluation however many callers ask.
	 */
	private void refreshExpected()
	{
		final Fight shown = getDisplayFight();
		specialAttack = combatCalc.specialAttack();
		// Sampled every tick and read back at the tick the attack went out on,
		// for the same reason the prayer is. Booking happens a tick or two
		// later, and a trident attack judged at its booking was being judged
		// against the whip the player had switched to by then — the whole point
		// of the question is what was worn when the attack was thrown.
		//
		// Asking it here rather than reconstructing it later also keeps the
		// weapon and the combat style honest: both are read live off the combat
		// tab, so they only describe this attack while this tick is current.
		//
		// Cheap despite appearances. The search behind it is held until the
		// weapon, the combat style, the target, or what the player has on them
		// changes — putting the answer on is none of those — so a fight fought
		// in one set searches once and every tick after costs a comparison.
		switchedByTick.put(client.getTickCount(),
			!combatCalc.missedGearSwitch(shown != null ? shown.getTargetId() : -1));
		switchedByTick.keySet().removeIf(t -> client.getTickCount() - t > SWITCH_HISTORY_TICKS);
		// A sample whose attack should long since have landed is dropped, so a
		// resolve that never comes cannot leave it waiting for the next fight's
		// hitsplat to claim it.
		pendingSamples.removeIf(sample -> client.getTickCount() - sample.tick > PENDING_SAMPLE_TICKS);
		// The speed of the weapon that will throw an attack on this tick, kept
		// per tick for the same reason as everything else here. Asked at the
		// booking instead it answers for whatever is held by then, which after a
		// switch is the wrong weapon — and can be worse than wrong, since the
		// combat tab lags a swap by a tick and rapid would then be read off the
		// old style and applied to the new weapon's speed.
		speedByTick.put(client.getTickCount(), combatCalc.attackSpeedTicks());
		speedByTick.keySet().removeIf(t -> client.getTickCount() - t > SWITCH_HISTORY_TICKS);
		if (shown != null)
		{
			// Pass the target so salve, dragon hunter and the rest can apply.
			expectedMaxHit = combatCalc.maxHit(shown.getTargetId());
			expectedAccuracy = combatCalc.hitChance(shown.getTargetId());
			expectedAverageHit = combatCalc.averageHit(shown.getTargetId());
			expectedSpecMaxHit = combatCalc.specialAttackMaxHit(shown.getTargetId());
			expectedForNpcId = shown.getTargetId();
			expectedByTick.put(client.getTickCount(),
				new double[]{expectedMaxHit, expectedAccuracy, expectedAverageHit, expectedForNpcId,
					combatCalc.isMeleeEquipped() ? 0 : 1});
			expectedByTick.keySet().removeIf(t -> client.getTickCount() - t > RECENT_TILES);
		}
		else
		{
			expectedMaxHit = combatCalc.maxHit(-1);
			expectedAccuracy = -1;
			expectedAverageHit = -1;
			expectedSpecMaxHit = combatCalc.specialAttackMaxHit(-1);
			expectedForNpcId = -1;
		}
	}

	/**
	 * Whether the target is dead but still on its feet. A fight only ends when
	 * the NPC despawns, and the death animation runs for several ticks before
	 * that — long enough for the next attack to come due against something that
	 * cannot be attacked. Those ticks belong with the unattackable ones: no
	 * attack was possible, so neither counting them lost nor counting them spent
	 * says anything about how the fight was played.
	 *
	 * <p>Reported as a lost tick on a kill finished with a dart and followed by
	 * a switch, which is simply the longest gap the booking lag allows before
	 * the corpse disappears.
	 */
	private boolean targetIsDying()
	{
		return targetNpc != null && targetNpc.isDead();
	}

	/**
	 * Whether an attack that has not been booked by now is late. Static and
	 * separate because the whole of a bug lived in it: dueTick counts in attack
	 * ticks and {@code now} is a booking tick, and the two are a tick or two
	 * apart depending on what will prove the next attack.
	 */
	/**
	 * Ticks between an area spell going out and its damage landing.
	 *
	 * <p>Wiki (Hit delay): {@code MagicDelay = 1 + floor((1 + Distance) / 3)},
	 * in game ticks. <b>The gap is not a constant, and every earlier attempt
	 * here treated it as one.</b> It was three, measured once; then the weapon's
	 * speed, which was five for a barrage. Both are right sometimes: a trace
	 * shows casts on 366 and 371 landing on 369 and 374, three ticks, and casts
	 * on 408, 413, 418 and 428 landing on 413, 418, 423 and 433, five. Distance
	 * is what separates them.
	 *
	 * <p>Judging late is not a small error. At five ticks with a five tick
	 * weapon the verdict arrives on the very tick the next cast goes out, which
	 * is both too late to read and late enough that the next cast used to
	 * overwrite it — so a splash thrown from range was never counted at all.
	 *
	 * <p>Distance is Chebyshev, and for these spells it is measured <i>from the
	 * player to the NPC's south-west tile</i> rather than edge to edge — the
	 * wiki calls barrage out as the exception. That is why standing against
	 * something the size of the Hueycoatl still reads as a dozen tiles.
	 */
	static int magicHitDelay(int distance)
	{
		return 1 + (1 + Math.max(0, distance)) / 3;
	}

	static boolean attackOverdue(int now, int dueTick, int bookingLag)
	{
		return now >= dueTick + bookingLag;
	}

	/**
	 * How long a booking might still take to arrive: the longer of what the
	 * last attack needs and what the weapon in hand would need.
	 *
	 * <p>Two attacks can be outstanding across a switch. The one already thrown
	 * carries the old weapon's lag — a dart fired before the swap is booked two
	 * ticks later whatever is held by then — while the next one will carry the
	 * new weapon's. Nothing can be called late until the longer of the two has
	 * had time to arrive, and taking only the weapon in hand booked a lost tick
	 * on every switch out of a projectile weapon into melee.
	 *
	 * <p>Self-limiting rather than merely lenient: once the deadline has passed
	 * by the longer lag, nothing can still be in flight.
	 */
	static int pendingBookingLag(boolean lastAttackWasProjectile, boolean meleeEquippedNow)
	{
		return lastAttackWasProjectile || !meleeEquippedNow ? PROJECTILE_BOOKING_LAG : MELEE_BOOKING_LAG;
	}

	private void record(boolean prayed, boolean switched, double actual, double ideal)
	{
		if (current != null && !current.isEnded())
		{
			current.recordAttackResolved(prayed, switched, actual, ideal);
		}
		session.recordAttackResolved(prayed, switched, actual, ideal);
	}

	private void sampleExpected(Fight fight)
	{
		final int targetId = fight.getTargetId();
		int maxHit = expectedMaxHit;
		double accuracy = expectedAccuracy;
		double averageHit = expectedAverageHit;
		// Prefer the figures from the tick the attack actually left on. A slow
		// projectile surfaces a tick after it was fired, and scoring it with the
		// loadout held by then credited a shadow's attack to the whip switched
		// to while it was still in the air.
		final double[] atOrigin = expectedByTick.get(attackOriginTick);
		if (attackObservedNpcId == targetId && attackObservedAccuracy >= 0)
		{
			maxHit = attackObservedMaxHit;
			accuracy = attackObservedAccuracy;
			averageHit = attackObservedAverageHit;
		}
		else if (atOrigin != null && (int) atOrigin[3] == targetId)
		{
			maxHit = (int) atOrigin[0];
			accuracy = atOrigin[1];
			averageHit = atOrigin[2];
		}
		else if (expectedForNpcId != targetId)
		{
			// The opening attack of a fight, whose figures the tick cache cannot
			// hold: the fight was created by the very event being sampled. Asking
			// for them now is a memo lookup on the tick already being served.
			maxHit = combatCalc.maxHit(targetId);
			accuracy = combatCalc.hitChance(targetId);
			averageHit = combatCalc.averageHit(targetId);
		}
		// Held rather than added, unless the attack has already resolved. What
		// an attack was expected to deal only belongs beside what it did deal
		// if it got the chance to deal it: a cast still in the air when someone
		// else lands the kill deals nothing, and counting its expectation
		// against a measured nought reads as the player having underperformed.
		// In a group that is most of the last cast on every kill.
		//
		// A melee blow is booked by its own hitsplat, so it has already
		// resolved and goes straight in.
		// One attack can be several rolls, and the measured side counts the
		// ATTACK, not the rolls: three scythe hitsplats land on one tick and the
		// burst grouping books them as one landed attack. So the expectation
		// beside it is the chance that AT LEAST ONE of the rolls connected, not
		// the sum of them. Summing read three chances against one measured hit
		// and marked the player down by that ratio — masked until now only
		// because the scythe was never being recognised in the first place.
		final double expectedChances =
			CombatCalc.landChance(accuracy, combatCalc.hitsPerAttack(targetId));
		if (attackObservedLag == MELEE_BOOKING_LAG)
		{
			fight.recordExpected(maxHit, expectedChances, averageHit);
			session.recordExpected(expectedChances, averageHit);
		}
		else
		{
			pendingSamples.add(new PendingSample(
				fight.getTargetIndex(), client.getTickCount(), maxHit, expectedChances, averageHit));
		}
		// Spent: they describe one attack, not the next.
		attackObservedNpcId = -1;
		attackObservedAccuracy = -1;
		attackObservedAverageHit = -1;
	}

	/**
	 * What an attack was expected to do, held until it is known to have
	 * resolved. Only the expected damage and hits wait: the attempt, the tick
	 * and the prayer, boost and switch counters are all recorded when the
	 * attack goes out, because those describe how it was set up and that
	 * happened whatever became of it.
	 */
	private static final class PendingSample
	{
		private final int npcIndex;
		private final int tick;
		private final int maxHit;
		private final double accuracy;
		private final double averageHit;

		private PendingSample(int npcIndex, int tick, int maxHit, double accuracy, double averageHit)
		{
			this.npcIndex = npcIndex;
			this.tick = tick;
			this.maxHit = maxHit;
			this.accuracy = accuracy;
			this.averageHit = averageHit;
		}
	}

	// An attack landed on this NPC, so the oldest thing waiting on it is what
	// resolved. A splash counts: it landed for nought, which is a real miss and
	// belongs in the accuracy.
	private void resolvePendingSample(int npcIndex)
	{
		for (int i = 0; i < pendingSamples.size(); i++)
		{
			final PendingSample sample = pendingSamples.get(i);
			if (sample.npcIndex != npcIndex)
			{
				continue;
			}
			pendingSamples.remove(i);
			if (current != null && !current.isEnded())
			{
				current.recordExpected(sample.maxHit, sample.accuracy, sample.averageHit);
			}
			session.recordExpected(sample.accuracy, sample.averageHit);
			return;
		}
	}

	// Anything still waiting once its target is gone never landed, and is
	// dropped rather than counted against a measured nought.
	private void dropPendingSamples()
	{
		pendingSamples.clear();
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
		final OlmPhase phase = OlmPhase.forNpc(npc.getId());
		if (phase != null)
		{
			// The flame wall is an NPC rather than an object, so it arrives here.
			olmPhase = phase;
		}
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
	 * fought at all, Sotetseg wears a separate one for the maze.
	 */
	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		final NPC npc = event.getNpc();
		if (current == null || current.isEnded())
		{
			return;
		}
		if (current.getTargetIndex() == npc.getIndex())
		{
			targetLiveId = npc.getId();
		}
		// A boss that is never removed from the scene announces its defeat by
		// changing into a beaten form. That is the kill, and it beats waiting
		// for loot: in a group there may be no drop for this player at all, and
		// then no loot event ever arrives.
		if (EncounterGroup.isDefeated(npc.getId())
			&& EncounterGroup.sameGroup(npc.getId(), current.getTargetId()))
		{
			finalizeFight(true, System.currentTimeMillis());
		}
	}

	/**
	 * A drain landed by another party member, from the special attack counter's
	 * own message. It carries only what others did, and only while a party is
	 * set up, so it adds to rather than replaces watching our own energy.
	 */
	@Subscribe
	public void onSpecialCounterUpdate(SpecialCounterUpdate event)
	{
		if (current != null && !current.isEnded()
			&& current.getTargetIndex() == event.getNpcIndex())
		{
			drain.onSpecialCounterUpdate(event, targetNpc);
		}
	}

	/**
	 * Takes the party's hitpoints levels from the party plugin's own broadcasts,
	 * which is where the Chambers scaling term comes from, the game gives only
	 * the player's own level, and raiding beside a higher one made it wrong.
	 */
	@Subscribe
	public void onStatusUpdate(StatusUpdate event)
	{
		partyHitpoints.onStatusUpdate(event);
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		partyHitpoints.forget(event.getMemberId());
	}

	/**
	 * A cast with no projectile is only visible as the caster's own animation.
	 *
	 * <p>A barrage that misses produces nothing else at all: no projectile, no
	 * hitsplat, and no splash graphic — traced across a whole kill. Booking such
	 * a cast from its hitsplat therefore counts only the ones that hit, which
	 * quietly drove accuracy towards a hundred per cent, because every miss
	 * vanished from both halves of the sum rather than from one.
	 *
	 * <p>So these are booked here instead, and not from the hitsplat. The
	 * animation fires on the tick the cast goes out, which makes this the most
	 * exact of the four routes rather than the least: no booking lag at all,
	 * where the hitsplat needed three.
	 *
	 * <p>What the animation *is* does not matter and is deliberately not
	 * checked — that would be the per-spell table that sank two earlier designs.
	 * What carries the weight is that a no-projectile spell is the attack in
	 * hand and the weapon is off cooldown, so an eat between casts cannot book
	 * one.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		final Player me = client.getLocalPlayer();
		if (event.getActor() != me || me.getAnimation() == IDLE_ANIMATION)
		{
			return;
		}
		if (!combatCalc.castLandsWithoutProjectile()
			|| client.getTickCount() < lastAttackSeenTick + combatCalc.attackSpeedTicks())
		{
			return;
		}
		// Any animation at all was too generous: being hit plays one, and so
		// does eating, and both booked an attack once the weapon was off
		// cooldown. Traced over one trip, 1156 fired fifty-eight times and 829
		// nine, against the cast's own 10092.
		//
		// So the cast animation is named. A short list rather than a table of
		// every weapon and spell, because only the projectile-less spells come
		// through here — and an id missing from it costs the misses but not the
		// hits, since the hitsplat still books what lands.
		if (!CAST_ANIMATIONS.contains(me.getAnimation()))
		{
			log.debug("TRACE cast UNKNOWN ANIM tick={} id={} spell={}",
				client.getTickCount(), me.getAnimation(), combatCalc.activeSpellName());
			return;
		}
		final NPC target = castTarget();
		if (target == null)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (current == null || current.isEnded() || current.getTargetIndex() != target.getIndex())
		{
			startFight(target, now);
		}
		lastCastBookedTick = client.getTickCount();
		// Due when the damage would land, and HITPOINTS xp cannot make it any
		// sooner. Judging on the cast tick was tried and the log threw it out:
		// a cast booked on tick 20 read "hp xp moved false" while its hitsplat
		// arrived on tick 25 for 28 damage, and the same on ticks 30, 52, 82 and
		// 97. Hitpoints xp arrives WITH THE DAMAGE. Every cast therefore read as
		// a splash, which armed the gauntlets on hits and counted the expected
		// damage on the cast instead of on the landing.
		//
		// What is still open is MAGIC xp, which may well arrive at the cast —
		// that is what an xp drop plugin shows immediately. If it does, a splash
		// is knowable at once and the wait goes away. It is being measured; see
		// the xp trace in onGameTick.
		castsAwaiting.add(new PendingCast(target.getIndex(),
			client.getTickCount() + magicHitDelay(castDistance(target)),
			hitpointsXpLastTick));
		recordAttackObserved(false, target.getId(), CAST_BOOKING_LAG);
	}

	/**
	 * Chebyshev distance from the player to the target's south-west tile, which
	 * is what these spells measure and not the edge-to-edge distance everything
	 * else uses. {@code getWorldLocation} on an NPC is that tile already.
	 */
	private int castDistance(NPC target)
	{
		final Player me = client.getLocalPlayer();
		if (me == null || me.getWorldLocation() == null || target.getWorldLocation() == null)
		{
			return 0;
		}
		return Math.max(
			Math.abs(me.getWorldLocation().getX() - target.getWorldLocation().getX()),
			Math.abs(me.getWorldLocation().getY() - target.getWorldLocation().getY()));
	}

	/** A cast with no projectile, and the tick its damage should land on. */
	private static final class PendingCast
	{
		private final int npcIndex;
		private final int resolveTick;
		private final int xpBefore;
		private boolean connected;

		PendingCast(int npcIndex, int resolveTick, int xpBefore)
		{
			this.npcIndex = npcIndex;
			this.resolveTick = resolveTick;
			this.xpBefore = xpBefore;
		}
	}

	// What the cast is aimed at: what I am interacting with, or failing that
	// the fight already open, which covers the tick an autocast rolls onto a
	// new target before getInteracting has caught up.
	private NPC castTarget()
	{
		final Player me = client.getLocalPlayer();
		final Actor interacting = me == null ? null : me.getInteracting();
		if (interacting instanceof NPC)
		{
			return (NPC) interacting;
		}
		return current != null && !current.isEnded() ? targetNpc : null;
	}

	/**
	 * A boss that never despawns still drops loot, and that is what says it
	 * died.
	 *
	 * <p>The Hueycoatl's combat NPCs are never removed — a whole kill produced
	 * despawns for lobby and victory workers and nothing else, all reading
	 * dead=false — so the despawn path could not see the kill however the
	 * grouping was arranged, and kills stayed at zero while damage counted.
	 *
	 * <p>Fired by the client's own loot manager rather than by a plugin, and it
	 * names the NPC, so a death inside the group ends the fight as a kill on the
	 * same terms as a despawn would. Anything that dies without dropping is
	 * still covered by the despawn.
	 */
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		final NPC npc = event.getNpc();
		final long now = System.currentTimeMillis();
		if (current != null && !current.isEnded()
			&& (current.getTargetIndex() == npc.getIndex()
			|| EncounterGroup.sameGroup(npc.getId(), current.getTargetId())))
		{
			finalizeFight(true, now);
		}
		// The room ends with the kill that closed it, so the next kill opens a
		// fresh one. Without this a grouped boss kept every kill of a trip in
		// the room the first one opened: the Hueycoatl's head, tail and body are
		// deliberately one room, so the name always matched and the room always
		// accepted. The overlay then read as a running total of the trip with
		// the trip totals switched off — which is the failure openEncounterFor
		// already guards against, but only for an NPC that is not grouped.
		//
		// A loot drop is the one event that says a KILL happened here rather
		// than a part of the boss dying, which is why the room cannot simply
		// close on a fight that ended in a death: several of those belong to one
		// Hueycoatl. Inside a raid no loot drop fires and rooms there really do
		// span several fights, so Olm and the Nylocas are untouched.
		//
		// Ended rather than dropped, so the kill just made stays on the overlay
		// to be read. openEncounterFor will not continue an ended room.
		if (currentEncounter != null && !currentEncounter.isEnded() && currentEncounter.holds(npc.getId()))
		{
			currentEncounter.end(now);
			// The export rebuilds its rooms from the fights alone and would
			// otherwise group a trip's kills exactly as the overlay used to, so
			// the fight the kill closed on carries the boundary into the file.
			final List<Fight> held = currentEncounter.getFights();
			if (!held.isEmpty())
			{
				held.get(held.size() - 1).closeRoom();
			}
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		final NPC npc = event.getNpc();
		// The drain dies with the NPC, as its stats do.
		drain.forget(npc.getIndex());
		if (current == null || current.isEnded())
		{
			return;
		}
		if (current.getTargetIndex() == npc.getIndex())
		{
			finalizeFight(npc.isDead(), System.currentTimeMillis());
			return;
		}
		// A boss made of several NPCs does not die on the one being hit. The
		// Hueycoatl is killed at its head while the fight is usually open on a
		// body segment, so the death despawns an index the fight has never
		// heard of and the kill went unrecorded — damage counted, kills stayed
		// at zero.
		//
		// Only a death, and only within the group, so an add dying beside a
		// boss cannot claim the kill.
		if (npc.isDead() && EncounterGroup.sameGroup(npc.getId(), current.getTargetId()))
		{
			finalizeFight(true, System.currentTimeMillis());
		}
	}

	// Advances the attack cooldown by a tick and books the tick as an attack,
	// as wasted, or as still on cooldown.
	private void trackAttackCooldown()
	{
		final boolean attacked = attackObservedTick == client.getTickCount();
		attackObservedTick = -1;

		if (current == null || current.isEnded())
		{
			attackDueTick = Integer.MIN_VALUE;
			lastAttackFromProjectile = false;
			return;
		}
		if (attacked)
		{
			// Before anything reads the style: which event proved this attack
			// decides whether a queued cast can be what went out.
			combatCalc.noteAttackKind(attackObservedFromProjectile);
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
			// The two are read at different moments on purpose. A prayer counts if
			// it was up at any point in the tick, which is what makes flicking
			// work, and one flicked on after the swing is still processed before
			// the server resolves the tick. A potion is not: drinking after the
			// attack cannot have boosted it, so the boost is taken as it stood
			// when the attack went out.
			// The carried flag alone, and deliberately not the live varbits. An
			// attack is booked the tick after the one it went out on, so what was
			// up at the end of the previous tick is what the server resolved it
			// with. Reading the varbits here instead counts a prayer switched on
			// after the swing, which is the whole of the bug: a flick showed
			// upTick one below the booking tick, a late prayer showed it equal.
			// Which tick the attack went out on depends on what proved it. A
			// hitsplat lands the tick the blow is thrown, so a melee attack is
			// booked on time and this tick's answer is the right one. A
			// projectile surfaces a tick after it is fired, so a magic or ranged
			// attack is booked late and the previous tick's answer is.
			// The tick the attack actually went out on. Taken from the event that
			// proved it rather than from a fixed offset: a projectile carries the
			// cycle it was fired on, and a hitsplat lands the tick of the blow.
			// Guessed offsets could not work here, because the gap between an
			// attack and its booking is not constant.
			final int attackTick = client.getTickCount() - attackObservedLag;
			final boolean prayed = Boolean.TRUE.equals(prayerUpByTick.get(attackTick));
			// TRACE. Which tick the booking read, and what was stored there.
			log.debug("TRACE booked tick {} lag {} -> attackTick {} prayed {} stored {}",
				client.getTickCount(), attackObservedLag, attackTick, prayed,
				prayerUpByTick.get(attackTick));
			final boolean potted = attackObservedPotted;
			// Worked out both ways now, while the loadout and boost are the ones
			// that threw the attack. Which of the two applies is decided by the
			// prayer, and for a projectile that is not known until it lands.
			final double ifPrayed = combatCalc.actualAverageHit(targetId, true);
			final double ifNot = combatCalc.actualAverageHit(targetId, false);
			final double idealSetup = combatCalc.idealAverageHit(targetId);
			// Read from the tick the attack went out on, never asked live here:
			// by now the weapon and the gear may both be someone else's. Absent
			// means the tick was never sampled, which is not evidence of a
			// missed switch, so it reads as clean.
			final boolean switched = !Boolean.FALSE.equals(switchedByTick.get(attackTick));
			// The pause an eat caused is over the moment an attack goes out.
			lastConsumeTick = 0;
			consumeDelay = 0;
			current.recordAttackMade(potted);
			if (current.isScored())
			{
				session.recordAttackMade(potted);
				// Decided here, at the attack, for both styles. The damage this
				// is measured against was worked out on this tick from this
				// loadout and this boost, so the prayer has to be the one that
				// went with it. Reading it when the hitsplat arrives instead
				// measured the prayer at the LANDING, ticks after the server had
				// already scored the attack.
				record(prayed, switched, prayed ? ifPrayed : ifNot, idealSetup);
				// Sampled on the tick the attack went out, not the tick it
				// resolved, so the figures describe the loadout that threw it,
				// every style updates on the same beat, and one attack takes one
				// sample however it ends.
				sampleExpected(current);
			}
			else
			{
				session.recordTickSpent();
			}
			// Last of all. Everything above describes the attack that just went
			// out, the cooldown included, a cast holds the weapon for five ticks
			// and a whip for four, so spending the cast any earlier would put the
			// spell on the whip's clock.
			// From the tick the attack went out on, not from this one, and with
			// the speed that was in force then. This is the booking tick, which
			// trails the attack by one for melee and two for a projectile, so
			// reading the speed here reads it off whatever is held by now.
			final Integer threwAt = speedByTick.get(attackTick);
			attackDueTick = attackTick
				+ (threwAt != null ? threwAt : combatCalc.attackSpeedTicks());
			lastAttackFromProjectile = attackObservedFromProjectile;
			if (attackObservedFromProjectile)
			{
				// Only a cast can spend a cast. A melee blow landing on the tick
				// the spell was clicked would otherwise consume it, and the cast
				// that went out a tick later then read as the weapon, a fire
				// strike scoring the fang's expected damage.
				combatCalc.noteAttackThrown();
			}
			// Last: back to describing the loadout rather than one past attack,
			// so the overlay goes on showing a queued cast between attacks.
			combatCalc.noteAttackKind(true);
			return;
		}
		// The trip totals take the same ticks as they happen, so the share shown
		// in whole-trip mode moves during a fight rather than only at its end.
		// Both are gated on the fight having started, which is the fight's rule
		// for counting a tick at all.
		if (EncounterGroup.isUnattackable(targetLiveId) || targetIsDying())
		{
			// Between phases, charging, or dead but still standing. The tick is
			// booked neither lost nor spent: no attack was possible, so counting
			// it either way would move a figure that measures choices. The due
			// tick simply passes while this runs, which is what leaves the first
			// attackable tick due immediately.
			return;
		}
		final boolean counts = current.isAttacking();
		// The due tick is in attack ticks; a booking arrives a tick or two after
		// the attack it describes, so the wait is measured against the tick that
		// booking would land on. Which lag applies is decided by the weapon in
		// hand now rather than the one that threw the last attack, because it is
		// the next attack whose booking is being waited for: a whip attack
		// followed by a switch to a trident is booked two ticks after the trident
		// goes out, and holding that to the whip's one-tick lag booked a lost
		// tick on every switch from melee into a projectile weapon.
		final int lag = pendingBookingLag(lastAttackFromProjectile, combatCalc.isMeleeEquipped());
		if (!attackOverdue(client.getTickCount(), attackDueTick, lag))
		{
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

	// Notices food and drink going down, from the click rather than the
	// animation.
	private void noteConsumed(MenuOptionClicked event)
	{
		final String option = event.getMenuOption();
		if (!"Eat".equals(option) && !"Drink".equals(option))
		{
			return;
		}
		final int tick = client.getTickCount();
		if (tick != lastConsumeTick)
		{
			// A new pause rather than a second helping added to the last one.
			lastConsumeTick = tick;
			consumeDelay = 0;
		}
		consumeDelay += isComboFood(event.getItemId()) ? KARAMBWAN_TICKS : EAT_TICKS;
	}

	private static boolean isComboFood(int itemId)
	{
		return itemId == ItemID.TBWT_COOKED_KARAMBWAN || itemId == ItemID.BR_TBWT_COOKED_KARAMBWAN;
	}

	// Whether this lost tick was spent eating.
	private boolean isConsuming()
	{
		final Player me = client.getLocalPlayer();
		if (me != null && CONSUME_ANIMATIONS.contains(me.getAnimation()) && consumeDelay == 0)
		{
			// Nothing was clicked that this knows about, a wine, a cake, an
			// unfamiliar option. The animation is the weaker signal but it is
			// better than calling the tick idle.
			lastConsumeTick = client.getTickCount();
			consumeDelay = EAT_TICKS;
		}
		return lastConsumeTick > 0 && client.getTickCount() - lastConsumeTick < consumeDelay;
	}

	/**
	 * Notes that an attack went out this tick. Called only from the two events
	 * that prove one did, and whose tick is exact: a melee hitsplat lands on the
	 * tick it was thrown, and a projectile is created on the tick it was fired.
	 */
	private void recordAttackObserved(boolean fromProjectile, int npcId)
	{
		recordAttackObserved(fromProjectile, npcId,
			fromProjectile ? PROJECTILE_BOOKING_LAG : MELEE_BOOKING_LAG);
	}

	// How far behind the attack this observation is depends on what proved it,
	// and there are three answers rather than two: a melee blow lands the tick
	// it is thrown, a projectile surfaces two ticks later, and a cast with no
	// projectile lands three.
	private void recordAttackObserved(boolean fromProjectile, int npcId, int bookingLag)
	{
		attackObservedLag = bookingLag;
		// Recorded here rather than at the booking, which is a tick or two
		// later: this is what says a weapon is on cooldown, and it has to be
		// true from the moment the attack goes out.
		lastAttackSeenTick = client.getTickCount();
		attackObservedTick = client.getTickCount();
		attackObservedFromProjectile = fromProjectile;
		attackOriginTick = client.getTickCount();
		attackObservedNpcId = npcId;
		attackObservedMaxHit = combatCalc.maxHit(npcId);
		attackObservedAccuracy = combatCalc.hitChance(npcId);
		attackObservedAverageHit = combatCalc.averageHit(npcId);
		// A weapon swapped on a tick is not wielded until the next one, so an
		// attack thrown on the tick of a switch used what was held before it.
		// The client shows the new weapon immediately, which is why the figures
		// have to come from the tick before the change rather than from now.
		// This holds for every switch, not only one that lands on a melee
		// weapon: magic to ranged and staff to staff are the same case.
		if (gearChangedTick >= client.getTickCount() - 1)
		{
			for (int tick = gearChangedTick - 1;
				tick >= gearChangedTick - SWITCH_LOOKBACK_TICKS; tick--)
			{
				final double[] earlier = expectedByTick.get(tick);
				if (earlier != null && (int) earlier[3] == npcId && earlier[1] >= 0)
				{
					attackObservedMaxHit = (int) earlier[0];
					attackObservedAccuracy = earlier[1];
					attackObservedAverageHit = earlier[2];
					break;
				}
			}
		}
		attackObservedPotted = combatCalc.isPotted();
	}

	// Notices the intended prayer going up part-way through a tick, so a
	// prayer switched on and attacked with on the same tick counts.
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.WORN)
		{
			combatCalc.invalidateGear();
			gearChangedTick = client.getTickCount();
		}
		else if (event.getContainerId() == InventoryID.INV)
		{
			// What could have been switched into may have changed. Eating and
			// drinking fire this too, and so does every switch, which is why
			// nothing is counted here — the next attack decides whether the
			// player actually gained or lost anything wearable.
			combatCalc.invalidateInventory();
		}
	}

	/**
	 * Mark of Darkness, tracked from the game's own messages because no varbit
	 * carries it — the one named for it is a buff bar display toggle. The
	 * messages are exact, which beats computing a duration from the magic level
	 * and the staff and hoping the formula holds.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		final String message = Text.removeTags(event.getMessage());
		if (message.startsWith("You have placed a Mark of Darkness upon yourself"))
		{
			gearBonuses.setMarkOfDarkness(true);
		}
		else if (message.startsWith("Your Mark of Darkness has faded away"))
		{
			gearBonuses.setMarkOfDarkness(false);
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Watched here rather than on its own, because the energy falling is what
		// says a special went out and the player's own specials are not carried
		// by the party message.
		drain.onEnergyChanged();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		gearBonuses.updateRaidState();
		RaidScaling.setTombsRaidLevel(gearBonuses.tombsRaidLevel());
		trackRaid(System.currentTimeMillis());
		startFightOnTarget();
		// Refreshed before the attack is booked, not after. The attack tick is
		// where the expected figures are sampled, and they have to describe the
		// loadout that threw it, reading a cache filled at the end of the last
		// tick would date every sample by one tick and misattribute any switch.
		// Before the figures are refreshed, so the armed accuracy is on screen
		// the moment the miss is known. Safe ahead of the booking below because
		// a cast is never judged on its own tick — the hit delay is at least
		// one — so this can only arm for an attack thrown later, which is the
		// attack the bonus really does apply to.
		resolveCasts(System.currentTimeMillis());
		// TRACE. Sampled here rather than from StatChanged, which was subscribed
		// and registered and never once printed across a whole session. Whatever
		// the reason, a tick loop that is known to run is the better instrument,
		// and the question it has to answer is narrow: does MAGIC xp move on the
		// tick a spell is cast, or on the tick its damage lands? Read it against
		// the "booked" and "hitsplat" lines. Hitpoints xp is in there beside it
		// as the control — that one is already known to arrive with the damage.
		if (current != null && !current.isEnded())
		{
			traceXpChange(Skill.MAGIC, 0);
			traceXpChange(Skill.HITPOINTS, 1);
		}
		refreshExpected();
		// The server's copy, which is the only one that answers the question
		// being asked: did the server resolve this tick with the prayer up.
		// Clicking a prayer off flips the client's copy at once so the orb
		// responds without waiting for a reply, which is exactly what a flick
		// does at the end of a tick — the client reads off while the server
		// still resolved the attack with it up. That is the undercount.
		//
		// A flag fed from the varbit event was tried here and removed. It could
		// only add trues, and it added them a tick late as well as on time, so
		// one flick satisfied two bookings. It also earned nothing: on every
		// pulse traced, the reading below was already true on its own.
		//
		// Taken BEFORE the attack is booked, beside the rest of the tick's
		// samples. It sat after the booking on the reasoning that an attack
		// booked this tick went out on an earlier one and so reads an earlier
		// tick's answer — true of melee at one tick's lag and of a projectile
		// at two, and false of the one attack that is booked on the tick it
		// goes out. An ancient area spell is booked from the cast animation at
		// no lag at all, so it read this tick's answer before this tick had
		// one, found nothing, and counted every barrage as unprayed however
		// long augury had been up. Writing first is safe for the other two:
		// they read keys this never touches.
		final boolean upThisTick = combatCalc.hasOffensivePrayer();
		prayerUpByTick.put(client.getTickCount(), upThisTick);
		prayerUpByTick.keySet().removeIf(t -> client.getTickCount() - t > PRAYER_HISTORY_TICKS);
		// TRACE. What the prayer and the gauntlets were read as, which is what a
		// booking will later read back. Printed only when it CHANGES: one line
		// a tick of every fight ran a log to 89MB in half an hour, and the
		// interesting thing here is always the moment a value moves.
		if (current != null && !current.isEnded())
		{
			final String line = upThisTick + " " + combatCalc.traceLine();
			if (!line.equals(lastTraceLine))
			{
				lastTraceLine = line;
				log.debug("TRACE tick {} up={}", client.getTickCount(), line);
			}
		}
		trackAttackCooldown();
		hitpointsXpLastTick = client.getSkillExperience(Skill.HITPOINTS);

		final Player me = client.getLocalPlayer();
		if (me != null && me.getLocalLocation() != null)
		{
			recentTiles.put(client.getTickCount(), me.getLocalLocation());
			recentTiles.keySet().removeIf(t -> client.getTickCount() - t > RECENT_TILES);
		}

		// Drop projectiles that have landed so the set doesn't retain them.
		countedProjectiles.values().removeIf(seen -> client.getTickCount() - seen > PROJECTILE_KEY_TICKS);
		// Bursts are over long before this; the entries are dropped so the two
		// collections cannot grow with every NPC ever hit.
		lastHitsplatTick.values().removeIf(seen -> client.getTickCount() - seen > PROJECTILE_KEY_TICKS);
		burstLanded.retainAll(lastHitsplatTick.keySet());
		burstBooked.retainAll(lastHitsplatTick.keySet());

		if (current != null && !current.isEnded())
		{
			if (current.getAttempts() == 0)
			{
				// Opened by targeting and not yet fought. The idle timeout can't
				// judge it: it would expire while the player is still walking
				// into range. It ends when they look away instead, and not the
				// instant it blinks, because the interaction lapses on its own
				// between a cast leaving and its projectile appearing.
				notTargetingTicks = isTargeting(current.getTargetIndex())
					? 0 : notTargetingTicks + 1;
				if (notTargetingTicks > LOOK_AWAY_TICKS)
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
			recentTiles.clear();
			lastHitsplatTick.clear();
			burstLanded.clear();
			pendingMineHits.clear();
			drain.clear();
			nightmareBoss = null;
			targetNpc = null;
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
		// Tags stripped: some names carry colour markup and it was reaching the
		// CSV verbatim, "<col=00ffff>Rubble</col>" and all.
		current = new Fight(Text.removeTags(npc.getName()), npc.getId(), npc.getIndex(),
			maxHp == null ? -1 : maxHp, now,
			raidType, raidCounter);
		targetLiveId = npc.getId();
		targetNpc = npc;
		combatCalc.setTargetIndex(npc.getIndex());
		labelOlmPhase(current);
		labelNightmareTotem(current, npc.getId());
		openEncounterFor(current, now);
		if (npc.getIndex() == respawnNpcIndex)
		{
			// This is the boss whose respawn was watched, so the wait for it can
			// be timed from the tick it appeared rather than from this one.
			current.setEngageFromTick(respawnTick);
			respawnNpcIndex = -1;
		}
	}

	// Opens a fight as soon as the player targets something attackable, so the
	// overlay is up while the first attack is still in the air rather than
	// appearing when it lands.
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

	// Files a fight under the room it belongs to, continuing the room already
	// open when it is the same one.
	private void openEncounterFor(Fight fight, long now)
	{
		// Only a grouped NPC continues a room. Without that, a second Vorkath
		// would join the first, same name, so the same room, and the overlay
		// would quietly turn into a running total of the trip.
		final String name = fight.encounterName();
		// An ended room takes nothing more. It is left on show so the kill can
		// be read, and this is what stops the fight after it being added to it.
		if (currentEncounter == null || currentEncounter.isEnded()
			|| fight.getGroupName() == null || !currentEncounter.accepts(name))
		{
			if (currentEncounter != null)
			{
				if (!currentEncounter.isEnded())
				{
					currentEncounter.end(now);
				}
				if (currentEncounter.hasAttempts())
				{
					lastFinishedEncounter = currentEncounter;
				}
			}
			currentEncounter = new Encounter(name, raidType, now);
			if (currentRaid != null)
			{
				currentRaid.add(currentEncounter);
			}
		}
		currentEncounter.add(fight);
		log.debug("PvM Performance: fight on {} (npc {}) -> room '{}' group={} fights={}",
			fight.getTargetName(), fight.getTargetId(), currentEncounter.getName(),
			fight.getGroupName(), currentEncounter.getFights().size());
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
		olmPhase = null;
		if (raidType != null)
		{
			currentRaid = new Raid(raidType, ++raidCounter, now);
		}
	}

	/**
	 * Names an Olm fight by the special its phase is running, so the three can be
	 * compared against each other. A phase whose special has not shown itself yet
	 * keeps the plain name rather than being filed under a guess.
	 */
	private void labelOlmPhase(Fight fight)
	{
		if (olmPhase == null || fight.getGroupName() == null
			|| !fight.getGroupName().startsWith("Great Olm"))
		{
			return;
		}
		fight.setEncounterLabel(fight.getGroupName() + " · " + olmPhase.getLabel());
	}

	/**
	 * Files a totem under whichever Nightmare is being fought. The two share
	 * their totem ids, so the boss in the room is the only thing that tells them
	 * apart, and it is always met before its totems are.
	 */
	private void labelNightmareTotem(Fight fight, int npcId)
	{
		final String boss = EncounterGroup.nightmareBossName(npcId);
		if (boss != null)
		{
			nightmareBoss = boss;
			return;
		}
		if (nightmareBoss != null && EncounterGroup.isNightmareTotem(npcId))
		{
			fight.setEncounterLabel(nightmareBoss);
		}
	}

	private void finalizeFight(boolean died, long now)
	{
		current.end(died, now);
		// Anything still in the air never landed. Counting what it was expected
		// to deal against the nought it actually dealt is what made a group kill
		// read as underperformance.
		dropPendingSamples();
		// A cast still in the air when the target died is not a splash, and the
		// fight it belonged to is over either way.
		castsAwaiting.clear();
		pendingMineHits.clear();
		// Nothing is being fought, so no drain should be read against anything.
		combatCalc.setTargetIndex(-1);
		targetNpc = null;
		targetLiveId = -1;
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

	// The tick a projectile is due to land on. Cycles run at 20ms and ticks at
	// 600, so thirty of the former make one of the latter.
	// The tick a projectile was fired on, from the cycle it started.
	private int startTick(Projectile projectile)
	{
		final int cyclesAgo = Math.max(0, client.getGameCycle() - projectile.getStartCycle());
		return client.getTickCount() - cyclesAgo / CYCLES_PER_TICK;
	}

	private int landingTick(Projectile projectile)
	{
		final int cyclesOut = Math.max(0, projectile.getEndCycle() - client.getGameCycle());
		return client.getTickCount() + cyclesOut / CYCLES_PER_TICK;
	}

	// Whether a hitsplat landing now is one of my attacks arriving from flight,
	// rather than a fresh melee blow. Matched on when the attack was due to land
	// rather than counted, because a count cannot expire: a projectile that never
	// produces a hitsplat leaves the count high forever, and then a later melee
	// hit is mistaken for it and never booked as an attack of its own.
	private boolean consumePending(int npcIndex)
	{
		final List<Integer> due = pendingMineHits.get(npcIndex);
		if (due == null)
		{
			return false;
		}
		final int now = client.getTickCount();
		// Anything that should have landed and did not is dropped rather than
		// left to be matched against something unrelated later.
		due.removeIf(tick -> now - tick > FLIGHT_SLACK_TICKS);
		for (int i = 0; i < due.size(); i++)
		{
			if (Math.abs(due.get(i) - now) <= FLIGHT_SLACK_TICKS)
			{
				due.remove(i);
				return true;
			}
		}
		if (due.isEmpty())
		{
			pendingMineHits.remove(npcIndex);
		}
		return false;
	}

	/** The fight the overlay should display: the active one, else the last finished. */
	Fight getDisplayFight()
	{
		return current != null ? current : lastFinished;
	}

	/**
	 * The room the overlay should display, or null if nothing has been fought.
	 *
	 * <p>A room that has been opened but not yet fought in does not replace the
	 * one before it. With the trip totals off the overlay is the room, so the
	 * kill just made vanished the instant anything opened a new one — and a
	 * fight opens on merely looking at an NPC, which after a kill is
	 * immediate. The player could never read how the kill went. The finished
	 * room stays up until the first attack of the next one.
	 */
	Encounter getDisplayEncounter()
	{
		if (currentEncounter != null && currentEncounter.hasAttempts())
		{
			return currentEncounter;
		}
		return lastFinishedEncounter != null ? lastFinishedEncounter : currentEncounter;
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
			// Keyed on the room rather than the NPC, so a grouped room reads as
			// one line instead of one per add.
			byName.computeIfAbsent(fight.encounterName(), NpcStats::new).add(fight);
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
			if (fight.encounterName().equals(name))
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
					writer.write(CSV_HEADER);
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

	// Writes each fight, then the room it belonged to, then the raid the rooms
	// belonged to: the same numbers at three widths, told apart by the level
	// column. Phases are written whole rather than summed away, so which phase
	// a kill went wrong on survives into the file, and anyone who wants only
	// the rooms can filter on one column.
	private static void writeRows(Writer writer, List<Fight> fights) throws IOException
	{
		final List<Fight> ordered = new ArrayList<>(fights);
		ordered.sort(Comparator.comparingLong(Fight::getStartMillis));

		List<Encounter> raidRooms = new ArrayList<>();
		Encounter room = null;
		int raidRun = 0;
		String raidName = null;

		boolean closesRoom = false;
		for (Fight fight : ordered)
		{
			final boolean newRaid = fight.getRaidId() != raidRun;
			if (newRaid && !raidRooms.isEmpty())
			{
				writer.write(csvRaidRow(raidName, raidRun, raidRooms));
				raidRooms = new ArrayList<>();
			}
			// A room also ends at the fight that closed it, so a trip's kills
			// are one room row each rather than one row for the lot. Only that
			// fight breaks it: a boss made of several NPCs sets targetDied on
			// the way down and would otherwise split a single kill.
			if (room == null || newRaid || closesRoom || fight.getGroupName() == null
				|| !room.accepts(fight.encounterName()))
			{
				writeRoomRow(writer, raidName, raidRun, room);
				room = new Encounter(fight.encounterName(), null, fight.getStartMillis());
				if (fight.getRaidId() > 0)
				{
					raidRooms.add(room);
				}
			}
			raidRun = fight.getRaidId();
			raidName = fight.getRaidName();
			room.add(fight);
			closesRoom = fight.isClosedRoom();
			writer.write(csvFightRow(raidName, raidRun, fight));
		}
		writeRoomRow(writer, raidName, raidRun, room);
		if (!raidRooms.isEmpty())
		{
			writer.write(csvRaidRow(raidName, raidRun, raidRooms));
		}
	}

	// A room row only earns its place when the room holds more than one fight.
	// Everywhere outside a raid it holds exactly one, so writing it repeated the
	// fight row verbatim and doubled the length of the file for nothing.
	private static void writeRoomRow(Writer writer, String raidName, int raidRun, Encounter room)
		throws IOException
	{
		if (room != null && room.getFights().size() > 1)
		{
			writer.write(csvRoomRow(raidName, raidRun, room));
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

	static String csvRoomRow(String raidName, int raidRun, Encounter room)
	{
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(room.getStartMillis()), ZoneId.systemDefault()));
		final double seconds = Math.max(0.6, room.durationMillis() / 1000.0);
		final int attempts = room.getAttempts();
		return csvScope("room", raidName, raidRun, room.getName())
			+ String.format("%s,,,,%b,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,,%s,%s,%d,%s,%d,,%d,%d,%d,%d,%s%n",
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
				room.getAttacksPotted(),
				room.getAttacksSwitched(),
				csvExpected(room.efficiency() * 100, 1));
	}

	static String csvRaidRow(String raidName, int raidRun, List<Encounter> rooms)
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
			+ String.format("%s,,,,,%d,,%d,%d,%.1f,%.1f,%.2f,%.2f,,%s,%s,%d,%s,%d,,%d,%d,%d,%d,%s%n",
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
				raid.getAttacksPotted(),
				raid.getAttacksSwitched(),
				csvExpected(raid.efficiency() * 100, 1));
	}

	private static String csvFightRow(String raidName, int raidRun, Fight fight)
	{
		// "fight" rather than "phase": a phase is what it is inside a raid, but
		// outside one it is a single kill, and the label read as jargon.
		return csvScope("fight", raidName, raidRun, fight.encounterName()) + csvRow(fight);
	}

	static String csvRow(Fight fight)
	{
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(fight.getStartMillis()), ZoneId.systemDefault()));
		return String.format("%s,\"%s\",%d,%d,%b,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%s,%s,%s,%d,%s,%d,%s,%d,%d,%d,%d,%s%n",
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
			fight.getAttacksPotted(),
			fight.getAttacksSwitched(),
			csvExpected(fight.efficiency() * 100, 1));
	}

	/**
	 * Expected figures are left blank rather than written as a number when the
	 * model had nothing to say, no target stats, or a fight recorded before
	 * these columns existed, so a reader can tell "unknown" from a real zero.
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
