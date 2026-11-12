package com.example.groupchat.server;

import com.example.groupchat.Constants;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Stores chat images on disk, {@code <world>/groupchat/images/<groupId>/<imageId>.png}, with a small
 * in-memory byte cache. Animated GIFs live next to them as {@code <imageId>.gif}. Images are only
 * ever read back for members of the owning group.
 */
public final class ImageStore {
	private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final byte[] GIF_MAGIC_87 = {'G', 'I', 'F', '8', '7', 'a'};
	private static final byte[] GIF_MAGIC_89 = {'G', 'I', 'F', '8', '9', 'a'};

	private final Path root;
	private final Logger logger;
	private final Map<String, String> groupByImage = new LinkedHashMap<>();
	private final Map<String, String> groupByGif = new LinkedHashMap<>();
	private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
			return this.size() > 32;
		}
	};

	public ImageStore(Path root, Logger logger) {
		this.root = root;
		this.logger = logger;
	}

	public void scan() {
		this.groupByImage.clear();
		this.groupByGif.clear();
		if (!Files.isDirectory(this.root)) {
			return;
		}
		try (DirectoryStream<Path> groups = Files.newDirectoryStream(this.root)) {
			for (Path groupDir : groups) {
				if (!Files.isDirectory(groupDir)) {
					continue;
				}
				String groupId = groupDir.getFileName().toString();
				try (DirectoryStream<Path> files = Files.newDirectoryStream(groupDir, "*.png")) {
					for (Path file : files) {
						this.groupByImage.put(fileNameToId(file.getFileName().toString()), groupId);
					}
				}
				try (DirectoryStream<Path> files = Files.newDirectoryStream(groupDir, "*.gif")) {
					for (Path file : files) {
						this.groupByGif.put(fileNameToId(file.getFileName().toString()), groupId);
					}
				}
			}
		} catch (IOException exception) {
			this.logger.warn("[groupchat] failed to scan image store", exception);
		}
	}

	public boolean isPng(byte[] data) {
		return startsWith(data, PNG_MAGIC);
	}

	/** GIFs are recognised by their {@code GIF87a} / {@code GIF89a} header. */
	public boolean isGif(byte[] data) {
		return startsWith(data, GIF_MAGIC_87) || startsWith(data, GIF_MAGIC_89);
	}

	private static boolean startsWith(byte[] data, byte[] magic) {
		if (data == null || data.length < magic.length) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if (data[i] != magic[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return {@code null} on success, otherwise a short user-facing error
	 */
	public String save(String groupId, String imageId, byte[] data) {
		if (!isPng(data)) {
			return "That file is not a PNG image.";
		}
		if (data.length > Constants.MAX_IMAGE_BYTES) {
			return "Image is too large (max " + (Constants.MAX_IMAGE_BYTES / 1000) + " KB).";
		}
		try {
			Path groupDir = this.root.resolve(safe(groupId));
			Files.createDirectories(groupDir);
			Files.write(groupDir.resolve(safe(imageId) + ".png"), data);
			this.groupByImage.put(imageId, groupId);
			this.cache.put(imageId, data);
			evictOldest(groupDir);
			return null;
		} catch (IOException exception) {
			this.logger.warn("[groupchat] failed to store image", exception);
			return "Server could not store that image.";
		}
	}

	public byte[] load(String imageId) {
		byte[] cached = this.cache.get(imageId);
		if (cached != null) {
			return cached;
		}
		String groupId = this.groupByImage.get(imageId);
		if (groupId == null) {
			return null;
		}
		Path file = this.root.resolve(safe(groupId)).resolve(safe(imageId) + ".png");
		try {
			byte[] data = Files.readAllBytes(file);
			this.cache.put(imageId, data);
			return data;
		} catch (IOException exception) {
			return null;
		}
	}

	public String groupOf(String imageId) {
		return this.groupByImage.get(imageId);
	}

	/**
	 * Stores an animated GIF.
	 *
	 * @return {@code null} on success, otherwise a user-facing error
	 */
	public String saveGif(String groupId, String gifId, byte[] data) {
		if (!isGif(data)) {
			return "That file is not a GIF.";
		}
		if (data.length > Constants.MAX_GIF_BYTES) {
			return "GIF is too large (max " + (Constants.MAX_GIF_BYTES / 1000) + " KB).";
		}
		try {
			Path groupDir = this.root.resolve(safe(groupId));
			Files.createDirectories(groupDir);
			Files.write(groupDir.resolve(safe(gifId) + ".gif"), data);
			this.groupByGif.put(gifId, groupId);
			this.cache.put(gifId, data);
			evictOldestGifs(groupDir);
			return null;
		} catch (IOException exception) {
			this.logger.warn("[groupchat] failed to store gif", exception);
			return "Server could not store that GIF.";
		}
	}

	public byte[] loadGif(String gifId) {
		byte[] cached = this.cache.get(gifId);
		if (cached != null) {
			return cached;
		}
		String groupId = this.groupByGif.get(gifId);
		if (groupId == null) {
			return null;
		}
		Path file = this.root.resolve(safe(groupId)).resolve(safe(gifId) + ".gif");
		try {
			byte[] data = Files.readAllBytes(file);
			this.cache.put(gifId, data);
			return data;
		} catch (IOException exception) {
			return null;
		}
	}

	/** The group that owns a GIF, used to check that the requester is a member. */
	public String groupOfGif(String gifId) {
		return this.groupByGif.get(gifId);
	}

	private void evictOldestGifs(Path groupDir) {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(groupDir, "*.gif")) {
			List<Path> all = new ArrayList<>();
			for (Path file : files) {
				all.add(file);
			}
			if (all.size() <= Constants.MAX_GIFS_PER_GROUP) {
				return;
			}
			all.sort(Comparator.comparingLong(path -> path.toFile().lastModified()));
			int excess = all.size() - Constants.MAX_GIFS_PER_GROUP;
			for (int i = 0; i < excess; i++) {
				Path victim = all.get(i);
				String id = fileNameToId(victim.getFileName().toString());
				this.groupByGif.remove(id);
				this.cache.remove(id);
				Files.deleteIfExists(victim);
			}
		} catch (IOException exception) {
			this.logger.warn("[groupchat] failed to prune gifs", exception);
		}
	}

	private void evictOldest(Path groupDir) {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(groupDir, "*.png")) {
			List<Path> all = new ArrayList<>();
			for (Path file : files) {
				all.add(file);
			}
			if (all.size() <= Constants.MAX_IMAGES_PER_GROUP) {
				return;
			}
			all.sort(Comparator.comparingLong(path -> path.toFile().lastModified()));
			int excess = all.size() - Constants.MAX_IMAGES_PER_GROUP;
			for (int i = 0; i < excess; i++) {
				Path victim = all.get(i);
				this.groupByImage.remove(fileNameToId(victim.getFileName().toString()));
				this.cache.remove(fileNameToId(victim.getFileName().toString()));
				Files.deleteIfExists(victim);
			}
		} catch (IOException exception) {
			this.logger.warn("[groupchat] failed to prune images", exception);
		}
	}

	/** Strips everything that could escape the image directory. */
	private static String safe(String value) {
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length() && builder.length() < 64; i++) {
			char character = value.charAt(i);
			if (Character.isLetterOrDigit(character) || character == '-' || character == '_') {
				builder.append(character);
			}
		}
		return builder.length() == 0 ? "invalid" : builder.toString();
	}

	private static String fileNameToId(String fileName) {
		return fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
	}
}
