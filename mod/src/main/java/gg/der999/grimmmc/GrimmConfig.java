package gg.der999.grimmmc;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where the converted Grimm Eclipse data lives and how to (re)create it.
 * <p>Melty writes %LOCALAPPDATA%\GrimmMC\config.properties before every Play with:
 * ge_dir (the player's Grimm Eclipse folder), converter (GrimmConvert.exe) and data_dir. Values use forward slashes.
 * <p>For development the folder can be moved with the GRIMMMC_HOME environment variable.
 */
final class GrimmConfig {
	final Path home;
	final Path dataDir;
	/** The player's Grimm Eclipse folder, or null when not configured. */
	final String geDir;
	/** Path of GrimmConvert.exe, or null when not configured. */
	final Path converter;
	/**
	 * Minecraft damage per point of Grimm Eclipse damage. A Beowolf has 2,000 health and a zombie 20, so 0.01 would keep
	 * Grimm Eclipse's exact ratios but takes dozens of combos per zombie; the default 0.15 makes a light combo cost a
	 * zombie about a third of its health. Lower it for a harder game (config key damage_scale).
	 */
	final float damageScale;
	/** Scales Grimm Eclipse's knockback and launch distances to Minecraft (config key knockback_scale). */
	final float knockbackScale;
	/**
	 * Whether Ruby's attacks hurt every kind of mob (default) or only hostile ones (config key hit_all_mobs=false).
	 * Players, tamed pets and armor stands are never hit.
	 */
	final boolean hitAllMobs;
	/** Full aura in Grimm Eclipse points (config key aura_max; Grimm Eclipse has 4,000). Damage costs the same per hit, so more aura soaks more. */
	final float auraMax;
	/** Play Ruby's own sounds and voice lines from the converted data (config key ge_sounds, default true). */
	final boolean geSounds;
	/** Volume of those sounds before Minecraft's own volume sliders (config key sound_volume, default 1). */
	final float soundVolume;
	/** How high Ruby jumps in blocks (config key jump_height; vanilla is 1.25, Grimm Eclipse's movement is far more airy). */
	final float jumpHeight;
	/** Seconds the aura takes to refill once it starts recharging (config key aura_recharge_seconds; Grimm Eclipse uses 2). */
	final float auraRechargeSeconds;
	/** Whether the third light attack and the charged heavy break dirt blocks (config key break_blocks, default true). */
	final boolean breakBlocks;
	/** Whether the Grimm break blocks (config key grimm_break_blocks, default true). */
	final boolean grimmBreakBlocks;
	/** Whether Grimm spawn by themselves in the overworld (config key grimm_spawn, default true). /summon always works. */
	final boolean grimmSpawn;
	/** Whether blocks players placed are spared by Ruby and the Grimm (config key spare_placed, default false). */
	final boolean sparePlaced;
	/** Enables the developer keys (animation browser, placed Ruby) (config key debug=true). */
	final boolean debug;

	private GrimmConfig(Path home, Path dataDir, String geDir, Path converter, float damageScale, float knockbackScale, boolean hitAllMobs, float auraMax, float auraRechargeSeconds, boolean geSounds, float soundVolume, float jumpHeight, boolean breakBlocks, boolean grimmBreakBlocks, boolean grimmSpawn, boolean sparePlaced, boolean debug) {
		this.home = home;
		this.dataDir = dataDir;
		this.geDir = geDir;
		this.converter = converter;
		this.damageScale = damageScale;
		this.knockbackScale = knockbackScale;
		this.hitAllMobs = hitAllMobs;
		this.auraMax = auraMax;
		this.geSounds = geSounds;
		this.soundVolume = soundVolume;
		this.jumpHeight = jumpHeight;
		this.auraRechargeSeconds = auraRechargeSeconds;
		this.breakBlocks = breakBlocks;
		this.grimmBreakBlocks = grimmBreakBlocks;
		this.grimmSpawn = grimmSpawn;
		this.sparePlaced = sparePlaced;
		this.debug = debug;
	}

	private static float number(Properties p, String key, float fallback) {
		try {
			String v = p.getProperty(key);
			return v == null || v.isBlank() ? fallback : Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static GrimmConfig load() {
		String env = System.getenv("GRIMMMC_HOME");
		Path home;
		if (env != null && !env.isBlank()) {
			home = Path.of(env);
		} else {
			String local = System.getenv("LOCALAPPDATA");
			if (local == null || local.isBlank()) local = System.getProperty("user.home") + "/AppData/Local";
			home = Path.of(local).resolve("GrimmMC");
		}
		Properties p = new Properties();
		Path file = home.resolve("config.properties");
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				p.load(r);
			} catch (IOException e) {
				GrimmMcClient.LOG.error("Could not read {}", file, e);
			}
		}
		String data = p.getProperty("data_dir");
		String converter = p.getProperty("converter");
		String ge = p.getProperty("ge_dir");
		return new GrimmConfig(
			home,
			data == null || data.isBlank() ? home.resolve("data") : Path.of(data.trim()),
			ge == null || ge.isBlank() ? null : ge.trim(),
			converter == null || converter.isBlank() ? null : Path.of(converter.trim()),
			number(p, "damage_scale", 0.15f),
			number(p, "knockback_scale", 0.35f),
			!"false".equalsIgnoreCase(p.getProperty("hit_all_mobs", "true").trim()),
			Math.max(100f, number(p, "aura_max", 8000f)),
			number(p, "aura_recharge_seconds", 8f),
			!"false".equalsIgnoreCase(p.getProperty("ge_sounds", "true").trim()),
			Math.max(0f, number(p, "sound_volume", 1f)),
			Math.max(1f, Math.min(8f, number(p, "jump_height", 3f))),
			!"false".equalsIgnoreCase(p.getProperty("break_blocks", "true").trim()),
			!"false".equalsIgnoreCase(p.getProperty("grimm_break_blocks", "true").trim()),
			!"false".equalsIgnoreCase(p.getProperty("grimm_spawn", "true").trim()),
			"true".equalsIgnoreCase(p.getProperty("spare_placed", "false").trim()),
			"true".equalsIgnoreCase(p.getProperty("debug", "false").trim()));
	}
}
