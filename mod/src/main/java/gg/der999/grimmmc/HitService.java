package gg.der999.grimmmc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Applies Ruby's attacks to Minecraft mobs. Grimm Eclipse resolves a hit as "everything overlapping a sphere in front of
 * her"; this does the same on the client, then applies damage, knockback and launch on the integrated server's thread
 * (so it works in single player only, which is all this mod supports).
 */
final class HitService {
	private HitService() {}

	/** Which entities Ruby's attacks hurt: hostile mobs (Grimm Eclipse's Grimm are her only enemies). */
	static boolean isTarget(Entity e) {
		if (!(e instanceof LivingEntity) || !e.isAlive() || e.isRemoved()) return false;
		if (e instanceof PlayerEntity || e instanceof net.minecraft.entity.decoration.ArmorStandEntity) return false;
		if (e instanceof Monster) return true;
		if (!GrimmMcClient.CONFIG.hitAllMobs) return false;
		if (e instanceof net.minecraft.entity.passive.TameableEntity t && t.isTamed()) return false;
		if (e instanceof net.minecraft.entity.passive.AbstractHorseEntity h && h.isTame()) return false;
		return true;
	}

	static void hit(MinecraftClient client, CombatController combat, CombatData.Attack atk) {
		if (atk == null || atk.hit == null || !atk.hasVolume) return;
		PlayerEntity p = client.player;
		if (p == null || client.world == null) return;
		float yawRad = combat.facingYaw() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d fwd = new Vec3d(-MathHelper.sin(yawRad), 0, MathHelper.cos(yawRad));
		Vec3d right = new Vec3d(-MathHelper.cos(yawRad), 0, -MathHelper.sin(yawRad));
		float s = CombatController.SCALE;
		Vec3d center = p.getPos()
			.add(right.multiply(atk.offset[0] * s))
			.add(0, atk.offset[1] * s, 0)
			.add(fwd.multiply(atk.offset[2] * s));
		double radius = atk.radius * s;

		List<UUID> targets = new ArrayList<>();
		Box search = new Box(center, center).expand(radius);
		for (Entity e : client.world.getOtherEntities(p, search, HitService::isTarget)) {
			Box b = e.getBoundingBox();
			double dx = MathHelper.clamp(center.x, b.minX, b.maxX) - center.x;
			double dy = MathHelper.clamp(center.y, b.minY, b.maxY) - center.y;
			double dz = MathHelper.clamp(center.z, b.minZ, b.maxZ) - center.z;
			if (dx * dx + dy * dy + dz * dz <= radius * radius) targets.add(e.getUuid());
		}
		deliver(client, p, targets, fwd, atk.hit);
	}

	/** Dirt, grass and the like: the only blocks Ruby's attacks break. */
	static boolean isBreakable(net.minecraft.block.BlockState state) {
		return state.isIn(net.minecraft.registry.tag.BlockTags.DIRT)
			|| state.isOf(net.minecraft.block.Blocks.FARMLAND) || state.isOf(net.minecraft.block.Blocks.DIRT_PATH);
	}

	/** Adds the dirt inside an attack's hit sphere, but never the ground Ruby stands on (blocks below her feet are spared). */
	private static void collectSphere(MinecraftClient client, CombatController combat, CombatData.Attack atk, List<net.minecraft.util.math.BlockPos> found) {
		PlayerEntity p = client.player;
		if (atk == null || !atk.hasVolume) return;
		float yawRad = combat.facingYaw() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d fwd = new Vec3d(-MathHelper.sin(yawRad), 0, MathHelper.cos(yawRad));
		Vec3d right = new Vec3d(-MathHelper.cos(yawRad), 0, -MathHelper.sin(yawRad));
		float s = CombatController.SCALE;
		Vec3d center = p.getPos().add(right.multiply(atk.offset[0] * s)).add(0, atk.offset[1] * s, 0).add(fwd.multiply(atk.offset[2] * s));
		collectAround(client, center, atk.radius * s, MathHelper.floor(p.getY() + 0.01), found);
	}

	/** Adds the breakable blocks whose centres lie within the radius of the point, none below minY, up to 40 in all. */
	private static void collectAround(MinecraftClient client, Vec3d center, double radius, int minY, List<net.minecraft.util.math.BlockPos> found) {
		for (net.minecraft.util.math.BlockPos pos : net.minecraft.util.math.BlockPos.iterate(
				MathHelper.floor(center.x - radius), Math.max(minY, MathHelper.floor(center.y - radius)), MathHelper.floor(center.z - radius),
				MathHelper.floor(center.x + radius), MathHelper.floor(center.y + radius), MathHelper.floor(center.z + radius))) {
			if (found.size() >= 40) return;
			if (pos.getSquaredDistance(center.x - 0.5, center.y - 0.5, center.z - 0.5) > radius * radius) continue;
			if (isBreakable(client.world.getBlockState(pos)) && !found.contains(pos)) found.add(pos.toImmutable());
		}
	}

