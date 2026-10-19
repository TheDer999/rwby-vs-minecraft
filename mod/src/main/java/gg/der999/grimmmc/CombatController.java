package gg.der999.grimmmc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Ruby's combat state machine, ported from Grimm Eclipse's PlayerCharacter / BaseCharacter:
 * buffered inputs, combo counters, per-move cooldown and combo-input windows, dodge invulnerability,
 * auto-targeting, and the push / gravity effects the moves use. Runs once per client tick (20 Hz).
 */
public final class CombatController {
	enum Action { NONE, DODGE, COUNTER, LIGHT, HEAVY, RANGED, SPECIAL, TEAM, TAUNT }

	enum Phase { IDLE, CHARGE, MOVE, COOLDOWN, WINDOW }

	static final float DT = 0.05f;
	/** Grimm Eclipse metres to Minecraft blocks (Ruby's model is drawn 1.11 times larger to fit the 1.8 block player). */
	static final float SCALE = 1.11f;

	// Grimm Eclipse "Melee" auto-target settings (AutotargetData asset).
	private static final double TARGET_DISTANCE = 10.0 * SCALE;
	private static final double TARGET_ANGLE_CUTOFF = 90.0;
	private static final double TARGET_FULL_WEIGHT = 0.5, TARGET_REDUCED_WEIGHT = 0.25;
	private static final double TARGET_MIN_SCORE = 0.3;
	/** A forward push stops once the target is this close (GE: 1 unit). */
	private static final double PUSH_STOP_DISTANCE = 1.3;
	/** How far the team attack (Reap) reaches: Ruby warps to the enemy, so it stays inside what the server accepts as one move. */
	static final double TEAM_RANGE = 9.0;

	final CombatData data;

	Move move;
	Phase phase = Phase.IDLE;
	float phaseTime;
	float moveTime;
	private int nextEvent;

	private Action queued = Action.NONE;
	private final int[] combo = new int[2]; // melee, ranged
	private boolean canDodge = true;
	private boolean canDodgeInAir = true;
	private boolean canAttackInAir = true;
	private boolean attackFromGround;
	/** Set by a dodge: lets the next attack start at once and picks the dash attack. */
	private boolean inLightAction;
	/** True while a dodge lets Ruby act (GE's m_inAction = false during a dodge). */
	private boolean actionFree;
	private boolean uninterruptible;
	private float invulnerableLeft;
	private boolean visible = true;
	private boolean heavyHeld;
	private float chargeTime;
	private Entity counterTarget;

	// Facing and target
	private float facingYaw;
	private Entity target;
	private boolean followTarget;

	// Push (horizontal motion along Ruby's facing)
	private boolean pushing;
	private float pushSpeed;
	private float smoothPushSpeed;
	private boolean pushScaling;
	private float pushFrom, pushTo, pushTime, pushElapsed;

	// Gravity override while a move holds Ruby in the air
	private boolean upping;
	private float upFrom, upTo, upTime, upElapsed;
	private boolean gravityActive;
	private float gravity = -10f;
	private float gravitySpeed = 1f;

	/** Seconds the current clip has been playing (it keeps running through the cooldown and combo window). */
	private float clipTime;
	private String clipName;

	CombatController(CombatData data) {
		this.data = data;
	}

	// ---- queries

	boolean active() { return phase != Phase.IDLE; }

	/** True while a move's timeline is running: movement and jumping are locked (a dodge leaves Ruby free to act). */
	boolean inMove() { return (phase == Phase.MOVE || phase == Phase.CHARGE) && !actionFree; }

	public boolean invulnerable() { return invulnerableLeft > 0f; }

	boolean rubyVisible() { return visible; }

	String clipName() { return clipName; }

	float clipTime() { return clipTime; }

	int comboCount() { return combo[0]; }

	/** Direction Ruby faces, in Minecraft yaw degrees. Follows her target during a move. */
	public float facingYaw() { return facingYaw; }

	Entity target() { return target; }

	// ---- input (called on key edges)

