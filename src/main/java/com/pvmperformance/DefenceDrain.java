package com.pvmperformance;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

// How much defence has been drained off each NPC being fought.
@Slf4j
@Singleton
class DefenceDrain
{
	// A special lands on the tick it is thrown for melee, and a few ticks later
	// for the thrown and fired ones. Past this the special is forgotten rather
	// than credited to whatever is hit next.
	private static final int MAX_SPEC_ENERGY = 1000;
	private static final int HIT_WINDOW_TICKS = 5;

	private final Client client;
	private final MonsterStatsProvider monsters;
	private final PartyHitpoints partyHitpoints;

	// npc index -> defence levels taken off it.
	private final Map<Integer, Integer> drained = new HashMap<>();

	// Seeded from the live value rather than left unknown, so the first special
	// after a reset is measured against something. Left at -1 it was swallowed:
	// the drop that fired it looked like the first reading rather than a fall.
	private int specEnergy = -1;
	private SpecialWeapon firedWeapon;
	private int firedTick = Integer.MIN_VALUE;

	@Inject
	DefenceDrain(Client client, MonsterStatsProvider monsters, PartyHitpoints partyHitpoints)
	{
		this.client = client;
		this.monsters = monsters;
		this.partyHitpoints = partyHitpoints;
	}

	/** Levels of defence already taken off this NPC. */
	int drainedFrom(int npcIndex)
	{
		final Integer levels = drained.get(npcIndex);
		return levels == null ? 0 : levels;
	}

	/**
	 * Notices the player's own special attack going out, from the energy
	 * falling. The party message only carries what other members did, and only
	 * while they are in a party, so a solo player's own specials have to be
	 * seen here or not at all.
	 */
	void onEnergyChanged()
	{
		final int energy = client.getVarpValue(VarPlayerID.SA_ENERGY);
		final int previous = specEnergy;
		specEnergy = energy;
		if (previous < 0)
		{
			// First reading since a reset. A full bar cannot have just been
			// spent, so anything short of it is a special that already went out.
			if (energy >= MAX_SPEC_ENERGY)
			{
				return;
			}
		}
		else if (energy >= previous)
		{
			return;
		}
		final SpecialWeapon weapon = equippedSpecialWeapon();
		if (weapon != null)
		{
			firedWeapon = weapon;
			firedTick = client.getTickCount();
		}
	}

	/**
	 * Applies the special's drain when its hit lands. The hit is what decides
	 * the amount for both kinds, a percentage of what remains, or the damage
	 * dealt, so nothing can be worked out until it arrives.
	 */
	void onMyHitsplat(NPC npc, int amount)
	{
		if (firedWeapon == null || client.getTickCount() - firedTick > HIT_WINDOW_TICKS)
		{
			firedWeapon = null;
			return;
		}
		final SpecialWeapon weapon = firedWeapon;
		firedWeapon = null;
		apply(npc, weapon, amount);
	}

	/** A drain landed by someone else in the party. */
	void onSpecialCounterUpdate(SpecialCounterUpdate event, NPC npc)
	{
		if (npc != null && npc.getIndex() == event.getNpcIndex())
		{
			apply(npc, event.getWeapon(), event.getHit());
		}
	}

	// Books a drain against the NPC, stopping at whatever floor it has.
	private void apply(NPC npc, SpecialWeapon weapon, int hit)
	{
		final MonsterStatsProvider.MonsterStats stats = monsters.get(npc.getId());
		if (stats == null)
		{
			return;
		}
		// The scaled figure, since that is the defence the drain is a share of.
		final int base = RaidScaling.defence(client, stats.getDefenceLevel(), stats.getName(), partyHitpoints.highest());
		final int already = drainedFrom(npc.getIndex());
		final int remaining = base - already;
		if (remaining <= 0)
		{
			return;
		}

		int taken;
		if (weapon.isDamage())
		{
			taken = weapon.computeHit(hit, npc);
		}
		else
		{
			final float keep = weapon.computeDrainPercent(hit, npc);
			if (keep <= 0)
			{
				return;
			}
			taken = remaining - (int) (remaining * keep);
		}
		if (taken <= 0)
		{
			return;
		}

		final int ceiling = DefenceDrainCap.maxDrain(npc.getId(), base);
		final int total = Math.min(ceiling, Math.min(base, already + taken));
		if (total != already)
		{
			drained.put(npc.getIndex(), total);
			log.debug("PvM Performance: {} drained to {} of {} defence by {}",
				npc.getName(), base - total, base, weapon.getName());
		}
	}

	/** The drain dies with the NPC, as its stats do. */
	void forget(int npcIndex)
	{
		drained.remove(npcIndex);
	}

	void clear()
	{
		drained.clear();
		firedWeapon = null;
		specEnergy = -1;
	}

	private SpecialWeapon equippedSpecialWeapon()
	{
		final int weaponId = equippedWeaponId();
		if (weaponId <= 0)
		{
			return null;
		}
		for (SpecialWeapon weapon : SpecialWeapon.values())
		{
			for (int id : weapon.getItemID())
			{
				if (id == weaponId)
				{
					return weapon;
				}
			}
		}
		return null;
	}

	private int equippedWeaponId()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return -1;
		}
		final Item item = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return item == null ? -1 : item.getId();
	}
}
