package com.konohaven.heads;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads the minecraft-heads.com custom head list (API v2) and caches it locally.
 * Each head is just a name, a category and the texture hash on textures.minecraft.net.
 */
public final class HeadDatabase {
    private static final String API = "https://minecraft-heads.com/api/";
    private static final String USER_AGENT = "KonoHeads/0.1 (Fabric mod)";
    private static final Gson GSON = new Gson();
    private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("konoheads-cache.json");
    /** Minimum time between manual refreshes. */
    private static final long REFRESH_COOLDOWN_MS = 60_000;
    /** Pause between page requests, to keep load on minecraft-heads.com low. */
    private static final long PAGE_DELAY_MS = 500;
    /** Hard cap on pages per download, in case the API reports a nonsense last_page. */
    private static final int MAX_PAGES = 500;
    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("[0-9a-fA-F]{16,128}");
    private static final java.util.regex.Pattern UUID_RE =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final java.util.regex.Pattern KEY_RE = java.util.regex.Pattern.compile("[A-Za-z0-9._\\-]{1,200}");

    public static boolean validAppUuid(String s) { return UUID_RE.matcher(s.trim()).matches(); }
    public static boolean validApiKey(String s) { return s.isBlank() || KEY_RE.matcher(s.trim()).matches(); }

    public record Head(String name, String hash, int category, String searchKey) {
        static Head of(String name, String hash, int category, String categoryName) {
            return new Head(name, hash, category, (name + " " + categoryName).toLowerCase(Locale.ROOT));
        }
    }

    private record CacheFile(long savedAt, String license, Map<Integer, String> categories, List<String[]> heads) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private volatile List<Head> heads = List.of();
    private volatile Map<Integer, String> categories = Map.of();
    private volatile String license = "unknown";
    private volatile String status = "Not loaded";
    private volatile boolean loading = false;
    private long savedAt = 0;
    private long lastRequestStart = 0;
    private boolean autoAttempted = false;
    private volatile String warnings = "";

    public String warnings() { return warnings; }

    public List<Head> heads() { return heads; }
    public Map<Integer, String> categories() { return categories; }
    public String license() { return license; }
    public String status() { return status; }
    public boolean loading() { return loading; }

    /** Load from disk cache, and download if there is no cache or it is stale. */
    /**
     * Called when the screen opens. Uses the disk cache when possible and only downloads automatically
     * once per game session (minecraft-heads.com terms: request once on init/reload, cache responses).
     */
    public synchronized void ensureLoaded() {
        if (loading || autoAttempted) return;
        autoAttempted = true;
        if (loadCache()) {
            int maxAge = KonoHeads.CONFIG.cacheMaxAgeHours;
            boolean stale = maxAge > 0 && System.currentTimeMillis() - savedAt > maxAge * 3_600_000L;
            if (!stale) return;
        }
        refresh();
    }

