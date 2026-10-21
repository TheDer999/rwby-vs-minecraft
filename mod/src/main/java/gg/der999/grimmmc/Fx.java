package gg.der999.grimmmc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Sound and visual effects for Ruby's moves. Grimm Eclipse's own sounds and effects are not used (nothing from the game is
 * shipped); Minecraft's own sounds and particles stand in, with Ruby's rose petals as red dust. Client side only.
 */
final class Fx {
	private Fx() {}

	private static final DustParticleEffect PETAL = new DustParticleEffect(0xC81E2D, 1.3f);
	private static final DustParticleEffect PETAL_DARK = new DustParticleEffect(0x5A0F18, 1.0f);

	static void sound(SoundEvent e, float volume, float pitch) {
		if (Sounds.active()) return; // Ruby's own sounds play instead
		MinecraftClient c = MinecraftClient.getInstance();
		ClientPlayerEntity p = c.player;
		if (p == null || c.world == null) return;
		c.world.playSound(p.getX(), p.getY(), p.getZ(), e, SoundCategory.PLAYERS, volume, pitch, false);
	}

	/** A slash at Ruby's weapon height in front of her, as Grimm Eclipse's swipe effect. */
	static void swipe(float facingYaw, float damage) {
		MinecraftClient c = MinecraftClient.getInstance();
		ClientPlayerEntity p = c.player;
		if (p == null || c.world == null) return;
		float yaw = facingYaw * MathHelper.RADIANS_PER_DEGREE;
		Vec3d at = p.getPos().add(-MathHelper.sin(yaw) * 1.5, 1.0, MathHelper.cos(yaw) * 1.5);
		c.world.addParticle(damage >= 150f ? ParticleTypes.SWEEP_ATTACK : ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 0, 0, 0);
		sound(damage >= 300f ? SoundEvents.ITEM_MACE_SMASH_GROUND : damage >= 100f ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
			0.7f, damage >= 300f ? 1.1f : 1.0f);
	}

	/** Rose petals trailing behind Ruby while she dashes. */
	static void petals(Vec3d at, int count) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null) return;
		var r = c.world.random;
		for (int i = 0; i < count; i++) {
			c.world.addParticle(r.nextInt(3) == 0 ? PETAL_DARK : PETAL, at.x + (r.nextDouble() - 0.5) * 0.7, at.y + 0.2 + r.nextDouble() * 1.4,
				at.z + (r.nextDouble() - 0.5) * 0.7, (r.nextDouble() - 0.5) * 0.05, 0.02, (r.nextDouble() - 0.5) * 0.05);
		}
	}

	/** A burst of petals and dust around a point (smash, special, warp). */
	static void burst(Vec3d at, int count, boolean ring) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null) return;
		var r = c.world.random;
		for (int i = 0; i < count; i++) {
			double a = r.nextDouble() * Math.PI * 2, s = ring ? 0.35 : 0.15 + r.nextDouble() * 0.2;
			c.world.addParticle(PETAL, at.x, at.y + 0.5, at.z, Math.cos(a) * s, 0.05 + r.nextDouble() * 0.1, Math.sin(a) * s);
		}
		if (ring) for (int i = 0; i < 10; i++) {
			double a = i * Math.PI / 5;
			c.world.addParticle(ParticleTypes.POOF, at.x + Math.cos(a) * 0.6, at.y + 0.1, at.z + Math.sin(a) * 0.6, Math.cos(a) * 0.1, 0.02, Math.sin(a) * 0.1);
		}
	}

	/** What each ability sounds like when it starts. */
	static void started(String coroutine, Vec3d at) {
		switch (coroutine) {
			case "DodgeLand", "DodgeAir" -> { sound(SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 0.5f, 1.5f); petals(at, 14); }
			case "LightDodgeAttack" -> { sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.4f); petals(at, 10); }
			case "HeavyCombo_00" -> { sound(SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 0.5f, 1.3f); petals(at, 16); }
			case "Crescendo" -> { sound(SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 0.8f, 0.9f); burst(at, 30, true); }
			case "Counter" -> sound(SoundEvents.ITEM_SHIELD_BLOCK, 0.8f, 1.4f);
			case "FailedCounter" -> sound(SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.6f, 1.0f);
			case "Reap" -> sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.5f);
			default -> { }
		}
	}

	static void chargeComplete(Vec3d at) {
		sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.6f);
		burst(at, 14, false);
	}

	static void shot(boolean last) {
		Sounds.shot(last);
		sound(SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, 0.5f, 1.5f);
	}

	static void warp(Vec3d at) {
		Sounds.warp();
		sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.8f);
		petals(at, 18);
	}

	static void smash(Vec3d at) {
		sound(SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, 0.9f, 1.0f);
		burst(at, 36, true);
	}
}