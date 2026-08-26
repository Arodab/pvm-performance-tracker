package com.pvmperformance;

import lombok.Getter;

/**
 * One fight against one NPC: the measured side from observed facts - damage from
 * {@code hitsplat.isMine()} splats, hits vs zeros, damage taken, duration -
 * alongside the expected side sampled from the combat model once per attack.
 */
@Getter
class Fight
{
	// A minute. Past this the wait is no longer about the fight.
	private static final int MAX_ENGAGE_TICKS = 100;

	private final String targetName;
	// The room or boss this fight is a part of, or null if it stands alone.
	private final String groupName;
	// Overrides the group name where the room alone is too coarse to compare
	// against itself - Olm's phases. Null unless set.
	private String encounterLabel;
	// Stored the wrong way round on purpose: deserialisation fills fields without
	// running initialisers, so a fight from an older history file gets false,
	// which has to mean the ordinary case.
	private final boolean unscored;
	// The raid this was fought in and which run of it, so the history reads back
	// as raids without a second file. Null and 0 outside a raid, which is what an
	// older history file reads as.
	private final String raidName;
	private final int raidId;
	private final int targetId;
	private final int targetIndex;
	// The NPC's max HP (from NPCManager), or -1 if unknown; used to classify bosses.
	private final int maxHp;
	private final long startMillis;

	private long lastActivityMillis;
	private int damageDealt;
	private int damageTaken;
	// One attempt per attack thrown, and a hit for each attack that landed
	// anything. accuracy = hits / attempts.
	private int attempts;
	private int hits;
	// Attacks that went out and never resolved, because the target died or
	// changed form while they were still in the air. See recordAttackNulled.
	private int nulled;
	private boolean ended;
	private boolean targetDied;
	// Whether this fight is the last of its room: the one the boss's loot dropped
	// on. Not targetDied, which a Hueycoatl sets several times on the way down.
	private boolean closedRoom;
	private long endMillis;

	// Sampled once per attack rather than snapshotted once for the fight: with a
	// spec weapon swapped in partway the mean is the actual blend wielded, which
	// no single snapshot could represent.
	private double sumExpectedMaxHit;
	private int expectedMaxHitSamples;
	private double sumExpectedAccuracy;
	private double sumExpectedAverageHit;
	// Accuracy and average hit need target data, so they can be missing while the
	// max hit is known, and are counted separately.
	private int expectedTargetSamples;

	// What the attacks were set up to deal, against what they would have set up
	// properly. The ratio prices each slip in damage rather than counting alike.
	private int attacksMade;
	private int attacksPrayed;
	private int attacksPotted;
	// Attacks thrown with nothing better available to switch into. The gap to
	// attacksMade is the number that missed at least one switch.
	private int attacksSwitched;
	private double sumActualSetup;
	private double sumIdealSetup;

	// Ticks the weapon was off cooldown with no attack made, against those
	// elapsed since the first attack. Eating is separated out because it is a
	// choice with a known cost.
	private int ticksLostEating;
	private int ticksLostOther;
	private int combatTicks;
	private boolean attacking;

	// The tick this target became attackable, and how long it took to be
	// attacked. 0 means unknown, not -1: deserialising an older history file does
	// not run the initialisers.
	private transient int engageFromTick;
	private int ticksToEngage;

	Fight(String targetName, int targetId, int targetIndex, int maxHp, long now,
		RaidType raid, int raidId)
	{
		this.targetName = targetName == null ? "NPC" : targetName;
		this.groupName = EncounterGroup.of(targetId);
		this.unscored = EncounterGroup.isUnscored(targetId);
		this.raidName = raid == null ? null : raid.getDisplayName();
		this.raidId = raid == null ? 0 : raidId;
		this.targetId = targetId;
		this.targetIndex = targetIndex;
		this.maxHp = maxHp;
		this.startMillis = now;
		this.lastActivityMillis = now;
	}

	/**
	 * Notes the tick this target became attackable, so the wait before the first
	 * attack can be timed. Only where that tick is known, which is a boss
	 * respawning under the player's nose.
	 */
	void setEngageFromTick(int tick)
	{
		engageFromTick = tick;
	}

	// Times the first attack against the tick the target became attackable,
	// and returns the gap, or 0 if it isn't known.
	int recordEngaged(int tick)
	{
		if (engageFromTick <= 0 || tick <= engageFromTick)
		{
			return 0;
		}
		final int gap = tick - engageFromTick;
		// Beyond a minute the player was doing something else entirely, banking,
		// restocking, away, and timing it says nothing about the kill.
		ticksToEngage = gap > MAX_ENGAGE_TICKS ? 0 : gap;
		return ticksToEngage;
	}

	/**
	 * Whether this fight's damage counts towards its room. An unscored fight
	 * still spends time and loses ticks; it just does not speak for how well the
	 * player was hitting.
	 */
	boolean isScored()
	{
		return !unscored;
	}

	/** The room or boss this belongs to, falling back to the target's own name. */
	String encounterName()
	{
		if (encounterLabel != null)
		{
			return encounterLabel;
		}
		return groupName == null ? getTargetName() : groupName;
	}

	void setEncounterLabel(String label)
	{
		this.encounterLabel = label;
	}

	/**
	 * Damage from one hitsplat. {@code landedAttack} says whether this is the
	 * one that makes its attack count as landed - the first of a burst to deal
	 * anything - since a claw special's four splats are one attack.
	 */
	void recordDamageDealt(int amount, long now, boolean landedAttack)
	{
		damageDealt += amount;
		if (landedAttack)
		{
			hits++;
		}
		lastActivityMillis = now;
	}

