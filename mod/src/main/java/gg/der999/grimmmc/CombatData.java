package gg.der999.grimmmc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Ruby's combat numbers, read from ruby_combat.json (converted from the player's own Grimm Eclipse AbilityData,
 * Attack and HitInfo assets). Units are Grimm Eclipse's: metres, seconds, and damage in its own scale.
 */
final class CombatData {
	static final class Hit {
		float damage, knockBack, knockUp, hitTime;
	}

	static final class Attack {
		boolean hasVolume;
		float radius;
		/** Offset of the hit sphere from Ruby's feet, in her local space (x right, y up, z forward). */
		final float[] offset = new float[3];
		Hit hit;
	}

	static final class Ability {
		String name, coroutine;
		float cooldown, comboWindow, fullCharge, maxCharge;
		int comboType, chargeType;
		boolean increaseCombo, resetCombo, uninterruptible;
		/** Animation clip names: windup, primary, secondary. */
		String clipWindup, clipPrimary, clipSecondary, clipLoopStart, clipLoop, clipLoopEnd;
		final Attack[] attacks = new Attack[3]; // primary, secondary, tertiary
	}

	final Map<String, Ability> abilities = new HashMap<>();
	/** Role name (LightCombo, HeavyBasic, DodgeLand...) to the ability names filling it, in order. */
	final Map<String, String[]> roles = new HashMap<>();

	static CombatData load(Path file) throws IOException {
		CombatData d = new CombatData();
		JsonObject root;
		try (Reader r = Files.newBufferedReader(file)) {
			root = JsonParser.parseReader(r).getAsJsonObject();
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("roles").entrySet()) {
			if (e.getValue().isJsonArray()) {
				JsonArray a = e.getValue().getAsJsonArray();
				String[] names = new String[a.size()];
				for (int i = 0; i < names.length; i++) names[i] = a.get(i).isJsonNull() ? null : a.get(i).getAsString();
				d.roles.put(e.getKey(), names);
			} else {
				d.roles.put(e.getKey(), new String[] {e.getValue().getAsString()});
			}
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("abilities").entrySet()) {
			JsonObject o = e.getValue().getAsJsonObject();
			Ability a = new Ability();
			a.name = e.getKey();
			a.coroutine = o.get("coroutine").getAsString();
			a.cooldown = o.get("cooldownSeconds").getAsFloat();
			a.comboWindow = o.get("waitForComboInputSeconds").getAsFloat();
			a.comboType = o.get("comboType").getAsInt();
			a.increaseCombo = o.get("increaseComboCounter").getAsBoolean();
			a.resetCombo = o.get("resetComboCounter").getAsBoolean();
			a.uninterruptible = o.get("uninterruptible").getAsBoolean();
			a.chargeType = o.get("chargeType").getAsInt();
			a.fullCharge = o.get("fullChargeTimeSeconds").getAsFloat();
			a.maxCharge = o.get("maxChargeTimeSeconds").getAsFloat();
			JsonObject an = o.getAsJsonObject("animations");
			a.clipWindup = str(an, "WindupAnimation");
			a.clipPrimary = str(an, "PrimaryAnimation");
			a.clipSecondary = str(an, "SecondaryAnimation");
			a.clipLoopStart = str(an, "LoopStartAnimation");
			a.clipLoop = str(an, "LoopAnimation");
			a.clipLoopEnd = str(an, "LoopEndAnimation");
			JsonObject at = o.getAsJsonObject("attacks");
			String[] slots = {"primary", "secondary", "tertiary"};
			for (int i = 0; i < 3; i++) {
				if (!at.has(slots[i])) continue;
				JsonObject ao = at.getAsJsonObject(slots[i]);
				Attack atk = new Attack();
				atk.hasVolume = ao.has("radius");
				if (atk.hasVolume) {
					atk.radius = ao.get("radius").getAsFloat();
					JsonArray off = ao.getAsJsonArray("offset");
					for (int k = 0; k < 3; k++) atk.offset[k] = off.get(k).getAsFloat();
				}
				if (ao.has("hit")) {
					JsonObject h = ao.getAsJsonObject("hit");
					Hit hit = new Hit();
					hit.damage = h.get("baseDamage").getAsFloat();
					hit.knockBack = h.get("knockBack").getAsFloat();
					hit.knockUp = h.get("knockUp").getAsFloat();
					hit.hitTime = h.get("hitTime").getAsFloat();
					atk.hit = hit;
				}
				a.attacks[i] = atk;
			}
			d.abilities.put(a.name, a);
		}
		return d;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsString() : null;
	}

	/** The ability filling role[index], or null. */
	Ability role(String role, int index) {
		String[] names = roles.get(role);
		if (names == null || index < 0 || index >= names.length || names[index] == null) return null;
		return abilities.get(names[index]);
	}

	int roleSize(String role) {
		String[] names = roles.get(role);
		return names == null ? 0 : names.length;
	}
}
