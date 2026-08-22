package com.pvmperformance;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

// The dart loaded in a blowpipe.
@Getter
@RequiredArgsConstructor
public enum BlowpipeDart
{
	NONE("None", -1),
	BRONZE("Bronze", ItemID.BRONZE_DART),
	IRON("Iron", ItemID.IRON_DART),
	STEEL("Steel", ItemID.STEEL_DART),
	BLACK("Black", ItemID.BLACK_DART),
	MITHRIL("Mithril", ItemID.MITHRIL_DART),
	ADAMANT("Adamant", ItemID.ADAMANT_DART),
	RUNE("Rune", ItemID.RUNE_DART),
	AMETHYST("Amethyst", ItemID.AMETHYST_DART),
	DRAGON("Dragon", ItemID.DRAGON_DART);

	private final String displayName;
	private final int itemId;

	@Override
	public String toString()
	{
		return displayName;
	}
}
