package dev.femboyware.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * Windows' own Open dialog, on top of the game: run off the render thread so the game keeps
 * drawing, and the file you pick handed back on the main thread. Elsewhere it says to type a name.
 */
public final class FilePicker {
	public static final List<String> PICTURES = List.of("png", "jpg", "jpeg");
	private static final AtomicBoolean OPEN = new AtomicBoolean();

	private FilePicker() {
	}

	public static boolean supported() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/**
	 * Asks for a file. {@code kind} names them in the dialog's filter ("Pictures"), {@code extensions}
	 * are without dots; {@code picked} runs on the main thread, only if a file was chosen.
	 */
	public static void pick(String title, String kind, List<String> extensions, Consumer<Path> picked) {
		if (!supported()) {
			ChatUtil.info("Choosing a file only works on Windows; type its name instead");
			return;
		}
		// One dialog at a time: a second click while it's open does nothing
		if (!OPEN.compareAndSet(false, true)) return;
		StringBuilder patterns = new StringBuilder();
		for (String ext : extensions) {
			if (!patterns.isEmpty()) patterns.append(';');
			patterns.append("*.").append(ext.replaceAll("[^A-Za-z0-9]", ""));
		}
		String filter = quote(kind + " (" + patterns + ")|" + patterns + "|All files (*.*)|*.*");
		String script = "Add-Type -AssemblyName System.Windows.Forms;"
			+ "$owner = New-Object System.Windows.Forms.Form -Property @{TopMost = $true};"
			+ "$dialog = New-Object System.Windows.Forms.OpenFileDialog -Property @{Filter = '" + filter + "'; Title = '" + quote(title) + "'};"
			+ "if ($dialog.ShowDialog($owner) -eq 'OK') { [Console]::Out.Write($dialog.FileName) }";
		CompletableFuture.supplyAsync(() -> {
			try {
				Process process = new ProcessBuilder("powershell", "-NoProfile", "-STA", "-Command", script).redirectErrorStream(false).start();
				process.getOutputStream().close();
				String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				process.waitFor();
				return out;
			} catch (Exception e) {
				return "";
			}
		}).thenAccept(out -> Minecraft.getInstance().execute(() -> {
			OPEN.set(false);
			if (out.isEmpty()) return;
			Path path = Path.of(out);
			if (Files.isRegularFile(path)) picked.accept(path);
		}));
	}

	/** Windows' Save As dialog, for a {@code .extension} file named {@code suggested} to start with. */
	public static void saveAs(String title, String kind, String extension, String suggested, Consumer<Path> chosen) {
		if (!supported()) {
			ChatUtil.info("Choosing where to save only works on Windows");
			return;
		}
		if (!OPEN.compareAndSet(false, true)) return;
		String ext = extension.replaceAll("[^A-Za-z0-9]", "");
		String script = "Add-Type -AssemblyName System.Windows.Forms;"
			+ "$owner = New-Object System.Windows.Forms.Form -Property @{TopMost = $true};"
			+ "$dialog = New-Object System.Windows.Forms.SaveFileDialog -Property @{Filter = '" + quote(kind + " (*." + ext + ")|*." + ext) + "'; Title = '" + quote(title)
			+ "'; FileName = '" + quote(suggested) + "'; DefaultExt = '" + ext + "'; AddExtension = $true; OverwritePrompt = $true};"
			+ "if ($dialog.ShowDialog($owner) -eq 'OK') { [Console]::Out.Write($dialog.FileName) }";
		run(script, out -> {
			if (!out.isEmpty()) chosen.accept(Path.of(out));
		});
	}

	private static void run(String script, Consumer<String> done) {
		CompletableFuture.supplyAsync(() -> {
			try {
				Process process = new ProcessBuilder("powershell", "-NoProfile", "-STA", "-Command", script).redirectErrorStream(false).start();
				process.getOutputStream().close();
				String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				process.waitFor();
				return out;
			} catch (Exception e) {
				return "";
			}
		}).thenAccept(out -> Minecraft.getInstance().execute(() -> {
			OPEN.set(false);
			done.accept(out);
		}));
	}

	/**
	 * Copies a picked file into one of femboyware's folders (under {@code name}, or its own name),
	 * returning the copy, or null with a message if it couldn't.
	 */
	public static Path copyInto(Path file, Path folder, String name) {
		try {
			Files.createDirectories(folder);
			Path target = folder.resolve(name == null ? file.getFileName().toString() : name);
			if (!target.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
				Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return target;
		} catch (Exception e) {
			ChatUtil.info("Couldn't copy " + file.getFileName() + " (" + e.getMessage() + ")");
			return null;
		}
	}

	/** Safe inside a PowerShell single-quoted string. */
	private static String quote(String text) {
		return text.replace("'", "''").replaceAll("[\\r\\n]", " ");
	}
}
