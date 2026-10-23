package gg.der999.grimmmc;

/**
 * Ruby's move timelines, translated from the coroutines in Grimm Eclipse's `Ruby` class (decompiled for reference only).
 * Each `wait` in the original becomes an absolute time here. Pushes are in Grimm Eclipse units per second.
 * Only moves listed here exist; unknown coroutines return null and are ignored.
 */
final class Moves {
	private Moves() {}

	static Move build(CombatData.Ability a, boolean grounded) {
		String clip = a.clipPrimary;
		switch (a.coroutine) {
			// ---- ground light combo
			case "LightCombo_00":
				return new Move(a, clip)
					.at(0.06f, c -> c.push(20f))
					.at(0.14f, c -> { c.hit(0); c.stopPush(); })
					.end(0.14f);
			case "LightCombo_01":
				return new Move(a, clip)
					.at(0.06f, c -> c.push(20f))
					.at(0.11f, c -> { c.hit(0); c.stopPush(); })
					.end(0.11f);
			case "LightCombo_02":
				return new Move(a, clip)
					.at(0f, c -> c.push(15f))
					.at(0.10f, c -> c.hit(0))
					.at(0.20f, c -> { c.hit(0); c.stopPush(); })
					.at(0.30f, c -> c.hit(0))
					.end(0.30f);
			case "LightCombo_03":
				return new Move(a, clip)
					.at(0f, c -> c.push(8f))
					.at(0.15f, c -> { c.hit(0); c.push(-7f); })
					.at(0.25f, c -> { c.hit(1); c.stopPush(); })
					.at(0.35f, c -> { c.push(-4f); c.gravity(20f, 1f); })
					.end(0.35f);

			// ---- air light combo (Ruby hangs in the air while she attacks)
			case "LightComboAir_00":
				return new Move(a, clip)
					.at(0f, c -> c.gravity(15f, 0.1f))
					.at(0.05f, c -> c.push(8f))
					.at(0.175f, c -> { c.hit(0); c.push(4f); })
					.at(0.275f, c -> c.gravity(0f, 1f))
					.end(0.275f);
			case "LightComboAir_01":
				return new Move(a, clip)
					.at(0f, c -> { c.gravitySpeed(0.1f); c.push(8f); })
					.at(0.15f, c -> { c.push(4f); c.gravity(20f, 0.1f); c.hit(0); })
					.at(0.25f, c -> c.gravity(0f, 1f))
					.end(0.25f);
			case "LightComboAir_02":
				return new Move(a, clip)
					.at(0f, c -> { c.gravitySpeed(0.1f); c.push(8f); })
					.at(0.15f, c -> { c.push(4f); c.hit(0); c.gravity(25f, 0.1f); })
					.at(0.25f, c -> c.gravity(0f, 1f))
					.end(0.25f);
			case "LightComboAir_03":
				return new Move(a, clip)
					.at(0f, c -> { c.gravitySpeed(0.1f); c.push(8f); })
					.at(0.15f, c -> { c.push(4f); c.gravity(20f, 0.1f); c.hit(0); })
					.at(0.55f, c -> c.gravity(0f, 1f))
					.end(0.55f);

			// ---- dodge and the dash attack that follows it
			case "DodgeLand":
				return new Move(a, clip)
					.at(0f, c -> c.dodge(0.7f))
					.at(0.05f, c -> { c.setVisible(false); c.push(35f); })
					.at(0.15f, c -> { c.push(10f); c.setVisible(true); })
					.end(0.15f);
			case "DodgeAir":
				return new Move(a, clip)
					.at(0f, c -> { c.dodge(0.5f); c.push(45f); c.scalePush(45f, 5f, 0.3f); c.gravity(15f, 0.5f); })
					.at(0.05f, c -> c.setVisible(false))
					.at(0.15f, c -> c.setVisible(true))
					.end(0.15f);
			case "LightDodgeAttack":
				return new Move(a, clip)
					.at(0f, c -> c.push(70f))
					.at(0.05f, c -> { c.push(4f); c.hit(0); })
					.end(0.05f);
			// ---- heavy attacks (RMB). "Heavy" is a charge stub; heavy() builds the swing once the button is released.
			case "Heavy":
				return new Move(a, a.clipWindup).end(Float.MAX_VALUE);
			case "HeavyCombo_00":
				return new Move(a, clip)
					.at(0f, c -> { c.push(-5f); c.scalePush(-5f, 0f, 0.25f); })
					.at(0.25f, c -> { c.setVisible(false); c.push(55f); c.scalePush(55f, 0f, 0.1f); c.hit(0); })
					.at(0.35f, c -> { c.stopPush(); c.setVisible(true); })
					.end(0.35f);
			case "HeavyCombo_01":
				return new Move(a, clip)
					.at(0f, c -> { c.push(25f); c.scalePush(25f, 0f, 0.2f); })
					.at(0.10f, c -> c.hit(1))
					.at(0.35f, c -> { c.hit(0); c.push(-10f); c.scalePush(-10f, 0f, 0.2f); })
					.end(0.35f);
			case "HeavyCombo_02":
				return new Move(a, clip)
					.at(0f, c -> { c.push(-10f); c.scalePush(-10f, 5f, 0.1f); })
					.at(0.05f, c -> { c.push(15f); c.scalePush(15f, 0f, 0.2f); c.hit(0); })
					.at(0.20f, c -> c.hit(0))
					.at(0.25f, c -> c.stopPush())
					.at(0.60f, c -> { c.push(-5f); c.hit(1); c.gravity(20f, 1f); })
					.end(0.60f);
			case "HeavyComboFinal": {
				boolean[] pushed = {false};
				return new Move(a, clip)
					.at(0f, c -> { if (c.afterDodge()) { c.push(20f); pushed[0] = true; } })
					.at(0.33f, c -> { if (!pushed[0]) c.push(20f); })
					.at(0.41f, c -> { c.hit(0); c.stopPush(); })
					.end(0.41f);
			}
			case "HeavyComboAir":
				return new Move(a, clip)
					.at(0f, c -> { c.gravity(15f, 0.1f); c.push(4f); })
					.at(0.15f, c -> c.hit(0))
					.at(0.35f, c -> { c.push(2f); c.hit(1); })
					.at(0.65f, c -> c.gravity(-30f, 1f))
					.end(0.65f);

			// ---- ranged (Q): a hitscan stands in for Grimm Eclipse's bullet
			case "BasicRanged": {
				Move m = new Move(a, grounded || a.clipSecondary == null ? clip : a.clipSecondary);
				if (!grounded) m.at(0f, c -> c.gravity(20f, 0.3f));
				return m
					.at(0f, c -> c.push(-15f))
					.at(0.05f, c -> { c.push(-2f); c.shoot(0); })
					.end(0.40f);
			}
			case "FinalRanged":
				return new Move(a, clip)
					.at(0f, c -> { c.gravity(5f, 0f); c.push(-1f); })
					.at(0.45f, c -> { c.scalePush(-15f, -1f, 0.3f); c.gravity(20f, 0.1f); c.shootFinal(); })
					.end(0.45f);

			// ---- special (middle click): Crescendo, a spinning burst around Ruby
			case "Crescendo": {
				float charge = a.fullCharge;
				float t0 = 0.1f + charge;
				Move m = new Move(a, a.clipWindup)
					.at(0f, c -> { c.invuln(0.4f + charge); c.pushUp(5f, 1f, 0.3f); c.gravity(15f, 0.1f); })
					.at(t0, c -> { c.stopPushUp(); c.gravity(20f, 0.5f); c.setClip(a.clipPrimary); });
				if (charge <= 0f) {
					return m.at(t0 + 0.1f, c -> c.hit(0)).end(t0 + 0.2f);
				}
				return m.at(t0 + 0.1f, c -> c.hit(0)).at(t0 + 0.2f, c -> c.hit(0)).at(t0 + 0.3f, c -> c.hit(1)).end(t0 + 0.4f);
			}

			// ---- Reap (Ctrl, standing still): Grimm Eclipse's team attack, here as a solo finisher on one nearby enemy
			case "Reap":
				return new Move(a, a.clipLoopStart)
					.at(0f, c -> { c.invuln(1.4f); c.setVisible(false); c.warpToTarget(); })
					.at(0.05f, c -> { c.setVisible(true); c.gravity(25f, 0.3f); c.push(5f); c.setClip(a.clipLoop); })
					.at(0.10f, c -> c.strikeSlot(0))
					.at(0.30f, c -> c.strikeSlot(0))
					.at(0.50f, c -> c.strikeSlot(0))
					.at(0.70f, c -> { c.setClip(a.clipLoopEnd); c.gravitySpeed(1f); })
					.at(0.75f, c -> { c.gravity(15f, 1f); c.push(-15f); c.scalePush(-15f, 0f, 0.2f); c.smash(1); })
					.end(0.95f);

			// ---- counter (F)
			case "Counter":
				return new Move(a, clip)
					.at(0f, c -> { c.invuln(0.6f); c.setVisible(false); c.warpToTarget(); })
					.at(0.1f, c -> { c.setVisible(true); c.strikeTarget(); })
					.end(0.1f);
			case "FailedCounter":
				return new Move(a, clip).end(0.1f);
			default:
				return null;
		}
	}

	/** The part of the charged heavy attack that runs once the button is released. */
	static Move heavy(CombatData.Ability a, boolean charged) {
		if (charged) {
			return new Move(a, a.clipPrimary)
				.at(0f, c -> { c.push(20f); c.reorient(); })
				.at(0.2f, c -> c.stopPush())
				.at(0.3f, c -> { c.hit(0); c.breakAtCrosshair(); })
				.end(0.3f);
		}
		return new Move(a, a.clipSecondary)
			.at(0.4f, c -> { c.push(30f); c.scalePush(30f, -5f, 0.3f); c.reorient(); c.hit(1); })
			.end(0.4f);
	}
}