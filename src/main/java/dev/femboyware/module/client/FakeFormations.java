package dev.femboyware.module.client;

import dev.femboyware.setting.EnumSetting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The shapes fake players can stand in. Spots are measured in spacings from the middle of the
 * formation, sideways and ahead, so they can be scaled and turned to face any way. Saved shapes are
 * cells on the maker's grid, written as {@code name=right,forward right,forward|name=...}.
 */
public final class FakeFormations {
	public enum Shape {
		NONE, CIRCLE, LINE, COLUMN, WEDGE, GRID, TRIANGLE, DIAMOND, STAR, HEART, SPIRAL, TEXT, CUSTOM
	}

	private static final int LETTER_WIDTH = 5, LETTER_HEIGHT = 7;
	/** A 5x7 font: each letter is seven rows, two hex digits each, with a row's low five bits being its pixels. */
	private static final Map<Character, String> FONT = Map.ofEntries(
		Map.entry('A', "0E11111F111111"), Map.entry('B', "1E11111E11111E"), Map.entry('C', "0E11101010110E"), Map.entry('D', "1E11111111111E"),
		Map.entry('E', "1F10101E10101F"), Map.entry('F', "1F10101E101010"), Map.entry('G', "0E11101711110F"), Map.entry('H', "1111111F111111"),
		Map.entry('I', "0E04040404040E"), Map.entry('J', "0702020202120C"), Map.entry('K', "11121418141211"), Map.entry('L', "1010101010101F"),
		Map.entry('M', "111B1515111111"), Map.entry('N', "11191513111111"), Map.entry('O', "0E11111111110E"), Map.entry('P', "1E11111E101010"),
		Map.entry('Q', "0E11111115120D"), Map.entry('R', "1E11111E141211"), Map.entry('S', "0F10100E01011E"), Map.entry('T', "1F040404040404"),
		Map.entry('U', "1111111111110E"), Map.entry('V', "1111111111" + "0A04"), Map.entry('W', "1111111515" + "1B11"), Map.entry('X', "11110A040A1111"),
		Map.entry('Y', "11110A04040404"), Map.entry('Z', "1F01020408101F"),
		Map.entry('0', "0E11131519110E"), Map.entry('1', "040C040404040E"), Map.entry('2', "0E11010204081F"), Map.entry('3', "1E01010E01011E"),
		Map.entry('4', "02060A121F0202"), Map.entry('5', "1F101E0101110E"), Map.entry('6', "0608101E11110E"), Map.entry('7', "1F010204080808"),
		Map.entry('8', "0E11110E11110E"), Map.entry('9', "0E11110F01020C"),
		Map.entry('!', "04040404040004"), Map.entry('?', "0E110102040004"), Map.entry('.', "00000000000C0C"), Map.entry(',', "000000000C0408"),
		Map.entry('-', "0000001F000000"), Map.entry(':', "000C0C000C0C00"), Map.entry('\'', "04040800000000")
	);

	public record Spot(double right, double forward) {}

	public record Cell(int right, int forward) {}

	private FakeFormations() {
	}

	/** The named shape, or null if there's no such built-in one. CUSTOM and NONE don't count. */
	public static Shape builtIn(String name) {
		for (Shape shape : Shape.values()) {
			if (shape != Shape.NONE && shape != Shape.TEXT && shape != Shape.CUSTOM && shape.name().equalsIgnoreCase(name)) return shape;
		}
		return null;
	}

	public static String displayName(Shape shape) {
		return EnumSetting.prettify(shape.name());
	}

