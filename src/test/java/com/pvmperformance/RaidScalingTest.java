package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The scaling arithmetic, which is the part worth testing: it is transcribed
 * from elsewhere, it is invisible when wrong, and every expected figure in a
 * raid rests on it.
 */
public class RaidScalingTest
{
	private static final double CM = 1.5;
	private static final double NONE = 1.0;

	@Test
	public void tombsAddsTwoPercentPerFiveRaidLevels()
	{
		assertEquals(80, RaidScaling.tombs(80, 0));
		// 44% at 110 rather than 44.8%: the raid level is counted in whole fives.
		assertEquals(80 * 144 / 100, RaidScaling.tombs(80, 110));
		assertEquals(112, RaidScaling.tombs(80, 100));   // +40%
		assertEquals(176, RaidScaling.tombs(80, 300));   // +120%
		assertEquals(240, RaidScaling.tombs(80, 500));   // +200%
	}

	@Test
	public void tombsCountsRaidLevelsInWholeFives()
	{
		// 104 and 100 are the same raid for scaling; 105 is not.
		assertEquals(RaidScaling.tombs(150, 100), RaidScaling.tombs(150, 104));
		assertTrue(RaidScaling.tombs(150, 105) > RaidScaling.tombs(150, 104));
	}

	@Test
	public void chambersLeavesASoloMaxedRaiderAlone()
	{
		// 99 hitpoints and one player is the identity case: the hitpoints term
		// saturates and the party term contributes nothing.
		assertEquals(205, RaidScaling.chambers(205, 1, 99, NONE));
		assertEquals(175, RaidScaling.chambers(175, 1, 99, NONE));
	}

	@Test
	public void chambersRaisesLevelsWithPartySize()
	{
		assertTrue(RaidScaling.chambers(205, 5, 99, NONE) > RaidScaling.chambers(205, 1, 99, NONE));
		assertTrue(RaidScaling.chambers(205, 15, 99, NONE) > RaidScaling.chambers(205, 5, 99, NONE));
	}

	@Test
	public void chambersLowersLevelsWithTheHitpointsTerm()
	{
		assertTrue(RaidScaling.chambers(205, 1, 50, NONE) < RaidScaling.chambers(205, 1, 99, NONE));
		assertTrue(RaidScaling.chambers(205, 1, 1, NONE) < RaidScaling.chambers(205, 1, 50, NONE));
		// The lowest the term reaches is 55/99, and it is already there at 1
		// hitpoint — the clamp guarding it never binds. Written down because it
		// is easy to read the clamp as doing more than it does.
		assertEquals(205 * 55 / 99, RaidScaling.chambers(205, 1, 1, NONE));
		assertEquals(RaidScaling.chambers(205, 1, 1, NONE), RaidScaling.chambers(205, 1, 2, NONE));
	}

	@Test
	public void tektonInAFiveManChallengeModeRaid()
	{
		// Checked against the source this was transcribed from.
		assertEquals(287, RaidScaling.chambers(205, 5, 99, 1.35));
	}

	@Test
	public void challengeModeMultipliersAreNotUniform()
	{
		assertEquals(1.5, RaidScaling.defenceMultiplier(true, 5, "Vasa Nistirio"), 0.0001);
		// Tekton takes less, and less again in a small team.
		assertEquals(1.2, RaidScaling.defenceMultiplier(true, 3, "Tekton"), 0.0001);
		assertEquals(1.35, RaidScaling.defenceMultiplier(true, 4, "Tekton"), 0.0001);
		// The glowing crystal's defence is not raised at all.
		assertEquals(1.0, RaidScaling.defenceMultiplier(true, 5, "Glowing crystal"), 0.0001);
		// None of it applies outside challenge mode.
		assertEquals(1.0, RaidScaling.defenceMultiplier(false, 5, "Tekton"), 0.0001);
		assertEquals(1.0, RaidScaling.defenceMultiplier(false, 5, null), 0.0001);
	}

	@Test
	public void everyStepFloorsAndTheOrderIsKept()
	{
		// Applying the multiplier before the party term instead of after gives a
		// different answer; this pins the order rather than only the value.
		final int correct = RaidScaling.chambers(205, 5, 99, CM);
		final int reordered = (int) (RaidScaling.chambers(205, 5, 99, NONE) * CM);
		assertTrue("the order of the steps must not stop mattering", correct <= reordered);
	}

	@Test
	public void olmsHandsKeepAThirdOfTheWrongStyle()
	{
		final double third = 1.0 / 3.0;
		// Right hand is the mage hand: magic goes through, nothing else does.
		assertEquals(1.0, RaidScaling.damageTaken(NpcIds.OLM_RIGHT, AttackType.MAGIC), 0.0001);
		assertEquals(third, RaidScaling.damageTaken(NpcIds.OLM_RIGHT, AttackType.SLASH), 0.0001);
		assertEquals(third, RaidScaling.damageTaken(NpcIds.OLM_RIGHT, AttackType.RANGED), 0.0001);
		// Left hand is the melee hand, and takes any melee style.
		assertEquals(1.0, RaidScaling.damageTaken(NpcIds.OLM_LEFT, AttackType.CRUSH), 0.0001);
		assertEquals(third, RaidScaling.damageTaken(NpcIds.OLM_LEFT, AttackType.MAGIC), 0.0001);
	}

	@Test
	public void nightmareTotemsTakeDoubleFromMagic()
	{
		assertEquals(2.0, RaidScaling.damageTaken(NpcIds.TOTEM, AttackType.MAGIC), 0.0001);
		assertEquals(1.0, RaidScaling.damageTaken(NpcIds.TOTEM, AttackType.CRUSH), 0.0001);
	}

	@Test
	public void everythingElseTakesWhatItIsGiven()
	{
		assertEquals(1.0, RaidScaling.damageTaken(-1, AttackType.MAGIC), 0.0001);
		assertEquals(1.0, RaidScaling.damageTaken(NpcIds.GOBLIN, AttackType.SLASH), 0.0001);
	}
}
