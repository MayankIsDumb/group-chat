package com.example.groupchat.voice;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-neutral voice backend hook. The Simple Voice Chat plugin installs an implementation when
 * SVC is present; otherwise calls are no-ops and the built-in voice transport is used.
 */
public final class VoiceBridge {
	public record Call(String groupId, String groupName, List<ServerPlayer> members) {
	}

	public interface Impl {
		default void update(List<Call> calls) {
		}

		default void reset() {
		}
	}

	private static Impl impl = new Impl() {
	};

	private VoiceBridge() {
	}

	public static void set(Impl value) {
		impl = value == null ? new Impl() {
		} : value;
	}

	public static void update(List<Call> calls) {
		impl.update(calls);
	}

	public static void reset() {
		impl.reset();
	}
}
