# OfflinePlayerLib

OfflinePlayerLib stores Minecraft player UUIDs and names in `players.db` and exposes `CachedOfflinePlayer` identity objects to other plugins.

## Using the API

Run `mvn install` in this project to add OfflinePlayerLib to your local Maven repository, then add it as a provided dependency when compiling your plugin:

```xml
<dependency>
  <groupId>com.pixelcatt.offlineplayerlib</groupId>
  <artifactId>OfflinePlayerLib</artifactId>
  <version>1.0.0</version>
  <scope>provided</scope>
</dependency>
```

Declare OfflinePlayerLib as a dependency in your plugin's `plugin.yml` so it loads first:

```yaml
depend: [OfflinePlayerLib]
```

Then get the plugin instance and call either lookup method:

```java
OfflinePlayerLib offlinePlayerLib = (OfflinePlayerLib) Bukkit.getPluginManager().getPlugin("OfflinePlayerLib");
CachedOfflinePlayer playerByUUID = offlinePlayerLib.GetOfflinePlayerByUUID(uuid);
offlinePlayerLib.GetOfflinePlayerByName(username).thenAccept(playerByName -> {
  if (playerByName != null) {
    System.out.println(playerByName.getUniqueId());
  }
});
offlinePlayerLib.GetAllOfflinePlayers().thenAccept(players -> {
  players.forEach(player -> System.out.println(player.getName() + " " + player.getUniqueId()));
});
```

`GetOfflinePlayerByName` returns a `CompletableFuture<CachedOfflinePlayer>` because unresolved names can require network lookups. It first checks saved player names, then uses the server profile cache, Floodgate/Geyser, LibreLogin, and the server's UUID mode to resolve a UUID. It completes with `null` when the identity cannot be determined. `CachedOfflinePlayer` exposes the database name and UUID and provides `getOfflinePlayer()` when Bukkit player operations are needed.

`GetAllOfflinePlayers` returns a future because its records are read from the database asynchronously. Each result is a `CachedOfflinePlayer` with the stored name and UUID.

## Database

The plugin creates `plugins/OfflinePlayerLib/players.db` with one table:

```sql
CREATE TABLE offline_players (
  UUID TEXT NOT NULL,
  NAME TEXT NOT NULL COLLATE NOCASE,
  PRIMARY KEY (UUID, NAME)
);
```

Player records are updated when a player joins. Existing UUIDs receive the current name; otherwise an existing name receives the current UUID; otherwise a new record is inserted.

## Build

Run `mvn package` to build the plugin jar. Add the resulting jar to the server's `plugins` directory.
