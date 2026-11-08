plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11"

stonecutter parameters {
    replacements {
        // Pure renames between 1.21.11 and 26.x. Each pair is (1.21.11 pattern -> 26.x value, 26.x pattern -> 1.21.11 value).
        // The reverse pattern runs on 1.21.11 sources, so it must never match code that is already 1.21.11.
        fun rename(from: String, to: String, back: String, backTo: String) =
            regex(current.parsed >= "26.1") { replace(from, to, back, backTo) }
        fun word(old: String, new: String) = rename("\\b$old\\b", new, "\\b$new\\b", old)

        word("GuiGraphics", "GuiGraphicsExtractor")
        word("ClickType", "ContainerInput")
        word("handleInventoryMouseClick", "handleContainerInput")
        word("KeyBindingHelper", "KeyMappingHelper")
        word("registerKeyBinding", "registerKeyMapping")
        word("LivingEntityFeatureRendererRegistrationCallback", "LivingEntityRenderLayerRegistrationCallback")
        rename("api\\.client\\.keybinding\\.v1", "api.client.keymapping.v1", "api\\.client\\.keymapping\\.v1", "api.client.keybinding.v1")
        rename("net\\.minecraft\\.client\\.renderer\\.state\\.CameraRenderState", "net.minecraft.client.renderer.state.level.CameraRenderState",
            "net\\.minecraft\\.client\\.renderer\\.state\\.level\\.CameraRenderState", "net.minecraft.client.renderer.state.CameraRenderState")
        rename("net\\.minecraft\\.client\\.gui\\.render\\.state\\.GlyphRenderState", "net.minecraft.client.renderer.state.gui.GlyphRenderState",
            "net\\.minecraft\\.client\\.renderer\\.state\\.gui\\.GlyphRenderState", "net.minecraft.client.gui.render.state.GlyphRenderState")
        rename("import net\\.minecraft\\.client\\.GuiMessage;", "import net.minecraft.client.multiplayer.chat.GuiMessage;",
            "import net\\.minecraft\\.client\\.multiplayer\\.chat\\.GuiMessage;", "import net.minecraft.client.GuiMessage;")
        // The same moves in descriptor form, as written in mixin targets
        fun moved(old: String, new: String) = rename("L$old;", "L$new;", "L$new;", "L$old;")
        moved("net/minecraft/client/renderer/state/CameraRenderState", "net/minecraft/client/renderer/state/level/CameraRenderState")
        moved("net/minecraft/client/GuiMessage", "net/minecraft/client/multiplayer/chat/GuiMessage")
        moved("net/minecraft/client/GuiMessageTag", "net/minecraft/client/multiplayer/chat/GuiMessageTag")
        // Entity type constants moved to EntityTypes
        rename("\\bEntityType\\.([A-Z_]+)\\b", "net.minecraft.world.entity.EntityTypes.$1",
            "net\\.minecraft\\.world\\.entity\\.EntityTypes\\.([A-Z_]+)\\b", "EntityType.$1")

        // GuiGraphics draw calls (every GuiGraphics in this codebase is named `context`)
        rename("context\\.drawString\\(", "context.text(", "context\\.text\\(", "context.drawString(")
        rename("context\\.drawCenteredString\\(", "context.centeredText(", "context\\.centeredText\\(", "context.drawCenteredString(")
        rename("context\\.renderItemDecorations\\(", "context.itemDecorations(", "context\\.itemDecorations\\(", "context.renderItemDecorations(")
        rename("context\\.renderItem\\(", "context.item(", "context\\.item\\(", "context.renderItem(")

        // Screen rendering became render state extraction
        rename("void render\\(GuiGraphics(Extractor)? context, int mouseX, int mouseY, float deltaTicks\\)",
            "void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks)",
            "void extractRenderState\\(GuiGraphics(Extractor)? context, int mouseX, int mouseY, float deltaTicks\\)",
            "void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks)")
        rename("void renderBackground\\(GuiGraphics(Extractor)? context, int mouseX, int mouseY, float deltaTicks\\)",
            "void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks)",
            "void extractBackground\\(GuiGraphics(Extractor)? context, int mouseX, int mouseY, float deltaTicks\\)",
            "void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks)")
        word("renderBlurredBackground", "extractBlurredBackground")
        rename("\\brenderBackground\\(context, mouseX, mouseY, deltaTicks\\)", "extractBackground(context, mouseX, mouseY, deltaTicks)",
            "\\bextractBackground\\(context, mouseX, mouseY, deltaTicks\\)", "renderBackground(context, mouseX, mouseY, deltaTicks)")
        rename("skinWidget\\.render\\(", "skinWidget.extractRenderState(", "skinWidget\\.extractRenderState\\(", "skinWidget.render(")
    }
}

// Builds every version and copies the jars to build/libs/<mod version>/
tasks.register("buildAll") {
    group = "build"
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}
