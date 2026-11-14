package dev.femboyware.setting;

import java.util.List;

/** A setting picked from a dropdown in the ClickGUI: an enum or a list that can change. */
public interface Choice {
	String getName();

	void reset();

	List<String> optionNames();

	int selectedIndex();

	void setIndex(int index);

	String getDisplayName();
}
