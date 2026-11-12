package com.example.groupchat.client.media;

import com.example.groupchat.client.GroupChatClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client for Tenor GIF search.
 *
 * <p>Two sources are supported:
 *
 * <ul>
 *   <li>the official API at {@code https://<host>/v2/search}, which needs an API key that the
 *       player supplies. Both {@code tenor.googleapis.com} and Giphy's Tenor-compatible
 *       {@code api.giphy.com} speak the same request and response shape.</li>
 *   <li>the public {@code tenor.com/search/<query>} page, whose embedded state contains the very
 *       same {@code media_formats} objects. This is used when no API key is configured, so the
 *       feature works without signing up for anything.</li>
 * </ul>
 *
 * <p>Tenor stopped accepting new API clients in January 2026, so the fallback keeps the feature
 * usable for players who never got a key.
 */
public final class TenorClient {
	/** Tenor-compatible API hosts. */
	public static final String TENOR_HOST = "tenor.googleapis.com";
	public static final String GIPHY_HOST = "api.giphy.com";

	private static final String CLIENT_KEY = "groupchat-mod";
	private static final int TIMEOUT_SECONDS = 20;
	private static final int MAX_RESULTS = 24;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private TenorClient() {
	}

	/** One search hit. */
	public record Gif(String id, String title, String gifUrl, String previewUrl, String smallGifUrl) {
	}

	/**
	 * Searches for GIFs.
	 *
	 * @param host   API host, see {@link #TENOR_HOST} and {@link #GIPHY_HOST}
	 * @param apiKey the player's API key, or blank to use the public search page
	 * @param query  what to search for
	 * @throws IOException when neither source can answer
	 */
	public static List<Gif> search(String host, String apiKey, String query) throws IOException {
		if (apiKey != null && !apiKey.isBlank()) {
			try {
				return searchApi(host, apiKey, query);
			} catch (IOException exception) {
				GroupChatClient.LOGGER.warn("[groupchat] GIF API search failed ({}), falling back to the public page",
						exception.getMessage());
			}
		}
		return searchPage(query);
	}

	/** Downloads a GIF, refusing anything bigger than {@code maxBytes}. */
	public static byte[] download(String url, int maxBytes) throws IOException {
		HttpResponse<byte[]> response = send(HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", browserUserAgent())
				.GET()
				.build(), HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + response.statusCode());
		}
		byte[] data = response.body();
		if (data.length > maxBytes) {
			throw new IOException("GIF is too big (" + (data.length / 1000) + " KB)");
		}
		return data;
	}

	// ------------------------------------------------------------------- sources

	private static List<Gif> searchApi(String host, String apiKey, String query) throws IOException {
		String url = "https://" + host + "/v2/search?q=" + encode(query)
				+ "&key=" + encode(apiKey)
				+ "&client_key=" + encode(CLIENT_KEY)
				+ "&limit=" + MAX_RESULTS
				+ "&media_filter=gif,tinygif,gifpreview"
				+ "&contentfilter=low";
		JsonObject root = JsonParser.parseString(get(url)).getAsJsonObject();
		JsonArray results = root.getAsJsonArray("results");
		if (results == null) {
			return List.of();
		}
		return collect(results);
	}

	/**
	 * Reads the result set the public search page embeds in its {@code store-cache} script tag.
	 */
	private static List<Gif> searchPage(String query) throws IOException {
		String path = query.trim().replace(' ', '-');
		String html = get("https://tenor.com/search/" + encodePath(path));
		int open = html.indexOf("<script id=\"store-cache\"");
		if (open < 0) {
			throw new IOException("The public search page changed shape");
		}
		int start = html.indexOf('>', open);
		int end = html.lastIndexOf("</script>");
		if (start < 0 || end < 0 || end <= start) {
			throw new IOException("The public search page changed shape");
		}
		JsonObject cache = JsonParser.parseString(html.substring(start + 1, end)).getAsJsonObject();
		JsonObject universal = cache.getAsJsonObject("universal");
		JsonObject search = universal == null ? null : universal.getAsJsonObject("search");
		if (search == null) {
			throw new IOException("The public search page carried no results");
		}
		for (Map.Entry<String, JsonElement> entry : search.entrySet()) {
			JsonArray results = entry.getValue().getAsJsonObject().getAsJsonArray("results");
			if (results != null && !results.isEmpty()) {
				return collect(results);
			}
		}
		return List.of();
	}

	// ------------------------------------------------------------------ helpers

	private static List<Gif> collect(JsonArray results) {
		List<Gif> gifs = new ArrayList<>();
		for (JsonElement element : results) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject result = element.getAsJsonObject();
			JsonObject formats = result.getAsJsonObject("media_formats");
			if (formats == null) {
				continue;
			}
			String gifUrl = urlOf(formats, "gif");
			String smallGifUrl = urlOf(formats, "tinygif");
			if (gifUrl == null) {
				gifUrl = smallGifUrl;
			}
			if (gifUrl == null) {
				continue;
			}
			if (smallGifUrl == null) {
				smallGifUrl = gifUrl;
			}
			String preview = urlOf(formats, "gifpreview");
			if (preview == null) {
				preview = smallGifUrl;
			}
			gifs.add(new Gif(
					result.has("id") ? result.get("id").getAsString() : "",
					result.has("title") ? result.get("title").getAsString() : "",
					gifUrl,
					preview,
					smallGifUrl));
			if (gifs.size() >= MAX_RESULTS) {
				break;
			}
		}
		return gifs;
	}

	private static String urlOf(JsonObject formats, String name) {
		JsonObject format = formats.getAsJsonObject(name);
		if (format == null || !format.has("url")) {
			return null;
		}
		String url = format.get("url").getAsString();
		return url.isBlank() ? null : url;
	}

	private static String get(String url) throws IOException {
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", browserUserAgent())
				.header("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
				.header("Accept-Language", "en-US,en;q=0.9")
				.GET()
				.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + response.statusCode());
		}
		return response.body();
	}

	private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return HTTP.send(request, handler);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", exception);
		}
	}

	private static String browserUserAgent() {
		return "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** Keeps slashes readable in a path segment. */
	private static String encodePath(String value) {
		return encode(value).replace("+", "%20");
	}
}
