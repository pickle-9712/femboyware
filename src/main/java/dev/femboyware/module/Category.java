package dev.femboyware.module;

/** A tab inside one of the ClickGUI panels. Each panel ({@link Group}) holds several tabs. */
public enum Category {
	ATTACK(Group.PVP, "Attack"),
	LEGIT(Group.PVP, "Legit"),
	PROTECT(Group.PVP, "Protect"),
	BASIC(Group.MOVEMENT, "Basic"),
	RAGE(Group.MOVEMENT, "Rage"),
	COSMETIC(Group.VISUAL, "Cosmetic"),
	ESP(Group.VISUAL, "Esp"),
	LARP(Group.VISUAL, "Larp"),
	WORLD(Group.UTILITY, "World"),
	AUTO(Group.UTILITY, "Auto"),
	BUILD(Group.UTILITY, "Build"),
	EQUIP(Group.UTILITY, "Equip"),
	PLAYER(Group.UTILITY, "Player"),
	MISC(Group.UTILITY, "Misc"),
	THEME(Group.CUSTOMIZE, "Theme"),
	TARGETS(Group.CUSTOMIZE, "Targets"),
	EFFECTS(Group.CUSTOMIZE, "Effects"),
	SKY(Group.CUSTOMIZE, "Sky"),
	ELEMENTS(Group.HUD, "Elements");

	public enum Group {
		PVP("Pvp"),
		MOVEMENT("Movement"),
		VISUAL("Visual"),
		UTILITY("Utility"),
		CUSTOMIZE("Customize"),
		HUD("HUD");

		private final String displayName;

		Group(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return displayName;
		}
	}

	private final Group group;
	private final String displayName;

	Category(Group group, String displayName) {
		this.group = group;
		this.displayName = displayName;
	}

	public Group getGroup() {
		return group;
	}

	public String getDisplayName() {
		return displayName;
	}
}
