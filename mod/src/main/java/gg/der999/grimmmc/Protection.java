package gg.der999.grimmmc;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whether Ruby and the Grimm may break blocks in this world, and which blocks they spare. The settings start from the config file
 * (break_blocks, grimm_break_blocks, spare_placed), can be changed in game with /grimmmc, and are saved with the world.
 * It also remembers the blocks players placed, so that they can be spared. Read from the client and server threads, hence synchronized.
 */
final class Protection {
	private Protection() {}

	private static boolean ruby = true, grimm = true, sparePlaced;
	private static boolean dirty;
	/** Blocks players placed, per dimension, as BlockPos.asLong. */
	private static final Map<String, Set<Long>> PLACED = new HashMap<>();

	static void init() {
		ruby = GrimmMcClient.CONFIG.breakBlocks;
		grimm = GrimmMcClient.CONFIG.grimmBreakBlocks;
		sparePlaced = GrimmMcClient.CONFIG.sparePlaced;
		ServerLifecycleEvents.SERVER_STARTED.register(Protection::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(Protection::save);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % 6000 == 0) save(server);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
			CommandManager.literal("grimmmc")
				.then(CommandManager.literal("status").executes(c -> {
					c.getSource().sendFeedback(() -> Text.literal(describe()), false);
					return 1;
				}))
				.then(CommandManager.literal("digging")
					.then(CommandManager.literal("ruby").then(CommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
						set(0, BoolArgumentType.getBool(c, "on"));
						c.getSource().sendFeedback(() -> Text.literal(describe()), false);
						return 1;
					})))
					.then(CommandManager.literal("grimm").then(CommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
						set(1, BoolArgumentType.getBool(c, "on"));
						c.getSource().sendFeedback(() -> Text.literal(describe()), false);
						return 1;
					})))
					.then(CommandManager.literal("all").then(CommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
						set(0, BoolArgumentType.getBool(c, "on"));
						set(1, BoolArgumentType.getBool(c, "on"));
						c.getSource().sendFeedback(() -> Text.literal(describe()), false);
						return 1;
					}))))
				.then(CommandManager.literal("spare-placed").then(CommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
					set(2, BoolArgumentType.getBool(c, "on"));
					c.getSource().sendFeedback(() -> Text.literal(describe()), false);
					return 1;
				})))));
	}

	private static synchronized void set(int which, boolean value) {
		if (which == 0) ruby = value;
		else if (which == 1) grimm = value;
		else sparePlaced = value;
		dirty = true;
	}

	static synchronized String describe() {
		return "Grimm Eclipse: Ruby digging " + (ruby ? "on" : "off") + ", Grimm digging " + (grimm ? "on" : "off")
			+ ", blocks players placed are " + (sparePlaced ? "spared" : "not spared")
			+ ". Change with /grimmmc digging <ruby|grimm|all> <true|false> and /grimmmc spare-placed <true|false>.";
	}

	static synchronized boolean rubyDigs() { return ruby; }

	static synchronized boolean grimmDigs() { return grimm; }

	/** True if a block the player placed must be left alone (the option is on and this block was placed by a player). */
	static synchronized boolean spared(String dimension, BlockPos pos) {
		if (!sparePlaced) return false;
		Set<Long> set = PLACED.get(dimension);
		return set != null && set.contains(pos.asLong());
	}

	static String dimension(World world) {
		return world.getRegistryKey().getValue().toString();
	}

	/** A player placed a block here. */
	static synchronized void markPlaced(World world, BlockPos pos) {
		PLACED.computeIfAbsent(dimension(world), k -> new HashSet<>()).add(pos.asLong());
		dirty = true;
	}

	/** A block here is gone: forget that it was placed. */
	static synchronized void forget(World world, BlockPos pos) {
		Set<Long> set = PLACED.get(dimension(world));
		if (set != null && set.remove(pos.asLong())) dirty = true;
	}

	// ---- saved with the world

	private static Path file(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("grimmmc_protection.dat");
	}

	private static synchronized void load(MinecraftServer server) {
		ruby = GrimmMcClient.CONFIG.breakBlocks;
		grimm = GrimmMcClient.CONFIG.grimmBreakBlocks;
		sparePlaced = GrimmMcClient.CONFIG.sparePlaced;
		PLACED.clear();
		dirty = false;
		Path f = file(server);
		if (!Files.isRegularFile(f)) return;
		try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
			if (in.readInt() != 1) return;
			ruby = in.readBoolean();
			grimm = in.readBoolean();
			sparePlaced = in.readBoolean();
			int dims = in.readInt();
			for (int d = 0; d < dims; d++) {
				String name = in.readUTF();
				int n = in.readInt();
				Set<Long> set = new HashSet<>();
				for (int i = 0; i < n; i++) set.add(in.readLong());
				PLACED.put(name, set);
			}
		} catch (IOException e) {
			GrimmMcClient.LOG.warn("Could not read {}: {}", f, e.toString());
		}
	}

	private static synchronized void save(MinecraftServer server) {
		if (!dirty) return;
		Path f = file(server);
		try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(f))) {
			out.writeInt(1);
			out.writeBoolean(ruby);
			out.writeBoolean(grimm);
			out.writeBoolean(sparePlaced);
			out.writeInt(PLACED.size());
			for (Map.Entry<String, Set<Long>> e : PLACED.entrySet()) {
				out.writeUTF(e.getKey());
				out.writeInt(e.getValue().size());
				for (long l : e.getValue()) out.writeLong(l);
			}
			dirty = false;
		} catch (IOException e) {
			GrimmMcClient.LOG.warn("Could not write {}: {}", f, e.toString());
		}
	}
}
