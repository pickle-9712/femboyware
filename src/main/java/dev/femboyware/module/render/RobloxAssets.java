package dev.femboyware.module.render;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import net.fabricmc.loader.api.FabricLoader;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Reads Roblox accessories: downloads an asset by its id (cached on disk), reads the accessory out
 * of its model file (the XML kind or the binary .rbxm kind), and reads its mesh. Everything here
 * runs off the render thread.
 */
final class RobloxAssets {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final Path CACHE = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("roblox");
	private static final Pattern ID = Pattern.compile("(?:id=|rbxassetid://|/catalog/|/asset/)(\\d+)|^\\s*(\\d+)\\s*$");

	private RobloxAssets() {
	}

	/** The asset's bytes: from the disk cache, or downloaded from Roblox and cached. */
	static byte[] download(long id) throws IOException, InterruptedException {
		Path file = CACHE.resolve(id + ".bin");
		if (Files.isRegularFile(file)) return Files.readAllBytes(file);
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://assetdelivery.roblox.com/v1/asset/?id=" + id))
			.header("User-Agent", "Mozilla/5.0").timeout(Duration.ofSeconds(20)).GET().build();
		HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) throw new IOException("Roblox said " + response.statusCode() + " for asset " + id);
		byte[] body = response.body();
		Files.createDirectories(CACHE);
		Files.write(file, body);
		return body;
	}

	/** The asset id in a Roblox content link like rbxassetid://123 or .../asset/?id=123, or 0. */
	static long idIn(String link) {
		if (link == null) return 0;
		Matcher m = ID.matcher(link);
		if (!m.find()) return 0;
		return Long.parseLong(m.group(1) != null ? m.group(1) : m.group(2));
	}

	// ---------------------------------------------------------------- the accessory

	/** A position and rotation (row-major 3x3, like Roblox's R00..R22). */
	record Frame(float[] pos, float[] rot) {
		static final Frame IDENTITY = new Frame(new float[3], new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});

		/** This frame's inverse applied to {@code p}: rotᵀ (p - pos). */
		float[] toLocal(float[] p) {
			float x = p[0] - pos[0], y = p[1] - pos[1], z = p[2] - pos[2];
			return new float[]{rot[0] * x + rot[3] * y + rot[6] * z, rot[1] * x + rot[4] * y + rot[7] * z, rot[2] * x + rot[5] * y + rot[8] * z};
		}

		/** The inverse's rotation applied to a direction. */
		float[] toLocalDirection(float[] d) {
			return new float[]{rot[0] * d[0] + rot[3] * d[1] + rot[6] * d[2], rot[1] * d[0] + rot[4] * d[1] + rot[7] * d[2], rot[2] * d[0] + rot[5] * d[1] + rot[8] * d[2]};
		}
	}

	/**
	 * What's needed to put an accessory on a head: its mesh and texture, how the mesh is scaled and
	 * offset in its handle, and where the handle attaches (which head attachment, and the handle's
	 * own attachment frame).
	 */
	record Accessory(long mesh, long texture, float[] scale, float[] offset, boolean meshPart, float[] size, float[] initialSize, String attachment, Frame frame) {
	}

	/** One instance read from a model file: its class, name, parent and the properties a hat needs. */
	private static final class Inst {
		String className = "", name = "";
		int parent = -1;
		final Map<String, Object> props = new HashMap<>();
	}

	/** Reads the accessory out of a model file, or null if it isn't one we can wear. */
	static Accessory accessory(byte[] data) throws IOException {
		Map<Integer, Inst> insts;
		if (startsWith(data, "<roblox!")) insts = readBinary(data);
		else if (startsWith(data, "<roblox")) insts = readXml(data);
		else return null;
		// The handle's mesh: a SpecialMesh inside it, or the handle itself being a MeshPart
		Inst mesh = null, handle = null;
		for (Inst i : insts.values()) {
			if (i.className.equals("SpecialMesh") || i.className.equals("FileMesh")) mesh = mesh == null || i.name.equals("Mesh") ? i : mesh;
			if (i.name.equals("Handle") && (i.className.equals("Part") || i.className.equals("MeshPart"))) handle = i;
		}
		if (handle == null && mesh == null) {
			for (Inst i : insts.values()) if (i.className.equals("MeshPart")) handle = i;
		}
		boolean meshPart = mesh == null && handle != null && handle.className.equals("MeshPart");
		Inst source = mesh != null ? mesh : handle;
		if (source == null) return null;
		long meshId = idIn(str(source, "MeshId", "MeshID"));
		long textureId = idIn(str(source, "TextureId", "TextureID"));
		if (meshId == 0) return null;
		float[] scale = vec(source, "Scale", new float[]{1, 1, 1});
		float[] offset = vec(source, "Offset", new float[3]);
		float[] size = handle != null ? vec(handle, "size", vec(handle, "Size", null)) : null;
		float[] initial = handle != null ? vec(handle, "InitialSize", null) : null;
		// Where it attaches: an attachment in the handle named for a head attachment, else the old AttachmentPoint
		String attachment = "HatAttachment";
		Frame frame = null;
		for (Map.Entry<Integer, Inst> e : insts.entrySet()) {
			Inst i = e.getValue();
			if (!i.className.equals("Attachment") || handle == null || insts.get(i.parent) != handle) continue;
			if (i.props.get("CFrame") instanceof Frame f) {
				attachment = i.name;
				frame = f;
				if (i.name.equals("HatAttachment") || i.name.equals("HairAttachment") || i.name.startsWith("Face")) break;
			}
		}
		if (frame == null) {
			for (Inst i : insts.values()) {
				if ((i.className.equals("Accessory") || i.className.equals("Hat")) && i.props.get("AttachmentPoint") instanceof Frame f) {
					frame = f;
					attachment = "Legacy";
				}
			}
		}
		if (frame == null) frame = Frame.IDENTITY;
		return new Accessory(meshId, textureId, scale, offset, meshPart, size, initial, attachment, frame);
	}

	private static String str(Inst i, String... names) {
		for (String n : names) if (i.props.get(n) instanceof String s && !s.isBlank()) return s;
		return null;
	}

	private static float[] vec(Inst i, String name, float[] fallback) {
		return i.props.get(name) instanceof float[] v ? v : fallback;
	}

	private static boolean startsWith(byte[] data, String text) {
		byte[] t = text.getBytes(StandardCharsets.US_ASCII);
		if (data.length < t.length) return false;
		for (int k = 0; k < t.length; k++) if (data[k] != t[k]) return false;
		return true;
	}

	// ---------------------------------------------------------------- XML models

	private static Map<Integer, Inst> readXml(byte[] data) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(data)).getDocumentElement();
			Map<Integer, Inst> insts = new HashMap<>();
			readXmlItems(root, -1, insts);
			return insts;
		} catch (Exception e) {
			throw new IOException("Couldn't read the model", e);
		}
	}

	private static void readXmlItems(Element parent, int parentId, Map<Integer, Inst> insts) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (!(n instanceof Element item) || !item.getTagName().equals("Item")) continue;
			Inst inst = new Inst();
			int id = insts.size();
			insts.put(id, inst);
			inst.className = item.getAttribute("class");
			inst.parent = parentId;
			for (Node p = item.getFirstChild(); p != null; p = p.getNextSibling()) {
				if (!(p instanceof Element props) || !props.getTagName().equals("Properties")) continue;
				for (Node q = props.getFirstChild(); q != null; q = q.getNextSibling()) {
					if (!(q instanceof Element prop)) continue;
					String name = prop.getAttribute("name");
					switch (prop.getTagName()) {
						case "string" -> {
							if (name.equals("Name")) inst.name = prop.getTextContent();
						}
						case "Content" -> inst.props.put(name, prop.getTextContent().trim());
						case "Vector3" -> inst.props.put(name, new float[]{num(prop, "X"), num(prop, "Y"), num(prop, "Z")});
						case "CoordinateFrame", "CFrame" -> inst.props.put(name, new Frame(new float[]{num(prop, "X"), num(prop, "Y"), num(prop, "Z")},
							new float[]{num(prop, "R00"), num(prop, "R01"), num(prop, "R02"), num(prop, "R10"), num(prop, "R11"), num(prop, "R12"), num(prop, "R20"), num(prop, "R21"), num(prop, "R22")}));
						default -> {
						}
					}
				}
			}
			readXmlItems(item, id, insts);
		}
	}

	private static float num(Element e, String tag) {
		var list = e.getElementsByTagName(tag);
		if (list.getLength() == 0) return 0;
		try {
			return Float.parseFloat(list.item(0).getTextContent().trim());
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	// ---------------------------------------------------------------- binary models

	private static Map<Integer, Inst> readBinary(byte[] data) throws IOException {
		ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		in.position(32);
		Map<Integer, String> classNames = new HashMap<>();
		Map<Integer, int[]> classInsts = new HashMap<>();
		Map<Integer, Inst> insts = new HashMap<>();
		while (in.remaining() >= 16) {
			byte[] tag = new byte[4];
			in.get(tag);
			int compressed = in.getInt(), size = in.getInt();
			in.getInt();
			byte[] raw = new byte[compressed != 0 ? compressed : size];
			in.get(raw);
			String chunk = new String(tag, StandardCharsets.US_ASCII);
			if (chunk.startsWith("END")) break;
			byte[] body = compressed != 0 ? decompress(raw, size) : raw;
			ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
			switch (chunk) {
				case "INST" -> {
					int classId = b.getInt();
					String className = string(b);
					b.get();
					int count = b.getInt();
					int[] refs = referents(b, count);
					classNames.put(classId, className);
					classInsts.put(classId, refs);
					for (int ref : refs) {
						Inst inst = new Inst();
						inst.className = className;
						insts.put(ref, inst);
					}
				}
				case "PROP" -> {
					int classId = b.getInt();
					String name = string(b);
					int type = b.get() & 0xFF;
					int[] refs = classInsts.get(classId);
					if (refs == null) break;
					int n = refs.length;
					switch (type) {
						case 0x01 -> {
							for (int ref : refs) {
								String value = string(b);
								Inst inst = insts.get(ref);
								if (name.equals("Name")) inst.name = value;
								else inst.props.put(name, value);
							}
						}
						case 0x0E -> {
							float[] x = floats(b, n), y = floats(b, n), z = floats(b, n);
							for (int k = 0; k < n; k++) insts.get(refs[k]).props.put(name, new float[]{x[k], y[k], z[k]});
						}
						case 0x10 -> {
							float[][] rots = new float[n][];
							for (int k = 0; k < n; k++) {
								int rotation = b.get() & 0xFF;
								if (rotation == 0) {
									rots[k] = new float[9];
									for (int r = 0; r < 9; r++) rots[k][r] = b.getFloat();
								} else {
									rots[k] = basicRotation(rotation);
								}
							}
							float[] x = floats(b, n), y = floats(b, n), z = floats(b, n);
							for (int k = 0; k < n; k++) insts.get(refs[k]).props.put(name, new Frame(new float[]{x[k], y[k], z[k]}, rots[k]));
						}
						default -> {
						}
					}
				}
				case "PRNT" -> {
					b.get();
					int count = b.getInt();
					int[] children = referents(b, count), parents = referents(b, count);
					for (int k = 0; k < count; k++) {
						Inst inst = insts.get(children[k]);
						if (inst != null) inst.parent = parents[k];
					}
				}
				default -> {
				}
			}
		}
		return insts;
	}

	private static String string(ByteBuffer b) {
		int length = b.getInt();
		byte[] bytes = new byte[length];
		b.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** {@code count} 4-byte values stored byte-plane by byte-plane, most significant first. */
	private static int[] interleaved(ByteBuffer b, int count) {
		byte[] bytes = new byte[count * 4];
		b.get(bytes);
		int[] out = new int[count];
		for (int k = 0; k < count; k++) {
			out[k] = (bytes[k] & 0xFF) << 24 | (bytes[count + k] & 0xFF) << 16 | (bytes[2 * count + k] & 0xFF) << 8 | (bytes[3 * count + k] & 0xFF);
		}
		return out;
	}

	/** Instance referents: interleaved, zigzag-encoded, each one a step from the last. */
	private static int[] referents(ByteBuffer b, int count) {
		int[] raw = interleaved(b, count);
		int last = 0;
		for (int k = 0; k < count; k++) {
			last += (raw[k] >>> 1) ^ -(raw[k] & 1);
			raw[k] = last;
		}
		return raw;
	}

	/** Floats with their sign bit moved to the bottom, interleaved. */
	private static float[] floats(ByteBuffer b, int count) {
		int[] raw = interleaved(b, count);
		float[] out = new float[count];
		for (int k = 0; k < count; k++) out[k] = Float.intBitsToFloat((raw[k] >>> 1) | (raw[k] << 31));
		return out;
	}

	/** A right-angle rotation stored as an id: which axis the right and up vectors point along. */
	private static float[] basicRotation(int id) {
		float[][] normals = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0}, {0, -1, 0}, {0, 0, -1}};
		int code = id - 1;
		float[] x = normals[Math.floorMod(code / 6, 6)], y = normals[Math.floorMod(code % 6, 6)];
		float[] z = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
		return new float[]{x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]};
	}

	/** LZ4 block decompression. Roblox models' chunks are LZ4 (newer ones can be zstd, which we can't read). */
	private static byte[] decompress(byte[] src, int size) throws IOException {
		if (src.length >= 4 && (src[0] & 0xFF) == 0x28 && (src[1] & 0xFF) == 0xB5 && (src[2] & 0xFF) == 0x2F && (src[3] & 0xFF) == 0xFD) {
			throw new IOException("This model uses zstd compression, which isn't supported yet");
		}
		byte[] out = new byte[size];
		int i = 0, o = 0;
		while (i < src.length) {
			int token = src[i++] & 0xFF;
			int literals = token >>> 4;
			if (literals == 15) {
				int more;
				do {
					more = src[i++] & 0xFF;
					literals += more;
				} while (more == 255);
			}
			System.arraycopy(src, i, out, o, literals);
			i += literals;
			o += literals;
			if (i >= src.length) break;
			int back = (src[i] & 0xFF) | (src[i + 1] & 0xFF) << 8;
			i += 2;
			int match = token & 15;
			if (match == 15) {
				int more;
				do {
					more = src[i++] & 0xFF;
					match += more;
				} while (more == 255);
			}
			match += 4;
			int from = o - back;
			for (int k = 0; k < match; k++) out[o++] = out[from + k];
		}
		if (o != size) throw new IOException("A model chunk didn't unpack right");
		return out;
	}

	// ---------------------------------------------------------------- meshes

	/** A mesh's triangles: per corner x y z u v nx ny nz, in the mesh's own space (studs). */
	record Mesh(float[] data, int triangles) {
		static final int STRIDE = 8;
	}

	/** Reads a Roblox .mesh of version 1 to 5. */
	static Mesh mesh(byte[] data) throws IOException {
		int newline = 0;
		while (newline < data.length && data[newline] != '\n') newline++;
		String version = new String(data, 0, newline, StandardCharsets.US_ASCII).trim();
		if (version.startsWith("version 1")) return textMesh(new String(data, StandardCharsets.US_ASCII), version.equals("version 1.00") ? 0.5f : 1f);
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		b.position(newline + 1);
		int start = b.position();
		int headerSize = b.getShort() & 0xFFFF;
		int vertexSize, faceSize = 12, vertices, faces, lods = 0, bones = 0;
		if (version.startsWith("version 2")) {
			vertexSize = b.get() & 0xFF;
			faceSize = b.get() & 0xFF;
			vertices = b.getInt();
			faces = b.getInt();
		} else if (version.startsWith("version 3")) {
			vertexSize = b.get() & 0xFF;
			faceSize = b.get() & 0xFF;
			b.getShort();
			lods = b.getShort() & 0xFFFF;
			vertices = b.getInt();
			faces = b.getInt();
		} else if (version.startsWith("version 4") || version.startsWith("version 5")) {
			b.getShort();
			vertices = b.getInt();
			faces = b.getInt();
			lods = b.getShort() & 0xFFFF;
			bones = b.getShort() & 0xFFFF;
			vertexSize = 40;
		} else {
			throw new IOException("Mesh " + version + " isn't supported yet");
		}
		b.position(start + headerSize);
		float[][] verts = new float[vertices][];
		for (int v = 0; v < vertices; v++) {
			int at = b.position();
			verts[v] = new float[]{b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat()};
			b.position(at + vertexSize);
		}
		if (bones > 0) b.position(b.position() + vertices * 8);
		int facesAt = b.position();
		int used = faces;
		if (lods > 1) {
			// Only the most detailed level of detail: faces up to where the second one starts
			b.position(facesAt + faces * faceSize);
			b.getInt();
			used = Math.min(faces, b.getInt());
		}
		b.position(facesAt);
		List<float[]> corners = new ArrayList<>();
		for (int f = 0; f < used; f++) {
			int at = b.position();
			int a = b.getInt(), c = b.getInt(), d = b.getInt();
			b.position(at + faceSize);
			if (a >= vertices || c >= vertices || d >= vertices) continue;
			corners.add(verts[a]);
			corners.add(verts[c]);
			corners.add(verts[d]);
		}
		return pack(corners, false);
	}

	private static Mesh textMesh(String text, float scale) throws IOException {
		String[] lines = text.split("\\R");
		if (lines.length < 3) throw new IOException("Empty mesh");
		Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE]-?\\d+)?").matcher(lines[2]);
		List<Float> nums = new ArrayList<>();
		while (m.find()) nums.add(Float.parseFloat(m.group()));
		List<float[]> corners = new ArrayList<>();
		for (int o = 0; o + 9 <= nums.size(); o += 9) {
			corners.add(new float[]{nums.get(o) * scale, nums.get(o + 1) * scale, nums.get(o + 2) * scale, nums.get(o + 3), nums.get(o + 4), nums.get(o + 5), nums.get(o + 6), nums.get(o + 7)});
		}
		return pack(corners, true);
	}

	/** Packs corners (x y z nx ny nz u v) into the mesh layout (x y z u v nx ny nz). Old text meshes have v upside down. */
	private static Mesh pack(List<float[]> corners, boolean flipV) {
		int triangles = corners.size() / 3;
		float[] data = new float[triangles * 3 * Mesh.STRIDE];
		for (int k = 0; k < triangles * 3; k++) {
			float[] c = corners.get(k);
			int o = k * Mesh.STRIDE;
			data[o] = c[0];
			data[o + 1] = c[1];
			data[o + 2] = c[2];
			data[o + 3] = c[6];
			data[o + 4] = flipV ? 1 - c[7] : c[7];
			data[o + 5] = c[3];
			data[o + 6] = c[4];
			data[o + 7] = c[5];
		}
		return new Mesh(data, triangles);
	}
}
