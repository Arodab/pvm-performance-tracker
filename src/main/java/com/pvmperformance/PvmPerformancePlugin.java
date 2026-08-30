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
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
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
import net.runelite.api.gameval.VarPlayerID;
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
	// Single-NPC bosses from the RuneLite hiscore list. Raid and minigame entries are on that list too but never match a
	// combat NPC, so they fall out here and are handled separately.
	private static final Set<String> BOSS_NAMES = buildBossNames();
	private static final List<String> BOSS_DISPLAY_NAMES = buildBossDisplayNames();
	// Eating and drinking, the one thing worth telling apart from idle time. Getting it wrong only mislabels the
	// breakdown; the total is measured from the attacks themselves.
	private static final Set<Integer> CONSUME_ANIMATIONS = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList(AnimationID.HUMAN_EAT, AnimationID.HUMAN_KILLERWATT_ELECTRICSHOCK)));
	// How long an eat holds the attack back. Delays stack within a tick, so a shark chased with a karambwan is three plus
	// two.
	private static final int EAT_TICKS = 3;
	// A combo food adds its own delay on top of the food it chases.
	private static final int KARAMBWAN_TICKS = 2;
	// How long a click on an NPC vouches for a projectile aimed at it. A cast is five ticks, so this covers the attack the
	// click ordered and lapses before the next would be thrown without a further click.
	private static final int CLICK_ATTRIBUTION_TICKS = 5;
	// How long a counted projectile is remembered: past any real flight, short enough that the map stays a handful of
	// entries.
	private static final int PROJECTILE_KEY_TICKS = 20;
	// How many of the player's recent tiles a projectile may have been fired from. Long enough to cover a projectile's
	// flight while the player runs.
	private static final int RECENT_TILES = 6;
	// How far a projectile may have left from a tile I am known to have occupied. Two, because that is how far running
	// moves in the tick the shot went out.
	private static final int TILE_SLACK = 2;
	private static final int LOCAL_TILE_SIZE = 128;
	// How far back to look for the loadout that threw a projectile, when a switch has already replaced it. A switch lands
	// within a tick or two.
	private static final int SWITCH_LOOKBACK_TICKS = 4;
	private static final int PRAYER_HISTORY_TICKS = 10;
	// The switch history needs the same reach as the prayer one, and for the same reason: the gap between an attack and
	// its booking differs by style.
	private static final int SWITCH_HISTORY_TICKS = 10;
	// How long an attack may stay in the air before its expected figures are given up on. Past any real flight, so only
	// one that truly never landed is dropped by this rather than by the fight ending.
	private static final int PENDING_SAMPLE_TICKS = 10;
	// How many ticks after an attack goes out it is booked. Measured, not reasoned about: with a flick on the attack tick
	// the mark lands two ticks below the booking tick for a projectile and one for a melee blow.
	private static final int MELEE_BOOKING_LAG = 1;
	private static final int PROJECTILE_BOOKING_LAG = 2;
	// None at all for a cast with no projectile, which is booked from the caster's animation and so is seen on the tick it
	// goes out.
	private static final int CAST_BOOKING_LAG = 0;
	// The animations a projectile-less cast is thrown with. Only these spells reach the animation path. Extend it from the
	// "cast UNKNOWN ANIM" trace.
	private static final Set<Integer> CAST_ANIMATIONS =
		Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(10092)));
	// Casts with no projectile, waiting to be told whether they landed. Usually none or one; two only while a long-range
	// cast is still in the air as the next goes out. A single slot let the new cast overwrite the old one's verdict, and
	// at range the two coincide exactly.
	private final List<PendingCast> castsAwaiting = new ArrayList<>();
	// The ticks an attack of mine dealt damage on, from the hitpoints experience drop, real or fake. See onFakeXpDrop.
	private final Set<Integer> damageDealtTicks = new HashSet<>();
	// Hitpoints experience as last seen, so a level change is not read as damage.
	private int hitpointsXp;


	// The tick the worn items last changed on.
	private static final int CYCLES_PER_TICK = 30;
	// How far either side of its due tick a projectile may land and still be recognised. One tick each way; nothing swings
	// faster than two.
	private static final int FLIGHT_SLACK_TICKS = 1;
	// How long the player must be looking away before a fight they opened by targeting, and have not yet attacked, is
	// closed.
	private static final int LOOK_AWAY_TICKS = 5;
	private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	/** The column names, kept beside the row builders so the two cannot drift. */
	static final String CSV_HEADER =
		"level,raid,raidRun,room,"
		+ "started,npc,npcId,maxHp,killed,damageDealt,damageTaken,attempts,hits,"
		+ "accuracyPct,durationSec,dps,avgHit,expMaxHit,expAccuracyPct,expAvgHit,"
		+ "ticksLost,ticksLostPct,ticksLostEating,"
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
	private double expectedSpecAccuracy = -1;
	private double expectedSpecAverageHit = -1;
	private double expectedDps = -1;
	private SpecialAttack specialAttack;
	// Which target the figures above were computed against, so an attack is only sampled with figures that were actually
	// meant for it.
	private int expectedForNpcId = -1;
	// The NPC last clicked on, and when. A click is unambiguously mine and always precedes the attack it orders, which the
	// interaction is not on the opening attack of a fight.
	private int clickedNpcIndex = -1;
	private int clickedNpcTick = -1;
	// Ticks left before the weapon can attack again; 0 means ready. Counted from the tick the last attack went out, not
	// the tick it was booked: from the booking it measures the gap between bookings, which is a tick longer whenever the
	// style changes from melee to a projectile one.
	private int attackDueTick = Integer.MIN_VALUE;
	// Whether the last attack was proven by a projectile, and so booked two ticks after it went out rather than one. A
	// switch can leave one weapon's attack in flight while another is in hand.
	private boolean lastAttackFromProjectile;
	// The tick my last attack of any style went out on. Apart from attackDueTick, which is only set once booked: this has
	// to be right immediately, being what says the weapon is busy.
	private int lastAttackSeenTick = Integer.MIN_VALUE;
	// The last tick a melee weapon was in hand. A swap is not wielded until the tick after it, so a blow thrown before the
	// swap lands while the client is already showing the new weapon.
	private int lastMeleeTick = Integer.MIN_VALUE;
	// How far behind the attack the observation that proved it was.
	private int attackObservedLag = MELEE_BOOKING_LAG;
	// The last tick a cast was booked from its animation, so the hitsplat does not book the same cast a second time.
	private int lastCastBookedTick = Integer.MIN_VALUE;
	// Expected figures for attacks that have gone out but not yet landed.
	private final List<PendingSample> pendingSamples = new ArrayList<>();
	// The tick a projectile was last taken as mine, so at most one is taken per tick. A player cannot throw two attacks in
	// one.
	private int acceptedProjectileTick = Integer.MIN_VALUE;
	// What getAnimation returns when nothing is playing.
	private static final int IDLE_ANIMATION = -1;
	/** How long after a swap a landing blow may still be the melee weapon's. */
	private static final int MELEE_SWAP_GRACE_TICKS = 1;
	// The tick an attack was last seen going out on, from the events that prove one rather than from what the player looks
	// like, and which event proved it: a projectile is a cast or a shot, a hitsplat a melee blow.
	private int attackObservedTick = -1;
	private boolean attackObservedFromProjectile;
	// Prayer and boost as they stood the instant the attack was proven, not at the end of the tick: a potion drunk after
	// the attack but inside the same tick has already raised the boosted level by the time GameTick runs.
	private boolean attackObservedPotted;
	// The tick the attack left on, which is not always the tick its event surfaces: a projectile's start cycle can be a
	// tick before the plugin hears of it, by which time the player may have switched. Melee has no such gap.
	private int attackOriginTick = -1;
	// The figures as they stood when the attack was seen. The per-tick snapshot is written at GameTick, after any switch
	// made during it; the projectile event fires while the weapon that threw the attack is still in hand.
	private int gearChangedTick = Integer.MIN_VALUE;
	private int attackObservedNpcId = -1;
	private int attackObservedMaxHit;
	private double attackObservedAccuracy = -1;
	private double attackObservedAverageHit = -1;
	private int attackObservedSpecMaxHit;
	private double attackObservedSpecAccuracy = -1;
	private double attackObservedSpecAverageHit = -1;
	// The expected figures as they stood on each of the last few ticks, so an attack can be scored with the loadout that
	// actually threw it. Each row carries the ordinary figures AND the special's, because which of the two an attack needs
	// is not known until it is booked - see sampleExpected.
	private static final int EXP_MAX = 0;
	private static final int EXP_ACCURACY = 1;
	private static final int EXP_AVERAGE = 2;
	private static final int EXP_NPC_ID = 3;
	private static final int EXP_SPEC_MAX = 4;
	private static final int EXP_SPEC_ACCURACY = 5;
	private static final int EXP_SPEC_AVERAGE = 6;
	private static final int EXP_WIDTH = 7;
	private final Map<Integer, double[]> expectedByTick = new HashMap<>();
	// The tick the special attack energy last fell, which is the tick a special went out on. Read back at the booking
	// rather than at the sample: the varp can arrive after the tick's onGameTick has already run. How long after the
	// energy falls an attack may still be that special's.
	private static final int SPEC_BOOKING_WINDOW_TICKS = 1;
	private int specFiredTick = Integer.MIN_VALUE;
	private int specEnergy = -1;
	// Where I stood on each of the last few ticks, in scene coordinates: a projectile names the tile it left, which is
	// where the player was when it was fired, not where they are when the event arrives.
	private final Map<Integer, LocalPoint> recentTiles = new HashMap<>();
	// Consecutive ticks the player has not been interacting with the open fight.
	private int notTargetingTicks;
	// Per NPC: the tick my last hitsplat landed, and whether its burst has been counted as a landed attack. Keyed by index
	// because one attack can land on several NPCs at once, and their bursts must not swallow each other.
	private final Map<Integer, Integer> lastHitsplatTick = new HashMap<>();
	private final Set<Integer> burstLanded = new HashSet<>();
	// NPCs whose current burst has already booked an attack. Claws split one special across two ticks, and each hitsplat
	// was booking its own attack, so one spec counted as two.
	private final Set<Integer> burstBooked = new HashSet<>();
	// Whether the goal prayer was up during each recent tick. A history rather than a slot, because the tick an attack
	// goes out on is not the tick it is booked on, and the gap differs by style.
	private final Map<Integer, Boolean> prayerUpByTick = new HashMap<>();
	// Whether the gear worn on each recent tick was the best available, kept as a history for the same reason the prayer
	// is.
	private final Map<Integer, Boolean> switchedByTick = new HashMap<>();
	// The attack speed in force on each recent tick, so the wait after an attack is the throwing weapon's and not that of
	// whatever replaced it.
	private final Map<Integer, Integer> speedByTick = new HashMap<>();
	// The tick an eat was last seen on, so the pause it causes is credited to it rather than only the one tick its
	// animation shows for.
	private int lastConsumeTick;
	private int consumeDelay;

	// Running totals for the trip, shown instead of the current fight when the overlay is set to whole-trip mode.
	private final SessionTotals session = new SessionTotals(System.currentTimeMillis());


	// The room the fights add up to, and the raid the rooms add up to. Both are views over the fights, so the three widths
	// cannot disagree.
	private Encounter currentEncounter;
	// The last room with something in it, kept so a finished kill stays readable while the next room is still empty. See
	// getDisplayEncounter.
	private Encounter lastFinishedEncounter;
	private Raid currentRaid;
	private RaidType raidType;
	private int raidCounter;

	// The id the target wears now, which is not the id the fight opened on once a boss transforms. Kept from the change
	// events rather than looked up.
	private int targetLiveId = -1;
	private NPC targetNpc;

	// Which Nightmare was last seen, so her totems can be told apart. Both fights use the same totem ids and nothing in a
	// totem says whose it is.
	private String nightmareBoss;

	// Which special Olm is running. Reset when the raid is, since it means nothing outside one.
	private OlmPhase olmPhase;

	// The fight currently in progress, or null between fights.
	private Fight current;
	// The most recently finished fight, kept so the overlay lingers briefly.
	private Fight lastFinished;
	// Persisted history, most-recent first. Copy-on-write so the panel (EDT) can iterate it safely while combat events
	// (client thread) append to it.
	private final List<Fight> history = new CopyOnWriteArrayList<>();

	// My in-flight projectiles, each credited once. Keyed by start cycle, id and target rather than by the Projectile,
	// whose identity the client recycles.
	private final Map<Long, Integer> countedProjectiles = new HashMap<>();
	// npcIndex -> my launched attacks not yet resolved. A splash carries no caster, so it only counts as mine when it
	// resolves one of these.
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
		// Both re-read from scratch: a stale energy reading across a reset would look like a special the moment the bar came
		// back lower than it was.
		specFiredTick = Integer.MIN_VALUE;
		specEnergy = -1;
		lastHitsplatTick.clear();
		burstLanded.clear();
		pendingMineHits.clear();
		drain.clear();
		partyHitpoints.clear();
		nightmareBoss = null;
		targetNpc = null;
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
			// Hitsplats arriving together are one attack: a claw special lands four across two ticks, a dark bow two on one.
			// Grouped by adjacency, and nothing swings faster than two ticks, so no two real attacks merge.
			final int tick = client.getTickCount();
			final Integer seen = lastHitsplatTick.put(npc.getIndex(), tick);
			final boolean newBurst = seen == null || tick - seen > 1;
			if (newBurst)
			{
				burstLanded.remove(npc.getIndex());
				burstBooked.remove(npc.getIndex());
			}
			// The first hitsplat of the burst to actually deal damage is what makes its attack a landed one. A claw special that
			// opens with a zero and then connects still landed once.
			final boolean alreadyLanded = burstLanded.contains(npc.getIndex());
			final boolean landedAttack = hitsplat.getAmount() > 0 && !alreadyLanded;
			if (landedAttack)
			{
				burstLanded.add(npc.getIndex());
			}
			// Whether this hitsplat is one of MY projectiles landing. Consumed here rather than further down because the resolve
			// below depends on it.
			final boolean arrivedFromFlight = consumePending(npc.getIndex());
			// KNOWN-WRONG, deliberately: this resolves on ANY hitsplat of mine, so a melee blow can resolve a projectile still
			// in the air. The melee attack then books its own too, so the tick adds both and the total comes out right by luck -
			// and would not if the target died in between. It is left this way because the obvious fix is worse, and
			// deliberately left that way for now - see "the melee blow that resolved a crossbow" in CODE_NOTES.md. Gating it on
			// arrivedFromFlight was tried and reverted: it made ranged resolution depend on the landing-tick estimate being
			// accurate, and where that missed, the expectation was never counted at all. Magic did not show it because a cast
			// has a SECOND route through recordSplash, which is what proved the cause. Trading a total that is right by luck for
			// one that is silently short is the worse bargain; the real fix is to match a held sample to its own landing tick.
			resolvePendingSample(npc.getIndex());
			// A cast waiting on an answer has it the moment one of my hitsplats DEALS DAMAGE to the NPC it was aimed at; nothing
			// later can unsay that. A zero is deliberately taken as a splash rather than a hit that rolled nothing - not
			// strictly true, but rare, and it makes damage dealt the single question both witnesses answer.
			if (hitsplat.getAmount() > 0)
			{
				for (PendingCast cast : castsAwaiting)
				{
					// The oldest unanswered cast on this NPC is the one that landed: they resolve in the order they were thrown.
					if (cast.npcIndex == npc.getIndex() && !cast.connected)
					{
						cast.connected = true;
						break;
					}
				}
			}
			// A zero is a miss, which is what arms the confliction gauntlets for the next cast against this same enemy.
			if (combatCalc.usesConflictionGauntlets())
			{
				combatCalc.noteMagicResolved(npc.getIndex(), hitsplat.getAmount() == 0);
			}
			current.recordDamageDealt(hitsplat.getAmount(), now, landedAttack);
			if (current.isScored())
			{
				session.recordAttempt(hitsplat.getAmount(), landedAttack, now);
			}
			// A special's drain is worked out from the hit it landed, so it can only be applied here.
			drain.onMyHitsplat(npc, hitsplat.getAmount());
			// Melee, plus a projectile-less cast the animation route did not recognise; everything else is booked from its
			// projectile. That route sees misses and this one cannot, so it books first and this is only here so an unknown cast
			// animation costs the misses rather than everything. Its lag is the hit delay, since it books from the landing and
			// so is exactly as late as the spell was slow.
			final int castLag = magicHitDelay(castDistance(npc));
			final boolean unbookedCast = combatCalc.castLandsWithoutProjectile()
				&& client.getTickCount() - lastCastBookedTick > castLag;
			// Melee as it was when the blow was THROWN, not as the client shows it now. A swap is not wielded until the tick
			// after it, so swinging and then switching to a blowpipe left the melee hitsplat arriving while a ranged weapon was
			// on screen - the attack was never booked and its damage counted against no expectation. Only non-projectile
			// hitsplats reach here, so widening this cannot claim a ranged attack.
			final boolean meleeRecently = combatCalc.isMeleeEquipped()
				|| client.getTickCount() - lastMeleeTick <= MELEE_SWAP_GRACE_TICKS;
			final boolean melee = meleeRecently || unbookedCast;
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
	 * Prints how the loadout was resolved, so a player hitting a wrong figure
	 * can report why it is wrong rather than only that it is. Also logs the
	 * game's own combat option data, which is what identifies a category this
	 * plugin has wrong.
	 */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"loadout".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		final Fight shown = getDisplayFight();
		// The form the target wears now, not the one the fight opened on: a boss that has transformed is described as it
		// stands.
		final int targetId = current != null && !current.isEnded() ? targetLiveId
			: shown == null ? -1 : shown.getTargetId();
		// Mirrored to the log as well as the chatbox. ::loadout is the tool for settling a wrong figure, and a chat message
		// cannot be read back out of client.log afterwards - which cost a round trip the first time it was needed.
		for (String line : combatCalc.describeLoadout(targetId))
		{
			log.debug("::loadout {}", line);
			client.addChatMessage(ChatMessageType.CONSOLE, "PvM Performance", line, null);
		}
	}

	// Catches a spell cast by hand onto an NPC. The autocast varbit only knows about autocasting, so a spell clicked while
	// holding a powered staff would otherwise be reported as the staff's own attack.
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		noteConsumed(event);
		// Any click on an NPC, whatever it ordered: attacking it, casting on it, or firing at it. What matters is that I
		// aimed something at that NPC.
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
		// Magic and ranged fire a projectile before impact. Recognising mine lets a fight begin on my first cast even if it
		// splashes.
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
		// One attack a tick, because a player cannot throw two. The booking was already capped on a tick stamp; what was not
		// is everything this sets up - the figures the attack is scored against and the pending hit that says where its
		// hitsplat came from - which a neighbour's projectile could overwrite. First one wins: there is nothing to choose
		// between them, and taking the first at least keeps the figures and the hit consistent with each other.
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
		// Not always the tick the event surfaces on: the start cycle says when the projectile was fired, which for a slow one
		// can be a tick earlier.
		attackOriginTick = startTick(projectile);
	}

	// Whether a projectile left a tile the player was standing on recently.


	// Close enough to have been fired by me. Not exact, because running covers two tiles a tick while the history records
	// one, so the tile an attack was thrown from is often never in it.


	/**
	 * Identifies one cast: two projectiles sharing a start cycle, an id and a
	 * target are the same cast seen on a later frame.
	 */
	private static long projectileKey(Projectile projectile, NPC target)
	{
		return ((long) projectile.getStartCycle() << 32)
			^ ((long) projectile.getId() << 16)
			^ target.getIndex();
	}

	// Whether this projectile came from me. It names its own source actor, which settles it outright and cannot confuse me
	// with another player on my tile.
	/**
	 * Whether a projectile set off too soon after my last attack to have been
	 * mine. Static so the arithmetic stays under test without a Client.
	 */
	static boolean tooSoonToBeMine(int firedTick, int lastAttackTick, int fastestSpeed)
	{
		return lastAttackTick != Integer.MIN_VALUE && firedTick < lastAttackTick + fastestSpeed;
	}

	/**
	 * The shortest attack speed seen in the last few ticks, for deciding whether a
	 * projectile could have been mine.
	 *
	 * <p>The FASTEST rather than the current one, and deliberately. The live
	 * reading is unusable at exactly the moment this is asked: ProjectileMoved
	 * fires earlier in the frame than the GameTick that records the speed, so the
	 * current tick has no entry yet, and mid-switch the weapon is not readable at
	 * all so it defaults to four. A two tick blowpipe shot was being judged
	 * against that four and rejected as too early to be mine, while its hitsplat
	 * still counted damage - a hit that added damage and no expectation.
	 *
	 * <p>The two errors are not equal. A wrongly REJECTED attack disappears from
	 * the expected side and nothing says so; a wrongly accepted one is a
	 * neighbour's projectile that already had to leave a tile I stood on, at a
	 * target I am fighting, while I was animating. This guard is the weakest of
	 * the four and should behave like it.
	 */
	private int fastestSpeedSeen()
	{
		int fastest = combatCalc.attackSpeedTicks();
		for (int speed : speedByTick.values())
		{
			if (speed > 0 && speed < fastest)
			{
				fastest = speed;
			}
		}
		return Math.max(1, fastest);
	}

	private boolean isProjectileMine(Projectile projectile, Player me, Actor target)
	{
		final Actor source = projectile.getSourceActor();
		if (source != null)
		{
			return source == me;
		}
		// The firing tile is the only thing that identifies a caster, so it is required rather than consulted last: in
		// company, everyone else is casting at what I am fighting too. Compared in scene coordinates, which an instance does
		// not translate, against where I stood on the tick it set off - a projectile is counted once, on first sight.
		if (!firedFromWhereIWas(projectile, me))
		{
			return false;
		}
		// And I have to have been able to fire it. Two players stand on one tile as a matter of course, so position alone
		// cannot separate them - but a weapon on cooldown cannot have thrown anything.
		//
		// Judged on the tick the projectile SET OFF, and against the fastest speed seen lately rather than the live one - see
		// fastestSpeedSeen for why the live reading is unusable here.
		final int firedTick = startTick(projectile);
		if (tooSoonToBeMine(firedTick, lastAttackSeenTick, fastestSpeedSeen()))
		{
			return false;
		}
		// There is deliberately NO animation check here. There was one - a player with nothing playing did not throw this -
		// and it had to go: RuneLite delivers ProjectileMoved BEFORE AnimationChanged within a tick, so on the first attack
		// of a fight, or the first after a switch, getAnimation() is still IDLE when this is asked. Traced at six ticks stale
		// on a shot that was plainly mine, whose 24 damage counted against no expectation. No grace window fixes that: the
		// evidence does not exist yet at the moment it is needed, and widening the window only delays the same rejection.
		//
		// The three checks above carry the work: the projectile left a tile I stood on, my weapon was off cooldown when it
		// set off, and it is aimed at what I am fighting. Do not add a fourth that is unavailable exactly when the first
		// attack needs it. And aimed at something I am engaged with. Held loosely on purpose - any one of the three will do,
		// because getInteracting lapses between a cast leaving and its projectile appearing. The check above does the work.
		final NPC aimedAt = (NPC) target;
		final boolean engaged = (clickedNpcIndex == aimedAt.getIndex()
				&& client.getTickCount() - clickedNpcTick <= CLICK_ATTRIBUTION_TICKS)
			|| me.getInteracting() == target
			|| (current != null && !current.isEnded()
				&& current.getTargetIndex() == aimedAt.getIndex());
		return engaged;
	}

	// Whether the projectile set off from where I stood when it did: the start cycle says which tick that was, so one
	// still in the air after several ticks of running is judged against the right position.
	private boolean firedFromWhereIWas(Projectile projectile, Player me)
	{
		final int ticksAgo = Math.max(0,
			(client.getGameCycle() - projectile.getStartCycle()) / CYCLES_PER_TICK);
		final LocalPoint then = recentTiles.get(client.getTickCount() - ticksAgo);
		final LocalPoint here = me.getLocalLocation();
		if (then != null
			&& Math.abs(projectile.getX1() - then.getX()) <= TILE_SLACK * LOCAL_TILE_SIZE
			&& Math.abs(projectile.getY1() - then.getY()) <= TILE_SLACK * LOCAL_TILE_SIZE)
		{
			return true;
		}
		if (here != null
			&& Math.abs(projectile.getX1() - here.getX()) <= TILE_SLACK * LOCAL_TILE_SIZE
			&& Math.abs(projectile.getY1() - here.getY()) <= TILE_SLACK * LOCAL_TILE_SIZE)
		{
			return true;
		}
		return false;
	}

	/**
	 * Watches for what Olm's phase is made of. Each special leaves something of
	 * its own in the scene, and his head spawning starts a phase.
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

	/**
	 * A hitpoints experience drop, which is the one thing that says an attack of
	 * mine dealt damage on the tick it went out.
	 */
	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (event.getSkill() == Skill.HITPOINTS && event.getXp() > 0)
		{
			noteDamageDealt();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.HITPOINTS)
		{
			return;
		}
		// Only a rise in experience counts: this event also fires for a level change, and hitpoints has one every time the
		// player regenerates or eats.
		final int was = hitpointsXp;
		hitpointsXp = event.getXp();
		if (was > 0 && hitpointsXp > was)
		{
			noteDamageDealt();
		}
	}

	/**
	 * Whether the attack that went out on this tick dealt damage.
	 */
	private boolean dealtDamageOn(int attackTick)
	{
		return damageDealtTicks.contains(attackTick)
			|| damageDealtTicks.contains(attackTick - 1)
			|| damageDealtTicks.contains(attackTick + 1);
	}

	private void noteDamageDealt()
	{
		damageDealtTicks.add(client.getTickCount());
		damageDealtTicks.removeIf(t -> client.getTickCount() - t > PRAYER_HISTORY_TICKS);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		// A splash produces no hitsplat, so this is what sees a missed cast - but only the fast route: it needs the spotanim
		// id to be right and the splash to land on the NPC the fight is open on, neither of which holds for an area spell.
		// resolveCasts answers the same question without either.
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
		if (current.getTargetIndex() != index)
		{
			return;
		}
		// A splash names no caster, so ordinarily it counts as mine only when it resolves a projectile I fired. The ancient
		// area spells have none, and no hitsplat either, so for those this graphic is the only evidence the cast happened -
		// which is why the attack itself is recorded here too.
		final boolean noProjectile = combatCalc.castLandsWithoutProjectile();
		if (!noProjectile && !consumePending(index))
		{
			return;
		}
		recordSplash(index, System.currentTimeMillis());
	}

	/**
	 * A cast of mine that missed, however it was found out. The attack itself is
	 * not booked here - a projectile-less cast is booked from the animation, so
	 * booking it again would double it.
	 */
	private void recordSplash(int index, long now)
	{
		// Nothing is left waiting on this NPC, whichever route got here first: both can fire for one cast, and a splash
		// counted twice would leave attempts running ahead of attacks made.
		castsAwaiting.removeIf(cast -> cast.npcIndex == index);
		resolvePendingSample(index);
		if (combatCalc.usesConflictionGauntlets())
		{
			combatCalc.noteMagicResolved(index, true);
		}
		current.recordSplash(now);
		// Gated as a landed hit is: an unscored NPC spends the tick but contributes no damage, accuracy or efficiency.
		if (current.isScored())
		{
			session.recordAttempt(0, false, now);
		}
	}

	/**
	 * Judges the casts whose damage should have landed by now.
	 */
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
			splashed(cast, now);
		}
	}

	/** A cast of mine that reached the enemy it was aimed at and did nothing. */
	private void splashed(PendingCast cast, long now)
	{
		if (combatCalc.usesConflictionGauntlets())
		{
			combatCalc.noteMagicResolved(cast.npcIndex, true);
		}
		recordSplash(cast.npcIndex, now);
	}

	/**
	 * Recomputes the tick's expected figures. Everything that reads them - the
	 * overlay, the attack-tick sample, ::loadout - takes this one snapshot, so
	 * they cannot disagree within a tick.
	 */
	private void refreshExpected()
	{
		final Fight shown = getDisplayFight();
		specialAttack = combatCalc.specialAttack();
		// for the same reason the prayer is: booking happens a tick or two later, and a trident attack judged at its booking
		// was judged against the whip switched to by then. Cheap: the search is held until the weapon, style, target or
		// carried items change.
		switchedByTick.put(client.getTickCount(),
			!combatCalc.missedGearSwitch(shown != null ? shown.getTargetId() : -1));
		if (client.getTickCount() % 5 == 0)
		{
			switchedByTick.keySet().removeIf(t -> client.getTickCount() - t > SWITCH_HISTORY_TICKS);
		}
		// A sample whose attack should long since have landed is dropped, so a resolve that never comes cannot wait for the
		// next fight's hitsplat.
		pendingSamples.removeIf(sample -> client.getTickCount() - sample.tick > PENDING_SAMPLE_TICKS);
		// The speed of the weapon that will throw an attack on this tick. Asked at the booking it answers for whatever is
		// held by then, and worse: the combat tab lags a swap by a tick, so rapid would be read off the old style and applied
		// to the new weapon's speed.
		speedByTick.put(client.getTickCount(), combatCalc.attackSpeedTicks());
		if (combatCalc.isMeleeEquipped())
		{
			lastMeleeTick = client.getTickCount();
		}
		if (client.getTickCount() % 5 == 0)
		{
			speedByTick.keySet().removeIf(t -> client.getTickCount() - t > SWITCH_HISTORY_TICKS);
		}
		if (shown != null)
		{
			// Pass the target so salve, dragon hunter and the rest can apply.
			expectedMaxHit = combatCalc.maxHit(shown.getTargetId());
			expectedAccuracy = combatCalc.hitChance(shown.getTargetId());
			expectedAverageHit = combatCalc.averageHit(shown.getTargetId());
			expectedDps = combatCalc.expectedDps(shown.getTargetId());
			expectedSpecMaxHit = combatCalc.specialAttackMaxHit(shown.getTargetId());
			expectedSpecAccuracy = combatCalc.specialAttackLandChance(shown.getTargetId());
			expectedSpecAverageHit = combatCalc.specialAttackAverageHit(shown.getTargetId());
			expectedForNpcId = shown.getTargetId();
			final double[] row = new double[EXP_WIDTH];
			row[EXP_MAX] = expectedMaxHit;
			row[EXP_ACCURACY] = expectedAccuracy;
			row[EXP_AVERAGE] = expectedAverageHit;
			row[EXP_NPC_ID] = expectedForNpcId;
			row[EXP_SPEC_MAX] = expectedSpecMaxHit;
			row[EXP_SPEC_ACCURACY] = expectedSpecAccuracy;
			row[EXP_SPEC_AVERAGE] = expectedSpecAverageHit;
			expectedByTick.put(client.getTickCount(), row);
			expectedByTick.keySet().removeIf(t -> client.getTickCount() - t > RECENT_TILES);
		}
		else
		{
			expectedMaxHit = combatCalc.maxHit(-1);
			expectedAccuracy = -1;
			expectedAverageHit = -1;
			expectedDps = -1;
			expectedSpecMaxHit = combatCalc.specialAttackMaxHit(-1);
			expectedSpecAccuracy = -1;
			expectedSpecAverageHit = -1;
			expectedForNpcId = -1;
		}
	}

	/**
	 * Whether the target is dead but still standing. A fight ends on the
	 * despawn, and the death animation runs for several ticks first - long enough
	 * for the next attack to come due against something unattackable. Those ticks
	 * are neither lost nor spent: no attack was possible.
	 */
	private boolean targetIsDying()
	{
		return targetNpc != null && targetNpc.isDead();
	}

	/**
	 * Ticks between an area spell going out and its damage landing. The wiki's
	 * Hit delay formula plus one, measured: the wiki says when the SERVER
	 * applies the hit, this needs when the CLIENT sees the hitsplat. Distance is
	 * Chebyshev to the NPC's south-west tile, barrage's documented exception.
	 */
	static int magicHitDelay(int distance)
	{
		return 2 + (1 + Math.max(0, distance)) / 3;
	}

	static boolean attackOverdue(int now, int dueTick, int bookingLag)
	{
		return now >= dueTick + bookingLag;
	}

	/**
	 * How long a booking might still take: the longer of what the last attack
	 * needs and what the weapon in hand would need.
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

	/**
	 * Whether the special that fired on {@code specFiredTick} is the one that threw
	 * the attack which left on {@code attackOriginTick}.
	 *
	 * <p>NOT an equality. The energy varp does not fall on the tick the attack goes
	 * out: a traced burning claws special put the drop on tick 45 and the hitsplat
	 * on 46, so asking for equality booked it against the ordinary expectation and
	 * threw the special's own figure away. DefenceDrain has carried a window over
	 * the same varp all along, for the same reason.
	 *
	 * <p>One tick, and no wider. The fastest weapon here attacks every two ticks,
	 * so a window of two would start claiming the NEXT attack as a special as well.
	 */
	static boolean specialWentOutFor(int attackOriginTick, int specFiredTick)
	{
		if (specFiredTick == Integer.MIN_VALUE || attackOriginTick < specFiredTick)
		{
			return false;
		}
		return attackOriginTick - specFiredTick <= SPEC_BOOKING_WINDOW_TICKS;
	}

	private void sampleExpected(Fight fight)
	{
		final int targetId = fight.getTargetId();
		int maxHit = expectedMaxHit;
		double accuracy = expectedAccuracy;
		double averageHit = expectedAverageHit;
		int specMaxHit = expectedSpecMaxHit;
		double specLandChance = expectedSpecAccuracy;
		double specAverageHit = expectedSpecAverageHit;
		// Prefer the figures from the tick the attack left on. A slow projectile surfaces a tick late, and scoring it with
		// the loadout held by then credited a shadow's attack to the whip switched to mid-flight.
		final double[] atOrigin = expectedByTick.get(attackOriginTick);
		// A PROJECTILE is scored from the tick it set off, not from the live reading taken when its event surfaced - by then
		// the player may have switched, and the observed figures are the new weapon's. Alternating a blowpipe and a crossbow
		// scored every shot with the OTHER one's average, which is what this ordering is for and what it was not doing: the
		// observed branch below was consulted first and always matched.
		//
		// Melee is left alone. It lands on the tick it is thrown, so the figures taken at its hitsplat are already the right
		// ones.
		final boolean originIsAuthoritative = attackObservedFromProjectile
			&& atOrigin != null && (int) atOrigin[EXP_NPC_ID] == targetId
			&& atOrigin[EXP_ACCURACY] >= 0;
		if (originIsAuthoritative)
		{
			maxHit = (int) atOrigin[EXP_MAX];
			accuracy = atOrigin[EXP_ACCURACY];
			averageHit = atOrigin[EXP_AVERAGE];
			specMaxHit = (int) atOrigin[EXP_SPEC_MAX];
			specLandChance = atOrigin[EXP_SPEC_ACCURACY];
			specAverageHit = atOrigin[EXP_SPEC_AVERAGE];
		}
		else if (attackObservedNpcId == targetId && attackObservedAccuracy >= 0)
		{
			maxHit = attackObservedMaxHit;
			accuracy = attackObservedAccuracy;
			averageHit = attackObservedAverageHit;
			specMaxHit = attackObservedSpecMaxHit;
			specLandChance = attackObservedSpecAccuracy;
			specAverageHit = attackObservedSpecAverageHit;
		}
		else if (atOrigin != null && (int) atOrigin[EXP_NPC_ID] == targetId)
		{
			maxHit = (int) atOrigin[EXP_MAX];
			accuracy = atOrigin[EXP_ACCURACY];
			averageHit = atOrigin[EXP_AVERAGE];
			specMaxHit = (int) atOrigin[EXP_SPEC_MAX];
			specLandChance = atOrigin[EXP_SPEC_ACCURACY];
			specAverageHit = atOrigin[EXP_SPEC_AVERAGE];
		}
		else if (expectedForNpcId != targetId)
		{
			// The opening attack of a fight, whose figures the tick cache cannot hold: the fight was created by the very event
			// being sampled. Asking for them now is a memo lookup on the tick already being served.
			maxHit = combatCalc.maxHit(targetId);
			accuracy = combatCalc.hitChance(targetId);
			averageHit = combatCalc.averageHit(targetId);
			specMaxHit = combatCalc.specialAttackMaxHit(targetId);
			specLandChance = combatCalc.specialAttackLandChance(targetId);
			specAverageHit = combatCalc.specialAttackAverageHit(targetId);
		}
		// Held rather than added, unless the attack has already resolved: an expectation only belongs beside a measured
		// figure if the attack got the chance to deal it, and a cast still in the air when someone else lands the kill reads
		// as underperformance. A melee blow is booked by its own hitsplat, so it has already resolved and goes straight in.
		//
		// One attack can be several rolls, and the measured side counts the ATTACK: three scythe hitsplats land on one tick
		// and book as one landed attack. So the expectation is the chance at least one roll connected, not the sum of them. A
		// special is not the attack the ordinary figures describe: it has its own accuracy, its own per-hitsplat maxima and,
		// on Verzik's first phase, its own exemption from the cap. Decided HERE rather than when the figures were sampled
		// because the energy varp can arrive after that tick's onGameTick has already run, so whether the attack was a
		// special is only reliably known by the time it is booked.
		final boolean special = specialWentOutFor(attackOriginTick, specFiredTick)
			&& specAverageHit >= 0;
		double expectedChances =
			CombatCalc.landChance(accuracy, combatCalc.hitsPerAttack(targetId));
		if (special)
		{
			// Spent: one activation books one attack, so the next attack inside the window cannot claim it too.
			specFiredTick = Integer.MIN_VALUE;
			maxHit = specMaxHit;
			averageHit = specAverageHit;
			expectedChances = specLandChance;
		}
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

	// An attack landed on this NPC, so the oldest thing waiting on it resolved. A splash counts: it landed for nought,
	// which is a real miss.
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

	/**
	 * Throws away what was expected of attacks that never landed, and tells the
	 * fight it lost that many. Both halves matter: an expectation counted against
	 * a measured nought reads as underperformance, and the attack left in the
	 * measured denominator reads as a miss.
	 */
	private void dropPendingSamples()
	{
		if (current != null)
		{
			for (int i = 0; i < pendingSamples.size(); i++)
			{
				current.recordAttackNulled();
				if (current.isScored())
				{
					session.recordAttackNulled();
				}
			}
		}
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
	 * Catches the boss just killed coming back, so the wait between kills can be
	 * timed. Nothing is counted here - the tick is remembered and spent when a
	 * fight opens on that NPC.
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
	}

	/**
	 * Follows the target through a transform: a boss that changes form keeps its
	 * index and changes its id, and the new id is what says whether it can be
	 * fought at all.
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
			// Anything of mine still in the air was nulled by the change, and a cast waiting on damage that will never arrive is
			// not a splash: left in place it resolves as one and arms the gauntlets against an enemy that never dodged anything.
			// Only the pending cast is fooled - the experience is paid whether the damage lands or is nulled.
			castsAwaiting.removeIf(cast -> cast.npcIndex == npc.getIndex());
		}
		// A boss never removed from the scene announces its defeat by changing into a beaten form. That is the kill, and it
		// beats waiting for loot: in a group there may be no drop for this player at all.
		if (EncounterGroup.isDefeated(npc.getId())
			&& EncounterGroup.sameGroup(npc.getId(), current.getTargetId()))
		{
			finalizeFight(true, System.currentTimeMillis());
		}
	}

	/**
	 * A drain landed by another party member, from the special attack counter's
	 * message. It carries only what others did, so it adds to rather than
	 * replaces watching our own energy.
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
	 * The party's hitpoints levels from the party plugin's broadcasts, which is
	 * where the Chambers scaling term comes from: the game gives only the
	 * player's own level, and raiding beside a higher one made it wrong.
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
		// Any animation at all was too generous: being hit plays one and so does eating, and both booked an attack once the
		// weapon was off cooldown. So the cast animation is named - a short list, not a table, because only projectile-less
		// spells come through here and a missing id costs the misses but not the hits.
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
		// Due when the damage would land: hitpoints xp arrives WITH THE DAMAGE, not at the cast. Judged on the cast tick,
		// every cast read as a splash - the gauntlets armed on hits and expected damage counted at the cast.
		final int distance = castDistance(target);
		final int due = client.getTickCount() + magicHitDelay(distance);
		castsAwaiting.add(new PendingCast(target.getIndex(), due));
		recordAttackObserved(false, target.getId(), CAST_BOOKING_LAG);
	}

	/**
	 * Chebyshev distance to the target's south-west tile, which is what these
	 * spells measure. getWorldLocation on an NPC is that tile already.
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
		private boolean connected;

		PendingCast(int npcIndex, int resolveTick)
		{
			this.npcIndex = npcIndex;
			this.resolveTick = resolveTick;
		}
	}

	// What the cast is aimed at: what I am interacting with, or the fight already open, which covers the tick an autocast
	// rolls onto a new target.
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

	/** A boss that never despawns still drops loot, and that is what says it died. */
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
		// A room ends with the kill that closed it, so the next opens a fresh one. Without it a grouped boss kept a whole
		// trip in the room the first kill opened, and the overlay read as a running total with the trip totals off. The loot
		// drop is the one event that says a KILL happened rather than a part of the boss dying - several deaths are one
		// Hueycoatl - and no loot fires inside a raid, so Olm and the Nylocas are untouched. Ended rather than dropped, so
		// the kill stays on the overlay to read.
		if (currentEncounter != null && !currentEncounter.isEnded() && currentEncounter.holds(npc.getId()))
		{
			currentEncounter.end(now);
			// The export rebuilds its rooms from the fights alone, so the fight the kill closed on carries the boundary into the
			// file.
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
		// A boss made of several NPCs does not die on the one being hit: the Hueycoatl is killed at its head while the fight
		// is open on a body segment, so the death despawns an index the fight never heard of. Only a death, and only within
		// the group, so an add dying beside a boss cannot claim it.
		if (npc.isDead() && EncounterGroup.sameGroup(npc.getId(), current.getTargetId()))
		{
			finalizeFight(true, System.currentTimeMillis());
		}
	}

	// Advances the attack cooldown by a tick and books the tick as an attack, as wasted, or as still on cooldown.
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
			// Before anything reads the style: which event proved this attack decides whether a queued cast can be what went
			// out.
			combatCalc.noteAttackKind(attackObservedFromProjectile);
			// Read on the attack tick: this is when the prayers and boosts are the ones the attack actually rolled with.
			final int targetId = current.getTargetId();
			// The tick the attack went out on, which is the lag of whatever proved it and not a fixed offset. The prayer is read
			// back from there, never live: read live it counts one switched on after the swing. The potion is taken as it stood
			// when the attack went out, since drinking after it cannot have boosted it.
			final int attackTick = client.getTickCount() - attackObservedLag;
			final boolean prayed = Boolean.TRUE.equals(prayerUpByTick.get(attackTick));
			final boolean potted = attackObservedPotted;
			// Worked out both ways while the loadout and boost are the ones that threw the attack. Which applies is decided by
			// the prayer, and for a projectile that is not known until it lands.
			final double ifPrayed = combatCalc.actualAverageHit(targetId, true);
			final double ifNot = combatCalc.actualAverageHit(targetId, false);
			final double idealSetup = combatCalc.idealAverageHit(targetId);
			// Read from the tick the attack went out on, never live: by now the gear may be someone else's. Absent means never
			// sampled, which is not evidence of a missed switch, so it reads as clean.
			final boolean switched = !Boolean.FALSE.equals(switchedByTick.get(attackTick));
			// Whether this attack dealt damage, read from the tick it went out, which is where the gauntlets are armed or spent:
			// every style passes through this one place with its attack tick worked out.
			if (combatCalc.usesConflictionGauntlets())
			{
				combatCalc.noteMagicResolved(current.getTargetIndex(), !dealtDamageOn(attackTick));
			}
			// The pause an eat caused is over the moment an attack goes out.
			lastConsumeTick = 0;
			consumeDelay = 0;
			current.recordAttackMade(potted);
			if (current.isScored())
			{
				session.recordAttackMade(potted);
				// Decided here, at the attack. The damage this is measured against was worked out on this tick from this loadout,
				// so the prayer has to be the one that went with it - read at the hitsplat it is measured at the LANDING, ticks
				// after the server scored the attack.
				record(prayed, switched, prayed ? ifPrayed : ifNot, idealSetup);
				// Sampled on the tick the attack went out, so the figures describe the loadout that threw it and one attack takes
				// one sample however it ends.
				sampleExpected(current);
			}
			else
			{
				session.recordTickSpent();
			}
			// Last of all: everything above describes the attack that just went out, the cooldown included. Taken from the tick
			// the attack went out on and with the speed in force then - this is the booking tick, which trails the attack, so
			// reading the speed here reads it off whatever is held now.
			final Integer threwAt = speedByTick.get(attackTick);
			attackDueTick = attackTick
				+ (threwAt != null ? threwAt : combatCalc.attackSpeedTicks());
			lastAttackFromProjectile = attackObservedFromProjectile;
			if (attackObservedFromProjectile)
			{
				// Only a cast can spend a cast. A melee blow landing on the tick the spell was clicked would otherwise consume it,
				// and the cast a tick later then read as the weapon.
				combatCalc.noteAttackThrown();
			}
			// Last: back to describing the loadout rather than one past attack, so the overlay goes on showing a queued cast
			// between attacks.
			combatCalc.noteAttackKind(true);
			return;
		}
		// The trip totals take the same ticks as they happen, so the share shown in whole-trip mode moves during a fight
		// rather than only at its end.
		if (EncounterGroup.isUnattackable(targetLiveId) || targetIsDying())
		{
			// Between phases, charging, or dead but standing. Neither lost nor spent: no attack was possible, so counting it
			// either way would move a figure that measures choices.
			return;
		}
		final boolean counts = current.isAttacking();
		// The due tick is in attack ticks, and a booking arrives a tick or two later, so the wait is measured against the
		// tick that booking would land on. Which lag applies is decided by the weapon in hand NOW, since it is the next
		// attack's booking being waited for: holding a trident to the whip's one-tick lag booked a lost tick on every switch
		// into a projectile.
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

	// Notices food and drink going down, from the click rather than the animation.
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
			// Nothing was clicked that this knows about, a wine, a cake, an unfamiliar option. The animation is the weaker
			// signal but it is better than calling the tick idle.
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

	// How far behind the attack this observation is depends on what proved it, and there are three answers rather than
	// two: a melee blow lands the tick it is thrown, a projectile surfaces two ticks later, and a cast with no projectile
	// lands three.
	private void recordAttackObserved(boolean fromProjectile, int npcId, int bookingLag)
	{
		attackObservedLag = bookingLag;
		// Recorded here rather than at the booking, which is a tick or two later: this is what says a weapon is on cooldown,
		// and it has to be true from the moment the attack goes out.
		lastAttackSeenTick = client.getTickCount();
		attackObservedTick = client.getTickCount();
		attackObservedFromProjectile = fromProjectile;
		attackOriginTick = client.getTickCount();
		attackObservedNpcId = npcId;
		attackObservedMaxHit = combatCalc.maxHit(npcId);
		attackObservedAccuracy = combatCalc.hitChance(npcId);
		attackObservedAverageHit = combatCalc.averageHit(npcId);
		attackObservedSpecMaxHit = combatCalc.specialAttackMaxHit(npcId);
		attackObservedSpecAccuracy = combatCalc.specialAttackLandChance(npcId);
		attackObservedSpecAverageHit = combatCalc.specialAttackAverageHit(npcId);
		// A weapon swapped on a tick is not wielded until the next one, so an attack thrown on the tick of a switch used what
		// was held before it. The client shows the new weapon immediately, which is why the figures have to come from the
		// tick before the change rather than from now. This holds for every switch, not only one that lands on a melee
		// weapon: magic to ranged and staff to staff are the same case.
		if (gearChangedTick >= client.getTickCount() - 1)
		{
			for (int tick = gearChangedTick - 1;
				tick >= gearChangedTick - SWITCH_LOOKBACK_TICKS; tick--)
			{
				final double[] earlier = expectedByTick.get(tick);
				if (earlier != null && (int) earlier[EXP_NPC_ID] == npcId
					&& earlier[EXP_ACCURACY] >= 0)
				{
					attackObservedMaxHit = (int) earlier[EXP_MAX];
					attackObservedAccuracy = earlier[EXP_ACCURACY];
					attackObservedAverageHit = earlier[EXP_AVERAGE];
					attackObservedSpecMaxHit = (int) earlier[EXP_SPEC_MAX];
					attackObservedSpecAccuracy = earlier[EXP_SPEC_ACCURACY];
					attackObservedSpecAverageHit = earlier[EXP_SPEC_AVERAGE];
					break;
				}
			}
		}
		attackObservedPotted = combatCalc.isPotted();
	}

	// Notices the intended prayer going up part-way through a tick, so a prayer switched on and attacked with on the same
	// tick counts.
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
			// What could have been switched into may have changed. Eating and every switch fire this too, which is why nothing
			// is counted here - the next attack decides whether anything wearable was gained or lost.
			combatCalc.invalidateInventory();
		}
	}

	/**
	 * Mark of Darkness, from the game's own messages: no varbit carries it, and
	 * the one named for it is a buff bar display toggle. The messages are exact,
	 * which beats computing a duration and hoping the formula holds.
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
		// Watched here because the energy falling is what says a special went out, and the player's own specials are not
		// carried by the party message.
		drain.onEnergyChanged();
		noteSpecialEnergy();
	}

	/**
	 * Marks the tick a special went out on, which is the tick the energy falls.
	 *
	 * <p>Tracked here rather than taken from {@link DefenceDrain}, which watches
	 * the same varp: that only arms for the handful of weapons whose special
	 * drains defence, and every special needs booking against its own expected
	 * damage rather than the ordinary attack's.
	 */
	private void noteSpecialEnergy()
	{
		final int energy = client.getVarpValue(VarPlayerID.SA_ENERGY);
		final int previous = specEnergy;
		specEnergy = energy;
		// A first reading cannot be compared against anything, and energy that went up is regeneration rather than a special.
		if (previous >= 0 && energy < previous)
		{
			specFiredTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		RaidScaling.setTombsRaidLevel(gearBonuses.tombsRaidLevel());
		trackRaid(System.currentTimeMillis());
		startFightOnTarget();
		// Both before the attack is booked. The expected figures must describe the loadout that threw it, and the cast
		// verdict must be on screen the moment the miss is known - safe ahead of the booking because a cast is never judged
		// on its own tick.
		resolveCasts(System.currentTimeMillis());
		refreshExpected();
		// Sampled BEFORE the booking reads it back: a cast booked from its animation has no lag, so it asks for this tick's
		// answer, and written afterwards there was none. hasOffensivePrayer reads the server's copy.
		final boolean upThisTick = combatCalc.hasOffensivePrayer();
		prayerUpByTick.put(client.getTickCount(), upThisTick);
		if (client.getTickCount() % 5 == 0)
		{
			prayerUpByTick.keySet().removeIf(t -> client.getTickCount() - t > PRAYER_HISTORY_TICKS);
		}
		trackAttackCooldown();

		// For ranged attacks where the player is right next to the target, the hitsplat can arrive on the exact same tick the
		// projectile sets off. If it did, the hitsplat's resolvePendingSample call fired empty because trackAttackCooldown
		// hadn't created the sample yet. Resolving again here catches that sample.
		for (java.util.Map.Entry<Integer, Integer> entry : lastHitsplatTick.entrySet())
		{
			if (entry.getValue() == client.getTickCount())
			{
				resolvePendingSample(entry.getKey());
			}
		}

		final Player me = client.getLocalPlayer();
		if (me != null && me.getLocalLocation() != null)
		{
			recentTiles.put(client.getTickCount(), me.getLocalLocation());
			if (client.getTickCount() % 5 == 0)
			{
				recentTiles.keySet().removeIf(t -> client.getTickCount() - t > RECENT_TILES);
			}
		}

		// Drop projectiles that have landed so the set doesn't retain them.
		countedProjectiles.values().removeIf(seen -> client.getTickCount() - seen > PROJECTILE_KEY_TICKS);
		// Bursts are over long before this; the entries are dropped so the two collections cannot grow with every NPC ever
		// hit.
		lastHitsplatTick.values().removeIf(seen -> client.getTickCount() - seen > PROJECTILE_KEY_TICKS);
		burstLanded.retainAll(lastHitsplatTick.keySet());
		burstBooked.retainAll(lastHitsplatTick.keySet());

		if (current != null && !current.isEnded())
		{
			if (current.getAttempts() == 0)
			{
				// Opened by targeting and not yet fought. The idle timeout cannot judge it - that would expire while the player is
				// still walking into range - so it ends when they look away, and not the instant it blinks, because the interaction
				// lapses between a cast and its projectile.
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
		// Tags stripped: some names carry colour markup and it was reaching the CSV verbatim, "<col=00ffff>Rubble</col>" and
		// all.
		current = new Fight(Text.removeTags(npc.getName()), npc.getId(), npc.getIndex(),
			maxHp == null ? -1 : maxHp, now,
			raidType, raidCounter);
		targetLiveId = npc.getId();
		targetNpc = npc;
		combatCalc.setTarget(npc);
		labelOlmPhase(current);
		labelNightmareTotem(current, npc.getId());
		openEncounterFor(current, now);
	}

	// Opens a fight as soon as the player targets something attackable, so the overlay is up while the first attack is
	// still in the air.
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

	// Files a fight under the room it belongs to, continuing the room already open when it is the same one.
	private void openEncounterFor(Fight fight, long now)
	{
		// Only a grouped NPC continues a room. Without that a second Vorkath would join the first - same name, same room -
		// and the overlay would quietly become a running total of the trip.
		final String name = fight.encounterName();
		// An ended room takes nothing more. It is left on show so the kill can be read, and this is what stops the fight
		// after it being added to it.
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
	 * Names an Olm fight by the special its phase is running, so the three can
	 * be compared. A phase whose special has not shown yet keeps the plain name.
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
	 * Files a totem under whichever Nightmare is being fought: the two share
	 * their totem ids, and the boss is always met before its totems.
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
		// Anything still in the air never landed. Counting what it was expected to deal against the nought it dealt made a
		// group kill read as underperformance.
		dropPendingSamples();
		// A cast still in the air when the target died is not a splash, and the fight is over either way. The charge goes
		// with it because the server reuses NPC indices, so a respawn can wear the one it was held against.
		castsAwaiting.clear();
		combatCalc.forgetConflictionCharge();
		pendingMineHits.clear();
		// Nothing is being fought, so no drain should be read against anything.
		combatCalc.setTarget(null);
		targetNpc = null;
		targetLiveId = -1;
		if (current.getAttempts() == 0)
		{
			// Opened because the player looked at something and then didn't fight it. Nothing happened, so nothing is recorded.
			current = null;
			return;
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

	// The tick a projectile was fired on, from the cycle it started. Cycles run at 20ms and ticks at 600, so thirty of the
	// former make one of the latter.
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

	// Whether a hitsplat landing now is one of my attacks arriving from flight rather than a fresh melee blow. Matched on
	// when the attack was due rather than counted, because a count cannot expire: a projectile that never produces a
	// hitsplat would leave it high forever.
	private boolean consumePending(int npcIndex)
	{
		final List<Integer> due = pendingMineHits.get(npcIndex);
		if (due == null)
		{
			return false;
		}
		final int now = client.getTickCount();
		// Anything that should have landed and did not is dropped rather than left to be matched against something unrelated
		// later.
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

	/** The room the overlay should display, or null if nothing has been fought. */
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

	/** Expected damage a second for the loadout in hand; see CombatCalc.expectedDps. */
	double getExpectedDps()
	{
		return expectedDps;
	}

	/** Max total damage of one special attack activation (0 if the weapon has none). */
		int getExpectedSpecMaxHit()
	{
		return expectedSpecMaxHit;
	}

	double getExpectedSpecAccuracy()
	{
		return expectedSpecAccuracy;
	}

	double getExpectedSpecAverageHit()
	{
		return expectedSpecAverageHit;
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
		// Oldest first, so "does this fight continue the room before it" can be asked. The history is most-recent-first for
		// the panel, so the result is turned back round at the end.
		final Set<String> roomOpen = new HashSet<>();
		for (int i = history.size() - 1; i >= 0; i--)
		{
			final Fight fight = history.get(i);
			if (bossOnly && !isBoss(fight))
			{
				continue;
			}
			// Keyed on the room rather than the NPC, so a grouped room reads as one line instead of one per add.
			final String name = fight.encounterName();
			// A fight starts a room only if nothing has one open under that name. An ungrouped NPC never continues anything, and
			// the fight the kill closed on ends its room - which is what makes one Hueycoatl count once.
			final boolean startsRoom = fight.getGroupName() == null || !roomOpen.contains(name);
			byName.computeIfAbsent(name, NpcStats::new).add(fight, startsRoom);
			if (fight.getGroupName() == null || fight.isClosedRoom())
			{
				roomOpen.remove(name);
			}
			else
			{
				roomOpen.add(name);
			}
		}
		final List<NpcStats> stats = new ArrayList<>(byName.values());
		Collections.reverse(stats);
		return stats;
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

	// Each fight, then its room, then the raid the rooms belong to: the same numbers at three widths, told apart by the
	// level column. Phases are written whole rather than summed away, so which phase a kill went wrong on survives.
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
			// A room also ends at the fight that closed it, so a trip's kills are one room row each. Only that fight breaks it:
			// a boss made of several NPCs sets targetDied on the way down and would otherwise split a kill.
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

	// A room row only earns its place when the room holds more than one fight: outside a raid it holds exactly one, so
	// writing it repeated the fight row.
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
			+ String.format("%s,,,,%b,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,,%s,%s,%d,%s,%d,%d,%d,%d,%d,%s%n",
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
			+ String.format("%s,,,,,%d,,%d,%d,%.1f,%.1f,%.2f,%.2f,,%s,%s,%d,%s,%d,%d,%d,%d,%d,%s%n",
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
		// "fight" rather than "phase": a phase is what it is inside a raid, but outside one it is a single kill, and the
		// label read as jargon.
		return csvScope("fight", raidName, raidRun, fight.encounterName()) + csvRow(fight);
	}

	static String csvRow(Fight fight)
	{
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(fight.getStartMillis()), ZoneId.systemDefault()));
		return String.format("%s,\"%s\",%d,%d,%b,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%s,%s,%s,%d,%s,%d,%d,%d,%d,%d,%s%n",
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
			fight.getAttacksMade(),
			fight.getAttacksPrayed(),
			fight.getAttacksPotted(),
			fight.getAttacksSwitched(),
			csvExpected(fight.efficiency() * 100, 1));
	}

	/**
	 * Expected figures are left blank rather than written as a number when the
	 * model had nothing to say, so a reader can tell "unknown" from a real zero.
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
