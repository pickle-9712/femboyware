package dev.femboyware.util.playerbypass;

import java.util.Arrays;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class ChunkLightSnapshot {
	public static final int BASE_DETECT_CEILING_Y = 62;
	public final int sectionCount;
	public final int bottomSectionY;
	public final LightSectionStatus[] sky;

	public ChunkLightSnapshot(int sectionCount, int bottomSectionY) {
		this.sectionCount = sectionCount;
		this.bottomSectionY = bottomSectionY;
		this.sky = new LightSectionStatus[sectionCount + 2];
		Arrays.fill(this.sky, LightSectionStatus.UNKNOWN);
	}

	public boolean hasBaseSignal() {
		for (int i = 1; i < this.sectionCount + 1; i++) {
			if (candidate(i, this.bottomSectionY) && this.sky[i] == LightSectionStatus.ZEROED_PRESENT) {
				return true;
			}
		}

		return false;
	}

	static boolean candidate(int index, int bottomSectionY) {
		return (bottomSectionY + index - 1) * 16 + 15 < 62;
	}
}
