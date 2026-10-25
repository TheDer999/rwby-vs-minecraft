package gg.der999.grimmmc;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.GameRules;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A Grimm (Beowolf or Creep, small, medium or large). A hostile mob that hunts players and villagers, attacks with the animations from
 * Grimm Eclipse, and smashes blocks that stand in its way, the more so the bigger it is. The client draws it with GrimmRenderer.
 */
public class GrimmEntity extends HostileEntity {
	/** The age (in ticks) at which the current attack began, -1 when it is not attacking. */
	static final TrackedData<Integer> ATTACK_START = DataTracker.registerData(GrimmEntity.class, TrackedDataHandlerRegistry.INTEGER);
	static final TrackedData<Integer> ATTACK_VARIANT = DataTracker.registerData(GrimmEntity.class, TrackedDataHandlerRegistry.INTEGER);

	final GrimmKind kind;
	final int size;

	public GrimmEntity(EntityType<? extends GrimmEntity> type, World world, GrimmKind kind, int size) {
		super(type, world);
		this.kind = kind;
		this.size = size;
		this.experiencePoints = 5 + size * 5;
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(ATTACK_START, -1);
		builder.add(ATTACK_VARIANT, 0);
	}

	static DefaultAttributeContainer.Builder attributes(GrimmKind kind, int size) {
		return HostileEntity.createHostileAttributes()
			.add(EntityAttributes.MAX_HEALTH, kind.health[size])
			.add(EntityAttributes.ATTACK_DAMAGE, kind.damage[size])
			.add(EntityAttributes.MOVEMENT_SPEED, kind.speed[size])
			.add(EntityAttributes.FOLLOW_RANGE, 20 + size * 6)
			.add(EntityAttributes.KNOCKBACK_RESISTANCE, size * 0.25)
			.add(EntityAttributes.ATTACK_KNOCKBACK, 0.3 + size * 0.3);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(1, new SwimGoal(this));
		goalSelector.add(2, new AttackGoal(this));
		goalSelector.add(5, new WanderAroundFarGoal(this, 0.8));
		goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 10.0f));
		goalSelector.add(7, new LookAroundGoal(this));
		targetSelector.add(1, new RevengeGoal(this));
		// Grimm hunt by their prey's presence, not by sight: they come for a player behind walls too (and then dig).
		targetSelector.add(2, new PreyGoal(this));
		targetSelector.add(3, new ActiveTargetGoal<>(this, MerchantEntity.class, true));
	}

	@Override
	public void onTrackedDataSet(TrackedData<?> data) {
		super.onTrackedDataSet(data);
		// The client hears the Grimm snarl as an attack begins.
		if (data == ATTACK_START && getWorld().isClient() && dataTracker.get(ATTACK_START) >= 0) {
			Sounds.grimmVoice(kind.voice(), getPos(), size);
		}
	}

	GrimmKind kind() { return kind; }

	int size() { return size; }

	/** Ticks since the current attack began, or -1 when not attacking (valid on both sides). */
	int attackTicks() {
		int start = dataTracker.get(ATTACK_START);
		if (start < 0) return -1;
		int t = age - start;
		return t <= (int) (kind.attackSeconds[dataTracker.get(ATTACK_VARIANT) % kind.attackClips.length] * 20f) ? t : -1;
	}

	int attackVariant() {
		return dataTracker.get(ATTACK_VARIANT) % kind.attackClips.length;
	}

	// ---- death plays out its animation for about two seconds

	@Override
	protected void updatePostDeath() {
		deathTime++;
		if (deathTime >= (int) (kind.deathSeconds() * 20f) + 8 && !getWorld().isClient() && !isRemoved()) {
			getWorld().sendEntityStatus(this, (byte) 60);
			remove(RemovalReason.KILLED);
		}
	}

	// ---- attacking and digging (server side)

	/** Reach in blocks from the edge of its body. */
	double reach() {
		return 1.3 + 0.5 * size;
	}

	boolean inReach(LivingEntity target) {
		double gap = Math.sqrt(squaredDistanceTo(target)) - getWidth() / 2.0 - target.getWidth() / 2.0;
		return gap <= reach();
	}

	void startAttack(LivingEntity target) {
		// face the prey: the blow, and the digging in front of it, go that way
		double dx = target.getX() - getX(), dz = target.getZ() - getZ();
		float yaw = (float) (MathHelper.atan2(-dx, dz) * 57.29577951308232);
		setYaw(yaw);
		setBodyYaw(yaw);
		setHeadYaw(yaw);
		int variant = random.nextInt(kind.attackClips.length);
		dataTracker.set(ATTACK_VARIANT, variant);
		dataTracker.set(ATTACK_START, age);
	}

	/** The blow of the attack lands: hurts the target if still in reach, and smashes what stands in front. */
	void strike(LivingEntity target) {
		if (!(getWorld() instanceof ServerWorld world)) return;
		if (kind == GrimmKind.MUTANT_CREEP) { explode(world); return; }
		if (target != null && target.isAlive() && inReach(target)) tryAttack(world, target);
		dig(world);
	}

	/** The mutant Creep blows itself up: a big explosion that harms what is near and, unless digging is off or placed blocks are spared, breaks blocks. */
	void explode(ServerWorld world) {
		float power = new float[] {2.0f, 3.5f, 6.0f}[size];
		boolean blocks = Protection.grimmDigs() && !Protection.sparesPlaced() && world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING);
		world.createExplosion(this, getX(), getY() + getHeight() / 2.0, getZ(), power,
			blocks ? World.ExplosionSourceType.MOB : World.ExplosionSourceType.NONE);
		kill(world);
	}

	/** Breaks blocks in front of it, as deep as its size allows (small 1, medium 2, large 4 of a block's hardness). */
	void dig(ServerWorld world) {
		if (!Protection.grimmDigs() || !world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) return;
		int budget = kind.dig[size];
		float yaw = getBodyYaw() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d fwd = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
		Vec3d right = new Vec3d(-MathHelper.cos(yaw), 0, -MathHelper.sin(yaw));
		double half = getWidth() / 2.0;
		double contact = reach() + 0.4;
		int floorY = MathHelper.floor(getY() + 0.01);
		List<BlockPos> found = new ArrayList<>();
		for (double u = -half; u <= half + 1e-6; u += 0.5) {
			for (double v = 0.25; v < getHeight(); v += 0.5) {
				Vec3d start = getPos().add(fwd.multiply(half)).add(right.multiply(u)).add(0, v, 0);
				HitService.drill(world, start, fwd, contact, contact + budget + 1, budget, floorY, found);
			}
		}
		if (!found.isEmpty()) HitService.breakNow(world, this, found);
	}

	// ---- spawning: small ones by day and night, medium and large ones mostly at night

	static boolean canSpawn(net.minecraft.entity.EntityType<? extends GrimmEntity> type, ServerWorldAccess world, SpawnReason reason, BlockPos pos, Random random) {
		if (reason == SpawnReason.SPAWNER || reason == SpawnReason.COMMAND || reason == SpawnReason.SPAWN_ITEM_USE) return true;
		if (kindOf(type).mutant()) return false; // mutants only come from the night spawner
		if (!GrimmMcClient.CONFIG.grimmSpawn || !GrimmModels.available()) return false;
		if (world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) return false;
		if (!world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) return false;
		int size = sizeOf(type);
		long time = world.toServerWorld().getTimeOfDay() % 24000L;
		boolean night = time >= 13000L && time < 23000L;
		return switch (size) {
			case 0 -> true; // small ones by day and by night
			case 1 -> night;
			default -> night && random.nextInt(2) == 0;
		};
	}

	private static GrimmKind kindOf(EntityType<?> type) {
		String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(type).getPath();
		GrimmKind best = GrimmKind.BEOWOLF;
		for (GrimmKind k : GrimmKind.values()) if (id.startsWith(k.id + "_") && k.id.length() >= best.id.length()) best = k;
		return best;
	}

	private static int sizeOf(EntityType<?> type) {
		String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(type).getPath();
		for (int i = 0; i < GrimmKind.SIZE_NAMES.length; i++) if (id.endsWith("_" + GrimmKind.SIZE_NAMES[i])) return i;
		return 0;
	}

	// ---- the fighting behaviour

	/** Picks the nearest player within its follow range, seen or not. */
	static final class PreyGoal extends ActiveTargetGoal<PlayerEntity> {
		PreyGoal(GrimmEntity g) {
			super(g, PlayerEntity.class, 10, false, false, null);
			this.targetPredicate.ignoreVisibility();
		}
	}

	/** Chases its target, strikes when close and smashes through blocks when it is stuck behind them. */
	static final class AttackGoal extends Goal {
		private final GrimmEntity g;
		private int cooldown, attackLeft, repath, stuck;
		private boolean hitDone;
		private Vec3d lastPos = Vec3d.ZERO;

		AttackGoal(GrimmEntity g) {
			this.g = g;
			setControls(EnumSet.of(Control.MOVE, Control.LOOK));
		}

		@Override
		public boolean canStart() {
			LivingEntity t = g.getTarget();
			return t != null && t.isAlive();
		}

		@Override
		public boolean shouldContinue() {
			return canStart();
		}

		@Override
		public void start() {
			g.setAttacking(true);
			repath = 0;
			stuck = 0;
			lastPos = g.getPos();
		}

		@Override
		public void stop() {
			g.getNavigation().stop();
			g.setAttacking(false);
			attackLeft = 0;
		}

		@Override
		public boolean shouldRunEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			LivingEntity t = g.getTarget();
			if (t == null) return;
			g.getLookControl().lookAt(t, 40f, 40f);
			if (cooldown > 0) cooldown--;
			// Smash whatever is in the way as it advances (a Grimm does not walk around walls).
			if (attackLeft <= 0 && (g.age % (g.kind.mutant() ? 4 : 7)) == 0 && g.getWorld() instanceof ServerWorld sw && g.getPos().squaredDistanceTo(lastPos) >= 0 && !g.inReach(t)) {
				double dx = t.getX() - g.getX(), dz = t.getZ() - g.getZ();
				float yaw = (float) (MathHelper.atan2(-dx, dz) * 57.29577951308232);
				if (g.horizontalCollision || g.getNavigation().isIdle() || g.squaredDistanceTo(t) > 4) {
					g.setBodyYaw(yaw);
					g.dig(sw);
				}
			}
			if (attackLeft > 0) {
				attackLeft--;
				g.getNavigation().stop();
				int ticksIn = (int) (g.kind.attackSeconds[g.attackVariant()] * 20f) - attackLeft;
				if (!hitDone && ticksIn >= (int) (g.kind.attackHitSeconds[g.attackVariant()] * 20f)) {
					hitDone = true;
					g.strike(t);
				}
				return;
			}
			if (g.inReach(t) && g.canSee(t)) {
				g.getNavigation().stop();
				if (cooldown <= 0) begin();
				return;
			}
			if (--repath <= 0) {
				repath = 4 + g.random.nextInt(7);
				g.getNavigation().startMovingTo(t, 1.15);
			}
			// Stuck behind a wall: smash it.
			if (g.getPos().squaredDistanceTo(lastPos) < 0.01) stuck++;
			else { stuck = 0; lastPos = g.getPos(); }
			if (stuck > 12 && cooldown <= 0 && g.squaredDistanceTo(t) < 24 * 24) {
				stuck = 0;
				begin();
			}
		}

		private void begin() {
			g.startAttack(g.getTarget());
			attackLeft = (int) (g.kind.attackSeconds[g.attackVariant()] * 20f);
			hitDone = false;
			cooldown = attackLeft + 5 + g.random.nextInt(6);
		}
	}
}
