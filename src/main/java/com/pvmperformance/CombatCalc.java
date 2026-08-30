package com.pvmperformance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.game.NPCManager;
import net.runelite.client.util.Text;

/**
 * Max hit, hit chance and average damage for the player's live loadout against a
 * given NPC. Anything needing the target's stats returns -1 when they are not
 * known. Formulae and prayer multipliers follow the LlemonDuck dps-calculator
 * (BSD-2), (c) Paul Norton.
 */
@Slf4j
class CombatCalc
{
	enum Style
	{
		MELEE,
		RANGED,
		MAGIC
	}

	private final Client client;
	private final ItemManager itemManager;
	private final MonsterStatsProvider monsters;
	private final GearBonusCalc gearBonuses;
	private final PvmPerformanceConfig config;
	private final NPCManager npcManager;
	private final DefenceDrain drain;
	private final PartyHitpoints partyHitpoints;

	// A manual cast counts for a few ticks past the click, so it survives the gap between casts while the player keeps
	// going.
	/** A game tick, in seconds. */
	private static final double TICK_SECONDS = 0.6;

	private static final int MANUAL_CAST_TICKS = 8;
	// Clicks that may be outstanding at once. Two covers a click made while the previous cast is still in the air; the
	// third is slack.
	private static final int MAX_PENDING_CASTS = 3;
	// Slot index back to the slot, for reading the slot an inventory item goes in off its equipment stats. Not every index
	// is a real slot.
	/**
	 * The claws' hitsplat shape when the first accuracy roll connects: a max
	 * hit, then half, then a quarter twice, which is the doubled max the wiki
	 * describes spread over four hitsplats.
	 */
	private static final double[] CLAW_SHAPE = {1.0, 0.5, 0.25, 0.25};
	/**
	 * The claws' fourth-roll outcome, which is the one that does not follow the
	 * shape above: 125% of the max concentrated onto a single hitsplat.
	 */
	private static final double[] CLAW_LAST_RESORT = {1.25};

	/**
	 * The crimson kisten's four accuracy rolls, the floor of each outcome's
	 * damage as a share of the max hit, and the width of every band.
	 */
	private static final int KISTEN_ROLLS = 4;
	private static final double[] KISTEN_FLOORS = {0.70, 0.90, 1.10, 1.30};
	private static final double KISTEN_BAND = 0.40;

	/**
	 * Burning barrage, by which accuracy roll connected first. The total rolls
	 * between the floor and the ceiling as a share of the normal max hit, and is
	 * split over three hitsplats by the shape.
	 */
	private static final double[] BURNING_CLAW_FLOORS = {0.75, 0.50, 0.25};
	private static final double[] BURNING_CLAW_CEILINGS = {1.75, 1.50, 1.25};
	private static final double[][] BURNING_CLAW_SHAPES = {
		{0.25, 0.25, 0.50},
		{0.50, 0.50, 0.00},
		{0.00, 0.00, 1.00},
	};

	/**
	 * Verzik's first phase, in all three difficulties. The seated form counts:
	 * it is the same phase and the same cap, and the fight opens on it.
	 */
	private static final Set<Integer> VERZIK_FIRST_PHASE = Collections.unmodifiableSet(
		new HashSet<>(Arrays.asList(
			NpcID.VERZIK_INITIAL, NpcID.VERZIK_PHASE1,
			NpcID.VERZIK_INITIAL_STORY, NpcID.VERZIK_PHASE1_STORY,
			NpcID.VERZIK_INITIAL_HARD, NpcID.VERZIK_PHASE1_HARD,
			NpcID.VERZIK_INITIAL_BASE, NpcID.VERZIK_INITIAL_QUICKSTART,
			NpcID.VERZIK_INITIAL_HARD_BASE, NpcID.VERZIK_INITIAL_HARD_QUICKSTART)));

	private static final Map<Integer, EquipmentInventorySlot> SLOT_BY_INDEX = new HashMap<>();

