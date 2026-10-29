package com.pixelcatt.offlineplayerlib;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


public class PlayerResolver {

    private record Context(UUID knownUuid, boolean libreLogin, boolean floodgate,
                           boolean onlineMode, String floodgatePrefix, File libreLoginFolder) { }
    private record Lookup(UUID uuid, boolean definitivelyNotFound) { }

    private static final java.util.regex.Pattern FLOODGATE_PREFIX_PATTERN = java.util.regex.Pattern
            .compile("(?m)^\\s*username-prefix\\s*:\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s#]*))");
    private static final java.util.regex.Pattern LIBRELOGIN_CREATOR_PATTERN = java.util.regex.Pattern
            .compile("(?m)^\\s*new-uuid-creator\\s*=\\s*([A-Za-z]+)");

    private final Plugin plugin;

    public PlayerResolver(Plugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<UUID> resolveAsync(String name) {
        CompletableFuture<Context> context = new CompletableFuture<>();
        Runnable snapshot = () -> {
            try {
                Plugin libreLogin = Bukkit.getPluginManager().getPlugin("LibreLogin");
                boolean libre = libreLogin != null && libreLogin.isEnabled();
                boolean floodgate = Bukkit.getPluginManager().isPluginEnabled("floodgate");

                OfflinePlayer known = Bukkit.getPlayerExact(name);
                if (known == null) known = Bukkit.getOfflinePlayerIfCached(name);
                UUID knownUuid = known != null ? known.getUniqueId() : null;

                context.complete(new Context(
                        knownUuid, libre, floodgate, Bukkit.getOnlineMode(),
                        floodgate ? getFloodgatePrefix() : null,
                        libre ? libreLogin.getDataFolder() : null));
            } catch (RuntimeException e) {
                context.completeExceptionally(e);
            }
        };
        if (Bukkit.isPrimaryThread()) snapshot.run();
        else Bukkit.getScheduler().runTask(plugin, snapshot);

        return context.thenApplyAsync(info -> resolve(name, info));
    }

    public String stripFloodgatePrefix(String name) {
        String prefix = getFloodgatePrefix();
        return prefix != null && !prefix.isEmpty() && name.startsWith(prefix)
                ? name.substring(prefix.length()) : name;
    }

    public String getFloodgatePrefix() {
        String prefix = readFloodgatePrefix();
        if (prefix == null && Bukkit.getPluginManager().getPlugin("floodgate") != null) {
            plugin.getLogger().warning("Could not determine the Floodgate username prefix from its API or config.yml.");
        }
        return prefix;
    }

    private UUID resolve(String name, Context info) {
        if (info.knownUuid() != null) return info.knownUuid();

        String prefix = info.floodgatePrefix();
        boolean prefixedBedrockName = info.floodgate() && prefix != null && !prefix.isEmpty() && name.startsWith(prefix);
        if (info.floodgate() && prefix != null) {
            Lookup bedrock = lookupGeyserBedrockUuid(name, prefix);
            if (bedrock.uuid() != null) return bedrock.uuid();
            if (prefixedBedrockName) {
                plugin.getLogger().warning("Could not resolve Floodgate UUID for " + name + "; refusing to guess a different identity.");
                return null;
            }
        }

        if (info.libreLogin()) {
            String creator = readLibreLoginUuidCreator(info.libreLoginFolder());
            if ("CRACKED".equals(creator)) return offlineUuid(name);
            if ("MOJANG".equals(creator)) {
                Lookup lookup = lookupMojangUuid(name);
                if (lookup.uuid() != null) return lookup.uuid();
                return lookup.definitivelyNotFound() ? offlineUuid(name) : null;
            }
            plugin.getLogger().warning("LibreLogin uses UUID creator '" + creator
                    + "'; this UUID cannot be predicted for a player who has never joined.");
            return null;
        }

        if (!info.onlineMode()) return offlineUuid(name);
        return getUUID(name);
    }

    private Lookup lookupGeyserBedrockUuid(String name, String prefix) {
        HttpURLConnection http = null;
        try {
            String encodedName = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
            String encodedPrefix = java.net.URLEncoder.encode(prefix == null ? "" : prefix, StandardCharsets.UTF_8);
            URL url = new URL("https://api.geysermc.org/v2/utils/uuid/bedrock_or_java/" + encodedName
                    + "?prefix=" + encodedPrefix);
            http = (HttpURLConnection) url.openConnection();
            http.setRequestMethod("GET");
            http.setConnectTimeout(3000);
            http.setReadTimeout(3000);
            int status = http.getResponseCode();
            if (status == 204) return new Lookup(null, true);
            if (status != 200) {
                plugin.getLogger().warning("Geyser did not return a Bedrock UUID for " + name + " (HTTP " + status + "); trying the configured Java UUID mode.");
                return new Lookup(null, true);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String returnedName = json.get("name").getAsString();
                if (prefix != null && !prefix.isEmpty() && returnedName.startsWith(prefix)) {
                    return new Lookup(parseUndashedUuid(json.get("id").getAsString()), false);
                }
                return new Lookup(null, true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Geyser Bedrock UUID lookup failed for " + name + "; trying the configured Java UUID mode: " + e.getMessage());
            return new Lookup(null, true);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private Lookup lookupMojangUuid(String name) {
        HttpURLConnection http = null;
        try {
            String encodedName = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
            http = (HttpURLConnection) new URL("https://api.mojang.com/users/profiles/minecraft/" + encodedName).openConnection();
            http.setRequestMethod("GET");
            http.setConnectTimeout(3000);
            http.setReadTimeout(3000);
            int status = http.getResponseCode();
            if (status == 204 || status == 404) return new Lookup(null, true);
            if (status != 200) return new Lookup(null, false);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                return new Lookup(parseUndashedUuid(json.get("id").getAsString()), false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Mojang UUID lookup failed for " + name + ": " + e.getMessage());
            return new Lookup(null, false);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private String readLibreLoginUuidCreator(File libreLoginFolder) {
        if (libreLoginFolder == null) return "UNKNOWN";
        File config = new File(libreLoginFolder, "config.conf");
        try {
            String contents = java.nio.file.Files.readString(config.toPath(), StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = LIBRELOGIN_CREATOR_PATTERN.matcher(contents);
            return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not read LibreLogin UUID creator setting: " + e.getMessage());
            return "UNKNOWN";
        }
    }

    private static String readFloodgatePrefix() {
        try {
            Class<?> apiType = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiType.getMethod("getInstance").invoke(null);
            if (api != null) {
                Object prefix = apiType.getMethod("getPlayerPrefix").invoke(api);
                if (prefix instanceof String s) return s;
            }
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {}

        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null) return null;
        try {
            String contents = java.nio.file.Files.readString(
                    new File(floodgate.getDataFolder(), "config.yml").toPath(), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = FLOODGATE_PREFIX_PATTERN.matcher(contents);
            if (m.find()) {
                for (int i = 1; i <= 3; i++) {
                    if (m.group(i) != null) return m.group(i);
                }
            }
        } catch (java.io.IOException ignored) {}

        return null;
    }

    private static UUID parseUndashedUuid(String id) {
        if (id.length() != 32) throw new IllegalArgumentException("Unexpected UUID format");
        return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20));
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID getUUID(String name) {
        HttpURLConnection connection = null;
        try {
            String prefix = readFloodgatePrefix();
            String encodedName = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
            URL url;
            if (prefix != null && !prefix.isEmpty() && name.startsWith(prefix)) {
                String encodedPrefix = java.net.URLEncoder.encode(prefix, StandardCharsets.UTF_8);
                url = new URL("https://api.geysermc.org/v2/utils/uuid/bedrock_or_java/" + encodedName
                        + "?prefix=" + encodedPrefix);
            } else {
                url = new URL("https://api.mojang.com/users/profiles/minecraft/" + encodedName);
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            if (connection.getResponseCode() != 200) return null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                return parseUndashedUuid(json.get("id").getAsString());
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
