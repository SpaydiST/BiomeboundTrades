package org.example1.biomeboundTrades.config;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.example1.biomeboundTrades.util.CompatibilityIndex;
import org.example1.biomeboundTrades.util.EnchantUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class PluginConfig {

    private static final int CONFIG_VERSION = 4;

    public enum RerollMode {
        MC_DAY,
        REAL_MINUTES
    }

    public static final class DailyReroll {
        public boolean enabled = true;
        public RerollMode mode = RerollMode.MC_DAY;
        public int checkIntervalSeconds = 5;
        public int realMinutes = 60;
        public boolean regenerateOffers = false;
        public boolean bookRotationEnabled = true;
        public int bookRotationMinDays = 7;
        public int bookRotationMaxDays = 14;
        public boolean dailySpecialBookRefresh = false;
        public boolean rerollOnStartup = false;
        public boolean restockTwiceADay = true;
        public int batchSize = 25;
        public Set<String> worlds = Collections.emptySet();
    }

    public static final class BiomeBooks {
        public boolean enabled = true;
        public boolean freezeHomeBiome = true;
        public boolean showEffects = true;
        public Set<String> excludedTools = new HashSet<>();
        public Set<Enchantment> excludedEnchants = new HashSet<>();
        public boolean autoDistributeNew = true;
        public int priceMin = 5;
        public int priceMax = 19;
        public int priceFixed = 0;
        public int maxTotalDiscountPercent = 40;
        public double specialPriceMultiplier = 1.5;
        public int specialBookChancePercent = 25;
        public int specialBookPityRotations = 2;
        public int maxUses = 3;
        public int specialMaxUsesMin = 1;
        public int specialMaxUsesMax = 2;
        public int multiMergeSpecialMaxUsesMin = 3;
        public int multiMergeSpecialMaxUsesMax = 4;
        public int singleMergeSpecialMaxUsesMin = 2;
        public int singleMergeSpecialMaxUsesMax = 3;
        public boolean specialRestockDelayEnabled = true;
        public int specialRestockMinDays = 5;
        public int specialRestockMaxDays = 7;
        public int multiMergeSpecialRestockMinDays = 2;
        public int multiMergeSpecialRestockMaxDays = 3;
        public int singleMergeSpecialRestockMinDays = 4;
        public int singleMergeSpecialRestockMaxDays = 5;
        public int experienceReward = 10;
        public float priceMultiplier = 0.05f;
        public Map<String, BookGroup> groups = new LinkedHashMap<>();
        public BookGroup defaultGroup = null;
        public CompatibilityIndex compatibilityIndex = new CompatibilityIndex(Collections.emptyMap());
    }

    public static final class Inflation {
        public boolean enabled = true;
        public int regionSize = 32;
        public int percentPerRepeat = 5;
        public int maxPercent = 50;
    }

    public static final class DualBooks {
        public boolean enabled = true;
        public int chancePercent = 5;
        public double priceMultiplier = 1.8;
        public int maxUses = 2;
        public int secondaryMaxLevel = 2;
    }

    public static final class CursedDualBooks {
        public boolean enabled = true;
        public int chancePercent = 8;
        public double bindingPriceMultiplier = 0.85;
        public int bindingMaxUses = 2;
        public double vanishingPriceMultiplier = 0.65;
        public int vanishingMaxUses = 1;
    }

    public static final class SeasonalStock {
        public boolean enabled = true;
        public int intervalDays = 14;
        public int durationDays = 3;
        public int chancePercent = 50;
        public int bonusUses = 2;
    }

    public static final class SpecialOffer {
        public boolean enabled = true;
        public int purchasesRequired = 8;
        public int chancePercent = 30;
        public int discountPercent = 30;
    }

    public static final class Reputation {
        public boolean enabled = true;
        public int radius = 16;
        public int percentPerBook = 1;
        public int maxPercent = 15;
    }

    public static final class DailySale {
        public boolean enabled = true;
        public int discountPercent = 25;
        public boolean showEffects = true;
    }

    public static final class Deflation {
        public boolean enabled = true;
        public int graceDays = 2;
        public int perDay = 1;
    }

    public static final class WanderingTrader {
        public boolean enabled = true;
        public int tradeCount = 6;
        public double priceMultiplier = 70;
        public int foreignTradeChancePercent = 50;
        public Map<String, WTGroup> groups = new LinkedHashMap<>();
    }

    public static final class WTGroup {
        private final Set<String> biomes;
        private final List<WTTrade> trades;

        public WTGroup(Set<String> biomes, List<WTTrade> trades) {
            this.biomes = biomes;
            this.trades = trades;
        }

        public Set<String> getBiomes() {
            return biomes;
        }

        public List<WTTrade> getTrades() {
            return trades;
        }
    }

    public static final class WTTrade {
        private final String item;
        private final int amount;
        private final int price;
        private final int chancePercent;
        private final int maxUses;

        public WTTrade(String item, int amount, int price, int chancePercent, int maxUses) {
            this.item = item;
            this.amount = amount;
            this.price = price;
            this.chancePercent = chancePercent;
            this.maxUses = maxUses;
        }

        public String getItem() {
            return item;
        }

        public int getAmount() {
            return amount;
        }

        public int getPrice() {
            return price;
        }

        public int getChancePercent() {
            return chancePercent;
        }

        public int getMaxUses() {
            return maxUses;
        }
    }

    public static final class ProfessionRebalance {
        public boolean enabled = true;
        public Map<String, RebalanceEntry> professions = new LinkedHashMap<>();
    }

    public static final class RebalanceEntry {
        public int sell = 100;
        public int buy = 100;
    }

    private final DailyReroll dailyReroll = new DailyReroll();
    private final BiomeBooks biomeBooks = new BiomeBooks();
    private final Inflation inflation = new Inflation();
    private final DualBooks dualBooks = new DualBooks();
    private final CursedDualBooks cursedDualBooks = new CursedDualBooks();
    private final SeasonalStock seasonalStock = new SeasonalStock();
    private final SpecialOffer specialOffer = new SpecialOffer();
    private final Reputation reputation = new Reputation();
    private final DailySale dailySale = new DailySale();
    private final Deflation deflation = new Deflation();
    private final WanderingTrader wanderingTrader = new WanderingTrader();
    private final ProfessionRebalance professionRebalance = new ProfessionRebalance();

    public DailyReroll dailyReroll() {
        return dailyReroll;
    }

    public BiomeBooks biomeBooks() {
        return biomeBooks;
    }

    public Inflation inflation() {
        return inflation;
    }

    public DualBooks dualBooks() {
        return dualBooks;
    }

    public CursedDualBooks cursedDualBooks() {
        return cursedDualBooks;
    }

    public SeasonalStock seasonalStock() {
        return seasonalStock;
    }

    public SpecialOffer specialOffer() {
        return specialOffer;
    }

    public Reputation reputation() {
        return reputation;
    }

    public DailySale dailySale() {
        return dailySale;
    }

    public Deflation deflation() {
        return deflation;
    }

    public WanderingTrader wanderingTrader() {
        return wanderingTrader;
    }

    public ProfessionRebalance professionRebalance() {
        return professionRebalance;
    }

    public void load(FileConfiguration cfg, Logger log) {
        int version = cfg.getInt("config-version", 0);
        if (version < CONFIG_VERSION) {
            log.warning("config.yml имеет устаревшую версию (" + version + "), ожидается " + CONFIG_VERSION
                    + ". Плагин может работать некорректно — сравните ваш config.yml с актуальным из jar.");
        }
        loadDailyReroll(cfg, log);
        loadBiomeBooks(cfg, log);
        loadInflation(cfg, log);
        loadDualBooks(cfg, log);
        loadCursedDualBooks(cfg, log);
        loadSeasonalStock(cfg);
        loadSpecialOffer(cfg, log);
        loadReputation(cfg, log);
        loadDailySale(cfg, log);
        loadDeflation(cfg, log);
        loadWanderingTrader(cfg, log);
        loadProfessionRebalance(cfg, log);
    }

    private void loadDailyReroll(FileConfiguration cfg, Logger log) {
        ConfigurationSection dr = cfg.getConfigurationSection("daily-reroll");
        if (dr == null) {
            return;
        }
        dailyReroll.enabled = dr.getBoolean("enabled", true);
        String mode = dr.getString("mode", "MC_DAY");
        try {
            dailyReroll.mode = RerollMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            dailyReroll.mode = RerollMode.MC_DAY;
            log.warning("daily-reroll.mode '" + mode + "' неизвестен, использую MC_DAY.");
        }
        dailyReroll.checkIntervalSeconds = Math.max(1, dr.getInt("check-interval-seconds", 5));
        dailyReroll.realMinutes = Math.max(1, dr.getInt("real-minutes", 60));
        dailyReroll.regenerateOffers = dr.getBoolean("regenerate-offers", false);
        dailyReroll.bookRotationEnabled = dr.getBoolean("book-rotation-enabled", true);
        dailyReroll.bookRotationMinDays = Math.max(1, dr.getInt("book-rotation-min-days", 7));
        dailyReroll.bookRotationMaxDays = Math.max(dailyReroll.bookRotationMinDays, dr.getInt("book-rotation-max-days", 14));
        dailyReroll.dailySpecialBookRefresh = dr.getBoolean("daily-special-book-refresh", false);
        dailyReroll.rerollOnStartup = dr.getBoolean("reroll-on-startup", false);
        dailyReroll.restockTwiceADay = dr.getBoolean("restock-twice-a-day", true);
        dailyReroll.batchSize = Math.max(1, dr.getInt("batch-size", 25));
        dailyReroll.worlds = new HashSet<>(dr.getStringList("worlds"));
    }

    private void loadBiomeBooks(FileConfiguration cfg, Logger log) {
        ConfigurationSection bb = cfg.getConfigurationSection("biome-books");
        if (bb == null) {
            return;
        }
        biomeBooks.enabled = bb.getBoolean("enabled", true);
        biomeBooks.freezeHomeBiome = bb.getBoolean("freeze-home-biome", true);
        biomeBooks.showEffects = bb.getBoolean("show-effects", true);
        biomeBooks.autoDistributeNew = bb.getBoolean("auto-distribute-new-books", true);

        Set<String> tools = new HashSet<>();
        for (String raw : bb.getStringList("excluded-tools")) {
            tools.add(raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", ""));
        }
        biomeBooks.excludedTools = tools;

        Set<Enchantment> excluded = new HashSet<>();
        for (String raw : bb.getStringList("excluded-enchants")) {
            Enchantment enchant = EnchantUtil.parse(raw);
            if (enchant == null) {
                log.warning("biome-books.excluded-enchants: неизвестные чары '" + raw + "', пропущены.");
            } else {
                excluded.add(enchant);
            }
        }
        biomeBooks.excludedEnchants = excluded;

        biomeBooks.priceMin = Math.max(1, bb.getInt("price.min", 5));
        biomeBooks.priceMax = Math.max(biomeBooks.priceMin, bb.getInt("price.max", 19));
        biomeBooks.priceFixed = Math.max(0, bb.getInt("price.fixed", 0));
        biomeBooks.maxTotalDiscountPercent = Math.max(0, Math.min(100, bb.getInt("price.max-total-discount-percent", 40)));
        biomeBooks.specialPriceMultiplier = Math.max(1.0, bb.getDouble("special-price-multiplier", 1.5));
        biomeBooks.specialBookChancePercent = Math.max(0, Math.min(100, bb.getInt("special-book-chance-percent", 25)));

        ConfigurationSection offer = bb.getConfigurationSection("offer");
        if (offer != null) {
            biomeBooks.maxUses = Math.max(1, offer.getInt("max-uses", 3));
            biomeBooks.specialMaxUsesMin = Math.max(1, offer.getInt("special-max-uses-min", 1));
            biomeBooks.specialMaxUsesMax = Math.max(biomeBooks.specialMaxUsesMin, offer.getInt("special-max-uses-max", 2));
            int legacyMergeUsesMin = offer.getInt("mergeable-special-max-uses-min", 3);
            int legacyMergeUsesMax = offer.getInt("mergeable-special-max-uses-max", 4);
            biomeBooks.multiMergeSpecialMaxUsesMin = Math.max(1,
                    offer.getInt("multi-merge-special-max-uses-min", legacyMergeUsesMin));
            biomeBooks.multiMergeSpecialMaxUsesMax = Math.max(biomeBooks.multiMergeSpecialMaxUsesMin,
                    offer.getInt("multi-merge-special-max-uses-max", legacyMergeUsesMax));
            biomeBooks.singleMergeSpecialMaxUsesMin = Math.max(1,
                    offer.getInt("single-merge-special-max-uses-min", 2));
            biomeBooks.singleMergeSpecialMaxUsesMax = Math.max(biomeBooks.singleMergeSpecialMaxUsesMin,
                    offer.getInt("single-merge-special-max-uses-max", 3));
            biomeBooks.specialRestockDelayEnabled = offer.getBoolean("special-restock-delay-enabled", true);
            biomeBooks.specialRestockMinDays = Math.max(1, offer.getInt("special-restock-min-days", 5));
            biomeBooks.specialRestockMaxDays = Math.max(biomeBooks.specialRestockMinDays, offer.getInt("special-restock-max-days", 7));
            int legacyMergeRestockMin = offer.getInt("mergeable-special-restock-min-days", 2);
            int legacyMergeRestockMax = offer.getInt("mergeable-special-restock-max-days", 3);
            biomeBooks.multiMergeSpecialRestockMinDays = Math.max(1,
                    offer.getInt("multi-merge-special-restock-min-days", legacyMergeRestockMin));
            biomeBooks.multiMergeSpecialRestockMaxDays = Math.max(biomeBooks.multiMergeSpecialRestockMinDays,
                    offer.getInt("multi-merge-special-restock-max-days", legacyMergeRestockMax));
            biomeBooks.singleMergeSpecialRestockMinDays = Math.max(1,
                    offer.getInt("single-merge-special-restock-min-days", 4));
            biomeBooks.singleMergeSpecialRestockMaxDays = Math.max(biomeBooks.singleMergeSpecialRestockMinDays,
                    offer.getInt("single-merge-special-restock-max-days", 5));
            biomeBooks.experienceReward = Math.max(0, offer.getInt("experience-reward", 10));
            biomeBooks.priceMultiplier = (float) offer.getDouble("price-multiplier", 0.05);
        }

        biomeBooks.groups = new LinkedHashMap<>();
        ConfigurationSection groups = bb.getConfigurationSection("groups");
        if (groups != null) {
            for (String name : groups.getKeys(false)) {
                BookGroup group = parseGroup(name, groups.getConfigurationSection(name), log);
                if (group != null) {
                    biomeBooks.groups.put(name.toLowerCase(Locale.ROOT), group);
                }
            }
        }

        for (BookGroup group : biomeBooks.groups.values()) {
            group.getCommon().removeIf(e -> EnchantUtil.isToolExcluded(e, biomeBooks.excludedTools) || biomeBooks.excludedEnchants.contains(e));
            group.getSpecial().removeIf(sp -> EnchantUtil.isToolExcluded(sp.getEnchant(), biomeBooks.excludedTools) || biomeBooks.excludedEnchants.contains(sp.getEnchant()));
        }

        BookGroup def = biomeBooks.groups.get("default");
        if (def == null) {
            def = new BookGroup("default", Collections.emptySet(), new ArrayList<>(), new LinkedHashSet<>());
            biomeBooks.groups.put("default", def);
        }
        biomeBooks.defaultGroup = def;

        if (biomeBooks.autoDistributeNew) {
            Set<Enchantment> covered = new HashSet<>(biomeBooks.excludedEnchants);
            for (BookGroup group : biomeBooks.groups.values()) {
                covered.addAll(group.getCommon());
                for (BookGroup.SpecialBook special : group.getSpecial()) {
                    covered.add(special.getEnchant());
                }
            }
            int added = 0;
            for (Enchantment enchant : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).stream().toList()) {
                if (covered.contains(enchant)) {
                    continue;
                }
                if (EnchantUtil.isToolExcluded(enchant, biomeBooks.excludedTools) || biomeBooks.excludedEnchants.contains(enchant)) {
                    continue;
                }
                def.getCommon().add(enchant);
                added++;
            }
            if (added > 0) {
                log.info("Новые чары автоматически добавлены в группу 'default': " + added + " шт.");
            }
        }
        biomeBooks.specialBookPityRotations = Math.max(0, bb.getInt("special-book-pity-rotations", 2));
        biomeBooks.compatibilityIndex = new CompatibilityIndex(biomeBooks.groups);
    }

    private BookGroup parseGroup(String name, ConfigurationSection section, Logger log) {
        if (section == null) {
            return null;
        }
        Set<String> biomes = new LinkedHashSet<>(section.getStringList("biomes"));

        List<BookGroup.SpecialBook> special = new ArrayList<>();
        List<Map<?, ?>> specialMaps = section.getMapList("special");
        for (Map<?, ?> map : specialMaps) {
            Object rawEnchant = map.get("enchant");
            if (!(rawEnchant instanceof String)) {
                log.warning("Группа '" + name + "': у особой книги нет поля enchant, пропущена.");
                continue;
            }
            Enchantment enchant = EnchantUtil.parse((String) rawEnchant);
            if (enchant == null) {
                log.warning("Группа '" + name + "': неизвестные чары '" + rawEnchant + "', пропущены.");
                continue;
            }
            int level = toInt(map.get("level"), 1);
            int price = toInt(map.get("price"), 0);
            special.add(new BookGroup.SpecialBook(enchant, Math.max(1, level), Math.max(0, price)));
        }

        Set<Enchantment> common = new LinkedHashSet<>();
        for (String raw : section.getStringList("common")) {
            Enchantment enchant = EnchantUtil.parse(raw);
            if (enchant == null) {
                log.warning("Группа '" + name + "': неизвестные чары '" + raw + "', пропущены.");
            } else {
                common.add(enchant);
            }
        }

        return new BookGroup(name, biomes, special, common);
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private void loadInflation(FileConfiguration cfg, Logger log) {
        ConfigurationSection in = cfg.getConfigurationSection("inflation");
        if (in == null) {
            return;
        }
        inflation.enabled = in.getBoolean("enabled", true);
        int legacyRadius = Math.max(16, in.getInt("radius", 16));
        inflation.regionSize = Math.max(16, in.getInt("region-size", Math.max(32, legacyRadius * 2)));
        inflation.percentPerRepeat = Math.max(1, in.getInt("percent-per-repeat", 5));
        inflation.maxPercent = Math.max(inflation.percentPerRepeat, in.getInt("max-percent", 50));
    }

    private void loadDualBooks(FileConfiguration cfg, Logger log) {
        ConfigurationSection s = cfg.getConfigurationSection("dual-books");
        if (s == null) {
            return;
        }
        dualBooks.enabled = s.getBoolean("enabled", true);
        dualBooks.chancePercent = Math.max(0, s.getInt("chance-percent", 5));
        dualBooks.priceMultiplier = Math.max(1.0, s.getDouble("price-multiplier", 1.8));
        dualBooks.maxUses = Math.max(1, s.getInt("max-uses", 2));
        dualBooks.secondaryMaxLevel = Math.max(1, s.getInt("secondary-level-max", 2));
    }

    private void loadCursedDualBooks(FileConfiguration cfg, Logger log) {
        ConfigurationSection s = cfg.getConfigurationSection("cursed-dual-books");
        if (s == null) {
            return;
        }
        cursedDualBooks.enabled = s.getBoolean("enabled", true);
        cursedDualBooks.chancePercent = Math.max(0, Math.min(100, s.getInt("chance-percent", 8)));
        cursedDualBooks.bindingPriceMultiplier = Math.max(0.1,
                Math.min(1.0, s.getDouble("binding-price-multiplier", 0.85)));
        cursedDualBooks.bindingMaxUses = Math.max(1, s.getInt("binding-max-uses", 2));
        cursedDualBooks.vanishingPriceMultiplier = Math.max(0.1,
                Math.min(1.0, s.getDouble("vanishing-price-multiplier", 0.65)));
        cursedDualBooks.vanishingMaxUses = Math.max(1, s.getInt("vanishing-max-uses", 1));
    }

    private void loadSeasonalStock(FileConfiguration cfg) {
        ConfigurationSection s = cfg.getConfigurationSection("seasonal-stock");
        if (s == null) {
            return;
        }
        seasonalStock.enabled = s.getBoolean("enabled", true);
        seasonalStock.intervalDays = Math.max(1, s.getInt("interval-days", 14));
        seasonalStock.durationDays = Math.max(1, s.getInt("duration-days", 3));
        seasonalStock.chancePercent = Math.max(0, Math.min(100, s.getInt("chance-percent", 50)));
        seasonalStock.bonusUses = Math.max(1, s.getInt("bonus-uses", 2));
    }

    private void loadSpecialOffer(FileConfiguration cfg, Logger log) {
        ConfigurationSection s = cfg.getConfigurationSection("special-offer");
        if (s == null) {
            return;
        }
        specialOffer.enabled = s.getBoolean("enabled", true);
        specialOffer.purchasesRequired = Math.max(1, s.getInt("purchases-required", 8));
        specialOffer.chancePercent = Math.max(1, Math.min(100, s.getInt("chance-percent", 30)));
        specialOffer.discountPercent = Math.max(1, Math.min(100, s.getInt("discount-percent", 30)));
    }

    private void loadReputation(FileConfiguration cfg, Logger log) {
        ConfigurationSection s = cfg.getConfigurationSection("reputation");
        if (s == null) {
            return;
        }
        reputation.enabled = s.getBoolean("enabled", true);
        reputation.radius = Math.max(1, s.getInt("radius", 16));
        reputation.percentPerBook = Math.max(0, s.getInt("percent-per-book", 1));
        reputation.maxPercent = Math.max(0, s.getInt("max-percent", 15));
    }

    private void loadDailySale(FileConfiguration cfg, Logger log) {
        ConfigurationSection s = cfg.getConfigurationSection("daily-sale");
        if (s == null) {
            return;
        }
        dailySale.enabled = s.getBoolean("enabled", true);
        dailySale.discountPercent = Math.max(1, Math.min(100, s.getInt("discount-percent", 25)));
        dailySale.showEffects = s.getBoolean("show-effects", true);
    }

    private void loadDeflation(FileConfiguration cfg, Logger log) {
        ConfigurationSection s = cfg.getConfigurationSection("deflation");
        if (s == null) {
            return;
        }
        deflation.enabled = s.getBoolean("enabled", true);
        deflation.graceDays = Math.max(1, s.getInt("grace-days", 2));
        deflation.perDay = Math.max(1, s.getInt("per-day", 1));
    }

    private void loadWanderingTrader(FileConfiguration cfg, Logger log) {
        ConfigurationSection wt = cfg.getConfigurationSection("wandering-trader");
        if (wt == null) {
            return;
        }
        wanderingTrader.enabled = wt.getBoolean("enabled", true);
        wanderingTrader.tradeCount = Math.max(1, wt.getInt("trade-count", 6));
        wanderingTrader.priceMultiplier = Math.max(1, wt.getDouble("price-multiplier", 70));
        wanderingTrader.foreignTradeChancePercent = Math.max(0, Math.min(100, wt.getInt("foreign-trade-chance-percent", 50)));
        wanderingTrader.groups.clear();

        ConfigurationSection groups = wt.getConfigurationSection("biome-groups");
        if (groups != null) {
            for (String name : groups.getKeys(false)) {
                ConfigurationSection gs = groups.getConfigurationSection(name);
                if (gs == null) {
                    continue;
                }
                Set<String> biomes = new LinkedHashSet<>(gs.getStringList("biomes"));
                List<WTTrade> trades = new ArrayList<>();
                for (Map<?, ?> map : gs.getMapList("trades")) {
                    Object rawItem = map.get("item");
                    if (!(rawItem instanceof String)) {
                        log.warning("wandering-trader.biome-groups.'" + name + "': у сделки нет поля item, пропущена.");
                        continue;
                    }
                    String item = ((String) rawItem).trim().toUpperCase(Locale.ROOT);
                    if (org.bukkit.Material.matchMaterial(item) == null) {
                        log.warning("wandering-trader.biome-groups.'" + name + "': неизвестный предмет '" + rawItem + "', пропущен.");
                        continue;
                    }
                    int amount = toInt(map.get("amount"), 1);
                    int price = toInt(map.get("price"), 1);
                    int chance = Math.max(1, Math.min(100, toInt(map.get("chance-percent"), 100)));
                    int maxUses = Math.max(1, toInt(map.get("max-uses"), 4));
                    trades.add(new WTTrade(item, Math.max(1, amount), Math.max(1, price), chance, maxUses));
                }
                if (!trades.isEmpty()) {
                    wanderingTrader.groups.put(name.toLowerCase(Locale.ROOT), new WTGroup(biomes, trades));
                }
            }
        }
    }

    private void loadProfessionRebalance(FileConfiguration cfg, Logger log) {
        ConfigurationSection pr = cfg.getConfigurationSection("profession-rebalance");
        if (pr == null) {
            return;
        }
        professionRebalance.enabled = pr.getBoolean("enabled", true);
        professionRebalance.professions.clear();
        ConfigurationSection professions = pr.getConfigurationSection("professions");
        if (professions != null) {
            for (String name : professions.getKeys(false)) {
                ConfigurationSection ps = professions.getConfigurationSection(name);
                RebalanceEntry entry = new RebalanceEntry();
                if (ps != null) {
                    entry.sell = Math.max(1, Math.min(500, ps.getInt("sell", 100)));
                    entry.buy = Math.max(1, Math.min(500, ps.getInt("buy", 100)));
                }
                professionRebalance.professions.put(name.toLowerCase(Locale.ROOT), entry);
            }
        }
    }
}
