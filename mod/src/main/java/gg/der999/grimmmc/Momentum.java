package gg.der999.grimmmc;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Ruby's combo momentum. Every hit she lands within a few seconds of the last one adds to the combo; the longer it runs the higher
 * her power level, which makes her hit harder, run faster and take less damage, and a kill with a combo running heals her. All of it
 * wears off a few seconds after the last hit. Read from the client and server threads, hence synchronized.
 */
final class Momentum {
	private Momentum() {}

	/** The combo breaks when no hit lands for this long. */
	static long windowMs() { return RubyLevel.comboWindowMs(); }
	/** Attacks that connect in a combo needed for power level 1, 2, 3 and 4. */
	static final int[] STEPS = {4, 10, 20, 35};
	/** The speed and resistance effects last this long (they are refreshed by every hit). */
	static final int EFFECT_TICKS = 120;

	private static int hits;
	private static long last;

	static synchronized void reset() {
		hits = 0;
		last = 0;
	}

	static synchronized void hit(int n) {
		long now = System.currentTimeMillis();
		if (now - last > windowMs()) hits = 0;
		hits += n;
		last = now;
	}

	/** Hits in the running combo, 0 once it has broken. */
	static synchronized int hits() {
		return System.currentTimeMillis() - last > windowMs() ? 0 : hits;
	}

	/** 0 to 4. */
	static synchronized int level() {
		int h = hits(), level = 0;
		for (int step : STEPS) if (h >= Math.round(step * RubyLevel.comboStepScale())) level++;
		return level;
	}

	/** How much of the window is left, 0 to 1 (for the HUD). */
	static synchronized float remaining() {
		return MathHelper.clamp(1f - (System.currentTimeMillis() - last) / (float) windowMs(), 0f, 1f);
	}

	/** Her hits do 10% more per level. */
	static float damageMultiplier(int level) {
		return 1f + 0.10f * level;
	}

	/** Gives the player the speed and resistance a power level earns (server thread). */
	static void applyEffects(ServerPlayerEntity player, int level) {
		if (level >= 2) player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, EFFECT_TICKS, level >= 4 ? 1 : 0, true, false, false));
		if (level >= 3) player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, EFFECT_TICKS, 0, true, false, false));
	}

	/** Health a kill gives back, in health points: a tenth of the victim's health (at least one point, at most six), up to twice that at power level 4. */
	static float healFor(LivingEntity victim, int level) {
		float base = MathHelper.clamp(victim.getMaxHealth() * 0.10f, 1f, 6f);
		return base * (1f + 0.25f * level) * RubyLevel.healMultiplier();
	}
}
