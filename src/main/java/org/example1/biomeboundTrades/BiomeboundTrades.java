package org.example1.biomeboundTrades;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.example1.biomeboundTrades.command.TradesCommand;
import org.example1.biomeboundTrades.config.MessageManager;
import org.example1.biomeboundTrades.config.PluginConfig;
import org.example1.biomeboundTrades.listener.TradeInflationListener;
import org.example1.biomeboundTrades.listener.VillagerListener;
import org.example1.biomeboundTrades.listener.WanderingTraderListener;
import org.example1.biomeboundTrades.trades.DailyRerollTask;
import org.example1.biomeboundTrades.trades.TradeManager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.Villager;

import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public final class BiomeboundTrades extends JavaPlugin {

    private PluginConfig configData;
    private MessageManager messages;
    private TradeManager tradeManager;
    private DailyRerollTask rerollTask;
    private TradeInflationListener inflationListener;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfigDefaults();
        configData = new PluginConfig();
        configData.load(getConfig(), getLogger());

        messages = new MessageManager(this);

        tradeManager = new TradeManager(this);

        inflationListener = new TradeInflationListener(this);
        getServer().getPluginManager().registerEvents(inflationListener, this);
        getServer().getPluginManager().registerEvents(new VillagerListener(this), this);
        getServer().getPluginManager().registerEvents(new WanderingTraderListener(this), this);

        TradesCommand tradesCommand = new TradesCommand(this);
        Objects.requireNonNull(getCommand("btrades")).setExecutor(tradesCommand);
        Objects.requireNonNull(getCommand("btrades")).setTabCompleter(tradesCommand);

        startRerollTask();
        autosaveTask = getServer().getScheduler().runTaskTimer(this, inflationListener::saveData,
                6000L, 6000L);

        if (configData.dailyReroll().rerollOnStartup) {
            getServer().getScheduler().runTaskLater(this, () -> tradeManager.rerollAll(), 40L);
        }

        getLogger().info("BiomeboundTrades v" + getPluginMeta().getVersion() + " включён.");
    }

    @Override
    public void onDisable() {
        if (rerollTask != null) {
            rerollTask.shutdown();
        }
        if (inflationListener != null) {
            inflationListener.stopGossip();
            inflationListener.restoreActivePrices();
            inflationListener.flushAndCloseData();
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (tradeManager != null) {
            tradeManager.cancelTasks();
        }
        getLogger().info("BiomeboundTrades выключен.");
    }

    public void startRerollTask() {
        if (rerollTask != null) {
            rerollTask.shutdown();
        }
        int intervalTicks = Math.max(1, configData.dailyReroll().checkIntervalSeconds) * 20;
        rerollTask = new DailyRerollTask(this);
        getServer().getPluginManager().registerEvents(rerollTask, this);
        rerollTask.bootstrap();
        rerollTask.runTaskTimer(this, intervalTicks, intervalTicks);
    }

    public void reloadConfigData() {
        reloadConfig();
        migrateConfigDefaults();
        configData.load(getConfig(), getLogger());
        if (messages != null) {
            messages.reload();
        }
        startRerollTask();
    }

    private void migrateConfigDefaults() {
        FileConfiguration cfg = getConfig();
        int version = cfg.getInt("config-version", 0);
        if (version >= 6) {
            return;
        }

        Path configFile = getDataFolder().toPath().resolve("config.yml");
        Path backupFile = getDataFolder().toPath().resolve("config.yml.v" + version + ".bak");
        if (Files.isRegularFile(configFile) && !Files.exists(backupFile)) {
            try {
                Files.copy(configFile, backupFile);
            } catch (IOException ex) {
                getLogger().warning("Не удалось создать резервную копию config.yml: " + ex.getMessage());
                return;
            }
        }

        if (version == 1) {
            updateDefaultInt(cfg, "biome-books.offer.max-uses", 4, 3);
            updateDefaultInt(cfg, "biome-books.offer.special-max-uses-max", 3, 2);
            updateDefaultInt(cfg, "special-offer.purchases-required", 5, 8);
            updateDefaultInt(cfg, "special-offer.discount-percent", 50, 30);
            updateDefaultInt(cfg, "reputation.max-percent", 25, 15);
            updateDefaultInt(cfg, "daily-sale.discount-percent", 40, 25);

            String path = "biome-books.groups.swamp.special";
            List<Map<?, ?>> oldBooks = cfg.getMapList(path);
            List<Map<String, Object>> updatedBooks = new ArrayList<>();
            boolean booksChanged = false;
            for (Map<?, ?> book : oldBooks) {
                Map<String, Object> copy = new LinkedHashMap<>();
                book.forEach((key, value) -> copy.put(String.valueOf(key), value));
                Object enchant = copy.get("enchant");
                Object level = copy.get("level");
                Object price = copy.get("price");
                if (enchant instanceof String name
                        && (name.equalsIgnoreCase("mending") || name.equalsIgnoreCase("minecraft:mending"))
                        && level instanceof Number number && number.intValue() == 1
                        && price instanceof Number numberPrice && numberPrice.intValue() == 20) {
                    copy.put("price", 24);
                    booksChanged = true;
                }
                updatedBooks.add(copy);
            }
            if (booksChanged) {
                cfg.set(path, updatedBooks);
            }
        }

        // The old default made fletchers demand about 46 sticks per emerald
        // instead of vanilla's 32. Restore the default for existing configs,
        // while leaving any custom value untouched.
        updateDefaultInt(cfg, "profession-rebalance.professions.fletcher.buy", 70, 100);

        // Cartographer purchase costs are also kept at vanilla values so paper
        // and glass panes remain the familiar amounts for existing configs.
        updateDefaultInt(cfg, "profession-rebalance.professions.cartographer.buy", 85, 100);

        if (!cfg.isSet("biome-books.special-book-pity-rotations")) {
            cfg.set("biome-books.special-book-pity-rotations", 2);
        }
        if (!cfg.isSet("cursed-dual-books.binding-price-multiplier")) {
            cfg.set("cursed-dual-books.binding-price-multiplier", 0.85);
        }
        if (!cfg.isSet("cursed-dual-books.binding-max-uses")) {
            cfg.set("cursed-dual-books.binding-max-uses", 2);
        }
        if (!cfg.isSet("cursed-dual-books.vanishing-price-multiplier")) {
            cfg.set("cursed-dual-books.vanishing-price-multiplier", 0.65);
        }
        if (!cfg.isSet("cursed-dual-books.vanishing-max-uses")) {
            cfg.set("cursed-dual-books.vanishing-max-uses", 1);
        }
        if (!cfg.isSet("seasonal-stock.enabled")) {
            cfg.set("seasonal-stock.enabled", true);
            cfg.set("seasonal-stock.interval-days", 14);
            cfg.set("seasonal-stock.duration-days", 3);
            cfg.set("seasonal-stock.chance-percent", 50);
            cfg.set("seasonal-stock.bonus-uses", 2);
        }
        if (!cfg.isSet("inflation.region-size")) {
            cfg.set("inflation.region-size", Math.max(32, cfg.getInt("inflation.radius", 16) * 2));
        }
        copyLegacyInt(cfg, "biome-books.offer.mergeable-special-max-uses-min",
                "biome-books.offer.multi-merge-special-max-uses-min", 3);
        copyLegacyInt(cfg, "biome-books.offer.mergeable-special-max-uses-max",
                "biome-books.offer.multi-merge-special-max-uses-max", 4);
        copyLegacyInt(cfg, "biome-books.offer.mergeable-special-restock-min-days",
                "biome-books.offer.multi-merge-special-restock-min-days", 2);
        copyLegacyInt(cfg, "biome-books.offer.mergeable-special-restock-max-days",
                "biome-books.offer.multi-merge-special-restock-max-days", 3);
        setDefault(cfg, "biome-books.offer.single-merge-special-max-uses-min", 2);
        setDefault(cfg, "biome-books.offer.single-merge-special-max-uses-max", 3);
        setDefault(cfg, "biome-books.offer.single-merge-special-restock-min-days", 4);
        setDefault(cfg, "biome-books.offer.single-merge-special-restock-max-days", 5);
        cfg.set("config-version", 6);
        saveConfig();
        getLogger().info("config.yml обновлён до версии 6; предыдущая версия сохранена в "
                + backupFile.getFileName() + ".");
    }

    private void copyLegacyInt(FileConfiguration cfg, String oldPath, String newPath, int fallback) {
        if (!cfg.isSet(newPath)) {
            cfg.set(newPath, cfg.getInt(oldPath, fallback));
        }
    }

    private void setDefault(FileConfiguration cfg, String path, int value) {
        if (!cfg.isSet(path)) {
            cfg.set(path, value);
        }
    }

    private void updateDefaultInt(FileConfiguration cfg, String path, int oldValue, int newValue) {
        if (cfg.getInt(path, oldValue) == oldValue) {
            cfg.set(path, newValue);
        }
    }

    public PluginConfig getConfigData() {
        return configData;
    }

    public MessageManager getMessages() {
        return messages;
    }

    public TradeManager getTradeManager() {
        return tradeManager;
    }

    public TradeInflationListener getInflationListener() {
        return inflationListener;
    }

    public DailyRerollTask getRerollTask() {
        return rerollTask;
    }

    public void trackVillager(Villager villager) {
        if (rerollTask != null) {
            rerollTask.trackVillager(villager);
        }
    }
}
