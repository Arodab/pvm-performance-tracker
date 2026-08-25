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
	// Each tier lists its reskins beside the original. A cosmetic override is a
	// different NPC id for the same thrall, so one left out is not a wrong
	// figure but no figure at all: the damage falls back into the player's
	// column silently. They are worth keeping complete for that reason.
	LESSER_GHOST(1, AttackType.MAGIC,
		NpcID.ARCEUUS_THRALL_GHOST_LESSER, NpcID.THRALL_IMP_MAGIC_LESSER),
	SUPERIOR_GHOST(2, AttackType.MAGIC,
		NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR, NpcID.THRALL_IMP_MAGIC_SUPERIOR),
	GREATER_GHOST(3, AttackType.MAGIC,
		NpcID.ARCEUUS_THRALL_GHOST_GREATER, NpcID.THRALL_IMP_MAGIC_GREATER,
		NpcID.DEADMAN_THRALL_GHOSTLY_GREATER_WISP),

	LESSER_SKELETON(1, AttackType.RANGED,
		NpcID.ARCEUUS_THRALL_SKELETON_LESSER, NpcID.THRALL_IMP_RANGED_LESSER),
	SUPERIOR_SKELETON(2, AttackType.RANGED,
		NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR, NpcID.THRALL_IMP_RANGED_SUPERIOR),
	GREATER_SKELETON(3, AttackType.RANGED,
		NpcID.ARCEUUS_THRALL_SKELETON_GREATER, NpcID.THRALL_IMP_RANGED_GREATER,
		NpcID.DEADMAN_THRALL_SKELETAL_GREATER_PRINCESS),

	LESSER_ZOMBIE(1, AttackType.CRUSH,
		NpcID.ARCEUUS_THRALL_ZOMBIE_LESSER, NpcID.THRALL_IMP_MELEE_LESSER),
	SUPERIOR_ZOMBIE(2, AttackType.CRUSH,
		NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR, NpcID.THRALL_IMP_MELEE_SUPERIOR),
	GREATER_ZOMBIE(3, AttackType.CRUSH,
		NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER, NpcID.THRALL_IMP_MELEE_GREATER,
		NpcID.DEADMAN_THRALL_ZOMBIE_GREATER_ZUK);

	private static final Map<Integer, Thrall> BY_ID = new HashMap<>();

	static
	{
		for (Thrall thrall : values())
		{
			for (int npcId : thrall.npcIds)
			{
				BY_ID.put(npcId, thrall);
			}
		}
	}

	private final int maxHit;
	private final AttackType attackType;
	private final int[] npcIds;

	Thrall(int maxHit, AttackType attackType, int... npcIds)
	{
		this.maxHit = maxHit;
		this.attackType = attackType;
		this.npcIds = npcIds;
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
