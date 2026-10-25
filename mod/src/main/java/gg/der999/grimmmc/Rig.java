package gg.der999.grimmmc;

import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Skinned mesh plus legacy animation clips, read from ruby_rig.json (converted from the
 * player's own Grimm Eclipse data). All data is in Unity's left-handed, Y-up space.
 * Matrices are row-major, column-vector convention (translation in m[3], m[7], m[11]).
 * The file is stream-parsed because it holds dozens of 60 Hz clips (about 17 MB).
 */
final class Rig {
	static final class Node {
		String path;
		int parent = -1;
	}

	static final class Track {
		int node;
		float[][] rot = new float[0][], pos = new float[0][], scale = new float[0][]; // keys: [time, comps...]
	}

	static final class Clip {
		String name;
		float length;
		Track[] tracks = new Track[0];
		Track[] byNode; // indexed by node, null when the clip does not animate it
	}

	int vertexCount;
	int[][] subs;        // [firstIndex, indexCount]
	int[] indices;
	float[] positions;   // V*3
	float[] uvs;         // V*2
	int[] skinIdx;       // V*4
	float[] skinW;       // V*4
	float[][] bindPoses; // 16 each
	int[] meshBones;     // mesh bone -> node index
	/** Optional transform above the skeleton root (a rotation a model's prefab carries), row-major, or null. */
	float[] rootMatrix;
	Node[] nodes;
	Clip[] clips;

	private float[][] world;
	private float[][] skinMats;
	/** Pose of the first clip at t=0, used for nodes a clip does not animate. */
	private float[][] restRot, restPos, restScale;

	private Rig() {}

	static Rig load(Path file) throws IOException {
		Rig rig = new Rig();
		try (Reader r = Files.newBufferedReader(file); JsonReader in = new JsonReader(r)) {
			in.beginObject();
			while (in.hasNext()) {
				String key = in.nextName();
				switch (key) {
					case "verts" -> rig.vertexCount = in.nextInt();
					case "subs" -> rig.subs = readIntPairs(in);
					case "indices" -> rig.indices = readInts(in);
					case "pos" -> rig.positions = readFloats(in);
					case "uv" -> rig.uvs = readFloats(in);
					case "skinIdx" -> rig.skinIdx = readInts(in);
					case "skinW" -> rig.skinW = readFloats(in);
					case "bindPoses" -> rig.bindPoses = readFloatRows(in);
					case "meshBones" -> rig.meshBones = readInts(in);
					case "root" -> rig.rootMatrix = readFloats(in);
					case "nodes" -> rig.nodes = readNodes(in);
					case "clips" -> rig.clips = readClips(in, rig.nodes.length);
					default -> in.skipValue();
				}
			}
			in.endObject();
		}
		rig.finish();
		return rig;
	}

	private void finish() {
		world = new float[nodes.length][16];
		skinMats = new float[bindPoses.length][16];
		restRot = new float[nodes.length][4];
		restPos = new float[nodes.length][3];
		restScale = new float[nodes.length][3];
		for (int i = 0; i < nodes.length; i++) {
			restRot[i] = new float[] {0, 0, 0, 1};
			restScale[i] = new float[] {1, 1, 1};
			Track t = clips[0].byNode[i];
			if (t == null) continue;
			sample(t.rot, 0f, 4, restRot[i], restRot[i]);
			sample(t.pos, 0f, 3, restPos[i], restPos[i]);
			sample(t.scale, 0f, 3, restScale[i], restScale[i]);
		}
	}

	// ---- parsing helpers

	private static int[][] readIntPairs(JsonReader in) throws IOException {
		List<int[]> out = new ArrayList<>();
		in.beginArray();
		while (in.hasNext()) out.add(readInts(in));
		in.endArray();
		return out.toArray(new int[0][]);
	}

	private static int[] readInts(JsonReader in) throws IOException {
		int[] a = new int[16];
		int n = 0;
		in.beginArray();
		while (in.hasNext()) {
			if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
			a[n++] = in.nextInt();
		}
		in.endArray();
		return java.util.Arrays.copyOf(a, n);
	}

	private static float[] readFloats(JsonReader in) throws IOException {
		float[] a = new float[16];
		int n = 0;
		in.beginArray();
		while (in.hasNext()) {
			if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
			a[n++] = (float) in.nextDouble();
		}
		in.endArray();
		return java.util.Arrays.copyOf(a, n);
	}

	private static float[][] readFloatRows(JsonReader in) throws IOException {
		List<float[]> out = new ArrayList<>();
		in.beginArray();
		while (in.hasNext()) out.add(readFloats(in));
		in.endArray();
		return out.toArray(new float[0][]);
	}

	private static Node[] readNodes(JsonReader in) throws IOException {
		List<Node> out = new ArrayList<>();
		in.beginArray();
		while (in.hasNext()) {
			Node n = new Node();
			in.beginObject();
			while (in.hasNext()) {
				switch (in.nextName()) {
					case "path" -> n.path = in.nextString();
					case "parent" -> n.parent = in.nextInt();
					default -> in.skipValue();
				}
			}
			in.endObject();
			out.add(n);
		}
		in.endArray();
		return out.toArray(new Node[0]);
	}

	private static Clip[] readClips(JsonReader in, int nodeCount) throws IOException {
		List<Clip> out = new ArrayList<>();
		in.beginArray();
		while (in.hasNext()) {
			Clip c = new Clip();
			List<Track> tracks = new ArrayList<>();
			in.beginObject();
			while (in.hasNext()) {
				switch (in.nextName()) {
					case "name" -> c.name = in.nextString();
					case "length" -> c.length = (float) in.nextDouble();
					case "tracks" -> {
						in.beginArray();
						while (in.hasNext()) {
							Track t = new Track();
							in.beginObject();
							while (in.hasNext()) {
								switch (in.nextName()) {
									case "node" -> t.node = in.nextInt();
									case "rot" -> t.rot = readFloatRows(in);
									case "pos" -> t.pos = readFloatRows(in);
									case "scale" -> t.scale = readFloatRows(in);
									default -> in.skipValue();
								}
							}
							in.endObject();
							tracks.add(t);
						}
						in.endArray();
					}
					default -> in.skipValue();
				}
			}
			in.endObject();
			c.tracks = tracks.toArray(new Track[0]);
			c.byNode = new Track[nodeCount];
			for (Track t : c.tracks) c.byNode[t.node] = t;
			out.add(c);
		}
		in.endArray();
		return out.toArray(new Clip[0]);
	}

	// ---- lookups

	/** Index of the node whose path ends in "/leaf" (or equals it), or -1. */
	int findLeaf(String leaf) {
		for (int i = 0; i < nodes.length; i++) {
			String p = nodes[i].path;
			if (p.equals(leaf) || p.endsWith("/" + leaf)) return i;
		}
		return -1;
	}

	/** Index of the clip with this name, or -1. */
	int findClip(String name) {
		for (int i = 0; i < clips.length; i++) if (clips[i].name.equals(name)) return i;
		return -1;
	}

	/** World matrix of a node from the last skin() call (row-major, column vectors). */
	float[] worldMatrix(int node) {
		return world[node];
	}

	// ---- sampling and skinning

	/** Linear sample of a key track into out[0..dims). Quaternions are nlerp'd along the short arc. */
	private static void sample(float[][] keys, float t, int dims, float[] out, float[] fallback) {
		if (keys.length == 0) {
			if (fallback != out) System.arraycopy(fallback, 0, out, 0, dims);
			return;
		}
		if (t <= keys[0][0] || keys.length == 1) {
			System.arraycopy(keys[0], 1, out, 0, dims);
			return;
		}
		float[] last = keys[keys.length - 1];
		if (t >= last[0]) {
			System.arraycopy(last, 1, out, 0, dims);
			return;
		}
		int lo = 0, hi = keys.length - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (keys[mid][0] <= t) lo = mid; else hi = mid;
		}
		float[] a = keys[lo], b = keys[hi];
		float f = (t - a[0]) / Math.max(1e-6f, b[0] - a[0]);
		float sign = 1f;
		if (dims == 4) {
			float dot = a[1] * b[1] + a[2] * b[2] + a[3] * b[3] + a[4] * b[4];
			if (dot < 0) sign = -1f;
		}
		for (int d = 0; d < dims; d++) out[d] = a[1 + d] + (sign * b[1 + d] - a[1 + d]) * f;
		if (dims == 4) {
			float len = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]);
			if (len > 0) for (int d = 0; d < 4; d++) out[d] /= len;
		}
	}

	private static void mul(float[] a, float[] b, float[] o) {
		for (int r = 0; r < 4; r++) {
			for (int c = 0; c < 4; c++) {
				o[r * 4 + c] = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c] + a[r * 4 + 3] * b[12 + c];
			}
		}
	}

	private final float[] q = new float[4], p = new float[3], s = new float[3], local = new float[16];

	/** Pose of a clip at time t and skin all vertices into out (V*3). */
	void skin(Clip clip, float t, float[] out) {
		pose(clip, t, null, 0f, 0f);
		skinVertices(out);
	}

	/** Skin while fading out of another clip: rom at time tf has weight w, clip has 1 - w (an animation crossfade). */
	void skin(Clip clip, float t, Clip from, float tf, float w, float[] out) {
		pose(clip, t, from, tf, w);
		skinVertices(out);
	}

	/** Compute the world matrices of every node for a clip at time t (seconds, clamped to its length). */
	void pose(Clip clip, float t) {
		pose(clip, t, null, 0f, 0f);
	}

	private final float[] q2 = new float[4], p2 = new float[3], s2 = new float[3];

	void pose(Clip clip, float t, Clip from, float tf, float blend) {
		t = Math.max(0, Math.min(clip.length, t));
		tf = from == null ? 0f : Math.max(0, Math.min(from.length, tf));
		for (int i = 0; i < nodes.length; i++) {
			Track tr = clip.byNode[i];
			if (tr == null) {
				System.arraycopy(restRot[i], 0, q, 0, 4);
				System.arraycopy(restPos[i], 0, p, 0, 3);
				System.arraycopy(restScale[i], 0, s, 0, 3);
			} else {
				sample(tr.rot, t, 4, q, restRot[i]);
				sample(tr.pos, t, 3, p, restPos[i]);
				sample(tr.scale, t, 3, s, restScale[i]);
			}
			if (from != null && blend > 0f) {
				Track tf2 = from.byNode[i];
				if (tf2 == null) {
					System.arraycopy(restRot[i], 0, q2, 0, 4);
					System.arraycopy(restPos[i], 0, p2, 0, 3);
					System.arraycopy(restScale[i], 0, s2, 0, 3);
				} else {
					sample(tf2.rot, tf, 4, q2, restRot[i]);
					sample(tf2.pos, tf, 3, p2, restPos[i]);
					sample(tf2.scale, tf, 3, s2, restScale[i]);
				}
				float dot = q[0] * q2[0] + q[1] * q2[1] + q[2] * q2[2] + q[3] * q2[3];
				float sg = dot < 0 ? -1f : 1f;
				for (int d = 0; d < 4; d++) q[d] += (sg * q2[d] - q[d]) * blend;
				float ql = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
				if (ql > 0) for (int d = 0; d < 4; d++) q[d] /= ql;
				for (int d = 0; d < 3; d++) {
					p[d] += (p2[d] - p[d]) * blend;
					s[d] += (s2[d] - s[d]) * blend;
				}
			}
			float x = q[0], y = q[1], z = q[2], w = q[3];
			// local = T * R * S
			local[0] = (1 - 2 * (y * y + z * z)) * s[0]; local[1] = 2 * (x * y - z * w) * s[1]; local[2] = 2 * (x * z + y * w) * s[2]; local[3] = p[0];
			local[4] = 2 * (x * y + z * w) * s[0]; local[5] = (1 - 2 * (x * x + z * z)) * s[1]; local[6] = 2 * (y * z - x * w) * s[2]; local[7] = p[1];
			local[8] = 2 * (x * z - y * w) * s[0]; local[9] = 2 * (y * z + x * w) * s[1]; local[10] = (1 - 2 * (x * x + y * y)) * s[2]; local[11] = p[2];
			local[12] = 0; local[13] = 0; local[14] = 0; local[15] = 1;
			int parent = nodes[i].parent;
			if (parent < 0) {
				if (rootMatrix == null) System.arraycopy(local, 0, world[i], 0, 16);
				else mul(rootMatrix, local, world[i]);
			} else mul(world[parent], local, world[i]);
		}
	}

	/** Skin all vertices with the matrices of the last pose() call. */
	void skinVertices(float[] out) {
		for (int j = 0; j < bindPoses.length; j++) {
			mul(world[meshBones[j]], bindPoses[j], skinMats[j]);
		}
		for (int v = 0; v < vertexCount; v++) {
			float px = positions[v * 3], py = positions[v * 3 + 1], pz = positions[v * 3 + 2];
			float ox = 0, oy = 0, oz = 0, wsum = 0;
			for (int k = 0; k < 4; k++) {
				float wt = skinW[v * 4 + k];
				if (wt <= 0) continue;
				float[] m = skinMats[skinIdx[v * 4 + k]];
				ox += wt * (m[0] * px + m[1] * py + m[2] * pz + m[3]);
				oy += wt * (m[4] * px + m[5] * py + m[6] * pz + m[7]);
				oz += wt * (m[8] * px + m[9] * py + m[10] * pz + m[11]);
				wsum += wt;
			}
			if (wsum > 0) { ox /= wsum; oy /= wsum; oz /= wsum; }
			out[v * 3] = ox; out[v * 3 + 1] = oy; out[v * 3 + 2] = oz;
		}
	}
}