	void press(Action action) {
		if (uninterruptible) return; // GE: no input is buffered during an uninterruptible ability
		queued = action;
	}

	/** Whether the heavy button is held (the heavy attack charges while it is). */
	void setHeavyHeld(boolean held) {
		heavyHeld = held;
	}

	boolean afterDodge() {
		return inLightAction;
	}

	// ---- per tick

	void tick(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null) return;
		boolean grounded = p.isOnGround();
		canDodgeInAir |= grounded;
		canAttackInAir |= grounded;
		if (invulnerableLeft > 0f) {
			invulnerableLeft -= DT;
			if (invulnerableLeft <= 0f) {
				invulnerableLeft = 0f;
				canDodge = true;
			}
		}
		if (phase == Phase.IDLE) facingYaw = p.getYaw();

		if (queued == Action.COUNTER) {
			queued = Action.NONE;
			if (!uninterruptible) {
				if (phase != Phase.IDLE) cancel(grounded);
				Entity ct = findCounterTarget(client);
				if (ct != null) {
					counterTarget = ct;
					start(client, data.role("Counter", 0), false);
				} else if (grounded) {
					start(client, data.role("FailedCounter", 0), false);
				}
			}
		}

		// The team attack (Reap) interrupts like the counter does, but needs an enemy close enough to warp to.
		if (queued == Action.TEAM) {
			queued = Action.NONE;
			if (!uninterruptible) {
				CombatData.Ability reap = data.role("TeamAttack", 0);
				if (phase != Phase.IDLE) cancel(grounded);
				if (reap != null) {
					start(client, reap, true, TEAM_RANGE);
					if (target == null) cancel(grounded);
				}
			}
		}

		// Dispatch a buffered input when Ruby is idle, in a combo window, or free to act after a dodge.
		if (queued != Action.NONE && (phase == Phase.IDLE || phase == Phase.WINDOW || actionFree)) {
			Action a = queued;
			if (dispatch(client, a, grounded)) queued = Action.NONE;
			else if (a == Action.DODGE || a == Action.TAUNT || a == Action.SPECIAL) queued = Action.NONE; // an unavailable dodge is dropped
		}

		// Turn toward the target while a move runs (GE: follow lock).
		if (followTarget && target != null && target.isAlive() && phase == Phase.MOVE) {
			facingYaw = approachAngle(facingYaw, yawTo(p, target), 0.5f);
		}

		// Hold the heavy button to charge; releasing (or reaching the maximum) swings.
		if (phase == Phase.CHARGE) {
			float before = chargeTime;
			chargeTime += DT;
			clipTime += DT;
			if (before < move.ability.fullCharge && chargeTime >= move.ability.fullCharge) Fx.chargeComplete(p.getPos());
			if (!heavyHeld || chargeTime >= move.ability.maxCharge - 1e-4f) {
				boolean full = chargeTime >= move.ability.fullCharge - 1e-4f;
				Move m = Moves.heavy(move.ability, full);
				move = m;
				moveTime = 0f;
				nextEvent = 0;
				phase = Phase.MOVE;
				phaseTime = 0f;
				clipName = m.clip;
				clipTime = 0f;
			}
		}

		// Advance the current move.
		if (phase != Phase.IDLE && phase != Phase.CHARGE) {
			clipTime += DT;
			phaseTime += DT;
			if (phase == Phase.MOVE) {
				moveTime += DT;
				while (move != null && nextEvent < move.events.size() && move.events.get(nextEvent).time <= moveTime + 1e-4f) {
					move.events.get(nextEvent++).action.accept(this);
				}
				if (moveTime >= move.duration - 1e-4f) {
					phase = Phase.COOLDOWN;
					phaseTime = 0f;
				}
			}
			if (phase == Phase.COOLDOWN && phaseTime >= move.ability.cooldown - 1e-4f) {
				phase = Phase.WINDOW;
				phaseTime = 0f;
			}
			if (phase == Phase.WINDOW && phaseTime >= move.ability.comboWindow - 1e-4f) {
				cancel(grounded);
			}
		}

