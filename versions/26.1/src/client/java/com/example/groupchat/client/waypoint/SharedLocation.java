package com.example.groupchat.client.waypoint;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

/**
 * A position shared in chat: encoded in the message text as {@code dimension|x|y|z}.
 */
public record SharedLocation(String dimension, double x, double y, double z) {
	public static SharedLocation parse(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String[] parts = text.split("\\|");
		if (parts.length != 4) {
			return null;
		}
		try {
			return new SharedLocation(parts[0].trim(), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
					Double.parseDouble(parts[3]));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	public static SharedLocation of(LocalPlayer player) {
		Identifier dimension = player.level().dimension().identifier();
		return new SharedLocation(dimension.toString(), player.getX(), player.getY(), player.getZ());
	}

	public String encode() {
		return this.dimension + '|' + Math.round(this.x * 10.0D) / 10.0D + '|' + Math.round(this.y * 10.0D) / 10.0D
				+ '|' + Math.round(this.z * 10.0D) / 10.0D;
	}

	/** {@code 128, 64, -301} — how players read coordinates. */
	public String pretty() {
		return String.format(Locale.ROOT, "%d, %d, %d", Math.round(this.x), Math.round(this.y), Math.round(this.z));
	}

	public String dimensionLabel() {
		String path = this.dimension;
		int colon = path.indexOf(':');
		if (colon >= 0) {
			path = path.substring(colon + 1);
		}
		if (path.isEmpty()) {
			return "Unknown";
		}
		String spaced = path.replace('_', ' ');
		return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}

	public boolean sameDimension(String otherDimension) {
		return this.dimension.equals(otherDimension);
	}

	/** Distance from the player, or {@code -1} when the player is in another dimension. */
	public double distanceFrom(Minecraft client) {
		if (client.player == null || client.level == null) {
			return -1.0D;
		}
		if (!this.sameDimension(client.level.dimension().identifier().toString())) {
			return -1.0D;
		}
		double dx = this.x - client.player.getX();
		double dy = this.y - client.player.getY();
		double dz = this.z - client.player.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
