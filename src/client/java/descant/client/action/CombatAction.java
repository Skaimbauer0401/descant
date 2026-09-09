package descant.client.action;

import java.util.List;

import descant.client.BotSettings;
import descant.client.control.BotInput;
import descant.client.control.Steering;
import descant.client.inventory.InventoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Self-defence: fights off hostiles that get close enough to be a problem.
 *
 * <p>Deliberately reactive rather than a hunting behaviour. The bot engages what comes within
 * roughly vanilla's three-block attack reach and otherwise keeps walking — a bot that chased every
 * mob it could see would never finish a journey.</p>
 *
 * <p>Two judgement calls are built in. Attacks wait for the strength meter to refill, because an
 * early swing does a fraction of the damage and vanilla combat rewards patience over spam. And
 * creepers are backed away from rather than traded with: there is no version of standing next to a
 * lit creeper that ends well.</p>
 *
 * <p>The shield is used here and <em>only</em> here. If the player carries one in the off-hand it is
 * raised while engaged and left up between swings — a raised shield does not stop the bot striking.
 * Keeping shield control inside combat is deliberate: it used to come up by accident when a fight
 * interrupted an eat (the eat action holds the use key, which raises an off-hand shield), leaving the
 * bot dithering between blocking, eating and hitting. Now blocking is a decision the fight owns, and
 * eating never runs while a threat is near, so the three no longer overlap.</p>
 */
public final class CombatAction {

	private static final float AIM_TOLERANCE = 20.0f;

	/**
	 * Ticks between swings at the same projectile.
	 *
	 * <p>A deflected fireball stops pointing at us and drops out of the scan on its own, so this is not
	 * what stops the bot flailing at one — it is what stops it sending an attack packet every tick for
	 * the two or three ticks that takes. Short enough that a swing the server rejected for range is
	 * retried almost immediately.</p>
	 */
	private static final int SWAT_INTERVAL_TICKS = 3;

	/** The projectile last swung at, and when, so the same one is not hit sixty times a second. */
	private int lastSwatted = -1;
	private long lastSwatTick;

	/** Nearest hostile close enough to deal with, or {@code null} if we can walk on. */
	public static LivingEntity findThreat(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.level == null) {
			return null;
		}
		// Creepers are picked up from further out so there is room to retreat before they blow.
		double range = Math.max(BotSettings.COMBAT_ENGAGE_RANGE.get(), BotSettings.CREEPER_DANGER_RANGE.get());
		AABB box = player.getBoundingBox().inflate(range);