	static
	{
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			SLOT_BY_INDEX.put(slot.getSlotIdx(), slot);
		}
	}

	// The weapon the combat tab heading was last seen describing, so a heading that has not caught up with a swap can be
	// told from one that has.
	private int headingWeapon = Integer.MIN_VALUE;
	private String headingText;
	private Spell manualCastSpell;
	private int manualCastTick = Integer.MIN_VALUE;
	// Clicks made but not yet seen leaving as a cast.
	private int manualCastsPending;
	// Whether the attack currently being described was proven by a hitsplat.
	private boolean meleeProvenAttack;
	// Which setup the level and prayer reads answer for. REAL is what the player had; IDEAL is the intended prayer at full
	// boost; PRAYER_HELD is the intended prayer at the real boost, for a prayer flicked within the tick.
	private static final int REAL = 0;
	private static final int IDEAL = 1;
	private static final int PRAYER_HELD = 2;
	private int mode = REAL;
	// The index of the NPC the figures are being computed against, so the defence read can account for what has been
	// drained off it.
	private int targetIndex = -1;
	// The NPC being fought, for the health bar the ruby bolt is a share of.
	private NPC targetNpc;
	// The enemy a miss has armed the gauntlets against, or -1. Per enemy because the effect is spent on the next attack
	// against that same one.
	private final ConflictionCharge charge = new ConflictionCharge();
	private BlowpipeDart cachedDart;
	private ItemEquipmentStats cachedDartStats;

	@Inject
	CombatCalc(Client client, ItemManager itemManager, MonsterStatsProvider monsters,
		GearBonusCalc gearBonuses, PvmPerformanceConfig config, NPCManager npcManager,
		DefenceDrain drain, PartyHitpoints partyHitpoints)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.monsters = monsters;
		this.gearBonuses = gearBonuses;
		this.config = config;
		this.npcManager = npcManager;
		this.drain = drain;
		this.partyHitpoints = partyHitpoints;
	}

	/** The gear multipliers for the current loadout against this target. */
	private GearBonus computeGearBonus(int npcId)
	{
		return gearBonuses.compute(gear(), attackStyle(), monsters.get(npcId), activeSpell(),
			!config.slayerHelmetOffTask());
	}

	// Held for the tick. Equipment, varbits and the combat option cannot change within one, and an attack tick asks for
	// the gear multipliers a dozen times. The ideal figures share it safely: they change levels, not gear.
	private int memoTick = Integer.MIN_VALUE;
	private AttackStyle memoStyle;
	private String memoWeaponName;
	private boolean memoWeaponNameSet;
	private final int[] memoAttackBonus = new int[AttackType.values().length];
	private final boolean[] memoAttackBonusSet = new boolean[AttackType.values().length];
	private final int[] memoEquipmentBonus = new int[2];
	private final boolean[] memoEquipmentBonusSet = new boolean[2];
	private final int[] memoBaseMaxHit = new int[3];
	private final boolean[] memoBaseMaxHitSet = new boolean[3];
	private int memoGearNpc = Integer.MIN_VALUE;
	private GearBonus memoGear;
	private Loadout memoLoadout;
	// The gear search's answer, which outlives the tick memo: the answer names item ids, and a switch only moves one
	// between the hand and the bag, so it keeps describing what the player should be wearing while they wear it.
	private int memoBestGearNpc = Integer.MIN_VALUE;
	private Loadout memoBestGear;
	// What the answer was worked out under: the weapon because it is pinned, the style because which attack bonus counts
	// turns on it, and the signature because the search can only offer what is there.
	private int memoBestGearWeapon = Integer.MIN_VALUE;
	private AttackStyle memoBestGearStyle;
	private long memoBestGearAvailable = Long.MIN_VALUE;
	// Every equippable item on the player, worn and carried together, and whether a container has said anything moved
	// since it was last counted.
	private long availableSignature;
	private boolean availableStale = true;

	/**
	 * Drops everything held once its tick has passed, and when the worn items
	 * change: a weapon swapped part way through a tick left every figure already
	 * worked out answering for the weapon before it. The gear search's answer is
	 * NOT dropped here - see bestGear.
	 */
	void invalidateGear()
	{
		memoTick = Integer.MIN_VALUE;
		availableStale = true;
	}

	/**
	 * Something moved in the inventory. Only a note that the count is worth
	 * taking again - it waits until the search asks, because this fires on every
	 * bite and sip.
	 */
	void invalidateInventory()
	{
		availableStale = true;
	}

	/**
	 * How many of what the player could put on, worn and carried together. A
	 * switch leaves it alone, which is the point: an item on the arm is one no
	 * longer in the bag.
	 */
	private long availableEquippableSignature()
	{
		if (!availableStale)
		{
			return availableSignature;
		}
		availableStale = false;
		// Order-independent, so items shuffled between slots or containers are not a change. Cheap next to the search it
		// avoids.
		long signature = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			final int id = gear().id(slot);
			if (id >= 0)
			{
				signature += (long) id * 2654435761L;
			}
		}
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				// Food and potions fall out here, and a dose drunk changes the item's id, so counting ids alone would throw the
				// answer away.
				if (equipmentStats(item.getId()) != null)
				{
					signature += (long) item.getId() * 2654435761L;
				}
			}
		}
		availableSignature = signature;
		return signature;
	}

	private void expireMemo()
	{
		final int tick = client.getTickCount();
		if (tick == memoTick)
		{
			return;
		}
		memoTick = tick;
		memoStyle = null;
		memoWeaponNameSet = false;
		memoGearNpc = Integer.MIN_VALUE;
		memoGear = null;
		memoLoadout = null;
		Arrays.fill(memoAttackBonusSet, false);
		Arrays.fill(memoEquipmentBonusSet, false);
		Arrays.fill(memoBaseMaxHitSet, false);
	}

	// The gear every figure is worked out for. One object rather than a container read per question, so asking what
	// another loadout would do is the same calculation with a different argument.
	private Loadout gear()
	{
		expireMemo();
		if (memoLoadout == null)
		{
			memoLoadout = Loadout.worn(itemManager, client.getItemContainer(InventoryID.WORN));
		}
		return memoLoadout;
	}

	// Points every figure at a different loadout. Everything derived from the gear goes with it, or the swap is half
	// applied - the bonuses and the max hit are memoised per tick.
	private void useLoadout(Loadout gear)
	{
		memoLoadout = gear;
		memoStyle = null;
		memoWeaponNameSet = false;
		memoGearNpc = Integer.MIN_VALUE;
		memoGear = null;
		Arrays.fill(memoAttackBonusSet, false);
		Arrays.fill(memoEquipmentBonusSet, false);
		Arrays.fill(memoBaseMaxHitSet, false);
	}

	// Expected damage for a loadout that is not being worn. Restores what was there afterwards, memo included, so a search
	// leaves no trace.
	private double averageHitWith(Loadout gear, int npcId, int as)
	{
		final Loadout worn = memoLoadout;
		final int was = mode;
		try
		{
			useLoadout(gear);
			mode = as;
			return averageHit(npcId);
		}
		finally
		{
			mode = was;
			useLoadout(worn);
		}
	}

	private AttackStyle attackStyle()
	{
		expireMemo();
		if (memoStyle == null)
		{
			memoStyle = resolveAttackStyle();
		}
		return memoStyle;
	}

	private String weaponName()
	{
		expireMemo();
		if (!memoWeaponNameSet)
		{
			memoWeaponName = computeWeaponName();
			memoWeaponNameSet = true;
		}
		return memoWeaponName;
	}

	private int attackBonus(AttackType type)
	{
		expireMemo();
		final int slot = type.ordinal();
		if (!memoAttackBonusSet[slot])
		{
			memoAttackBonus[slot] = computeAttackBonus(type);
			memoAttackBonusSet[slot] = true;
		}
		return memoAttackBonus[slot];
	}

	private int equipmentBonus(boolean melee)
	{
		expireMemo();
		final int slot = melee ? 0 : 1;
		if (!memoEquipmentBonusSet[slot])
		{
			memoEquipmentBonus[slot] = computeEquipmentBonus(melee);
			memoEquipmentBonusSet[slot] = true;
		}
		return memoEquipmentBonus[slot];
	}

	/**
	 * Held per mode rather than once: the levels and prayers behind it are
	 * substituted while working out what an attack should have been.
	 */
	private int baseMaxHit()
	{
		expireMemo();
		final int slot = mode;
		if (!memoBaseMaxHitSet[slot])
		{
			memoBaseMaxHit[slot] = computeBaseMaxHit();
			memoBaseMaxHitSet[slot] = true;
		}
		return memoBaseMaxHit[slot];
	}

	private GearBonus gearBonus(int npcId)
	{
		expireMemo();
		if (memoGear == null || memoGearNpc != npcId)
		{
			memoGear = computeGearBonus(npcId);
			memoGearNpc = npcId;
		}
		return memoGear;
	}

	/** Expected hit chance (0..1), or -1 with no data. */
	double hitChance(int npcId)
	{
		return hitChance(npcId, null);
	}

	/**
	 * Hit chance with a special attack's roll modifier folded in.
	 *
	 * <p>The modifier multiplies the ATTACK ROLL, not the finished chance, and is
	 * applied to the FINISHED roll with integer arithmetic rather than folded in
	 * with the gear multiplier - that is the order the game works in, and it is
	 * exact where a double multiply can land a point either side. Scaling the
	 * chance instead would put a godsword above 100% against anything it already
	 * hits two thirds of the time.
	 */
	double hitChance(int npcId, SpecialAttack spec)
	{
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		if (npc == null)
		{
			return -1;
		}
		final AttackStyle style = attackStyle();
		final AttackType type = style.getAttackType();
		final double gear = gearBonus(npcId).getAccuracy();
		if (type.isMelee())
		{
			return meleeHitChance(style, type, npc, gear, spec);
		}
		if (type == AttackType.RANGED)
		{
			return rangedHitChance(style, npc, gear, spec);
		}
		return magicHitChance(style, npc, gear, npcId, spec);
	}

	/** How much of a hit the target keeps, for the few that shrug most of it off. */
	private double mitigation(int npcId)
	{
		return RaidScaling.damageTaken(npcId, attackStyle().getAttackType())
			* corporealBeastMitigation(npcId);
	}

	/**
	 * The Corporeal Beast halves everything but a corpbane weapon on stab.
	 *
	 * <p>Wiki: "50% damage reduction against any melee and ranged weapon that is
	 * not a Corpbane weapon. These weapons must also be on the stab attack
	 * style." Magic is exempt outright, as are recoil and Retribution, neither
	 * of which this counts as the player's attack anyway.
	 *
	 * <p>Ranged therefore always halves: there is no ranged weapon that attacks
	 * on stab, so the exemption cannot be reached from it. That is the rule as
	 * written rather than an assumption about ranged.
	 */
	private double corporealBeastMitigation(int npcId)
	{
		if (npcId != NpcID.CORP_BEAST)
		{
			return 1.0;
		}
		final AttackType type = attackStyle().getAttackType();
		if (type == AttackType.MAGIC)
		{
			return 1.0;
		}
		return type == AttackType.STAB && isCorpbaneWeapon(weaponName()) ? 1.0 : 0.5;
	}

	/**
	 * Wiki (Corpbane weapons): every spear, every halberd, Osmumten's fang, the
	 * thunder khopesh and King's barrage. Matched on name because the spears and
	 * halberds run to thirty-odd items across every metal, and a list of ids
	 * would be a table where a missing entry silently halves the figure.
	 */
	static boolean isCorpbaneWeapon(String weaponName)
	{
		if (weaponName == null)
		{
			return false;
		}
		final String name = weaponName.toLowerCase(Locale.ROOT);
		return name.contains("spear")
			|| name.contains("halberd")
			|| name.contains("osmumten's fang")
			|| name.contains("thunder khopesh");
	}

	double averageHit(int npcId)
	{
		final double accuracy = hitChance(npcId);
		// Averages, not best cases: a keris crit or an ahrim's proc raises the max hit but only lifts sustained damage by a
		// few percent.
		final double averageMax = (baseMaxHit() * gearBonus(npcId).getExpectedDamage()
			+ colossalBladeBonus(npcId) + elementalWeaknessBonus(npcId)) * mitigation(npcId);
		if (accuracy < 0 || averageMax <= 0)
		{
			return -1;
		}
		final int cap = damageCap(npcId);
		// A scythe swing is several hits, each rolling its own accuracy and damage, so one attack is expected to deal their
		// sum. Each max is half the one before ROUNDED DOWN, which is why this walks them rather than multiplying by 1.75: a
		// 51 max is 51, 25, 12, which is 88 and not 89.25.
		double ordinary = 0;
		double max = averageMax;
		for (int hit = 0; hit < hitsPerAttack(npcId); hit++)
		{
			ordinary += cap == Integer.MAX_VALUE
				? accuracy * (max / 2.0)
				: accuracy * cappedAverage((int) max, cap);
			max = Math.floor(max / 2.0);
		}

		final EnchantedBolt bolt = loadedBolt(npcId);
		if (bolt == null)
		{
			return ordinary;
		}
		final double procChance = bolt.chance(hasKandarinHardDiary());
		switch (bolt)
		{
			case RUBY:
				// Fires regardless of the accuracy roll and replaces the hit entirely, so the two outcomes are weighted rather than
				// scaled.
				return procChance * EnchantedBolt.rubyDamage(targetCurrentHp(npcId))
					+ (1.0 - procChance) * ordinary;
			case DIAMOND:
				// Also ignores accuracy, but rolls damage normally against a raised max, so a proc always lands.
				return procChance * (1.15 * averageMax / 2.0) + (1.0 - procChance) * ordinary;
			default:
				// Onyx leeches from damage actually dealt, so it needs a hit first.
				return ordinary * (1.0 + 0.20 * procChance);
		}
	}

	/**
	 * The enchanted bolt loaded for this attack, or null when none applies -
	 * not ranged, not a crossbow, or onyx against undead.
	 */
	private EnchantedBolt loadedBolt(int npcId)
	{
		final AttackStyle style = attackStyle();
		if (style.getAttackType() != AttackType.RANGED)
		{
			return null;
		}
		final WeaponCategory category = weaponCategory();
		if (category != WeaponCategory.CROSSBOW)
		{
			return null;
		}
		final int ammo = equippedItemId(EquipmentInventorySlot.AMMO);
		final EnchantedBolt bolt = ammo < 0
			? null : EnchantedBolt.forAmmoName(itemManager.getItemComposition(ammo).getName());
		if (bolt == EnchantedBolt.ONYX)
		{
			final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
			if (npc != null && npc.hasAttribute("undead"))
			{
				return null;
			}
		}
		return bolt;
	}

	private boolean hasKandarinHardDiary()
	{
		return client.getVarbitValue(VarbitID.KANDARIN_DIARY_HARD_COMPLETE) == 1;
	}

	/**
	 * The target's health now, which the ruby bolt effect is a share of. Falls
	 * back to full health whenever it cannot be read for THIS target.
	 *
	 * <p>The health bar has to be confirmed to belong to the npc being asked
	 * about. It is read off whoever the player is interacting with, which is not
	 * always the fight this figure describes - a room with several NPCs, a
	 * target switched mid-fight, the opening attack recomputed against the
	 * fight's target, and every candidate the gear search evaluates all reach
	 * here. Taking the bar unchecked scaled one NPC's max health by another
	 * one's bar, which is not a rounding error but a different number
	 * altogether. Both the id and the index must agree: the id alone cannot
	 * separate two of the same NPC standing together.
	 *
	 * <p>What comes back is the health BAR, quantised to its own scale rather
	 * than exact hitpoints, so this is the right bucket and not the right
	 * number. Exact NPC health is not readable, and ruby's share of it inherits
	 * the granularity - a point or two on a high-health boss.
	 */
	private int targetCurrentHp(int npcId)
	{
		final Integer maxHp = npcManager.getHealth(npcId);
		if (maxHp == null || maxHp <= 0)
		{
			return 0;
		}
		final NPC npc = targetNpc;
		if (npc == null || !healthBarBelongsTo(npc.getId(), npc.getIndex(), npcId, targetIndex))
		{
			return maxHp;
		}
		final int ratio = npc.getHealthRatio();
		final int scale = npc.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return maxHp;
		}
		return Math.max(0, maxHp * ratio / scale);
	}

	/**
	 * Whether a health bar read off the interacting NPC describes the target
	 * being asked about. Split out static so the guard stays under test without
	 * a Client, like {@link #conflictionArmedAgainst}.
	 *
	 * <p>The index is only checked once there is one to check: no fight is open
	 * between kills and on the opening attack, and refusing the bar then would
	 * read every target at full health.
	 */
	static boolean healthBarBelongsTo(int barNpcId, int barIndex, int npcId, int targetIndex)
	{
		return barNpcId == npcId && (targetIndex < 0 || barIndex == targetIndex);
	}

	/**
	 * Defence level as the raid leaves it. The monster data carries raid level 0
	 * stats, so unscaled figures read too high - and in the flattering
	 * direction, the harder the invocations the better the player looks.
	 */
	private int defenceLevel(MonsterStatsProvider.MonsterStats npc)
	{
		// What the target has left, not what it started with: warhammers and godswords take real levels off it.
		return Math.max(0, raidScaled(npc) - drain.drainedFrom(targetIndex));
	}

	/**
	 * The NPC the expected figures are being asked about: its index for the
	 * drain lookup, and the NPC itself for the health bar the ruby bolt needs.
	 *
	 * <p>The NPC is held rather than found again from
	 * {@code getLocalPlayer().getInteracting()}, which is what the health bar
	 * used to be read off. Interaction lags a target switch by a tick, so on the
	 * tick of a switch it still named the NPC just left - and the guard, quite
	 * correctly, refused a bar belonging to something else and fell back to full
	 * health. That showed in game as the ruby figure reading full health for one
	 * tick after every switch before correcting itself. This is the fight's
	 * target as the plugin already knows it, which is true from the tick the
	 * fight opens.
	 */
	void setTarget(NPC npc)
	{
		this.targetNpc = npc;
		this.targetIndex = npc == null ? -1 : npc.getIndex();
	}

	// ToA: 2% per five raid levels, additively. Defence level only - magic does not scale, nor do defence bonuses.
	// Chambers scaling is in RaidScaling.
	private int raidScaled(MonsterStatsProvider.MonsterStats npc)
	{
		return RaidScaling.defence(client, npc.getDefenceLevel(), npc.getName(), partyHitpoints.highest());
	}

	private double meleeHitChance(AttackStyle style, AttackType type,
		MonsterStatsProvider.MonsterStats npc, double gear, SpecialAttack spec)
	{
		final int effAtk = (int) Math.floor(boostedLevel(Skill.ATTACK) * meleeAccuracyPrayer())
			+ style.attackLevelBonus() + 8;
		final int attRoll = scaled(attackRoll(effAtk, attackBonus(type), gear), spec);
		final int defBonus = type == AttackType.STAB ? npc.getDefStab()
			: type == AttackType.SLASH ? npc.getDefSlash() : npc.getDefCrush();
		final int defRoll = (defenceLevel(npc) + 9) * (defBonus + 64);
		return meleeHitChanceFrom(attRoll, defRoll);
	}

	/**
	 * Magic rolls against the target's magic level, not its defence, and starts
	 * from +9 rather than +8. The 30/70 blend is the player rule; a monster's
	 * magic defence is its Magic stat and its magic defence bonus (wiki).
	 */
	private double magicHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc, double gear,
		int npcId, SpecialAttack spec)
	{
		final int effMagic = (int) Math.floor(boostedLevel(Skill.MAGIC) * magicAccuracyPrayer())
			+ style.attackLevelBonus() + 9;
		// Elemental weakness is worth as much accuracy as it is damage, a point each, and multiplies the roll as the gear
		// effects do.
		final int attRoll = scaled(attackRoll(effMagic, attackBonus(AttackType.MAGIC),
			gear * (1.0 + elementalWeakness(npcId) / 100.0)), spec);
		final int magic = RaidScaling.magic(client, npcId, npc.getMagicLevel(), npc.getName(),
			partyHitpoints.highest());
		final int defRoll = (magic + 9) * (npc.getDefMagic() + 64);
		if (!hasConflictionGauntlets())
		{
			return hitChanceFrom(attRoll, defRoll);
		}
		// The real chance for the attack about to be thrown, not the long-run average: the gauntlets roll twice after a miss,
		// so every attack is at one of two known chances. Armed against an index rather than a flag, and both sides must name
		// a real enemy - no fight is -1 and unarmed is -1, and comparing them bare made the two nothings match.
		final boolean armed = conflictionArmedAgainst(charge.armedIndex(), targetIndex);
		return armed
			? 1.0 - sharedDefenceMissChance(attRoll, defRoll)
			: hitChanceFrom(attRoll, defRoll);
	}

	/**
	 * How a magic attack of mine ended, which says whether the next against
	 * that enemy rolls twice. A miss arms, a hit spends, and misses do not
	 * stack.
	 */
	void noteMagicResolved(int npcIndex, boolean missed)
	{
		charge.resolved(npcIndex, missed);
	}

	/**
	 * Which enemy the doubled roll is held against, apart so the rules can be
	 * tested without a Client. There are two, and both have been wrong once.
	 */
	static final class ConflictionCharge
	{
		private int armedIndex = -1;

		int armedIndex()
		{
			return armedIndex;
		}

		void resolved(int npcIndex, boolean missed)
		{
			if (npcIndex < 0)
			{
				// Nothing to hold it against.
				armedIndex = -1;
			}
			else if (missed)
			{
				// The last miss is what it is held against; a second on the same enemy is the same charge, since the effect does
				// not stack.
				armedIndex = npcIndex;
			}
			else if (armedIndex == npcIndex)
			{
				// Only a hit on the enemy it is held against spends it.
				armedIndex = -1;
			}
		}
	}

	/**
	 * Whether the doubled roll applies to the attack about to be thrown: armed
	 * by a miss, and against the enemy that miss was against. The wiki says
	 * "against the same enemy", so switching target drops it.
	 */
	static boolean conflictionArmedAgainst(int armedIndex, int targetIndex)
	{
		return armedIndex >= 0 && armedIndex == targetIndex;
	}

	/**
	 * Whether the gauntlets can work at all: worn, and no two-hander, which
	 * disables the effect outright.
	 */
	static boolean conflictionGauntletsWork(int glovesItemId, boolean twoHandedWeapon)
	{
		return glovesItemId == ItemID.CONFLICTION_GAUNTLETS && !twoHandedWeapon;
	}

	/** Forgets any charge held, because the fight it belonged to is over. */
	void forgetConflictionCharge()
	{
		charge.resolved(-1, false);
	}

	/** Whether the gauntlets are in play at all, so the caller can skip the rest. */
	boolean usesConflictionGauntlets()
	{
		return hasConflictionGauntlets() && attackStyle().getAttackType() == AttackType.MAGIC;
	}

	// Worn, and not disabled by a two-hander.
	private boolean hasConflictionGauntlets()
	{
		final ItemEquipmentStats weapon = weaponStats();
		return conflictionGauntletsWork(equippedItemId(EquipmentInventorySlot.GLOVES),
			weapon != null && weapon.isTwoHanded());
	}

	private double rangedHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc,
		double gear, SpecialAttack spec)
	{
		final int effRanged = (int) Math.floor(boostedLevel(Skill.RANGED) * rangedAccuracyPrayer())
			+ style.attackLevelBonus() + 8;
		final int attRoll = scaled(attackRoll(effRanged, attackBonus(AttackType.RANGED), gear), spec);
		final int defRoll = (defenceLevel(npc) + 9) * (npc.getDefRanged() + 64);
		return hitChanceFrom(attRoll, defRoll);
	}

	/**
	 * The attack roll, floored at zero: a bonus below -64 turns the term
	 * negative, and hitChanceFrom then returns a negative chance, which callers
	 * read as "no figure". Casting from melee gear reaches it easily.
	 */
	/** The special's scaling of a finished attack roll, or the roll unchanged. */
	private static int scaled(int attackRoll, SpecialAttack spec)
	{
		return spec == null ? attackRoll : spec.scaleAttackRoll(attackRoll);
	}

	static int attackRoll(int effectiveLevel, int equipmentBonus, double gearMultiplier)
	{
		return Math.max(0, (int) (effectiveLevel * (equipmentBonus + 64) * gearMultiplier));
	}

	private static double hitChanceFrom(int attRoll, int defRoll)
	{
		if (attRoll > defRoll)
		{
			return 1.0 - (defRoll + 2.0) / (2.0 * (attRoll + 1.0));
		}
		return attRoll / (2.0 * (defRoll + 1.0));
	}

	// Melee hit chance, with the fang's second accuracy roll. Inside ToA the defence roll is re-rolled too, making the
	// attempts independent; outside, both attack rolls are compared against one defence roll.
	private double meleeHitChanceFrom(int attRoll, int defRoll)
	{
		if (!isFangEquipped())
		{
			return hitChanceFrom(attRoll, defRoll);
		}
		if (gearBonuses.inTombsOfAmascut())
		{
			final double miss = 1.0 - hitChanceFrom(attRoll, defRoll);
			return 1.0 - miss * miss;
		}
		return 1.0 - sharedDefenceMissChance(attRoll, defRoll);
	}

	// Chance two attack rolls both fail against the same defence roll. For a fixed d one attack misses at
	// (d+1)/(attRoll+1), so two miss at the square; averaging that over d gives the closed forms, the sum of squares
	// becoming the (d+2)(2d+3)/6 term. Past attRoll every d misses outright. Checked against brute-force enumeration.
	private static double sharedDefenceMissChance(int attRoll, int defRoll)
	{
		final double a = attRoll;
		final double d = defRoll;
		if (defRoll <= attRoll)
		{
			return (d + 2.0) * (2.0 * d + 3.0) / (6.0 * (a + 1.0) * (a + 1.0));
		}
		final double capped = (a + 2.0) * (2.0 * a + 3.0) / (6.0 * (a + 1.0));
		return (capped + (d - a)) / (d + 1.0);
	}

	private boolean isFangEquipped()
	{
		final int weapon = weaponItemId();
		return weapon == ItemID.OSMUMTENS_FANG || weapon == ItemID.OSMUMTENS_FANG_ORNAMENT;
	}

	private double meleeAccuracyPrayer()
	{
		if (mode != REAL)
		{
			return prayerGoal().getAttackMultiplier();
		}
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_DECIMATE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_STRENGTH))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.PIETY))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.CHIVALRY))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.INCREDIBLE_REFLEXES))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.IMPROVED_REFLEXES))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.CLARITY_OF_THOUGHT))
		{
			return 1.05;
		}
		return 1.0;
	}

	// The selected combat option: the weapon's category paired with the option index, never null. Category from the name
	// on the combat tab, falling back to the varbit id - the name wins because the ids have drifted, and a powered staff
	// reporting 24 made tridents resolve as melee weapons.
	private WeaponCategory weaponCategory()
	{
		final String heading = combatTabCategory();
		final int weapon = weaponItemId();
		// The combat tab lags the equipment by a tick, so on a swap the heading still names the old weapon, and pairing it
		// with the new one's bonuses halved accuracy for a tick. A heading that has not changed while the weapon has is
		// stale, and is dropped for the weapon's own attack bonus.
		final boolean stale = weapon != headingWeapon && heading != null && heading.equals(headingText);
		if (!stale)
		{
			headingWeapon = weapon;
			headingText = heading;
		}
		final WeaponCategory named = stale ? null : WeaponCategory.forName(heading);
		if (named != null)
		{
			return named;
		}

		final String weaponName = weaponName();
		if (weaponName != null)
		{
			final String lower = weaponName.toLowerCase(Locale.ROOT);
			if (lower.contains("blowpipe") || lower.contains("dart") || lower.contains("knife") || lower.contains("thrownaxe") || lower.contains("chinchompa") || lower.contains("toktz-xil-ul"))
			{
				return WeaponCategory.THROWN;
			}
			if (lower.contains("crossbow") || lower.contains("c'bow"))
			{
				return WeaponCategory.CROSSBOW;
			}
			if (lower.contains("bow") || lower.contains("seercull"))
			{
				return WeaponCategory.BOW;
			}
		}

		return stale ? null
			: WeaponCategory.forVarbit(client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY));
	}

	// The combat tab's category heading, e.g. "Category: Whip", or null. The text is null-checked as well as the widget:
	// an interface can exist before its text is set, and Text.removeTags throws on null.
	String categoryName()
	{
		final WeaponCategory category = weaponCategory();
		return category == null ? "fallback:" + attackStyle().getAttackType() : category.name();
	}

	private String combatTabCategory()
	{
		final Widget widget = client.getWidget(InterfaceID.CombatInterface.CATEGORY);
		if (widget == null || widget.getText() == null)
		{
			return null;
		}
		return Text.removeTags(widget.getText());
	}

	/** The spell currently set to autocast, or null if none is. */
	private Spell autocastSpell()
	{
		return Spell.forVarbit(client.getVarbitValue(VarbitID.AUTOCAST_SPELL));
	}

	/**
	 * Notes a spell clicked onto an NPC. A manual cast overrides the weapon, so
	 * a trident held while barraging reports the barrage.
	 */
	void recordManualCast(Spell spell)
	{
		// A click arriving after the previous one has lapsed starts the count over rather than adding to a cast that never
		// happened.
		if (activeManualCast() == null)
		{
			manualCastsPending = 0;
		}
		manualCastSpell = spell;
		manualCastTick = client.getTickCount();
		// Capped: spamming the spell icon queues clicks the game will never turn into casts, and each would have to be spent
		// before the weapon is read.
		manualCastsPending = Math.min(MAX_PENDING_CASTS, manualCastsPending + 1);
	}

	/**
	 * The manually cast spell, if cast recently enough to still be what the
	 * player is doing. Casting takes 5 ticks, so the window is slightly wider
	 * and lapses back to the weapon once they stop clicking.
	 */
	private Spell activeManualCast()
	{
		if (manualCastSpell == null || client.getTickCount() - manualCastTick > MANUAL_CAST_TICKS)
		{
			return null;
		}
		return manualCastSpell;
	}

	// How the attack about to be described was proven. A hitsplat can only be a melee blow, and a melee blow is never a
	// cast whatever is queued.
	void noteAttackKind(boolean fromProjectile)
	{
		if (meleeProvenAttack != fromProjectile)
		{
			return; // already set the way this attack needs it
		}
		meleeProvenAttack = !fromProjectile;
		memoStyle = null; // the style turns on this
	}

	// Spends the manual cast once one has gone out under it. The timer alone cannot end it: it has to outlast the click-
	// to-cast delay, and a window that wide goes on claiming the weapon's own attacks afterwards.
	void noteAttackThrown()
	{
		if (meleeProvenAttack || activeManualCast() == null)
		{
			manualCastsPending = 0;
			return;
		}
		// One cast leaves per click and the two are several ticks apart, so more than one click can be outstanding at once.
		if (--manualCastsPending <= 0)
		{
			manualCastsPending = 0;
			manualCastSpell = null;
		}
	}

	/** The spell being cast, manual taking priority over autocast. */
	String activeSpellName()
	{
		final Spell spell = activeSpell();
		return spell == null ? "none" : spell.getDisplayName();
	}

	boolean castLandsWithoutProjectile()
	{
		final Spell spell = activeSpell();
		return spell != null && spell.landsWithoutProjectile()
			&& attackStyle().getAttackType() == AttackType.MAGIC;
	}

	private Spell activeSpell()
	{
		final Spell manual = activeManualCast();
		return manual != null ? manual : autocastSpell();
	}

	private AttackStyle resolveAttackStyle()
	{
		final int varp = client.getVarpValue(VarPlayerID.COM_MODE);
		// A spell clicked onto an NPC is a magic attack whatever is held, so it is settled before the weapon. A manual cast
		// is accurate whatever the combat option says.
		if (!meleeProvenAttack && activeManualCast() != null)
		{
			return new AttackStyle(varp, "Manual cast", AttackType.MAGIC, CombatStyle.ACCURATE);
		}
		// A staff recognised by name attacks with magic whatever the category table claims - its ids have gone stale, and
		// trusting it sent tridents down the melee path.
		if (poweredStaffMaxHit() > 0)
		{
			return new AttackStyle(varp, "Powered staff", AttackType.MAGIC,
				varp == 3 ? CombatStyle.LONGRANGE : CombatStyle.ACCURATE);
		}
		final WeaponCategory category = weaponCategory();
		if (category != null)
		{
			final AttackStyle style = category.styleFor(varp);
			if (style != null)
			{
				return style;
			}
		}
		return fallbackStyle(varp);
	}

	/**
	 * Best guess for a category with no table entry: the attack type from the
	 * weapon's dominant attack bonus, the style from the layout most share.
	 */
	private AttackStyle fallbackStyle(int varp)
	{
		final AttackType type = dominantAttackType();
		final CombatStyle style;
		if (type == AttackType.RANGED)
		{
			style = varp == 0 ? CombatStyle.ACCURATE
				: varp == 1 ? CombatStyle.RAPID
				: varp == 3 ? CombatStyle.LONGRANGE : CombatStyle.DEFENSIVE;
		}
		else if (type == AttackType.MAGIC)
		{
			style = varp == 3 ? CombatStyle.LONGRANGE : CombatStyle.ACCURATE;
		}
		else
		{
			style = varp == 0 ? CombatStyle.ACCURATE
				: varp == 1 ? CombatStyle.AGGRESSIVE
				: varp == 2 ? CombatStyle.CONTROLLED : CombatStyle.DEFENSIVE;
		}
		return new AttackStyle(varp, "Unknown", type, style);
	}

	/** The attack type the equipped weapon is strongest in. */
	private AttackType dominantAttackType()
	{
		final ItemEquipmentStats w = weaponStats();
		if (w == null)
		{
			return AttackType.CRUSH; // unarmed punches are crush
		}
		final int melee = Math.max(w.getAstab(), Math.max(w.getAslash(), w.getAcrush()));
		if (w.getArange() > melee && w.getArange() >= w.getAmagic())
		{
			return AttackType.RANGED;
		}
		if (w.getAmagic() > melee && w.getAmagic() > w.getArange())
		{
			return AttackType.MAGIC;
		}
		if (w.getAstab() >= w.getAslash() && w.getAstab() >= w.getAcrush())
		{
			return AttackType.STAB;
		}
		return w.getAslash() >= w.getAcrush() ? AttackType.SLASH : AttackType.CRUSH;
	}

	/**
	 * The dart loaded in the blowpipe, or null when one is not held. The game
	 * does not say which, so this comes from the config.
	 */
	private ItemEquipmentStats blowpipeDart()
	{
		final String weapon = weaponName();
		if (weapon == null || !weapon.toLowerCase().contains("blowpipe"))
		{
			return null;
		}
		final BlowpipeDart dart = config.blowpipeDart();
		if (dart == null || dart == BlowpipeDart.NONE)
		{
			return null;
		}
		// Held onto rather than looked up again: this is reached several times a tick, and the answer only changes when the
		// setting does.
		if (dart != cachedDart)
		{
			final ItemStats stats = itemManager.getItemStats(dart.getItemId());
			cachedDartStats = stats == null ? null : stats.getEquipment();
			cachedDart = dart;
		}
		return cachedDartStats;
	}

	// The equipment stats of one item, or null for an empty slot or an item carrying none.
	private ItemEquipmentStats equipmentStats(int itemId)
	{
		if (itemId < 0)
		{
			return null;
		}
		final ItemStats stats = itemManager.getItemStats(itemId);
		return stats == null ? null : stats.getEquipment();
	}

	/** Sum of the worn gear's attack bonus for the type being rolled. */
	private int computeAttackBonus(AttackType type)
	{
		final Loadout gear = gear();
		final boolean skipAmmo = !ammoSlotFeedsWeapon();
		int total = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (skipAmmo && slot == EquipmentInventorySlot.AMMO)
			{
				continue;
			}
			final ItemEquipmentStats e = equipmentStats(gear.id(slot));
			if (e != null)
			{
				switch (type)
				{
					case STAB:
						total += e.getAstab();
						break;
					case SLASH:
						total += e.getAslash();
						break;
					case CRUSH:
						total += e.getAcrush();
						break;
					case RANGED:
						total += e.getArange();
						break;
					default:
						total += e.getAmagic();
						break;
				}
			}
		}
		if (type == AttackType.MAGIC && usingWeaponsOwnAttack())
		{
			// The shadow multiplies the magic accuracy of everything else worn on the same terms as the damage: its own spell
			// only.
			total *= gearBonuses.shadowMultiplier(gear);
		}
		if (type == AttackType.RANGED)
		{
			final ItemEquipmentStats dart = blowpipeDart();
			if (dart != null)
			{
				total += dart.getArange();
			}
		}
		return total;
	}

	private ItemEquipmentStats weaponStats()
	{
		return equipmentStats(gear().id(EquipmentInventorySlot.WEAPON));
	}

	/** Ticks between attacks, which is the cadence tick loss measures against. */
	/**
	 * Damage a second this loadout is expected to do against this target, or -1
	 * when there is no figure.
	 *
	 * <p>Built from the ATTACK SPEED, not from a clock: average hit over the
	 * weapon's own cooldown. That is what makes it comparable between two setups
	 * that swing at different speeds, which is the whole reason it is shown - a
	 * bigger average hit on a slower weapon is not obviously better and this is
	 * the figure that settles it. It also owes nothing to the fight timer, which
	 * the measured dps does.
	 *
	 * <p>Ordinary attacks only. A special is not sustained, so folding one in
	 * would describe a rotation nobody can keep up.
	 */
	double expectedDps(int npcId)
	{
		final double average = averageHit(npcId);
		if (average < 0)
		{
			return -1;
		}
		return dpsFrom(average, attackSpeedTicks());
	}

	/**
	 * Average hit over the weapon's cooldown. Split out static so the arithmetic
	 * stays under test without a Client, like fangMaxHit and attackRoll.
	 */
	static double dpsFrom(double averageHit, int attackSpeedTicks)
	{
		return attackSpeedTicks <= 0 || averageHit < 0
			? -1
			: averageHit / (attackSpeedTicks * TICK_SECONDS);
	}

	int attackSpeedTicks()
	{
		final ItemEquipmentStats w = weaponStats();
		final int speed = w == null ? 4 : w.getAspeed();
		final int ticks = speed <= 0 ? 4 : speed;
		final AttackStyle style = attackStyle();
		if (style.getAttackType() == AttackType.MAGIC)
		{
			return castSpeedTicks(ticks);
		}
		// Rapid fires a tick sooner; it is the only style that alters attack speed.
		return style.getCombatStyle() == CombatStyle.RAPID ? Math.max(1, ticks - 1) : ticks;
	}

	/**
	 * Casting runs on its own clock: the weapon's speed applies only to powered
	 * staves and salamanders. Everything else casts in 5 ticks, or 4 for the
	 * harmonised staff on the standard spellbook.
	 */
	private int castSpeedTicks(int weaponTicks)
	{
		final WeaponCategory category = weaponCategory();
		// A manual cast runs on the spell's clock even from a powered staff, so a trident being barraged off is 5 ticks.
		// Recognising the staff by name matters here too: by category alone an unmapped one got the spell clock.
		final boolean ownAttack = poweredStaffMaxHit() > 0
			|| category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER;
		if (activeManualCast() == null && ownAttack)
		{
			return weaponTicks;
		}
		final Spell spell = activeSpell();
		if (weaponItemId() == ItemID.NIGHTMARE_STAFF_HARMONISED
			&& spell != null && spell.getSpellbook() == Spellbook.STANDARD)
		{
			return 4;
		}
		return 5;
	}

	/**
	 * Max hit against this target, or 0 if unavailable. The target matters
	 * because salve, dragon hunter, demonbane and the rest only apply against
	 * what they are meant for; pass -1 for the figure before those.
	 */
	int maxHit(int npcId)
	{
		// What actually lands, which against Olm's hands is a third of it when the style is the wrong one for that hand.
		final int first = (int) (unmitigatedMaxHit(npcId) * mitigation(npcId));
		final int cap = damageCap(npcId);
		// The most ONE ATTACK can deal, which for a scythe is all its hits: a 51 max against a 3x3 is 51 + 25 + 12, since the
		// halving rounds down at every step. The cap is per hit, being a limit on what one blow may land.
		int total = 0;
		int max = first;
		for (int hit = 0; hit < hitsPerAttack(npcId); hit++)
		{
			total += Math.min(cap, max);
			max = nextScytheMax(max);
		}
		return total;
	}

	/**
	 * The max of the hit after this one in a scythe swing: half, rounded down.
	 * Wiki: base 47 gives 47-23-11, base 48 gives 48-24-12.
	 */
	private String scytheBreakdown(int npcId)
	{
		final int hits = hitsPerAttack(npcId);
		if (hits < 2)
		{
			return "";
		}
		final StringBuilder parts = new StringBuilder(" (");
		final int cap = damageCap(npcId);
		int max = (int) (unmitigatedMaxHit(npcId) * mitigation(npcId));
		for (int hit = 0; hit < hits; hit++)
		{
			parts.append(hit == 0 ? "" : "+").append(Math.min(cap, max));
			max = nextScytheMax(max);
		}
		return parts.append(')').toString();
	}

	static int nextScytheMax(int max)
	{
		return max / 2;
	}

	/**
	 * The chance an attack lands at all, which is not its accuracy once one
	 * attack is several hits: the measured side counts the swing once however
	 * many connect, so this has to be the chance that any did.
	 */
	double landChance(int npcId)
	{
		final double accuracy = hitChance(npcId);
		if (accuracy < 0)
		{
			return accuracy;
		}
		return landChance(accuracy, hitsPerAttack(npcId));
	}

	static double landChance(double accuracy, int hits)
	{
		return accuracy < 0 ? accuracy : 1.0 - Math.pow(1.0 - accuracy, hits);
	}

	/**
	 * How many times one attack lands here: a scythe hits twice against a 2x2
	 * and three times against 3x3 or larger, each rolling its own accuracy and
	 * strength. Only the SAME target - the arc beside it is the AoE gap.
	 */
	int hitsPerAttack(int npcId)
	{
		if (!isScytheEquipped() || !attackStyle().getAttackType().isMelee())
		{
			return 1;
		}
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		if (npc == null || npc.getSize() < 2)
		{
			return 1;
		}
		return npc.getSize() >= 3 ? 3 : 2;
	}

	private boolean isScytheEquipped()
	{
		return isScythe(weaponName());
	}

	/** Whether this weapon name is a scythe of vitur, in any of its forms. */
	static boolean isScythe(String weaponName)
	{
		return weaponName != null && weaponName.toLowerCase(Locale.ROOT).contains("scythe of vitur");
	}

	private int damageCap(int npcId)
	{
		if (npcId == NpcID.HUEY_TAIL || npcId == NpcID.HUEY_TAIL_BROKEN)
		{
			return crushIsHighestAttackBonus() ? 9 : 4;
		}
		return verzikFirstPhaseCap(npcId);
	}

	/**
	 * Verzik's first phase caps every hitsplat but the Dawnbringer's special:
	 * ten for melee, three for ranged and magic. The cap is per hitsplat, which
	 * is what {@link #cappedAverage} already models.
	 *
	 * <p>The Dawnbringer exemption is NOT written here, and deliberately: the
	 * phase exempts Pulsate, not the staff, so an exemption at this level would
	 * uncap the Dawnbringer's ordinary attacks too. It lives on the special
	 * instead, as {@code SpecialAttack.ignoresDamageCap}, which is the only
	 * caller allowed to skip this. Every other special IS capped here - a
	 * godsword on the first phase is held to ten a hitsplat like anything else.
	 */
	private int verzikFirstPhaseCap(int npcId)
	{
		if (!VERZIK_FIRST_PHASE.contains(npcId))
		{
			return Integer.MAX_VALUE;
		}
		return attackStyle().getAttackType().isMelee() ? 10 : 3;
	}

	private boolean crushIsHighestAttackBonus()
	{
		final int crush = attackBonus(AttackType.CRUSH);
		return crush >= attackBonus(AttackType.STAB)
			&& crush >= attackBonus(AttackType.SLASH)
			&& crush >= attackBonus(AttackType.RANGED)
			&& crush >= attackBonus(AttackType.MAGIC);
	}

	/**
	 * The average of a roll from nought to {@code trueMax}, then capped. Every
	 * roll above the cap collapses onto it, so the average sits far closer to
	 * the cap than to half of it.
	 */
	/**
	 * The same average for a roll that cannot fall below {@code min}, which is
	 * the Voidwaker's shape and nothing else's.
	 *
	 * <p>Built out of the closed form below rather than walking the roll: the
	 * average over [min, max] is the whole sum less the part beneath min, so the
	 * tested arithmetic is reused on both ends and the hot path keeps its closed
	 * form. With min at zero this is that function exactly.
	 */
	static double cappedAverage(int min, int trueMax, int cap)
	{
		if (trueMax <= 0 || trueMax < min)
		{
			return 0;
		}
		if (min <= 0)
		{
			return cappedAverage(trueMax, cap);
		}
		final double whole = (trueMax + 1) * cappedAverage(trueMax, cap);
		final double beneath = min * cappedAverage(min - 1, cap);
		return (whole - beneath) / (trueMax - min + 1);
	}

	static double cappedAverage(int trueMax, int cap)
	{
		if (trueMax <= 0)
		{
			return 0;
		}
		if (cap >= trueMax)
		{
			return trueMax / 2.0;
		}
		final double atOrBelow = cap * (cap + 1) / 2.0;
		final double collapsedOnto = (double) (trueMax - cap) * cap;
		return (atOrBelow + collapsedOnto) / (trueMax + 1);
	}

	private int unmitigatedMaxHit(int npcId)
	{
		return fangNarrowed(unnarrowedMaxHit(npcId));
	}

	// Osmumten's fang rolls damage between 15% and 85% of the max hit rather than from zero, so the reachable max is the
	// max less the raised minimum.
	private int fangNarrowed(int hit)
	{
		return isFangEquipped() && attackStyle().getAttackType().isMelee()
			? fangMaxHit(hit)
			: hit;
	}

	/**
	 * The top of the fang's narrowed roll: the true max less the 15% it raises
	 * the bottom to. Split out so the off-by-one against a plain 0.85 stays
	 * under test without a Client.
	 */
	static int fangMaxHit(int trueMaxHit)
	{
		return trueMaxHit - trueMaxHit * 15 / 100;
	}

	/** The max hit before the fang narrows its roll, which is what its spec reaches. */
	private int unnarrowedMaxHit(int npcId)
	{
		final int hit = (int) (baseMaxHit() * gearBonus(npcId).getDamage())
			+ colossalBladeBonus(npcId) + elementalWeaknessBonus(npcId);
		final EnchantedBolt bolt = loadedBolt(npcId);
		if (bolt == null)
		{
			return hit;
		}
		switch (bolt)
		{
			case RUBY:
				// The bolt's hit replaces the weapon's, and only beats it on targets with a lot of health left.
				return Math.max(hit, EnchantedBolt.rubyDamage(targetCurrentHp(npcId)));
			case DIAMOND:
				return (int) (hit * 1.15);
			default:
				return (int) (hit * 1.20);
		}
	}

	/**
	 * The colossal blade adds 2 damage per tile of the target's size, up to 5.
	 * A flat addition, so it lands after the gear multipliers.
	 */
	private int colossalBladeBonus(int npcId)
	{
		if (weaponItemId() != ItemID.GIANTS_FOUNDRY_COLOSSAL_BLADE)
		{
			return 0;
		}
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		return npc == null ? 0 : 2 * Math.min(npc.getSize(), 5);
	}

	/** The equipped weapon's special attack, or null if it has none that hits. */
	SpecialAttack specialAttack()
	{
		return SpecialAttack.forItem(weaponItemId());
	}

	// The most one special attack activation can deal against this target, or 0 when the weapon has no damaging spec.
	// Multi-hit specs are totalled.
	int specialAttackMaxHit(int npcId)
	{
		// Mitigated like an ordinary hit: a special on the wrong hand is no more exempt from Olm's mitigation than anything
		// else is.
		return (int) (unmitigatedSpecialAttackMaxHit(npcId) * mitigation(npcId));
	}

	private int unmitigatedSpecialAttackMaxHit(int npcId)
	{
		final int weapon = weaponItemId();
		if (isFangEquipped())
		{
			// Eviscerate rolls to the true max instead of the narrowed 85%. Its other half is accuracy, which is not damage and
			// not counted here.
			return unnarrowedMaxHit(npcId);
		}
		if (weapon == ItemID.ABYSSAL_BLUDGEON)
		{
			final int missingPrayer = Math.max(0,
				client.getRealSkillLevel(Skill.PRAYER) - client.getBoostedSkillLevel(Skill.PRAYER));
			return (int) (unmitigatedMaxHit(npcId) * (1.0 + 0.005 * missingPrayer));
		}
		if (weapon == ItemID.NIGHTMARE_STAFF_VOLATILE || weapon == ItemID.DEADMAN_BLIGHTED_VOLATILE_STAFF)
		{
			final int magic = boostedLevel(Skill.MAGIC);
			// The spec is its own attack, not a spell, so no ancient bonus applies.
			return applyMagicDamage(Math.min(58, 58 * magic / 99 + 1), null);
		}
		if (weapon == ItemID.NIGHTMARE_STAFF_ELDRITCH)
		{
			final int magic = boostedLevel(Skill.MAGIC);
			// Wiki: min(floor(44 * magic / 99 + 1), 44), then the staff's own magic damage - the same shape as the volatile
			// staff above, which is why it sits beside it.
			return applyMagicDamage(Math.min(44, 44 * magic / 99 + 1), null);
		}
		if (weapon == ItemID.VERZIK_SPECIAL_WEAPON)
		{
			// Flat, and deliberately not run through applyMagicDamage: the wiki is explicit that magic damage gear, prayer and
			// the magic level all leave Pulsate alone.
			return SpecialAttack.DAWNBRINGER.fixedMax();
		}
		final SpecialAttack flat = SpecialAttack.forItem(weapon);
		if (flat != null && flat.hasFixedDamage())
		{
			// The dragonfire shields, which roll a flat range off the wielder's Defence rather than off any attack style or
			// gear.
			return flat.fixedMax();
		}
		final SpecialAttack spec = specialAttack();
		// The unmitigated hit: the caller applies mitigation, and taking it from maxHit() as well would apply Olm's third
		// twice over.
		return spec == null ? 0 : spec.maxTotal(unmitigatedMaxHit(npcId));
	}

	/**
	 * The chance one activation of the special lands at least one hitsplat.
	 *
	 * <p>Not the ordinary hit chance: most specials modify the attack roll, the
	 * Voidwaker and the Dawnbringer cannot miss at all, and the claws roll up to
	 * four times, so missing needs every roll to fail. The abyssal dagger is the
	 * other way about - its two rolls are linked, so both hits land or neither
	 * does, and a second hit adds nothing to the chance of landing.
	 */
	double specialAttackLandChance(int npcId)
	{
		final SpecialAttack spec = specialAttack();
		if (spec == null)
		{
			return landChance(hitChance(npcId), hitsPerAttack(npcId));
		}
		if (spec.alwaysHits())
		{
			return 1.0;
		}
		final double accuracy = hitChance(npcId, spec);
		if (accuracy < 0)
		{
			return -1;
		}
		if (spec.sharedAccuracyRoll())
		{
			return accuracy;
		}
		// One roll per hitsplat either way. A cascade stops at its first success and the others all roll independently, but
		// "at least one connected" is the same arithmetic for both, so the count is all this needs.
		return landChance(accuracy, spec.hits());
	}

	/**
	 * What one activation of the equipped special is expected to deal here.
	 *
	 * <p>This is the special's counterpart to {@link #averageHit}, and the two
	 * are not interchangeable: a special has its own accuracy, its own per-hit
	 * maxima and, for the claws and the Voidwaker, its own shape of damage roll.
	 * Booking a special with the ordinary figure understates a godsword and
	 * badly understates anything capped - a Dawnbringer on Verzik's first phase
	 * expects three where it deals fifty, because the cap belongs to her
	 * ordinary hitsplats and not to the special that is exempt from it.
	 */
	double specialAttackAverageHit(int npcId)
	{
		final SpecialAttack spec = specialAttack();
		if (spec == null)
		{
			// No special that changes the hit, so activating one deals what an ordinary attack deals.
			return averageHit(npcId);
		}
		final double accuracy = spec.alwaysHits() ? 1.0 : hitChance(npcId, spec);
		if (accuracy < 0)
		{
			return -1;
		}
		// Pulsate alone: fixed damage that no gear, prayer or mitigation touches, and the only hit Verzik's first phase lets
		// past its cap.
		if (spec.hasFixedDamage())
		{
			final int flatMax = spec.fixedMax();
			return cappedAverage((int) (flatMax * spec.minFraction()), flatMax,
				spec.ignoresDamageCap() ? Integer.MAX_VALUE : damageCap(npcId));
		}
		// Chance-based gear is worth less over time than it is at the top of the roll, which is the whole reason averageHit
		// has its own multiplier. The ratio carries that across without restating how a max hit is built.
		final GearBonus bonus = gearBonus(npcId);
		final double expectedShare = bonus.getDamage() > 0
			? bonus.getExpectedDamage() / bonus.getDamage()
			: 1.0;
		final double scale = mitigation(npcId) * expectedShare;
		// Every other special is capped like an ordinary hit: the phase exempts the Dawnbringer's, not specials in general,
		// so a godsword there is still held to ten a hitsplat.
		final int cap = damageCap(npcId);
		if (spec.cascadingAccuracy())
		{
			final int normalMax = (int) (unmitigatedMaxHit(npcId) * scale);
			return spec == SpecialAttack.BURNING_CLAWS
				? burningClawsAverage(normalMax, accuracy, cap)
				: clawsAverage(normalMax, accuracy, cap);
		}
		if (spec.binomialAccuracy())
		{
			return crimsonKistenAverage((int) (unmitigatedMaxHit(npcId) * scale), accuracy, cap);
		}
		// A special with no damage multipliers of its own - the fang, the bludgeon, the volatile staff - has already had its
		// max worked out.
		final int base = spec.hasDamageMultipliers()
			? unmitigatedMaxHit(npcId)
			: unmitigatedSpecialAttackMaxHit(npcId);
		return specAverage(spec.hitMaxima((int) (base * scale)), accuracy, spec.minFraction(),
			spec.flatMinimum(), cap);
	}

	/**
	 * The expected damage of a special that rolls its accuracy once and then
	 * lands every hitsplat, which is all of them bar the claws.
	 */
	static double specAverage(int[] hitMaxima, double accuracy, double minFraction, int cap)
	{
		return specAverage(hitMaxima, accuracy, minFraction, 0, cap);
	}

	/**
	 * As above, with a floor stated in hitpoints as well as one stated as a
	 * share of the hit's own max. Only the granite hammer needs the flat one,
	 * whose Hammer Blow adds five to a roll made against the max less five.
	 */
	static double specAverage(int[] hitMaxima, double accuracy, double minFraction,
		int flatMinimum, int cap)
	{
		double total = 0;
		for (int max : hitMaxima)
		{
			final int floor = Math.max(flatMinimum, (int) (max * minFraction));
			total += accuracy * cappedAverage(Math.min(floor, max), max, cap);
		}
		return total;
	}

	/**
	 * The crimson kisten's expected damage, which is a binomial rather than a
	 * cascade: all four accuracy rolls are made and the number that SUCCEED
	 * decides the damage, where the claws stop at the first success.
	 *
	 * <p>Wiki: one success deals 70-110% of the max hit, two 90-130%, three
	 * 110-150% and four 130-170%. No success deals nothing. Each band is 40%
	 * wide, so the outcome averages the midpoints - 90%, 110%, 130%, 150%.
	 */
	static double crimsonKistenAverage(int normalMaxHit, double accuracy, int cap)
	{
		double expected = 0;
		for (int hits = 1; hits <= KISTEN_ROLLS; hits++)
		{
			final double weight = binomial(KISTEN_ROLLS, hits)
				* Math.pow(accuracy, hits) * Math.pow(1.0 - accuracy, KISTEN_ROLLS - hits);
			final double floor = KISTEN_FLOORS[hits - 1];
			expected += weight * cappedAverage(
				(int) (normalMaxHit * floor), (int) (normalMaxHit * (floor + KISTEN_BAND)), cap);
		}
		return expected;
	}

	/** Ways to choose k of n, for the kisten's four rolls. */
	private static double binomial(int n, int k)
	{
		double result = 1;
		for (int i = 0; i < k; i++)
		{
			result = result * (n - i) / (i + 1);
		}
		return result;
	}

	/**
	 * The claws' expected damage, which their cascade of accuracy rolls makes a
	 * different shape from every other special.
	 *
	 * <p>Wiki: up to four accuracy rolls are made and the first to succeed
	 * decides the damage - the first roll spreads a doubled max across four
	 * hitsplats, the second 175% across three, the third 150% across two and the
	 * fourth 125% onto one. Those totals are exactly the running sums of the
	 * claws' own 1, 1/2, 1/4, 1/4 shape bar the last, so each failed roll simply
	 * drops the largest remaining hitsplat; only the fourth outcome needs a
	 * shape of its own. Four failures still deal 0 or 2, averaging 1.
	 *
	 * <p>Modelling this matters: against a target hit four times in five the
	 * cascade is worth about a fifth more than one roll for the whole
	 * activation, because a first-roll miss is usually rescued by the second.
	 */
	static double clawsAverage(int normalMaxHit, double accuracy, int cap)
	{
		final double[] byOutcome = new double[CLAW_SHAPE.length];
		for (int failed = 0; failed < byOutcome.length; failed++)
		{
			final double[] shape = failed < byOutcome.length - 1
				? Arrays.copyOf(CLAW_SHAPE, byOutcome.length - failed)
				: CLAW_LAST_RESORT;
			for (double share : shape)
			{
				byOutcome[failed] += cappedAverage(0, (int) (normalMaxHit * share), cap);
			}
		}
		// Every roll missed, which is the one outcome that still deals something.
		return cascadeAverage(accuracy, byOutcome, Math.min(1.0, cap));
	}

	/**
	 * Burning barrage's expected damage. Three rolls, and like the dragon claws
	 * the first to connect decides the damage - but each outcome has its own
	 * damage RANGE rather than just a ceiling, so it needs its own figures.
	 *
	 * <p>Wiki: the first roll rolls a total between 75% and 175% of the max hit
	 * split 25-25-50, the second 50% to 150% split 50-50-0, the third 25% to
	 * 125% split 0-0-100, and three misses deal 0, 1 or 2 at 20/40/40. The
	 * per-hitsplat shuffles the wiki describes after each split (-1, -1, +2 and
	 * so on) move damage between hitsplats without changing the total, so they
	 * matter only against a cap and are folded into the shares below.
	 *
	 * <p>The burn is NOT counted. Each hitsplat can inflict one, worth 10 over
	 * 40 ticks and stacking to five, so a spec is worth 0 to 29 more damage
	 * arriving long after the attack. That is damage with no attack behind it,
	 * which is the same accounting problem as thrall damage and is parked for
	 * the same reason - see "Thrall damage" in HANDOFF.md before modelling it.
	 */
	static double burningClawsAverage(int normalMaxHit, double accuracy, int cap)
	{
		final double[] byOutcome = new double[BURNING_CLAW_SHAPES.length];
		for (int failed = 0; failed < byOutcome.length; failed++)
		{
			final double floor = BURNING_CLAW_FLOORS[failed];
			final double ceiling = BURNING_CLAW_CEILINGS[failed];
			for (double share : BURNING_CLAW_SHAPES[failed])
			{
				byOutcome[failed] += cappedAverage(
					(int) (normalMaxHit * floor * share),
					(int) (normalMaxHit * ceiling * share),
					cap);
			}
		}
		// 0, 1 or 2 at 20/40/40, which averages 1.2.
		return cascadeAverage(accuracy, byOutcome, Math.min(1.2, cap));
	}

	/**
	 * Walks a cascade of accuracy rolls, where the first roll to connect decides
	 * the damage and a roll is only made because the one before it missed.
	 *
	 * <p>Split out because both claws share it and it is the half that is easy
	 * to get wrong: outcome k is reached only when the first k rolls all missed,
	 * so its weight is {@code (1-a)^k * a} rather than {@code a}.
	 */
	private static double cascadeAverage(double accuracy, double[] damageByOutcome, double missAverage)
	{
		double expected = 0;
		double reached = 1.0;
		for (double damage : damageByOutcome)
		{
			expected += reached * accuracy * damage;
			reached *= 1.0 - accuracy;
		}
		return expected + reached * missAverage;
	}

	private int computeBaseMaxHit()
	{
		switch (weaponStyle())
		{
			case RANGED:
				return rangedMaxHit();
			case MAGIC:
				return magicMaxHit();
			default:
				return meleeMaxHit();
		}
	}

	private int meleeMaxHit()
	{
		final int level = boostedLevel(Skill.STRENGTH);
		if (level <= 0)
		{
			return 0;
		}
		final int effective = (int) Math.floor(level * meleePrayer()) + attackStyle().strengthLevelBonus() + 8;
		return maxHitFromStrength(effective, equipmentBonus(true));
	}

	private int rangedMaxHit()
	{
		final int level = boostedLevel(Skill.RANGED);
		if (level <= 0)
		{
			return 0;
		}
		final int effective = (int) Math.floor(level * rangedPrayer()) + attackStyle().strengthLevelBonus() + 8;
		return maxHitFromStrength(effective, equipmentBonus(false));
	}

	private static int maxHitFromStrength(int effectiveStrength, int strengthBonus)
	{
		return (effectiveStrength * (strengthBonus + 64) + 320) / 640;
	}

	// Magic max hit: the autocast spell's base hit, or the staff's own attack for a powered staff, scaled by the worn
	// magic damage bonus.
	/**
	 * The spell's own base max hit, before any magic damage bonus. Elemental
	 * weakness is a share of THIS rather than of the finished figure, so it has
	 * to be reachable on its own.
	 */
	private int spellBaseMaxHit()
	{
		final Spell manual = activeManualCast();
		if (manual != null)
		{
			return manual.maxHitAt(boostedLevel(Skill.MAGIC));
		}
		if (poweredStaffMaxHit() > 0)
		{
			return 0;
		}
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER)
		{
			return 0;
		}
		final Spell spell = autocastSpell();
		return spell == null ? 0 : spell.maxHitAt(boostedLevel(Skill.MAGIC));
	}

	/**
	 * How many points of elemental weakness this attack gets to use, or 0.
	 *
	 * <p>Wiki (Elemental weakness): each point is worth 1% magic damage AND 1%
	 * magic accuracy. It applies only to the elemental spells of the standard
	 * spellbook - strike, bolt, blast, wave and surge - and explicitly not to
	 * the ancient spellbook, so a barrage gets none of it however weak the
	 * target is to the element.
	 */
	private int elementalWeakness(int npcId)
	{
		final Spell spell = activeSpell();
		if (spell == null || attackStyle().getAttackType() != AttackType.MAGIC)
		{
			return 0;
		}
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		if (npc == null || npc.getWeaknessElement() == null || npc.getWeaknessSeverity() <= 0)
		{
			return 0;
		}
		return spell.isElement(npc.getWeaknessElement()) ? npc.getWeaknessSeverity() : 0;
	}

	/**
	 * The flat damage elemental weakness adds. Wiki:
	 * {@code floor(BaseMax x (1 + MagicDamage%)) x (1 + Slayer/Salve%) + floor(BaseMax x Weakness%)}
	 * - so it is a share of the SPELL'S BASE max, added after the multipliers
	 * rather than folded in with them. Folded in it would compound with salve
	 * and the slayer helm, which the formula keeps it apart from.
	 */
	private int elementalWeaknessBonus(int npcId)
	{
		final int severity = elementalWeakness(npcId);
		return severity <= 0 ? 0 : (int) Math.floor(spellBaseMaxHit() * severity / 100.0);
	}

	private int magicMaxHit()
	{
		// A manual cast is what the player is actually doing, whatever is held, so it wins over the weapon's own attack.
		final Spell manual = activeManualCast();
		if (manual != null)
		{
			return applyMagicDamage(manual.maxHitAt(boostedLevel(Skill.MAGIC)), manual);
		}
		// A staff recognised by name fires its own attack. Tested before the weapon category, so it still holds when the
		// category varbit is one this plugin has no entry for, relying on the category meant an unmapped one fell through and
		// reported the autocast spell instead.
		final int staff = poweredStaffMaxHit();
		if (staff > 0)
		{
			return applyMagicDamage(staff, null);
		}
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER)
		{
			// A powered staff never casts the autocast spell, so one that isn't recognised is unknown rather than spell-shaped.
			return 0;
		}
		final Spell spell = autocastSpell();
		return spell == null ? 0
			: applyMagicDamage(spell.maxHitAt(boostedLevel(Skill.MAGIC)), spell);
	}

	/**
	 * Scales a base magic hit by the summed magic damage % of the worn gear
	 * (occult, tormented, ancestral, ...), which Tumeken's Shadow multiplies.
	 * Not modelled: the tomes' elemental bonuses and the smoke staff's
	 * standard-spell bonus, so those setups still read low.
	 */
	private int applyMagicDamage(int baseMaxHit, Spell spell)
	{
		final Loadout gear = gear();
		// Magic damage is a float: several items carry fractions of a percent, so summing into an int would truncate each one
		// away.
		double percent = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			final ItemEquipmentStats e = equipmentStats(gear.id(slot));
			if (e != null)
			{
				percent += e.getMdmg();
			}
		}
		percent += gearBonuses.virtusAncientDamagePercent(gear, spell);
		// The shadow's passive belongs to its built-in spell and to nothing else. Casting blood barrage while holding it is
		// an ordinary cast: the wiki calls the effect "exclusive to its built-in spell", and applying it to every cast read a
		// manual barrage at the 100% cap when the gear alone was nowhere near it. A null spell is the weapon's own attack,
		// which is the only thing the multiplier may touch.
		final int multiplier = spell == null ? gearBonuses.shadowMultiplier(gear) : 1;
		percent *= multiplier;
		if (multiplier > 1)
		{
			// Tripled equipment damage is capped at 100%; the prayer share below is added after, and is not equipment.
			percent = Math.min(100.0, percent);
		}
		// Prayer magic damage is not worn equipment, so the shadow doesn't multiply it and the equipment cap doesn't apply to
		// it.
		percent += magicDamagePrayerPercent();
		return (int) Math.floor(baseMaxHit * (1.0 + percent / 100.0));
	}

	/**
	 * Whether the magic attack in hand is the weapon's own rather than a spell.
	 * A manual cast is a cast whatever is held, and a powered staff cannot
	 * autocast. This is what the shadow's passive is gated on.
	 */
	private boolean usingWeaponsOwnAttack()
	{
		return activeManualCast() == null && poweredStaffMaxHit() > 0;
	}

	// The staff's own attack, for powered staves, or 0 if this isn't one.
	private int poweredStaffMaxHit()
	{
		final String name = weaponName();
		if (name == null || name.toLowerCase().contains("uncharged"))
		{
			return 0;
		}
		final int magic = boostedLevel(Skill.MAGIC);
		// Trident of the seas is floor(magic / 3) - 5; the rest are offsets of it.
		final int seas = magic / 3 - 5;
		final String lower = name.toLowerCase();
		if (lower.startsWith("trident of the seas"))
		{
			return Math.max(1, seas);
		}
		if (lower.startsWith("trident of the swamp"))
		{
			return Math.max(1, seas + 3);
		}
		if (lower.endsWith("sanguinesti staff"))
		{
			return Math.max(1, seas + 4);
		}
		if (lower.startsWith("tumeken's shadow"))
		{
			return Math.max(1, seas + 6);
		}
		if (lower.startsWith("eye of ayak"))
		{
			return Math.max(1, seas - 1);
		}
		if (lower.startsWith("warped sceptre"))
		{
			return Math.max(1, (8 * magic + 96) / 37);
		}
		return 0;
	}

	/**
	 * A short summary of how the loadout was resolved, for ::loadout. Written
	 * for a player pasting it into a bug report, so it says which weapon and
	 * target were read and what came out.
	 */
	List<String> describeLoadout(int npcId)
	{
		final List<String> lines = new ArrayList<>();
		final AttackStyle style = attackStyle();
		final WeaponCategory category = weaponCategory();

		lines.add(String.format("%s - %s, %s %s, %d tick",
			weaponName() == null ? "Unarmed" : weaponName(),
			category == null ? "unknown category" : category.getGameName(),
			style.getAttackType(), style.getCombatStyle(), attackSpeedTicks()));

		final Spell spell = activeSpell();
		if (spell != null)
		{
			lines.add("Casting " + spell.getDisplayName());
		}

		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		// The id is printed either way. When there are no stats for it, the id is the only thing that identifies what the
		// player was actually looking at.
		lines.add(npc == null
			? String.format("Target: npc %d, no stats for it", npcId)
			: String.format("Target: %s (npc %d), defence %d (base %d), magic %d, toa raid level %d",
				npc.getName(), npcId, defenceLevel(npc), npc.getDefenceLevel(), npc.getMagicLevel(),
				// The level actually applied, not the raw varbit, which is not cleared on leaving the Tombs.
				gearBonuses.tombsRaidLevel()));

		final double accuracy = hitChance(npcId);
		lines.add(accuracy < 0
			? String.format("Max hit %d, no accuracy without target stats", maxHit(npcId))
			: String.format("Max hit %d%s, accuracy %.1f%% (lands %.1f%%), avg hit %.2f, dps %.2f",
				maxHit(npcId), scytheBreakdown(npcId), accuracy * 100,
				landChance(npcId) * 100, averageHit(npcId), expectedDps(npcId)));

		// Where a melee or ranged max hit came from - the counterpart of the magic breakdown below. Without it a wrong max
		// hit is one number with nothing behind it, and for a blowpipe the strength bonus carries the dart from the CONFIG
		// rather than from the weapon, which is the first thing to check when the figure looks too high or too low.
		if (style.getAttackType() != AttackType.MAGIC)
		{
			final boolean melee = style.getAttackType().isMelee();
			final ItemEquipmentStats dart = blowpipeDart();
			lines.add(String.format("%s: str bonus %+d%s, level %d, attack bonus %+d",
				melee ? "Melee" : "Ranged",
				equipmentBonus(melee),
				dart == null ? "" : String.format(" (config dart %s %+d)",
					config.blowpipeDart(), dart.getRstr()),
				boostedLevel(melee ? Skill.STRENGTH : Skill.RANGED),
				attackBonus(style.getAttackType())));

			// Per slot, because a total that looks wrong says nothing about WHICH item is wrong. Only the slots that actually
			// contribute are listed, so this is short in practice.
			final StringBuilder perSlot = new StringBuilder();
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				final int id = gear().id(slot);
				final ItemEquipmentStats e = equipmentStats(id);
				if (e == null)
				{
					continue;
				}
				final int str = melee ? e.getStr() : e.getRstr();
				if (str != 0)
				{
					perSlot.append(perSlot.length() == 0 ? "" : ", ")
						.append(slot).append(' ').append(itemManager.getItemComposition(id).getName())
						.append(' ').append(String.format("%+d", str));
				}
			}
			lines.add("  str from: " + (perSlot.length() == 0 ? "nothing worn" : perSlot));
		}

		// Where a magic max hit came from. Every part is a separate rule, and a wrong figure is otherwise one number with
		// nothing behind it.
		if (style.getAttackType() == AttackType.MAGIC)
		{
			final Loadout gear = gear();
			double worn = 0;
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				final ItemEquipmentStats e = equipmentStats(gear.id(slot));
				if (e != null)
				{
					worn += e.getMdmg();
				}
			}
			final Spell cast = activeSpell();
			final boolean own = usingWeaponsOwnAttack();
			lines.add(String.format("Magic dmg: worn %.1f%%, ancient %.1f%%, prayer %.1f%%, shadow x%d (%s)",
				worn, gearBonuses.virtusAncientDamagePercent(gear, cast), magicDamagePrayerPercent(),
				own ? gearBonuses.shadowMultiplier(gear) : 1,
				own ? "own attack" : "cast, passive does not apply"));
			final GearBonus bonus = gearBonus(npcId);
			lines.add(String.format("Gear multipliers: accuracy x%.3f, damage x%.3f, mark of darkness %s",
				bonus.getAccuracy(), bonus.getDamage(),
				gearBonuses.hasMarkOfDarkness() ? "UP" : "down"));
			// Named even when it is nought, so "this target has none" reads differently from "the spell is the wrong element for
			// it".
			final MonsterStatsProvider.MonsterStats weak = monsters.get(npcId);
			lines.add(String.format("Elemental weakness: target %s, this spell gets %d%% (+%d max)",
				weak == null || weak.getWeaknessElement() == null ? "none"
					: weak.getWeaknessElement() + " " + weak.getWeaknessSeverity() + "%",
				elementalWeakness(npcId), elementalWeaknessBonus(npcId)));
		}

		// Which prayers are actually up, by name. "prayed 0/n" with augury held all fight turned out to be Mystic Lore really
		// being on, and the figures named the prayer only to someone who knew the table.
		final StringBuilder up = new StringBuilder();
		for (Prayer prayer : Prayer.values())
		{
			if (prayerActive(prayer))
			{
				up.append(up.length() == 0 ? "" : ", ").append(prayer);
			}
		}
		lines.add(String.format("Prayers up: %s (goal %s, %s)",
			up.length() == 0 ? "none" : up.toString(), prayerGoal(),
			hasOffensivePrayer() ? "met" : "NOT met"));

		final int spec = specialAttackMaxHit(npcId);
		if (spec > 0)
		{
			lines.add("Spec max hit " + spec);
		}
		final Skill main = attackStyle().getAttackType() == AttackType.MAGIC ? Skill.MAGIC
			: attackStyle().getAttackType() == AttackType.RANGED ? Skill.RANGED : Skill.STRENGTH;
		final int base = client.getRealSkillLevel(main);
		lines.add(String.format("Efficiency goal: %s, %s at %d (+%d)",
			prayerGoal(), main, base + maxBoost(main, base), maxBoost(main, base)));

		// Kept for bug reports: these raw values are what expose a misread. Every raid varbit that might say "I am in a
		// raid", so the ones that clear on leaving can be told from the ones that do not - ToA's raid level and ToB's
		// progress both persist.
		lines.add(String.format("(raid: cox indungeon %d, cox timer %d, tob progress %d,"
				+ " tob wave %d/%d, tob party %d, toa level %d, toa partystatus %d)",
			client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON),
			client.getVarbitValue(VarbitID.RAIDS_TIMER),
			client.getVarbitValue(VarbitID.TOB_PROGRESS),
			client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_VAL),
			client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_MAX),
			client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS),
			client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL),
			client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS)));
		// Two more that may separate a lobby from a running raid, and may explain why the level will not refresh without a
		// relog.
		lines.add(String.format("(toa path %d, toa level frozen %d)",
			client.getVarbitValue(VarbitID.TOA_CLIENT_CURRENT_PATH),
			client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL_STOP_AUTO_UPDATING)));
		lines.add(String.format("(instanced %b, toa raid level varbit %d, stale unless instanced)",
			client.isInInstancedRegion(),
			client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL)));
		lines.add(String.format("(category varbit %d, com mode %d, cox tier %d, cox timer %d, salts %d)",
			client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY),
			client.getVarpValue(VarPlayerID.COM_MODE),
			client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIER),
			client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIMER),
			client.getVarbitValue(VarbitID.STATRENEWAL_POTION_TIMER)));
		return lines;
	}

	/** The equipped weapon's name, or null when unarmed. */
	private String computeWeaponName()
	{
		final int weapon = weaponItemId();
		return weapon < 0 ? null : itemManager.getItemComposition(weapon).getName();
	}

	/** The equipped weapon's item id, or -1 when unarmed. */
	int equippedWeaponId()
	{
		return equippedItemId(EquipmentInventorySlot.WEAPON);
	}

	/**
	 * Whether the combat option attacks in melee, which decides where an
	 * attack's tick can be read from: melee lands on the tick it is thrown.
	 */
	boolean isMeleeEquipped()
	{
		return attackStyle().getAttackType().isMelee();
	}

	private int weaponItemId()
	{
		return equippedWeaponId();
	}

	private int equippedItemId(EquipmentInventorySlot slot)
	{
		return gear().id(slot);
	}

	private double meleePrayer()
	{
		if (mode != REAL)
		{
			return prayerGoal().getStrengthMultiplier();
		}
		if (prayerActive(Prayer.RP_DECIMATE))
		{
			return 1.27;
		}
		if (prayerActive(Prayer.RP_ANCIENT_STRENGTH))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.PIETY))
		{
			return 1.23;
		}
		if (prayerActive(Prayer.CHIVALRY))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.ULTIMATE_STRENGTH))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.SUPERHUMAN_STRENGTH))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.BURST_OF_STRENGTH))
		{
			return 1.05;
		}
		return 1.0;
	}

	// Expected damage if the attack had been set up properly: the configured prayer up and stats at full boost, everything
	// else exactly as it is.
	double idealAverageHit(int npcId)
	{
		return averageHitWith(bestGear(npcId), npcId, IDEAL);
	}

	/**
	 * Whether a switch was available and not made. Compared by contents, never
	 * by identity: the answer is held across ticks while the worn loadout is
	 * rebuilt each one, so the same gear is two objects a tick later.
	 */
	boolean missedGearSwitch(int npcId)
	{
		return !bestGear(npcId).sameItems(gear());
	}

	// The best gear against this target, held until the weapon, the combat style or what the player has on them moves.
	// Deliberately not the worn items: switching into the answer must not throw it away, or the search runs on every
	// attack of a fight fought properly. Not keyed on the spell either, which changes the figure but rarely the gear that
	// maximises it.
	private Loadout bestGear(int npcId)
	{
		final Loadout worn = gear();
		final int weaponId = worn.id(EquipmentInventorySlot.WEAPON);
		final AttackStyle style = attackStyle();
		final long available = availableEquippableSignature();
		if (memoBestGear != null && memoBestGearNpc == npcId && memoBestGearWeapon == weaponId
			&& memoBestGearStyle == style && memoBestGearAvailable == available)
		{
			// Kept only while it still beats what is worn. The search is greedy, so an answer reached from one set is not
			// guaranteed to beat a different set reached later - void put on after the fact is exactly that - and a stale answer
			// would mark a player down for doing the right thing.
			if (!memoBestGear.sameItems(worn)
				&& averageHitWith(worn, npcId, IDEAL)
				> averageHitWith(memoBestGear, npcId, IDEAL) - GearSearch.MEANINGFUL)
			{
				memoBestGear = worn;
			}
			return memoBestGear;
		}
		final ItemEquipmentStats weapon = weaponStats();
		memoBestGearNpc = npcId;
		memoBestGearWeapon = weaponId;
		memoBestGearStyle = style;
		memoBestGearAvailable = available;
		memoBestGear = GearSearch.best(worn, carriedCandidates(), weapon != null && weapon.isTwoHanded(),
			candidate -> averageHitWith(candidate, npcId, IDEAL));
		return memoBestGear;
	}

	// Everything carried that could be equipped, paired with its slot. The bank is deliberately out: the question is what
	// could have been switched to without leaving.
	private List<GearSearch.Candidate> carriedCandidates()
	{
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return Collections.emptyList();
		}
		final List<GearSearch.Candidate> candidates = new ArrayList<>();
		for (Item item : inventory.getItems())
		{
			final ItemEquipmentStats stats = equipmentStats(item.getId());
			if (stats == null)
			{
				continue;
			}
			final EquipmentInventorySlot slot = SLOT_BY_INDEX.get(stats.getSlot());
			if (slot != null)
			{
				candidates.add(new GearSearch.Candidate(slot, item.getId()));
			}
		}
		return candidates;
	}

	/**
	 * Expected damage for the attack as it was really set up. A prayer flicked
	 * within the tick counts as held: it was up while the server resolved it.
	 */
	double actualAverageHit(int npcId, boolean prayerHeld)
	{
		return averageHitAs(npcId, prayerHeld ? PRAYER_HELD : REAL);
	}

	private double averageHitAs(int npcId, int as)
	{
		mode = as;
		try
		{
			return averageHit(npcId);
		}
		finally
		{
			mode = REAL;
		}
	}

	/**
	 * The level a skill rolls at. Computing the ideal, this is the base plus the
	 * full boost of the potion expected here, so a decayed or undrunk dose shows
	 * up as the damage it costs.
	 */
	private int boostedLevel(Skill skill)
	{
		if (mode != IDEAL)
		{
			return client.getBoostedSkillLevel(skill);
		}
		final int base = client.getRealSkillLevel(skill);
		return base + maxBoost(skill, base);
	}

	// The boost this content's potion gives, applied to every stat the style rolls on - attack as well as strength for
	// melee - so the accuracy a dose buys is counted and not just the damage.
	private int maxBoost(Skill skill, int base)
	{
		// What the player says they meant to bring. A raid was assumed to supply its own dose, which is wrong from the moment
		// one starts: you enter with what you carried. The Chambers tier varbit survives the raid that set it.
		int boost;
		if (skill == Skill.RANGED)
		{
			boost = config.rangedBoostGoal().boost(base);
		}
		else if (skill == Skill.MAGIC)
		{
			boost = config.magicBoostGoal().boost(base);
		}
		else
		{
			boost = config.meleeBoostGoal().boost(skill, base);
		}
		// Raised if something stronger is up, never lowered: taking the standard from what the player happens to hold would
		// let one who took nothing read as perfectly dosed.
		if (client.getVarbitValue(VarbitID.STATRENEWAL_POTION_TIMER) > 0)
		{
			boost = Math.max(boost, base * 16 / 100 + 11);
		}
		if (client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIMER) > 0)
		{
			boost = Math.max(boost, chambersOverloadBoost(base));
		}
		return boost;
	}

	// The Chambers overload the player brewed. The tiers are far apart - at 99 they give 13, 17 and 21
	// - so treating them alike misjudges the standard.
	private int chambersOverloadBoost(int base)
	{
		switch (client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIER))
		{
			case 1: // Overload (-)
				return base / 10 + 4;
			case 3: // Overload (+)
				return base * 16 / 100 + 6;
			default: // Overload
				return base * 13 / 100 + 5;
		}
	}

	/** The prayer the player means to be using for the style in hand. */
	private PrayerChoice prayerGoal()
	{
		switch (attackStyle().getAttackType())
		{
			case MAGIC:
				return config.magicPrayerGoal();
			case RANGED:
				return config.rangedPrayerGoal();
			default:
				return config.meleePrayerGoal();
		}
	}

	// Whether the prayer that was up is at least the one intended for the style.
	boolean hasOffensivePrayer()
	{
		final PrayerChoice goal = prayerGoal();
		final double attack;
		final double strength;
		switch (attackStyle().getAttackType())
		{
			case MAGIC:
				attack = magicAccuracyPrayer();
				strength = 1.0;
				break;
			case RANGED:
				attack = rangedAccuracyPrayer();
				strength = rangedPrayer();
				break;
			default:
				attack = meleeAccuracyPrayer();
				strength = meleePrayer();
				break;
		}
		return attack >= goal.getAttackMultiplier() && strength >= goal.getStrengthMultiplier();
	}

	// Whether every combat stat the current style rolls on is at the full boost the best potion here would give.
	boolean isPotted()
	{
		switch (attackStyle().getAttackType())
		{
			case MAGIC:
				return isPotted(Skill.MAGIC);
			case RANGED:
				return isPotted(Skill.RANGED);
			default:
				return isPotted(Skill.ATTACK) && isPotted(Skill.STRENGTH);
		}
	}

	private boolean isPotted(Skill skill)
	{
		final int base = client.getRealSkillLevel(skill);
		return client.getBoostedSkillLevel(skill) >= base + maxBoost(skill, base);
	}

	// Whether a prayer was active for what the server is resolving now. Reads the varbit directly; Client.isPrayerActive
	// is deprecated with no replacement.
	private boolean prayerActive(Prayer prayer)
	{
		final int varbit = prayer.getVarbit();
		// The server's copy, always. Clicking flips the client's copy at once so the orb responds without waiting, which
		// answers for the click rather than the attack: flick off at the end of a tick and the client reads off while the
		// server resolved the attack with it up.
		return client.getServerVarbitValue(varbit) == 1;
	}

	// Magic attack prayer multiplier.
	private double magicAccuracyPrayer()
	{
		if (mode != REAL)
		{
			return prayerGoal().getAttackMultiplier();
		}
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_VAPORISE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_WILL))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.AUGURY))
		{
			return 1.25;
		}
		if (prayerActive(Prayer.MYSTIC_VIGOUR))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.MYSTIC_MIGHT))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.MYSTIC_LORE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.MYSTIC_WILL))
		{
			return 1.05;
		}
		return 1.0;
	}

	/**
	 * Magic damage from prayer, in percent: unlike the other styles, some magic
	 * prayers add damage as well as accuracy.
	 */
	private double magicDamagePrayerPercent()
	{
		if (mode != REAL)
		{
			return prayerGoal().getMagicDamagePercent();
		}
		if (prayerActive(Prayer.AUGURY))
		{
			return 4.0;
		}
		if (prayerActive(Prayer.RP_VAPORISE))
		{
			return 4.0;
		}
		if (prayerActive(Prayer.RP_ANCIENT_WILL))
		{
			return 3.0;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 2.0;
		}
		if (prayerActive(Prayer.MYSTIC_VIGOUR))
		{
			return 3.0;
		}
		if (prayerActive(Prayer.MYSTIC_MIGHT))
		{
			return 2.0;
		}
		if (prayerActive(Prayer.MYSTIC_LORE))
		{
			return 1.0;
		}
		// Mystic Will is deliberately absent: the wiki's magic damage table lists Lore, Might, Vigour and Augury only.
		return 0.0;
	}

	/** Ranged attack prayer multiplier. Rigour gives less here than it does to strength. */
	private double rangedAccuracyPrayer()
	{
		if (mode != REAL)
		{
			return prayerGoal().getAttackMultiplier();
		}
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_ANNIHILATE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_SIGHT))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.RIGOUR))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.DEADEYE))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.EAGLE_EYE))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.HAWK_EYE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.SHARP_EYE))
		{
			return 1.05;
		}
		return 1.0;
	}

	private double rangedPrayer()
	{
		if (mode != REAL)
		{
			return prayerGoal().getStrengthMultiplier();
		}
		if (prayerActive(Prayer.RP_ANNIHILATE))
		{
			return 1.27;
		}
		if (prayerActive(Prayer.RP_ANCIENT_SIGHT))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.RIGOUR))
		{
			return 1.23;
		}
		if (prayerActive(Prayer.DEADEYE))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.EAGLE_EYE))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.HAWK_EYE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.SHARP_EYE))
		{
			return 1.05;
		}
		return 1.0;
	}

	/** Sum of the melee (str) or ranged (rstr) strength bonus across worn gear. */
	// Sums the strength bonus across worn gear.
	/**
	 * The bows and crossbows that make their own ammunition, so the ammo slot is
	 * only being carried rather than drawn from.
	 */
	private static final Set<Integer> AMMOLESS_BOWS = Collections.unmodifiableSet(
		new HashSet<>(Arrays.asList(
			ItemID.CRYSTAL_BOW, ItemID.CRYSTAL_BOW_2500,
			ItemID.BOW_OF_FAERDHINEN, ItemID.BOW_OF_FAERDHINEN_INFINITE,
			ItemID.BOW_OF_FAERDHINEN_INFINITE_ITHELL, ItemID.BOW_OF_FAERDHINEN_INFINITE_IORWERTH,
			ItemID.BOW_OF_FAERDHINEN_INFINITE_TRAHAEARN, ItemID.BOW_OF_FAERDHINEN_INFINITE_CADARN,
			ItemID.WILD_CAVE_WEBWEAVER_CHARGED)));

	/**
	 * Whether anything worn in the ammo slot actually feeds this weapon.
	 *
	 * <p>A blowpipe keeps its darts inside itself, a chinchompa and a thrown
	 * weapon ARE the missile, and a crystal bow makes its own arrows - so in all
	 * of those the ammo slot is just being carried and the game does not roll it.
	 * Summing it anyway is what made a blowpipe read a max hit of 37 where it
	 * should have read about 16: dragon bolts left in the slot are +122 ranged
	 * strength, and they went straight into the max hit. Ranged ATTACK looked
	 * right the whole time, which is what made it hard to see - bolts carry
	 * ranged strength and no ranged attack at all.
	 *
	 * <p>The eclipse atlatl is the exception that stops this being a rule about
	 * thrown weapons: it is thrown, and its darts really do sit in the ammo slot.
	 */
	private boolean ammoSlotFeedsWeapon()
	{
		if (attackStyle().getAttackType() != AttackType.RANGED)
		{
			// Ammunition carries no melee or magic bonus, so nothing changes hands.
			return true;
		}
		final int weapon = weaponItemId();
		if (weapon == ItemID.ECLIPSE_ATLATL || weapon == ItemID.BR_ECLIPSE_ATLATL)
		{
			return true;
		}
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.THROWN || category == WeaponCategory.CHINCHOMPAS)
		{
			return false;
		}
		return !AMMOLESS_BOWS.contains(weapon);
	}

	private int computeEquipmentBonus(boolean melee)
	{
		final Loadout gear = gear();
		final boolean skipAmmo = !ammoSlotFeedsWeapon();
		int total = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (skipAmmo && slot == EquipmentInventorySlot.AMMO)
			{
				continue;
			}
			final ItemEquipmentStats e = equipmentStats(gear.id(slot));
			if (e != null)
			{
				total += melee ? e.getStr() : e.getRstr();
			}
		}
		if (!melee)
		{
			final ItemEquipmentStats dart = blowpipeDart();
			if (dart != null)
			{
				total += dart.getRstr();
			}
		}
		return total;
	}

	/** Which of the three combat styles the selected combat option attacks with. */
	private Style weaponStyle()
	{
		switch (attackStyle().getAttackType())
		{
			case RANGED:
				return Style.RANGED;
			case MAGIC:
				return Style.MAGIC;
			default:
				return Style.MELEE;
		}
	}
}
