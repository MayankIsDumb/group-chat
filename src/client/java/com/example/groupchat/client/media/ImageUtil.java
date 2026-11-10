package com.example.groupchat.client.media;

import com.example.groupchat.Constants;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Turns arbitrary local image files into the PNG bytes the mod transfers. Everything here runs off
 * the render thread (see {@code ImagePickerScreen}), and only the finished PNG is handed to the GPU.
 */
public final class ImageUtil {
	private static final String[] SUPPORTED = {".png", ".jpg", ".jpeg", ".gif", ".bmp"};

	private ImageUtil() {
	}

	public static boolean looksLikeImage(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		for (String suffix : SUPPORTED) {
			if (name.endsWith(suffix)) {
				return true;
			}
		}
		return false;
	}

	public static byte[] readAndNormalise(Path path) throws IOException {
		return normalise(Files.readAllBytes(path));
	}

	/**
	 * Decodes any format ImageIO understands, fits it into {@link Constants#MAX_IMAGE_DIMENSION} and
	 * re-encodes it as PNG (the only format {@code NativeImage} can read).
	 */
	public static byte[] normalise(byte[] raw) throws IOException {
		BufferedImage source;
		try (ByteArrayInputStream input = new ByteArrayInputStream(raw)) {
			source = ImageIO.read(input);
		}
		if (source == null) {
			throw new IOException("Unsupported image format");
		}
		BufferedImage scaled = scale(source);
		BufferedImage argb = new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = argb.createGraphics();
		try {
			graphics.drawImage(scaled, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (!ImageIO.write(argb, "png", output)) {
			throw new IOException("No PNG encoder available");
		}
		byte[] png = output.toByteArray();
		if (png.length > Constants.MAX_IMAGE_BYTES) {
			throw new IOException("Image is too large after conversion (" + (png.length / 1000) + " KB)");
		}
		return png;
	}


	/** Loads and downscales an image for a thumbnail (used by the image picker). */
	public static byte[] thumbnail(Path path, int maxSize) throws IOException {
		BufferedImage source;
		try (java.io.InputStream input = Files.newInputStream(path)) {
			source = ImageIO.read(input);
		}
		if (source == null) {
			throw new IOException("Unsupported image format");
		}
		double factor = Math.min(1.0, Math.min((double) maxSize / source.getWidth(), (double) maxSize / source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
		int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = target.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(target, "png", output);
		return output.toByteArray();
	}

	private static BufferedImage scale(BufferedImage source) {
		int max = Constants.MAX_IMAGE_DIMENSION;
		int width = source.getWidth();
		int height = source.getHeight();
		if (width <= max && height <= max) {
			return source;
		}
		double factor = Math.min((double) max / width, (double) max / height);
		int targetWidth = Math.max(1, (int) Math.round(width * factor));
		int targetHeight = Math.max(1, (int) Math.round(height * factor));
		BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = scaled.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
		} finally {
			graphics.dispose();
		}
		return scaled;
	}
}
