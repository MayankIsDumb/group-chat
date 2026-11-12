package com.example.groupchat.client.media;

import com.example.groupchat.Constants;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

/**
 * Decodes an animated GIF into plain ARGB frames.
 *
 * <p>The JDK's GIF reader is used rather than a bundled decoder, so no extra dependency is needed.
 * Frames are scaled down to {@link Constants#MAX_GIF_DIMENSION} and the frame count is capped at
 * {@link Constants#MAX_GIF_FRAMES} so a single chat message can never blow up the client.
 *
 * <p>The reader hands out the frames as they are stored in the file, which for an optimised GIF
 * means a frame can be just the few pixels that changed. The frames are therefore painted onto a
 * canvas of the logical screen size, honouring the disposal method, so each frame comes out
 * complete.
 */
public final class GifDecoder {
	/** Decoded frames plus the per-frame delay in milliseconds. */
	public record Decoded(int width, int height, int[][] frames, int[] delays) {
		/** Total animation length in milliseconds. */
		public long duration() {
			long total = 0L;
			for (int delay : this.delays) {
				total += delay;
			}
			return total;
		}
	}

	/** What the canvas should look like before the next frame is painted. */
	private enum Disposal {
		/** Keep the canvas as it is. */
		KEEP,
		/** Clear the area the frame covered. */
		CLEAR,
		/** Restore the canvas from before the frame was painted. */
		RESTORE
	}

	private GifDecoder() {
	}

	/**
	 * @param data the raw GIF bytes
	 * @throws IOException when the data is not a GIF the JDK can read
	 */
	public static Decoded decode(byte[] data) throws IOException {
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
		if (!readers.hasNext()) {
			throw new IOException("No GIF reader available");
		}
		ImageReader reader = readers.next();
		try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			reader.setInput(stream, false, true);
			int count = reader.getNumImages(true);
			if (count <= 0) {
				throw new IOException("GIF has no frames");
			}
			int logicalWidth = reader.getWidth(0);
			int logicalHeight = reader.getHeight(0);
			if (logicalWidth <= 0 || logicalHeight <= 0) {
				throw new IOException("GIF has no dimensions");
			}
			double scale = 1.0D;
			int limit = Constants.MAX_GIF_DIMENSION;
			if (Math.max(logicalWidth, logicalHeight) > limit) {
				scale = (double) limit / Math.max(logicalWidth, logicalHeight);
			}
			int width = Math.max(1, (int) Math.round(logicalWidth * scale));
			int height = Math.max(1, (int) Math.round(logicalHeight * scale));

			int frames = Math.min(count, Constants.MAX_GIF_FRAMES);
			List<int[]> pixels = new ArrayList<>(frames);
			int[] delays = new int[frames];

			// the canvas holds the composited picture so far
			BufferedImage canvas = new BufferedImage(logicalWidth, logicalHeight,
					BufferedImage.TYPE_INT_ARGB);
			BufferedImage restore = null;

			for (int i = 0; i < frames; i++) {
				IIOMetadata metadata = reader.getImageMetadata(i);
				IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
				IIOMetadataNode descriptor = child(root, "ImageDescriptor");
				IIOMetadataNode control = child(root, "GraphicControlExtension");
				Disposal disposal = disposalOf(control);
				int left = attribute(descriptor, "imageLeftPosition", 0);
				int top = attribute(descriptor, "imageTopPosition", 0);
				int frameWidth = attribute(descriptor, "imageWidth", logicalWidth);
				int frameHeight = attribute(descriptor, "imageHeight", logicalHeight);
				delays[i] = delayOf(control);

				if (disposal == Disposal.RESTORE) {
					restore = copyOf(canvas);
				}

				BufferedImage frame = reader.read(i);
				Graphics2D graphics = canvas.createGraphics();
				try {
					// transparent pixels of a frame leave the canvas untouched
					graphics.setComposite(java.awt.AlphaComposite.SrcOver);
					graphics.drawImage(frame, left, top, null);
				} finally {
					graphics.dispose();
				}

				pixels.add(toArgb(canvas, width, height, scale != 1.0D));

				// prepare the canvas for the next frame
				switch (disposal) {
					case CLEAR -> clear(canvas, left, top, frameWidth, frameHeight);
					case RESTORE -> {
						if (restore != null) {
							Graphics2D restoreGraphics = canvas.createGraphics();
							try {
								restoreGraphics.setComposite(java.awt.AlphaComposite.Src);
								restoreGraphics.drawImage(restore, 0, 0, null);
							} finally {
								restoreGraphics.dispose();
							}
							restore = null;
						}
					}
					default -> {
					}
				}
			}
			return new Decoded(width, height, pixels.toArray(new int[0][]), delays);
		} catch (IOException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new IOException("Could not decode the GIF", exception);
		} finally {
			reader.dispose();
		}
	}

	/** Reads the delay of a frame from the GIF graphic control extension. */
	private static int delayOf(IIOMetadataNode control) {
		if (control != null) {
			String delay = control.getAttribute("delayTime");
			if (delay != null) {
				try {
					// the attribute is in hundredths of a second
					return Math.max(20, Integer.parseInt(delay.trim()) * 10);
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return 100;
	}

	/** Reads the disposal method of a frame from the GIF graphic control extension. */
	private static Disposal disposalOf(IIOMetadataNode control) {
		if (control == null) {
			return Disposal.KEEP;
		}
		String method = control.getAttribute("disposalMethod");
		if (method == null) {
			return Disposal.KEEP;
		}
		return switch (method.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "restorebackgroundcolor" -> Disposal.CLEAR;
			case "restoretoprevious" -> Disposal.RESTORE;
			default -> Disposal.KEEP;
		};
	}

	private static int attribute(IIOMetadataNode node, String name, int fallback) {
		if (node == null) {
			return fallback;
		}
		String value = node.getAttribute(name);
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static IIOMetadataNode child(IIOMetadataNode node, String name) {
		for (int i = 0; i < node.getLength(); i++) {
			if (node.item(i).getNodeName().equalsIgnoreCase(name)) {
				return (IIOMetadataNode) node.item(i);
			}
		}
		return null;
	}

	/** Clears a rectangle of the canvas, which is what "restore to background" means. */
	private static void clear(BufferedImage canvas, int left, int top, int width, int height) {
		Graphics2D graphics = canvas.createGraphics();
		try {
			graphics.setComposite(java.awt.AlphaComposite.Clear);
			graphics.fillRect(left, top, width, height);
		} finally {
			graphics.dispose();
		}
	}

	private static BufferedImage copyOf(BufferedImage source) {
		BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = copy.createGraphics();
		try {
			graphics.drawImage(source, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		return copy;
	}

	/** Converts the composited canvas to a flat ARGB array, scaling it down when needed. */
	private static int[] toArgb(BufferedImage canvas, int width, int height, boolean scale) {
		BufferedImage source = canvas;
		if (scale) {
			BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = scaled.createGraphics();
			try {
				graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
						RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				graphics.drawImage(canvas, 0, 0, width, height, null);
			} finally {
				graphics.dispose();
			}
			source = scaled;
		}
		return source.getRGB(0, 0, width, height, null, 0, width);
	}
}
