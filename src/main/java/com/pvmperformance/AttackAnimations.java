package com.pvmperformance;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;

/**
 * Animations that are not attacks, used to tell a real attack apart from the
 * other things a player does mid-fight.
 *
 * <p>An attack is recognised by the player having a target and playing any
 * animation at all, rather than by matching against a list of attack
 * animations. Walking and idling leave {@code getAnimation()} at -1, since they
 * run as pose animations, so anything else is an action — which makes a short
 * blocklist enough and means new weapons work without being added anywhere.
 *
 * <p>The bulk of the list is defensive animations. Taking a hit plays a block
 * animation for whatever is wielded, and without excluding those, being hit
 * while off cooldown would read as an attack.
 *
 * <p>Blocklist ported from the AttackTimer plugin (BSD-2), (c) Matsyir, Mazhar
 * and Lexer747, whose approach this follows.
 */
final class AttackAnimations
{
	private static final Set<Integer> NOT_ATTACKS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		// Taking a hit: one block animation per weapon type
		AnimationID.HUMAN_AXE_BLOCK,
		AnimationID.HUMAN_DHSWORD_BLOCK,
		AnimationID.BRAIN_PLAYER_ANCHOR_DEFEND,
		AnimationID.IVANDIS_FLAIL_DEFEND,
		AnimationID.HUMAN_SPEAR_BLOCK,
		AnimationID.HUMAN_DINHS_BULWARK_BLOCK,
		AnimationID.WILD_CAVE_CHAINMACE_DEFEND,
		AnimationID.HUMAN_CHINCHOMPA_DEFEND,
		AnimationID.HUMAN_DDAGGER_BLOCK,
		AnimationID.WARGUILD_PARRY_DEFEND,
		AnimationID.HUMAN_SWORD_DEF,
		AnimationID.DH_SWORD_UPDATE_DEFEND,
		AnimationID.HUMAN_DSPEAR_BLOCK,
		AnimationID.HUMAN_STAFFORB_BLOCK,
		AnimationID.HUMAN_BLUNT_BLOCK,
		AnimationID.SLAYER_GRANITE_MAUL_DEFEND,
		AnimationID.HUMAN_SCYTHE_BLOCK,
		AnimationID.HUMAN_SHIELD_DEFENCE,
		AnimationID.HUMAN_ZAMORAKSPEAR_BLOCK,
		AnimationID.HUMAN_STAFF_BLOCK,
		AnimationID.HUMAN_UNARMEDBLOCK,
		AnimationID.BARROW_GUTHAN_DEFEND,
		AnimationID.SLAYER_ABYSSAL_WHIP_DEFEND,
		AnimationID.HUMAN_WEAPONS_CRIMSON_KISTEN_DEF,
		AnimationID.HUMAN_HALLOWFELL_DEFEND,

		// Eating, drinking and the overload hit
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
	 * Whether this animation could be an attack. Idle and walking report -1,
	 * which is not an attack, and everything on the blocklist is something else
	 * the player did.
	 */
	static boolean couldBeAttack(int animationId)
	{
		return animationId != -1 && !NOT_ATTACKS.contains(animationId);
	}
}
