package gg.der999.grimmmc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Ruby's own sound effects and voice lines, converted on the player's PC from their Grimm Eclipse install (sound_*.wav in the
 * data folder, written by GrimmConvert). They play through Java's sound API, outside Minecraft's sound engine, scaled by
 * Minecraft's master and player volume. Without them (older data, or a failed conversion) Minecraft's own sounds stand in.
 */
final class Sounds {
	private Sounds() {}

	private record Sample(AudioFormat format, byte[] pcm) {}

	private static final Map<String, Sample> BANK = new HashMap<>();
	private static final Random RANDOM = new Random();
	private static final ExecutorService PLAYER = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "GrimmMC-sound");
		t.setDaemon(true);
		return t;
	});
	private static final AtomicInteger PLAYING = new AtomicInteger();
	private static volatile boolean loaded;
	/** How many sounds have started playing (read by the self tests). */
	static final AtomicInteger STARTED = new AtomicInteger();
	static final AtomicInteger FAILED = new AtomicInteger();
	private static long lastVoiceNanos;

	/** True when Grimm Eclipse's sounds are loaded and enabled: Minecraft's stand-in sounds are then silent. */
	static boolean active() {
		return loaded && GrimmMcClient.CONFIG.geSounds;
	}

	static synchronized void load(Path dir) {
		if (loaded) return;
		BANK.clear();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : (Iterable<Path>) files::iterator) {
				String n = f.getFileName().toString();
				if (!n.startsWith("sound_") || !n.endsWith(".wav")) continue;
				try (AudioInputStream in = AudioSystem.getAudioInputStream(f.toFile())) {
					AudioFormat fmt = in.getFormat();
					if (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED && fmt.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) continue;
					BANK.put(n.substring(6, n.length() - 4).toLowerCase(Locale.ROOT), new Sample(fmt, in.readAllBytes()));
				} catch (Exception e) {
					GrimmMcClient.LOG.warn("Could not read sound {}: {}", n, e.toString());
				}
			}
		} catch (IOException e) {
			GrimmMcClient.LOG.warn("Could not list sounds in {}: {}", dir, e.toString());
		}
		loaded = !BANK.isEmpty();
		GrimmMcClient.LOG.info("Loaded {} Grimm Eclipse sounds", BANK.size());
	}

	// ---- playing

	/** One random sound whose name starts with the prefix (case-insensitive). */
	static void any(String prefix, float volume) {
		if (!active()) return;
		String p = prefix.toLowerCase(Locale.ROOT);
		List<String> names = new ArrayList<>();
		for (String n : BANK.keySet()) if (n.startsWith(p)) names.add(n);
		if (names.isEmpty()) return;
		play(BANK.get(names.get(RANDOM.nextInt(names.size()))), volume);
	}

	/** A voice line, at most one every 0.35 s so grunts do not pile up, and only some of the time. */
	static void voice(String prefix, float chance) {
		if (!active() || RANDOM.nextFloat() > chance) return;
		long now = System.nanoTime();
		if (now - lastVoiceNanos < 350_000_000L) return;
		lastVoiceNanos = now;
		any(prefix, 0.9f);
	}

	private static void play(Sample s, float volume) {
		if (PLAYING.get() >= 14) return;
		MinecraftClient c = MinecraftClient.getInstance();
		float master = c.options.getSoundVolume(SoundCategory.MASTER) * c.options.getSoundVolume(SoundCategory.PLAYERS);
		float gain = volume * master * GrimmMcClient.CONFIG.soundVolume;
		if (gain <= 0.001f) return;
		PLAYING.incrementAndGet();
		PLAYER.execute(() -> {
			try {
				Clip clip = AudioSystem.getClip();
				clip.addLineListener(ev -> {
					if (ev.getType() == LineEvent.Type.STOP) {
						clip.close();
						PLAYING.decrementAndGet();
					}
				});
				clip.open(s.format(), s.pcm(), 0, s.pcm().length);
				if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
					FloatControl ctl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
					float db = (float) (20.0 * Math.log10(Math.max(gain, 0.0001f)));
					ctl.setValue(Math.max(ctl.getMinimum(), Math.min(ctl.getMaximum(), db)));
				}
				clip.start();
				STARTED.incrementAndGet();
			} catch (Exception e) {
				FAILED.incrementAndGet();
				PLAYING.decrementAndGet();
			}
		});
	}

	// ---- what each move sounds like (names are Grimm Eclipse's own)

	static void moveStart(CombatData.Ability a, boolean grounded) {
		if (!active()) return;
		String n = a.coroutine;
		String name = a.name == null ? "" : a.name.toLowerCase(Locale.ROOT);
		if (n.startsWith("LightComboAir")) { any("ruby_lt_air_", 0.9f); voice("ruby_grunt_", 0.3f); }
		else if (n.startsWith("LightCombo_")) { any(RANDOM.nextInt(3) == 0 ? "ruby_lt_02_" : "ruby_lt_00_", 0.9f); voice("ruby_grunt_", 0.3f); }
		else if (n.equals("LightDodgeAttack")) { any("ruby_dodge_att_", 0.9f); voice("ruby_grunt_", 0.4f); }
		else if (n.equals("DodgeLand") || n.equals("DodgeAir")) { any("ruby_dodge_0", 0.9f); voice("ruby_gasp_", 0.25f); }
		else if (n.equals("Heavy")) { any("ruby_hvy_charge_", 0.9f); }
		else if (n.equals("HeavyCombo_00") || n.equals("HeavyComboFinal")) { any("ruby_hvy_att_", 0.95f); voice("ruby_heavy_", 0.6f); }
		else if (n.equals("HeavyCombo_01")) { any("ruby_hvy_01_", 0.95f); voice("ruby_heavy_", 0.6f); }
		else if (n.equals("HeavyCombo_02")) { any("ruby_hvy_02_", 0.95f); voice("ruby_heavy_", 0.6f); }
		else if (n.equals("HeavyComboAir")) { any("ruby_hvy_air_", 0.95f); voice("ruby_heavy_", 0.5f); }
		else if (n.equals("Crescendo")) {
			String key = name.startsWith("ruby_ultimate_") ? name : "ruby_ultimate_a";
			any(key + "_", 1.0f);
			any("weap_crescentrose_ultimate_", 0.8f);
			voice("ruby_heavy_", 0.8f);
		}
		else if (n.equals("Counter")) { any("ruby_counter_", 1.0f); }
		else if (n.equals("FailedCounter")) { voice("ruby_gasp_", 0.7f); }
		else if (n.equals("Reap")) { any("ruby_team_", 1.0f); voice("ruby_heavy_", 0.6f); }
	}

	static void heavyRelease(boolean charged) {
		if (!active()) return;
		any(charged ? "ruby_hvy_00_0" : "ruby_hvy_00_uncharged_", 0.95f);
		any("weap_crescentrose_double_swing_", 0.6f);
		voice("ruby_heavy_", 0.7f);
	}

	static void shot(boolean last) {
		if (!active()) return;
		any("ruby_ranged_gun_", 0.8f);
		if (last) any("ruby_ranged_a_", 0.9f);
	}

	static void warp() {
		any("ruby_warp_to_target_", 0.9f);
	}

	/** Ruby hurt (called when her aura or health takes damage). */
	static void hurt() {
		voice("ruby_hit_", 0.6f);
	}
}