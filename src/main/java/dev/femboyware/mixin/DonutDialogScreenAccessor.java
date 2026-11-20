package dev.femboyware.mixin;

import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.server.dialog.Dialog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The dialog a server dialog screen shows, for the DonutSMP AH macros. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(DialogScreen.class)
public interface DonutDialogScreenAccessor {
	@Accessor("dialog")
	Dialog femboyware$dialog();
}
