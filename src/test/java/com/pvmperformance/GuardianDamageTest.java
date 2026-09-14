package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * The Chambers' guardians, which only a pickaxe hurts and which a pickaxe hurts
 * by a factor of {@code (50 + Mining level + pickaxe's level req) / 150}, from
 * Mod Ash. The table below is the level requirement half of it: transcription
 * rather than arithmetic, and wrong by one is a percent of damage that nobody
 * would ever notice in game.
 */
public class GuardianDamageTest
{
	private static double multiplier(int miningLevel, String pickaxe)
	{
		return (50.0 + miningLevel + CombatCalc.pickaxeLevelRequirement(pickaxe)) / 150.0;
	}

	@Test
	public void everyGuardianIdIsClaimed()
	{
		// All four on purpose. The gamevals read the pair as left and right with two dead forms; the wiki's calculator
		// reads the same four as normal and challenge mode. Claiming the block costs nothing on a corpse and is the only
		// way the multiplier cannot go missing in whichever mode the other reading is right about.
		assertTrue(EncounterGroup.isGuardian(NpcID.RAIDS_STONEGUARDIANS_LEFT));
		assertTrue(EncounterGroup.isGuardian(NpcID.RAIDS_STONEGUARDIANS_RIGHT));
		assertTrue(EncounterGroup.isGuardian(NpcID.RAIDS_STONEGUARDIANS_LEFT_DEAD));
		assertTrue(EncounterGroup.isGuardian(NpcID.RAIDS_STONEGUARDIANS_RIGHT_DEAD));
		// And nothing else in the room.
		assertFalse(EncounterGroup.isGuardian(NpcIds.GOBLIN));
		assertFalse(EncounterGroup.isGuardian(NpcID.RAIDS_TEKTON_FIGHTING_STANDARD));
	}

	@Test
	public void thePickaxeTableIsTheMiningRequirement()
	{
		assertEquals(1, CombatCalc.pickaxeLevelRequirement("Bronze pickaxe"));
		assertEquals(1, CombatCalc.pickaxeLevelRequirement("Iron pickaxe"));
		assertEquals(6, CombatCalc.pickaxeLevelRequirement("Steel pickaxe"));
		assertEquals(11, CombatCalc.pickaxeLevelRequirement("Black pickaxe"));
		assertEquals(21, CombatCalc.pickaxeLevelRequirement("Mithril pickaxe"));
		assertEquals(31, CombatCalc.pickaxeLevelRequirement("Adamant pickaxe"));
		assertEquals(41, CombatCalc.pickaxeLevelRequirement("Rune pickaxe"));
		assertEquals(41, CombatCalc.pickaxeLevelRequirement("Gilded pickaxe"));
	}

	@Test
	public void crystalCountsAsADragonPickaxeAndNotAsItsOwnRequirement()
	{
		// Mod Ash, 2019: the crystal pickaxe uses the dragon's 61 rather than its own 71. Its place at the top of the
		// wiki's list is its melee stats, not this.
		assertEquals(61, CombatCalc.pickaxeLevelRequirement("Crystal pickaxe"));
		assertEquals(61, CombatCalc.pickaxeLevelRequirement("Dragon pickaxe"));
		assertEquals(61, CombatCalc.pickaxeLevelRequirement("Dragon pickaxe (or)"));
		assertEquals(61, CombatCalc.pickaxeLevelRequirement("Infernal pickaxe"));
		assertEquals(61, CombatCalc.pickaxeLevelRequirement("3rd age pickaxe"));
		// An unknown or absent name takes the same default: the variants outnumber the exceptions.
		assertEquals(61, CombatCalc.pickaxeLevelRequirement(null));
	}

	@Test
	public void aScytheHitsByTheTargetsSizeAndTheSizeComesFromTheGame()
	{
		// The rule itself. What changed is where the size comes from - the client's own composition rather than the wiki
		// data, which is silent for every id it does not list and records the Chambers guardian as size 0.
		assertEquals(1, CombatCalc.scytheHits(0));
		assertEquals(1, CombatCalc.scytheHits(1));
		assertEquals(2, CombatCalc.scytheHits(2));
		assertEquals(3, CombatCalc.scytheHits(3));
		// Olm's hands are five tiles across and take all three hits. They were reading one, having no entry to ask.
		assertEquals(3, CombatCalc.scytheHits(5));
		assertEquals(3, CombatCalc.scytheHits(7));
	}

	@Test
	public void aBetterPickaxeAndMoreMiningAreBothWorthDamage()
	{
		// 99 Mining with a dragon pickaxe: (50 + 99 + 61) / 150.
		assertEquals(1.4, multiplier(99, "Dragon pickaxe"), 0.0001);
		// The same player with a rune pickaxe gives up a little over nine percent of it.
		assertEquals(1.2667, multiplier(99, "Rune pickaxe"), 0.0001);
		// And a bronze one is exactly no multiplier at all, which is the arithmetic falling out right rather than a case.
		assertEquals(1.0, multiplier(99, "Bronze pickaxe"), 0.0001);
		assertTrue(multiplier(99, "Dragon pickaxe") > multiplier(70, "Dragon pickaxe"));
	}
}
