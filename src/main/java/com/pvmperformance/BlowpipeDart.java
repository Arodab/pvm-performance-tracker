package com.pvmperformance;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * The dart loaded in a blowpipe.
 *
 * <p>A blowpipe carries its ammunition inside itself rather than in the ammo
 * slot, and the game exposes only how many charges are left, not what they are.
 * Since the dart supplies most of a blowpipe's ranged strength, leaving it out
 * understates the max hit badly, so it is asked for in the config instead.
 *
 * <p>Only the item is named here; its bonuses are read from the game's own item
 * stats, so they cannot drift from a hardcoded copy.
 */
@Getter
@RequiredArgsConstructor
enum BlowpipeDart
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
