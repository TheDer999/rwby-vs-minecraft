package gg.der999.grimmmc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Ruby's Grimm Eclipse meters. Her aura (4,000) soaks up damage before her health (1,000) is touched, starts to recharge
 * a few seconds after she was last hit (five if it was emptied) and refills in two. Every 24 hits she lands earns a special
 * charge. Health is the player's own Minecraft health, shown on Grimm Eclipse's 1,000 scale.
 */
final class RubyStats {
	private RubyStats() {}

	/** Grimm Eclipse's aura is 4,000; three times that (config aura_max) suits Minecraft's steadier incoming damage. */
	static float maxShield() { return GrimmMcClient.CONFIG.auraMax * RubyLevel.auraMultiplier(); }
	static final float MAX_HEALTH = 1000f;
	/** Minecraft damage to aura points: the full aura is worth a full row of hearts (20 health). */
	static final float SHIELD_PER_DAMAGE = 4000f / 20f;
	/** Grimm Eclipse refills the aura in 2 s; in Minecraft that felt too fast, so the default is three times slower (config aura_recharge_seconds). */
	static final float RECHARGE_DELAY = 3f, DEPLETED_DELAY = 5f;
	static float rechargeSeconds() { return GrimmMcClient.CONFIG.auraRechargeSeconds * RubyLevel.auraRechargeFactor(); }
	static int hitsPerSpecial() { return RubyLevel.hitsPerSpecial(); }

	private static float shield = GrimmMcClient.CONFIG.auraMax;
	private static boolean depleted, recharging;
	private static float sinceHit = 99f;
	private static int hits;
	private static boolean specialReady;

	static synchronized void reset() {
		shield = maxShield();
		depleted = false;
		recharging = false;
		sinceHit = 99f;
		hits = 0;
		specialReady = false;
		Momentum.reset();
	}

	/** Damage the aura takes first; returns what is left over for her health (called on the server thread). */
	static synchronized float absorb(float damage) {
		if (damage <= 0f) return damage;
		sinceHit = 0f;
		float cost = damage * SHIELD_PER_DAMAGE;
		if (cost <= shield) {
			shield -= cost;
			if (shield <= 0f) { shield = 0f; depleted = true; }
			return 0f;
		}
		float rest = (cost - shield) / SHIELD_PER_DAMAGE;
		shield = 0f;
		depleted = true;
		return rest;
	}

	/** Spend the special charge (the meter keeps the progress toward the next one). */
	static synchronized void useSpecial() {
		specialReady = false;
	}

	/** Landed hits fill the special meter. */
	static synchronized void addHits(int n) {
		hits += n;
		while (hits >= hitsPerSpecial()) {
			hits -= hitsPerSpecial();
			specialReady = true;
		}
	}

	/** Once per client tick while Ruby is possessed. */
	static synchronized void tick(float dt) {
		sinceHit += dt;
		if (sinceHit >= (depleted ? DEPLETED_DELAY : RECHARGE_DELAY) && shield < maxShield()) {
			if (!recharging) { recharging = true; Sounds.auraRecharge(); }
			shield =Math.min(maxShield(), shield + maxShield() / Math.max(0.1f, rechargeSeconds()) * dt);
			if (shield >= maxShield()) depleted = false;
		} else if (sinceHit < RECHARGE_DELAY || shield >= maxShield()) {
			recharging = false;
		}
	}

	static synchronized float shield() { return shield; }

	static synchronized float specialFill() { return specialReady ? 1f : hits / (float) hitsPerSpecial(); }

	static synchronized boolean specialReady() { return specialReady; }

	// ---- HUD

	private static final int W = 110, H = 7;

	static void render(DrawContext ctx, MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null || !GrimmMcClient.possessed || client.options.hudHidden) return;
		int x = 8;
		int y = 8;
		float health = Math.max(0f, p.getHealth()) / Math.max(1f, p.getMaxHealth()) * MAX_HEALTH;
		bar(ctx, client, x, y, "Aura", shield() / maxShield(), 0xFFE8EEF5, 0xFF8FB4D9, Math.round(shield()));
		bar(ctx, client, x, y + 11, "Health", health / MAX_HEALTH, 0xFFE0453F, 0xFF7A1F1C, Math.round(health));
		bar(ctx, client, x, y + 22, "Special", specialFill(), specialReady() ? 0xFFFFD34D : 0xFFC05A87, 0xFF5A2840,
			specialReady() ? 1 : 0);
		// Ruby's level: progress toward the next one
		int rubyLevel = RubyLevel.level();
		ctx.fill(x - 1, y + 32, x + W + 1, y + 37, 0xAA000000);
		ctx.fill(x, y + 33, x + W, y + 36, 0xFF3A2F52);
		ctx.fill(x, y + 33, x + Math.round(W * RubyLevel.progress()), y + 36, RubyLevel.legacy() ? 0xFFFFD34D : 0xFFB48CFF);
		int prestige = RubyLevel.prestige();
		ctx.drawText(client.textRenderer, "Level " + rubyLevel + (rubyLevel >= RubyLevel.MAX ? " (max)" : "") + (prestige > 0 ? "  Prestige " + prestige : "") + (RubyLevel.legacy() ? "  Legacy" : ""), x + W + 6, y + 32, RubyLevel.legacy() ? 0xFFFFD34D : 0xFFFFFFFF, true);
		int combo = Momentum.hits();
		if (combo > 0) {
			int level = Momentum.level();
			ctx.fill(x - 1, y + 43, x + W + 1, y + 47, 0xAA000000);
			ctx.fill(x, y + 44, x + Math.round(W * Momentum.remaining()), y + 46, level > 0 ? 0xFFFFA23B : 0xFF9AA5B1);
			ctx.drawText(client.textRenderer, "Combo " + combo + (level > 0 ? "  Power " + level : ""), x, y + 49, level > 0 ? 0xFFFFD34D : 0xFFFFFFFF, true);
		}
	}

	private static void bar(DrawContext ctx, MinecraftClient client, int x, int y, String label, float fill, int color, int back, int value) {
		fill = Math.max(0f, Math.min(1f, fill));
		ctx.fill(x - 1, y - 1, x + W + 1, y + H + 1, 0xAA000000);
		ctx.fill(x, y, x + W, y + H, back);
		ctx.fill(x, y, x + Math.round(W * fill), y + H, color);
		ctx.drawText(client.textRenderer, label + " " + value, x + W + 6, y, 0xFFFFFFFF, true);
	}
}
