package com.pvmperformance;

import net.runelite.api.gameval.NpcID;
import static org.junit.Assert.assertSame;
import org.junit.Test;

/**
 * A cosmetic override is a different NPC id for the same thrall. One left out of
 * the table is not a wrong figure but no figure at all — the damage falls back
 * into the player's column silently — so every reskin is pinned here.
 */
public class ThrallReskinTest
{
	@Test
	public void theImpOverrideIsTheSameThrall()
	{
		assertSame(Thrall.GREATER_GHOST, Thrall.forNpc(NpcID.THRALL_IMP_MAGIC_GREATER));
		assertSame(Thrall.SUPERIOR_SKELETON, Thrall.forNpc(NpcID.THRALL_IMP_RANGED_SUPERIOR));
		assertSame(Thrall.LESSER_ZOMBIE, Thrall.forNpc(NpcID.THRALL_IMP_MELEE_LESSER));
	}

	@Test
	public void theDeadmanOverridesAreGreaters()
	{
		assertSame(Thrall.GREATER_GHOST, Thrall.forNpc(NpcID.DEADMAN_THRALL_GHOSTLY_GREATER_WISP));
		assertSame(Thrall.GREATER_SKELETON,
			Thrall.forNpc(NpcID.DEADMAN_THRALL_SKELETAL_GREATER_PRINCESS));
		assertSame(Thrall.GREATER_ZOMBIE, Thrall.forNpc(NpcID.DEADMAN_THRALL_ZOMBIE_GREATER_ZUK));
	}

	@Test
	public void areskinKeepsTheTiersDamage()
	{
		assertSame(Thrall.GREATER_GHOST, Thrall.forNpc(NpcID.THRALL_IMP_MAGIC_GREATER));
		assertSame(Thrall.GREATER_GHOST, Thrall.forNpc(NpcID.ARCEUUS_THRALL_GHOST_GREATER));
	}
}