	/** A magic splash on the target: an attack that landed nothing. */
	void recordSplash(long now)
	{
		lastActivityMillis = now;
	}

	void recordDamageTaken(int amount, long now)
	{
		damageTaken += amount;
		if (now > lastActivityMillis)
		{
			lastActivityMillis = now;
		}
	}

	void closeRoom()
	{
		closedRoom = true;
	}

	void end(boolean died, long now)
	{
		ended = true;
		targetDied = died;
		endMillis = now;
	}

	long durationMillis()
	{
		final long stop = ended ? endMillis : lastActivityMillis;
		return Math.max(0, stop - startMillis);
	}

	double durationSeconds()
	{
		// Floor at one tick so a one-shot kill doesn't produce an infinite DPS.
		return Math.max(0.6, durationMillis() / 1000.0);
	}

	double dps()
	{
		return damageDealt / durationSeconds();
	}

	/**
	 * Mean damage per attack, counting misses as zero. This is what the expected
	 * side is compared against, depending only on the loadout and the target.
	 */
	double averageHit()
	{
		final int resolved = resolvedAttempts();
		return resolved == 0 ? 0 : (double) damageDealt / resolved;
	}

	/** 0..1; share of my resolved attacks that dealt more than 0. */
	double accuracy()
	{
		final int resolved = resolvedAttempts();
		return resolved == 0 ? 0 : (double) hits / resolved;
	}

	/**
	 * One sample of the expected figures for the attack just resolved. A
	 * negative accuracy or average hit means no target stats were available.
	 */
	void recordExpected(int maxHit, double accuracy, double averageHit)
	{
		if (maxHit > 0)
		{
			sumExpectedMaxHit += maxHit;
			expectedMaxHitSamples++;
		}
		if (accuracy >= 0 && averageHit >= 0)
		{
			sumExpectedAccuracy += accuracy;
			sumExpectedAverageHit += averageHit;
			expectedTargetSamples++;
		}
	}

	/** Mean expected max hit over the attacks made, or -1 if never sampled. */
	double expectedMaxHit()
	{
		return expectedMaxHitSamples == 0 ? -1 : sumExpectedMaxHit / expectedMaxHitSamples;
	}

	/** Mean expected hit chance (0..1) over the attacks made, or -1 if never sampled. */
	double expectedAccuracy()
	{
		return expectedTargetSamples == 0 ? -1 : sumExpectedAccuracy / expectedTargetSamples;
	}

	/** Mean expected damage per attack over the attacks made, or -1 if never sampled. */
	double expectedAverageHit()
	{
		return expectedTargetSamples == 0 ? -1 : sumExpectedAverageHit / expectedTargetSamples;
	}

	// The prayer-dependent half of an attack. Apart from recordAttackMade because
	// a projectile's prayer is only known when it resolves, and the flag and the
	// efficiency pair have to come from the same reading.
	void recordAttackResolved(boolean prayed, boolean switched, double actualSetup, double idealSetup)
	{
		if (prayed)
		{
			attacksPrayed++;
		}
		if (switched)
		{
			attacksSwitched++;
		}
		if (actualSetup >= 0 && idealSetup > 0)
		{
			sumActualSetup += actualSetup;
			sumIdealSetup += idealSetup;
		}
	}

	/**
	 * An attack that went out and never got an answer: the target died or
	 * changed form while it was in the air, so the damage was nulled.
	 */
	void recordAttackNulled()
	{
		nulled++;
	}

	/** Attacks that actually got an answer, which is what accuracy divides by. */
	int resolvedAttempts()
	{
		return Math.max(0, attempts - nulled);
	}

	void recordAttackMade(boolean potted)
	{
		attacking = true;
		combatTicks++;
		attacksMade++;
		// One attack, one attempt. Counted here rather than where the damage
		// arrives, so a spec landing four hitsplats is the single attack it was and
		// both sides are denominated per attack thrown.
		attempts++;
		if (potted)
		{
			attacksPotted++;
		}
	}

	/**
	 * What the attacks were set up to deal as a share of what they would have
	 * with the intended prayer and full boost. -1 until an attack has been made
	 * against a target with stats.
	 */
	double efficiency()
	{
		return sumIdealSetup <= 0 ? -1 : sumActualSetup / sumIdealSetup;
	}

	/**
	 * A tick that passed with the weapon off cooldown and no attack made.
	 * Ignored before the first attack: walking to a boss is not wasted time.
	 */
	void recordTickLost(boolean eating)
	{
		if (!attacking)
		{
			return;
		}
		if (eating)
		{
			ticksLostEating++;
		}
		else
		{
			ticksLostOther++;
		}
		combatTicks++;
	}

	/** Every tick off cooldown with no attack, whatever the reason. */
	int getTicksLost()
	{
		return ticksLostEating + ticksLostOther;
	}

	/** A tick that passed while the weapon was still on cooldown. */
	void recordTickSpent()
	{
		if (attacking)
		{
			combatTicks++;
		}
	}

	/**
	 * Ticks lost as a share of those since the first attack, so a long fight and
	 * a short one compare. -1 before the first attack.
	 */
	double ticksLostShare()
	{
		return combatTicks <= 0 ? -1 : (double) getTicksLost() / combatTicks;
	}

	int getCombatTicks()
	{
		return combatTicks;
	}
}
