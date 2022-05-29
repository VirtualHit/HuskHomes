package net.william278.huskhomes;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import net.william278.huskhomes.cache.Cache;
import net.william278.huskhomes.command.*;
import net.william278.huskhomes.config.Locales;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.data.Database;
import net.william278.huskhomes.data.MySqlDatabase;
import net.william278.huskhomes.data.SqLiteDatabase;
import net.william278.huskhomes.listener.BukkitEventProcessor;
import net.william278.huskhomes.messenger.NetworkMessenger;
import net.william278.huskhomes.messenger.PluginMessenger;
import net.william278.huskhomes.messenger.RedisMessenger;
import net.william278.huskhomes.player.Player;
import net.william278.huskhomes.player.BukkitPlayer;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.Server;
import net.william278.huskhomes.position.SavedPositionManager;
import net.william278.huskhomes.teleport.BukkitTeleportManager;
import net.william278.huskhomes.teleport.TeleportManager;
import net.william278.huskhomes.util.BukkitLogger;
import net.william278.huskhomes.util.BukkitResourceReader;
import net.william278.huskhomes.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class HuskHomesBukkit extends JavaPlugin implements HuskHomes {

    private static HuskHomesBukkit instance;

    public static HuskHomesBukkit getInstance() {
        return instance;
    }

    private Settings settings;
    private Locales locales;
    private BukkitLogger logger;
    private BukkitResourceReader resourceReader;
    private Database database;
    private Cache cache;
    private TeleportManager teleportManager;
    private SavedPositionManager savedPositionManager;
    @Nullable
    private NetworkMessenger networkMessenger;

    @Nullable
    private Server server;

    /**
     * Returns the {@link Server} the plugin is on
     *
     * @param player {@link Player} to request the server
     * @return The {@link Server} object
     */
    @Override
    public CompletableFuture<Server> getServer(@NotNull Player player) {
        if (server != null) {
            return CompletableFuture.supplyAsync(() -> server);
        }
        if (!getSettings().getBooleanValue(Settings.ConfigOption.ENABLE_PROXY_MODE)) {
            server = new Server("server");
            return CompletableFuture.supplyAsync(() -> server);
        }
        assert networkMessenger != null;
        return networkMessenger.getServerName(player).thenApplyAsync(server -> {
            this.server = new Server(server);
            return this.server;
        });
    }

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        // Set the logging and resource reading adapter
        logger = new BukkitLogger(getLogger());
        resourceReader = new BukkitResourceReader(this);

        // Load plugin settings and messages
        loadConfigData();

        // Initialize the network messenger
        if (getSettings().getBooleanValue(Settings.ConfigOption.ENABLE_PROXY_MODE)) {
            networkMessenger = switch (settings.getStringValue(Settings.ConfigOption.MESSENGER_TYPE).toUpperCase()) {
                case "PLUGIN_MESSAGE", "PLUGINMESSAGE" -> new PluginMessenger();
                case "REDIS", "JEDIS" -> new RedisMessenger();
                default -> null;
            };
            if (networkMessenger == null) {
                getLoggingAdapter().log(Level.SEVERE, "Invalid network messenger type specified; disabling!");
                setEnabled(false);
                return;
            }
            networkMessenger.initialize(this);
        }

        // Initialize the database
        database = switch (settings.getStringValue(Settings.ConfigOption.DATA_STORAGE_TYPE).toUpperCase()) {
            case "MYSQL" -> new MySqlDatabase(settings, logger, resourceReader);
            case "SQLITE" -> new SqLiteDatabase(settings, logger, resourceReader);
            default -> null;
        };
        if (database == null) {
            getLoggingAdapter().log(Level.SEVERE, "Invalid database type specified; disabling!");
            setEnabled(false);
            return;
        }
        database.initialize().thenRun(() -> {
            // Initialize the cache
            cache = new Cache();
            cache.initialize(database);
        }).thenRun(() -> {
            // Prepare the teleport manager
            this.teleportManager = new BukkitTeleportManager(this);

            // Prepare the home and warp position manager
            this.savedPositionManager = new SavedPositionManager(database, cache);

            // Register commands - todo add all here
            final CommandBase[] commands = new CommandBase[]{
                    new HomeCommand(this), new SetHomeCommand(this),
                    new HomeListCommand(this), new BackCommand(this)};
            for (CommandBase commandBase : commands) {
                final PluginCommand pluginCommand = getCommand(commandBase.command);
                if (pluginCommand != null) {
                    new BukkitCommand(commandBase, this).register(pluginCommand);
                }
            }

            // Register listener
            getServer().getPluginManager().registerEvents(new BukkitEventProcessor(this), this);
        });
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.terminate();
        }
        if (networkMessenger != null) {
            networkMessenger.terminate();
        }
    }

    @Override
    public Logger getLoggingAdapter() {
        return logger;
    }

    @Override
    public List<Player> getOnlinePlayers() {
        return Bukkit.getOnlinePlayers().stream().map(
                player -> (Player) BukkitPlayer.adapt(player)).toList();
    }

    @Override
    public Settings getSettings() {
        return settings;
    }

    @Override
    public Locales getLocales() {
        return locales;
    }

    @Override
    public Database getDatabase() {
        return database;
    }

    @Override
    public Cache getCache() {
        return cache;
    }

    @Override
    public TeleportManager getTeleportManager() {
        return teleportManager;
    }

    @Override
    public SavedPositionManager getSettingManager() {
        return savedPositionManager;
    }

    @Override
    public @Nullable NetworkMessenger getNetworkMessenger() {
        return networkMessenger;
    }

    @Override
    public boolean isValidPositionOnServer(Position position) {
        final Optional<Location> adaptedLocation = BukkitAdapter.adaptLocation(position);
        if (adaptedLocation.isEmpty()) {
            return false;
        }
        final Location location = adaptedLocation.get();
        assert location.getWorld() != null;
        return location.getWorld().getWorldBorder().isInside(location);
    }

    // Load from the plugin config
    public void loadConfigData() {
        try {
            settings = Settings.load(YamlDocument.create(new File(getDataFolder(), "config.yml"),
                    Objects.requireNonNull(resourceReader.getResource("config.yml")),
                    GeneralSettings.builder().setUseDefaults(false).build(),
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.builder().setEncoding(DumperSettings.Encoding.UNICODE).build(),
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("config_version")).build()));
            locales = Locales.load(YamlDocument.create(new File(getDataFolder(),
                            "messages-" + settings.getStringValue(Settings.ConfigOption.LANGUAGE) + ".yml"),
                    Objects.requireNonNull(resourceReader.getResource(
                            "languages/" + settings.getStringValue(Settings.ConfigOption.LANGUAGE) + ".yml"))));
        } catch (IOException | NullPointerException e) {
            getLoggingAdapter().log(Level.SEVERE, "Failed to load data from the config", e);
        }
    }

    @Override
    public String getPluginVersion() {
        return getDescription().getVersion();
    }

    @Override
    public String getPlatformType() {
        return getServer().getName();
    }

    public static final class BukkitAdapter {

        public static Optional<Location> adaptLocation(net.william278.huskhomes.position.Location location) {
            World world = Bukkit.getWorld(location.world.name);
            if (world == null) {
                world = Bukkit.getWorld(location.world.uuid);
            }
            if (world == null) {
                return Optional.empty();
            }
            return Optional.of(new Location(world, location.x, location.y, location.z,
                    location.yaw, location.pitch));
        }

        @Nullable
        public static net.william278.huskhomes.position.Location adaptLocation(@NotNull Location location) {
            if (location.getWorld() == null) return null;
            return new net.william278.huskhomes.position.Location(location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(),
                    new net.william278.huskhomes.position.World(location.getWorld().getName(), location.getWorld().getUID()));
        }

    }

}
