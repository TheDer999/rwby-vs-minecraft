package gg.der999.grimmmc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnLocationTypes;
import net.minecraft.entity.SpawnRestriction;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Registers the Grimm: three sizes of each kind, spawning in the overworld (small ones also by day). */
public class GrimmMcMain implements ModInitializer {
	static final List<EntityType<GrimmEntity>> TYPES = new ArrayList<>();

	@Override
	public void onInitialize() {
		final int[] weights = {10, 6, 3};
		final int[] groupMax = {3, 2, 1};
		for (GrimmKind kind : GrimmKind.values()) {
			for (int size = 0; size < 3; size++) {
				final GrimmKind k = kind;
				final int sz = size;
				Identifier id = Identifier.of("grimmmc", kind.entityName(size));
				RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, id);
				EntityType<GrimmEntity> type = Registry.register(Registries.ENTITY_TYPE, key,
					EntityType.Builder.create((EntityType<GrimmEntity> t, net.minecraft.world.World w) -> new GrimmEntity(t, w, k, sz), SpawnGroup.MONSTER)
						.dimensions(kind.width[size], kind.height[size])
						.maxTrackingRange(10)
						.build(key));
				FabricDefaultAttributeRegistry.register(type, GrimmEntity.attributes(kind, size));
				SpawnRestriction.register(type, SpawnLocationTypes.ON_GROUND, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, GrimmEntity::canSpawn);
				if (!kind.mutant()) BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), SpawnGroup.MONSTER, type, weights[size], 1, groupMax[size]);
				TYPES.add(type);
			}
		}
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_WORLD_TICK.register(GrimmMcMain::spawnTick);
		// Prestige 5: +10% movement speed while the player is Ruby.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % 10 != 0) return;
			for (net.minecraft.server.network.ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
				var speed = pl.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.MOVEMENT_SPEED);
				if (speed == null) continue;
				Identifier mod = Identifier.of("grimmmc", "prestige_speed");
				boolean want = GrimmMcClient.possessed && RubyLevel.fasterRuby();
				boolean has = speed.getModifier(mod) != null;
				if (want && !has) speed.addTemporaryModifier(new net.minecraft.entity.attribute.EntityAttributeModifier(mod, 0.10, net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
				else if (!want && has) speed.removeModifier(mod);
			}
		});
		// A Grimm the player kills, as Ruby or as Steve, earns points toward Ruby's level.
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof GrimmEntity grimm && source.getAttacker() instanceof net.minecraft.server.network.ServerPlayerEntity player) {
				RubyLevel.award(player, RubyLevel.pointsFor(grimm));
			}
		});
	}

	/**
	 * A mutant Grimm, rare and only at night: on average once in `mutant_rarity` checks (every 3 seconds) per player, never while one
	 * is already near, medium or large, 40 to 60 blocks away.
	 */
	static boolean spawnMutant(net.minecraft.server.world.ServerWorld world, net.minecraft.server.network.ServerPlayerEntity player, net.minecraft.util.math.random.Random r, boolean force) {
		if (!force) {
			int rarity = RubyLevel.mutantRarity(GrimmMcClient.CONFIG.mutantRarity);
			if (rarity <= 0 || r.nextInt(rarity) != 0) return false;
			java.util.List<GrimmEntity> near = world.getEntitiesByClass(GrimmEntity.class, player.getBoundingBox().expand(150), e -> e.kind().mutant());
			if (!near.isEmpty()) return false;
		}
		GrimmKind[] mutants = {GrimmKind.MUTANT_BEOWOLF, GrimmKind.MUTANT_CREEP, GrimmKind.MUTANT_DEATHSTALKER};
		GrimmKind kind = mutants[r.nextInt(mutants.length)];
		int size = r.nextInt(100) < RubyLevel.mutantMediumChance() ? 1 : 2;
		for (int attempt = 0; attempt < 24; attempt++) {
			if (attempt == 12) size = 1; // big hitboxes rarely fit on uneven ground: fall back to medium
			double angle = r.nextDouble() * Math.PI * 2, dist = 36 + r.nextDouble() * 20;
			int x = net.minecraft.util.math.MathHelper.floor(player.getX() + Math.cos(angle) * dist);
			int z = net.minecraft.util.math.MathHelper.floor(player.getZ() + Math.sin(angle) * dist);
			if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
			int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
			if (!world.getBlockState(pos.down()).isSolidBlock(world, pos.down()) || !world.getFluidState(pos).isEmpty()) continue;
			GrimmEntity g = TYPES.get(kind.ordinal() * 3 + size).create(world, net.minecraft.entity.SpawnReason.NATURAL);
			if (g == null) continue;
			g.refreshPositionAndAngles(x + 0.5, y + 0.1, z + 0.5, r.nextFloat() * 360f, 0f);
			if (!world.isSpaceEmpty(g)) { g.discard(); continue; }
			g.initialize(world, world.getLocalDifficulty(pos), net.minecraft.entity.SpawnReason.NATURAL, null);
			world.spawnEntityAndPassengers(g);
			if (GrimmMcClient.AUTO) GrimmMcClient.LOG.info("GRIMM-TEST a mutant spawned: {} at {} blocks from the player", kind.entityName(size), (int) Math.sqrt(g.squaredDistanceTo(player)));
			return true;
		}
		return false;
	}

	/**
	 * Keeps Grimm around the players, in addition to the biome spawns: by day a few small ones, at night many, up to large ones.
	 * Every 3 seconds, for each player with fewer Grimm nearby than the limit, a group appears 24 to 44 blocks away.
	 */
	private static void spawnTick(net.minecraft.server.world.ServerWorld world) {
		if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD || world.getTime() % 60 != 0) return;
		if (!GrimmMcClient.CONFIG.grimmSpawn || !GrimmModels.available() || world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) return;
		if (!world.getGameRules().getBoolean(net.minecraft.world.GameRules.DO_MOB_SPAWNING)) return;
		long time = world.getTimeOfDay() % 24000L;
		boolean night = time >= 13000L && time < 23000L;
		int cap = Math.round((night ? 14 : 5) * GrimmMcClient.CONFIG.grimmSpawnMultiplier);
		for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
			if (player.isSpectator()) continue;
			net.minecraft.util.math.Box area = player.getBoundingBox().expand(80);
			int near = world.getEntitiesByClass(GrimmEntity.class, area, e -> true).size();
			net.minecraft.util.math.random.Random r = world.getRandom();
			if (night) spawnMutant(world, player, r, false);
			if (near >= cap) continue;
			// kind and size: by day only small ones; at night a mix, weighted to the bigger ones the later it is
			GrimmKind kind = GrimmKind.REGULAR[r.nextInt(GrimmKind.REGULAR.length)];
			int size = 0;
			if (night) {
				int roll = r.nextInt(100);
				size = roll < 40 ? 0 : roll < 75 ? 1 : 2;
			}
			int count = size == 0 ? 1 + r.nextInt(3) : size == 1 ? 1 + r.nextInt(2) : 1;
			for (int i = 0; i < count && near + i < cap + 2; i++) {
				double angle = r.nextDouble() * Math.PI * 2, dist = 24 + r.nextDouble() * 20;
				int x = net.minecraft.util.math.MathHelper.floor(player.getX() + Math.cos(angle) * dist);
				int z = net.minecraft.util.math.MathHelper.floor(player.getZ() + Math.sin(angle) * dist);
				if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
				int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
				if (!world.getBlockState(pos.down()).isSolidBlock(world, pos.down()) || !world.getFluidState(pos).isEmpty()) continue;
				EntityType<GrimmEntity> type = TYPES.get(kind.ordinal() * 3 + size);
				GrimmEntity g = type.create(world, net.minecraft.entity.SpawnReason.NATURAL);
				if (g == null) continue;
				g.refreshPositionAndAngles(x + 0.5, y, z + 0.5, r.nextFloat() * 360f, 0f);
				if (!world.isSpaceEmpty(g)) { g.discard(); continue; }
				g.initialize(world, world.getLocalDifficulty(pos), net.minecraft.entity.SpawnReason.NATURAL, null);
				world.spawnEntityAndPassengers(g);
			}
		}
	}
}
