package com.ttcraft.tiktok;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TikTokFeedService {
    private static final Logger LOG = LoggerFactory.getLogger("TikTokCraft");
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "TikTokCraft-Net");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);
    private static volatile List<TikTokVideo> cachedFeed = List.of();

    public static List<TikTokVideo> getCachedFeed() { return cachedFeed; }

    public static void fetchFeedAsync(String query, Consumer<List<TikTokVideo>> onOk, Consumer<String> onErr) {
        if (!FETCHING.compareAndSet(false, true)) {
            LOG.info("Feed fetch already in progress, skipping duplicate call");
            return;
        }
        POOL.submit(() -> {
            try {
                List<TikTokVideo> list = fetchFeed(query);
                if (!list.isEmpty()) {
                    cachedFeed = list;
                    saveFeedToDisk(list);
                    onOk.accept(list);
                    return;
                }
                List<TikTokVideo> disk = loadFeedFromDisk();
                if (!disk.isEmpty() && (query == null || query.trim().isEmpty())) {
                    cachedFeed = disk;
                    onOk.accept(disk);
                } else {
                    onErr.accept("Пустой ответ от TikTok API");
                }
            } catch (Exception e) {
                LOG.error("Feed fetch error", e);
                List<TikTokVideo> disk = loadFeedFromDisk();
                if (!disk.isEmpty() && (query == null || query.trim().isEmpty())) {
                    cachedFeed = disk;
                    onOk.accept(disk);
                } else {
                    onErr.accept(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } finally {
                FETCHING.set(false);
            }
        });
    }

    private static List<TikTokVideo> fetchFeed(String query) throws Exception {
        String q = query == null ? "" : query.trim();
        boolean isUrl = q.startsWith("http://") || q.startsWith("https://");
        boolean isUser = !isUrl && q.startsWith("@") && q.length() > 1;
        boolean isSearch = !isUrl && !isUser && !q.isEmpty();

        String apiUrl = isUrl
                ? "https://www.tikwm.com/api/?url=" + enc(q)
                : isUser
                ? "https://www.tikwm.com/api/user/posts?unique_id=" + enc(q) + "&count=10&cursor=0"
                : isSearch
                ? "https://www.tikwm.com/api/feed/search?keywords=" + enc(q) + "&count=10&cursor=0"
                : "https://www.tikwm.com/api/feed/list?region=UA&count=10";

        try {
            List<TikTokVideo> out = requestAndParse(apiUrl, isUrl);
            if (!out.isEmpty()) return out;
        } catch (Exception firstErr) {
            LOG.warn("tikwm attempt 1 failed: {}", firstErr.getMessage());
        }

        Thread.sleep(1300);
        try {
            String fallbackUrl = (!isUrl && !isUser && !isSearch)
                    ? "https://www.tikwm.com/api/feed/list?region=US&count=10" : apiUrl;
            List<TikTokVideo> out = requestAndParse(fallbackUrl, isUrl);
            if (!out.isEmpty()) return out;
        } catch (Exception secondErr) {
            LOG.warn("tikwm attempt 2 failed: {}", secondErr.getMessage());
            if (isUrl) {
                TikTokVideo direct = fetchDirectTikTokPage(q);
                if (direct != null) return List.of(direct);
            }
            throw secondErr;
        }
        return List.of();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String httpGetString(String url, Map<String, String> cookies) throws IOException {
        HttpURLConnection conn = openConn(url, "https://www.tiktok.com/", cookies);
        int code = conn.getResponseCode();
        LOG.info("HTTP {} <- {}", code, url);
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConn(String url, String referer, Map<String, String> cookies) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(current).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            if (referer != null) conn.setRequestProperty("Referer", referer);
            if (cookies != null && !cookies.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                cookies.forEach((k, v) -> sb.append(k).append('=').append(v).append("; "));
                conn.setRequestProperty("Cookie", sb.toString());
            }
            int code = conn.getResponseCode();
            if (cookies != null) {
                List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
                if (setCookies != null) {
                    for (String sc : setCookies) {
                        int eq = sc.indexOf('='), semi = sc.indexOf(';');
                        if (eq > 0) cookies.put(sc.substring(0, eq).trim(),
                                semi > eq ? sc.substring(eq + 1, semi).trim() : sc.substring(eq + 1).trim());
                    }
                }
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Empty redirect");
                current = URI.create(current).resolve(loc).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static List<TikTokVideo> requestAndParse(String apiUrl, boolean isUrl) throws Exception {
        String body = httpGetString(apiUrl, null);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        int code = root.has("code") ? root.get("code").getAsInt() : -1;
        if (code != 0) throw new RuntimeException("API: " + str(root, "msg"));

        List<TikTokVideo> out = new ArrayList<>();
        if (!root.has("data") || root.get("data").isJsonNull()) return out;
        JsonElement data = root.get("data");

        if (isUrl && data.isJsonObject()) {
            TikTokVideo v = parseItem(data.getAsJsonObject());
            if (v != null) out.add(v);
            return out;
        }
        JsonArray arr = data.isJsonArray() ? data.getAsJsonArray()
                : (data.isJsonObject() && data.getAsJsonObject().has("videos") && data.getAsJsonObject().get("videos").isJsonArray())
                ? data.getAsJsonObject().getAsJsonArray("videos") : null;
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                TikTokVideo v = parseItem(el.getAsJsonObject());
                if (v != null) out.add(v);
            }
        }
        LOG.info("Parsed {} videos", out.size());
        return out;
    }

    private static TikTokVideo fetchDirectTikTokPage(String pageUrl) {
        try {
            Map<String, String> cookies = new LinkedHashMap<>();
            String html = httpGetString(pageUrl, cookies);
            Matcher m = Pattern.compile("<script[^>]*id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html);
            if (!m.find()) return null;
            JsonObject root = JsonParser.parseString(m.group(1)).getAsJsonObject();
            JsonObject scope = root.getAsJsonObject("__DEFAULT_SCOPE__");
            JsonObject detail = scope.has("webapp.video-detail")
                    ? scope.getAsJsonObject("webapp.video-detail")
                    : scope.getAsJsonObject("webapp.reflow.video.detail");
            if (detail == null) return null;
            JsonObject item = detail.getAsJsonObject("itemInfo").getAsJsonObject("itemStruct");
            String play = item.getAsJsonObject("video").get("playAddr").getAsString();
            String id = str(item, "id");
            String desc = str(item, "desc");
            String author = "@" + str(item.getAsJsonObject("author"), "uniqueId");
            TikTokVideo v = new TikTokVideo(id, author, desc, play, 0);
            return v;
        } catch (Exception e) {
            LOG.warn("Direct TikTok fallback failed: {}", e.toString());
            return null;
        }
    }

    private static TikTokVideo parseItem(JsonObject o) {
        String play = str(o, "play");
        if (play.isEmpty()) return null;
        if (play.startsWith("//")) play = "https:" + play;
        else if (play.startsWith("/")) play = "https://www.tikwm.com" + play;
        String id = str(o, "video_id");
        if (id.isEmpty()) id = str(o, "id");
        if (id.isEmpty()) id = String.valueOf(Math.abs(play.hashCode()));
        String author = "tiktok";
        if (o.has("author") && o.get("author").isJsonObject()) {
            JsonObject a = o.getAsJsonObject("author");
            String uid = str(a, "unique_id"), nick = str(a, "nickname");
            author = !uid.isEmpty() ? "@" + uid : (!nick.isEmpty() ? nick : "tiktok");
        }
        long likes = o.has("digg_count") && !o.get("digg_count").isJsonNull() ? o.get("digg_count").getAsLong() : 0;
        return new TikTokVideo(id, author, str(o, "title"), play, likes);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    public static void ensureDownloadedAsync(TikTokVideo video) {
        if (video == null || video.localPath != null || video.downloading || video.failed) return;
        video.downloading = true;
        POOL.submit(() -> {
            try {
                Path dir = FabricLoader.getInstance().getGameDir().resolve("tiktok_cache");
                Files.createDirectories(dir);
                String safeId = video.id.replaceAll("[^a-zA-Z0-9_-]", "_");
                Path finalFile = dir.resolve(safeId + ".mp4");
                if (Files.exists(finalFile) && Files.size(finalFile) > 8192) {
                    video.localPath = finalFile.toAbsolutePath().toString();
                    return;
                }
                LOG.info("Downloading {} -> {}", video.id, video.playUrl);
                Path tmp = dir.resolve(safeId + ".part");
                HttpURLConnection conn = openConn(video.playUrl, "https://www.tiktok.com/", null);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (InputStream in = conn.getInputStream()) {
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    } finally { conn.disconnect(); }
                    Files.move(tmp, finalFile, StandardCopyOption.REPLACE_EXISTING);
                    video.localPath = finalFile.toAbsolutePath().toString();
                    LOG.info("Ready {} ({} bytes)", safeId, Files.size(finalFile));
                    trimCache(dir);
                } else {
                    conn.disconnect();
                    video.errorMsg = "HTTP " + code;
                    video.failed = true;
                }
            } catch (Exception e) {
                video.errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                video.failed = true;
                LOG.error("Download error for " + video.id, e);
            } finally { video.downloading = false; }
        });
    }

    private static void saveFeedToDisk(List<TikTokVideo> list) {
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("tiktok_cache").resolve("last_feed.json");
            Files.createDirectories(f.getParent());
            JsonArray arr = new JsonArray();
            for (TikTokVideo v : list) {
                JsonObject o = new JsonObject();
                o.addProperty("id", v.id); o.addProperty("author", v.author);
                o.addProperty("title", v.title); o.addProperty("playUrl", v.playUrl);
                o.addProperty("likes", v.likes);
                arr.add(o);
            }
            Files.writeString(f, arr.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static List<TikTokVideo> loadFeedFromDisk() {
        List<TikTokVideo> out = new ArrayList<>();
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("tiktok_cache").resolve("last_feed.json");
            if (!Files.exists(f)) return out;
            JsonArray arr = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                out.add(new TikTokVideo(str(o, "id"), str(o, "author"), str(o, "title"), str(o, "playUrl"),
                        o.has("likes") ? o.get("likes").getAsLong() : 0));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void trimCache(Path dir) {
        try (var s = Files.list(dir)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".mp4"))
                    .sorted(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0L; }
                    })).toList();
            for (int i = 0; i < files.size() - 20; i++) Files.deleteIfExists(files.get(i));
        } catch (Exception ignored) {}
    }
}
