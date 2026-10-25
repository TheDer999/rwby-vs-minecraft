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
	private static final int[] NEEDED = {150, 350, 600, 950, 1400, 2000, 2800, 3800, 5000};
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

	/** What each prestige (1 to 10) gives for good, in the order earned. */
	static final String[] PRESTIGE_PERKS = {
		"+10% damage",
		"+15% aura",
		"kills heal 25% more",
		"the Special fills 3 hits sooner",
		"+10% movement speed as Ruby",
		"sniper shots do 50% more damage",
		"the Crescendo does 50% more damage from level 5 on",
		"the aura recharges 25% quicker",
		"combo power levels come 25% sooner",
		"+25% damage and the Legacy mark",
	};
	static final int MAX_PRESTIGE = 10;

	private static int points;
	private static int resets;
	private static int prestige;
	private static boolean legacy;
	private static boolean loaded;

	private static Path file() {
		return GrimmMcClient.CONFIG.home.resolve("ruby_level.properties");
	}

	/** The Legacy mark lives in its own file so that wiping everything else leaves it alone. */
	private static Path legacyFile() {
		return GrimmMcClient.CONFIG.home.resolve("ruby_legacy.properties");
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
			prestige = Math.max(0, Math.min(MAX_PRESTIGE, Integer.parseInt(p.getProperty("prestige", "0").trim())));
		} catch (IOException | NumberFormatException e) {
			GrimmMcClient.LOG.warn("Could not read {}: {}", f, e.toString());
		}
		Path lf = legacyFile();
		if (Files.isRegularFile(lf)) legacy = true;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Properties p = new Properties();
			p.setProperty("points", Integer.toString(points));
			p.setProperty("resets", Integer.toString(resets));
			p.setProperty("prestige", Integer.toString(prestige));
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

	/**
	 * Points a Grimm gives toward Ruby's level: the experience it drops without the grimm_xp setting, except that a mutant is worth
	 * 1.5 times its kind here (it drops 4 times the experience): two large mutants once gave nearly six levels in a night.
	 */
	static int pointsFor(GrimmEntity g) {
		GrimmKind k = g.kind();
		return Math.max(1, Math.round(3f * (float) Math.pow(3, g.size()) * k.xpFactor() * (k.mutant() ? 1.5f : 1f)));
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
		String text = "Ruby reached level " + level + (unlock.isEmpty() ? "" : ": " + unlock) + (level == MAX ? (prestige() < MAX_PRESTIGE ? ". Prestige with /grimmmc prestige for a permanent perk, or keep going." : ". That is the top.") : ".");
		player.sendMessage(Text.literal(text), false);
		var world = player.getServerWorld();
		world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.9f, 1.0f);
		world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getBodyY(0.6), player.getZ(), 24, 0.5, 0.7, 0.5, 0.05);
	}

	/** Back to level 1, keeping prestige; returns the old level. */
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

	// ---- prestige

	static synchronized int prestige() {
		load();
		return prestige;
	}

	static synchronized boolean legacy() {
		load();
		return legacy;
	}

	/** At level 10 with prestige to spare: back to level 1 with one more permanent perk. Returns the new prestige, or -1 if not allowed. */
	static synchronized int prestigeUp() {
		load();
		if (levelFor(points) < MAX || prestige >= MAX_PRESTIGE) return -1;
		prestige++;
		points = 0;
		if (prestige >= MAX_PRESTIGE && !legacy) {
			legacy = true;
			try {
				Files.createDirectories(legacyFile().getParent());
				Files.writeString(legacyFile(), "legacy=true\n");
			} catch (IOException e) {
				GrimmMcClient.LOG.warn("Could not write {}: {}", legacyFile(), e.toString());
			}
		}
		save();
		return prestige;
	}

	/** Sets the prestige directly (for ops and tests); the Legacy mark is only ever earned by prestiging. */
	static synchronized void setPrestige(int p) {
		load();
		prestige = Math.max(0, Math.min(MAX_PRESTIGE, p));
		save();
	}

	/** Erases the level and every prestige perk; the Legacy mark, if earned, stays. */
	static synchronized void wipe() {
		load();
		points = 0;
		prestige = 0;
		resets = 0;
		save();
	}

	static String describe() {
		int level = level();
		int need = nextNeeded();
		int p = prestige();
		StringBuilder sb = new StringBuilder("Ruby is level " + level + (level >= MAX ? " (the top)" : " with " + points() + " of " + need + " points for level " + (level + 1)));
		sb.append(p > 0 ? ", prestige " + p : ", no prestige yet").append(legacy() ? ", Legacy" : "").append(". Unlocked: ").append(unlocked(level)).append(".");
		if (p > 0) {
			sb.append(" Prestige perks:");
			for (int i = 0; i < p; i++) sb.append(i > 0 ? ";" : "").append(" ").append(i + 1).append(": ").append(PRESTIGE_PERKS[i]);
			sb.append(".");
		}
		return sb.toString();
	}

	private static String unlocked(int level) {
		if (level < 2) return "nothing yet";
		StringBuilder sb = new StringBuilder();
		for (int l = 2; l <= level; l++) sb.append(sb.length() > 0 ? "; " : "").append(l).append(": ").append(UNLOCKS[l - 2]);
		return sb.toString();
	}

	// ---- what the levels and prestige unlock

	static float auraMultiplier() {
		int l = level(), p = prestige();
		float m = l >= 8 ? 1.25f : l >= 2 ? 1.1f : 1f;
		if (p >= 2) m += 0.15f;
		if (legacy()) m += 0.05f;
		return m;
	}

	static float auraRechargeFactor() {
		float f = level() >= 8 ? 0.75f : 1f;
		return prestige() >= 8 ? f * 0.75f : f;
	}

	static boolean piercingShots() { return level() >= 3; }

	static boolean burstShots() { return level() >= 5; }

	static long comboWindowMs() { return level() >= 4 ? 4500 : 3000; }

	static float healMultiplier() {
		float m = level() >= 6 ? 1.5f : 1f;
		return prestige() >= 3 ? m * 1.25f : m;
	}

	static int hitsPerSpecial() {
		int h = level() >= 7 ? 18 : 24;
		return prestige() >= 4 ? h - 3 : h;
	}

	static boolean reapArea() { return level() >= 9; }

	static boolean bigCrescendo() { return level() >= 10; }

	/** Prestige 1 gives +10% and prestige 10 another +25% on everything Ruby hits. */
	static float damageBonus() {
		int p = prestige();
		return 1f + (p >= 1 ? 0.10f : 0f) + (p >= 10 ? 0.25f : 0f);
	}

	/** Prestige 6: sniper shots do 50% more. */
	static float shotDamageScale() { return prestige() >= 6 ? 1.5f : 1f; }

	/** Prestige 7: from level 5 on the Crescendo does 50% more. */
	static float crescendoDamageScale() { return prestige() >= 7 && level() >= 5 ? 1.5f : 1f; }

	/** Prestige 9: the combo reaches its power levels in three quarters of the attacks. */
	static float comboStepScale() { return prestige() >= 9 ? 0.75f : 1f; }

	/** Prestige 5: +10% movement speed while she is Ruby. */
	static boolean fasterRuby() { return prestige() >= 5; }

	// ---- the mutants grow with prestige

	/** Mutants spawn up to 40% more often at prestige 10 (rarity is the number of checks per mutant, so smaller is more often). */
	static int mutantRarity(int base) { return Math.max(1, Math.round(base * (1f - 0.04f * prestige()))); }

	static float mutantHealthScale() { return 1f + 0.10f * prestige(); }

	static float mutantDamageScale() { return 1f + 0.06f * prestige(); }

	/** Mutants dig 1 deeper from prestige 4 and 1 more from prestige 8. */
	static int mutantDigBonus() {
		int p = prestige();
		return (p >= 4 ? 1 : 0) + (p >= 8 ? 1 : 0);
	}

	/** Chance in percent that a mutant is a medium one rather than a large one (65 at prestige 0, 35 at prestige 10). */
	static int mutantMediumChance() { return 65 - 3 * prestige(); }
}