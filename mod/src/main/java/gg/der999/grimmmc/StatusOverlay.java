package gg.der999.grimmmc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

/** A small panel at the top of the screen showing the data conversion: progress, errors, and "ready". */
final class StatusOverlay {
	private static final int PANEL_WIDTH = 300;
	/** How long the "ready" message stays after a conversion, in milliseconds. */
	private static final long READY_SHOW_MS = 6000;
	/** Do not flash the panel for the instant check that runs on every start. */
	private static final long CHECK_DELAY_MS = 800;

	private StatusOverlay() {}

	static void render(DrawContext ctx, MinecraftClient client, ConverterRunner.Status st) {
		long age = System.currentTimeMillis() - st.sinceMillis();
		TextRenderer tr = client.textRenderer;
		int cx = ctx.getScaledWindowWidth() / 2;
		int x = cx - PANEL_WIDTH / 2;
		int y = 6;

		switch (st.state()) {
			case CHECKING -> {
				if (age < CHECK_DELAY_MS) return;
				panel(ctx, tr, x, y, 0xC0000000, 0xFFFFFFFF, "Grimm Eclipse in Minecraft", st.text(), -1);
			}
			case CONVERTING -> {
				float frac = st.total() > 0 ? Math.max(0f, (st.step() - 1f) / st.total()) : 0f;
				panel(ctx, tr, x, y, 0xC0000000, 0xFFFFFFFF, "Grimm Eclipse in Minecraft: converting your game data (first start only)",
					(st.step() > 0 ? "(" + st.step() + "/" + st.total() + ") " : "") + st.text(), frac);
			}
			case FAILED -> panel(ctx, tr, x, y, 0xC0600000, 0xFFFFAAAA, "Grimm Eclipse in Minecraft: setup problem",
				st.text() + " Press O in a world to try again. Details are in logs/latest.log.", -1);
			case READY -> {
				// step == total only right after a real conversion; a quick "up to date" check stays silent.
				if (st.step() == st.total() && st.total() > 0 && age < READY_SHOW_MS) {
					panel(ctx, tr, x, y, 0xC0003000, 0xFFAAFFAA, "Grimm Eclipse in Minecraft",
						"Ruby is ready. In a world, press O to become Ruby.", -1);
				}
			}
			default -> { }
		}
	}

	private static void panel(DrawContext ctx, TextRenderer tr, int x, int y, int bg, int fg,
			String title, String body, float progress) {
		List<OrderedText> lines = tr.wrapLines(Text.literal(body), PANEL_WIDTH - 16);
		List<OrderedText> titleLines = tr.wrapLines(Text.literal(title), PANEL_WIDTH - 16);
		int lineH = tr.fontHeight + 2;
		int h = 8 + (titleLines.size() + lines.size()) * lineH + (progress >= 0 ? 14 : 4);
		ctx.fill(x, y, x + PANEL_WIDTH, y + h, bg);
		int ty = y + 6;
		for (OrderedText t : titleLines) {
			ctx.drawTextWithShadow(tr, t, x + 8, ty, 0xFFFFFF55);
			ty += lineH;
		}
		for (OrderedText t : lines) {
			ctx.drawTextWithShadow(tr, t, x + 8, ty, fg);
			ty += lineH;
		}
		if (progress >= 0) {
			int bx = x + 8, bw = PANEL_WIDTH - 16, by = ty + 2;
			ctx.fill(bx, by, bx + bw, by + 6, 0xFF303030);
			ctx.fill(bx, by, bx + Math.round(bw * Math.min(1f, progress)), by + 6, 0xFFE03030);
		}
	}
}
