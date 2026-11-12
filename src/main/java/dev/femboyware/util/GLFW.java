package dev.femboyware.util;

//? if >=26.3 {
/*import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLMouse;

/^*
 * From 26.3 the game runs on SDL instead of GLFW: keys are SDL scancodes and mouse buttons count
 * from 1. This stands in for the bits of GLFW the mod uses, with SDL's values, so key handling
 * reads the same on every version (on older ones the real GLFW is imported instead).
 ^/
public final class GLFW {
	private GLFW() {
	}

	public static final int GLFW_PRESS = InputConstants.PRESS;
	public static final int GLFW_RELEASE = InputConstants.RELEASE;
	public static final int GLFW_MOUSE_BUTTON_LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	public static final int GLFW_MOUSE_BUTTON_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	public static final int GLFW_MOUSE_BUTTON_MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;
	public static final int GLFW_KEY_UNKNOWN = 0;
	public static final int GLFW_KEY_A = InputConstants.KEY_A;
	public static final int GLFW_KEY_B = InputConstants.KEY_B;
	public static final int GLFW_KEY_C = InputConstants.KEY_C;
	public static final int GLFW_KEY_F = InputConstants.KEY_F;
	public static final int GLFW_KEY_M = InputConstants.KEY_M;
	public static final int GLFW_KEY_N = InputConstants.KEY_N;
	public static final int GLFW_KEY_V = InputConstants.KEY_V;
	public static final int GLFW_KEY_X = InputConstants.KEY_X;
	public static final int GLFW_KEY_Z = InputConstants.KEY_Z;
	public static final int GLFW_KEY_6 = InputConstants.KEY_6;
	public static final int GLFW_KEY_ESCAPE = InputConstants.KEY_ESCAPE;
	public static final int GLFW_KEY_ENTER = InputConstants.KEY_RETURN;
	/^* SDL_SCANCODE_KP_ENTER ^/
	public static final int GLFW_KEY_KP_ENTER = 88;
	public static final int GLFW_KEY_BACKSPACE = InputConstants.KEY_BACKSPACE;
	public static final int GLFW_KEY_DELETE = InputConstants.KEY_DELETE;
	public static final int GLFW_KEY_TAB = InputConstants.KEY_TAB;
	public static final int GLFW_KEY_HOME = InputConstants.KEY_HOME;
	public static final int GLFW_KEY_END = InputConstants.KEY_END;
	public static final int GLFW_KEY_UP = InputConstants.KEY_UP;
	public static final int GLFW_KEY_DOWN = InputConstants.KEY_DOWN;
	public static final int GLFW_KEY_LEFT = InputConstants.KEY_LEFT;
	public static final int GLFW_KEY_RIGHT = InputConstants.KEY_RIGHT;
	public static final int GLFW_KEY_LEFT_SHIFT = InputConstants.KEY_LSHIFT;
	public static final int GLFW_KEY_RIGHT_SHIFT = InputConstants.KEY_RSHIFT;
	public static final int GLFW_KEY_LEFT_CONTROL = InputConstants.KEY_LCONTROL;
	public static final int GLFW_KEY_RIGHT_CONTROL = InputConstants.KEY_RCONTROL;

	/^* Whether the key is held (the window is ignored; SDL tracks the focused one). ^/
	public static int glfwGetKey(long window, int key) {
		return InputConstants.isKeyDown(key) ? GLFW_PRESS : GLFW_RELEASE;
	}

	public static int glfwGetMouseButton(long window, int button) {
		int held = SDLMouse.SDL_GetMouseState((java.nio.FloatBuffer) null, (java.nio.FloatBuffer) null);
		return (held & (1 << (button - 1))) != 0 ? GLFW_PRESS : GLFW_RELEASE;
	}

	public static void glfwSetCursorPos(long window, double x, double y) {
		SDLMouse.SDL_WarpMouseInWindow(window, (float) x, (float) y);
	}
}
*///?}
