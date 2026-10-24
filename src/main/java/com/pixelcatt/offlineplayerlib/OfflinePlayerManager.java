package com.pixelcatt.offlineplayerlib;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


public class OfflinePlayerManager {

    private final OfflinePlayerLib plugin;
    private final DatabaseWorker dbWorker;

    private final File dbFile;
    private Connection connection;

    public OfflinePlayerManager(OfflinePlayerLib plugin, DatabaseWorker dbWorker) {
        this.plugin = plugin;
        this.dbWorker = dbWorker;
        this.dbFile = new File(plugin.getDataFolder(), "players.db");
        openConnection();
        createTables();
    }


    private void openConnection() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        } catch (Exception e) {
            plugin.getLogger().severe("Could not connect to SQLite database!");
            throw new IllegalStateException("Could not connect to SQLite database", e);
        }
    }

    private void createTables() {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS offline_players (" +
                    "UUID TEXT NOT NULL," +
                    "NAME TEXT NOT NULL COLLATE NOCASE," +
                    "PRIMARY KEY(UUID, NAME))");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables in SQLite database.");
            throw new IllegalStateException("Could not create SQLite tables", e);
        }
    }

    public void updatePlayer(UUID uuid, String name) {
        dbWorker.getDatabaseExecutor().execute(() -> savePlayer(uuid, name));
    }

    private synchronized void savePlayer(UUID uuid, String name) {
        String uuidString = uuid.toString();

        try {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM offline_players WHERE UUID = ? AND NAME = ? COLLATE BINARY LIMIT 1")) {
                ps.setString(1, uuidString);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        connection.commit();
                        return;
                    }
                }
            }

            boolean uuidExists;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM offline_players WHERE UUID = ? LIMIT 1")) {
                ps.setString(1, uuidString);
                try (ResultSet rs = ps.executeQuery()) {
                    uuidExists = rs.next();
                }
            }

            if (uuidExists) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM offline_players WHERE NAME = ? COLLATE NOCASE AND UUID <> ?")) {
                    ps.setString(1, name);
                    ps.setString(2, uuidString);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE offline_players SET NAME = ? WHERE UUID = ?")) {
                    ps.setString(1, name);
                    ps.setString(2, uuidString);
                    ps.executeUpdate();
                }
            } else {
                boolean nameExists;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT 1 FROM offline_players WHERE NAME = ? COLLATE NOCASE LIMIT 1")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        nameExists = rs.next();
                    }
                }

                if (nameExists) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE offline_players SET UUID = ? WHERE NAME = ? COLLATE NOCASE")) {
                        ps.setString(1, uuidString);
                        ps.setString(2, name);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO offline_players(UUID, NAME) VALUES (?, ?)")) {
                        ps.setString(1, uuidString);
                        ps.setString(2, name);
                        ps.executeUpdate();
                    }
                }
            }

            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) { }
            plugin.getLogger().severe("Could not update offline player data: " + e.getMessage());
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not reset SQLite database connection: " + e.getMessage());
            }
        }
    }

    public synchronized UUID getUUIDByName(String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT UUID FROM offline_players WHERE NAME = ? COLLATE NOCASE LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("UUID"));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("Could not look up offline player data: " + e.getMessage());
        }
        return null;
    }

    public synchronized String getNameByUUID(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT NAME FROM offline_players WHERE UUID = ? LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("NAME");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not look up offline player data: " + e.getMessage());
        }
        return null;
    }

    public CompletableFuture<Map<UUID, String>> getAllPlayers() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, String> players = new LinkedHashMap<>();
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT UUID, NAME FROM offline_players ORDER BY NAME COLLATE NOCASE");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            players.put(UUID.fromString(rs.getString("UUID")), rs.getString("NAME"));
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Skipping invalid UUID in offline player data: " + rs.getString("UUID"));
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not list offline player data: " + e.getMessage());
                }
            }
            return players;
        }, dbWorker.getDatabaseExecutor());
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not close SQLite database: " + e.getMessage());
        }
    }
}
