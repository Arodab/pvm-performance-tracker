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
		keyName = "fightTimeoutTicks",
		name = "Fight timeout (ticks)",
		description = "End the current fight after this many game ticks with no damage dealt",
		position = 3
	)
	default int fightTimeoutTicks()
	{
		return 10;
	}
}
