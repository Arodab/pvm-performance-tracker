package com.pvmperformance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
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

	// A manual cast counts for a few ticks past the click, so it survives the
	// gap between casts while the player keeps going.
	private static final int MANUAL_CAST_TICKS = 8;
	// Clicks that may be outstanding at once. Two covers a click made while the
	// previous cast is still in the air; the third is slack.
	private static final int MAX_PENDING_CASTS = 3;
	// Slot index back to the slot, for reading the slot an inventory item goes
	// in off its equipment stats. Not every index is a real slot.
	private static final Map<Integer, EquipmentInventorySlot> SLOT_BY_INDEX = new HashMap<>();

	static
	{
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			SLOT_BY_INDEX.put(slot.getSlotIdx(), slot);
		}
	}

	// The weapon the combat tab heading was last seen describing, so a heading
	// that has not caught up with a swap can be told from one that has.
	private int headingWeapon = Integer.MIN_VALUE;
	private String headingText;
	private Spell manualCastSpell;
	private int manualCastTick = Integer.MIN_VALUE;
	// Clicks made but not yet seen leaving as a cast.
	private int manualCastsPending;
	// Whether the attack currently being described was proven by a hitsplat.
	private boolean meleeProvenAttack;
	// Which setup the level and prayer reads should answer for. REAL is what the
	// player had; IDEAL is the intended prayer at full boost; PRAYER_HELD is the
	// intended prayer at the real boost, used when a prayer was flicked and so
	// applied to the attack even though it reads as off by the tick's end.
	private static final int REAL = 0;
	private static final int IDEAL = 1;
	private static final int PRAYER_HELD = 2;
	private int mode = REAL;
	// The index of the NPC the figures are being computed against, so the
	// defence read can account for what has been drained off it.
	private int targetIndex = -1;
	// The enemy a miss has armed the confliction gauntlets against, or -1. Held
	// per enemy because the effect is spent on the next attack against that
	// same one, and dropped by switching target.
	private int conflictionArmedIndex = -1;
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

	// Held for the tick. Equipment, varbits and the combat option cannot change
	// within one, and a single attack tick asks for the gear multipliers a dozen
	// times over. The ideal figures share it safely: swapping in the intended
	// prayer and a full boost changes levels, not gear.
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
	// The gear search's answer. Outlives the tick memo on purpose — the search
	// is the one expensive thing in this class, and what it depends on is the
	// worn and carried items and the target, none of which is a tick.
	private int memoBestGearNpc = Integer.MIN_VALUE;
	private Loadout memoBestGear;
	// The equippable items carried when the search last ran.
	private long lastCarriedSignature;

	/** Drops everything held once the tick it was worked out for has passed. */
	// Dropped when the worn items change as well as when the tick does. A tick
	// was assumed to be the smallest unit anything could change in, which is
	// true of varbits and the combat option but not of equipment: a weapon
	// swapped part way through a tick left every figure already worked out that
	// tick answering for the weapon before it, so the overlay showed one
	// weapon's max hit beside the other's accuracy for a tick after every swap.
	void invalidateGear()
	{
		memoTick = Integer.MIN_VALUE;
		memoBestGear = null;
	}

	/**
	 * The carried items changed. The search behind the best-gear figure is only
	 * dropped if what changed could actually be worn.
	 *
	 * <p>This fires on every inventory change, which means every bite and every
	 * sip — and a dose drunk changes the item's id, so comparing ids alone would
	 * still throw the answer away. Only equippable ids go into the signature, so
	 * food and potions fall out of it and a search survives a trip's worth of
	 * eating.
	 */
	void invalidateInventory()
	{
		final long carried = carriedEquippableSignature();
		if (carried != lastCarriedSignature)
		{
			lastCarriedSignature = carried;
			memoBestGear = null;
		}
	}

	// Order-independent, so items shuffled around the inventory are not a
	// change. Cheap next to the search it avoids: one stats lookup an item
	// against a search that evaluates hundreds of loadouts.
	private long carriedEquippableSignature()
	{
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return 0;
		}
		long signature = 0;
		for (Item item : inventory.getItems())
		{
			if (equipmentStats(item.getId()) != null)
			{
				signature += (long) item.getId() * 2654435761L;
			}
		}
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

	// The gear every figure here is worked out for. One object rather than a
	// container read per question, so that asking what a different loadout
	// would do is the same calculation with a different argument.
	private Loadout gear()
	{
		expireMemo();
		if (memoLoadout == null)
		{
			memoLoadout = Loadout.worn(itemManager, client.getItemContainer(InventoryID.WORN));
		}
		return memoLoadout;
	}

	// Points every figure at a different loadout. Everything derived from the
	// gear has to go with it, or the swap is half applied: the bonuses and the
	// max hit are memoised per tick and would otherwise still describe the
	// loadout that was in place when they were first asked for.
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

	// Expected damage for a loadout that is not being worn. Restores what was
	// there afterwards, memo included, so a search leaves no trace.
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
	 * Held per mode rather than once. The levels and prayers behind it are
	 * substituted while working out what an attack should have been, so the real
	 * and the intended figures are genuinely different answers.
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

	/**
	 * Expected hit chance (0..1) vs the NPC, or -1 when there is no data or the
	 * style isn't modelled yet (magic).
	 */
	double hitChance(int npcId)
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
			return meleeHitChance(style, type, npc, gear);
		}
		if (type == AttackType.RANGED)
		{
			return rangedHitChance(style, npc, gear);
		}
		return magicHitChance(style, npc, gear, npcId);
	}

	/** How much of a hit the target keeps, for the few that shrug most of it off. */
	private double mitigation(int npcId)
	{
		return RaidScaling.damageTaken(npcId, attackStyle().getAttackType());
	}

	double averageHit(int npcId)
	{
		final double accuracy = hitChance(npcId);
		// Averages, not best cases: a keris crit or an ahrim's proc raises the max
		// hit but only lifts sustained damage by a few percent.
		final double averageMax = (baseMaxHit() * gearBonus(npcId).getExpectedDamage()
			+ colossalBladeBonus(npcId)) * mitigation(npcId);
		if (accuracy < 0 || averageMax <= 0)
		{
			return -1;
		}
		final int cap = damageCap(npcId);
		final double perHit = cap == Integer.MAX_VALUE
			? accuracy * (averageMax / 2.0)
			: accuracy * cappedAverage((int) averageMax, cap);
		// A scythe swing is several hits, each rolled on its own, so what one
		// attack is expected to deal is their sum and not the first of them.
		final double ordinary = perHit * scytheDamageShare(npcId);

		final EnchantedBolt bolt = loadedBolt(npcId);
		if (bolt == null)
		{
			return ordinary;
		}
		final double procChance = bolt.chance(hasKandarinHardDiary());
		switch (bolt)
		{
			case RUBY:
				// Fires regardless of the accuracy roll and replaces the hit
				// entirely, so the two outcomes are weighted rather than scaled.
				return procChance * EnchantedBolt.rubyDamage(targetCurrentHp(npcId))
					+ (1.0 - procChance) * ordinary;
			case DIAMOND:
				// Also ignores accuracy, but rolls damage normally against a
				// raised max, so a proc always lands.
				return procChance * (1.15 * averageMax / 2.0) + (1.0 - procChance) * ordinary;
			default:
				// Onyx leeches from damage actually dealt, so it needs a hit first.
				return ordinary * (1.0 + 0.20 * procChance);
		}
	}

	/**
	 * The enchanted bolt loaded for this attack, or null when none applies -
	 * the style isn't ranged, the weapon isn't a crossbow, or onyx is loaded
	 * against undead, which have no life to leech.
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
	 * The target's health right now, which the ruby bolt effect is a share of.
	 * Falls back to its full health when no health bar is showing.
	 */
	private int targetCurrentHp(int npcId)
	{
		final Integer maxHp = npcManager.getHealth(npcId);
		if (maxHp == null || maxHp <= 0)
		{
			return 0;
		}
		final Player player = client.getLocalPlayer();
		final Actor target = player == null ? null : player.getInteracting();
		if (!(target instanceof NPC))
		{
			return maxHp;
		}
		final NPC npc = (NPC) target;
		final int ratio = npc.getHealthRatio();
		final int scale = npc.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return maxHp;
		}
		return Math.max(0, maxHp * ratio / scale);
	}

	/**
	 * Defence level as the raid leaves it. The monster data carries raid level 0
	 * stats, so unscaled figures read too high, and wrong in the flattering
	 * direction: the harder the invocations, the better the player looks.
	 */
	private int defenceLevel(MonsterStatsProvider.MonsterStats npc)
	{
		// What the target has left, not what it started with. Warhammers and
		// godswords take real levels off it, and a model that keeps reading the
		// book figure reports the player as less accurate than they were.
		return Math.max(0, raidScaled(npc) - drain.drainedFrom(targetIndex));
	}

	/** The NPC the expected figures are being asked about, for the drain lookup. */
	void setTargetIndex(int npcIndex)
	{
		this.targetIndex = npcIndex;
	}

	// ToA raid level on a monster stat: every five levels add 2%, additively, so
	// 300 is +120% and 500 is +200%. Defence level only. Magic level does not
	// scale, nor do defence bonuses, which are armour rather than levels.
	// Chambers scaling is in RaidScaling.
	private int raidScaled(MonsterStatsProvider.MonsterStats npc)
	{
		return RaidScaling.defence(client, npc.getDefenceLevel(), npc.getName(), partyHitpoints.highest());
	}

	private double meleeHitChance(AttackStyle style, AttackType type, MonsterStatsProvider.MonsterStats npc, double gear)
	{
		final int effAtk = (int) Math.floor(boostedLevel(Skill.ATTACK) * meleeAccuracyPrayer())
			+ style.attackLevelBonus() + 8;
		final int attRoll = attackRoll(effAtk, attackBonus(type), gear);
		final int defBonus = type == AttackType.STAB ? npc.getDefStab()
			: type == AttackType.SLASH ? npc.getDefSlash() : npc.getDefCrush();
		final int defRoll = (defenceLevel(npc) + 9) * (defBonus + 64);
		return meleeHitChanceFrom(attRoll, defRoll);
	}

	/**
	 * Magic rolls against the target's magic level, not its defence level, and
	 * starts from +9 rather than the +8 melee and ranged use. The 30/70 blend is
	 * the player rule; a monster's magic defence is its Magic stat and its magic
	 * defence bonus alone (wiki: Magic).
	 */
	private double magicHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc, double gear,
		int npcId)
	{
		final int effMagic = (int) Math.floor(boostedLevel(Skill.MAGIC) * magicAccuracyPrayer())
			+ style.attackLevelBonus() + 9;
		final int attRoll = attackRoll(effMagic, attackBonus(AttackType.MAGIC), gear);
		final int magic = RaidScaling.magic(client, npcId, npc.getMagicLevel(), npc.getName(),
			partyHitpoints.highest());
		final int defRoll = (magic + 9) * (npc.getDefMagic() + 64);
		if (!hasConflictionGauntlets())
		{
			return hitChanceFrom(attRoll, defRoll);
		}
		// The real chance for the attack about to be thrown, not the long-run
		// average of them. The gauntlets roll accuracy twice after a miss, so
		// every attack is at one of exactly two chances, and which one is known:
		// the previous attack either missed this enemy or it did not.
		//
		// This used to return the steady state, the share of attacks that would
		// carry the bonus over a long fight, which is right in aggregate and
		// wrong for every individual attack — and it is individual attacks that
		// the expected damage and expected hits are summed from.
		//
		// "Against the same enemy" is the wiki's wording and is why this is
		// armed against an index rather than a flag: switching target drops it.
		// Both have to name a real enemy. No fight means targetIndex is -1, and
		// unarmed means conflictionArmedIndex is -1, so comparing them bare made
		// the two nothings match and left the doubled roll showing for good once
		// a boss died.
		final boolean armed = conflictionArmedIndex >= 0 && conflictionArmedIndex == targetIndex;
		return armed
			? 1.0 - sharedDefenceMissChance(attRoll, defRoll)
			: hitChanceFrom(attRoll, defRoll);
	}

	/**
	 * Notes how a magic attack of mine ended, which is what says whether the
	 * next one against that enemy rolls twice. A miss arms the gauntlets and a
	 * hit spends them; the effect does not stack across consecutive misses,
	 * so arming twice is the same as arming once.
	 */
	void noteMagicResolved(int npcIndex, boolean missed)
	{
		conflictionArmedIndex = missed ? npcIndex : -1;
	}

	/** Whether the gauntlets are in play at all, so the caller can skip the rest. */
	boolean usesConflictionGauntlets()
	{
		return hasConflictionGauntlets() && attackStyle().getAttackType() == AttackType.MAGIC;
	}

	/**
	 * Whether the confliction gauntlets are worn and able to work: their effect
	 * is disabled entirely by a two-handed weapon.
	 */
	private boolean hasConflictionGauntlets()
	{
		if (equippedItemId(EquipmentInventorySlot.GLOVES) != ItemID.CONFLICTION_GAUNTLETS)
		{
			return false;
		}
		final ItemEquipmentStats weapon = weaponStats();
		return weapon == null || !weapon.isTwoHanded();
	}

	private double rangedHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc, double gear)
	{
		final int effRanged = (int) Math.floor(boostedLevel(Skill.RANGED) * rangedAccuracyPrayer())
			+ style.attackLevelBonus() + 8;
		final int attRoll = attackRoll(effRanged, attackBonus(AttackType.RANGED), gear);
		final int defRoll = (defenceLevel(npc) + 9) * (npc.getDefRanged() + 64);
		return hitChanceFrom(attRoll, defRoll);
	}

	/**
	 * The attack roll, floored at zero. A bonus below -64 turns the bonus+64 term
	 * negative, and a negative roll comes back from hitChanceFrom as a negative
	 * chance, which every caller reads as "no figure" rather than the near zero
	 * it is. Casting from melee gear reaches it easily, around -70 magic attack.
	 */
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

	// Melee hit chance, with the fang's second accuracy roll. Inside ToA the
	// defence roll is re-rolled too, making the attempts independent; outside,
	// both attack rolls are compared against one defence roll.
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

	// Chance two attack rolls both fail against the same defence roll. For a
	// fixed d one attack misses at (d+1)/(attRoll+1), so two miss at the square;
	// averaging that over d gives the closed forms, the sum of squares becoming
	// the (d+2)(2d+3)/6 term. Past attRoll every d misses outright.
	// Checked against brute-force enumeration.
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

	// The selected combat option: the weapon's category paired with the option
	// index. Never null; unknown categories fall back to fallbackStyle().
	// Category from the name on the combat tab, falling back to the varbit id.
	// The name wins because the ids have drifted: powered staff reports 24,
	// which this table had as banner, so tridents resolved as melee weapons.
	private WeaponCategory weaponCategory()
	{
		final String heading = combatTabCategory();
		final int weapon = weaponItemId();
		// The combat tab lags the equipment by a tick. On the tick a weapon is
		// swapped the heading still names the old one, and pairing it with the
		// new weapon's bonuses gives a figure belonging to neither: a whip read
		// through a warhammer's crush category has no attack bonus at all, and
		// accuracy halved for one tick in the middle of every switch.
		//
		// A heading that has not changed while the weapon has is therefore stale
		// rather than wrong, and is dropped. What answers instead is the
		// weapon's own dominant attack bonus, by way of fallbackStyle, which is
		// right for the weapon actually in hand.
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
		return stale ? null
			: WeaponCategory.forVarbit(client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY));
	}

	// The combat tab's category heading, e.g. "Category: Whip", or null.
	// The text is null-checked as well as the widget: an interface can exist
	// before its text is set, and Text.removeTags throws on null.
	// For the trace only: what the category resolved to this tick.
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
	 * Notes that the player clicked a spell onto an NPC. A manual cast overrides
	 * whatever the weapon would otherwise do, so a trident held while barraging
	 * reports the barrage rather than the trident's own attack.
	 */
	void recordManualCast(Spell spell)
	{
		// A click arriving after the previous one has lapsed starts the count
		// over rather than adding to a cast that never happened.
		if (activeManualCast() == null)
		{
			manualCastsPending = 0;
		}
		manualCastSpell = spell;
		manualCastTick = client.getTickCount();
		// Capped: spamming the spell icon queues clicks the game will never turn
		// into casts, and each one would otherwise have to be spent before the
		// weapon could be read again.
		manualCastsPending = Math.min(MAX_PENDING_CASTS, manualCastsPending + 1);
	}

	/**
	 * The manually cast spell, if one was cast recently enough to still be what
	 * the player is doing. Casting takes 5 ticks, so a window slightly wider than
	 * that stays lit while the player keeps clicking and lapses back to the
	 * weapon once they stop.
	 */
	private Spell activeManualCast()
	{
		if (manualCastSpell == null || client.getTickCount() - manualCastTick > MANUAL_CAST_TICKS)
		{
			return null;
		}
		return manualCastSpell;
	}

	// How the attack about to be described was proven. A hitsplat can only be a
	// melee blow, and a melee blow is never a cast whatever is queued.
	void noteAttackKind(boolean fromProjectile)
	{
		if (meleeProvenAttack != fromProjectile)
		{
			return; // already set the way this attack needs it
		}
		meleeProvenAttack = !fromProjectile;
		memoStyle = null; // the style turns on this
	}

	// Spends the manual cast once a cast has actually gone out under it. The
	// timer alone cannot end it: it has to outlast the click-to-cast delay, and
	// a window that wide goes on claiming the weapon's own attacks afterwards.
	void noteAttackThrown()
	{
		if (meleeProvenAttack || activeManualCast() == null)
		{
			manualCastsPending = 0;
			return;
		}
		// One cast leaves per click and the two are several ticks apart, so more
		// than one click can be outstanding at once.
		if (--manualCastsPending <= 0)
		{
			manualCastsPending = 0;
			manualCastSpell = null;
		}
	}

	/** The spell being cast, manual taking priority over autocast. */
	/**
	 * Whether the attack in hand lands without a projectile, which is the
	 * ancient area spells. Their attacks cannot be booked from a projectile
	 * because the client never shows one, so the hitsplat has to serve.
	 */
	// Names the spell in the "cast UNKNOWN ANIM" line, which is how the cast
	// animation list gets extended. Kept for that.
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
		// A spell clicked onto an NPC is a magic attack whatever is held, so it is
		// settled before the weapon is consulted. The combat option does not
		// enter it: a manual cast is accurate whatever the weapon is set to.
		if (!meleeProvenAttack && activeManualCast() != null)
		{
			return new AttackStyle(varp, "Manual cast", AttackType.MAGIC, CombatStyle.ACCURATE);
		}
		// A staff recognised by name attacks with magic, whatever the category
		// table claims. The table's ids have gone stale against the live game,
		// and trusting it sent tridents down the melee path entirely.
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
	 * Best guess for a weapon category we don't have a table for: the attack
	 * type comes from the weapon's dominant attack bonus and the combat style
	 * from the layout the great majority of categories share.
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
	 * The dart loaded in the blowpipe, or null when a blowpipe isn't held. The
	 * game doesn't say which dart it holds, so this comes from the config.
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
		// Held onto rather than looked up again: this is reached several times a
		// tick, and the answer only changes when the setting does.
		if (dart != cachedDart)
		{
			final ItemStats stats = itemManager.getItemStats(dart.getItemId());
			cachedDartStats = stats == null ? null : stats.getEquipment();
			cachedDart = dart;
		}
		return cachedDartStats;
	}

	// The equipment stats of one item, or null for an empty slot or an item
	// carrying none.
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
		int total = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
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
			// The shadow multiplies the magic accuracy of everything else worn,
			// on the same terms as the damage: its built-in spell only. A cast
			// made while holding it is an ordinary cast.
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

	/**
	 * How many ticks the current weapon takes between attacks, which is the
	 * cadence the tick-loss count measures against.
	 */
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
	 * staves and salamanders, which fire their own attack rather than a spell.
	 * Everything else casts in 5 ticks, or 4 for the harmonised staff on the
	 * standard spellbook.
	 */
	private int castSpeedTicks(int weaponTicks)
	{
		final WeaponCategory category = weaponCategory();
		// A manual cast runs on the spell's clock even from a powered staff, so
		// a trident being barraged off is 5 ticks, not the trident's own speed.
		// Recognising the staff by name matters here too: going by category
		// alone put an unmapped one on the 5 tick spell clock.
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
	 * Max hit for the current loadout against this target, or 0 if unavailable.
	 * The target matters because salve, dragon hunter, demonbane and the rest
	 * only apply against the monsters they are meant for; pass -1 for the figure
	 * before any target-dependent gear effects.
	 */
	int maxHit(int npcId)
	{
		// What actually lands, which against Olm's hands is a third of it when the
		// style is the wrong one for that hand.
		return Math.min(damageCap(npcId),
			(int) (unmitigatedMaxHit(npcId) * mitigation(npcId)));
	}

	/**
	 * The most a single hit may deal against this target, whatever the roll.
	 *
	 * <p>A cap is not a smaller max hit and must not be modelled as one: rolling
	 * nought to sixty and capping at nine averages a little over eight, where
	 * half of nine is four and a half. {@link #cappedAverage} is what the
	 * expected damage uses.
	 *
	 * <p>The Hueycoatl's tail is the only one known: nine while the player's
	 * crush attack bonus is their highest, four otherwise, which is what makes
	 * that phase drag for a team without a crush weapon.
	 */
	/**
	 * How many times one attack lands on this target. A scythe swing hits twice
	 * against a 2x2 and three times against anything 3x3 or larger, each hit
	 * rolling its own accuracy and strength.
	 *
	 * <p>Only hits on the *same* target are counted here. A scythe also reaches
	 * along a 1x3 arc into whatever stands beside the target, and those are
	 * separate NPCs — that is the AoE gap listed under known-wrong, not this.
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

	// Each hit deals half the one before, so two hits are worth 1.5 of the
	// first and three are worth 1.75. The wiki's odd-max-hit rounding is not
	// modelled; it moves the total by well under a point.
	private double scytheDamageShare(int npcId)
	{
		switch (hitsPerAttack(npcId))
		{
			case 3:
				return 1.75;
			case 2:
				return 1.5;
			default:
				return 1.0;
		}
	}

	private boolean isScytheEquipped()
	{
		final String name = weaponName();
		return name != null && name.startsWith("Scythe of vitur");
	}

	private int damageCap(int npcId)
	{
		if (npcId != NpcID.HUEY_TAIL && npcId != NpcID.HUEY_TAIL_BROKEN)
		{
			return Integer.MAX_VALUE;
		}
		return crushIsHighestAttackBonus() ? 9 : 4;
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
	 * The average of a hit rolled uniformly from nought to {@code trueMax} and
	 * then capped. Static and separate because the arithmetic is the whole point
	 * of the distinction: every roll above the cap collapses onto it, so the
	 * average sits far closer to the cap than to half of it.
	 */
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

	// Osmumten's fang rolls damage between 15% and 85% of the max hit rather
	// than from zero, so the reachable max is the max less the raised minimum.
	private int fangNarrowed(int hit)
	{
		return isFangEquipped() && attackStyle().getAttackType().isMelee()
			? fangMaxHit(hit)
			: hit;
	}

	/**
	 * The top of the fang's narrowed roll: the true max less the 15% minimum it
	 * raises the bottom to. Split out from the equipment reads so the off-by-one
	 * against a plain 0.85 multiplier stays under test without a {@code Client}.
	 */
	static int fangMaxHit(int trueMaxHit)
	{
		return trueMaxHit - trueMaxHit * 15 / 100;
	}

	/** The max hit before the fang narrows its roll, which is what its spec reaches. */
	private int unnarrowedMaxHit(int npcId)
	{
		final int hit = (int) (baseMaxHit() * gearBonus(npcId).getDamage()) + colossalBladeBonus(npcId);
		final EnchantedBolt bolt = loadedBolt(npcId);
		if (bolt == null)
		{
			return hit;
		}
		switch (bolt)
		{
			case RUBY:
				// The bolt's hit replaces the weapon's, and only beats it on
				// targets with a lot of health left.
				return Math.max(hit, EnchantedBolt.rubyDamage(targetCurrentHp(npcId)));
			case DIAMOND:
				return (int) (hit * 1.15);
			default:
				return (int) (hit * 1.20);
		}
	}

	/**
	 * The colossal blade adds 2 damage per tile of the target's size, up to 5
	 * tiles. This is a flat addition rather than a multiplier, so it lands after
	 * the gear multipliers.
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

	// The most one special attack activation can deal against this target, or
	// 0 when the weapon has no damaging spec. Multi-hit specs are totalled.
	int specialAttackMaxHit(int npcId)
	{
		// Mitigated like an ordinary hit: a special on the wrong hand is no more
		// exempt from Olm's mitigation than anything else is.
		return (int) (unmitigatedSpecialAttackMaxHit(npcId) * mitigation(npcId));
	}

	private int unmitigatedSpecialAttackMaxHit(int npcId)
	{
		final int weapon = weaponItemId();
		if (isFangEquipped())
		{
			// Eviscerate rolls to the true max instead of the narrowed 85%, so it
			// reaches exactly the figure the passive gives up. Its other half is a
			// 50% accuracy bonus, which is not damage and so is not counted here.
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
		final SpecialAttack spec = specialAttack();
		// The unmitigated hit: the caller applies mitigation, and taking it from
		// maxHit() as well would apply Olm's third twice over.
		return spec == null ? 0 : spec.maxTotal(unmitigatedMaxHit(npcId));
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

	// Magic max hit: the autocast spell's base hit for a casting staff, or the
	// staff's own attack for a powered staff, either way scaled by the magic
	// damage bonus of the worn gear.
	private int magicMaxHit()
	{
		// A manual cast is what the player is actually doing, whatever is held,
		// so it wins over the weapon's own attack.
		final Spell manual = activeManualCast();
		if (manual != null)
		{
			return applyMagicDamage(manual.maxHitAt(boostedLevel(Skill.MAGIC)), manual);
		}
		// A staff recognised by name fires its own attack. Tested before the
		// weapon category, so it still holds when the category varbit is one
		// this plugin has no entry for, relying on the category meant an
		// unmapped one fell through and reported the autocast spell instead.
		final int staff = poweredStaffMaxHit();
		if (staff > 0)
		{
			return applyMagicDamage(staff, null);
		}
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER)
		{
			// A powered staff never casts the autocast spell, so one that isn't
			// recognised is unknown rather than spell-shaped.
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
		// Magic damage is a float: several items carry fractions of a percent, so
		// summing into an int would truncate each one away.
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
		// The shadow's passive belongs to its built-in spell and to nothing
		// else. Casting blood barrage while holding it is an ordinary cast: the
		// wiki calls the effect "exclusive to its built-in spell", and applying
		// it to every cast read a manual barrage at the 100% cap when the gear
		// alone was nowhere near it. A null spell is the weapon's own attack,
		// which is the only thing the multiplier may touch.
		final int multiplier = spell == null ? gearBonuses.shadowMultiplier(gear) : 1;
		percent *= multiplier;
		if (multiplier > 1)
		{
			// Tripled equipment damage is capped at 100%; the prayer share below
			// is added after, and is not equipment.
			percent = Math.min(100.0, percent);
		}
		// Prayer magic damage is not worn equipment, so the shadow doesn't
		// multiply it and the equipment cap doesn't apply to it.
		percent += magicDamagePrayerPercent();
		return (int) Math.floor(baseMaxHit * (1.0 + percent / 100.0));
	}

	/**
	 * Whether the magic attack in hand is the weapon's own rather than a spell.
	 * A manual cast is a cast whatever is held, and a powered staff cannot
	 * autocast, so anything else with a staff recognised by name is its own
	 * attack. This is what the shadow's passive is gated on.
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
	 * A short summary of how the current loadout was resolved, for the ::loadout
	 * command. Written for a player pasting it into a bug report, so it says
	 * which weapon and target were read and what came out. The cache internals
	 * it used to print meant nothing to anyone but me, and only while the weapon
	 * category table was being fixed.
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
		// The id is printed either way. When there are no stats for it, the id is
		// the only thing that identifies what the player was actually looking at.
		lines.add(npc == null
			? String.format("Target: npc %d, no stats for it", npcId)
			: String.format("Target: %s (npc %d), defence %d (base %d), magic %d, toa raid level %d",
				npc.getName(), npcId, defenceLevel(npc), npc.getDefenceLevel(), npc.getMagicLevel(),
				// The level actually applied, not the raw varbit. That is not
				// cleared on leaving the Tombs, and printing it beside an
				// unscaled defence reads as though the scaling were still on.
				gearBonuses.tombsRaidLevel()));

		final double accuracy = hitChance(npcId);
		lines.add(accuracy < 0
			? String.format("Max hit %d, no accuracy without target stats", maxHit(npcId))
			: String.format("Max hit %d, accuracy %.1f%%, avg hit %.2f",
				maxHit(npcId), accuracy * 100, averageHit(npcId)));

		// Where a magic max hit came from. Every part of it is a separate rule
		// and a wrong figure is otherwise one number with nothing behind it —
		// the wand's stale multiplier and the shadow's misapplied passive both
		// showed up only as "the max hit is wrong".
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
		}

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

		// Kept for bug reports: these raw values are what expose a misread. The
		// cox timer goes beside the tier because the tier is only read while the
		// timer runs, and the tier is not cleared on leaving a raid.
		// Every raid varbit that might say "I am in a raid", so the ones that
		// actually clear on leaving can be told from the ones that do not. ToA's
		// raid level and ToB's progress are both known to persist.
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
		// Two more that may separate a lobby from a running raid, and may
		// explain why the level will not refresh without a relog: the path is
		// likely 0 until inside, and the freeze flag reads as though the level
		// auto-updates in the lobby until a raid starts and pins it.
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
	 * Whether the selected combat option attacks in melee, which decides where
	 * an attack's tick can be read from: melee lands on the tick it is thrown,
	 * while ranged and magic fly for a while first.
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

	// Expected damage per attack if the attack had been set up properly: the
	// configured prayer up and stats at full boost, with everything else -
	// gear, target, style, exactly as it is.
	double idealAverageHit(int npcId)
	{
		return averageHitWith(bestGear(npcId), npcId, IDEAL);
	}

	/**
	 * Whether a switch was available and not made. Compared by what is in the
	 * two loadouts, never by identity: the search's answer is held across
	 * ticks while the worn loadout is rebuilt on each one, so the same gear is
	 * two different objects a tick later. Comparing references marked every
	 * attack after the first as a miss.
	 */
	boolean missedGearSwitch(int npcId)
	{
		return !bestGear(npcId).sameItems(gear());
	}

	// The best gear available against this target, held until the worn or
	// carried items change or the target does. Never worked out per tick: it
	// is asked for once an attack, and the search is the expensive thing here.
	private Loadout bestGear(int npcId)
	{
		if (memoBestGear != null && memoBestGearNpc == npcId)
		{
			return memoBestGear;
		}
		final Loadout worn = gear();
		final ItemEquipmentStats weapon = weaponStats();
		memoBestGearNpc = npcId;
		memoBestGear = GearSearch.best(worn, carriedCandidates(), weapon != null && weapon.isTwoHanded(),
			candidate -> averageHitWith(candidate, npcId, IDEAL));
		return memoBestGear;
	}

	// Everything in the inventory that could be equipped, paired with the slot
	// it goes in. The bank is deliberately not available: the question is what
	// the player could have switched to without leaving.
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
	 * within the tick counts as held: it was up while the server resolved the
	 * attack, even though reading it afterwards finds it off.
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
	 * The level a skill rolls at. While computing the ideal, this is the base
	 * level plus the full boost of the potion expected here, so a dose that has
	 * decayed or was never drunk shows up as the damage it costs.
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

	// The boost the potion for this content gives, applied to every stat the
	// style rolls on, attack as well as strength for melee, so the accuracy a
	// dose buys is counted and not just the damage.
	private int maxBoost(Skill skill, int base)
	{
		// What the player says they meant to bring. A raid was assumed to supply
		// its own dose, which is wrong from the moment one starts: you enter with
		// what you carried and brew later, if at all. The Chambers tier varbit is
		// no help either, since it survives the raid that set it.
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
		// Raised if something stronger is actually up, never lowered: taking the
		// standard from whatever the player happens to hold would let one who
		// took nothing read as perfectly dosed.
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

	// The boost from the Chambers overload the player brewed. The three tiers
	// are far apart, at 99 they give 13, 17 and 21, so treating them alike
	// would misjudge the standard by up to eight levels.
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

	// Whether the prayer that was up is at least the one intended for the
	// style.
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

	// Whether every combat stat the current style rolls on is at the full
	// boost the best potion here would give.
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

	// Whether a prayer was active for what the server is resolving now. Reads
	// the prayer's varbit directly; Client.isPrayerActive is deprecated and
	// has no replacement overload.
	private boolean prayerActive(Prayer prayer)
	{
		final int varbit = prayer.getVarbit();
		// The server's copy, always. Clicking a prayer flips the client's copy at
		// once so the orb responds without waiting for a reply, which answers for
		// the click rather than for the attack: flick a prayer off at the end of
		// a tick and the client reads off while the server resolved the attack
		// with it up. The xp drop plugins read the server copy for the same
		// reason. A client-view mode existed here and was removed once the last
		// caller went; it undercounted every flick it was used for.
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
	 * Magic damage granted by prayer, in percent. Unlike the other styles, some
	 * magic prayers add damage as well as accuracy.
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
		// Mystic Will is deliberately absent: the wiki's magic damage table
		// lists Lore, Might, Vigour and Augury only.
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
	private int computeEquipmentBonus(boolean melee)
	{
		final Loadout gear = gear();
		int total = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
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
