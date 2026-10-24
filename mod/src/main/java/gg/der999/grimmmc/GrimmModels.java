package gg.der999.grimmmc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The Grimm models, converted from the player's own Grimm Eclipse install, loaded when the first one is drawn. */
final class GrimmModels {
	private GrimmModels() {}

	static final class Model {
		Rig rig;
		Identifier[] textures;
		float[] skinned;
	}

	private static final Map<GrimmKind, Model> MODELS = new EnumMap<>(GrimmKind.class);
	private static final Set<GrimmKind> FAILED = EnumSet.noneOf(GrimmKind.class);

	/** True when the converter has written the Grimm models (a Grimm Eclipse install was converted with this version). */
	static boolean available() {
		Path d = GrimmMcClient.CONFIG.dataDir;
		for (GrimmKind k : GrimmKind.values()) if (!Files.exists(d.resolve(k.id + "_rig.json"))) return false;
		return true;
	}

	/** The model of a kind (render thread), or null if it cannot be loaded. */
	static Model get(GrimmKind kind) {
		Model m = MODELS.get(kind);
		if (m != null || FAILED.contains(kind)) return m;
		Path d = GrimmMcClient.CONFIG.dataDir;
		try {
			m = new Model();
			m.rig = Rig.load(d.resolve(kind.id + "_rig.json"));
			m.skinned = new float[m.rig.vertexCount * 3];
			int n = Math.max(1, m.rig.subs.length);
			m.textures = new Identifier[n];
			MinecraftClient client = MinecraftClient.getInstance();
			for (int i = 0; i < n; i++) {
				Path png = d.resolve(kind.id + "_texture" + i + ".png");
				if (!Files.exists(png)) { m.textures[i] = i > 0 ? m.textures[0] : null; continue; }
				try (InputStream in = Files.newInputStream(png)) {
					Identifier id = Identifier.of("grimmmc", "grimm/" + kind.id + "_" + i);
					client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(NativeImage.read(in)));
					m.textures[i] = id;
				}
			}
			MODELS.put(kind, m);
			Sounds.load(d); // the Grimm's voices (a no-op when Ruby has already loaded them)
			GrimmMcClient.LOG.info("Loaded the {} model: {} vertices, {} clips", kind.id, m.rig.vertexCount, m.rig.clips.length);
			return m;
		} catch (Exception e) {
			FAILED.add(kind);
			GrimmMcClient.LOG.error("Could not load the {} model", kind.id, e);
			return null;
		}
	}

	/** Length of a clip in seconds, or 0 when the model is not loaded or has no such clip. */
	static float clipLength(GrimmKind kind, String name) {
		Model m = MODELS.get(kind);
		if (m == null) return 0f;
		int i = m.rig.findClip(name);
		return i < 0 ? 0f : m.rig.clips[i].length;
	}

	/** Draws one Grimm in the given clip at the given time; the matrix stack is at its feet. */
	static void draw(GrimmKind kind, int size, String clipName, float time, boolean loop, float bodyYaw, boolean hurt,
			MatrixStack ms, VertexConsumerProvider vcp, int light) {
		Model m = get(kind);
		if (m == null) return;
		Rig rig = m.rig;
		int ci = rig.findClip(clipName);
		if (ci < 0) ci = rig.findClip("Idle");
		if (ci < 0) return;
		Rig.Clip clip = rig.clips[ci];
		float t = loop ? (clip.length > 0 ? time % clip.length : 0f) : Math.min(time, clip.length);
		rig.skin(clip, t, m.skinned);

		ms.push();
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-bodyYaw)); // Unity +Z is the model's front
		float k = CombatController.SCALE * kind.scale[size];
		ms.scale(k, k, k);
		MatrixStack.Entry entry = ms.peek();
		Matrix4f mat = entry.getPositionMatrix();
		int overlay = OverlayTexture.packUv(OverlayTexture.getU(0.0f), OverlayTexture.getV(hurt));
		for (int sm = 0; sm < rig.subs.length; sm++) {
			Identifier tex = m.textures[Math.min(sm, m.textures.length - 1)];
			if (tex == null) continue;
			VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(tex));
			emit(vc, mat, entry, m.skinned, rig.uvs, rig.indices, rig.subs[sm][0], rig.subs[sm][1], light, overlay);
		}
		ms.pop();
	}

	/** Triangles from Unity-space positions (left-handed, Y-up), mirrored on X for Minecraft. */
	private static void emit(VertexConsumer vc, Matrix4f m, MatrixStack.Entry entry, float[] p, float[] uv, int[] indices,
			int first, int count, int light, int overlay) {
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
			vert(vc, m, entry, ax, ay, az, uv[a * 2], uv[a * 2 + 1], nx, ny, nz, light, overlay);
			vert(vc, m, entry, bx, by, bz, uv[b * 2], uv[b * 2 + 1], nx, ny, nz, light, overlay);
			vert(vc, m, entry, cx, cy, cz, uv[c * 2], uv[c * 2 + 1], nx, ny, nz, light, overlay);
			vert(vc, m, entry, cx, cy, cz, uv[c * 2], uv[c * 2 + 1], nx, ny, nz, light, overlay); // entity layers draw quads
		}
	}

	private static void vert(VertexConsumer vc, Matrix4f m, MatrixStack.Entry entry, float x, float y, float z, float u, float v,
			float nx, float ny, float nz, int light, int overlay) {
		vc.vertex(m, x, y, z).color(255, 255, 255, 255).texture(u, 1.0f - v).overlay(overlay).light(light).normal(entry, nx, ny, nz);
	}
}
