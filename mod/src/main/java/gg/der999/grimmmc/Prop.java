package gg.der999.grimmmc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Unskinned meshes (Crescent Rose) that ride on a skeleton node of the animation clip. */
final class Prop {
	static final class Part {
		String name;
		int[] subs;      // [first, count] of the first submesh
		int[] indices;
		float[] positions;
		float[] uvs;
		int node = -1;
	}

	final Part[] parts;

	private Prop(Part[] parts) {
		this.parts = parts;
	}

	static Prop load(Path file, Rig rig) throws IOException {
		try (Reader r = Files.newBufferedReader(file)) {
			JsonArray arr = new Gson().fromJson(r, JsonObject.class).getAsJsonArray("parts");
			Part[] parts = new Part[arr.size()];
			for (int i = 0; i < parts.length; i++) {
				JsonObject o = arr.get(i).getAsJsonObject();
				Part p = new Part();
				p.name = o.get("name").getAsString();
				JsonArray s = o.getAsJsonArray("subs").get(0).getAsJsonArray();
				p.subs = new int[] {s.get(0).getAsInt(), s.get(1).getAsInt()};
				p.indices = ints(o.getAsJsonArray("indices"));
				p.positions = floats(o.getAsJsonArray("pos"));
				p.uvs = floats(o.getAsJsonArray("uv"));
				p.node = rig.findLeaf(p.name);
				parts[i] = p;
			}
			return new Prop(parts);
		}
	}

	private static int[] ints(JsonArray a) {
		int[] r = new int[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
		return r;
	}

	private static float[] floats(JsonArray a) {
		float[] r = new float[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsFloat();
		return r;
	}
}
