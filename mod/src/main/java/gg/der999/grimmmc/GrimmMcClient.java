package gg.der999.grimmmc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Grimm Eclipse in Minecraft. Ruby from the player's own Grimm Eclipse data.
 * <p>On first start a converter (GrimmConvert.exe) reads the player's Grimm Eclipse and writes the data this mod needs;
 * progress shows on the title screen. Nothing from the game is shipped in this jar.
 * <p>Placed mode: R puts Ruby three blocks ahead, facing you.
 * <p>Possess mode: O makes Ruby your avatar (third person). She follows your position and facing and plays
 * Idle / Walk / Run / Jump / JumpFall / JumpLand from how you move.
 * <p>Both modes: G light combo, H heavy combo, V run (toggle, placed mode), B walk (toggle, placed mode),
 * J jump, K dash attack, N / M previous / next clip of all her clips.
 */
public class GrimmMcClient implements ClientModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("grimmmc");
	static final Identifier TEXTURE = Identifier.of("grimmmc", "ruby_texture");
	static final Identifier WEAPON_TEXTURE = Identifier.of("grimmmc", "crescent_texture");
	/** Ruby is about 1.62 units tall; the player is 1.8 blocks. */
	static final float SCALE = 1.11f;
	/** Submeshes 0 (body) and 1 (cape) use the outfit texture; submesh 2 is a glow sprite and is skipped. */
	static final int DRAWN_SUBMESHES = 2;

	static final String[] LIGHT_COMBO = {"LandCombo00", "LandCombo01", "LandCombo02", "LandCombo03"};
	static final String[] HEAVY_COMBO = {"HeavyCombo00", "HeavyCombo01", "HeavyCombo02"};
	/** After a combo hit ends, the next press within this many seconds continues the chain. */
	static final float COMBO_WINDOW = 0.6f;
	/** Horizontal speed (blocks per tick) above which she walks. */
	static final double WALK_SPEED = 0.02;

	static final GrimmConfig CONFIG = GrimmConfig.load();
	static final ConverterRunner CONVERTER = new ConverterRunner();

	// ---- opt-in self tests (environment variables, never set for players)
	static final boolean AUTO = "1".equals(System.getenv("GRIMMMC_AUTO"));
	/** "placed" (default, shoots clips at fixed times), "possess" (drives the player) or "title" (title-screen conversion screens). */
	static final String AUTO_MODE = System.getenv("GRIMMMC_MODE") == null ? "placed" : System.getenv("GRIMMMC_MODE");
	/** Placed-mode shots, e.g. GRIMMMC_CLIPS="Walk:0,Run:0.15". */
	static final String[] AUTO_NAMES;
	static final float[] AUTO_TIMES;
	static {
		String spec = System.getenv("GRIMMMC_CLIPS");
		if (spec == null || spec.isBlank()) spec = "Idle:0.4,Run:0.15,Jump:0.4,HeavyCombo00:0.6,HeavyCombo01:0.5";
		String[] parts = spec.split(",");
		AUTO_NAMES = new String[parts.length];
		AUTO_TIMES = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			String[] kv = parts[i].split(":");
			AUTO_NAMES[i] = kv[0];
			AUTO_TIMES[i] = Float.parseFloat(kv[1]);
		}
	}
	static int autoTicks;
	static int forcedClip = -1;
	static float forcedTime;

	static Prop weapon;
	static boolean weaponTextureReady;
	static Rig rig;
	static float[] skinned;
	static boolean textureReady;
	static boolean placed;
	/** Read by the mixins (including on the integrated server thread), hence volatile. */
	public static volatile boolean possessed;
	static volatile UUID possessedPlayer;
	static CombatData combatData;
	public static volatile CombatController combat;
	static volatile int hitsLanded;
	private static boolean prevAttack, prevShift;
	static long lastCombatTickNanos;
	static Vec3d pos = Vec3d.ZERO;
	static float yawDegrees;
	static Perspective savedPerspective = Perspective.FIRST_PERSON;

	// animation state
	static int clipIndex;
	static boolean looping = true;
	static long startNanos;
	static long attackUntilNanos;
	static int lightStep, heavyStep;
	static long comboUntilNanos;
	static int browseIndex;
	static boolean wasOnGround = true;

	// render statistics (read by the Melty test)
	static volatile int framesDrawn;
	static volatile int lastTriangles;
	static volatile String renderError;

	@Override
	public void onInitializeClient() {
		// Playing as Ruby uses Minecraft's own inputs (left click, right click, Shift, Space, WASD...); only the toggle is new.
		KeyBinding possess = key("possess", GLFW.GLFW_KEY_O);
		// Developer keys, only when debug=true in config.properties.
		KeyBinding place = CONFIG.debug ? key("place", GLFW.GLFW_KEY_LEFT_BRACKET) : null;
		KeyBinding prev = CONFIG.debug ? key("prev", GLFW.GLFW_KEY_COMMA) : null;
		KeyBinding next = CONFIG.debug ? key("next", GLFW.GLFW_KEY_PERIOD) : null;

		// Convert the player's Grimm Eclipse data in the background as soon as the game has started.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			MeltyTest.detect(); // set up before the converter starts: the test waits for it
			CONVERTER.start(CONFIG);
		});

		// Progress on the title screen, and in the world (HUD).
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof TitleScreen) {
				ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) ->
					StatusOverlay.render(ctx, client, CONVERTER.status()));
			}
		});
		HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
			StatusOverlay.render(ctx, MinecraftClient.getInstance(), CONVERTER.status());
			RubyStats.render(ctx, MinecraftClient.getInstance());
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (MeltyTest.active != null) MeltyTest.active.tick(client);
			if (AUTO && AUTO_MODE.equals("title")) autoTitle(client);
			if (AUTO && client.player != null && client.world != null) {
				if (AUTO_MODE.equals("possess")) autoPossess(client);
				else if (AUTO_MODE.equals("combat")) autoCombat(client);
				else if (AUTO_MODE.equals("media")) autoMedia(client);
				else if (AUTO_MODE.equals("fight")) autoFight(client);
				else if (AUTO_MODE.equals("move")) autoMove(client);
				else if (AUTO_MODE.equals("break")) autoBreak(client);
				else if (AUTO_MODE.equals("placed")) autoStep(client);
			}
			if (place != null) while (place.wasPressed()) placeRuby(client);
			while (possess.wasPressed()) togglePossess(client);

			if (possessed && client.player != null) {
				client.player.setInvisible(true); // hide the vanilla model; re-asserted every tick
				handleCombatInput(client);
				RubyStats.tick(CombatController.DT);
				applyJumpBoost(client, true);
				if (combat == null || !combat.showsClip()) updateLocomotion(client);
			}

			boolean active = rig != null && (placed || possessed);
			if (!active || !CONFIG.debug) return;
			// Developer animation browser.
			while (prev.wasPressed()) {
				browseIndex = (browseIndex + rig.clips.length - 1) % rig.clips.length;
				playIndex(client, browseIndex, false, true);
			}
			while (next.wasPressed()) {
				browseIndex = (browseIndex + 1) % rig.clips.length;
				playIndex(client, browseIndex, false, true);
			}
		});

		WorldRenderEvents.AFTER_ENTITIES.register(GrimmMcClient::render);
	}

	static KeyBinding key(String name, int code) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.grimmmc." + name, code, "category.grimmmc"));
	}

	/**
	 * True once the converted data is there. Otherwise tells the player why, and retries after a failure.
	 */
	static boolean dataReady(MinecraftClient client) {
		ConverterRunner.Status st = CONVERTER.status();
		switch (st.state()) {
			case READY:
				return true;
			case FAILED:
			case NOT_STARTED:
				CONVERTER.start(CONFIG);
				tell(client, "Checking your Grimm Eclipse data again...");
				return false;
			default:
				tell(client, "Still converting your Grimm Eclipse data. Please wait a moment.");
				return false;
		}
	}

	static void tell(MinecraftClient client, String text) {
		if (client.player != null) client.player.sendMessage(Text.literal(text), true);
	}

	/** Continue a combo if the previous hit was recent, otherwise restart it. */
	static int chainStep(int step, int length) {
		return System.nanoTime() < comboUntilNanos ? (step + 1) % length : 0;
	}

	static void play(MinecraftClient client, String name, boolean loop, boolean attack) {
		int i = rig.findClip(name);
		if (i < 0) return;
		playIndex(client, i, loop, attack);
		if (attack && name.startsWith("LandCombo") || name.startsWith("HeavyCombo")) {
			comboUntilNanos = System.nanoTime() + (long) ((rig.clips[i].length + COMBO_WINDOW) * 1e9);
		}
	}

	/** Start a clip. An attack/one-shot locks locomotion switching until it finishes. */
	static void playIndex(MinecraftClient client, int i, boolean loop, boolean attack) {
		clipIndex = i;
		looping = loop;
		startNanos = System.nanoTime();
		browseIndex = i;
		attackUntilNanos = attack ? startNanos + (long) (rig.clips[i].length * 1e9) : 0;
		tell(client, rig.clips[i].name + String.format(" (%.2fs)", rig.clips[i].length));
	}

	// ---- possess mode

	static void togglePossess(MinecraftClient client) {
		if (client.player == null || !dataReady(client)) return;
		ensureLoaded(client);
		if (rig == null || !textureReady) return;
		possessed = !possessed;
		if (possessed) {
			placed = false;
			savedPerspective = client.options.getPerspective();
			client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
			wasOnGround = client.player.isOnGround();
			possessedPlayer = client.player.getUuid();
			combat = combatData == null ? null : new CombatController(combatData);
			prevAttack = false;
			prevShift = false;
			RubyStats.reset();
			playIndex(client, rig.findClip("Idle"), true, false);
			tell(client, combat == null ? "Ruby possessed (combat data missing). O to release." : "Ruby possessed. O to release.");
		} else {
			combat = null;
			possessedPlayer = null;
			client.player.setInvisible(false);
			client.options.setPerspective(savedPerspective);
			applyJumpBoost(client, false);
			applySprintBoost(client, false);
			tell(client, "Ruby released.");
		}
	}

	/**
	 * Minecraft's own inputs drive Ruby, as in Grimm Eclipse's PC controls: left click is her light attack, Shift is her
	 * dodge (hold to sprint). Vanilla clicks are cancelled while she is possessed (see MinecraftClientMixin).
	 */
	/** Set by MinecraftClientMixin when F (swap hands) is pressed while Ruby is possessed. */
	public static volatile boolean counterPressed;
	/** Set by MinecraftClientMixin when the pick-item button (middle click) is pressed while Ruby is possessed. */
	public static volatile boolean specialPressed;
	static boolean prevHeavy, prevCtrl;

	static void handleCombatInput(MinecraftClient client) {
		if (combat == null) return;
		boolean inGame = client.currentScreen == null && client.player != null;
		boolean attack = inGame && client.options.attackKey.isPressed();
		boolean shift = inGame && client.options.sneakKey.isPressed();
		if (attack && !prevAttack) combat.press(CombatController.Action.LIGHT);
		boolean inWater = client.player != null && client.player.isTouchingWater();
		if (shift && !prevShift && !inWater) combat.press(CombatController.Action.DODGE);
		applySprintBoost(client, shift && !inWater);
		// Minecraft only starts a sprint from its own sprint key; Shift (Grimm Eclipse's dodge and sprint button) and swimming
		// forward under water start one here.
		var pl = client.player;
		if (pl != null && inGame && client.options.forwardKey.isPressed() && !pl.isSprinting() && !pl.isUsingItem()
				&& pl.getHungerManager().getFoodLevel() > 6 && (combat == null || !combat.busy())) {
			boolean grounded = pl.isOnGround() && !inWater;
			if ((shift && grounded) || (inWater && pl.isSubmergedInWater())) pl.setSprinting(true);
		}
		boolean heavy = inGame && client.options.useKey.isPressed();
		if (heavy && !prevHeavy) combat.press(CombatController.Action.HEAVY);
		combat.setHeavyHeld(heavy);
		prevHeavy = heavy;
		boolean shoot = inGame && client.options.dropKey.isPressed();
		if (shoot) combat.press(CombatController.Action.RANGED);
		boolean ctrl = inGame && client.options.sprintKey.isPressed();
		boolean still = !client.options.forwardKey.isPressed() && !client.options.backKey.isPressed()
			&& !client.options.leftKey.isPressed() && !client.options.rightKey.isPressed();
		if (ctrl && !prevCtrl && still) combat.press(CombatController.Action.TEAM);
		prevCtrl = ctrl;
		if (specialPressed) {
			specialPressed = false;
			if (inGame) combat.press(CombatController.Action.SPECIAL);
		}
		if (counterPressed) {
			counterPressed = false;
			if (inGame) combat.press(CombatController.Action.COUNTER);
		}
		prevAttack = attack;
		prevShift = shift;
		lastCombatTickNanos = System.nanoTime();
		combat.tick(client);
	}

	/** True while Shift is carrying Ruby sideways or backwards faster (Minecraft only sprints forwards). */
	static boolean sprintBoost;

	/**
	 * Grimm Eclipse's dodge button is also its sprint button, in any direction. Minecraft's own sprint only works forwards,
	 * so while Shift is held and Ruby moves sideways or back on the ground, her speed is raised to sprint speed here.
	 */
	static final net.minecraft.util.Identifier JUMP_BOOST_ID = net.minecraft.util.Identifier.of("grimmmc", "jump_boost");

	/**
	 * Ruby jumps jump_height blocks (default 3) instead of Minecraft's 1.25, to match how far her moves carry her. The extra
	 * jump strength is a modifier that exists only while she is possessed. Jump height grows with the square of the take-off
	 * speed, so the speed is scaled by the square root of the wanted height over the vanilla one.
	 */
	static void applyJumpBoost(MinecraftClient client, boolean on) {
		var p = client.player;
		if (p == null) return;
		var attr = p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.JUMP_STRENGTH);
		if (attr == null) return;
		boolean has = attr.hasModifier(JUMP_BOOST_ID);
		if (!on) {
			if (has) attr.removeModifier(JUMP_BOOST_ID);
			return;
		}
		double factor = Math.sqrt((CONFIG.jumpHeight + 0.45) / 1.25) - 1.0; // +0.45: so a full 3-block wall is cleared, not just reached
		if (has) {
			var m = attr.getModifier(JUMP_BOOST_ID);
			if (m != null && Math.abs(m.value() - factor) < 1e-6) return;
			attr.removeModifier(JUMP_BOOST_ID);
		}
		attr.addTemporaryModifier(new net.minecraft.entity.attribute.EntityAttributeModifier(JUMP_BOOST_ID, factor,
			net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
	}

	static final net.minecraft.util.Identifier SPRINT_BOOST_ID = net.minecraft.util.Identifier.of("grimmmc", "sprint_boost");

	static void applySprintBoost(MinecraftClient client, boolean shiftHeld) {
		var p = client.player;
		var o = client.options;
		boolean forward = o.forwardKey.isPressed();
		boolean other = o.backKey.isPressed() || o.leftKey.isPressed() || o.rightKey.isPressed();
		sprintBoost = shiftHeld && !forward && other && p.isOnGround() && (combat == null || !combat.busy());
		var speed = p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.MOVEMENT_SPEED);
		if (speed == null) return;
		boolean has = speed.hasModifier(SPRINT_BOOST_ID);
		if (sprintBoost && !has) {
			// The same +30% Minecraft's own sprint gives.
			speed.addTemporaryModifier(new net.minecraft.entity.attribute.EntityAttributeModifier(SPRINT_BOOST_ID, 0.3,
				net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else if (!sprintBoost && has) {
			speed.removeModifier(SPRINT_BOOST_ID);
		}
	}
	/** Damage left for the player's health after Ruby's aura soaked its share (read on the integrated server's thread). */
	public static float auraAbsorb(UUID id, float amount) {
		if (possessed && id.equals(possessedPlayer)) {
			float before = RubyStats.shield();
			float rest = RubyStats.absorb(amount);
			if (amount > 0f) {
				boolean broke = before > 0f && RubyStats.shield() <= 0f;
				MinecraftClient.getInstance().execute(() -> { Sounds.auraHit(before > 0f, broke); Sounds.hurt(); });
			}
			return rest;
		}
		return amount;
	}

	/** True when the given player is inside a dodge and must take no damage (read on the integrated server's thread). */
	public static boolean dodgeProtects(UUID id) {
		CombatController c = combat;
		return possessed && c != null && c.invulnerable() && id.equals(possessedPlayer);
	}

	/** Choose Idle / Walk / Run / Jump / JumpFall / JumpLand from the player's movement. */
	static void updateLocomotion(MinecraftClient client) {
		PlayerEntity p = client.player;
		boolean onGround = p.isOnGround();
		Vec3d v = p.getVelocity();
		double speed = Math.sqrt(v.x * v.x + v.z * v.z);
		long now = System.nanoTime();
		boolean locked = now < attackUntilNanos;
		String current = rig.clips[clipIndex].name;

		if (!locked) {
			String desired;
			boolean oneShot = false;
			if (p.isTouchingWater()) {
				// Grimm Eclipse has no swimming animations: she treads water with her walk and idle loops.
				desired = speed > WALK_SPEED * 0.5 ? (p.isSwimming() ? "Run" : "Walk") : "Idle";
			} else if (!onGround) {
				boolean jumpPlaying = current.equals("Jump") && !looping && elapsed() < rig.clips[clipIndex].length;
				if (jumpPlaying) {
					desired = "Jump";
				} else if (wasOnGround && v.y > 0) {
					desired = "Jump";
					oneShot = true;
				} else {
					desired = "JumpFall";
				}
			} else if (!wasOnGround) {
				desired = "JumpLand";
				oneShot = true;
			} else if (current.equals("JumpLand") && !looping && elapsed() < rig.clips[clipIndex].length) {
				desired = "JumpLand";
			} else if (speed > WALK_SPEED) {
				desired = p.isSprinting() || sprintBoost ? "Run" : "Walk";
			} else {
				desired = "Idle";
			}
			if (!desired.equals(current)) {
				play(client, desired, !oneShot && !desired.equals("Jump") && !desired.equals("JumpLand"), false);
			}
		}
		wasOnGround = onGround;
	}

	static float elapsed() {
		return (System.nanoTime() - startNanos) / 1_000_000_000f;
	}

	// ---- self tests (opt-in via GRIMMMC_AUTO=1)

	static boolean titleShotProgress, titleShotDone;
	static int titleTicks, titleStateTicks;
	static ConverterRunner.State titleLastState = ConverterRunner.State.NOT_STARTED;

	/** Title-screen self test: screenshot the conversion panel while converting and at its end (ready or failed), then quit. */
	static void autoTitle(MinecraftClient client) {
		// Minecraft's own loading screen covers the title screen at first.
		if (!(client.currentScreen instanceof TitleScreen) || client.getOverlay() != null) return;
		titleTicks++;
		ConverterRunner.Status st = CONVERTER.status();
		if (st.state() != titleLastState) {
			titleLastState = st.state();
			titleStateTicks = 0;
			LOG.info("auto title: state -> {}", st.state());
		}
		titleStateTicks++;
		if (st.state() == ConverterRunner.State.CONVERTING && !titleShotProgress && st.step() >= 3 && titleStateTicks > 10) {
			titleShotProgress = true;
			shoot(client);
		}
		boolean ended = st.state() == ConverterRunner.State.READY || st.state() == ConverterRunner.State.FAILED;
		if (ended && !titleShotDone && titleStateTicks > 10) {
			titleShotDone = true;
			LOG.info("auto title: final state {} '{}'", st.state(), st.text());
			shoot(client);
		}
		if (titleShotDone && titleStateTicks > 30) client.scheduleStop();
		if (titleTicks > 20 * 180) client.scheduleStop();
	}

	/** Placed-mode self test: place Ruby, shoot clips at fixed times, quit. */
	static void autoStep(MinecraftClient client) {
		autoTicks++;
		// Fixed camera so every run frames Ruby the same way.
		if (autoTicks >= 90 && autoTicks < 220) {
			client.player.setYaw(0f);
			client.player.setPitch(5f);
		}
		if (autoTicks == 100) placeRuby(client);
		if (rig == null) return;
		for (int i = 0; i < AUTO_NAMES.length; i++) {
			if (autoTicks == 140 + i * 10) {
				forcedClip = rig.findClip(AUTO_NAMES[i]);
				forcedTime = AUTO_TIMES[i];
			}
			if (autoTicks == 145 + i * 10) shoot(client);
		}
		if (autoTicks == 220) client.scheduleStop();
	}

	/** Possess-mode self test: possess, walk, sprint, jump, light attack; screenshot each. */
	static void autoPossess(MinecraftClient client) {
		autoTicks++;
		if (autoTicks >= 90 && autoTicks < 330) {
			client.player.setYaw(0f);
			client.player.setPitch(15f);
		}
		if (autoTicks == 100) togglePossess(client);
		// Short bursts: the test spawn is a few blocks from a ridge edge.
		if (autoTicks == 130) client.options.forwardKey.setPressed(true);               // walk
		if (autoTicks == 135) shoot(client);
		if (autoTicks == 136) client.options.sprintKey.setPressed(true);                // run
		if (autoTicks == 141) shoot(client);
		if (autoTicks == 142) {
			client.options.forwardKey.setPressed(false);
			client.options.sprintKey.setPressed(false);
		}
		if (autoTicks == 160) shoot(client);                                             // back to idle
		if (autoTicks == 180) client.options.jumpKey.setPressed(true);                  // jump
		if (autoTicks == 182) client.options.jumpKey.setPressed(false);
		if (autoTicks == 186) shoot(client);
		if (autoTicks == 230 && rig != null) play(client, "LandCombo00", false, true);  // attack
		if (autoTicks == 236) shoot(client);
		if (autoTicks == 330) client.scheduleStop();
	}

	/** Combat self test: possess, summon a husk, click and dodge with the real vanilla keys, log what happens. */
	static void autoCombat(MinecraftClient client) {
		autoTicks++;
		if (autoTicks >= 90 && autoTicks < 800) {
			client.player.setYaw(0f);
			client.player.setPitch(10f);
		}
		if (autoTicks == 100) togglePossess(client);
		if (autoTicks == 110 && client.player.networkHandler != null) {
			client.player.networkHandler.sendChatCommand("summon minecraft:husk ~ ~ ~2.4 {Silent:1b}");
		}
		if (autoTicks == 140) logTargets(client, "before");
		int[] clicks = {145, 156, 167, 178};
		for (int i = 0; i < clicks.length; i++) {
			if (autoTicks == clicks[i]) client.options.attackKey.setPressed(true);
			if (autoTicks == clicks[i] + 1) client.options.attackKey.setPressed(false);
			if (autoTicks == clicks[i] + 4) shoot(client);
		}
		if (autoTicks == 200) logTargets(client, "after light combo");
		if (autoTicks == 214) client.options.leftKey.setPressed(true);    // dodge sideways
		if (autoTicks == 215) client.options.sneakKey.setPressed(true);   // dodge
		if (autoTicks == 217) client.options.sneakKey.setPressed(false);
		if (autoTicks == 218) client.options.leftKey.setPressed(false);
		if (autoTicks == 219) shoot(client);
		if (autoTicks == 224) client.options.attackKey.setPressed(true);  // dash attack out of the dodge
		if (autoTicks == 225) client.options.attackKey.setPressed(false);
		if (autoTicks == 229) shoot(client);
		if (autoTicks == 260) logTargets(client, "after dash attack");
		if (autoTicks == 270) client.options.jumpKey.setPressed(true);    // air combo
		if (autoTicks == 272) client.options.jumpKey.setPressed(false);
		if (autoTicks == 280) client.options.attackKey.setPressed(true);
		if (autoTicks == 281) client.options.attackKey.setPressed(false);
		if (autoTicks == 284) shoot(client);
		if (autoTicks == 330) logTargets(client, "after air attack");
		if (autoTicks == 340 && client.player.networkHandler != null) {
			client.player.networkHandler.sendChatCommand("summon minecraft:husk ~ ~ ~3 {Silent:1b}");
		}
		if (autoTicks == 360) logTargets(client, "second husk");
		if (autoTicks == 362) client.options.useKey.setPressed(true);     // heavy attack: hold to charge
		if (autoTicks == 385) client.options.useKey.setPressed(false);
		if (autoTicks == 395) shoot(client);
		if (autoTicks == 420) logTargets(client, "after heavy");
		if (autoTicks == 425) client.options.dropKey.setPressed(true);    // ranged shot
		if (autoTicks == 427) client.options.dropKey.setPressed(false);
		if (autoTicks == 455) logTargets(client, "after ranged");
		if (autoTicks == 460) counterPressed = true;                      // counter (F)
		if (autoTicks == 466) shoot(client);
		if (autoTicks == 490) logTargets(client, "after counter");
		if (autoTicks == 464 && client.player.networkHandler != null) client.player.networkHandler.sendChatCommand("summon minecraft:husk ~ ~ ~2 {Silent:1b}");
		if (autoTicks == 470) { RubyStats.addHits(24); }
		if (autoTicks == 472) specialPressed = true;                      // special (middle click)
		if (autoTicks == 478) shoot(client);
		if (autoTicks == 486) logTargets(client, "after special");
		if (autoTicks == 488 && client.player.networkHandler != null) client.player.networkHandler.sendChatCommand("gamemode survival");
		if (autoTicks == 492 && client.player.networkHandler != null) client.player.networkHandler.sendChatCommand("damage @s 6 minecraft:mob_attack");
		if (autoTicks == 498) logTargets(client, "after 6 damage");
		if (autoTicks == 499) shoot(client);
		if (autoTicks == 560 || autoTicks == 640 || autoTicks == 720 || autoTicks == 740) logTargets(client, "aura recharge t" + autoTicks);
		if (autoTicks == 745) client.scheduleStop();
	}

	/** Listing screenshots: possess Ruby, fight a few still mobs, and take pictures at the interesting moments. */
	static void autoMedia(MinecraftClient client) {
		autoTicks++;
		int n = autoTicks;
		if (n >= 90 && n < 400) {
			client.player.setYaw(0f);
			client.player.setPitch(12f);
		}
		if (n == 100) togglePossess(client);
		var net = client.player.networkHandler;
		if (n == 108 && net != null) net.sendChatCommand("summon minecraft:husk ~ ~ ~3.2 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
		if (n == 109 && net != null) net.sendChatCommand("summon minecraft:zombie ~-2.2 ~ ~3.6 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
		if (n == 110 && net != null) net.sendChatCommand("summon minecraft:skeleton ~2.4 ~ ~3.8 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
		if (n == 125) shoot(client);
		int[] clicks = {130, 141, 152, 163};
		for (int i = 0; i < clicks.length; i++) {
			if (n == clicks[i]) client.options.attackKey.setPressed(true);
			if (n == clicks[i] + 1) client.options.attackKey.setPressed(false);
			if (n == clicks[i] + 3) shoot(client);
		}
		if (n == 200) client.options.useKey.setPressed(true);
		if (n == 209) shoot(client);
		if (n == 216) client.options.useKey.setPressed(false);
		if (n == 224) shoot(client);
		if (n == 232 && net != null) net.sendChatCommand("summon minecraft:husk ~ ~ ~2.6 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
		if (n == 240) RubyStats.addHits(24);
		if (n == 246) specialPressed = true;
		if (n == 251) shoot(client);
		if (n == 255) shoot(client);
		if (n == 285) client.options.dropKey.setPressed(true);
		if (n == 288) client.options.dropKey.setPressed(false);
		if (n == 290) shoot(client);
		if (n == 300) client.scheduleStop();
	}
	static String lastFightClip;

	/** Counter and Reap self test: a husk that really attacks, F pressed until a counter lands, then Ctrl for Reap on a still zombie. */
	static void autoFight(MinecraftClient client) {
		autoTicks++;
		int n = autoTicks;
		if (n >= 90 && n < 400) {
			client.player.setYaw(0f);
			client.player.setPitch(12f);
		}
		var net = client.player.networkHandler;
		if (n == 100) togglePossess(client);
		if (n == 104 && net != null) net.sendChatCommand("gamemode survival");
		if (n == 108 && net != null) net.sendChatCommand("summon minecraft:husk ~ ~ ~4 {Silent:1b,PersistenceRequired:1b}");
		CombatController cc = combat;
		String clip = cc != null && cc.active() ? cc.clipName() : null;
		if (cc != null && !java.util.Objects.equals(clip, lastFightClip)) {
			lastFightClip = clip;
			LOG.info("FIGHT-TEST tick {} clip={} aura={} {}", n, clip, Math.round(RubyStats.shield()), fightMobs(client));
		}
		if (n >= 125 && n < 260 && n % 4 == 0) counterPressed = true;
		if (n == 262) logTargets(client, "after counter spam");
		if (n == 268 && net != null) net.sendChatCommand("kill @e[type=minecraft:husk]");
		if (n == 272 && net != null) net.sendChatCommand("summon minecraft:zombie ~ ~ ~4.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
		if (n == 285) logTargets(client, "before reap");
		if (n == 286) client.options.sprintKey.setPressed(true);
		if (n == 288) client.options.sprintKey.setPressed(false);
		if (n == 292 || n == 298 || n == 306) shoot(client);
		if (n == 325) logTargets(client, "after reap");
		if (n == 330 && net != null) net.sendChatCommand("summon minecraft:pig ~ ~ ~2.6 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
		if (n == 340) logPig(client, "pig before");
		for (int c : new int[] {345, 356, 367, 378}) {
			if (n == c) client.options.attackKey.setPressed(true);
			if (n == c + 1) client.options.attackKey.setPressed(false);
		}
		if (n == 400) logPig(client, "pig after light combo");
		if (n == 405) client.scheduleStop();
	}

	static net.minecraft.util.math.BlockPos breakBase;

	static String blockAt(MinecraftClient client, int dx, int dy, int dz) {
		return client.world.getBlockState(breakBase.add(dx, dy, dz)).getBlock().getTranslationKey().replace("block.minecraft.", "");
	}

	static int solidCount(MinecraftClient client, String name, int x0, int y0, int z0, int x1, int y1, int z1) {
		int c = 0;
		for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) if (blockAt(client, x, y, z).equals(name)) c++;
		return c;
	}

	/** Dirt left in each layer (z offset 2 to 7) of the wall. */
	static String layers(MinecraftClient client) {
		StringBuilder sb = new StringBuilder();
		for (int z = 2; z <= 7; z++) { int c = 0; for (int x = -1; x <= 1; x++) for (int y = 0; y <= 2; y++) if (!blockAt(client, x, y, z).equals("air")) c++; sb.append(c).append(z < 7 ? "/" : ""); }
		return sb.toString();
	}

	/** The item entities lying around, as name x count. */
	static String dropped(MinecraftClient client) {
		java.util.Map<String, Integer> m = new java.util.TreeMap<>();
		for (net.minecraft.entity.Entity e : client.world.getEntities()) if (e instanceof net.minecraft.entity.ItemEntity ie) m.merge(ie.getStack().getItem().getTranslationKey().replace("block.minecraft.", "").replace("item.minecraft.", ""), ie.getStack().getCount(), Integer::sum);
		return m.toString();
	}

	/** Which blocks of the dirt column at z offset 4 to 9 are still there (D) or gone (.). */
	static String column(MinecraftClient client) {
		StringBuilder sb = new StringBuilder();
		for (int z = 4; z <= 9; z++) sb.append(blockAt(client, 0, 1, z).equals("dirt") ? 'D' : '.');
		return sb.toString();
	}

	/** Block breaking self test: a charged heavy at a dirt block under the crosshair, then the third light attack against a dirt wall. */
	static void autoBreak(MinecraftClient client) {
		autoTicks++;
		int n = autoTicks;
		var p = client.player;
		var net = p.networkHandler;
		var o = client.options;
		if (n >= 90 && n < 360) { p.setYaw(0f); p.setPitch(0f); }
		if (n == 100) togglePossess(client);
		if (n == 104 && net != null) net.sendChatCommand("gamemode survival");
		if (n == 106) {
			breakBase = p.getBlockPos();
			var b = breakBase;
			if (net != null) {
				net.sendChatCommand("setblock " + b.getX() + " " + (b.getY() + 1) + " " + (b.getZ() + 6) + " minecraft:dirt");
				net.sendChatCommand("setblock " + b.getX() + " " + (b.getY() + 1) + " " + (b.getZ() + 7) + " minecraft:dirt");
				net.sendChatCommand("setblock " + (b.getX() + 2) + " " + (b.getY() + 1) + " " + (b.getZ() + 6) + " minecraft:stone");
				net.sendChatCommand("setblock " + (b.getX() + 1) + " " + (b.getY() + 1) + " " + (b.getZ() + 6) + " minecraft:dirt");
				net.sendChatCommand("setblock " + b.getX() + " " + (b.getY() + 1) + " " + (b.getZ() + 12) + " minecraft:dirt");
			}
		}
		if (n == 112) LOG.info("BREAK-TEST before heavy: near dirt={} side dirt={} far dirt={} distant dirt={} stone={}", blockAt(client, 0, 1, 6), blockAt(client, 1, 1, 6), blockAt(client, 0, 1, 7), blockAt(client, 0, 1, 12), blockAt(client, 2, 1, 6));
		// hold the heavy button long enough for a full charge, then release
		if (n == 115) o.useKey.setPressed(true);
		if (n == 175) o.useKey.setPressed(false);
		if (n == 215) LOG.info("BREAK-TEST after charged heavy: near={} side={} far={} distant={} stone={}", blockAt(client, 0, 1, 6), blockAt(client, 1, 1, 6), blockAt(client, 0, 1, 7), blockAt(client, 0, 1, 12), blockAt(client, 2, 1, 6));
		// a dirt wall in front of her, plus stone and sand right beside it that must stay
		if (n == 220 && net != null) {
			var b = p.getBlockPos();
			breakBase = b;
			String[] mats = {"dirt", "stone", "obsidian", "sand", "glass", "oak_log"};
				for (int z = 0; z < mats.length; z++) net.sendChatCommand("fill " + (b.getX() - 1) + " " + b.getY() + " " + (b.getZ() + 2 + z) + " " + (b.getX() + 1) + " " + (b.getY() + 2) + " " + (b.getZ() + 2 + z) + " minecraft:" + mats[z]);
			net.sendChatCommand("setblock " + (b.getX() + 3) + " " + b.getY() + " " + (b.getZ() + 2) + " minecraft:stone");
		}
		if (n == 228) LOG.info("BREAK-TEST wall before: dirt={} layers={} stone={} floorUnderfoot={}", solidCount(client, "dirt", -1, 0, 2, 1, 2, 7), layers(client), blockAt(client, 3, 0, 2), blockAt(client, 0, -1, 0));
		for (int c : new int[] {235, 246, 257, 268}) {
			if (n == c) o.attackKey.setPressed(true);
			if (n == c + 1) o.attackKey.setPressed(false);
			if (n == c + 8) LOG.info("BREAK-TEST after light attack {}: dirt left={} layers={} clip={}", (c - 235) / 11 + 1, solidCount(client, "dirt", -1, 0, 2, 1, 2, 7), layers(client), combat == null ? null : combat.clipName());
		}
		if (n == 300) LOG.info("BREAK-TEST items dropped: {}", dropped(client));
		if (n == 300) LOG.info("BREAK-TEST end of lights: layers={} stone={} floorUnderfoot={}", layers(client), blockAt(client, 3, 0, 2), blockAt(client, 0, -1, 0));
		// three aimed shots at a column of dirt: one, two, then three blocks deep
		if (n == 305 && net != null) {
			breakBase = p.getBlockPos();
			var b = breakBase;
			net.sendChatCommand("fill " + (b.getX() - 3) + " " + b.getY() + " " + (b.getZ() + 1) + " " + (b.getX() + 3) + " " + (b.getY() + 3) + " " + (b.getZ() + 9) + " minecraft:air");
		}
		if (n == 312 && net != null) {
			var b = breakBase;
			net.sendChatCommand("fill " + b.getX() + " " + (b.getY() + 1) + " " + (b.getZ() + 4) + " " + b.getX() + " " + (b.getY() + 1) + " " + (b.getZ() + 9) + " minecraft:dirt");
		}
		if (n == 320) LOG.info("BREAK-TEST shot column before: {}", column(client));
		for (int c : new int[] {325, 345, 365}) {
			if (n == c) o.dropKey.setPressed(true);
			if (n == c + 4) o.dropKey.setPressed(false);
			if (n == c + 14) LOG.info("BREAK-TEST after shot {}: {}", (c - 325) / 20 + 1, column(client));
		}
		// what each kind of block costs: placed in a row behind her
		String[] kinds = {"poppy", "torch", "dirt", "oak_planks", "glass", "stone", "iron_block", "chest", "furnace", "obsidian", "ancient_debris", "reinforced_deepslate", "bedrock", "barrier", "command_block", "water", "spawner"};
		if (n >= 380 && n < 380 + kinds.length && net != null) {
			var b = breakBase;
			net.sendChatCommand("setblock " + (b.getX() - 8 + (n - 380)) + " " + (b.getY() + 1) + " " + (b.getZ() - 4) + " minecraft:" + kinds[n - 380]);
		}
		if (n == 420) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < kinds.length; i++) {
				var pos = breakBase.add(-8 + i, 1, -4);
				sb.append(kinds[i]).append('=').append(HitService.cost(client.world, pos, client.world.getBlockState(pos))).append(' ');
			}
			LOG.info("BREAK-TEST costs: {}", sb);
		}
		if (n == 425) client.scheduleStop();
	}

	static void logPig(MinecraftClient client, String label) {
		for (net.minecraft.entity.Entity e : client.world.getEntities()) {
			if (e instanceof net.minecraft.entity.passive.PigEntity pig && pig.distanceTo(client.player) < 8) LOG.info("FIGHT-TEST {}: pig hp={} d={} hits={} aliveNoAI={}", label, String.format("%.1f", pig.getHealth()), String.format("%.1f", pig.distanceTo(client.player)), hitsLanded, pig.isAiDisabled());
		}
	}

	static String fightMobs(MinecraftClient client) {
		StringBuilder sb = new StringBuilder();
		for (net.minecraft.entity.Entity e : client.world.getEntities()) {
			if (e instanceof net.minecraft.entity.mob.HuskEntity || e instanceof net.minecraft.entity.mob.ZombieEntity) {
				var le = (net.minecraft.entity.LivingEntity) e;
				sb.append(String.format("[%s hp=%.1f d=%.1f]", e.getType().getUntranslatedName(), le.getHealth(), Math.hypot(e.getX() - client.player.getX(), e.getZ() - client.player.getZ())));
			}
		}
		return sb.toString();
	}
	static double moveX0, moveZ0, moveY0, jumpMax;

	/** Movement self test: sideways walking with and without Shift, aimed shots, then swimming (sink, dive, surface). */
	static void autoMove(MinecraftClient client) {
		autoTicks++;
		int n = autoTicks;
		var p = client.player;
		var net = p.networkHandler;
		var o = client.options;
		if (n >= 90 && n < 800 && n != 0) { if (n < 330 || n > 400) { p.setYaw(0f); } }
		if (n == 100) togglePossess(client);
		if (n == 104 && net != null) net.sendChatCommand("gamemode survival");
		// jump height: press jump once and record how high Ruby gets
		if (n == 106) { moveY0 = p.getY(); jumpMax = moveY0; o.jumpKey.setPressed(true); }
		if (n == 108) o.jumpKey.setPressed(false);
		if (n > 106 && n < 150) jumpMax = Math.max(jumpMax, p.getY());
		if (n == 150) LOG.info("MOVE-TEST jump: peak {} blocks above the ground (jump_height={})", String.format("%.2f", jumpMax - moveY0), CONFIG.jumpHeight);
		// walking left: plain, then with Shift
		if (n == 120) { moveX0 = p.getX(); moveZ0 = p.getZ(); o.leftKey.setPressed(true); }
		if (n == 140) { LOG.info("MOVE-TEST left only: {} blocks in 20 ticks", String.format("%.2f", Math.hypot(p.getX() - moveX0, p.getZ() - moveZ0))); o.leftKey.setPressed(false); }
		if (n == 160) { o.forwardKey.setPressed(true); o.sneakKey.setPressed(true); }
		if (n >= 161 && n <= 190 && n % 4 == 1) LOG.info("MOVE-TEST dodge-run tick {}: clip={} sprinting={} speed={} combatActive={} shows={}", n, rig.clips[clipIndex].name, p.isSprinting(), String.format("%.2f", Math.hypot(p.getVelocity().x, p.getVelocity().z)), combat != null && combat.active(), combat != null && combat.showsClip());
		if (n == 170) { moveX0 = p.getX(); moveZ0 = p.getZ(); }
		if (n == 190) { LOG.info("MOVE-TEST left + Shift (after the dodge): {} blocks in 20 ticks clip={}", String.format("%.2f", Math.hypot(p.getX() - moveX0, p.getZ() - moveZ0)), rig.clips[clipIndex].name); o.forwardKey.setPressed(false); o.sneakKey.setPressed(false); }
		// aimed shots at a zombie 10 blocks ahead
		if (n == 192 && net != null) net.sendChatCommand("summon minecraft:zombie ~ ~ ~10 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
		if (n == 200) { p.setPitch(1.6f); }
		if (n >= 205 && n < 240 && n % 8 == 0) o.dropKey.setPressed(true);
		if (n >= 205 && n < 240 && n % 8 == 2) o.dropKey.setPressed(false);
		if (n == 245) LOG.info("MOVE-TEST aimed shots: {}", fightMobs(client));
		if (n == 250) { p.setPitch(30f); }
		if (n == 256) shoot(client);
		if (n >= 262 && n < 290 && n % 8 == 0) o.dropKey.setPressed(true);
		if (n >= 262 && n < 290 && n % 8 == 2) o.dropKey.setPressed(false);
		if (n == 295) LOG.info("MOVE-TEST shots aimed at the ground: {}", fightMobs(client));
		// water
		if (n == 300 && net != null) net.sendChatCommand("kill @e[type=minecraft:zombie]");
		if (n == 305 && net != null) net.sendChatCommand("fill ~-4 ~-3 ~-4 ~4 ~4 ~4 water");
		if (n == 345) { o.sneakKey.setPressed(true); }
		if (n == 360) LOG.info("MOVE-TEST sinking with Shift: y={} eyeY={} submerged={}", String.format("%.2f", p.getY()), String.format("%.2f", p.getEyeY()), p.isSubmergedInWater());
		if (n == 400) { LOG.info("MOVE-TEST after sinking: y={} submerged={}", String.format("%.2f", p.getY()), p.isSubmergedInWater()); o.sneakKey.setPressed(false); }
		if (n == 402) { p.setPitch(40f); o.forwardKey.setPressed(true); }
		if (n >= 404 && n <= 440 && n % 6 == 2) LOG.info("MOVE-TEST dive tick {}: y={} sprinting={} swimming={} submerged={} clip={}", n, String.format("%.2f", p.getY()), p.isSprinting(), p.isSwimming(), p.isSubmergedInWater(), rig.clips[clipIndex].name);
		if (n == 420) shoot(client);
		if (n == 441) { p.setPitch(-35f); }
		if (n >= 443 && n <= 447) { }
		if (n >= 443 && n <= 480 && n % 6 == 2) LOG.info("MOVE-TEST rise tick {}: y={} sprinting={} swimming={} submerged={}", n, String.format("%.2f", p.getY()), p.isSprinting(), p.isSwimming(), p.isSubmergedInWater());
		if (n == 462) shoot(client);
		if (n == 485) { o.forwardKey.setPressed(false); client.scheduleStop(); }
	}
	static void logTargets(MinecraftClient client, String label) {
		for (net.minecraft.entity.Entity e : client.world.getEntities()) {
			if (e instanceof net.minecraft.entity.mob.Monster && e instanceof net.minecraft.entity.LivingEntity le) {
				LOG.info("COMBAT-TEST {}: {} hp={} pos=({}, {}, {}) player=({}, {}, {})", label, e.getType().getUntranslatedName(),
					String.format("%.2f", le.getHealth()), String.format("%.1f", e.getX()), String.format("%.1f", e.getY()), String.format("%.1f", e.getZ()),
					String.format("%.1f", client.player.getX()), String.format("%.1f", client.player.getY()), String.format("%.1f", client.player.getZ()));
			}
		}
		CombatController c = combat;
		LOG.info("COMBAT-TEST {}: sounds started={} failed={} hitsLanded={} combatActive={} clip={} aura={} special={} hp={}", label, Sounds.STARTED.get(), Sounds.FAILED.get(), hitsLanded, c != null && c.active(), c == null ? null : c.clipName(), Math.round(RubyStats.shield()), String.format("%.2f", RubyStats.specialFill()), client.player.getHealth());
	}

	static void shoot(MinecraftClient client) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(),
			msg -> LOG.info("auto screenshot: {}", msg.getString()));
	}

	// ---- placed mode and loading

	static void placeRuby(MinecraftClient client) {
		if (client.player == null || !dataReady(client)) return;
		ensureLoaded(client);
		if (rig == null || !textureReady) return;
		if (possessed) togglePossess(client);
		Vec3d look = client.player.getRotationVec(1.0f);
		pos = client.player.getPos().add(look.x * 3, 0, look.z * 3);
		yawDegrees = client.player.getYaw();
		placed = true;
		playIndex(client, rig.findClip("Idle"), true, false);
	}

	static void ensureLoaded(MinecraftClient client) {
		Path dir = CONFIG.dataDir;
		try {
			if (rig == null) {
				rig = Rig.load(dir.resolve("ruby_rig.json"));
				skinned = new float[rig.vertexCount * 3];
				LOG.info("Loaded Ruby rig: {} vertices, {} bones, {} clips", rig.vertexCount, rig.bindPoses.length, rig.clips.length);
			}
			if (!textureReady) {
				try (InputStream in = Files.newInputStream(dir.resolve("ruby_texture.png"))) {
					NativeImage image = NativeImage.read(in);
					client.getTextureManager().registerTexture(TEXTURE, new NativeImageBackedTexture(image));
					textureReady = true;
					LOG.info("Loaded Ruby texture");
				}
			}
			// Combat data: without it Ruby can be possessed and animated but cannot fight.
			if (combatData == null && Files.exists(dir.resolve("ruby_combat.json"))) {
				combatData = CombatData.load(dir.resolve("ruby_combat.json"));
				LOG.info("Loaded Ruby combat data: {} abilities", combatData.abilities.size());
			}
			// Grimm Eclipse's own sounds are optional too (older data, or a failed sound conversion).
			Sounds.load(dir);
			// Crescent Rose is optional: Ruby still renders without it.
			if (weapon == null && Files.exists(dir.resolve("crescent_rose.json"))) {
				weapon = Prop.load(dir.resolve("crescent_rose.json"), rig);
				LOG.info("Loaded Crescent Rose: {} parts", weapon.parts.length);
			}
			if (!weaponTextureReady && Files.exists(dir.resolve("crescent_texture.png"))) {
				try (InputStream in = Files.newInputStream(dir.resolve("crescent_texture.png"))) {
					NativeImage image = NativeImage.read(in);
					client.getTextureManager().registerTexture(WEAPON_TEXTURE, new NativeImageBackedTexture(image));
					weaponTextureReady = true;
					LOG.info("Loaded Crescent Rose texture");
				}
			}
		} catch (Exception e) {
			LOG.error("Could not load Ruby data from {}", dir, e);
			tell(client, "Could not load Ruby's data (see logs/latest.log).");
		}
	}

	// ---- rendering

	/** Current clip and time; a finished one-shot falls back to the idle loop (or a fall loop in the air). */
	static Rig.Clip currentClip(float[] timeOut) {
		if (forcedClip >= 0) {
			timeOut[0] = forcedTime;
			return rig.clips[forcedClip];
		}
		Rig.Clip clip = rig.clips[clipIndex];
		float t = elapsed();
		if (looping) {
			t = clip.length > 0 ? t % clip.length : 0f;
		} else if (t > clip.length) {
			int next = rig.findClip(possessed && !wasOnGround ? "JumpFall" : "Idle");
			clipIndex = next;
			looping = true;
			startNanos = System.nanoTime();
			attackUntilNanos = 0;
			clip = rig.clips[next];
			t = 0f;
		}
		timeOut[0] = t;
		return clip;
	}

	static final float[] timeBuf = new float[1];

	// crossfade state (render thread only)
	static Rig.Clip lastClip, fadeFrom;
	static float lastClipTime, fadeFromTime, fadeSeconds;
	static long fadeStartNanos;

	/** A drawing error must never crash the game: it is logged once and the Melty test reports it. */
	static void render(WorldRenderContext ctx) {
		try {
			renderRuby(ctx);
		} catch (Exception e) {
			if (renderError == null) {
				renderError = e.toString();
				LOG.error("Drawing Ruby failed", e);
			}
		}
	}

	static void renderRuby(WorldRenderContext ctx) {
		if (rig == null || !textureReady || !(placed || possessed)) return;
		MinecraftClient client = MinecraftClient.getInstance();
		MatrixStack ms = ctx.matrixStack();
		if (ms == null) return;
		Vec3d cam = ctx.camera().getPos();

		Vec3d at;
		float yaw;
		float lean = 0f, leanPitch = 0f; // swimming: Ruby lies out in the water as Minecraft's swimming pose does
		if (possessed) {
			PlayerEntity p = client.player;
			if (p == null || client.options.getPerspective().isFirstPerson()) return;
			float delta = ctx.tickCounter().getTickDelta(true);
			at = p.getLerpedPos(delta);
			lean = p.getLeaningPitch(delta);
			leanPitch = p.getPitch(delta);
			// Unity +Z (Ruby's forward) = the player's forward.
			yaw = combat != null && combat.showsClip()
				? -combat.facingYaw()
				: -MathHelper.lerpAngleDegrees(delta, p.prevBodyYaw, p.bodyYaw);
		} else {
			at = pos;
			// Placed Ruby turns to face the player who placed her.
			yaw = 180f - yawDegrees;
		}

		Rig.Clip clip;
		CombatController cc = combat;
		if (possessed && cc != null && cc.showsClip() && cc.clipName() != null && rig.findClip(cc.clipName()) >= 0) {
			clip = rig.clips[rig.findClip(cc.clipName())];
			float sinceTick = Math.min(CombatController.DT, (System.nanoTime() - lastCombatTickNanos) / 1_000_000_000f);
			timeBuf[0] = Math.min(clip.length, cc.clipTime() + sinceTick);
		} else {
			clip = currentClip(timeBuf);
		}
		if (possessed && cc != null && !cc.rubyVisible()) return; // Ruby vanishes for a moment in her dash (as in Grimm Eclipse)
		// Crossfade: when the clip changes (or restarts), blend out of the pose it was in. Attacks snap quickly (GE uses
		// 0.05-0.1 s fades); walking, running and landing ease over 0.15 s.
		long nowNanos = System.nanoTime();
		float t = timeBuf[0];
		if (lastClip != null && (clip != lastClip || t < lastClipTime - 0.02f)) {
			fadeFrom = lastClip;
			fadeFromTime = lastClipTime;
			fadeStartNanos = nowNanos;
			fadeSeconds = possessed && cc != null && cc.showsClip() ? 0.07f : 0.15f;
		}
		lastClip = clip;
		lastClipTime = t;
		float weight = 0f;
		float fromTime = 0f;
		if (fadeFrom != null) {
			float elapsedFade = (nowNanos - fadeStartNanos) / 1_000_000_000f;
			float a = elapsedFade / fadeSeconds;
			if (a >= 1f) {
				fadeFrom = null;
			} else {
				weight = 1f - a * a * (3f - 2f * a); // smoothstep
				fromTime = fadeFromTime + elapsedFade;
			}
		}
		rig.skin(clip, t, fadeFrom, fromTime, weight, skinned);

		ms.push();
		ms.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
		if (lean > 0f) {
			ms.translate(0, 0.35 * lean, 0);
			ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees((90f + MathHelper.clamp(leanPitch, -60f, 60f)) * lean));
		}
		ms.scale(SCALE, SCALE, SCALE);
		MatrixStack.Entry entry = ms.peek();
		Matrix4f m = entry.getPositionMatrix();

		VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
		int triangles = 0;
		for (int sm = 0; sm < DRAWN_SUBMESHES; sm++) {
			emit(vc, m, entry, skinned, rig.uvs, rig.indices, rig.subs[sm][0], rig.subs[sm][1]);
			triangles += rig.subs[sm][1] / 3;
		}
		lastTriangles = triangles;
		framesDrawn++;

		if (weapon != null && weaponTextureReady) {
			VertexConsumer wvc = ctx.consumers().getBuffer(RenderLayer.getEntityCutoutNoCull(WEAPON_TEXTURE));
			for (Prop.Part part : weapon.parts) {
				if (part.node < 0) continue;
				float[] w = rig.worldMatrix(part.node);
				int n = part.positions.length / 3;
				if (propScratch.length < n * 3) propScratch = new float[n * 3];
				for (int v = 0; v < n; v++) {
					float x = part.positions[v * 3], y = part.positions[v * 3 + 1], z = part.positions[v * 3 + 2];
					propScratch[v * 3] = w[0] * x + w[1] * y + w[2] * z + w[3];
					propScratch[v * 3 + 1] = w[4] * x + w[5] * y + w[6] * z + w[7];
					propScratch[v * 3 + 2] = w[8] * x + w[9] * y + w[10] * z + w[11];
				}
				emit(wvc, m, entry, propScratch, part.uvs, part.indices, part.subs[0], part.subs[1]);
			}
		}
		ms.pop();
	}

	static float[] propScratch = new float[0];

	/** Emit triangles from Unity-space positions (left-handed, Y-up), mirrored on X for Minecraft. */
	static void emit(VertexConsumer vc, Matrix4f m, MatrixStack.Entry entry,
			float[] p, float[] uv, int[] indices, int first, int count) {
		for (int i = first; i + 2 < first + count; i += 3) {
			int a = indices[i], b = indices[i + 1], c = indices[i + 2];
			float ax = -p[a * 3], ay = p[a * 3 + 1], az = p[a * 3 + 2];
			float bx = -p[b * 3], by = p[b * 3 + 1], bz = p[b * 3 + 2];
			float cx = -p[c * 3], cy = p[c * 3 + 1], cz = p[c * 3 + 2];
			float ux = bx - ax, uy = by - ay, uz = bz - az;
			float vx = cx - ax, vy = cy - ay, vz = cz - az;
			float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 0) { nx /= len; ny /= len; nz /= len; }
			vert(vc, m, entry, ax, ay, az, uv[a * 2], uv[a * 2 + 1], nx, ny, nz);
			vert(vc, m, entry, bx, by, bz, uv[b * 2], uv[b * 2 + 1], nx, ny, nz);
			vert(vc, m, entry, cx, cy, cz, uv[c * 2], uv[c * 2 + 1], nx, ny, nz);
			// Entity layers draw quads: repeat the last vertex.
			vert(vc, m, entry, cx, cy, cz, uv[c * 2], uv[c * 2 + 1], nx, ny, nz);
		}
	}

	static void vert(VertexConsumer vc, Matrix4f m, MatrixStack.Entry entry,
			float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		vc.vertex(m, x, y, z)
			.color(255, 255, 255, 255)
			.texture(u, 1.0f - v) // source V is bottom-up
			.overlay(OverlayTexture.DEFAULT_UV)
			.light(15728880)
			.normal(entry, nx, ny, nz);
	}
}

