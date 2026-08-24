package com.pvmperformance;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import net.runelite.api.gameval.NpcID;

/**
 * The Arceuus thralls, which fight alongside the player and are worth counting
 * apart from them.
 *
 * <p>Their damage is not the player's doing beyond having summoned them, so
 * folding it into the player's total flatters the loadout: a thrall's chip
 * damage lands in the same column as a scythe swing. It is also why a fight can
 * show more damage than the weapon could have dealt.
 *
 * <p>Wiki (Thrall): every tier attacks every 4 ticks, and <b>thralls always
 * land</b> — "Thralls notably always deal successful hits, with the damage roll
 * ranging from 0-3 depending on the tier of spell used, as well as ignoring the
 * flat armour stat." So there is no accuracy to model: one attack is worth half
 * its maximum, every time, and the only thing that moves it is a target that
 * takes more or less from that style.
 */
@Getter
enum Thrall
{
	LESSER_GHOST(NpcID.ARCEUUS_THRALL_GHOST_LESSER, 1, AttackType.MAGIC),
	SUPERIOR_GHOST(NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR, 2, AttackType.MAGIC),
	GREATER_GHOST(NpcID.ARCEUUS_THRALL_GHOST_GREATER, 3, AttackType.MAGIC),

	LESSER_SKELETON(NpcID.ARCEUUS_THRALL_SKELETON_LESSER, 1, AttackType.RANGED),
	SUPERIOR_SKELETON(NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR, 2, AttackType.RANGED),
	GREATER_SKELETON(NpcID.ARCEUUS_THRALL_SKELETON_GREATER, 3, AttackType.RANGED),

	LESSER_ZOMBIE(NpcID.ARCEUUS_THRALL_ZOMBIE_LESSER, 1, AttackType.CRUSH),
	SUPERIOR_ZOMBIE(NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR, 2, AttackType.CRUSH),
	GREATER_ZOMBIE(NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER, 3, AttackType.CRUSH);

	private static final Map<Integer, Thrall> BY_ID = new HashMap<>();

	static
	{
		for (Thrall thrall : values())
		{
			BY_ID.put(thrall.npcId, thrall);
		}
	}

	private final int npcId;
	private final int maxHit;
	private final AttackType attackType;

	Thrall(int npcId, int maxHit, AttackType attackType)
	{
		this.npcId = npcId;
		this.maxHit = maxHit;
		this.attackType = attackType;
	}

	static Thrall forNpc(int npcId)
	{
		return BY_ID.get(npcId);
	}

	/**
	 * What one of its attacks is worth. The roll is nought to the maximum and it
	 * always lands, so this is simply half the maximum — no accuracy term, which
	 * is the whole difference between a thrall and a weapon.
	 */
	double expectedDamage()
	{
		return maxHit / 2.0;
	}
}
