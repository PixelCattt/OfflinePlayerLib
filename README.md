<div align="center">
  <img src="https://github.com/PixelCattt/OfflinePlayerLib/blob/HEAD/icons/OfflinePlayerLib-250x250.png?raw=true" alt="OfflinePlayerLib-Icon">
  
  <h1>OfflinePlayerLib</h1>
  <a href="https://modrinth.com/plugin/offline_player_lib/versions">
    <img src="https://img.shields.io/modrinth/v/offline_player_lib?style=for-the-badge&label=Version&color=5A00FF">
    <img src="https://img.shields.io/modrinth/dt/offline_player_lib?style=for-the-badge&label=Downloads&color=29A100">
  </a>
</div>


## How Lookups Work
When doing a Lookup, Saved Players in the DB are checked first, then the User Cache and GeyserMC/Floodgate + LibreLogin are used. </br>
A lookup returns with `null` when the Identity cannot be determined.

`GetAllOfflinePlayers` returns a `List<CachedOfflinePlayer>`. </br>
`GetOfflinePlayerByName` and `GetOfflinePlayerByUUID` return a `CompletableFuture<CachedOfflinePlayer>`.

`CachedOfflinePlayer` holds the Name and UUID and provides `getOfflinePlayer()` when a Bukkit Player is needed.

## Using the OfflinePlayer API
Add it as a provided dependency when compiling your Plugin:

```xml
<dependency>
  <groupId>com.pixelcatt.offlineplayerlib</groupId>
  <artifactId>OfflinePlayerLib</artifactId>
  <version>VERSION-HERE</version>
  <scope>provided</scope>
</dependency>
```

Declare OfflinePlayerLib as a dependency in your plugin's `plugin.yml` so it loads first:

```yaml
depend: [OfflinePlayerLib]
```

Import OfflinePlayerLib and the ChachedPlayer Type:

```java
import com.pixelcatt.offlineplayerlib.OfflinePlayerLib;
import com.pixelcatt.offlineplayerlib.CachedOfflinePlayer;
```

Then get the Plugin Instance:

```java
OfflinePlayerLib offlinePlayerLib = (OfflinePlayerLib) getServer().getPluginManager().getPlugin("OfflinePlayerLib");
```

And use these Methods to Lookup Offline Players:

```java
// Look up by Username
offlinePlayerLib.GetOfflinePlayerByName(username).thenAccept(playerByName -> {
    if (playerByName != null) {
        System.out.println(playerByName.getUniqueId());
    }
});

// Look up by UUID
offlinePlayerLib.GetOfflinePlayerByUUID(UUID).thenAccept(playerByUUID -> {
    if (playerByUUID != null) {
        System.out.println(playerByName.getName());
    }
});

// Get a list of All Saved Offline Players
offlinePlayerLib.GetAllOfflinePlayers().thenAccept(players -> {
    players.forEach(player ->
        System.out.println(player.getName() + " - " + player.getUniqueId())
    );
});
```
