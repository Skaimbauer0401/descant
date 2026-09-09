package descant.client.action;

import descant.client.BotSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What is actually worth fighting, and what is merely nearby.
 *
 * <p>Two separate questions, both of which the bot used to get wrong. It treated every
 * {@link net.minecraft.world.entity.monster.Monster} within reach as an enemy — which is how a walk
 * through the Nether turns into a war with a piglin pack that had no quarrel with anyone. And it
 * ignored projectiles entirely, so a ghast could shell it from forty blocks away and the bot's only
 * response was to keep walking and take it.</p>
 */
public final class Threats {

	/**
	 * How close a projectile's flight path must point at the player to count as aimed at them.
	 *
	 * <p>A cosine, so 0.85 is about thirty degrees. Loose rather than tight on purpose: an arrow
	 * leading a moving target is not pointing exactly at where the player is now, and a bot that only
	 * blocked the shots already guaranteed to hit would block nothing in time.</p>
	 */
	private static final double AIMED_AT_US = 0.85;

	/** What to do about something in flight. */
	public enum Answer {
		/**
		 * Hit it. A ghast's fireball and a shulker bullet are both big enough and slow enough to strike,
		 * and a fireball sent back the way it came is not merely stopped — it is the fastest way to kill
		 * the ghast that sent it, which is why it beats hiding behind a shield.
		 */
		SWAT,
		/**
		 * Get behind the shield. Arrows, tridents, a blaze's fireballs and wither skulls are all too
		 * small or too fast to swing at, and there would be no return value in connecting anyway — but a
		 * raised shield stops every one of them dead.
		 */
		BLOCK
	}

	/** Something in flight and the answer to it. */
	public record Incoming(Projectile projectile, Answer answer, double distance) {
	}

	private Threats() {
	}

	// ---------------------------------------------------------------- who is an enemy

	/**
	 * Whether this mob is one to fight rather than one to walk past.
	 *
	 * <p>Hostile-by-nature mobs always count. The neutral ones — endermen, zombified piglins, and
	 * anything else implementing {@link NeutralMob} — count only once they have turned on somebody,
	 * because attacking one that had not is how the bot <em>creates</em> the fight. In the Nether that
	 * is not a fight with one piglin either; the whole pack joins in.</p>
	 *
	 * <p><b>Read from what the client can actually see</b>, which is the limitation worth naming.
	 * Vanilla's own {@code isAngryAt} needs a {@code ServerLevel} and the anger timer is never sent to
	 * the client, so neither is available here. What <em>is</em> synced is
	 * {@link Mob#isAggressive()} — the flag the server sets while a mob is actively attacking — and,
	 * for endermen, {@link EnderMan#isCreepy()}, the stare. Both mean "this one has picked a fight",
	 * which is the question being asked; neither can tell us the fight is with <em>us</em>. Hitting
	 * back at a piglin that is busy with a wither skeleton is the worst this gets wrong, and it is a
	 * great deal better than starting the fight.</p>
	 */
	public static boolean isHostile(LivingEntity mob) {
		if (!(mob instanceof Enemy)) {
			return false; // a cow is not a threat, however close it stands
		}
		if (mob instanceof EnderMan enderman) {
			// The stare comes before the charge and is the only warning there is.
			return enderman.isCreepy() || enderman.isAggressive();
		}
		if (mob instanceof NeutralMob) {
			return mob instanceof Mob aggressor && aggressor.isAggressive();
		}
		return true;
	}

	// ---------------------------------------------------------------- what is in flight

	/**
	 * The most urgent thing flying at the player, or {@code null}.
	 *
	 * <p>Most urgent means nearest, not most dangerous: everything here is closing, and the one about
	 * to arrive is the one there is still time to do something about.</p>
	 */
	public static Incoming incoming(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.level == null || !BotSettings.GUARD_PROJECTILES.get()) {
			return null;
		}
		double range = BotSettings.PROJECTILE_WATCH_RANGE.get();
		AABB box = player.getBoundingBox().inflate(range);

		Incoming best = null;
		for (Projectile projectile : minecraft.level.getEntitiesOfClass(Projectile.class, box,
				candidate -> candidate.isAlive())) {
			Answer answer = answerFor(projectile);
			if (answer == null || projectile.getOwner() == player) {
				continue;
			}
			double distance = projectile.distanceTo(player);
			if (distance > range || !aimedAtUs(projectile, player)) {
				continue;
			}
			if (best == null || distance < best.distance()) {
				best = new Incoming(projectile, answer, distance);
			}
		}
		return best;
	}

	/**
	 * What kind of answer this projectile calls for, or {@code null} for one not worth reacting to.
	 *
	 * <p>Named one class at a time rather than by base class, because the base class is a lie here:
	 * everything a blaze, a ghast, a wither and a breeze shoot shares
	 * {@link AbstractHurtingProjectile}, and the right answer to those four is different every time.
	 * An earlier version swatted the lot, which meant the bot standing in the open swinging at blaze
	 * fireballs it cannot hit while they burned it.</p>
	 */
	private static Answer answerFor(Projectile projectile) {
		if (projectile instanceof LargeFireball || projectile instanceof ShulkerBullet) {
			return Answer.SWAT;
		}
		// A blaze's fireball is a tenth the size of a ghast's and arrives in a burst of three; a wither
		// skull is not much better. Neither can be batted away by hand, and both are stopped outright by
		// a shield — as are arrows and tridents, which there was never any point swinging at.
		if (projectile instanceof SmallFireball || projectile instanceof WitherSkull
				|| projectile instanceof AbstractArrow) {
			return Answer.BLOCK;
		}
		// Everything else is left alone, and each for its own reason:
		// - a dragon fireball does no damage itself. What hurts is the cloud of dragon's breath it leaves
		//   where it lands, and no swing or shield touches that; the only answer is to walk out of it.
		// - a wind charge deals no damage at all, only knockback, which a shield does not stop either.
		// - a snowball, an egg, a fishing float and a firework are all projectiles too, and a bot that
		//   reacted to every one of them would stand in a field blocking chickens.
		return null;
	}

	/**
	 * Whether this thing is flying at the player rather than merely near them.
	 *
	 * <p>An arrow that has already gone past is still within range for a second or two, and stopping
	 * to block one is worse than useless. Direction of travel against direction to the player answers
	 * it, and it also rules out the spent arrow lying in the dirt, which has no velocity at all.</p>
	 */
	private static boolean aimedAtUs(Projectile projectile, LocalPlayer player) {
		Vec3 velocity = projectile.getDeltaMovement();
		if (velocity.lengthSqr() < 1.0e-4) {
			return false; // stuck in the ground, or hovering: not going anywhere near us
		}
		Vec3 towards = player.getEyePosition().subtract(projectile.position());
		if (towards.lengthSqr() < 1.0e-6) {
			return true; // already on top of us
		}
		return velocity.normalize().dot(towards.normalize()) >= AIMED_AT_US;
	}

	/** Whether the bot can reach this to hit it, by the same rule the server will apply. */
	public static boolean withinSwing(LocalPlayer player, Entity target) {
		return player.isWithinEntityInteractionRange(target, 0.0);
	}
}
