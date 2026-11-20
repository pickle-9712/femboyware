package dev.femboyware.module.donut;

import java.util.List;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
final class CompletionRequests {
	private CompletionChannel.Form form = CompletionChannel.Form.BOTH;

	CompletionChannel.Form form() {
		return this.form;
	}

	void form(CompletionChannel.Form form) {
		this.form = form;
	}

	int fire(String partial) {
		return this.fire(partial, this.form);
	}

	int fire(String partial, CompletionChannel.Form using) {
		return CompletionChannel.INSTANCE.fire(this, partial, using);
	}

	List<String> poll(int id) {
		return CompletionChannel.INSTANCE.poll(id);
	}

	void forget(int id) {
		CompletionChannel.INSTANCE.forget(id);
	}

	void prune(long now) {
		CompletionChannel.INSTANCE.prune(now);
	}

	void clear() {
		CompletionChannel.INSTANCE.clear(this);
		this.form = CompletionChannel.Form.BOTH;
	}
}