	/** A built-in shape with one spot for each of {@code count} fake players, about one spacing apart. */
	public static List<Spot> spots(Shape shape, int count) {
		List<Spot> spots = new ArrayList<>();
		switch (shape) {
			case CIRCLE -> {
				double radius = Math.max(1, count / (Math.PI * 2));
				for (int i = 0; i < count; i++) {
					double angle = Math.PI * 2 * i / count;
					spots.add(new Spot(Math.sin(angle) * radius, Math.cos(angle) * radius));
				}
			}
			case LINE -> {
				for (int i = 0; i < count; i++) spots.add(new Spot(i - (count - 1) / 2.0, 0));
			}
			case COLUMN -> {
				for (int i = 0; i < count; i++) spots.add(new Spot(0, (count - 1) / 2.0 - i));
			}
			case WEDGE -> {
				// Point first, then a pair further back on each side
				int ranks = (count + 1) / 2;
				for (int i = 0; i < count; i++) {
					int rank = (i + 1) / 2;
					spots.add(new Spot(i % 2 == 0 ? rank : -rank, ranks / 2.0 - rank));
				}
			}
			case GRID -> {
				int columns = (int) Math.ceil(Math.sqrt(count));
				int rows = (int) Math.ceil(count / (double) columns);
				for (int i = 0; i < count; i++) {
					spots.add(new Spot(i % columns - (columns - 1) / 2.0, (rows - 1) / 2.0 - i / columns));
				}
			}
			case TRIANGLE -> spots = outline(new double[][]{{0, 1}, {0.87, -0.5}, {-0.87, -0.5}}, count);
			case DIAMOND -> spots = outline(new double[][]{{0, 1}, {0.7, 0}, {0, -1}, {-0.7, 0}}, count);
			case STAR -> {
				double[][] corners = new double[10][];
				for (int i = 0; i < 10; i++) {
					double angle = Math.PI * i / 5, reach = i % 2 == 0 ? 1 : 0.42;
					corners[i] = new double[]{Math.sin(angle) * reach, Math.cos(angle) * reach};
				}
				spots = outline(corners, count);
			}
			case HEART -> {
				double[][] edge = new double[64][];
				for (int i = 0; i < 64; i++) {
					double t = Math.PI * 2 * i / 64, sin = Math.sin(t);
					edge[i] = new double[]{16 * sin * sin * sin, 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)};
				}
				spots = outline(edge, count);
			}
			case SPIRAL -> {
				double perRadian = 1.2 / (Math.PI * 2);
				for (int i = 0; i < count; i++) {
					double angle = Math.sqrt(2 * (i + 0.5) / perRadian), radius = perRadian * angle;
					spots.add(new Spot(Math.sin(angle) * radius, Math.cos(angle) * radius));
				}
			}
			default -> {
			}
		}
		return spots;
	}

	/** Evenly spaced spots around a closed shape, scaled so neighbours are about one spacing apart. */
	private static List<Spot> outline(double[][] corners, int count) {
		double perimeter = 0;
		for (int i = 0; i < corners.length; i++) perimeter += edgeLength(corners, i);
		double scale = count / perimeter, along = 0;
		int edge = 0;
		List<Spot> spots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			double wanted = perimeter * i / count;
			while (along + edgeLength(corners, edge) < wanted) along += edgeLength(corners, edge++);
			double[] from = corners[edge], to = corners[(edge + 1) % corners.length];
			double t = (wanted - along) / edgeLength(corners, edge);
			spots.add(new Spot((from[0] + (to[0] - from[0]) * t) * scale, (from[1] + (to[1] - from[1]) * t) * scale));
		}
		return spots;
	}

	private static double edgeLength(double[][] corners, int i) {
		double[] from = corners[i], to = corners[(i + 1) % corners.length];
		return Math.hypot(to[0] - from[0], to[1] - from[1]);
	}

	/** The cells that spell {@code text}, centered, read from behind the formation. A / starts a new line, and characters the font doesn't have leave a gap. */
	static Set<Cell> textCells(String text) {
		Set<Cell> cells = new LinkedHashSet<>();
		String[] lines = text.toUpperCase(Locale.ROOT).split("/");
		int lineGap = LETTER_HEIGHT + 2;
		int top = (lines.length * lineGap - 2) / 2;
		for (int line = 0; line < lines.length; line++) {
			String letters = lines[line].trim();
			int left = -(letters.length() * (LETTER_WIDTH + 1) - 1) / 2;
			for (int i = 0; i < letters.length(); i++) {
				String glyph = FONT.get(letters.charAt(i));
				if (glyph == null) continue;
				for (int row = 0; row < LETTER_HEIGHT; row++) {
					int pixels = Integer.parseInt(glyph.substring(row * 2, row * 2 + 2), 16);
					for (int column = 0; column < LETTER_WIDTH; column++) {
						if ((pixels >> (LETTER_WIDTH - 1 - column) & 1) != 0) {
							cells.add(new Cell(left + i * (LETTER_WIDTH + 1) + column, top - line * lineGap - row));
						}
					}
				}
			}
		}
		return cells;
	}

	/** A shape's spots rounded onto the maker's grid. */
	public static Set<Cell> cells(List<Spot> spots) {
		Set<Cell> cells = new LinkedHashSet<>();
		for (Spot spot : spots) cells.add(new Cell((int) Math.round(spot.right()), (int) Math.round(spot.forward())));
		return cells;
	}

	/** One spot for each cell, or an even selection of them when there aren't enough fake players to fill them all. */
	static List<Spot> spots(Set<Cell> cells, int count) {
		List<Cell> all = new ArrayList<>(cells);
		List<Spot> spots = new ArrayList<>();
		int used = Math.min(count, all.size());
		for (int i = 0; i < used; i++) {
			Cell cell = all.get(i * all.size() / used);
			spots.add(new Spot(cell.right(), cell.forward()));
		}
		return spots;
	}

	static Map<String, Set<Cell>> decode(String saved) {
		Map<String, Set<Cell>> formations = new LinkedHashMap<>();
		for (String entry : saved.split("\\|")) {
			int equals = entry.indexOf('=');
			if (equals <= 0) continue;
			Set<Cell> cells = new LinkedHashSet<>();
			for (String pair : entry.substring(equals + 1).trim().split("\\s+")) {
				String[] parts = pair.split(",");
				if (parts.length != 2) continue;
				try {
					cells.add(new Cell(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
				} catch (NumberFormatException ignored) {
					// Someone edited the config by hand; keep the rest of the shape
				}
			}
			if (!cells.isEmpty()) formations.put(entry.substring(0, equals), cells);
		}
		return formations;
	}

	static String encode(Map<String, Set<Cell>> formations) {
		List<String> entries = new ArrayList<>();
		for (Map.Entry<String, Set<Cell>> entry : formations.entrySet()) {
			List<String> pairs = new ArrayList<>();
			for (Cell cell : entry.getValue()) pairs.add(cell.right() + "," + cell.forward());
			entries.add(entry.getKey() + "=" + String.join(" ", pairs));
		}
		return String.join("|", entries);
	}
}
