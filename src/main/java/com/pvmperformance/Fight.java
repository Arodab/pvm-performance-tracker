package com.pvmperformance;

import lombok.Getter;

/**
 * The performance of a single fight against one NPC: the measured side from
 * observed facts, damage from {@code hitsplat.isMine()} splats, hits vs zeros
 * for accuracy, damage taken, and wall-clock duration, alongside the expected
 * side sampled from the combat model once per attack made.
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
	// against itself, Olm's phases, which differ by which special is running.
	// Null unless set, which is also how an older history file reads.
	private String encounterLabel;
	// Stored the wrong way round on purpose. Deserialisation fills fields
	// without running their initialisers, so a fight read back from a history
	// file written before this existed gets false, which has to mean the
	// ordinary case, that the fight counts.
	private final boolean unscored;
	// The raid this was fought in, and which run of it, so the history can be
	// read back as raids without a second file to keep in step. Null and 0
	// outside a raid, which is also what an older history file reads as.
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
	private boolean ended;
	private boolean targetDied;
	// Whether this fight is the last of its room: the one the boss's loot
	// dropped on. Not the same as targetDied, which a Hueycoatl sets several
	// times over on the way to one kill as its parts fall.
	private boolean closedRoom;
	private long endMillis;

	// The expected figures, sampled once per attack made rather than snapshotted
	// once for the fight. With one loadout throughout, the mean is just that
	// loadout's figure; with a spec weapon swapped in partway it is the actual
	// blend of what was wielded, which no single snapshot could represent.
	private double sumExpectedMaxHit;
	private int expectedMaxHitSamples;
	private double sumExpectedAccuracy;
	private double sumExpectedAverageHit;
	// Accuracy and average hit need target data, so they can be missing while the
	// max hit is known, and are counted separately.
	private int expectedTargetSamples;

	// What the attacks were set up to deal, against what they would have dealt
	// set up properly. The ratio prices each slip in damage rather than counting
	// them all alike.
	private int attacksMade;
	private int attacksPrayed;
	private int attacksPotted;
	// Attacks thrown with nothing better available to switch into. The gap to
	// attacksMade is the number that missed at least one switch.
	private int attacksSwitched;
	private double sumActualSetup;
	private double sumIdealSetup;

	// Ticks the weapon was off cooldown but no attack was made, against the
	// ticks elapsed since the first attack. Eating is separated out because it
	// is a choice with a known cost, unlike the rest of the idle time.
	private int ticksLostEating;
	private int ticksLostOther;
	private int combatTicks;
	private boolean attacking;

	// The tick this target became attackable, and how long it then took to be
	// attacked. 0 means unknown, not -1: deserialising an older history file
	// does not run the initialisers, and a gap of 0 cannot occur anyway.
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
	 * attack can be timed. Set only where that tick is actually known, which is
	 * a boss respawning under the player's nose; a fight that starts any other
	 * way has nothing to measure from and reports no lag.
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
	 * Whether this fight's damage counts towards the room it belongs to. An
	 * unscored fight still spends time and still loses ticks; it just does not
	 * get to speak for how well the player was hitting.
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
	 * Damage from one hitsplat. {@code landedAttack} says whether this hitsplat
	 * is the one that makes its attack count as landed, the first of a burst to
	 * deal anything. A dragon claw special lands four hitsplats and a dark bow
	 * two, and those are one attack apiece, so only the first to do damage
	 * credits a hit.
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
	 * Mean damage per attack made, counting misses as zero. This is the figure
	 * the expected side is compared against, since it depends only on the
	 * loadout and the target rather than on how fast the fight was played.
	 */
	double averageHit()
	{
		return attempts == 0 ? 0 : (double) damageDealt / attempts;
	}

	/** 0..1; share of my resolved attacks that dealt more than 0. */
	double accuracy()
	{
		return attempts == 0 ? 0 : (double) hits / attempts;
	}

	/**
	 * Takes one sample of the expected figures for the attack just resolved.
	 * A negative accuracy or average hit means the target's stats weren't
	 * available, and only the max hit is recorded.
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

	/**
	 * Marks that an attack was made this tick, recording how well it was set up.
	 * Sampled here rather than where the damage lands, because that is the tick
	 * the prayers and boosts actually applied to the attack.
	 */
	// The prayer-dependent half of an attack: whether the goal prayer was up,
	// and how the attack was set up given that. Kept apart from recordAttackMade
	// because a projectile's prayer is only known when it resolves, and the flag
	// and the efficiency pair have to come from the same reading.
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

	void recordAttackMade(boolean potted)
	{
		attacking = true;
		combatTicks++;
		attacksMade++;
		// One attack, one attempt. Counted here rather than where the damage
		// arrives so that a spec landing four hitsplats is the single attack it
		// was, and so the measured side is denominated the same way the expected
		// side is, both are now per attack thrown.
		attempts++;
		if (potted)
		{
			attacksPotted++;
		}
	}

	/**
	 * Damage the attacks were set up to deal as a share of what they would have
	 * dealt with the intended prayer up and stats at full boost. -1 until an
	 * attack has been made against a target with stats to compare on.
	 */
	double efficiency()
	{
		return sumIdealSetup <= 0 ? -1 : sumActualSetup / sumIdealSetup;
	}

	/**
	 * Marks a tick that passed with the weapon off cooldown and no attack made.
	 * Ignored before the first attack, since waiting to reach a boss isn't
	 * wasted combat time.
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
	 * Ticks lost as a share of those elapsed since the first attack, so a long
	 * fight and a short one compare. -1 before the first attack.
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
