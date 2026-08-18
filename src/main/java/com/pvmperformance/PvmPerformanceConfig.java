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
		keyName = "overlaySessionTotals",
		name = "Overlay: whole trip",
		description = "Keep the overlay running across kills instead of resetting each fight, "
			+ "so a whole trip can be judged rather than one kill. Reset it from the side panel",
		position = 3
	)
	default boolean overlaySessionTotals()
	{
		return false;
	}

	@ConfigItem(
		keyName = "raidScope",
		name = "Inside a raid, show",
		description = "Whether the overlay reports the room being fought or the whole raid so far",
		position = 4
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
		position = 5
	)
	default BlowpipeDart blowpipeDart()
	{
		return BlowpipeDart.DRAGON;
	}

	@ConfigItem(
		keyName = "meleePrayerGoal",
		name = "Efficiency: melee prayer",
		description = "The melee prayer you mean to hold up. Efficiency compares the damage "
			+ "your attacks were set up for against what this prayer would have given",
		position = 6
	)
	default PrayerChoice meleePrayerGoal()
	{
		return PrayerChoice.PIETY;
	}

	@ConfigItem(
		keyName = "rangedPrayerGoal",
		name = "Efficiency: ranged prayer",
		description = "The ranged prayer you mean to hold up",
		position = 7
	)
	default PrayerChoice rangedPrayerGoal()
	{
		return PrayerChoice.RIGOUR;
	}

	@ConfigItem(
		keyName = "magicPrayerGoal",
		name = "Efficiency: magic prayer",
		description = "The magic prayer you mean to hold up",
		position = 8
	)
	default PrayerChoice magicPrayerGoal()
	{
		return PrayerChoice.AUGURY;
	}

	@ConfigItem(
		keyName = "slayerHelmetOffTask",
		name = "Slayer helmet off-task",
		description = "Wearing a slayer helmet while not on task, so its bonus does not count",
		position = 9
	)
	default boolean slayerHelmetOffTask()
	{
		return false;
	}

	@ConfigItem(
		keyName = "fightTimeoutTicks",
		name = "Fight timeout (ticks)",
		description = "End the current fight after this many game ticks with no damage dealt",
		position = 10
	)
	default int fightTimeoutTicks()
	{
		return 10;
	}
}
