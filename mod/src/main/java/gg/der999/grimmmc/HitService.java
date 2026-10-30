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
		hit(client, combat, atk, 1f, 1f);
	}

	/** An attack with its volume and damage scaled (the level 10 Crescendo). */
	static void hit(MinecraftClient client, CombatController combat, CombatData.Attack atk, float radiusScale, float damageScale) {
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
		double radius = atk.radius * s * radiusScale;

		List<UUID> targets = new ArrayList<>();
		Box search = new Box(center, center).expand(radius);
		for (Entity e : client.world.getOtherEntities(p, search, HitService::isTarget)) {
			Box b = e.getBoundingBox();
			double dx = MathHelper.clamp(center.x, b.minX, b.maxX) - center.x;
			double dy = MathHelper.clamp(center.y, b.minY, b.maxY) - center.y;
			double dz = MathHelper.clamp(center.z, b.minZ, b.maxZ) - center.z;
			if (dx * dx + dy * dy + dz * dz <= radius * radius) targets.add(e.getUuid());
		}
		deliver(client, p, targets, fwd, atk.hit, damageScale);
	}

	/** Sand, gravel, wood, leaves and glass: as hard as dirt, but breaking one takes its neighbours of the same kind with it. 0 if the block is none of them. */
	private static int bulkKind(net.minecraft.block.BlockState state) {
		if (state.isIn(net.minecraft.registry.tag.BlockTags.SAND)) return 1;
		if (state.isOf(net.minecraft.block.Blocks.GRAVEL)) return 2;
		if (state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)) return 3;
		if (state.isIn(net.minecraft.registry.tag.BlockTags.PLANKS) || state.isIn(net.minecraft.registry.tag.BlockTags.WOODEN_SLABS)
			|| state.isIn(net.minecraft.registry.tag.BlockTags.WOODEN_STAIRS) || state.isIn(net.minecraft.registry.tag.BlockTags.WOODEN_FENCES)) return 4;
		if (state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) return 5;
		if (state.isIn(net.minecraft.registry.tag.BlockTags.IMPERMEABLE)
			|| (state.getBlock() instanceof net.minecraft.block.PaneBlock && !state.isOf(net.minecraft.block.Blocks.IRON_BARS))) return 6;
		return 0;
	}

	/**
	 * How much of an attack's depth a block uses up to break, 0 if Ruby cannot break it. Every block can be broken except those Minecraft
	 * itself makes unbreakable (bedrock, barriers, command blocks, portal frames...) and liquids. The cost follows the block's own hardness:
	 * 1 for soft blocks and dirt, sand, gravel, wood, leaves and glass; 2 for stone and the like (hardness up to 5); 3 for obsidian and the
	 * like (up to 50); 4 for the very hardest (reinforced deepslate).
	 */
	static int cost(net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos pos, net.minecraft.block.BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty()) return 0;
		float hardness = state.getHardness(world, pos);
		if (hardness < 0f) return 0; // bedrock, barrier, command blocks, end portal frame...
		if (state.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || state.isOf(net.minecraft.block.Blocks.FARMLAND)
			|| state.isOf(net.minecraft.block.Blocks.DIRT_PATH) || bulkKind(state) != 0) return 1;
		if (hardness <= 1f) return 1;
		if (hardness <= 5f) return 2;
		return hardness <= 50f ? 3 : 4;
	}

	/** Breaking a bulk block (sand, gravel, wood, leaves, glass) also takes up to 8 connected blocks of the same kind within 2 blocks of it. */
	static void cascade(World world, net.minecraft.util.math.BlockPos origin, net.minecraft.block.BlockState state, List<net.minecraft.util.math.BlockPos> found) {
		int kind = bulkKind(state);
		if (kind == 0) return;
		java.util.ArrayDeque<net.minecraft.util.math.BlockPos> queue = new java.util.ArrayDeque<>();
		queue.add(origin);
		int extra = 0;
		while (!queue.isEmpty() && extra < 8) {
			net.minecraft.util.math.BlockPos at = queue.poll();
			for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
				net.minecraft.util.math.BlockPos next = at.offset(d);
				if (extra >= 8 || found.size() >= 60) return;
				if (Math.abs(next.getX() - origin.getX()) > 2 || Math.abs(next.getY() - origin.getY()) > 2 || Math.abs(next.getZ() - origin.getZ()) > 2) continue;
				if (found.contains(next) || bulkKind(world.getBlockState(next)) != kind || Protection.spared(Protection.dimension(world), next)) continue;
				found.add(next.toImmutable());
				queue.add(next);
				extra++;
			}
		}
	}

	/**
	 * Adds the dirt along an attack's swing, a given number of blocks deep. Parallel rays cross the attack's hit sphere in Ruby's facing
	 * direction; each takes the first `depth` breakable blocks it meets and stops at anything solid it cannot break. Blocks below
	 * her feet are spared, so she never digs her own floor away.
	 */
	private static void collectSphere(MinecraftClient client, CombatController combat, CombatData.Attack atk, int depth, List<net.minecraft.util.math.BlockPos> found) {
		PlayerEntity p = client.player;
		if (atk == null || !atk.hasVolume || depth <= 0) return;
		float yawRad = combat.facingYaw() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d fwd = new Vec3d(-MathHelper.sin(yawRad), 0, MathHelper.cos(yawRad));
		Vec3d right = new Vec3d(-MathHelper.cos(yawRad), 0, -MathHelper.sin(yawRad));
		float s = CombatController.SCALE;
		Vec3d center = p.getPos().add(right.multiply(atk.offset[0] * s)).add(0, atk.offset[1] * s, 0).add(fwd.multiply(atk.offset[2] * s));
		double radius = atk.radius * s;
		int floorY = MathHelper.floor(p.getY() + 0.01);
		for (double u = -radius; u <= radius + 1e-6; u += 0.5) {
			for (double v = -radius; v <= radius + 1e-6; v += 0.5) {
				if (u * u + v * v > radius * radius) continue;
				Vec3d start = center.subtract(fwd.multiply(radius)).add(right.multiply(u)).add(0, v, 0);
				// the swing must touch the dirt inside its sphere; from there the ray goes on until it has `depth` blocks
				drill(client.world, start, fwd, radius * 2, radius * 2 + depth + 1, depth, floorY, found);
			}
		}
	}

	/** Walks a line and adds up to `depth` breakable blocks along it; it ends at the first solid block that cannot be broken. */
	static void drill(World world, Vec3d start, Vec3d dir, double contactReach, double length, int depth, int minY, List<net.minecraft.util.math.BlockPos> found) {
		int used = 0;
		net.minecraft.util.math.BlockPos last = null;
		for (double t = 0; t <= length && used < depth && found.size() < 60; t += 0.25) {
			if (used == 0 && t > contactReach) break; // nothing breakable within reach of the swing
			Vec3d at = start.add(dir.multiply(t));
			net.minecraft.util.math.BlockPos pos = net.minecraft.util.math.BlockPos.ofFloored(at);
			if (pos.equals(last)) continue;
			last = pos;
			if (pos.getY() < minY) continue;
			net.minecraft.block.BlockState state = world.getBlockState(pos);
			int c = cost(world, pos, state);
			if (c > 0) {
				if (Protection.spared(Protection.dimension(world), pos)) break; // a block a player placed, spared by option
				if (c > depth - used) break; // too hard for what is left of this attack's depth
				used += c;
				if (!found.contains(pos)) {
					found.add(pos.toImmutable());
					cascade(world, pos, state, found);
				}
			} else if (!state.getCollisionShape(world, pos).isEmpty()) {
				break;
			}
		}
	}

	/** Adds the breakable blocks whose centres lie within the radius of the point, none below minY, up to 40 in all. */
	private static void collectAround(MinecraftClient client, Vec3d center, double radius, int minY, List<net.minecraft.util.math.BlockPos> found) {
		for (net.minecraft.util.math.BlockPos pos : net.minecraft.util.math.BlockPos.iterate(
				MathHelper.floor(center.x - radius), Math.max(minY, MathHelper.floor(center.y - radius)), MathHelper.floor(center.z - radius),
				MathHelper.floor(center.x + radius), MathHelper.floor(center.y + radius), MathHelper.floor(center.z + radius))) {
			if (found.size() >= 40) return;
			if (pos.getSquaredDistance(center.x - 0.5, center.y - 0.5, center.z - 0.5) > radius * radius) continue;
			// the crosshair burst takes whatever costs up to 3 (not the very hardest)
			int c = cost(client.world, pos, client.world.getBlockState(pos));
			if (c > 0 && c <= 3 && !found.contains(pos) && !Protection.spared(Protection.dimension(client.world), pos)) found.add(pos.toImmutable());
		}
	}

	/** Breaks the dirt along an attack's swing, `depth` blocks deep. */
	static void breakInSphere(MinecraftClient client, CombatController combat, CombatData.Attack atk, int depth) {
		PlayerEntity p = client.player;
		if (!Protection.rubyDigs() || p == null || client.world == null) return;
		List<net.minecraft.util.math.BlockPos> found = new ArrayList<>();
		collectSphere(client, combat, atk, depth, found);
		if (GrimmMcClient.AUTO) GrimmMcClient.LOG.info("break depth {} found {} at yaw {} pos {}", depth, found.size(), combat.facingYaw(), p.getBlockPos());
		breakAll(client, p, found);
	}

	/**
	 * The charged heavy's break: the dirt inside the attack's own hit sphere (the swing), plus everything within 2 blocks of where
	 * the crosshair lands, up to 10 blocks away, so it does not have to be aimed at a single block. The ground right under Ruby is spared.
	 */
	static void breakHeavy(MinecraftClient client, CombatController combat, CombatData.Attack atk) {
		PlayerEntity p = client.player;
		if (!Protection.rubyDigs() || p == null || client.world == null) return;
		List<net.minecraft.util.math.BlockPos> found = new ArrayList<>();
		collectSphere(client, combat, atk, 6, found);
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
			breakNow(world, player, positions);
		});
	}

	/**
	 * Breaks the given blocks on the server thread: what each drops, or itself when that is nothing; skips what cannot be broken or is
	 * spared. `breaker` is Ruby's player or the Grimm that smashed them; a player is also checked for permission to change the spot.
	 */
	static int breakNow(ServerWorld world, Entity breaker, List<net.minecraft.util.math.BlockPos> positions) {
		int broken = 0;
		for (net.minecraft.util.math.BlockPos pos : positions) {
			net.minecraft.block.BlockState state = world.getBlockState(pos);
			if (cost(world, pos, state) == 0 || Protection.spared(Protection.dimension(world), pos)) continue;
			if (breaker instanceof PlayerEntity pl && !world.canPlayerModifyAt(pl, pos)) continue;
			List<net.minecraft.item.ItemStack> drops = net.minecraft.block.Block.getDroppedStacks(state, world, pos, world.getBlockEntity(pos), breaker, net.minecraft.item.ItemStack.EMPTY);
			if (drops.isEmpty() && state.getBlock().asItem() != net.minecraft.item.Items.AIR) drops = List.of(new net.minecraft.item.ItemStack(state.getBlock()));
			world.breakBlock(pos, false, breaker);
			Protection.forget(world, pos);
			for (net.minecraft.item.ItemStack stack : drops) net.minecraft.block.Block.dropStack(world, pos, stack);
			broken++;
		}
		return broken;
	}
	/** A ranged hit: a hitscan along the aim line (target centre, or where Ruby looks), stopped by blocks. */
	static void shoot(MinecraftClient client, CombatController combat, CombatData.Hit h, Entity aim, int breakDepth) {
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
		boolean blockHit = block.getType() != HitResult.Type.MISS;
		Entity best = null;
		Vec3d bestPoint = end;
		double bestDist = Double.MAX_VALUE;
		// everything the line meets, nearest first: a piercing shot (level 3) goes through them all
		List<Entity> onLine = new ArrayList<>();
		java.util.Map<Entity, Double> lineDist = new java.util.HashMap<>();
		for (Entity e : client.world.getOtherEntities(p, new Box(eye, end).expand(1.0), HitService::isTarget)) {
			java.util.Optional<Vec3d> point = e.getBoundingBox().expand(0.3).raycast(eye, end);
			if (point.isEmpty()) continue;
			double d = eye.squaredDistanceTo(point.get());
			onLine.add(e);
			lineDist.put(e, d);
			if (d < bestDist) { bestDist = d; best = e; bestPoint = point.get(); }
		}
		onLine.sort(java.util.Comparator.comparingDouble(lineDist::get));
		boolean pierce = RubyLevel.piercingShots();
		// where the shot stops: the first enemy, or (piercing) the wall or the far end
		Vec3d tip = best != null && !pierce ? bestPoint : end;
		double len = tip.distanceTo(eye);
		for (double s = 0.8; s < len; s += 0.8) {
			Vec3d at = eye.add(dir.multiply(s));
			Glow.spark(at, 0, 0, 0, 14, 0.1f);
		}
		// A shot digs into dirt along its line like a light attack: one block deep for the first shot, two, then three.
		if (breakDepth > 0 && Protection.rubyDigs()) {
			List<net.minecraft.util.math.BlockPos> dug = new ArrayList<>();
			double shotLength = Math.min(80.0, best != null && !pierce ? len : len + breakDepth + 0.5);
			drill(client.world, eye, dir, shotLength, shotLength, breakDepth, Integer.MIN_VALUE / 2, dug);
			int floorY = MathHelper.floor(p.getY() + 0.01);
			// spare the ground right under Ruby
			dug.removeIf(pos -> pos.getY() < floorY && Math.hypot(pos.getX() + 0.5 - p.getX(), pos.getZ() + 0.5 - p.getZ()) < 2.0);
			breakAll(client, p, dug);
		}
		List<UUID> struck = new ArrayList<>();
		if (best != null) {
			if (pierce) for (Entity e : onLine) { if (struck.size() < 6) struck.add(e.getUuid()); }
			else struck.add(best.getUuid());
		}
		// Every shot shows a burst where it lands (it helps to aim); from level 5 it also hurts what is near and breaks the softer blocks around the spot.
		if (best != null || blockHit) {
			Vec3d at = best != null && !pierce ? bestPoint : (blockHit ? end : bestPoint);
			for (int i = 0; i < 8; i++) Glow.puff(at, (Math.random() - 0.5) * 0.2, (Math.random() - 0.3) * 0.15, (Math.random() - 0.5) * 0.2, 10 + (int) (Math.random() * 6), 0.3f, 255, 225, 200);
			for (int i = 0; i < 14; i++) Glow.spark(at, (Math.random() - 0.5) * 0.35, (Math.random() - 0.2) * 0.3, (Math.random() - 0.5) * 0.35, 12 + (int) (Math.random() * 8), 0.11f);
		}
		if (RubyLevel.burstShots() && (best != null || blockHit)) {
			Vec3d at = best != null && !pierce ? bestPoint : (blockHit ? end : bestPoint);
			for (Entity e : client.world.getOtherEntities(p, new Box(at, at).expand(2.5), HitService::isTarget)) {
				if (!struck.contains(e.getUuid()) && e.squaredDistanceTo(at) <= 2.5 * 2.5 && struck.size() < 10) struck.add(e.getUuid());
			}
			if (Protection.rubyDigs()) {
				List<net.minecraft.util.math.BlockPos> around = new ArrayList<>();
				net.minecraft.util.math.BlockPos c = net.minecraft.util.math.BlockPos.ofFloored(at);
				int floorY = MathHelper.floor(p.getY() + 0.01);
				for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
					net.minecraft.util.math.BlockPos pos = c.add(x, y, z);
					if (x * x + y * y + z * z > 4 || (pos.getY() < floorY && Math.hypot(pos.getX() + 0.5 - p.getX(), pos.getZ() + 0.5 - p.getZ()) < 2.0)) continue;
					int cost = cost(client.world, pos, client.world.getBlockState(pos));
					if (cost >= 1 && cost <= 2) around.add(pos);
				}
				breakAll(client, p, around);
			}
		}
		if (!struck.isEmpty()) deliver(client, p, struck, dir.multiply(1, 0, 1).normalize(), h, RubyLevel.shotDamageScale());
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

	/** Hits the target and every enemy within `radius` blocks of it (Reap from level 9). */
	static void strikeArea(MinecraftClient client, CombatController combat, Entity target, CombatData.Hit h, double radius) {
		PlayerEntity p = client.player;
		if (p == null || target == null || h == null || client.world == null) return;
		float yaw = combat.facingYaw() * MathHelper.RADIANS_PER_DEGREE;
		List<UUID> all = new ArrayList<>();
		all.add(target.getUuid());
		for (Entity e : client.world.getOtherEntities(p, target.getBoundingBox().expand(radius), HitService::isTarget)) {
			if (e != target && e.distanceTo(target) <= radius + e.getWidth() / 2.0) all.add(e.getUuid());
		}
		deliver(client, p, all, new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw)), h);
	}

	private static void deliver(MinecraftClient client, PlayerEntity p, List<UUID> targets, Vec3d fwd, CombatData.Hit h) {
		deliver(client, p, targets, fwd, h, 1f);
	}

	private static void deliver(MinecraftClient client, PlayerEntity p, List<UUID> targets, Vec3d fwd, CombatData.Hit h, float damageScale) {
		if (targets.isEmpty()) return;
		MinecraftServer server = client.getServer();
		if (server == null) return; // not single player: nothing to hit with
		float damage = h.damage * GrimmMcClient.CONFIG.damageScale * damageScale;
		UUID playerId = p.getUuid();
		RegistryKey<World> dim = client.world.getRegistryKey();
		Vec3d origin = p.getPos();
		GrimmMcClient.hitsLanded += targets.size();
		RubyStats.addHits(targets.size());
		Momentum.hit(1); // one per attack that connects, however many it hits
		server.execute(() -> apply(server, dim, playerId, targets, origin, fwd, damage, h));
	}

	private static void apply(MinecraftServer server, RegistryKey<World> dim, UUID playerId, List<UUID> targets,
			Vec3d origin, Vec3d fwd, float damage, CombatData.Hit h) {
		ServerWorld world = server.getWorld(dim);
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
		if (world == null || player == null) return;
		int level = Momentum.level();
		damage *= Momentum.damageMultiplier(level) * RubyLevel.damageBonus(); // the combo's power level and prestige make every hit stronger
		for (UUID id : targets) {
			Entity e = world.getEntity(id);
			if (!(e instanceof LivingEntity target) || !target.isAlive()) continue;
			target.timeUntilRegen = 0; // Grimm Eclipse has no hit immunity between hits
			target.damage(world, world.getDamageSources().playerAttack(player), Math.max(damage, 0.01f));
			if (!target.isAlive()) { // a kill gives health back, more at higher power levels
				player.heal(Momentum.healFor(target, level));
				world.spawnParticles(ParticleTypes.HEART, player.getX(), player.getBodyY(0.9), player.getZ(), 3, 0.3, 0.3, 0.3, 0.0);
			}

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
		Momentum.applyEffects(player, level);
		world.playSound(null, player.getBlockPos(),
			damage >= 1f ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
			SoundCategory.PLAYERS, 0.8f, 1.0f);
	}
}

