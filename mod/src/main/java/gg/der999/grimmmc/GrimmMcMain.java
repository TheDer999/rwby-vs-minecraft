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
		final int[] weights = {10, 6, 2};
		final int[] groupMax = {2, 1, 1};
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
				BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(), SpawnGroup.MONSTER, type, weights[size], 1, groupMax[size]);
				TYPES.add(type);
			}
		}
	}
}
