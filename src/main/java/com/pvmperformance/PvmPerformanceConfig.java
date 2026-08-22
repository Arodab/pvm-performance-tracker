package com.pvmperformance;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PvmPerformanceConfig.GROUP)
public interface PvmPerformanceConfig extends Config
{
	String GROUP = "pvmperformance";

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Show the live performance overlay while fighting",
		position = 1
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayBossesOnly",
		name = "Overlay: bosses only",
		description = "Only show the overlay while fighting a boss (from the hiscores list)",
		position = 2
	)
	default boolean overlayBossesOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlayExpectedOnly",
		name = "Expected figures only",
		description = "Show what the loadout does against the target and nothing else: no damage, "
			+ "efficiency or ticks lost",
		position = 3
	)
	default boolean overlayExpectedOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlaySessionTotals",
		name = "Overlay: whole trip",
		description = "Keep the overlay running across kills instead of resetting each fight, "
			+ "so a whole trip can be judged rather than one kill. Reset it from the side panel",
		position = 4
	)
	default boolean overlaySessionTotals()
	{
		return false;
	}

	@ConfigItem(
		keyName = "raidScope",
		name = "Inside a raid, show",
		description = "Whether the overlay reports the room being fought or the whole raid so far",
		position = 5
	)
	default RaidScope raidScope()
	{
		return RaidScope.ROOM;
	}

	@ConfigItem(
		keyName = "blowpipeDart",
		name = "Blowpipe dart",
		description = "Which dart your blowpipe is loaded with. A blowpipe keeps its darts "
			+ "inside itself and the game does not say which they are, so the max hit is "
			+ "well short without this",
		position = 6
	)
	default BlowpipeDart blowpipeDart()
	{
		return BlowpipeDart.DRAGON;
	}

	@ConfigItem(
		keyName = "meleeBoostGoal",
		name = "Melee boost",
		description = "The melee boost you mean to be holding. Efficiency compares what your "
			+ "attacks were set up for against what this would have given. Overloads and "
			+ "smelling salts are detected on their own and raise it while they run",
		position = 6
	)
	default MeleeBoost meleeBoostGoal()
	{
		return MeleeBoost.SUPER_COMBAT;
	}

	@ConfigItem(
		keyName = "rangedBoostGoal",
		name = "Ranged boost",
		description = "The ranged boost you mean to be holding",
		position = 6
	)
	default RangedBoost rangedBoostGoal()
	{
		return RangedBoost.RANGING;
	}

	@ConfigItem(
		keyName = "magicBoostGoal",
		name = "Magic boost",
		description = "The magic boost you mean to be holding",
		position = 6
	)
	default MagicBoost magicBoostGoal()
	{
		return MagicBoost.SATURATED_HEART;
	}

	@ConfigItem(
		keyName = "meleePrayerGoal",
		name = "Melee prayer",
		description = "The melee prayer you mean to hold up. Efficiency compares the damage "
			+ "your attacks were set up for against what this prayer would have given",
		position = 7
	)
	default PrayerChoice meleePrayerGoal()
	{
		return PrayerChoice.PIETY;
	}

	@ConfigItem(
		keyName = "rangedPrayerGoal",
		name = "Ranged prayer",
		description = "The ranged prayer you mean to hold up",
		position = 8
	)
	default PrayerChoice rangedPrayerGoal()
	{
		return PrayerChoice.RIGOUR;
	}

	@ConfigItem(
		keyName = "magicPrayerGoal",
		name = "Magic prayer",
		description = "The magic prayer you mean to hold up",
		position = 9
	)
	default PrayerChoice magicPrayerGoal()
	{
		return PrayerChoice.AUGURY;
	}

	@ConfigItem(
		keyName = "slayerHelmetOffTask",
		name = "Slayer helmet off-task",
		description = "Wearing a slayer helmet while not on task, so its bonus does not count",
		position = 10
	)
	default boolean slayerHelmetOffTask()
	{
		return false;
	}

	@ConfigItem(
		keyName = "fightTimeoutTicks",
		name = "Fight timeout (ticks)",
		description = "End the current fight after this many game ticks with no damage dealt",
		position = 11
	)
	default int fightTimeoutTicks()
	{
		return 10;
	}
}
