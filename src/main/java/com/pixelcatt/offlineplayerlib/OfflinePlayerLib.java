package com.pixelcatt.offlineplayerlib;

import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


public class OfflinePlayerLib extends JavaPlugin {

    public OfflinePlayerManager manager;
    private DatabaseWorker dbWorker;
    private PlayerResolver playerUuidResolver;

    private JoinListener joinListener;

    @Override
    public void onEnable() {
        getLogger().info("~ Created by PixelCatt ~");

        // Initialize Database-Worker
        getLogger().info("Initializing Database-Worker...");
        dbWorker = new DatabaseWorker();

        // Initialize Offline-Player-Manager
        getLogger().info("Initializing Offline-Player-Manager...");
        manager = new OfflinePlayerManager(this, dbWorker);
        playerUuidResolver = new PlayerResolver(this);

        // Initialize Event Listeners
        getLogger().info("Initializing Event Listeners...");
        initializeEventListeners();

        // Check for Updates
        getLogger().info("Checking for Updates...");
        checkForUpdates();
    }


    public void initializeEventListeners() {
        joinListener = new JoinListener(this, manager);
        getServer().getPluginManager().registerEvents(joinListener, this);
    }

    public CachedOfflinePlayer GetOfflinePlayerByUUID(UUID uuid) {
        return getOfflinePlayerByUUID(uuid, null);
    }

    public CompletableFuture<CachedOfflinePlayer> GetOfflinePlayerByName(String username) {
        UUID uuid = manager.getUUIDByName(username);
        if (uuid != null) {
            return getOfflinePlayerAsync(uuid, username);
        }
        return playerUuidResolver.resolveAsync(username)
                .thenCompose(resolvedUUID -> getOfflinePlayerAsync(resolvedUUID, username));
    }

    public CompletableFuture<List<CachedOfflinePlayer>> GetAllOfflinePlayers() {
        return manager.getAllPlayers().thenCompose(playersByUuid -> {
            CompletableFuture<List<CachedOfflinePlayer>> players = new CompletableFuture<>();
            Runnable loadPlayers = () -> {
                List<CachedOfflinePlayer> offlinePlayers = new ArrayList<>();
                for (Map.Entry<UUID, String> entry : playersByUuid.entrySet()) {
                    offlinePlayers.add(new CachedOfflinePlayer(entry.getValue(), entry.getKey(),
                            getServer().getOfflinePlayer(entry.getKey())));
                }
                players.complete(offlinePlayers);
            };
            if (Bukkit.isPrimaryThread()) loadPlayers.run();
            else Bukkit.getScheduler().runTask(this, loadPlayers);
            return players;
        });
    }

    private CachedOfflinePlayer getOfflinePlayerByUUID(UUID uuid, String fallbackName) {
        if (uuid == null) return null;
        OfflinePlayer player = getServer().getOfflinePlayer(uuid);
        String name = manager.getNameByUUID(uuid);
        if (name == null) name = fallbackName;
        if (name == null) name = player.getName();
        if (name != null) manager.updatePlayer(player.getUniqueId(), name);
        return new CachedOfflinePlayer(name, uuid, player);
    }

    private CompletableFuture<CachedOfflinePlayer> getOfflinePlayerAsync(UUID uuid, String fallbackName) {
        if (uuid == null) return CompletableFuture.completedFuture(null);
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(getOfflinePlayerByUUID(uuid, fallbackName));
        }

        CompletableFuture<CachedOfflinePlayer> offlinePlayer = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> offlinePlayer.complete(getOfflinePlayerByUUID(uuid, fallbackName)));
        return offlinePlayer;
    }

    private void checkForUpdates() {
        String[] latestVersion = getLatestVersion().split("\\|", 2);
        String currentVersion = getDescription().getVersion();

        if (!"error".equals(latestVersion[0])) {
            if (isNewerVersion(latestVersion[0], currentVersion)) {
                getLogger().warning("A new Version of OfflinePlayerLib is available: " + latestVersion[0]);
                joinListener.setUpdateAvailable(true);
            } else {
                getLogger().info("No new Updates available.");
            }
        } else {
            getLogger().warning("Failed to Check for Updates!\n" + latestVersion[1]);
        }
    }

    public String getLatestVersion() {
        String apiUrl = "https://api.modrinth.com/v2/project/offlineplayerlib/version";

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(8))
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JSONArray jsonArray = new JSONArray(response.body());
                    if (!jsonArray.isEmpty()) {
                        JSONObject latestVersion = jsonArray.getJSONObject(0);
                        return latestVersion.getString("version_number");
                    } else {
                        return "error|No Version Data Found: Project has no Versions on Modrinth";
                    }
                } else {
                    return "error|No Version Data Found: Failed to Connect to Modrinth API";
                }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            getLogger().warning("Failed to check for updates: " + e.getMessage());
            StringWriter stackTrace = new StringWriter();
            e.printStackTrace(new PrintWriter(stackTrace));
            return "error|" + stackTrace;
        }
    }

    public boolean isNewerVersion(String comparingVersion, String referenceVersion) {
        return compareVersions(comparingVersion, referenceVersion) > 0;
    }

    public boolean isOlderVersion(String comparingVersion, String referenceVersion) {
        return compareVersions(comparingVersion, referenceVersion) < 0;
    }

    private int compareVersions(String left, String right) {
        int[] a = versionNumbers(left);
        int[] b = versionNumbers(right);
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(a[i], b[i]);
            if (result != 0) return result;
        }
        boolean aPre = left != null && left.matches(".*-[0-9A-Za-z].*");
        boolean bPre = right != null && right.matches(".*-[0-9A-Za-z].*");
        return aPre == bPre ? 0 : (aPre ? -1 : 1);
    }

    private int[] versionNumbers(String version) {
        int[] numbers = new int[3];
        if (version == null) return numbers;
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matcher(version.trim());
        if (!matcher.matches()) return numbers;
        try {
            for (int i = 0; i < 3; i++) numbers[i] = Integer.parseInt(matcher.group(i + 1));
        } catch (NumberFormatException ignored) { return new int[3]; }
        return numbers;
    }


    // ------------------------------------------------------------ \\


    @Override
    public void onDisable() {
        dbWorker.shutdown();
        manager.close();
    }
}
