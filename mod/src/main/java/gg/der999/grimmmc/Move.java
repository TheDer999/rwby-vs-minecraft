package gg.der999.grimmmc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * One ability's timeline: an animation clip plus timed events (pushes, hits, gravity changes), ported from the
 * ability coroutines in Grimm Eclipse's Ruby class. Times are seconds from the start of the move.
 */
final class Move {
	static final class Event {
		final float time;
		final Consumer<CombatController> action;

		Event(float time, Consumer<CombatController> action) {
			this.time = time;
			this.action = action;
		}
	}

	final CombatData.Ability ability;
	/** Name of the animation clip to play. */
	final String clip;
	final List<Event> events = new ArrayList<>();
	/** When the ability coroutine ends (the last wait); the cooldown and combo window follow. */
	float duration;

	Move(CombatData.Ability ability, String clip) {
		this.ability = ability;
		this.clip = clip;
	}

	Move at(float time, Consumer<CombatController> action) {
		events.add(new Event(time, action));
		return this;
	}

	Move end(float duration) {
		this.duration = duration;
		events.sort(Comparator.comparingDouble(e -> e.time));
		return this;
	}
}
