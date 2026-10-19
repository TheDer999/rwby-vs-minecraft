package gg.der999.grimmmc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.world.gen.WorldPresets;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Melty's unattended test run (players never see it).
 * <p>While %LOCALAPPDATA%\Melty\test\&lt;slug&gt;.json exists, Melty is testing this mashup. The file holds
 * {testId, run, runs, seed, checks}. The mod then goes to its main feature without any input, and appends one line per check to the
 * file named by "checks": "pass &lt;name&gt;" or "fail &lt;name&gt;: why". It never quits the game: Melty closes it when the run ends.
 * <p>Scenario: wait for the data conversion, create a flat creative world with Melty's seed, load Ruby, possess her, walk, stop, attack.
 * <p>For local runs: GRIMMMC_TEST_DIR moves the test folder, GRIMMMC_TEST_QUIT=1 quits after the last check.
 */
final class MeltyTest {
	/** The mashup's Melty slug (set after create_mod). While null, any test file that looks right is accepted. */
	static final String SLUG = null;

	static final String CHECK_DATA = "data-ready";
	static final String CHECK_RIG = "rig-loaded";
	static final String CHECK_RENDER = "ruby-rendered";
	static final String CHECK_MOVE = "possess-moves";
	static final String CHECK_ATTACK = "attack-played";
	static final List<String> ALL_CHECKS = List.of(CHECK_DATA, CHECK_RIG, CHECK_RENDER, CHECK_MOVE, CHECK_ATTACK);

	private enum Phase { WAIT_DATA, OPEN_CREATE, CONFIGURE, PRESS_CREATE, WAIT_WORLD, SETTLE, POSSESS, WALK, STOP, ATTACK, DONE }

	static MeltyTest active;

	private final Path checksFile;
	private final String testId;
	private final String seed;
	private final int run, runs;
	private final boolean quitWhenDone = "1".equals(System.getenv("GRIMMMC_TEST_QUIT"));
	private final Set<String> reported = new HashSet<>();

	private Phase phase = Phase.WAIT_DATA;
	private int phaseTicks;
	private int totalTicks;
	private boolean sawMoving, sawIdleAfter, sawAttack, sawAttackEnd;
	private int attackClip = -1;

	private MeltyTest(Path checksFile, String testId, String seed, int run, int runs) {
		this.checksFile = checksFile;
		this.testId = testId;
		this.seed = seed;
		this.run = run;
		this.runs = runs;
	}

	/** Called once when the client has started. Sets {@link #active} when Melty is testing. */
	static void detect() {
		try {
			String env = System.getenv("GRIMMMC_TEST_DIR");
			String local = System.getenv("LOCALAPPDATA");
			Path dir = env != null && !env.isBlank() ? Path.of(env)
				: Path.of(local == null || local.isBlank() ? System.getProperty("user.home") + "/AppData/Local" : local).resolve("Melty").resolve("test");
			if (!Files.isDirectory(dir)) return;
			Path newest = null;
			try (Stream<Path> files = Files.list(dir)) {
				for (Path f : (Iterable<Path>) files::iterator) {
					String name = f.getFileName().toString();
					if (!name.endsWith(".json")) continue;
					if (SLUG != null && !name.equals(SLUG + ".json")) continue;
					if (newest == null || Files.getLastModifiedTime(f).compareTo(Files.getLastModifiedTime(newest)) > 0) newest = f;
				}
			}
			if (newest == null) return;
			JsonObject o;
			try (Reader r = Files.newBufferedReader(newest)) {
				o = JsonParser.parseReader(r).getAsJsonObject();
			}
			if (!o.has("checks")) return;
			Path checks = Path.of(o.get("checks").getAsString());
			active = new MeltyTest(checks,
				o.has("testId") ? o.get("testId").getAsString() : "?",
				o.has("seed") ? o.get("seed").getAsString() : "0",
				o.has("run") ? o.get("run").getAsInt() : 1,
				o.has("runs") ? o.get("runs").getAsInt() : 1);
			if (checks.getParent() != null) Files.createDirectories(checks.getParent());
			GrimmMcClient.LOG.info("Melty test mode: test {} run {}/{} seed {} checks file {}", active.testId, active.run, active.runs, active.seed, checks);
		} catch (Exception e) {
			GrimmMcClient.LOG.error("Could not read the Melty test file", e);
		}
	}

	// ---- reporting

