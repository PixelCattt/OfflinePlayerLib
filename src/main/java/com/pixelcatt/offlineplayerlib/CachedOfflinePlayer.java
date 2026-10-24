package com.pixelcatt.offlineplayerlib;

import org.bukkit.OfflinePlayer;

import java.util.UUID;


public class CachedOfflinePlayer {

    private final String name;
    private final UUID uuid;
    private final OfflinePlayer offlinePlayer;

    public CachedOfflinePlayer(String name, UUID uuid, OfflinePlayer offlinePlayer) {
        this.name = name;
        this.uuid = uuid;
        this.offlinePlayer = offlinePlayer;
    }

    public String getName() {
        return name;
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public OfflinePlayer getOfflinePlayer() {
        return offlinePlayer;
    }
}
