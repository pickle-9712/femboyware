package dev.femboyware.module.donut;

import dev.femboyware.mixin.DonutDialogScreenAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.ButtonListDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.SimpleDialog;
import net.minecraft.server.dialog.action.Action.ValueGetter;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.world.item.ItemStack;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
final class Dialogs {
	private Dialogs() {
	}

	static Dialogs.View current(Minecraft mc) {
		if (!(dev.femboyware.util.Mc.screen() instanceof DialogScreen<?> screen)) {
			return null;
		} else {
			Dialog dialog = ((DonutDialogScreenAccessor)screen).femboyware$dialog();
			if (dialog == null) {
				return null;
			}

			List<ActionButton> raw = new ArrayList<>();
			if (dialog instanceof MultiActionDialog multi) {
				raw.addAll(multi.actions());
			}

			if (dialog instanceof SimpleDialog simple) {
				raw.addAll(simple.mainActions());
			}

			if (dialog instanceof ButtonListDialog list) {
				list.exitAction().ifPresent(raw::add);
			}

			List<Dialogs.Button> buttons = new ArrayList<>(raw.size());

			for (ActionButton button : raw) {
				Component label = button.button().label();
				String text = visibleText(label);
				buttons.add(new Dialogs.Button(button, ServerText.normalize(text), ServerText.key(text), label));
			}

			List<String> body = new ArrayList<>();
			List<ItemStack> items = new ArrayList<>();

			for (DialogBody part : dialog.common().body()) {
				if (part instanceof PlainMessage message) {
					body.add(ServerText.normalize(visibleText(message.contents())));
				}

				if (part instanceof ItemBody item) {
					//? if >=26.1 {
					/*items.add(item.item().create());
					*///?} else {
					items.add(item.item());
					//?}
					item.description().ifPresent(message -> body.add(ServerText.normalize(visibleText(message.contents()))));
				}
			}

			List<String> textInputs = new ArrayList<>();

			for (Input input : dialog.common().inputs()) {
				if (input.control() instanceof TextInput) {
					textInputs.add(input.key());
				}
			}

			return new Dialogs.View(dialog, ServerText.normalize(visibleText(dialog.common().title())), buttons, body, textInputs, items);
		}
	}

	static String visibleText(Component component) {
		StringBuilder out = new StringBuilder();
		appendVisible(component, out);
		return out.toString();
	}

	private static void appendVisible(Component component, StringBuilder out) {
		if (!(component.getContents() instanceof ObjectContents)) {
			component.getContents().visit(text -> {
				out.append(text);
				return Optional.empty();
			});
		}

		for (Component sibling : component.getSiblings()) {
			appendVisible(sibling, out);
		}
	}

	static boolean press(Minecraft mc, Dialogs.View view, Dialogs.Button button, Map<String, String> text) {
		if (dev.femboyware.util.Mc.screen() instanceof DialogScreen<?> screen) {
			if (((DonutDialogScreenAccessor)screen).femboyware$dialog() != view.dialog()) {
				return false;
			}

			Map<String, ValueGetter> values = new HashMap<>();

			for (Input input : view.dialog().common().inputs()) {
				if (input.control() instanceof TextInput textInput) {
					values.put(input.key(), ValueGetter.of(text.getOrDefault(input.key(), textInput.initial())));
				} else if (input.control() instanceof BooleanInput bool) {
					values.put(input.key(), booleanValue(bool));
				}
			}

			Optional<ClickEvent> event = button.raw().action().flatMap(action -> action.createAction(values));
			// femboyware safeguard: never press a button that would send money or items to someone
			if (event.isPresent() && event.get() instanceof ClickEvent.RunCommand run && sendsMoney(run.command())) {
				return false;
			}
			screen.runAction(event);
			return true;
		} else {
			return false;
		}
	}

	private static boolean sendsMoney(String command) {
		String c = command.trim().toLowerCase(java.util.Locale.ROOT);
		while (c.startsWith("/")) c = c.substring(1);
		String head = c.split(" ", 2)[0];
		head = head.substring(head.indexOf(':') + 1); // essentials:pay -> pay
		return java.util.Set.of("pay", "epay", "money", "eco", "trade", "give", "gift", "bank", "transfer", "send").contains(head);
	}

	static void close(Minecraft mc) {
		if (dev.femboyware.util.Mc.screen() instanceof DialogScreen<?> screen) {
			screen.onClose();
		}
	}

	private static ValueGetter booleanValue(BooleanInput input) {
		final boolean value = input.initial();
		return new ValueGetter() {
			public String asTemplateSubstitution() {
				return value ? input.onTrue() : input.onFalse();
			}

			public Tag asTag() {
				return ByteTag.valueOf(value);
			}
		};
	}

	private static boolean isGreen(int rgb) {
		int r = rgb >> 16 & 0xFF;
		int g = rgb >> 8 & 0xFF;
		int b = rgb & 0xFF;
		return g >= 192 && r < 144 && b < 144;
	}
	record Button(ActionButton raw, String label, String key, Component component) {
		boolean green() {
			boolean[] found = new boolean[]{false};
			this.component.visit((style, text) -> {
				TextColor color = style.getColor();
				if (!text.isBlank() && color != null && Dialogs.isGreen(color.getValue())) {
					found[0] = true;
				}

				return Optional.empty();
			}, Style.EMPTY);
			return found[0];
		}
	}
	record View(Dialog dialog, String title, List<Dialogs.Button> buttons, List<String> body, List<String> textInputs, List<ItemStack> items) {
		Dialogs.Button find(Predicate<Dialogs.Button> test) {
			for (Dialogs.Button button : this.buttons) {
				if (test.test(button)) {
					return button;
				}
			}

			return null;
		}

		Dialogs.Button labelled(String... words) {
			for (String word : words) {
				String wanted = ServerText.key(word);
				Dialogs.Button exact = this.find(button -> button.key().equals(wanted));
				if (exact != null) {
					return exact;
				}
			}

			for (String word : words) {
				String wanted = ServerText.key(word);
				Dialogs.Button partial = this.find(button -> button.key().contains(wanted));
				if (partial != null) {
					return partial;
				}
			}

			return null;
		}

		boolean titled(String text) {
			return this.title.contains(text);
		}

		String describe() {
			List<String> labels = new ArrayList<>();

			for (Dialogs.Button button : this.buttons) {
				if (!button.label().isBlank()) {
					labels.add(button.label());
				}

				if (labels.size() >= 12) {
					break;
				}
			}

			return "'" + this.title + "' buttons: " + (labels.isEmpty() ? "(none)" : String.join(", ", labels));
		}
	}
}
