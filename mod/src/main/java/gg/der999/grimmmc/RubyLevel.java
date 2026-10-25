package gg.der999.grimmmc;

import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Ruby's level, 1 to 10, as in Grimm Eclipse: Grimm kills earn points and each level unlocks something. It is saved for the player on this
 * PC (ruby_level.properties next to the config), not per world, so it carries over between worlds. At level 10 the player can keep it
 * or reset it with /grimmmc level reset. Read from the client and server threads, hence synchronized.
 */
final class RubyLevel {
	private RubyLevel() {}

	static final int MAX = 10;
	/** Total points needed to reach level 2, 3 ... 10. */
	private static final int[] NEEDED = {30, 70, 120, 190, 280, 400, 560, 760, 1000};
	/** What each level unlocks (index = level - 2), shown when it is reached. */
	static final String[] UNLOCKS = {
		"aura +10%",
		"piercing sniper shots",
		"a longer combo window",
		"sniper shots burst on impact",
		"kills heal 50% more",
		"the Special fills in 18 hits",
		"aura +25% and quicker recharge",
		"Reap hits every enemy around its target",
		"a far bigger Crescendo",
	};

	private static int points;
	private static int resets;
	private static boolean loaded;

	private static Path file() {
		return GrimmMcClient.CONFIG.home.resolve("ruby_level.properties");
	}

	private static void load() {
		if (loaded) return;
		loaded = true;
		Path f = file();
		if (!Files.isRegularFile(f)) return;
		try (InputStream in = Files.newInputStream(f)) {
			Properties p = new Properties();
			p.load(in);
			points = Math.max(0, Integer.parseInt(p.getProperty("points", "0").trim()));
			resets = Math.max(0, Integer.parseInt(p.getProperty("resets", "0").trim()));
		} catch (IOException | NumberFormatException e) {
			GrimmMcClient.LOG.warn("Could not read {}: {}", f, e.toString());
		}
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Properties p = new Properties();
			p.setProperty("points", Integer.toString(points));
			p.setProperty("resets", Integer.toString(resets));
			try (OutputStream out = Files.newOutputStream(file())) {
				p.store(out, "Ruby's level: Grimm Eclipse in Minecraft");
			}
		} catch (IOException e) {
			GrimmMcClient.LOG.warn("Could not write {}: {}", file(), e.toString());
		}
	}

	private static int levelFor(int pts) {
		int level = 1;
		for (int need : NEEDED) if (pts >= need) level++;
		return level;
	}

	static synchronized int level() {
		load();
		return levelFor(points);
	}

	static synchronized int points() {
		load();
		return points;
	}

	/** Times the player started over at level 10. */
	static synchronized int resets() {
		load();
		return resets;
	}

	/** Points needed for the next level, or -1 at the top. */
	static synchronized int nextNeeded() {
		int level = level();
		return level >= MAX ? -1 : NEEDED[level - 1];
	}

	/** Progress inside the current level, 0 to 1 (1 at the top). */
	static synchronized float progress() {
		int level = level();
		if (level >= MAX) return 1f;
		int from = level == 1 ? 0 : NEEDED[level - 2], to = NEEDED[level - 1];
		return Math.max(0f, Math.min(1f, (points - from) / (float) (to - from)));
	}

	/** Points a Grimm gives toward Ruby's level (the experience it drops, without the grimm_xp setting). */
	static int pointsFor(GrimmEntity g) {
		return g.kind().baseExperience(g.size());
	}

	/** A kill earned points (server thread). */
	static void award(ServerPlayerEntity player, int pts) {
		if (pts <= 0) return;
		int before, after;
		synchronized (RubyLevel.class) {
			load();
			before = levelFor(points);
			points += pts;
			after = levelFor(points);
			save();
		}
		if (after > before) announce(player, after);
	}

	private static void announce(ServerPlayerEntity player, int level) {
		String unlock = level >= 2 ? UNLOCKS[level - 2] : "";
		String text = "Ruby reached level " + level + (unlock.isEmpty() ? "" : ": " + unlock) + (level == MAX ? ". Keep it, or start over with /grimmmc level reset." : ".");
		player.sendMessage(Text.literal(text), false);
		var world = player.getServerWorld();
		world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.9f, 1.0f);
		world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getBodyY(0.6), player.getZ(), 24, 0.5, 0.7, 0.5, 0.05);
	}

	/** Back to level 1 (the player chose to start over); returns the old level. */
	static synchronized int reset() {
		load();
		int old = levelFor(points);
		points = 0;
		resets++;
		save();
		return old;
	}

	/** Sets the level directly (for ops and tests). */
	static synchronized void setLevel(int level) {
		load();
		int l = Math.max(1, Math.min(MAX, level));
		points = l == 1 ? 0 : NEEDED[l - 2];
		save();
	}

	static String describe() {
		int level = level();
		int need = nextNeeded();
		return "Ruby is level " + level + (level >= MAX ? " (the top" + (resets() > 0 ? ", started over " + resets() + " time" + (resets() == 1 ? "" : "s") : "") + ")" : " with " + points() + " of " + need + " points for level " + (level + 1))
			+ ". Unlocked: " + unlocked(level) + ".";
	}

	private static String unlocked(int level) {
		if (level < 2) return "nothing yet";
		StringBuilder sb = new StringBuilder();
		for (int l = 2; l <= level; l++) sb.append(sb.length() > 0 ? "; " : "").append(l).append(": ").append(UNLOCKS[l - 2]);
		return sb.toString();
	}

	// ---- what the levels unlock

	static float auraMultiplier() {
		int l = level();
		return l >= 8 ? 1.25f : l >= 2 ? 1.1f : 1f;
	}

	static float auraRechargeFactor() {
		return level() >= 8 ? 0.75f : 1f;
	}

	static boolean piercingShots() { return level() >= 3; }

	static boolean burstShots() { return level() >= 5; }

	static long comboWindowMs() { return level() >= 4 ? 4500 : 3000; }

	static float healMultiplier() { return level() >= 6 ? 1.5f : 1f; }

	static int hitsPerSpecial() { return level() >= 7 ? 18 : 24; }

	static boolean reapArea() { return level() >= 9; }

	static boolean bigCrescendo() { return level() >= 10; }
}