	/** Breaks the dirt inside an attack's hit sphere. */
	static void breakInSphere(MinecraftClient client, CombatController combat, CombatData.Attack atk) {
		PlayerEntity p = client.player;
		if (!GrimmMcClient.CONFIG.breakBlocks || p == null || client.world == null) return;
		List<net.minecraft.util.math.BlockPos> found = new ArrayList<>();
		collectSphere(client, combat, atk, found);
		breakAll(client, p, found);
	}

	/**
	 * The charged heavy's break: the dirt inside the attack's own hit sphere (the swing), plus everything within 2 blocks of where
	 * the crosshair lands, up to 10 blocks away, so it does not have to be aimed at a single block. The ground right under Ruby is spared.
	 */
	static void breakHeavy(MinecraftClient client, CombatController combat, CombatData.Attack atk) {
		PlayerEntity p = client.player;
		if (!GrimmMcClient.CONFIG.breakBlocks || p == null || client.world == null) return;
		List<net.minecraft.util.math.BlockPos> found = new ArrayList<>();
		collectSphere(client, combat, atk, found);
		Camera camera = client.gameRenderer.getCamera();
		float cy = camera.getYaw() * MathHelper.RADIANS_PER_DEGREE, cp = camera.getPitch() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d dir = new Vec3d(-MathHelper.sin(cy) * MathHelper.cos(cp), -MathHelper.sin(cp), MathHelper.cos(cy) * MathHelper.cos(cp));
		Vec3d from = camera.getPos();
		BlockHitResult block = client.world.raycast(new RaycastContext(from, from.add(dir.multiply(10)), RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
		if (block.getType() == HitResult.Type.BLOCK) {
			List<net.minecraft.util.math.BlockPos> burst = new ArrayList<>();
			collectAround(client, block.getPos(), 2.0, Integer.MIN_VALUE / 2, burst);
			int floorY = MathHelper.floor(p.getY() + 0.01);
			for (net.minecraft.util.math.BlockPos pos : burst) {
				boolean underRuby = pos.getY() < floorY && Math.hypot(pos.getX() + 0.5 - p.getX(), pos.getZ() + 0.5 - p.getZ()) < 2.0;
				if (!underRuby && !found.contains(pos) && found.size() < 40) found.add(pos);
			}
		}
		breakAll(client, p, found);
	}

	private static void breakAll(MinecraftClient client, PlayerEntity p, List<net.minecraft.util.math.BlockPos> positions) {
		MinecraftServer server = client.getServer();
		if (server == null || positions.isEmpty()) return;
		UUID playerId = p.getUuid();
		RegistryKey<World> dim = client.world.getRegistryKey();
		server.execute(() -> {
			ServerWorld world = server.getWorld(dim);
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
			if (world == null || player == null) return;
			for (net.minecraft.util.math.BlockPos pos : positions) {
				if (isBreakable(world.getBlockState(pos)) && world.canPlayerModifyAt(player, pos)) world.breakBlock(pos, true, player);
			}
		});
	}

	/** A ranged hit: a hitscan along the aim line (target centre, or where Ruby looks), stopped by blocks. */
	static void shoot(MinecraftClient client, CombatController combat, CombatData.Hit h, Entity aim) {
		PlayerEntity p = client.player;
		if (p == null || client.world == null || h == null) return;
		// Aim like a third-person shooter: what the crosshair points at (seen from the camera) is where the shot goes,
		// and it leaves from Ruby's gun toward that point, so cover in front of her still blocks it.
		Camera camera = client.gameRenderer.getCamera();
		float cy = camera.getYaw() * MathHelper.RADIANS_PER_DEGREE, cp = camera.getPitch() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d camDir = new Vec3d(-MathHelper.sin(cy) * MathHelper.cos(cp), -MathHelper.sin(cp), MathHelper.cos(cy) * MathHelper.cos(cp));
		Vec3d aimPoint = crosshairPoint(client, p, camera.getPos(), camDir);
		float fy = combat.facingYaw() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d eye = p.getPos().add(0, 1.25, 0)
			.add(-MathHelper.sin(fy) * 0.6, 0, MathHelper.cos(fy) * 0.6)
			.add(-MathHelper.cos(fy) * 0.2, 0, -MathHelper.sin(fy) * 0.2);
		Vec3d dir = aimPoint.subtract(eye);
		dir = dir.lengthSquared() < 1e-4 ? camDir : dir.normalize();
		Vec3d end = eye.add(dir.multiply(80));
		BlockHitResult block = client.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
		if (block.getType() != HitResult.Type.MISS) end = block.getPos();
		Entity best = null;
		Vec3d bestPoint = end;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : client.world.getOtherEntities(p, new Box(eye, end).expand(1.0), HitService::isTarget)) {
			java.util.Optional<Vec3d> point = e.getBoundingBox().expand(0.3).raycast(eye, end);
			if (point.isEmpty()) continue;
			double d = eye.squaredDistanceTo(point.get());
			if (d < bestDist) { bestDist = d; best = e; bestPoint = point.get(); }
		}
		Vec3d tip = best != null ? bestPoint : end;
		double len = tip.distanceTo(eye);
		for (double s = 0.8; s < len; s += 0.8) {
			Vec3d at = eye.add(dir.multiply(s));
			client.world.addParticle(ParticleTypes.CRIT, at.x, at.y, at.z, 0, 0, 0);
		}
		if (best != null) {
			List<UUID> one = new ArrayList<>();
			one.add(best.getUuid());
			deliver(client, p, one, dir.multiply(1, 0, 1).normalize(), h);
		}
	}

	/** The first thing the camera's centre line meets (an enemy or a block), or a far point if it meets nothing. */
	private static Vec3d crosshairPoint(MinecraftClient client, PlayerEntity p, Vec3d from, Vec3d dir) {
		Vec3d end = from.add(dir.multiply(100));
		BlockHitResult block = client.world.raycast(new RaycastContext(from, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
		if (block.getType() != HitResult.Type.MISS) end = block.getPos();
		Vec3d best = end;
		double bestDist = from.squaredDistanceTo(end);
		for (Entity e : client.world.getOtherEntities(p, new Box(from, end).expand(1.0), HitService::isTarget)) {
			java.util.Optional<Vec3d> point = e.getBoundingBox().expand(0.15).raycast(from, end);
			if (point.isEmpty()) continue;
			double d = from.squaredDistanceTo(point.get());
			if (d < bestDist) { bestDist = d; best = point.get(); }
		}
		return best;
	}

	/** Hits one entity directly (the counter strike). */
	static void strike(MinecraftClient client, CombatController combat, Entity target, CombatData.Hit h) {
		PlayerEntity p = client.player;
		if (p == null || target == null || h == null) return;
		float yaw = combat.facingYaw() * MathHelper.RADIANS_PER_DEGREE;
		List<UUID> one = new ArrayList<>();
		one.add(target.getUuid());
		deliver(client, p, one, new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw)), h);
	}

	private static void deliver(MinecraftClient client, PlayerEntity p, List<UUID> targets, Vec3d fwd, CombatData.Hit h) {
		if (targets.isEmpty()) return;
		MinecraftServer server = client.getServer();
		if (server == null) return; // not single player: nothing to hit with
		float damage = h.damage * GrimmMcClient.CONFIG.damageScale;
		UUID playerId = p.getUuid();
		RegistryKey<World> dim = client.world.getRegistryKey();
		Vec3d origin = p.getPos();
		GrimmMcClient.hitsLanded += targets.size();
		RubyStats.addHits(targets.size());
		server.execute(() -> apply(server, dim, playerId, targets, origin, fwd, damage, h));
	}

	private static void apply(MinecraftServer server, RegistryKey<World> dim, UUID playerId, List<UUID> targets,
			Vec3d origin, Vec3d fwd, float damage, CombatData.Hit h) {
		ServerWorld world = server.getWorld(dim);
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
		if (world == null || player == null) return;
		for (UUID id : targets) {
			Entity e = world.getEntity(id);
			if (!(e instanceof LivingEntity target) || !target.isAlive()) continue;
			target.timeUntilRegen = 0; // Grimm Eclipse has no hit immunity between hits
			target.damage(world, world.getDamageSources().playerAttack(player), Math.max(damage, 0.01f));

			// Knockback: Grimm Eclipse pushes for hitTime seconds at knockBack units per second. Negative pulls the target in.
			Vec3d away = new Vec3d(target.getX() - origin.x, 0, target.getZ() - origin.z);
			away = away.lengthSquared() < 1e-6 ? fwd : away.normalize();
			double scale = GrimmMcClient.CONFIG.knockbackScale;
			double distance = h.knockBack * h.hitTime * scale;
			// Grimm Eclipse's light hits stagger an enemy more than they shove it; in Minecraft that reads as no knockback at
			// all, so every hit that pushes at all moves its victim at least about two thirds of a block.
			if (h.knockBack > 0f && scale > 0) distance = Math.max(distance, 0.7 * scale / 0.35);
			double horizontal = distance * 0.45; // ground friction in Minecraft spreads a velocity over about 2.2 ticks of travel
			Vec3d v = target.getVelocity();
			double vy = v.y;
			if (h.knockUp > 0f) {
				double apex = CombatController.knockUpApex(h.knockUp) * GrimmMcClient.CONFIG.knockbackScale;
				vy = Math.max(vy, Math.sqrt(0.16 * apex) * 1.05);
			}
			target.setVelocity(away.x * horizontal, vy, away.z * horizontal);
			target.velocityModified = true;

			world.spawnParticles(h.damage >= 100f ? ParticleTypes.CRIT : ParticleTypes.ENCHANTED_HIT,
				target.getX(), target.getBodyY(0.6), target.getZ(), h.damage >= 100f ? 12 : 4, 0.25, 0.25, 0.25, 0.1);
		}
		world.playSound(null, player.getBlockPos(),
			damage >= 1f ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
			SoundCategory.PLAYERS, 0.8f, 1.0f);
	}
}
