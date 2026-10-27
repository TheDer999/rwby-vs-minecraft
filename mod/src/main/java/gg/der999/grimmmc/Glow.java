package gg.der999.grimmmc;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Glowing sparks for Ruby's effects: rose petals, the trail of a shot and the burst where it lands. Minecraft's own dust particles are lit like
 * the world, so at night they were dull; these are small camera-facing glows drawn at full brightness. Client side only.
 */
final class Glow {
	private Glow() {}

	private static final class Spark {
		double x, y, z, vx, vy, vz;
		int age, life;
		float size;
		int r, g, b;
		double gravity;
	}

	private static final List<Spark> SPARKS = new ArrayList<>();
	private static final Identifier TEXTURE = Identifier.of("grimmmc", "glow/spark");
	private static boolean textureReady, failed;
	private static final int MAX = 600;

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.isPaused()) return;
			for (Iterator<Spark> it = SPARKS.iterator(); it.hasNext(); ) {
				Spark s = it.next();
				if (++s.age >= s.life) { it.remove(); continue; }
				s.x += s.vx; s.y += s.vy; s.z += s.vz;
				s.vx *= 0.94; s.vz *= 0.94; s.vy = s.vy * 0.94 - s.gravity;
			}
		});
	}

	/** A rose petal: red (some darker), drifts and falls a little. */
	static void petal(Vec3d at, double vx, double vy, double vz, boolean dark) {
		Spark s = make(at, vx, vy, vz, 22 + MinecraftClient.getInstance().world.random.nextInt(14), 0.16f + (dark ? 0f : 0.04f));
		s.r = dark ? 120 : 235; s.g = dark ? 20 : 40; s.b = dark ? 35 : 55;
		s.gravity = 0.004;
	}

	/** A bright pink-white spark (the trail of a shot, the burst where it lands). */
	static void spark(Vec3d at, double vx, double vy, double vz, int life, float size) {
		Spark s = make(at, vx, vy, vz, life, size);
		s.r = 255; s.g = 170; s.b = 190;
		s.gravity = 0.0;
	}

	private static Spark make(Vec3d at, double vx, double vy, double vz, int life, float size) {
		Spark s = new Spark();
		s.x = at.x; s.y = at.y; s.z = at.z; s.vx = vx; s.vy = vy; s.vz = vz;
		s.life = life; s.size = size;
		if (SPARKS.size() >= MAX) SPARKS.remove(0);
		SPARKS.add(s);
		return s;
	}

	private static void ensureTexture() {
		if (textureReady || failed) return;
		try {
			int n = 16;
			NativeImage img = new NativeImage(NativeImage.Format.RGBA, n, n, false);
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
					double d = Math.sqrt(dx * dx + dy * dy);
					double a = MathHelper.clamp(1.0 - d, 0.0, 1.0);
					int alpha = (int) Math.round(255 * a * Math.sqrt(a)); // bright core, soft edge
					img.setColorArgb(x, y, (alpha << 24) | 0xFFFFFF);
				}
			}
			NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
			MinecraftClient.getInstance().getTextureManager().registerTexture(TEXTURE, tex);
			textureReady = true;
		} catch (Exception e) {
			failed = true;
			GrimmMcClient.LOG.error("Could not make the glow texture", e);
		}
	}

	/** Draws every spark (after the entities). */
	static void render(WorldRenderContext ctx) {
		if (SPARKS.isEmpty()) return;
		ensureTexture();
		if (!textureReady) return;
		MatrixStack ms = ctx.matrixStack();
		if (ms == null || ctx.consumers() == null) return;
		Vec3d cam = ctx.camera().getPos();
		float delta = ctx.tickCounter().getTickDelta(false);
		VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getEyes(TEXTURE));
		for (Spark s : SPARKS) {
			float f = (s.age + delta) / s.life;
			float fade = f < 0.15f ? f / 0.15f : MathHelper.clamp((1f - f) / 0.6f, 0f, 1f);
			int alpha = Math.round(255 * fade);
			if (alpha <= 2) continue;
			ms.push();
			ms.translate(s.x + s.vx * delta - cam.x, s.y + s.vy * delta - cam.y, s.z + s.vz * delta - cam.z);
			ms.multiply(ctx.camera().getRotation());
			Matrix4f m = ms.peek().getPositionMatrix();
			float h = s.size * (0.6f + 0.4f * (1f - f));
			quad(vc, m, ms.peek(), h, s.r, s.g, s.b, alpha);
			ms.pop();
		}
	}

	private static void quad(VertexConsumer vc, Matrix4f m, MatrixStack.Entry e, float h, int r, int g, int b, int a) {
		vert(vc, m, e, -h, -h, 0f, 1f, r, g, b, a);
		vert(vc, m, e, -h, h, 0f, 0f, r, g, b, a);
		vert(vc, m, e, h, h, 1f, 0f, r, g, b, a);
		vert(vc, m, e, h, -h, 1f, 1f, r, g, b, a);
	}

	private static void vert(VertexConsumer vc, Matrix4f m, MatrixStack.Entry e, float x, float y, float u, float v, int r, int g, int b, int a) {
		vc.vertex(m, x, y, 0f).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(15728880).normal(e, 0f, 0f, 1f);
	}
}
