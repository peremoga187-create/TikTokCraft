package com.ttcraft.tiktok;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TikTokFeedService {
    private static final Logger LOG = LoggerFactory.getLogger("TikTokCraft");
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "TikTokCraft-Net");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);
    private static volatile List<TikTokVideo> cachedFeed = List.of();

    public static List<TikTokVideo> getCachedFeed() { return cachedFeed; }

    public static void fetchFeedAsync(String query, Consumer<List<TikTokVideo>> onOk, Consumer<String> onErr) {
        if (!FETCHING.compareAndSet(false, true)) return;
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
                    onErr.accept("Пустой ответ от TikTok");
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

        String endpoint;
        String formBody;
        if (isUrl) {
            endpoint = "https://www.tikwm.com/api/";
            formBody = "url=" + enc(q) + "&hd=1";
        } else if (isUser) {
            endpoint = "https://www.tikwm.com/api/user/posts";
            formBody = "unique_id=" + enc(q) + "&count=12&cursor=0";
        } else if (isSearch) {
            endpoint = "https://www.tikwm.com/api/feed/search";
            formBody = "keywords=" + enc(q) + "&count=12&cursor=0";
        } else {
            endpoint = "https://www.tikwm.com/api/feed/list";
            formBody = "region=UA&count=12";
        }

        try {
            List<TikTokVideo> out = requestTikwmPost(endpoint, formBody, isUrl);
            if (!out.isEmpty()) return out;
        } catch (Exception e) {
            LOG.warn("tikwm POST failed ({}), trying fallback...", e.getMessage());
        }

        // Запасной канал: напрямую с сайта TikTok (без tikwm.com)
        if (!isSearch) {
            String directUrl = isUrl ? q : (isUser ? "https://www.tiktok.com/" + q : "https://www.tiktok.com/explore");
            List<TikTokVideo> direct = fetchDirectFromTikTok(directUrl);
            if (!direct.isEmpty()) return direct;
        }

        Thread.sleep(1300);
        if (!isUrl && !isUser && !isSearch) {
            return requestTikwmPost("https://www.tikwm.com/api/feed/search", "keywords=trending&count=12&cursor=0", false);
        }
        return requestTikwmPost(endpoint, formBody, isUrl);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static List<TikTokVideo> requestTikwmPost(String url, String formBody, boolean isUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Origin", "https://www.tikwm.com")
                .header("Referer", "https://www.tikwm.com/")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", "current_language=en")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.info("tikwm POST {} -> HTTP {}", url, resp.statusCode());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
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
        LOG.info("tikwm parsed {} videos", out.size());
        return out;
    }

    private static List<TikTokVideo> fetchDirectFromTikTok(String url) {
        List<TikTokVideo> out = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            LOG.info("Direct TikTok {} -> HTTP {}", url, resp.statusCode());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return out;

            StringBuilder cookieSb = new StringBuilder();
            for (String sc : resp.headers().allValues("set-cookie")) {
                int semi = sc.indexOf(';');
                String pair = semi > 0 ? sc.substring(0, semi) : sc;
                if (!pair.isBlank()) cookieSb.append(pair).append("; ");
            }
            String cookies = cookieSb.toString();

            Matcher m = Pattern.compile("<script[^>]*id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(resp.body());
            if (!m.find()) return out;
            JsonElement root = JsonParser.parseString(m.group(1));
            Set<String> seenIds = new HashSet<>();
            collectDirectVideos(root, cookies, seenIds, out);
            LOG.info("Direct TikTok parsed {} videos", out.size());
        } catch (Exception e) {
            LOG.warn("Direct TikTok error: {}", e.toString());
        }
        return out;
    }

    private static void collectDirectVideos(JsonElement el, String cookies, Set<String> seen, List<TikTokVideo> out) {
        if (el == null || el.isJsonNull() || out.size() >= 15) return;
        if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) collectDirectVideos(item, cookies, seen, out);
            return;
        }
        if (!el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        if (o.has("id") && o.has("video") && o.get("video").isJsonObject()) {
            JsonObject vid = o.getAsJsonObject("video");
            String play = str(vid, "playAddr");
            if (play.isEmpty()) play = str(vid, "downloadAddr");
            String id = str(o, "id");
            if (!play.isEmpty() && !id.isEmpty() && seen.add(id)) {
                String author = "tiktok";
                if (o.has("author") && o.get("author").isJsonObject()) {
                    String uid = str(o.getAsJsonObject("author"), "uniqueId");
                    if (!uid.isEmpty()) author = "@" + uid;
                }
                long likes = 0;
                if (o.has("stats") && o.get("stats").isJsonObject()) {
                    JsonObject st = o.getAsJsonObject("stats");
                    if (st.has("diggCount") && !st.get("diggCount").isJsonNull()) likes = st.get("diggCount").getAsLong();
                }
                TikTokVideo v = new TikTokVideo(id, author, str(o, "desc"), play, likes);
                v.cookieHeader = cookies;
                out.add(v);
                return;
            }
        }
        for (var entry : o.entrySet()) collectDirectVideos(entry.getValue(), cookies, seen, out);
    }

    private static TikTokVideo parseItem(JsonObject o) {
        if (o.has("images") && o.get("images").isJsonArray() && !o.get("images").getAsJsonArray().isEmpty()) return null;
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
                Path tmp = dir.resolve(safeId + ".part");
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(video.playUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", video.cookieHeader != null ? DESKTOP_UA : UA)
                        .header("Referer", "https://www.tiktok.com/");
                if (video.cookieHeader != null && !video.cookieHeader.isBlank()) {
                    rb.header("Cookie", video.cookieHeader);
                }
                HttpResponse<InputStream> resp = HTTP.send(rb.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                String ct = resp.headers().firstValue("content-type").orElse("");
                if (resp.statusCode() >= 200 && resp.statusCode() < 300 && !ct.contains("text/") && !ct.contains("json")) {
                    try (InputStream in = resp.body()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
                    Files.move(tmp, finalFile, StandardCopyOption.REPLACE_EXISTING);
                    video.localPath = finalFile.toAbsolutePath().toString();
                    trimCache(dir);
                } else {
                    video.errorMsg = "HTTP " + resp.statusCode();
                    video.failed = true;
                }
            } catch (Exception e) {
                video.errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                video.failed = true;
            } finally { video.downloading = false; }
        });
    }

    private static void saveFeedToDisk(List<TikTokVideo> list) {
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("tiktok_cache").resolve("last_feed.json");
            Files.createDirectories(f.getParent());
            JsonArray arr = new JsonArray();
            for (TikTokVideo v : list) {
                if (v.cookieHeader != null && !v.cookieHeader.isEmpty()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", v.id); o.addProperty("author", v.author);
                o.addProperty("title", v.title); o.addProperty("playUrl", v.playUrl); o.addProperty("likes", v.likes);
                arr.add(o);
            }
            if (!arr.isEmpty()) Files.writeString(f, arr.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public static List<TikTokVideo> loadFeedFromDisk() {
        List<TikTokVideo> out = new ArrayList<>();
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("tiktok_cache");
            Path f = dir.resolve("last_feed.json");
            if (Files.exists(f)) {
                JsonArray arr = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    out.add(new TikTokVideo(str(o, "id"), str(o, "author"), str(o, "title"), str(o, "playUrl"),
                            o.has("likes") ? o.get("likes").getAsLong() : 0));
                }
            }
            if (out.isEmpty() && Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    for (Path p : s.filter(x -> x.toString().endsWith(".mp4")).toList()) {
                        TikTokVideo v = new TikTokVideo(p.getFileName().toString(), "@cache", "Кэшированное видео", "", 0);
                        v.localPath = p.toAbsolutePath().toString();
                        out.add(v);
                    }
                }
            }
            if (!out.isEmpty()) cachedFeed = out;
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
