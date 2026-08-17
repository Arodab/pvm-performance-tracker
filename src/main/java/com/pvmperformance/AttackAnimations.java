package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;

/**
 * Tells a real attack apart from the other things a player does mid-fight.
 *
 * <p>The primary test is not this list. Once an attack has been corroborated for
 * the equipped weapon — by a hitsplat landing the same tick for melee, or a
 * projectile being created the same tick for ranged and magic — the animation
 * that was playing is remembered, and from then on only that animation counts as
 * an attack for that weapon. That is learnt from what the game actually did, so
 * no animation added in a future update can be mistaken for an attack.
 *
 * <p>This list only covers the gap before a weapon has been corroborated, where
 * the test falls back to "has a target and is playing some animation": walking
 * and idling run as pose animations and leave {@code getAnimation()} at -1, so
 * anything else is an action of some kind.
 *
 * <p>Block animations are deliberately absent even though taking a hit plays
 * one. They are caught by noticing that damage was taken on the same tick,
 * which needs no list and so cannot go stale as new weapons arrive — that was
 * the largest and fastest-growing part of the list this replaces.
 *
 * <p>Blocklist ported from the AttackTimer plugin (BSD-2), (c) Matsyir, Mazhar
 * and Lexer747, whose animation approach this follows.
 */
final class AttackAnimations
{
	/** Eating and drinking, which cost attack ticks and are counted on their own. */
	private static final Set<Integer> CONSUMING = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		AnimationID.HUMAN_EAT,
		AnimationID.HUMAN_KILLERWATT_ELECTRICSHOCK
	)));

	private static final Set<Integer> NOT_ATTACKS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		AnimationID.HUMAN_EAT,
		AnimationID.HUMAN_KILLERWATT_ELECTRICSHOCK,

		// Utility spells cast during a fight
		AnimationID.HUMAN_CASTBONESTOBANANAS,
		AnimationID.DREAM_PLAYER_SPELLBOOK_SWAP,
		AnimationID.QUEST_LUNAR_SPELL_CAST_SPELL_ON_GROUP,
		AnimationID.QUEST_LUNAR_PUSHING_MAGIC_ANIMATION,
		AnimationID.LUNAR_HUMAN_MAGIC_SUMMON2,
		AnimationID.VENGEANCE_SPELL_ANIM_NOSTALLING,
		AnimationID.VENGEANCE_SPELL_ANIM_STALLING,
		AnimationID.ARCEUUS_NECROMANCY_PLAYERANIM,
		AnimationID.HUMAN_CAST_OFFERING,
		AnimationID.HUMAN_SPELLCAST_SHADOWVEIL,
		AnimationID.HUMAN_CAST_SELFIMBUE,
		AnimationID.HUMAN_SPELLCAST_RESURRECT,
		AnimationID.HUMAN_TELEPORT_OTHER_IMPACT,
		AnimationID.DREAM_PLAYER_MONSTEREXAM_STATSPY,
		AnimationID.DREAM_PLAYER_HUMIDIFY_SPELL,
		AnimationID.LUNAR_HUMAN_MAGIC_GEOMANCY,
		AnimationID.HUMAN_CASTLOWLVLALCHEMY,
		AnimationID.HUMAN_CASTHIGHLVLALCHEMY,
		AnimationID.TELEPORT_NARDAH_HUMAN,
		AnimationID.FOSSIL_LOC_CLAM_IDLE_SHUT,

		// Skilling and slayer items
		AnimationID.HUMAN_PICKPOCKET,
		AnimationID.SLAYER_SALT_SPRINKLE,
		AnimationID.HUMAN_FLETCHING,
		AnimationID.HUMAN_CRAFTING_SPIKEDVAMBRACES,
		AnimationID.HUMAN_FLETCHING_HUNTINGBOLTS,
		AnimationID.HUMAN_FLETCHING_ADD_DART_FEATHERS_BRONZE,
		AnimationID.HUMAN_FLETCHING_ADD_DART_FEATHERS_IRON,
		AnimationID.HUMAN_FLETCHING_ADD_DART_FEATHERS_STEEL,
		AnimationID.HUMAN_FLETCHING_ADD_DART_FEATHERS_MITHRIL,
		AnimationID.HUMAN_FLETCHING_ADD_DART_FEATHERS_ADAMANT,
		AnimationID.HUMAN_FLETCHING_ADD_DART_FEATHERS_RUNE,
		AnimationID.HUMAN_FLETCHING_ADD_DART_FEATHERS_DRAGON,
		AnimationID.HUMAN_FLETCHING_ADD_DART_FEATHERS_AMETHYST,
		AnimationID.HUMAN_SALAMANDER_TAR_GRIND,
		AnimationID.HUMAN_LAYTRAP,
		AnimationID.HUMAN_HUNTING_DISMANTLE_NET,
		AnimationID.HUNTING_SETTING_TRAP_SMALL
	)));

	private AttackAnimations()
	{
	}

	/**
	 * Whether this animation could be an attack, for a weapon whose real attack
	 * animation hasn't been observed yet. Idle and walking report -1.
	 */
	static boolean couldBeAttack(int animationId)
	{
		return animationId != -1 && !NOT_ATTACKS.contains(animationId);
	}

	/** Whether the player is eating or drinking, which costs attack ticks. */
	static boolean isConsuming(int animationId)
	{
		return CONSUMING.contains(animationId);
	}
}