		// Not every Monster is an enemy. The neutral ones are only a threat once they have turned on
		// somebody — see Threats.isHostile, which is also what the surroundings report reads.
		List<Monster> hostiles = minecraft.level.getEntitiesOfClass(Monster.class, box,
				monster -> monster.isAlive() && !monster.isSpectator() && Threats.isHostile(monster));

		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Monster hostile : hostiles) {
			double distance = hostile.distanceTo(player);
			boolean relevant = isDangerousCreeper(hostile)
					? distance <= BotSettings.CREEPER_DANGER_RANGE.get()
					: distance <= BotSettings.COMBAT_ENGAGE_RANGE.get();
			if (relevant && distance < bestDistance) {
				bestDistance = distance;
				best = hostile;
			}
		}
		return best;
	}

	/** A creeper that has started its fuse — the one mob worth actively running from. */
	private static boolean isDangerousCreeper(Monster monster) {
		return monster instanceof Creeper creeper && (creeper.getSwellDir() > 0 || creeper.isIgnited());
	}

	/**
	 * Deals with one threat for a tick.
	 *
	 * @return {@link ActionState#DONE} when the threat is gone and the route can resume
	 */
	public ActionState tick(Minecraft minecraft, LocalPlayer player, BotInput input,
			LivingEntity target) {
		if (minecraft.gameMode == null || target == null || !target.isAlive()) {
			setBlocking(minecraft, player, false);
			return ActionState.DONE;
		}

		Vec3 eye = player.getEyePosition();
		Vec3 aimPoint = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);

		float desiredYaw = Steering.yawTowards(eye, aimPoint);
		float desiredPitch = Steering.pitchTowards(eye, aimPoint);

		// A lit creeper is not a fight, it is a countdown. Face away and run — and drop the shield,
		// which does nothing against the blast and only slows the retreat.
		if (target instanceof Creeper creeper && (creeper.getSwellDir() > 0 || creeper.isIgnited())) {
			setBlocking(minecraft, player, false);
			player.setYRot(Steering.approach(player.getYRot(), desiredYaw + 180.0f));
			input.forward(true).sprint(true);
			return ActionState.WORKING;
		}

		player.setYRot(Steering.approach(player.getYRot(), desiredYaw));
		player.setXRot(Steering.approach(player.getXRot(), desiredPitch));

		// Stand and fight rather than drifting past mid-swing.
		input.forward(false).backward(false).left(false).right(false).sprint(false);

		if (target.distanceTo(player) > BotSettings.COMBAT_ENGAGE_RANGE.get()) {
			setBlocking(minecraft, player, false);
			return ActionState.DONE; // it backed off; get on with the journey
		}
		if (Steering.angleDifference(player.getYRot(), desiredYaw) > AIM_TOLERANCE) {
			return ActionState.WORKING; // still turning; hold whatever the shield was doing
		}

		InventoryManager.equipBestWeapon(minecraft, player);

		// Raise the shield if one is carried and hold it up throughout: it soaks hits between our
		// swings and does not prevent the swing itself. Only when the main-hand weapon has no use
		// action of its own, though — holding the use key with a trident or bow in hand would throw
		// or draw it rather than fall through to the off-hand shield.
		boolean hasShield = player.getOffhandItem().getItem() == Items.SHIELD;
		boolean mainHandInert = player.getMainHandItem().getUseAnimation() == ItemUseAnimation.NONE;
		setBlocking(minecraft, player, hasShield && mainHandInert);

		// Wait for the cooldown: a full-strength hit is worth several weak ones, and sprint-spam
		// attacking is both less effective and the most obvious thing a bot can do.
		if (player.getAttackStrengthScale(0.0f) < BotSettings.ATTACK_STRENGTH_THRESHOLD.getFloat()) {
			return ActionState.WORKING;
		}

		minecraft.gameMode.attack(player, target);
		player.swing(InteractionHand.MAIN_HAND);
		player.resetAttackStrengthTicker();
		return ActionState.WORKING;
	}

	/**
	 * Answers something in flight for a tick.
	 *
	 * <p>Two answers, and which one it is was decided by {@link Threats} rather than here. A ghast's
	 * fireball is <b>hit</b>: striking one sends it back the way it came, which both stops it and is
	 * the quickest way to kill the ghast that sent it. An arrow, a trident, a blaze's fireball or a
	 * wither skull is <b>blocked</b>: none of them can usefully be swung at, and a raised shield stops
	 * all of them dead.</p>
	 *
	 * <p>The aim snaps rather than easing round. Everywhere else the bot turns gradually because a
	 * smooth turn is what keeps it on its route — but an arrow crosses twenty blocks in about two
	 * seconds, and a shield facing the wrong way is a shield doing nothing.</p>
	 *
	 * @return {@link ActionState#DONE} when there is nothing left to answer
	 */
	public ActionState tickProjectile(Minecraft minecraft, LocalPlayer player, BotInput input,
			Threats.Incoming incoming) {
		if (minecraft.gameMode == null || incoming == null || !incoming.projectile().isAlive()) {
			setBlocking(minecraft, player, false);
			return ActionState.DONE;
		}
		Entity projectile = incoming.projectile();

		Vec3 eye = player.getEyePosition();
		Vec3 aimPoint = projectile.position().add(0.0, projectile.getBbHeight() * 0.5, 0.0);
		player.setYRot(Steering.yawTowards(eye, aimPoint));
		player.setXRot(Steering.pitchTowards(eye, aimPoint));

		// Stand still. Walking sideways out from behind a shield defeats the shield, and a swing at
		// something moving this fast wants the body still.
		input.forward(false).backward(false).left(false).right(false).sprint(false);

		if (incoming.answer() == Threats.Answer.SWAT) {
			// No shield: the main hand has to be free to swing, and holding use with a weapon that has
			// its own use action would throw it instead.
			setBlocking(minecraft, player, false);
			if (!Threats.withinSwing(player, projectile)) {
				return ActionState.WORKING; // still closing; keep facing it and wait
			}
			// Deliberately not waiting for the attack cooldown. A deflection does not care how hard the
			// hit was, and a fireball does not wait for the strength meter to refill.
			long now = player.tickCount;
			if (projectile.getId() != lastSwatted || now - lastSwatTick >= SWAT_INTERVAL_TICKS) {
				lastSwatted = projectile.getId();
				lastSwatTick = now;
				InventoryManager.equipBestWeapon(minecraft, player);
				minecraft.gameMode.attack(player, projectile);
				player.swing(InteractionHand.MAIN_HAND);
			}
			return ActionState.WORKING;
		}

		boolean hasShield = player.getOffhandItem().getItem() == Items.SHIELD;
		boolean mainHandInert = player.getMainHandItem().getUseAnimation() == ItemUseAnimation.NONE;
		setBlocking(minecraft, player, hasShield && mainHandInert);
		// Without a shield there is nothing to be gained by standing still and watching it arrive.
		return hasShield && mainHandInert ? ActionState.WORKING : ActionState.DONE;
	}

	/**
	 * Drops the shield and forgets the fight — called when the controller switches away from
	 * combat, so a raised shield never lingers into walking or eating.
	 */
	public void cancel(Minecraft minecraft, LocalPlayer player) {
		setBlocking(minecraft, player, false);
	}

	/**
	 * Raises or lowers the off-hand shield through the same held-use-key mechanism the eat action
	 * uses: pressing {@code keyUse} with a weapon in the main hand (which has no use action of its
	 * own) falls through to the off-hand and starts blocking. Releasing must also cancel the active
	 * use, or vanilla keeps the shield up for a tick after the key is gone.
	 */
	private static void setBlocking(Minecraft minecraft, LocalPlayer player, boolean raise) {
		if (raise) {
			minecraft.options.keyUse.setDown(true);
			return;
		}
		minecraft.options.keyUse.setDown(false);
		if (player.isUsingItem() && minecraft.gameMode != null) {
			minecraft.gameMode.releaseUsingItem(player);
		}
	}
}
