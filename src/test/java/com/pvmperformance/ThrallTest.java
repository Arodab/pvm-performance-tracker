package com.pvmperformance;

import net.runelite.api.gameval.NpcID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import org.junit.Test;

/**
 * Wiki (Thrall): every tier attacks every 4 ticks and they always land —
 * "Thralls notably always deal successful hits, with the damage roll ranging
 * from 0-3 depending on the tier of spell used". So a hit is worth half the
 * tier's maximum with no accuracy term, which is the whole difference between a
 * thrall and a weapon.
 */
public class ThrallTest
{
	@Test
	public void theThreeTiers()
	{
		assertEquals(1, Thrall.LESSER_GHOST.getMaxHit());
		assertEquals(2, Thrall.SUPERIOR_GHOST.getMaxHit());
		assertEquals(3, Thrall.GREATER_GHOST.getMaxHit());
	}

	@Test
	public void aHitIsWorthHalfTheMaximum()
	{
		assertEquals(0.5, Thrall.LESSER_ZOMBIE.expectedDamage(), 1e-9);
		assertEquals(1.0, Thrall.SUPERIOR_ZOMBIE.expectedDamage(), 1e-9);
		assertEquals(1.5, Thrall.GREATER_ZOMBIE.expectedDamage(), 1e-9);
	}

	@Test
	public void eachFamilyFightsInItsOwnStyle()
	{
		// The style is what a target's damage multiplier is applied against -
		// the Nightmare's pillars taking double from magic being the case to
		// keep in mind - so a ghost and a zombie are not interchangeable.
		assertEquals(AttackType.MAGIC, Thrall.GREATER_GHOST.getAttackType());
		assertEquals(AttackType.RANGED, Thrall.GREATER_SKELETON.getAttackType());
		assertEquals(AttackType.CRUSH, Thrall.GREATER_ZOMBIE.getAttackType());
	}

	@Test
	public void lookedUpByNpcId()
	{
		assertSame(Thrall.GREATER_SKELETON, Thrall.forNpc(NpcID.ARCEUUS_THRALL_SKELETON_GREATER));
		assertSame(Thrall.LESSER_GHOST, Thrall.forNpc(NpcID.ARCEUUS_THRALL_GHOST_LESSER));
	}

	@Test
	public void anythingElseIsNotAThrall()
	{
		assertNull(Thrall.forNpc(NpcIds.GOBLIN));
		assertNull(Thrall.forNpc(NpcID.HESPORI));
	}
}