    /** Manual reload. Rate limited to once per minute. */
    public synchronized CompletableFuture<Void> refresh() {
        if (loading) return CompletableFuture.completedFuture(null);
        String appUuid = KonoHeads.CONFIG.appUuid;
        if (appUuid.isBlank()) {
            status = "No App UUID set. Use /heads appuuid <uuid>.";
            return CompletableFuture.completedFuture(null);
        }
        if (!validAppUuid(appUuid)) {
            status = "App UUID in config is not a valid UUID.";
            return CompletableFuture.completedFuture(null);
        }
        if (!validApiKey(KonoHeads.CONFIG.apiKey)) {
            status = "API key in config has invalid characters.";
            return CompletableFuture.completedFuture(null);
        }
        long wait = lastRequestStart + REFRESH_COOLDOWN_MS - System.currentTimeMillis();
        if (wait > 0) {
            status = "Please wait " + (wait / 1000 + 1) + "s before refreshing again.";
            return CompletableFuture.completedFuture(null);
        }
        lastRequestStart = System.currentTimeMillis();
        warnings = "";
        loading = true;
        status = "Downloading categories...";
        return CompletableFuture.runAsync(this::download).whenComplete((v, t) -> {
            loading = false;
            if (t != null) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                status = "Download failed: " + cause.getMessage();
                KonoHeads.LOGGER.warn("Head download failed", cause);
            }
        });
    }

    private void download() {
        JsonObject catJson = get("heads/categories");
        Map<Integer, String> cats = new LinkedHashMap<>();
        for (JsonElement e : dataArray(catJson)) {
            JsonObject o = e.getAsJsonObject();
            cats.put(o.get("id").getAsInt(), clean(o.get("n").getAsString()));
        }

        List<Head> result = new ArrayList<>();
        int page = 1;
        int lastPage = 1;
        do {
            if (page > 1) sleep(PAGE_DELAY_MS);
            status = "Downloading heads (page " + page + "/" + lastPage + ")...";
            JsonObject json = get("heads/custom-heads", "page=" + page);
            for (JsonElement e : dataArray(json)) {
                JsonObject o = e.getAsJsonObject();
                if (!o.has("n") || !o.has("u")) continue;
                String hash = o.get("u").getAsString();
                if (!HASH.matcher(hash).matches()) continue; // only accept plain texture hashes
                int c = o.has("c") ? o.get("c").getAsInt() : -1;
                result.add(Head.of(clean(o.get("n").getAsString()), hash, c, cats.getOrDefault(c, "")));
            }
            if (json.has("pagination") && json.get("pagination").isJsonObject()) {
                lastPage = Math.min(MAX_PAGES, json.getAsJsonObject("pagination").get("last_page").getAsInt());
            }
        } while (page++ < lastPage);

        this.categories = cats;
        this.heads = List.copyOf(result);
        this.savedAt = System.currentTimeMillis();
        this.status = result.size() + " heads (license: " + license + ")";
        saveCache();
    }

    private JsonObject get(String route, String... params) {
        StringBuilder url = new StringBuilder(API).append(route)
                .append("?app_uuid=").append(URLEncoder.encode(KonoHeads.CONFIG.appUuid.trim(), StandardCharsets.UTF_8));
        for (String p : params) url.append('&').append(p);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET();
        if (!KonoHeads.CONFIG.apiKey.isBlank()) req.header("api-key", KonoHeads.CONFIG.apiKey.trim());

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> res;
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new RuntimeException("network error: " + e.getMessage(), e);
            }
            if (res.statusCode() == 429 && attempt < 5) {
                long wait = res.headers().firstValueAsLong("Retry-After").orElse(5);
                status = "Rate limited, waiting " + wait + "s...";
                sleep(wait * 1000);
                continue;
            }
            JsonObject json;
            try {
                json = JsonParser.parseString(res.body()).getAsJsonObject();
            } catch (Exception e) {
                throw new RuntimeException("HTTP " + res.statusCode() + " (not JSON)");
            }
            if (json.has("error")) throw new RuntimeException(json.get("error").getAsString());
            if (res.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + res.statusCode());
            if (json.has("meta") && json.get("meta").isJsonObject() && json.getAsJsonObject("meta").has("license")) {
                license = json.getAsJsonObject("meta").get("license").getAsString();
            }
            if (json.has("warnings") && json.get("warnings").isJsonArray()) {
                StringBuilder w = new StringBuilder();
                for (JsonElement e : json.getAsJsonArray("warnings")) {
                    String msg = e.isJsonPrimitive() ? e.getAsString()
                            : e.isJsonObject() && e.getAsJsonObject().has("message") ? e.getAsJsonObject().get("message").getAsString()
                            : e.toString();
                    if (!w.isEmpty()) w.append(" | ");
                    w.append(msg);
                }
                if (!w.isEmpty()) {
                    warnings = clean(w.toString());
                    KonoHeads.LOGGER.warn("minecraft-heads.com API warning: {}", warnings);
                }
            }
            return json;
        }
    }

    /** Strip control characters and cap length of text coming from the API. */
    private static String clean(String s) {
        String t = s.replaceAll("\\p{Cntrl}", "").trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }

    private static JsonArray dataArray(JsonObject json) {
        return json.has("data") && json.get("data").isJsonArray() ? json.getAsJsonArray("data") : new JsonArray();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean loadCache() {
        try {
            if (!Files.exists(CACHE)) return false;
            CacheFile c = GSON.fromJson(Files.readString(CACHE), CacheFile.class);
            if (c == null || c.heads() == null || c.heads().isEmpty()) return false;
            Map<Integer, String> cats = c.categories() != null ? c.categories() : Map.of();
            List<Head> list = new ArrayList<>(c.heads().size());
            for (String[] h : c.heads()) {
                if (h == null || h.length < 3 || !HASH.matcher(h[1]).matches()) continue;
                int cat = Integer.parseInt(h[2]);
                list.add(Head.of(h[0], h[1], cat, cats.getOrDefault(cat, "")));
            }
            this.categories = cats;
            this.heads = List.copyOf(list);
            this.license = c.license() != null ? c.license() : "unknown";
            this.savedAt = c.savedAt();
            this.status = list.size() + " heads (cached, license: " + license + ")";
            return true;
        } catch (Exception e) {
            KonoHeads.LOGGER.warn("Could not read head cache", e);
            return false;
        }
    }

    private void saveCache() {
        try {
            List<String[]> rows = new ArrayList<>(heads.size());
            for (Head h : heads) rows.add(new String[]{h.name(), h.hash(), String.valueOf(h.category())});
            Files.writeString(CACHE, GSON.toJson(new CacheFile(savedAt, license, categories, rows)));
        } catch (Exception e) {
            KonoHeads.LOGGER.warn("Could not write head cache", e);
        }
    }

    /** All heads whose name/category contains every word of the query, optionally limited to one category. */
    public List<Head> search(String query, int categoryId) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Head> out = new ArrayList<>();
        for (Head h : heads) {
            if (categoryId != -1 && h.category() != categoryId) continue;
            boolean ok = true;
            for (String w : words) {
                if (!w.isEmpty() && !h.searchKey().contains(w)) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(h);
        }
        return out;
    }
}
