package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// The ruby bolt effect is a share of the target's CURRENT health, so the health
// bar it reads has to belong to the target being asked about. It is read off
// whoever the player is interacting with, which is not always the same NPC.
public class RubyBoltTargetTest
{
	private static final int GOBLIN = NpcIds.GOBLIN;
	private static final int OTHER = GOBLIN + 1;

	/** The ordinary case: the bar is the target's own. */
	@Test
	public void aBarOnTheTargetIsUsed()
	{
		assertTrue(CombatCalc.healthBarBelongsTo(GOBLIN, 42, GOBLIN, 42));
	}

	/**
	 * The bug this guards. Taking the bar unchecked scaled the max health of one
	 * NPC by the bar of another, which is not an imprecise figure but a
	 * meaningless one.
	 */
	@Test
	public void aBarOnADifferentNpcIsRefused()
	{
		assertFalse(CombatCalc.healthBarBelongsTo(OTHER, 42, GOBLIN, 42));
	}

	/**
	 * Two of the same NPC standing together: the id matches and the instance
	 * does not, which is the case the id alone cannot separate.
	 */
	@Test
	public void aBarOnAnotherOfTheSameNpcIsRefused()
	{
		assertFalse(CombatCalc.healthBarBelongsTo(GOBLIN, 7, GOBLIN, 42));
	}

	/**
	 * With no fight open there is no index to check against, and refusing the
	 * bar then would read every target at full health.
	 */
	@Test
	public void theIndexIsOnlyCheckedOnceThereIsOne()
	{
		assertTrue(CombatCalc.healthBarBelongsTo(GOBLIN, 7, GOBLIN, -1));
		assertFalse(CombatCalc.healthBarBelongsTo(OTHER, 7, GOBLIN, -1));
	}

	/** Blood forfeit is 20% of CURRENT health, capped at 100. */
	@Test
	public void rubyDamageIsAFifthOfCurrentHealthCapped()
	{
		assertEquals(20, EnchantedBolt.rubyDamage(100));
		assertEquals(2, EnchantedBolt.rubyDamage(14));
		// The cap bites above 500 current health, not above 500 max.
		assertEquals(100, EnchantedBolt.rubyDamage(600));
		assertEquals(100, EnchantedBolt.rubyDamage(500));
		assertEquals(99, EnchantedBolt.rubyDamage(499));
	}

	/**
	 * The Kandarin hard diary raises every enchanted bolt rate by a tenth of
	 * itself, and is always active - the wiki is explicit that the headgear need
	 * not be worn, which is why nothing checks for it.
	 */
	@Test
	public void theKandarinDiaryRaisesEveryRateByATenth()
	{
		for (EnchantedBolt bolt : EnchantedBolt.values())
		{
			assertEquals(bolt.name(), bolt.chance(false) * 1.1, bolt.chance(true), 1e-9);
			assertTrue(bolt.name(), bolt.chance(false) > 0 && bolt.chance(true) < 1);
		}
		// PvM rates, which are the only ones this plugin scores.
		assertEquals(0.06, EnchantedBolt.RUBY.chance(false), 1e-9);
	}
}
