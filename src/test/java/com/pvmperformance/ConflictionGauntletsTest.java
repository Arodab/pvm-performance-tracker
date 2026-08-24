package com.pvmperformance;

import net.runelite.api.gameval.ItemID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The two rules that decide whether the doubled accuracy roll applies. Both are
 * pinned here because one of them has already regressed once: comparing an
 * unarmed -1 against a no-fight -1 matched, and left the bonus showing for good
 * after a kill.
 */
public class ConflictionGauntletsTest
{
	@Test
	public void aTwoHandedWeaponDisablesThem()
	{
		assertFalse(CombatCalc.conflictionGauntletsWork(ItemID.CONFLICTION_GAUNTLETS, true));
	}

	@Test
	public void theyWorkOneHanded()
	{
		assertTrue(CombatCalc.conflictionGauntletsWork(ItemID.CONFLICTION_GAUNTLETS, false));
	}

	@Test
	public void otherGlovesDoNothingEitherWay()
	{
		assertFalse(CombatCalc.conflictionGauntletsWork(ItemID.BR_RUNE_GLOVES, false));
		assertFalse(CombatCalc.conflictionGauntletsWork(-1, false));
	}

	@Test
	public void armedAgainstTheEnemyBeingFought()
	{
		assertTrue(CombatCalc.conflictionArmedAgainst(42, 42));
	}

	@Test
	public void switchingTargetDropsIt()
	{
		assertFalse(CombatCalc.conflictionArmedAgainst(42, 43));
	}

	@Test
	public void unarmedIsNotArmedAgainstNothing()
	{
		// The regression: two nothings matching left the roll doubled for good.
		assertFalse(CombatCalc.conflictionArmedAgainst(-1, -1));
	}

	@Test
	public void armedButNothingBeingFought()
	{
		assertFalse(CombatCalc.conflictionArmedAgainst(42, -1));
	}

	@Test
	public void aHitOnTheArmedEnemySpendsTheCharge()
	{
		final CombatCalc.ConflictionCharge charge = new CombatCalc.ConflictionCharge();
		charge.resolved(42, true);
		charge.resolved(42, false);
		assertFalse(CombatCalc.conflictionArmedAgainst(charge.armedIndex(), 42));
	}

	@Test
	public void aHitOnSomethingElseLeavesTheChargeAlone()
	{
		// It used to clear on any hit of mine, so a blow landing on anything
		// beside the enemy being fought disarmed the gauntlets against it.
		final CombatCalc.ConflictionCharge charge = new CombatCalc.ConflictionCharge();
		charge.resolved(42, true);
		charge.resolved(43, false);
		assertTrue(CombatCalc.conflictionArmedAgainst(charge.armedIndex(), 42));
	}

	@Test
	public void consecutiveMissesDoNotStack()
	{
		final CombatCalc.ConflictionCharge charge = new CombatCalc.ConflictionCharge();
		charge.resolved(42, true);
		charge.resolved(42, true);
		assertTrue(CombatCalc.conflictionArmedAgainst(charge.armedIndex(), 42));
	}

	@Test
	public void aMissOnAnotherEnemyMovesTheCharge()
	{
		// "Against the same enemy": the last miss is what the charge is held
		// against, so switching target and missing moves it rather than keeping
		// both.
		final CombatCalc.ConflictionCharge charge = new CombatCalc.ConflictionCharge();
		charge.resolved(42, true);
		charge.resolved(43, true);
		assertFalse(CombatCalc.conflictionArmedAgainst(charge.armedIndex(), 42));
		assertTrue(CombatCalc.conflictionArmedAgainst(charge.armedIndex(), 43));
	}

	@Test
	public void aChargeIsForgottenWhenThereIsNothingToHoldItAgainst()
	{
		// An attack whose damage was nulled - the target died in flight, or
		// changed form - pays no experience, and no experience is how a miss is
		// recognised. It armed against an enemy that never dodged anything, and
		// on a boss that transforms in place the NPC index does not change, so
		// nothing else cleared it and the doubled accuracy stayed on screen.
		final CombatCalc.ConflictionCharge charge = new CombatCalc.ConflictionCharge();
		charge.resolved(42, true);
		assertTrue(CombatCalc.conflictionArmedAgainst(charge.armedIndex(), 42));
		charge.resolved(-1, false);
		assertFalse(CombatCalc.conflictionArmedAgainst(charge.armedIndex(), 42));
	}
}