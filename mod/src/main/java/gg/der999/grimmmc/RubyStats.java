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

	static final float MAX_SHIELD = 4000f, MAX_HEALTH = 1000f;
	/** Minecraft damage to aura points: the full aura is worth a full row of hearts (20 health). */
	static final float SHIELD_PER_DAMAGE = MAX_SHIELD / 20f;
	static final float RECHARGE_DELAY = 3f, DEPLETED_DELAY = 5f, RECHARGE_SECONDS = 2f;
	static final int HITS_PER_SPECIAL = 24;

	private static float shield = MAX_SHIELD;
	private static boolean depleted;
	private static float sinceHit = 99f;
	private static int hits;
	private static boolean specialReady;

	static synchronized void reset() {
		shield = MAX_SHIELD;
		depleted = false;
		sinceHit = 99f;
		hits = 0;
		specialReady = false;
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
		while (hits >= HITS_PER_SPECIAL) {
			hits -= HITS_PER_SPECIAL;
			specialReady = true;
		}
	}

	/** Once per client tick while Ruby is possessed. */
	static synchronized void tick(float dt) {
		sinceHit += dt;
		if (sinceHit >= (depleted ? DEPLETED_DELAY : RECHARGE_DELAY) && shield < MAX_SHIELD) {
			shield = Math.min(MAX_SHIELD, shield + MAX_SHIELD / RECHARGE_SECONDS * dt);
			if (shield >= MAX_SHIELD) depleted = false;
		}
	}

	static synchronized float shield() { return shield; }

	static synchronized float specialFill() { return specialReady ? 1f : hits / (float) HITS_PER_SPECIAL; }

	static synchronized boolean specialReady() { return specialReady; }

	// ---- HUD

	private static final int W = 110, H = 7;

	static void render(DrawContext ctx, MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null || !GrimmMcClient.possessed || client.options.hudHidden) return;
		int x = 8;
		int y = 8;
		float health = Math.max(0f, p.getHealth()) / Math.max(1f, p.getMaxHealth()) * MAX_HEALTH;
		bar(ctx, client, x, y, "Aura", shield() / MAX_SHIELD, 0xFFE8EEF5, 0xFF8FB4D9, Math.round(shield()));
		bar(ctx, client, x, y + 11, "Health", health / MAX_HEALTH, 0xFFE0453F, 0xFF7A1F1C, Math.round(health));
		bar(ctx, client, x, y + 22, "Special", specialFill(), specialReady() ? 0xFFFFD34D : 0xFFC05A87, 0xFF5A2840,
			specialReady() ? 1 : 0);
	}

	private static void bar(DrawContext ctx, MinecraftClient client, int x, int y, String label, float fill, int color, int back, int value) {
		fill = Math.max(0f, Math.min(1f, fill));
		ctx.fill(x - 1, y - 1, x + W + 1, y + H + 1, 0xAA000000);
		ctx.fill(x, y, x + W, y + H, back);
		ctx.fill(x, y, x + Math.round(W * fill), y + H, color);
		ctx.drawText(client.textRenderer, label + " " + value, x + W + 6, y, 0xFFFFFFFF, true);
	}
}
