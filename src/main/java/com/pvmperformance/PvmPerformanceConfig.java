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
		keyName = "assumeSlayerTask",
		name = "Assume on slayer task",
		description = "Count the black mask / slayer helmet bonus in the expected figures. "
			+ "There is no reliable way to tell whether the current target is your task, "
			+ "so this is a manual switch: leave it off unless you are on task",
		position = 3
	)
	default boolean assumeSlayerTask()
	{
		return false;
	}

	@ConfigItem(
		keyName = "fightTimeoutTicks",
		name = "Fight timeout (ticks)",
		description = "End the current fight after this many game ticks with no damage dealt",
		position = 4
	)
	default int fightTimeoutTicks()
	{
		return 10;
	}
}
