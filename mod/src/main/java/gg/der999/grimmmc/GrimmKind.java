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
		new float[] {0.7f, 1.0f, 1.55f});

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
