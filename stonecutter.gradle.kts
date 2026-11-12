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

        // 26.3: GLFW became SDL, the GPU api moved to renderpearl, and a few renames
        fun rename3(from: String, to: String, back: String, backTo: String) =
            regex(current.parsed >= "26.3") { replace(from, to, back, backTo) }
        fun word3(old: String, new: String) = rename3("\\b$old\\b", new, "\\b$new\\b", old)
        // Our own stand-in with the GLFW names on SDL's codes
        rename3("import org\\.lwjgl\\.glfw\\.GLFW;", "import dev.femboyware.util.GLFW;", "import dev\\.femboyware\\.util\\.GLFW;", "import org.lwjgl.glfw.GLFW;")
        fun gpu(old: String, new: String) {
            rename3("\\b${old.replace(".", "\\.")}\\b", new, "\\b${new.replace(".", "\\.")}\\b", old)
            rename3("L${old.replace('.', '/')};", "L${new.replace('.', '/')};", "L${new.replace('.', '/')};", "L${old.replace('.', '/')};")
        }
        for (c in listOf("GpuBuffer", "GpuBufferSlice")) gpu("com.mojang.blaze3d.buffers.$c", "com.mojang.renderpearl.api.buffers.$c")
        for (c in listOf("BlendFunction", "ColorTargetState", "DepthStencilState", "RenderPipeline")) gpu("com.mojang.blaze3d.pipeline.$c", "com.mojang.renderpearl.api.pipeline.$c")
        gpu("com.mojang.blaze3d.platform.CompareOp", "com.mojang.renderpearl.api.pipeline.CompareOp")
        gpu("com.mojang.blaze3d.systems.GpuDevice", "com.mojang.renderpearl.api.device.GpuDevice")
        for (c in listOf("FilterMode", "GpuSampler", "GpuTexture", "GpuTextureView")) gpu("com.mojang.blaze3d.textures.$c", "com.mojang.renderpearl.api.textures.$c")
        word3("RedStoneWireBlock", "RedstoneWireBlock")
        // Swings only animate now (the server works them out itself), and take an animation
        rename3("\\.swing\\(([\\w.]+)\\)", ".swing($1, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false)",
            "\\.swing\\(([\\w.]+), net\\.minecraft\\.world\\.item\\.component\\.SwingAnimation\\.DEFAULT, false\\)", ".swing($1)")
        rename3("\\.swinging\\b", ".isSwinging()", "\\.isSwinging\\(\\)", ".swinging")
        word3("getAttackAnim", "getSwingAnimation")
        rename3("\\.getEntityIds\\(\\)", ".entityIds()", "\\.entityIds\\(\\)", ".getEntityIds()")
        rename3("packet\\.getHitResult\\(\\)", "packet.hitResult()", "packet\\.hitResult\\(\\)", "packet.getHitResult()")
        rename3("InputConstants\\.Type\\.KEYSYM\\b", "InputConstants.Type.KEYBOARD", "InputConstants\\.Type\\.KEYBOARD\\b", "InputConstants.Type.KEYSYM")
        // PoseStack rotations are rotate() now; mulPose only takes matrices (every rotated PoseStack here is named `matrices`)
        rename3("\\bmatrices\\.mulPose\\((Axis\\.|anchor\\.facing\\)|event\\.camera\\(\\)\\.orientation\\))", "matrices.rotate($1",
            "\\bmatrices\\.rotate\\((Axis\\.|anchor\\.facing\\)|event\\.camera\\(\\)\\.orientation\\))", "matrices.mulPose($1")
    }
}

// Builds every version and copies the jars to build/libs/<mod version>/
tasks.register("buildAll") {
    group = "build"
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}