		applyPhysics(p);
		if (phase != Phase.IDLE) {
			p.setBodyYaw(facingYaw);
			p.prevBodyYaw = facingYaw;
		}
	}

	/** Start the ability for a queued action. Returns false if it is not available right now. */
	private boolean dispatch(MinecraftClient client, Action a, boolean grounded) {
		switch (a) {
			case DODGE: {
				if (!canDodge || !canDodgeInAir) return false;
				canDodgeInAir = false;
				return start(client, data.role(grounded ? "DodgeLand" : "DodgeAir", 0), false);
			}
			case LIGHT: {
				if (inLightAction && grounded) {
					inLightAction = false;
					attackFromGround = true;
					return start(client, data.role("LightDodgeAttack", 0), true);
				}
				if (grounded) {
					attackFromGround = true;
					return start(client, data.role("LightCombo", combo[0]), true);
				}
				if (canAttackInAir) {
					attackFromGround = false;
					return start(client, data.role("LightComboAir", combo[0]), true);
				}
				return false;
			}
			case HEAVY: {
				if (inLightAction && grounded) {
					inLightAction = false;
					attackFromGround = true;
					return start(client, data.role("HeavyCombo", Math.max(combo[0] - 1, 0)), true);
				}
				if (grounded) {
					attackFromGround = true;
					return combo[0] == 0
						? start(client, data.role("HeavyBasic", 0), true)
						: start(client, data.role("HeavyCombo", combo[0] - 1), true);
				}
				if (canAttackInAir) {
					attackFromGround = false;
					return start(client, data.role("HeavyComboAir", 0), true);
				}
				return false;
			}
			case SPECIAL: {
				if (!RubyStats.specialReady()) return false;
				CombatData.Ability ab = data.role("SpecialAttack", 0);
				if (ab == null || !start(client, ab, false)) return false;
				RubyStats.useSpecial();
				attackFromGround = grounded;
				return true;
			}
			case RANGED: {
				if (!canAttackInAir) return false;
				attackFromGround = grounded;
				return start(client, data.role("RangedCombo", combo[1]), true);
			}
			default:
				return false; // special, team attack and taunt arrive in a later milestone
		}
	}

	private boolean start(MinecraftClient client, CombatData.Ability ability, boolean autotarget) {
		return start(client, ability, autotarget, ability == null || ability.comboType != 1 ? TARGET_DISTANCE : 40.0);
	}

	private boolean start(MinecraftClient client, CombatData.Ability ability, boolean autotarget, double range) {
		if (ability == null) return false;
		ClientPlayerEntity p = client.player;
		Move m = Moves.build(ability, p.isOnGround());
		if (m == null) return false;

		// Choose where to face (GE: UseAutotargetAbility, else the direction the player is holding, else the camera).
		float aim = p.getYaw();
		Vec3d desired = desiredDirection(client, aim);
		Entity previous = target;
		boolean ranged = ability.comboType == 1;
		target = autotarget ? selectTarget(client, desired != null ? yawOf(desired) : aim, desired != null, previous, range, ranged ? 45.0 : TARGET_ANGLE_CUTOFF) : null;
		if (counterTarget != null) {
			target = counterTarget;
			counterTarget = null;
		}
		followTarget = autotarget;
		facingYaw = target != null ? yawTo(p, target) : desired != null ? yawOf(desired) : aim;

		move = m;
		moveTime = 0f;
		nextEvent = 0;
		phase = "Heavy".equals(ability.coroutine) ? Phase.CHARGE : Phase.MOVE;
		chargeTime = 0f;
		phaseTime = 0f;
		clipName = m.clip;
		clipTime = 0f;
		// GE: SetAttack
		queued = Action.NONE;
		if (ability.resetCombo) { combo[0] = 0; combo[1] = 0; }
		if (ability.increaseCombo) combo[ability.comboType]++;
		if (ability.uninterruptible) uninterruptible = true;
		if (!canDodge) { invulnerableLeft = 0f; canDodge = true; } // starting an attack ends a dodge early
		actionFree = false;
		stopPush();
		gravityActive = true; // GE: gravity is 0 at the start of every ability
		gravity = 0f;
		gravitySpeed = 1f;
		Fx.started(ability.coroutine, p.getPos());
		return true;
	}

	/** End of an ability chain (GE: Cancel). */
	void cancel(boolean grounded) {
		phase = Phase.IDLE;
		move = null;
		combo[0] = 0;
		combo[1] = 0;
		queued = Action.NONE;
		uninterruptible = false;
		inLightAction = false;
		actionFree = false;
		canAttackInAir = grounded || attackFromGround;
		attackFromGround = false;
		stopPush();
		stopPushUp();
		gravityActive = false;
		gravity = -10f;
		gravitySpeed = 1f;
		visible = true;
		target = null;
		followTarget = false;
	}

	// ---- targeting

	/** The movement keys as a world direction (relative to where the camera looks), or null when none is held. */
	private static Vec3d desiredDirection(MinecraftClient client, float aimYaw) {
		int fw = (client.options.forwardKey.isPressed() ? 1 : 0) - (client.options.backKey.isPressed() ? 1 : 0);
		int rt = (client.options.rightKey.isPressed() ? 1 : 0) - (client.options.leftKey.isPressed() ? 1 : 0);
		if (fw == 0 && rt == 0) return null;
		float y = aimYaw * MathHelper.RADIANS_PER_DEGREE;
		Vec3d forward = new Vec3d(-MathHelper.sin(y), 0, MathHelper.cos(y));
		Vec3d right = new Vec3d(-MathHelper.cos(y), 0, -MathHelper.sin(y));
		return forward.multiply(fw).add(right.multiply(rt)).normalize();
	}

	private static float yawOf(Vec3d dir) {
		return (float) (MathHelper.atan2(-dir.x, dir.z) * MathHelper.DEGREES_PER_RADIAN);
	}

	private static float yawTo(ClientPlayerEntity p, Entity e) {
		return yawOf(new Vec3d(e.getX() - p.getX(), 0, e.getZ() - p.getZ()));
	}

	private static float approachAngle(float from, float to, float t) {
		return from + MathHelper.wrapDegrees(to - from) * t;
	}

	/** Grimm Eclipse's AutotargetData scoring: closer and more centred enemies score higher. */
	private Entity selectTarget(MinecraftClient client, float aimYaw, boolean directionIndicated, Entity previous, double maxDistance, double angleCutoff) {
		ClientPlayerEntity p = client.player;
		double aim = aimYaw * MathHelper.RADIANS_PER_DEGREE;
		Vec3d forward = new Vec3d(-Math.sin(aim), 0, Math.cos(aim));
		Box box = p.getBoundingBox().expand(maxDistance);
		List<Entity> candidates = client.world.getOtherEntities(p, box, HitService::isTarget);
		Entity best = null;
		double bestScore = 0;
		double weight = directionIndicated ? TARGET_FULL_WEIGHT : TARGET_REDUCED_WEIGHT;
		for (Entity e : candidates) {
			Vec3d to = new Vec3d(e.getX() - p.getX(), 0, e.getZ() - p.getZ());
			double dist = Math.sqrt(to.lengthSquared());
			if (dist >= maxDistance) continue;
			double angle = dist < 1e-6 ? 0 : Math.toDegrees(Math.acos(MathHelper.clamp(forward.dotProduct(to.multiply(1 / dist)), -1, 1)));
			if (angle >= angleCutoff) continue;
			double angleScore = 1.0 - angle / angleCutoff;
			double distScore = 1.0 - dist / maxDistance;
			double score = angleScore * weight + distScore * (1.0 - weight);
			if (e == previous) score += 0.1; // keep the previous target unless another is clearly better
			if (score >= TARGET_MIN_SCORE && score > bestScore) {
				best = e;
				bestScore = score;
			}
		}
		return best;
	}

	/** An enemy that is attacking Ruby right now, within counter range (GE: the enemy flashes before it strikes). */
	private Entity findCounterTarget(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		Entity best = null;
		double bestDist = 8.5;
		for (Entity e : client.world.getOtherEntities(p, p.getBoundingBox().expand(8.5), HitService::isTarget)) {
			// The server's target is not known to the client, so an enemy that is in attack mode (or swinging) close by counts.
			double d = Math.hypot(e.getX() - p.getX(), e.getZ() - p.getZ());
			boolean attacking = e instanceof net.minecraft.entity.mob.MobEntity m
				&& ((m.isAttacking() && d <= 5.0) || (m.handSwinging && d <= 6.0));
			if (attacking && d < bestDist) {
				best = e;
				bestDist = d;
			}
		}
		return best;
	}

	// ---- effects used by moves

	/** Face the (re)selected target or the direction held, as GE's ReorientPlayer does before a heavy swing. */
	void reorient() {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity p = client.player;
		if (p == null) return;
		Vec3d desired = desiredDirection(client, p.getYaw());
		target = selectTarget(client, desired != null ? yawOf(desired) : p.getYaw(), desired != null, target, TARGET_DISTANCE, TARGET_ANGLE_CUTOFF);
		if (target != null) facingYaw = yawTo(p, target);
		else if (desired != null) facingYaw = yawOf(desired);
	}

	void invuln(float seconds) {
		invulnerableLeft = seconds;
		canDodge = false;
	}

	/** Counter: appear beside the enemy being countered. */
	void warpToTarget() {
		ClientPlayerEntity p = MinecraftClient.getInstance().player;
		if (p == null || target == null) return;
		Vec3d away = new Vec3d(p.getX() - target.getX(), 0, p.getZ() - target.getZ());
		away = away.lengthSquared() < 1e-6 ? new Vec3d(0, 0, -1) : away.normalize();
		p.setPosition(target.getX() + away.x * 1.5, target.getY(), target.getZ() + away.z * 1.5);
		p.setVelocity(0, 0, 0);
		facingYaw = yawTo(p, target);
		Fx.warp(p.getPos());
	}

	void strikeTarget() {
		strikeSlot(0);
		Fx.sound(net.minecraft.sound.SoundEvents.ITEM_SHIELD_BLOCK, 0.9f, 1.2f);
	}

	/** Direct damage to the current target with one of the move's attacks (Counter and Reap hit their target, not a volume). */
	void strikeSlot(int slot) {
		CombatData.Attack atk = move == null ? null : move.ability.attacks[slot];
		if (atk == null || atk.hit == null || target == null || !target.isAlive()) return;
		HitService.strike(MinecraftClient.getInstance(), this, target, atk.hit);
		Fx.swipe(facingYaw, atk.hit.damage);
	}

	/** The smashing finish of Reap. */
	void smash(int slot) {
		strikeSlot(slot);
		ClientPlayerEntity p = MinecraftClient.getInstance().player;
		if (p != null) Fx.smash(p.getPos());
	}

	void shoot(int slot) {
		CombatData.Attack atk = move == null ? null : move.ability.attacks[slot];
		if (atk != null) HitService.shoot(MinecraftClient.getInstance(), this, atk.hit, target);
		Fx.shot();
	}

	/** The third shot: GE fires a projectile whose damage lives in a prefab, so this is three bullets' worth. */
	void shootFinal() {
		CombatData.Ability basic = data.role("RangedCombo", 0);
		CombatData.Attack atk = basic == null ? null : basic.attacks[0];
		if (atk == null || atk.hit == null) return;
		CombatData.Hit h = new CombatData.Hit();
		h.damage = atk.hit.damage * 3f;
		h.knockBack = atk.hit.knockBack * 3f;
		h.hitTime = atk.hit.hitTime;
		HitService.shoot(MinecraftClient.getInstance(), this, h, target);
		Fx.shot();
	}

	void push(float speed) {
		smoothPushSpeed = 0f; // GE: Push calls StopPush first
		pushing = true;
		pushScaling = false;
		pushSpeed = speed;
	}

	void scalePush(float from, float to, float time) {
		pushScaling = true;
		pushFrom = from;
		pushTo = to;
		pushTime = time;
		pushElapsed = 0f;
	}

	/** Grimm Eclipse's upward push (Crescendo's hop): speed in units per second, easing from one value to another. */
	void pushUp(float from, float to, float seconds) {
		upping = true;
		upFrom = from;
		upTo = to;
		upTime = seconds;
		upElapsed = 0f;
	}

	void stopPushUp() {
		upping = false;
	}

	/** Switch to another animation clip part-way through a move. */
	void setClip(String name) {
		clipName = name;
		clipTime = 0f;
	}

	void stopPush() {
		pushing = false;
		pushScaling = false;
		pushSpeed = 0f;
		smoothPushSpeed = 0f;
	}

	void gravity(float g, float speed) {
		gravityActive = true;
		gravity = g;
		gravitySpeed = speed;
	}

	void gravitySpeed(float speed) {
		gravityActive = true;
		gravitySpeed = speed;
	}

	void setVisible(boolean v) {
		visible = v;
	}

	/** Dodge: invulnerable for the given time, and free to attack at once. */
	void dodge(float seconds) {
		canDodge = false;
		invulnerableLeft = seconds;
		inLightAction = true;
		actionFree = true;
	}

	void hit(int slot) {
		CombatData.Attack atk = move == null ? null : move.ability.attacks[slot];
		MinecraftClient client = MinecraftClient.getInstance();
		HitService.hit(client, this, atk);
		if (atk != null && atk.hit != null) Fx.swipe(facingYaw, atk.hit.damage);
	}

	// ---- physics

	private void applyPhysics(ClientPlayerEntity p) {
		if (!active() && !gravityActive) return;
		Vec3d v = p.getVelocity();
		double vx = v.x, vy = v.y, vz = v.z;
		if (pushing) {
			if (pushScaling) {
				pushElapsed += DT;
				float f = pushTime <= 0f ? 1f : MathHelper.clamp(pushElapsed / pushTime, 0f, 1f);
				pushSpeed = MathHelper.lerp(f, pushFrom, pushTo);
				if (f >= 1f) pushScaling = false;
			} else {
				pushSpeed -= pushSpeed * DT; // GE: the push speed fades toward 0
			}
			// GE: a forward push does not carry Ruby into the enemy she is attacking.
			boolean blocked = target != null && target.isAlive() && pushSpeed >= 0f && !inLightAction
				&& Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ()) <= PUSH_STOP_DISTANCE;
			if (!blocked) {
				smoothPushSpeed += (pushSpeed - smoothPushSpeed) * Math.min(1f, 15f * DT);
				if (smoothPushSpeed >= 20f && visible) Fx.petals(p.getPos(), 3);
				float yaw = facingYaw * MathHelper.RADIANS_PER_DEGREE;
				double perTick = smoothPushSpeed / 20.0;
				vx = -MathHelper.sin(yaw) * perTick;
				vz = MathHelper.cos(yaw) * perTick;
			} else {
				vx = 0;
				vz = 0;
			}
		} else if (inMove()) {
			vx = 0;
			vz = 0; // no walking while a move runs
		}
		if (gravityActive) {
			// Grimm Eclipse eases gravity toward -65 once a second; the vertical speed is gravity * gravitySpeed.
			gravity += (-65f - gravity) * DT;
			vy = gravity * gravitySpeed / 20.0;
		}
		if (upping) {
			upElapsed += DT;
			float f = upTime <= 0f ? 1f : MathHelper.clamp(upElapsed / upTime, 0f, 1f);
			vy += MathHelper.lerp(f, upFrom, upTo) / 20.0;
		}
		p.setVelocity(vx, vy, vz);
	}

	/** Movement input is frozen while a move runs. */
	public boolean lockMovement() {
		return inMove();
	}

	/** Height in blocks a victim rises when hit with the given knock-up (Grimm Eclipse gravity model). */
	static double knockUpApex(double knockUp) {
		double g = knockUp, h = 0, dt = 0.005;
		for (int i = 0; i < 4000 && g > 0; i++) {
			h += g * dt;
			g += (-65.0 - g) * dt;
		}
		return h;
	}
}