	private void report(String name, boolean pass, String why) {
		if (!reported.add(name)) return;
		String line = pass ? "pass " + name : "fail " + name + ": " + why;
		GrimmMcClient.LOG.info("Melty test check: {}", line);
		try {
			Files.writeString(checksFile, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			GrimmMcClient.LOG.error("Could not write {}", checksFile, e);
		}
	}

	private void failRemaining(String why) {
		for (String c : ALL_CHECKS) report(c, false, why);
	}

	private void go(Phase p) {
		GrimmMcClient.LOG.info("Melty test: {} -> {}", phase, p);
		phase = p;
		phaseTicks = 0;
	}

	// ---- the scenario, one step per client tick

	void tick(MinecraftClient client) {
		totalTicks++;
		phaseTicks++;

		// A fresh Minecraft shows an accessibility screen before the title screen.
		if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
			client.options.onboardAccessibility = false;
			client.setScreen(new TitleScreen());
			return;
		}
		if (phase != Phase.DONE && totalTicks > 20 * 60 * 12) {
			failRemaining("timed out in " + phase);
			go(Phase.DONE);
		}

		switch (phase) {
			case WAIT_DATA -> {
				if (!(client.currentScreen instanceof TitleScreen) || client.getOverlay() != null) return;
				ConverterRunner.Status st = GrimmMcClient.CONVERTER.status();
				if (st.state() == ConverterRunner.State.READY) {
					checkData();
					go(Phase.OPEN_CREATE);
				} else if (st.state() == ConverterRunner.State.FAILED) {
					report(CHECK_DATA, false, st.text());
					failRemaining("no converted data");
					go(Phase.DONE);
				}
			}
			case OPEN_CREATE -> {
				if (phaseTicks < 10) return;
				CreateWorldScreen.show(client, client.currentScreen);
				go(Phase.CONFIGURE);
			}
			case CONFIGURE -> {
				if (!(client.currentScreen instanceof CreateWorldScreen screen)) {
					if (phaseTicks > 100) { failRemaining("could not open the world creation screen"); go(Phase.DONE); }
					return;
				}
				WorldCreator wc = screen.getWorldCreator();
				wc.setWorldName("Grimm test " + testId);
				wc.setGameMode(WorldCreator.Mode.CREATIVE);
				wc.setCheatsEnabled(true);
				wc.setSeed(seed);
				wc.setGenerateStructures(false);
				for (WorldCreator.WorldType t : wc.getNormalWorldTypes()) {
					if (t.preset().matchesKey(WorldPresets.FLAT)) { wc.setWorldType(t); break; }
				}
				go(Phase.PRESS_CREATE);
			}
			case PRESS_CREATE -> {
				if (phaseTicks < 10) return; // let the screen apply the settings
				if (!(client.currentScreen instanceof CreateWorldScreen screen)) return;
				String create = Text.translatable("selectWorld.create").getString();
				for (Element e : new ArrayList<>(screen.children())) {
					if (e instanceof ButtonWidget b && b.getMessage().getString().equals(create)) {
						b.onPress();
						go(Phase.WAIT_WORLD);
						return;
					}
				}
				if (phaseTicks > 100) { failRemaining("could not find the Create New World button"); go(Phase.DONE); }
			}
			case WAIT_WORLD -> {
				if (client.world != null && client.player != null) go(Phase.SETTLE);
				else if (phaseTicks > 20 * 120) { failRemaining("the test world did not load"); go(Phase.DONE); }
			}
			case SETTLE -> {
				// Let chunks load and the player land.
				if (phaseTicks < 60 || !client.player.isOnGround()) {
					if (phaseTicks > 20 * 30) { failRemaining("the player never landed on the ground"); go(Phase.DONE); }
					return;
				}
				client.player.setYaw(0f);
				client.player.setPitch(15f);
				GrimmMcClient.ensureLoaded(client);
				if (GrimmMcClient.rig != null && GrimmMcClient.textureReady && GrimmMcClient.rig.clips.length > 0 && GrimmMcClient.rig.bindPoses.length > 0) {
					report(CHECK_RIG, true, "");
					go(Phase.POSSESS);
				} else {
					report(CHECK_RIG, false, "Ruby's rig or texture did not load (see logs/latest.log)");
					failRemaining("no rig");
					go(Phase.DONE);
				}
			}
			case POSSESS -> {
				client.player.setYaw(0f);
				client.player.setPitch(15f);
				if (phaseTicks == 1) {
					GrimmMcClient.togglePossess(client);
					if (!GrimmMcClient.possessed) {
						report(CHECK_RENDER, false, "could not possess Ruby");
						report(CHECK_MOVE, false, "could not possess Ruby");
						report(CHECK_ATTACK, false, "could not possess Ruby");
						go(Phase.DONE);
					}
					return;
				}
				if (phaseTicks >= 40) {
					if (GrimmMcClient.renderError != null) {
						report(CHECK_RENDER, false, "rendering failed: " + GrimmMcClient.renderError);
					} else if (GrimmMcClient.framesDrawn >= 5 && GrimmMcClient.lastTriangles > 1000) {
						report(CHECK_RENDER, true, "");
					} else {
						report(CHECK_RENDER, false, "Ruby was not drawn (" + GrimmMcClient.framesDrawn + " frames, " + GrimmMcClient.lastTriangles + " triangles)");
					}
					go(Phase.WALK);
				}
			}
			case WALK -> {
				client.player.setYaw(0f);
				client.options.forwardKey.setPressed(true);
				String clip = GrimmMcClient.rig.clips[GrimmMcClient.clipIndex].name;
				if (clip.equals("Walk") || clip.equals("Run")) sawMoving = true;
				if (sawMoving && phaseTicks > 10 || phaseTicks > 60) {
					client.options.forwardKey.setPressed(false);
					go(Phase.STOP);
				}
			}
			case STOP -> {
				client.player.setYaw(0f);
				if (GrimmMcClient.rig.clips[GrimmMcClient.clipIndex].name.equals("Idle")) sawIdleAfter = true;
				if (sawIdleAfter || phaseTicks > 80) {
					if (sawMoving && sawIdleAfter) report(CHECK_MOVE, true, "");
					else report(CHECK_MOVE, false, !sawMoving ? "Ruby never switched to Walk while the player moved" : "Ruby never returned to Idle when the player stopped");
					go(Phase.ATTACK);
				}
			}
			case ATTACK -> {
				// Real combat: a mob that stands still, four left clicks, and the mob must lose health.
				if (phaseTicks == 1) {
					client.player.setYaw(0f);
					if (client.player.networkHandler != null) {
						client.player.networkHandler.sendChatCommand("summon minecraft:husk ~ ~ ~2.4 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
					}
					return;
				}
				client.player.setYaw(0f);
				if (phaseTicks == 20) {
					dummy = findDummy(client);
					if (dummy == null) {
						report(CHECK_ATTACK, false, "the test mob did not appear");
						go(Phase.DONE);
						return;
					}
					dummyHealth = dummy.getHealth();
				}
				if (phaseTicks == 22 || phaseTicks == 33 || phaseTicks == 44 || phaseTicks == 55) client.options.attackKey.setPressed(true);
				if (phaseTicks == 23 || phaseTicks == 34 || phaseTicks == 45 || phaseTicks == 56) client.options.attackKey.setPressed(false);
				CombatController cc = GrimmMcClient.combat;
				if (cc != null && cc.clipName() != null && cc.clipName().startsWith("LandCombo")) sawAttack = true;
				if (phaseTicks >= 100) {
					boolean damaged = dummy != null && (!dummy.isAlive() || dummy.getHealth() < dummyHealth - 0.01f);
					boolean finished = cc == null || !cc.active();
					if (!sawAttack) report(CHECK_ATTACK, false, "Ruby never started her light combo when the attack button was pressed");
					else if (!damaged) report(CHECK_ATTACK, false, "her attacks did not damage the test mob");
					else if (!finished) report(CHECK_ATTACK, false, "her attack never finished");
					else report(CHECK_ATTACK, true, "");
					go(Phase.DONE);
				}
			}
			case DONE -> {
				// Never quit the game: Melty closes it. Locally, GRIMMMC_TEST_QUIT=1 quits for the test harness.
				failRemainingOnce();
				if (quitWhenDone && phaseTicks == 40) client.scheduleStop();
			}
		}
	}

	private net.minecraft.entity.LivingEntity dummy;
	private float dummyHealth;

	private static net.minecraft.entity.LivingEntity findDummy(MinecraftClient client) {
		net.minecraft.entity.LivingEntity best = null;
		double bestDist = 8 * 8;
		for (net.minecraft.entity.Entity e : client.world.getEntities()) {
			if (e instanceof net.minecraft.entity.mob.HuskEntity h && h.isAlive()) {
				double d = h.squaredDistanceTo(client.player);
				if (d < bestDist) { bestDist = d; best = h; }
			}
		}
		return best;
	}

	private boolean doneChecked;

	/** Anything still unreported when the scenario ends fails, so a run can never pass by omission. */
	private void failRemainingOnce() {
		if (doneChecked) return;
		doneChecked = true;
		for (String c : ALL_CHECKS) report(c, false, "not reached");
		GrimmMcClient.LOG.info("Melty test: finished, {} checks reported", reported.size());
	}

	/** data-ready: ready.json exists next to every file it lists. */
	private void checkData() {
		try {
			Path dir = GrimmMcClient.CONFIG.dataDir;
			Path ready = dir.resolve("ready.json");
			if (!Files.isRegularFile(ready)) { report(CHECK_DATA, false, "ready.json is missing"); return; }
			JsonObject o;
			try (Reader r = Files.newBufferedReader(ready)) { o = JsonParser.parseReader(r).getAsJsonObject(); }
			for (var out : o.getAsJsonArray("outputs")) {
				String file = out.getAsJsonObject().get("file").getAsString();
				if (!Files.isRegularFile(dir.resolve(file))) { report(CHECK_DATA, false, file + " is missing"); return; }
			}
			report(CHECK_DATA, true, "");
		} catch (Exception e) {
			report(CHECK_DATA, false, "ready.json could not be read: " + e.getMessage());
		}
	}
}
