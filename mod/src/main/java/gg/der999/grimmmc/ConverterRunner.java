package gg.der999.grimmmc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs GrimmConvert in the background the first time (or when the player's game changed) and reports progress.
 * Protocol (stdout): "PROGRESS n/total text", "INFO text", "DONE folder", "UPTODATE", "ERROR code text".
 * Exit codes: 0 ok, 2 game not found, 3 asset missing / unknown build, 4 unsupported texture, 5 io, 10 (--check) not ready.
 */
final class ConverterRunner {
	enum State { NOT_STARTED, CHECKING, CONVERTING, READY, FAILED }

	record Status(State state, int step, int total, String text, long sinceMillis) {
		boolean busy() { return state == State.CHECKING || state == State.CONVERTING; }
	}

	private volatile Status status = new Status(State.NOT_STARTED, 0, 0, "", System.currentTimeMillis());

	Status status() { return status; }

	boolean isReady() { return status.state() == State.READY; }

	private void set(State s, int step, int total, String text) {
		status = new Status(s, step, total, text, System.currentTimeMillis());
	}

	/** Starts (or retries) in the background. A run already in progress is left alone. */
	synchronized void start(GrimmConfig cfg) {
		if (status.busy()) return;
		set(State.CHECKING, 0, 0, "Checking your Grimm Eclipse data");
		Thread t = new Thread(() -> run(cfg), "GrimmMC-converter");
		t.setDaemon(true);
		t.start();
	}

	private void run(GrimmConfig cfg) {
		try {
			if (cfg.converter == null || cfg.geDir == null) {
				// Development layout: data already placed in the data folder, no converter configured.
				if (Files.isRegularFile(cfg.dataDir.resolve("ruby_rig.json"))) {
					set(State.READY, 0, 0, "Ruby is ready");
				} else {
					set(State.FAILED, 0, 0, "Not set up: Melty has not configured Grimm Eclipse's location.");
				}
				return;
			}
			if (!Files.isRegularFile(cfg.converter)) {
				set(State.FAILED, 0, 0, "The data converter is missing. Reinstall the mashup from Melty.");
				return;
			}
			int check = exec(cfg, true);
			if (check == 0) {
				set(State.READY, 0, 0, "Ruby is ready");
				return;
			}
			if (check != 10) return; // exec already set a failure for any other code
			set(State.CONVERTING, 0, 6, "Preparing to convert your Grimm Eclipse data");
			int code = exec(cfg, false);
			if (code == 0 && status.state() != State.FAILED) {
				set(State.READY, 6, 6, "Ruby is ready");
			}
		} catch (Exception e) {
			GrimmMcClient.LOG.error("Converter failed", e);
			set(State.FAILED, 0, 0, "Could not run the converter: " + e.getMessage());
		}
	}

	/** Runs the converter once. Sets FAILED itself for any code other than 0 (and 10 in check mode). */
	private int exec(GrimmConfig cfg, boolean checkOnly) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(cfg.converter.toString());
		cmd.add("--ge");
		cmd.add(cfg.geDir);
		cmd.add("--out");
		cmd.add(cfg.dataDir.toString());
		if (checkOnly) cmd.add("--check");
		GrimmMcClient.LOG.info("Running {}", String.join(" ", cmd));

		Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String lastError = null;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				GrimmMcClient.LOG.info("[converter] {}", line);
				if (line.startsWith("PROGRESS ") && !checkOnly) {
					parseProgress(line.substring(9));
				} else if (line.startsWith("ERROR ")) {
					lastError = line.substring(6);
				}
			}
		}
		int code = proc.waitFor();
		if (code == 0 || (checkOnly && code == 10)) return code;

		String detail = lastError == null ? "" : lastError.replaceFirst("^\\d+ ", "");
		// The converter's own message is already written for players; add a short cause only where it helps.
		set(State.FAILED, 0, 0, switch (code) {
			case 2 -> detail.isBlank() ? "Grimm Eclipse was not found. Install RWBY: Grimm Eclipse from Steam, then restart." : detail;
			case 3 -> "This version of Grimm Eclipse is not supported yet. " + detail;
			case 4 -> "Part of your Grimm Eclipse data uses a format this mashup cannot read yet. " + detail;
			case 5 -> "Could not read or write files (disk full, or a protected folder?). " + detail;
			default -> "The converter failed (code " + code + "). " + detail;
		});
		return code;
	}

	private void parseProgress(String s) {
		// "n/total text"
		int sp = s.indexOf(' ');
		if (sp < 0) return;
		String[] frac = s.substring(0, sp).split("/");
		try {
			set(State.CONVERTING, Integer.parseInt(frac[0]), Integer.parseInt(frac[1]), s.substring(sp + 1));
		} catch (NumberFormatException ignored) {
			// keep the previous status
		}
	}
}
