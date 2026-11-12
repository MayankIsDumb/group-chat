package com.example.groupchat.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sliding-window rate limiting, keyed by {@code player:action}.
 */
public final class RateLimits {
	private final Map<String, Deque<Long>> hits = new HashMap<>();

	public boolean allow(String player, String action, int max, long windowMs) {
		long now = System.currentTimeMillis();
		String key = player + ':' + action;
		Deque<Long> window = this.hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		while (!window.isEmpty() && now - window.peekFirst() > windowMs) {
			window.pollFirst();
		}
		if (window.size() >= max) {
			return false;
		}
		window.addLast(now);
		return true;
	}

	public void clear() {
		this.hits.clear();
	}
}
