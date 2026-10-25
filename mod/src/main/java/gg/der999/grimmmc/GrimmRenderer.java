package gg.der999.grimmmc;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;

/** Draws a Grimm from its converted Grimm Eclipse model; which animation plays follows what the Grimm is doing. */
public class GrimmRenderer extends EntityRenderer<GrimmEntity, GrimmRenderer.State> {
	public static final class State extends EntityRenderState {
		GrimmKind kind = GrimmKind.BEOWOLF;
		int size;
		String clip = "Idle";
		float time;
		boolean loop = true;
		float bodyYaw;
		boolean hurt;
	}

	public GrimmRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		this.shadowRadius = 0.6f;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void updateRenderState(GrimmEntity e, State s, float delta) {
		super.updateRenderState(e, s, delta);
		s.kind = e.kind();
		s.size = e.size();
		s.bodyYaw = MathHelper.lerpAngleDegrees(delta, e.prevBodyYaw, e.bodyYaw);
		s.hurt = e.hurtTime > 0 || e.deathTime > 0;
		GrimmKind k = e.kind();
		int attack = e.attackTicks();
		double speed = e.getVelocity().horizontalLength();
		s.loop = true;
		if (e.isDead()) {
			s.clip = "Death";
			s.time = (e.deathTime + delta) / 20f;
			s.loop = false;
		} else if (attack >= 0) {
			s.clip = k.attackClips[e.attackVariant()];
			s.time = (attack + delta) / 20f;
			s.loop = false;
		} else if (e.hurtTime > 0) {
			s.clip = "HitLand01";
			s.time = (10 - e.hurtTime + delta) / 20f;
			s.loop = false;
		} else if (speed > 0.16) {
			s.clip = k.run;
			s.time = s.age / 20f;
		} else if (speed > 0.025) {
			s.clip = k.walk;
			s.time = s.age / 20f;
		} else {
			s.clip = k.idle;
			s.time = s.age / 20f;
		}
		String forced = System.getenv("GRIMMMC_FORCECLIP"); // self tests only
		if (GrimmMcClient.AUTO && forced != null && !forced.isBlank()) { s.clip = forced; s.time = s.age / 20f; s.loop = true; }
	}

	@Override
	public void render(State s, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		try {
			GrimmModels.draw(s.kind, s.size, s.clip, s.time, s.loop, s.bodyYaw, s.hurt, matrices, vertexConsumers, light);
		} catch (Exception e) {
			if (!failed) {
				failed = true;
				GrimmMcClient.LOG.error("Drawing a Grimm failed", e);
			}
		}
		super.render(s, matrices, vertexConsumers, light);
	}

	private static boolean failed;
}
