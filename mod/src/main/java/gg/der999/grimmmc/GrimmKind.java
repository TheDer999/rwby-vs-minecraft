package gg.der999.grimmmc;

/**
 * The Grimm the mod adds, each in three sizes (small, medium, large). Their model and animations come from the player's own Grimm
 * Eclipse install (beowolf_rig.json, creep_rig.json...); how they fight and dig is this mod's own design, tuned for Minecraft.
 */
enum GrimmKind {
	// id, idle, walk, run clips; attacks {clip, seconds the clip lasts, seconds into it the blow lands}
	BEOWOLF("beowolf", "Idle", "Walk", "Run",
		new String[] {"Spin Slash", "Joust"}, new float[] {0.97f, 1.6f}, new float[] {0.45f, 0.85f},
		// per size: width, height, health, damage, speed, digging depth, model scale
		new float[] {1.0f, 1.5f, 2.2f}, new float[] {1.7f, 2.6f, 4.0f},
		new double[] {30, 70, 160}, new double[] {2.5, 4, 7}, new double[] {0.30, 0.31, 0.33}, new int[] {1, 2, 4},
		new float[] {1.9f, 2.85f, 4.4f}),
	CREEP("creep", "Idle", "Walk", "Run",
		new String[] {"Flip"}, new float[] {0.77f}, new float[] {0.40f},
		new float[] {0.45f, 0.7f, 1.1f}, new float[] {0.45f, 0.65f, 1.0f},
		new double[] {20, 45, 100}, new double[] {2, 3.5, 6}, new double[] {0.34, 0.33, 0.32}, new int[] {1, 2, 4},
		new float[] {0.7f, 1.0f, 1.55f}),
	URSA("ursa", "Idle", "Run", "Run",
		new String[] {"Smash"}, new float[] {1.83f}, new float[] {0.9f},
		new float[] {1.3f, 1.9f, 2.6f}, new float[] {2.1f, 3.2f, 4.6f},
		new double[] {40, 90, 200}, new double[] {3.5, 6, 10}, new double[] {0.24, 0.25, 0.26}, new int[] {2, 3, 4},
		new float[] {0.45f, 0.7f, 1.0f}),
	BOARBATUSK("boar", "Idle", "Walk", "Run",
		new String[] {"Tackle", "SpinHit"}, new float[] {0.58f, 0.65f}, new float[] {0.3f, 0.3f},
		new float[] {1.0f, 1.6f, 2.4f}, new float[] {1.3f, 2.0f, 3.2f},
		new double[] {24, 55, 120}, new double[] {2.5, 4.5, 8}, new double[] {0.36, 0.35, 0.34}, new int[] {1, 2, 3},
		new float[] {1.0f, 1.6f, 2.6f}),
	DEATHSTALKER("deathstalker", "Idle", "Walk", "Run",
		new String[] {"Swipe", "Smash"}, new float[] {1.83f, 1.17f}, new float[] {0.9f, 0.6f},
		new float[] {1.2f, 1.8f, 2.6f}, new float[] {1.1f, 1.7f, 2.6f},
		new double[] {30, 70, 160}, new double[] {3, 5.5, 9}, new double[] {0.30, 0.30, 0.30}, new int[] {2, 3, 4},
		new float[] {0.18f, 0.28f, 0.45f}),
	// The mutants: rare, night-only and very destructive (see GrimmMcMain.spawnTick and GrimmEntity.dig).
	MUTANT_BEOWOLF("mutant_beowolf", "Idle", "Walk", "Run",
		new String[] {"GroundPound", "UppercutRight", "Frenzy"}, new float[] {2.08f, 1.33f, 1.83f}, new float[] {1.2f, 0.6f, 0.9f},
		new float[] {1.6f, 2.4f, 3.4f}, new float[] {2.2f, 3.4f, 5.2f},
		new double[] {120, 260, 520}, new double[] {5, 9, 16}, new double[] {0.30, 0.30, 0.30}, new int[] {4, 6, 9},
		new float[] {1.0f, 1.5f, 2.3f}),
	MUTANT_CREEP("mutant_creep", "Idle", "Walk", "Run",
		new String[] {"Explode"}, new float[] {1.58f}, new float[] {1.4f},
		new float[] {0.8f, 1.1f, 1.6f}, new float[] {1.8f, 2.7f, 4.2f},
		new double[] {40, 90, 200}, new double[] {3, 5, 8}, new double[] {0.34, 0.34, 0.34}, new int[] {3, 4, 6},
		new float[] {0.6f, 0.9f, 1.4f}),
	MUTANT_DEATHSTALKER("mutant_deathstalker", "Idle", "Walk", "Run",
		new String[] {"Swipe", "Smash", "Lunge"}, new float[] {1.83f, 1.17f, 1.83f}, new float[] {0.9f, 0.6f, 0.9f},
		new float[] {1.6f, 2.4f, 3.6f}, new float[] {1.4f, 2.1f, 3.4f},
		new double[] {100, 220, 450}, new double[] {5, 9, 15}, new double[] {0.30, 0.30, 0.30}, new int[] {4, 6, 9},
		new float[] {0.22f, 0.34f, 0.55f});

	boolean mutant() {
		return this == MUTANT_BEOWOLF || this == MUTANT_CREEP || this == MUTANT_DEATHSTALKER;
	}

	/** The kinds that spawn in the ordinary way (everything but the mutants). */
	static final GrimmKind[] REGULAR = {BEOWOLF, CREEP, URSA, BOARBATUSK, DEATHSTALKER};

	/** Seconds the Death animation takes, so the body is removed when it has played. */
	float deathSeconds() {
		return switch (this) {
			case URSA -> 1.9f;
			case BOARBATUSK -> 1.5f;
			case DEATHSTALKER, MUTANT_DEATHSTALKER -> 3.33f;
			case MUTANT_BEOWOLF -> 2.0f;
			case MUTANT_CREEP -> 1.5f;
			default -> 2.33f;
		};
	}

	/** Prefix of the converted sound this Grimm snarls with when it attacks. */
	String voice() {
		return switch (this) {
			case BEOWOLF -> "bark_";
			case CREEP -> "creep_grunt_";
			case URSA -> "roar_";
			case BOARBATUSK -> "boar_spin";
			case DEATHSTALKER, MUTANT_DEATHSTALKER -> "deathstalker_laugh";
			case MUTANT_BEOWOLF -> "roar_";
			case MUTANT_CREEP -> "creep_roar_";
		};
	}

	static final String[] SIZE_NAMES = {"small", "medium", "large"};

	final String id;
	final String idle, walk, run;
	final String[] attackClips;
	final float[] attackSeconds, attackHitSeconds;
	final float[] width, height;
	final double[] health, damage, speed;
	final int[] dig;
	final float[] scale;

	GrimmKind(String id, String idle, String walk, String run, String[] attackClips, float[] attackSeconds, float[] attackHitSeconds,
			float[] width, float[] height, double[] health, double[] damage, double[] speed, int[] dig, float[] scale) {
		this.id = id;
		this.idle = idle;
		this.walk = walk;
		this.run = run;
		this.attackClips = attackClips;
		this.attackSeconds = attackSeconds;
		this.attackHitSeconds = attackHitSeconds;
		this.width = width;
		this.height = height;
		this.health = health;
		this.damage = damage;
		this.speed = speed;
		this.dig = dig;
		this.scale = scale;
	}

	String entityName(int size) {
		return id + "_" + SIZE_NAMES[size];
	}
}
