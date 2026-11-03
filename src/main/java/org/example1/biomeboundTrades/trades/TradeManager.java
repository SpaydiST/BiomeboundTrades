package org.example1.biomeboundTrades.trades;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.example1.biomeboundTrades.BiomeboundTrades;
import org.example1.biomeboundTrades.config.BookGroup;
import org.example1.biomeboundTrades.config.PluginConfig;
import org.example1.biomeboundTrades.util.EnchantUtil;
import org.example1.biomeboundTrades.util.TextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

public final class TradeManager {

    private final BiomeboundTrades plugin;
    private final NamespacedKey homeBiomeKey;
    private final NamespacedKey villagerLevelKey;
    private final NamespacedKey rebalanceKey;
    private final NamespacedKey nextBookRotationDayKey;
    private final NamespacedKey lastSpecialRerollDayKey;
    private final NamespacedKey nextSpecialRestockDayKey;
    private final NamespacedKey failedSpecialBookRotationsKey;
    private final NamespacedKey primaryBookBasePriceKey;
    private final NamespacedKey restockPeriodKey;
    private final NamespacedKey lastWorldRefreshDayKey;
    private final NamespacedKey nextRealRefreshMillisKey;
    private final Random random = new Random();
    private final Set<BukkitTask> activeTasks = new HashSet<>();

    public TradeManager(BiomeboundTrades plugin) {
        this.plugin = plugin;
        this.homeBiomeKey = new NamespacedKey(plugin, "home_biome");
        this.villagerLevelKey = new NamespacedKey(plugin, "villager_level");
        this.rebalanceKey = new NamespacedKey(plugin, "rebalance_orig");
        this.nextBookRotationDayKey = new NamespacedKey(plugin, "next_book_rotation_day");
        this.lastSpecialRerollDayKey = new NamespacedKey(plugin, "last_special_reroll_day");
        this.nextSpecialRestockDayKey = new NamespacedKey(plugin, "next_special_restock_day");
        this.failedSpecialBookRotationsKey = new NamespacedKey(plugin, "failed_special_book_rotations");
        this.primaryBookBasePriceKey = new NamespacedKey(plugin, "primary_book_base_price");
        this.restockPeriodKey = new NamespacedKey(plugin, "last_restock_period");
        this.lastWorldRefreshDayKey = new NamespacedKey(plugin, "last_world_refresh_day");
        this.nextRealRefreshMillisKey = new NamespacedKey(plugin, "next_real_refresh_millis");
    }

    public void cancelTasks() {
        for (BukkitTask task : activeTasks) {
            task.cancel();
        }
        activeTasks.clear();
    }

    public void rerollAll() {
        for (World world : Bukkit.getWorlds()) {
            rerollWorld(world, true);
        }
    }

    public void rerollWorld(World world) {
        rerollWorld(world, false);
    }

    public void rerollWorld(World world, boolean forceBookRefresh) {
        Collection<Villager> villagers = world.getEntitiesByClass(Villager.class);
        forEachBatched(villagers, v -> rerollVillager(v, forceBookRefresh));
        plugin.getLogger().info("Переторговано жителей в мире '" + world.getName() + "': " + villagers.size());
    }

    public void rerollVillager(Villager villager) {
        rerollVillager(villager, false);
    }

    public void rerollVillager(Villager villager, boolean forceBookRefresh) {
        PluginConfig cfg = plugin.getConfigData();
        PluginConfig.DailyReroll dr = cfg.dailyReroll();
        restock(villager);
        if (forceBookRefresh) {
            scheduleNextBookRotation(villager);
            markSpecialRerolledToday(villager);
            applyBiomeBooks(villager, true, true);
        } else {
            boolean rotateCommons = dr.regenerateOffers || isBookRotationDue(villager);
            boolean refreshSpecial = dr.regenerateOffers || dr.dailySpecialBookRefresh;
            if (refreshSpecial) {
                markSpecialRerolledToday(villager);
            }
            applyBiomeBooks(villager, rotateCommons, refreshSpecial);
        }
        applyProfessionRebalance(villager);
        playUpdateEffects(villager, false);
        plugin.trackVillager(villager);
    }

    public void checkPendingBookRotation(Villager villager) {
        processScheduledUpdates(villager);
        plugin.trackVillager(villager);
    }

    public void prepareForScheduling(Villager villager) {
        if (villager == null || !villager.isValid() || villager.isDead()) {
            return;
        }
        PluginConfig cfg = plugin.getConfigData();
        PluginConfig.DailyReroll dr = cfg.dailyReroll();
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long fullTime = villager.getWorld().getFullTime();
        long day = fullTime / 24000L;
        if (!pdc.has(restockPeriodKey, PersistentDataType.LONG)) {
            pdc.set(restockPeriodKey, PersistentDataType.LONG, fullTime / 12000L);
        }
        if (dr.enabled && dr.mode == PluginConfig.RerollMode.MC_DAY
                && !pdc.has(lastWorldRefreshDayKey, PersistentDataType.LONG)) {
            pdc.set(lastWorldRefreshDayKey, PersistentDataType.LONG, day);
        }
        if (dr.enabled && dr.mode == PluginConfig.RerollMode.REAL_MINUTES
                && !pdc.has(nextRealRefreshMillisKey, PersistentDataType.LONG)) {
            pdc.set(nextRealRefreshMillisKey, PersistentDataType.LONG,
                    System.currentTimeMillis() + dr.realMinutes * 60_000L);
        }
        if (villager.getProfession() != Villager.Profession.LIBRARIAN) {
            return;
        }
        if (!pdc.has(nextBookRotationDayKey, PersistentDataType.LONG)) {
            scheduleNextBookRotation(villager);
        }
        if (!pdc.has(lastSpecialRerollDayKey, PersistentDataType.LONG)) {
            markSpecialRerolledToday(villager);
        }
        if (!pdc.has(nextSpecialRestockDayKey, PersistentDataType.LONG)) {
            BookGroup group = resolveGroup(villager);
            MerchantRecipe special = group == null ? null : findSpecialBookInList(villager.getRecipes(), group);
            scheduleNextSpecialRestock(villager, day, cfg.biomeBooks(), special);
        }
    }

    public long nextScheduledWorldTick(Villager villager) {
        PluginConfig cfg = plugin.getConfigData();
        PluginConfig.DailyReroll dr = cfg.dailyReroll();
        long now = villager.getWorld().getFullTime();
        long next = Long.MAX_VALUE;
        if (isScheduledWorld(villager.getWorld(), dr)) {
            if (dr.restockTwiceADay) {
                next = Math.min(next, (Math.floorDiv(now, 12000L) + 1L) * 12000L);
            }
            if (dr.enabled && dr.mode == PluginConfig.RerollMode.MC_DAY) {
                long lastDay = villager.getPersistentDataContainer()
                        .getOrDefault(lastWorldRefreshDayKey, PersistentDataType.LONG, now / 24000L);
                next = Math.min(next, (lastDay + 1L) * 24000L);
            }
            if (villager.getProfession() == Villager.Profession.LIBRARIAN && dr.enabled
                    && dr.bookRotationEnabled) {
                Long rotation = villager.getPersistentDataContainer()
                        .get(nextBookRotationDayKey, PersistentDataType.LONG);
                if (rotation != null) {
                    next = Math.min(next, rotation * 24000L);
                }
            }
            if (villager.getProfession() == Villager.Profession.LIBRARIAN
                    && villager.getVillagerLevel() >= 5) {
                Long special = villager.getPersistentDataContainer()
                        .get(nextSpecialRestockDayKey, PersistentDataType.LONG);
                BookGroup group = resolveGroup(villager);
                MerchantRecipe specialRecipe = group == null ? null
                        : findSpecialBookInList(villager.getRecipes(), group);
                if (special != null && specialRecipe != null && specialRecipe.getUses() > 0) {
                    next = Math.min(next, special * 24000L);
                }
            }
        }
        return next;
    }

    public long nextScheduledRealMillis(Villager villager) {
        PluginConfig.DailyReroll dr = plugin.getConfigData().dailyReroll();
        if (!dr.enabled || dr.mode != PluginConfig.RerollMode.REAL_MINUTES
                || !isScheduledWorld(villager.getWorld(), dr)) {
            return Long.MAX_VALUE;
        }
        return villager.getPersistentDataContainer()
                .getOrDefault(nextRealRefreshMillisKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    private boolean isScheduledWorld(World world, PluginConfig.DailyReroll dr) {
        return dr.worlds.isEmpty() || dr.worlds.contains(world.getName());
    }

    public void processScheduledUpdates(Villager villager) {
        if (villager == null || !villager.isValid() || villager.isDead()) {
            return;
        }
        PluginConfig cfg = plugin.getConfigData();
        PluginConfig.DailyReroll dr = cfg.dailyReroll();
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        long fullTime = villager.getWorld().getFullTime();
        long currentPeriod = fullTime / 12000L;
        long currentDay = fullTime / 24000L;
        boolean scheduledWorld = isScheduledWorld(villager.getWorld(), dr);
        Long lastPeriodValue = pdc.get(restockPeriodKey, PersistentDataType.LONG);
        long lastPeriod = lastPeriodValue == null ? currentPeriod : lastPeriodValue;
        boolean restockDue = scheduledWorld && dr.restockTwiceADay && currentPeriod > lastPeriod;

        boolean dayRefreshDue = false;
        if (scheduledWorld && dr.enabled && dr.mode == PluginConfig.RerollMode.MC_DAY) {
            Long lastDayValue = pdc.get(lastWorldRefreshDayKey, PersistentDataType.LONG);
            long lastDay = lastDayValue == null ? currentDay : lastDayValue;
            if (currentDay > lastDay) {
                dayRefreshDue = true;
                pdc.set(lastWorldRefreshDayKey, PersistentDataType.LONG, currentDay);
            }
        }

        boolean realRefreshDue = false;
        if (scheduledWorld && dr.enabled && dr.mode == PluginConfig.RerollMode.REAL_MINUTES) {
            Long nextReal = pdc.get(nextRealRefreshMillisKey, PersistentDataType.LONG);
            long now = System.currentTimeMillis();
            if (nextReal == null) {
                pdc.set(nextRealRefreshMillisKey, PersistentDataType.LONG, now + dr.realMinutes * 60_000L);
            } else if (now >= nextReal) {
                realRefreshDue = true;
                pdc.set(nextRealRefreshMillisKey, PersistentDataType.LONG, now + dr.realMinutes * 60_000L);
            }
        }

        boolean rotateCommons = villager.getProfession() == Villager.Profession.LIBRARIAN
                && dr.enabled && dr.bookRotationEnabled && isBookRotationDue(villager);
        boolean refreshOffers = dayRefreshDue || realRefreshDue;
        boolean refreshSpecial = refreshOffers && (dr.regenerateOffers || dr.dailySpecialBookRefresh);
        boolean refreshCommons = rotateCommons || (refreshOffers && dr.regenerateOffers);

        if (restockDue || dayRefreshDue || realRefreshDue) {
            restock(villager);
            pdc.set(restockPeriodKey, PersistentDataType.LONG, currentPeriod);
        }
        if (refreshCommons || refreshSpecial) {
            applyBiomeBooks(villager, refreshCommons, refreshSpecial);
            playUpdateEffects(villager, false);
        } else if (!restockDue && !dayRefreshDue && !realRefreshDue
                && villager.getVillagerLevel() >= 5) {
            checkPendingSpecialRestock(villager);
        }
        if (restockDue || dayRefreshDue || realRefreshDue) {
            applyProfessionRebalance(villager);
        }
    }

    public void onBookTraded(Villager villager, MerchantRecipe traded) {
        if (villager.getProfession() != Villager.Profession.LIBRARIAN || villager.getVillagerLevel() < 5) {
            return;
        }
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        if (!bb.enabled || !bb.specialRestockDelayEnabled) {
            return;
        }
        if (!isSpecialBookRecipe(villager, traded)) {
            return;
        }
        long currentDay = villager.getWorld().getFullTime() / 24000L;
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long nextDay = pdc.get(nextSpecialRestockDayKey, PersistentDataType.LONG);
        if (nextDay == null || currentDay >= nextDay || traded.getUses() + 1 >= traded.getMaxUses()) {
            scheduleNextSpecialRestock(villager, currentDay, bb, traded);
        }
    }

    public boolean isSpecialBookRecipe(Villager villager, MerchantRecipe recipe) {
        if (recipe == null || villager.getProfession() != Villager.Profession.LIBRARIAN || villager.getVillagerLevel() < 5) {
            return false;
        }
        BookGroup group = resolveGroup(villager);
        if (group == null || group.getSpecial().isEmpty()) {
            return false;
        }
        for (Enchantment enchant : EnchantUtil.getStoredEnchants(recipe.getResult()).keySet()) {
            for (BookGroup.SpecialBook sb : group.getSpecial()) {
                if (sb.getEnchant().equals(enchant)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean tryReplenishSpecialBook(Villager villager, MerchantRecipe recipe) {
        if (!isSpecialRestockDue(villager, recipe)) {
            return false;
        }
        recipe.setMaxUses(randomSpecialUses(villager, recipe));
        return true;
    }

    private void checkPendingSpecialRestock(Villager villager) {
        List<MerchantRecipe> recipes = villager.getRecipes();
        BookGroup group = resolveGroup(villager);
        if (group == null) {
            return;
        }
        MerchantRecipe special = findSpecialBookInList(recipes, group);
        if (special == null || special.getUses() == 0) {
            return;
        }
        if (isSpecialRestockDue(villager, special)) {
            special.setUses(0);
            special.setMaxUses(randomSpecialUses(villager, special));
            villager.setRecipes(recipes);
            playUpdateEffects(villager, false);
        }
    }

    private boolean isSpecialRestockDue(Villager villager, MerchantRecipe recipe) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        if (!bb.specialRestockDelayEnabled) {
            return true;
        }
        long currentDay = villager.getWorld().getFullTime() / 24000L;
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long nextDay = pdc.get(nextSpecialRestockDayKey, PersistentDataType.LONG);
        if (nextDay == null) {
            scheduleNextSpecialRestock(villager, currentDay, bb, recipe);
            return false;
        }
        if (currentDay >= nextDay) {
            scheduleNextSpecialRestock(villager, currentDay, bb, recipe);
            return true;
        }
        return false;
    }

    private void scheduleNextSpecialRestock(Villager villager, long currentDay, PluginConfig.BiomeBooks bb,
                                            MerchantRecipe recipe) {
        int mergeOperations = specialMergeOperations(villager, recipe);
        int min = mergeOperations >= 2 ? bb.multiMergeSpecialRestockMinDays
                : mergeOperations == 1 ? bb.singleMergeSpecialRestockMinDays : bb.specialRestockMinDays;
        int max = mergeOperations >= 2 ? bb.multiMergeSpecialRestockMaxDays
                : mergeOperations == 1 ? bb.singleMergeSpecialRestockMaxDays : bb.specialRestockMaxDays;
        min = Math.max(1, min);
        max = Math.max(min, max);
        int delta = min + random.nextInt(max - min + 1);
        villager.getPersistentDataContainer().set(nextSpecialRestockDayKey, PersistentDataType.LONG, currentDay + delta);
    }

    private void markSpecialRerolledToday(Villager villager) {
        if (villager.getProfession() == Villager.Profession.LIBRARIAN) {
            long currentDay = villager.getWorld().getFullTime() / 24000L;
            villager.getPersistentDataContainer().set(lastSpecialRerollDayKey, PersistentDataType.LONG, currentDay);
        }
    }

    private boolean isBookRotationDue(Villager villager) {
        if (villager.getProfession() != Villager.Profession.LIBRARIAN) {
            return false;
        }
        PluginConfig.DailyReroll dr = plugin.getConfigData().dailyReroll();
        if (!dr.bookRotationEnabled) {
            return false;
        }
        long currentDay = villager.getWorld().getFullTime() / 24000L;
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long nextDay = pdc.get(nextBookRotationDayKey, PersistentDataType.LONG);
        if (nextDay == null) {
            scheduleNextBookRotation(villager, currentDay, dr);
            return false;
        }
        if (currentDay >= nextDay) {
            scheduleNextBookRotation(villager, currentDay, dr);
            return true;
        }
        return false;
    }

    private void scheduleNextBookRotation(Villager villager) {
        long currentDay = villager.getWorld().getFullTime() / 24000L;
        scheduleNextBookRotation(villager, currentDay, plugin.getConfigData().dailyReroll());
    }

    private void scheduleNextBookRotation(Villager villager, long currentDay, PluginConfig.DailyReroll dr) {
        int min = Math.max(1, dr.bookRotationMinDays);
        int max = Math.max(min, dr.bookRotationMaxDays);
        int delta = min + random.nextInt(max - min + 1);
        villager.getPersistentDataContainer().set(nextBookRotationDayKey, PersistentDataType.LONG, currentDay + delta);
    }

    private void playUpdateEffects(Villager villager, boolean levelUp) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        if (!bb.enabled || !bb.showEffects) {
            return;
        }
        World world = villager.getWorld();
        Location loc = villager.getLocation().add(0, 1.0, 0);
        world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 20, 0.5, 0.5, 0.5, 0.01);
        if (levelUp) {
            world.spawnParticle(Particle.NOTE, loc, 10, 0.5, 0.5, 0.5, 0);
            world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);
        } else {
            world.playSound(loc, Sound.ENTITY_VILLAGER_YES, 0.8f, 1.0f);
        }
    }

    private void restock(Villager villager) {
        List<MerchantRecipe> recipes = villager.getRecipes();
        MerchantRecipe special = null;
        if (villager.getProfession() == Villager.Profession.LIBRARIAN && villager.getVillagerLevel() >= 5) {
            BookGroup group = resolveGroup(villager);
            if (group != null) {
                special = findSpecialBookInList(recipes, group);
            }
        }
        for (MerchantRecipe recipe : recipes) {
            if (recipe == special) {
                if (recipe.getUses() > 0 && isSpecialRestockDue(villager, recipe)) {
                    recipe.setUses(0);
                    recipe.setMaxUses(randomSpecialUses(villager, recipe));
                }
            } else {
                recipe.setUses(0);
            }
        }
        villager.setRecipes(recipes);
    }

    public void restockWorld(World world) {
        Collection<Villager> villagers = world.getEntitiesByClass(Villager.class);
        forEachBatched(villagers, villager -> {
            restock(villager);
            applyProfessionRebalance(villager);
        });
    }

    private void forEachBatched(Collection<Villager> villagers, Consumer<Villager> action) {
        int batch = Math.max(1, plugin.getConfigData().dailyReroll().batchSize);
        if (villagers.size() <= batch) {
            for (Villager villager : villagers) {
                action.accept(villager);
            }
            return;
        }
        List<Villager> list = villagers instanceof List<Villager> l ? l : new ArrayList<>(villagers);
        int[] cursor = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int start = cursor[0];
            cursor[0] = start + batch;
            if (start >= list.size()) {
                task[0].cancel();
                activeTasks.remove(task[0]);
                return;
            }
            int end = Math.min(start + batch, list.size());
            for (int i = start; i < end; i++) {
                Villager villager = list.get(i);
                if (villager.isValid() && !villager.isDead()) {
                    action.accept(villager);
                }
            }
        }, 1L, 1L);
        activeTasks.add(task[0]);
    }

    public void clearRebalance(Villager villager) {
        villager.getPersistentDataContainer().remove(rebalanceKey);
        villager.getPersistentDataContainer().remove(nextBookRotationDayKey);
        villager.getPersistentDataContainer().remove(lastSpecialRerollDayKey);
        villager.getPersistentDataContainer().remove(nextSpecialRestockDayKey);
        villager.getPersistentDataContainer().remove(failedSpecialBookRotationsKey);
        villager.getPersistentDataContainer().remove(restockPeriodKey);
        villager.getPersistentDataContainer().remove(lastWorldRefreshDayKey);
        villager.getPersistentDataContainer().remove(nextRealRefreshMillisKey);
    }

    public void copyPersistentData(Entity from, Entity to) {
        PersistentDataContainer src = from.getPersistentDataContainer();
        PersistentDataContainer dst = to.getPersistentDataContainer();
        String homeBiome = src.get(homeBiomeKey, PersistentDataType.STRING);
        if (homeBiome != null) {
            dst.set(homeBiomeKey, PersistentDataType.STRING, homeBiome);
        }
        Integer level = src.get(villagerLevelKey, PersistentDataType.INTEGER);
        if (level != null) {
            dst.set(villagerLevelKey, PersistentDataType.INTEGER, level);
        }
        String rebalance = src.get(rebalanceKey, PersistentDataType.STRING);
        if (rebalance != null) {
            dst.set(rebalanceKey, PersistentDataType.STRING, rebalance);
        }
        Long nextRotation = src.get(nextBookRotationDayKey, PersistentDataType.LONG);
        if (nextRotation != null) {
            dst.set(nextBookRotationDayKey, PersistentDataType.LONG, nextRotation);
        }
        Long lastSpecial = src.get(lastSpecialRerollDayKey, PersistentDataType.LONG);
        if (lastSpecial != null) {
            dst.set(lastSpecialRerollDayKey, PersistentDataType.LONG, lastSpecial);
        }
        Long nextSpecialRestock = src.get(nextSpecialRestockDayKey, PersistentDataType.LONG);
        if (nextSpecialRestock != null) {
            dst.set(nextSpecialRestockDayKey, PersistentDataType.LONG, nextSpecialRestock);
        }
        Integer failedSpecialBookRotations = src.get(failedSpecialBookRotationsKey, PersistentDataType.INTEGER);
        if (failedSpecialBookRotations != null) {
            dst.set(failedSpecialBookRotationsKey, PersistentDataType.INTEGER, failedSpecialBookRotations);
        }
        Long restockPeriod = src.get(restockPeriodKey, PersistentDataType.LONG);
        if (restockPeriod != null) {
            dst.set(restockPeriodKey, PersistentDataType.LONG, restockPeriod);
        }
        Long lastWorldRefresh = src.get(lastWorldRefreshDayKey, PersistentDataType.LONG);
        if (lastWorldRefresh != null) {
            dst.set(lastWorldRefreshDayKey, PersistentDataType.LONG, lastWorldRefresh);
        }
        Long nextRealRefresh = src.get(nextRealRefreshMillisKey, PersistentDataType.LONG);
        if (nextRealRefresh != null) {
            dst.set(nextRealRefreshMillisKey, PersistentDataType.LONG, nextRealRefresh);
        }
    }

    public void applyProfessionRebalance(Villager villager) {
        PluginConfig cfg = plugin.getConfigData();
        if (!cfg.professionRebalance().enabled) {
            restoreProfessionRebalance(villager);
            return;
        }
        if (villager.getProfession() == Villager.Profession.LIBRARIAN) {
            restoreProfessionRebalance(villager);
            return;
        }
        PluginConfig.RebalanceEntry entry = cfg.professionRebalance().professions
                .get(villager.getProfession().getKey().getKey());
        if (entry == null || (entry.sell == 100 && entry.buy == 100)) {
            restoreProfessionRebalance(villager);
            return;
        }

        OriginalData originals = loadOriginals(villager);
        boolean storedChanged = false;
        List<MerchantRecipe> recipes = villager.getRecipes();
        List<MerchantRecipe> updated = null;
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            MerchantRecipe copy = new MerchantRecipe(recipe);
            boolean buy = copy.getResult().getType() == Material.EMERALD;
            int current = rebalanceTargetValue(copy, buy);
            if (current < 0) {
                continue;
            }
            int percent = buy ? entry.buy : entry.sell;
            String key = recipeKey(copy);
            String legacyKey = legacyRecipeKey(copy);
            String resultName = copy.getResult().getType().name();
            Integer original = originals.map.get(key);
            boolean hadStoredOriginal = original != null;
            if (original == null && !legacyKey.equals(key)) {
                original = originals.map.get(legacyKey);
                if (original != null) {
                    // Migrate the previous key format, which included a diamond
                    // added by ensureDiamondIngredient on earlier runs.
                    originals.map.put(key, original);
                    storedChanged = true;
                    hadStoredOriginal = true;
                }
            }
            if (original == null && !buy && originals.legacy.containsKey(resultName)) {
                original = originals.legacy.get(resultName);
                originals.map.put(key, original);
                storedChanged = true;
                hadStoredOriginal = true;
            }
            if (original == null) {
                original = current;
                originals.map.put(key, original);
                storedChanged = true;
            }
            int desired = buy ? scaleBuyCost(original, percent) : scalePrice(original, percent);
            boolean changed = setRebalanceTargetValue(copy, buy, desired, current);
            if (!buy && percent > 100 && ensureDiamondIngredient(copy)) {
                changed = true;
            } else if (!buy && percent <= 100 && hadStoredOriginal
                    && diamondRequirement(copy.getResult().getType()) > 0
                    && removeRebalanceDiamond(copy)) {
                changed = true;
            }
            if (changed) {
                if (updated == null) {
                    updated = new ArrayList<>(recipes);
                }
                updated.set(i, copy);
            }
        }
        if (storedChanged) {
            saveOriginals(villager, originals.map);
        }
        if (updated != null) {
            villager.setRecipes(updated);
        }
    }

    private void restoreProfessionRebalance(Villager villager) {
        OriginalData originals = loadOriginals(villager);
        if (originals.map.isEmpty() && originals.legacy.isEmpty()) {
            villager.getPersistentDataContainer().remove(rebalanceKey);
            return;
        }

        List<MerchantRecipe> recipes = villager.getRecipes();
        List<MerchantRecipe> updated = null;
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            MerchantRecipe copy = new MerchantRecipe(recipe);
            boolean buy = copy.getResult().getType() == Material.EMERALD;
            int current = rebalanceTargetValue(copy, buy);
            if (current < 0) {
                continue;
            }
            String key = recipeKey(copy);
            Integer original = originals.map.get(key);
            if (original == null && !buy) {
                original = originals.legacy.get(copy.getResult().getType().name());
            }
            if (original == null) {
                continue;
            }

            boolean changed = setRebalanceTargetValue(copy, buy, original, current);
            if (!buy && diamondRequirement(copy.getResult().getType()) > 0
                    && removeRebalanceDiamond(copy)) {
                changed = true;
            }
            if (changed) {
                if (updated == null) {
                    updated = new ArrayList<>(recipes);
                }
                updated.set(i, copy);
            }
        }
        if (updated != null) {
            villager.setRecipes(updated);
        }
        villager.getPersistentDataContainer().remove(rebalanceKey);
    }

    private boolean removeRebalanceDiamond(MerchantRecipe recipe) {
        List<ItemStack> ingredients = recipe.getIngredients();
        boolean removed = ingredients.removeIf(ingredient -> ingredient.getType() == Material.DIAMOND);
        if (removed) {
            recipe.setIngredients(ingredients);
        }
        return removed;
    }

    public boolean hasProfessionRebalance(Villager villager) {
        return villager.getPersistentDataContainer().has(rebalanceKey, PersistentDataType.STRING);
    }

    private boolean ensureDiamondIngredient(MerchantRecipe recipe) {
        int diamonds = diamondRequirement(recipe.getResult().getType());
        if (diamonds <= 0) {
            return false;
        }
        List<ItemStack> ingredients = recipe.getIngredients();
        for (ItemStack ing : ingredients) {
            if (ing.getType() == Material.DIAMOND) {
                return false;
            }
        }
        if (ingredients.size() >= 2) {
            return false;
        }
        ingredients.add(new ItemStack(Material.DIAMOND, diamonds));
        recipe.setIngredients(ingredients);
        return true;
    }

    private int diamondRequirement(Material type) {
        return switch (type) {
            case DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS -> 2;
            case DIAMOND_HELMET, DIAMOND_BOOTS, DIAMOND_SWORD,
                 DIAMOND_PICKAXE, DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_HOE -> 1;
            default -> 0;
        };
    }

    private String recipeKey(MerchantRecipe recipe) {
        // ensureDiamondIngredient adds this ingredient after the original price is
        // recorded. Ignore it for diamond equipment sales so the key stays stable.
        return recipeKey(recipe, diamondRequirement(recipe.getResult().getType()) > 0);
    }

    private String legacyRecipeKey(MerchantRecipe recipe) {
        return recipeKey(recipe, false);
    }

    private String recipeKey(MerchantRecipe recipe, boolean ignoreRebalanceDiamond) {
        List<String> parts = new ArrayList<>();
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient.getType() != Material.AIR
                    && !(ignoreRebalanceDiamond && ingredient.getType() == Material.DIAMOND)) {
                parts.add(ingredient.getType().name());
            }
        }
        Collections.sort(parts);
        parts.add(0, recipe.getResult().getType().name());
        return String.join("|", parts);
    }

    private int rebalanceTargetValue(MerchantRecipe recipe, boolean buy) {
        if (buy) {
            for (ItemStack ingredient : recipe.getIngredients()) {
                if (ingredient.getType() != Material.EMERALD && ingredient.getType() != Material.AIR) {
                    return ingredient.getAmount();
                }
            }
            return -1;
        }
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient.getType() == Material.EMERALD) {
                return ingredient.getAmount();
            }
        }
        return -1;
    }

    private boolean setRebalanceTargetValue(MerchantRecipe recipe, boolean buy, int desired, int current) {
        List<ItemStack> ingredients = recipe.getIngredients();
        for (ItemStack ingredient : ingredients) {
            boolean matches = buy
                    ? (ingredient.getType() != Material.EMERALD && ingredient.getType() != Material.AIR)
                    : (ingredient.getType() == Material.EMERALD);
            if (matches) {
                int maxStack = Math.max(1, ingredient.getMaxStackSize());
                int clamped = Math.max(1, Math.min(maxStack, desired));
                if (clamped == current) {
                    return false;
                }
                ingredient.setAmount(clamped);
                recipe.setIngredients(ingredients);
                return true;
            }
        }
        return false;
    }

    private int scalePrice(int base, int percent) {
        return Math.max(1, (int) Math.round(base * percent / 100.0));
    }

    private int scaleBuyCost(int baseCost, int buyPercent) {
        if (buyPercent <= 0) {
            return baseCost;
        }
        return Math.max(1, (int) Math.round(baseCost * 100.0 / buyPercent));
    }

    private static final class OriginalData {
        final Map<String, Integer> map = new HashMap<>();
        final Map<String, Integer> legacy = new HashMap<>();
    }

    private OriginalData loadOriginals(Villager villager) {
        OriginalData data = new OriginalData();
        String raw = villager.getPersistentDataContainer().get(rebalanceKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return data;
        }
        for (String part : raw.split(";")) {
            int sep = part.lastIndexOf(':');
            if (sep > 0 && sep < part.length() - 1) {
                String k = part.substring(0, sep);
                String v = part.substring(sep + 1);
                try {
                    int value = Integer.parseInt(v);
                    if (k.contains("|")) {
                        data.map.put(k, value);
                    } else {
                        data.legacy.put(k, value);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return data;
    }

    private void saveOriginals(Villager villager, Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        villager.getPersistentDataContainer().set(rebalanceKey, PersistentDataType.STRING, sb.toString());
    }

    public void applyBiomeBooks(Villager villager) {
        applyBiomeBooks(villager, true, true);
    }

    public void applyBiomeBooks(Villager villager, boolean refresh) {
        applyBiomeBooks(villager, refresh, refresh);
    }

    public void applyBiomeBooks(Villager villager, boolean refreshCommons, boolean refreshSpecial) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        if (!bb.enabled) {
            return;
        }
        if (villager.getProfession() != Villager.Profession.LIBRARIAN) {
            return;
        }
        BookGroup group = resolveGroup(villager);
        if (group == null || group.isEmpty()) {
            return;
        }

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        if (!pdc.has(nextBookRotationDayKey, PersistentDataType.LONG)) {
            scheduleNextBookRotation(villager);
        }
        if (!pdc.has(lastSpecialRerollDayKey, PersistentDataType.LONG)) {
            markSpecialRerolledToday(villager);
        }
        if (!pdc.has(nextSpecialRestockDayKey, PersistentDataType.LONG)) {
            MerchantRecipe currentSpecial = findSpecialBookInList(villager.getRecipes(), group);
            scheduleNextSpecialRestock(villager, villager.getWorld().getFullTime() / 24000L, bb, currentSpecial);
        }

        int level = villager.getVillagerLevel();
        boolean master = level >= 5;
        int target = targetBookCount(group, level);
        Integer previousLevel = pdc.get(villagerLevelKey, PersistentDataType.INTEGER);
        boolean newlyMaster = master && (previousLevel == null || previousLevel < 5);
        boolean specialRotation = refreshCommons || refreshSpecial;
        boolean rollSpecial = master && (refreshSpecial
                || (refreshCommons && bb.specialBookChancePercent < 100)
                || newlyMaster);
        Integer storedFailedSpecialRotations = pdc.get(failedSpecialBookRotationsKey, PersistentDataType.INTEGER);
        int pityRotations = Math.max(0, bb.specialBookPityRotations);
        int failedSpecialRotations = storedFailedSpecialRotations == null
                ? 0 : Math.max(0, Math.min(pityRotations, storedFailedSpecialRotations));
        boolean guaranteedSpecial = pityRotations > 0 && failedSpecialRotations >= pityRotations;
        boolean offerSpecial = master && !group.getSpecial().isEmpty()
                && (group.getCommon().isEmpty()
                    || bb.specialBookChancePercent >= 100
                    || (rollSpecial && (guaranteedSpecial
                        || random.nextInt(100) < bb.specialBookChancePercent)));
        if (pityRotations > 0 && rollSpecial && specialRotation && master && !group.getSpecial().isEmpty()
                && !group.getCommon().isEmpty()) {
            pdc.set(failedSpecialBookRotationsKey, PersistentDataType.INTEGER,
                    offerSpecial ? 0 : Math.min(pityRotations, failedSpecialRotations + 1));
            PluginConfig.DailyReroll rotation = plugin.getConfigData().dailyReroll();
            if (!offerSpecial && refreshCommons && rotation.enabled && rotation.bookRotationEnabled) {
                long currentDay = villager.getWorld().getFullTime() / 24000L;
                pdc.set(nextBookRotationDayKey, PersistentDataType.LONG,
                        currentDay + Math.max(1, rotation.bookRotationMinDays));
            }
        } else if (pityRotations == 0) {
            pdc.remove(failedSpecialBookRotationsKey);
        }

        List<MerchantRecipe> books = new ArrayList<>();
        List<MerchantRecipe> others = new ArrayList<>();
        for (MerchantRecipe recipe : villager.getRecipes()) {
            if (EnchantUtil.isStoredBook(recipe.getResult())) {
                books.add(recipe);
            } else {
                others.add(recipe);
            }
        }

        while (books.size() > target) {
            books.remove(books.size() - 1);
        }

        boolean specialBookChanged = false;
        if (refreshCommons || refreshSpecial) {
            Set<Enchantment> previousEnchants = new HashSet<>();
            for (MerchantRecipe book : books) {
                previousEnchants.addAll(EnchantUtil.getStoredEnchants(book.getResult()).keySet());
            }
            MerchantRecipe existingSpecial = (master && !group.getSpecial().isEmpty())
                    ? findSpecialBookInList(books, group)
                    : null;
            List<MerchantRecipe> existingCommons = new ArrayList<>();
            for (MerchantRecipe book : books) {
                if (book != existingSpecial) {
                    existingCommons.add(book);
                }
            }

            books.clear();
            Set<Enchantment> used = new HashSet<>();
            if (!refreshCommons) {
                for (MerchantRecipe common : existingCommons) {
                    used.addAll(EnchantUtil.getStoredEnchants(common.getResult()).keySet());
                }
            }

            if (offerSpecial && target > 0) {
                MerchantRecipe specialBook;
                if (!rollSpecial && existingSpecial != null) {
                    if (existingSpecial.getUses() > 0 && isSpecialRestockDue(villager, existingSpecial)) {
                        existingSpecial.setUses(0);
                        existingSpecial.setMaxUses(randomSpecialUses(villager, existingSpecial));
                    }
                    specialBook = existingSpecial;
                } else {
                    Set<Enchantment> excluded = new HashSet<>(used);
                    excluded.addAll(previousEnchants);
                    specialBook = buildBookRecipe(group, true, true, excluded);
                    specialBookChanged = specialBook != null;
                }
                if (specialBook != null) {
                    books.add(specialBook);
                    used.addAll(EnchantUtil.getStoredEnchants(specialBook.getResult()).keySet());
                }
            }

            if (!refreshCommons) {
                for (MerchantRecipe common : existingCommons) {
                    if (books.size() >= target) {
                        break;
                    }
                    books.add(common);
                }
            }

            while (books.size() < target) {
                boolean needSpecial = books.isEmpty() && offerSpecial;
                Set<Enchantment> excluded = new HashSet<>(used);
                excluded.addAll(previousEnchants);
                MerchantRecipe recipe = buildBookRecipe(group, master, needSpecial, excluded);
                if (recipe == null) {
                    break;
                }
                books.add(recipe);
                used.addAll(EnchantUtil.getStoredEnchants(recipe.getResult()).keySet());
                if (needSpecial) {
                    specialBookChanged = true;
                }
            }
        } else {
            if (newlyMaster && bb.specialBookChancePercent < 100) {
                MerchantRecipe existingSpecial = findSpecialBookInList(books, group);
                if (existingSpecial != null) {
                    books.remove(existingSpecial);
                }
            }
            Set<Enchantment> used = new HashSet<>();
            for (MerchantRecipe book : books) {
                used.addAll(EnchantUtil.getStoredEnchants(book.getResult()).keySet());
            }
            boolean hasSpecial = hasSpecialBookInList(books, group);
            while (books.size() < target) {
                boolean needSpecial = newlyMaster && offerSpecial && !hasSpecial;
                MerchantRecipe recipe = buildBookRecipe(group, master, needSpecial, used);
                if (recipe == null) {
                    break;
                }
                books.add(recipe);
                used.addAll(EnchantUtil.getStoredEnchants(recipe.getResult()).keySet());
                if (needSpecial) {
                    hasSpecial = true;
                    specialBookChanged = true;
                }
            }
            if (newlyMaster && offerSpecial && !hasSpecial && !books.isEmpty()) {
                MerchantRecipe specialRecipe = buildBookRecipe(group, true, true, used);
                if (specialRecipe != null) {
                    books.set(books.size() - 1, specialRecipe);
                    specialBookChanged = true;
                }
            }
        }

        List<MerchantRecipe> updated = new ArrayList<>(books.size() + others.size());
        updated.addAll(books);
        updated.addAll(others);
        villager.setRecipes(updated);

        if (specialBookChanged && offerSpecial) {
            MerchantRecipe currentSpecial = findSpecialBookInList(books, group);
            if (currentSpecial != null) {
                scheduleNextSpecialRestock(villager, villager.getWorld().getFullTime() / 24000L,
                        bb, currentSpecial);
            }
        }

        pdc.set(villagerLevelKey, PersistentDataType.INTEGER, level);
    }

    public boolean hasSpecialBook(Villager villager, BookGroup group) {
        return hasSpecialBookInList(villager.getRecipes(), group);
    }

    private MerchantRecipe findSpecialBookInList(List<MerchantRecipe> recipes, BookGroup group) {
        if (group.getSpecial().isEmpty()) {
            return null;
        }
        Set<Enchantment> specials = new HashSet<>();
        for (BookGroup.SpecialBook sb : group.getSpecial()) {
            specials.add(sb.getEnchant());
        }
        for (MerchantRecipe recipe : recipes) {
            for (Enchantment enchant : EnchantUtil.getStoredEnchants(recipe.getResult()).keySet()) {
                if (specials.contains(enchant)) {
                    return recipe;
                }
            }
        }
        return null;
    }

    private boolean hasSpecialBookInList(List<MerchantRecipe> recipes, BookGroup group) {
        return findSpecialBookInList(recipes, group) != null || group.getSpecial().isEmpty();
    }

    public boolean hasEnchant(Villager villager, Enchantment target) {
        return collectExistingEnchants(villager).contains(target);
    }

    public Set<Enchantment> collectExistingEnchants(Villager villager) {
        Set<Enchantment> result = new HashSet<>();
        for (MerchantRecipe recipe : villager.getRecipes()) {
            result.addAll(EnchantUtil.getStoredEnchants(recipe.getResult()).keySet());
        }
        return result;
    }

    public int countBooks(Villager villager) {
        int count = 0;
        for (MerchantRecipe recipe : villager.getRecipes()) {
            if (EnchantUtil.isStoredBook(recipe.getResult())) {
                count++;
            }
        }
        return count;
    }

    public int getTargetBookCount(Villager villager, BookGroup group) {
        return targetBookCount(group, villager.getVillagerLevel());
    }

    private int targetBookCount(BookGroup group, int level) {
        int pool = group.getCommon().size() + (group.getSpecial().isEmpty() ? 0 : 1);
        int desired = level >= 5 ? 2 : 1;
        return Math.min(desired, pool);
    }

    public MerchantRecipe buildFallbackNonBookRecipe(Villager villager) {
        Set<Material> existing = new HashSet<>();
        for (MerchantRecipe r : villager.getRecipes()) {
            existing.add(r.getResult().getType());
        }
        int level = villager.getVillagerLevel();
        if (level <= 1 && !existing.contains(Material.BOOKSHELF)) {
            MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.BOOKSHELF, 1), 0, 12, true, 2, 0.05f);
            r.addIngredient(new ItemStack(Material.EMERALD, 9));
            return r;
        }
        if (level == 2 && !existing.contains(Material.LANTERN)) {
            MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.LANTERN, 1), 0, 12, true, 5, 0.05f);
            r.addIngredient(new ItemStack(Material.EMERALD, 1));
            return r;
        }
        if (level == 3 && !existing.contains(Material.GLASS)) {
            MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.GLASS, 4), 0, 12, true, 10, 0.05f);
            r.addIngredient(new ItemStack(Material.EMERALD, 1));
            return r;
        }
        if (!existing.contains(Material.CLOCK)) {
            MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.CLOCK, 1), 0, 12, true, 15, 0.05f);
            r.addIngredient(new ItemStack(Material.EMERALD, 5));
            return r;
        }
        if (!existing.contains(Material.COMPASS)) {
            MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.COMPASS, 1), 0, 12, true, 15, 0.05f);
            r.addIngredient(new ItemStack(Material.EMERALD, 4));
            return r;
        }
        MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.LANTERN, 1), 0, 12, true, 5, 0.05f);
        r.addIngredient(new ItemStack(Material.EMERALD, 1));
        return r;
    }

    public void handleTradeAcquired(Villager villager) {
        if (!villager.isValid() || villager.getProfession() != Villager.Profession.LIBRARIAN) {
            return;
        }
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        if (!bb.enabled) {
            return;
        }
        int level = villager.getVillagerLevel();
        Integer stored = villager.getPersistentDataContainer().get(villagerLevelKey, PersistentDataType.INTEGER);
        applyBiomeBooks(villager, stored == null, stored == null);
        if (stored != null && stored < level) {
            playUpdateEffects(villager, true);
        }
        plugin.trackVillager(villager);
    }

    public MerchantRecipe buildBookRecipe(BookGroup group, boolean master, boolean firstBook) {
        return buildBookRecipe(group, master, firstBook, Collections.emptySet());
    }

    public MerchantRecipe buildBookRecipe(BookGroup group, boolean master, boolean firstBook, Set<Enchantment> excluded) {
        if (master && firstBook && !group.getSpecial().isEmpty()) {
            BookGroup.SpecialBook special = pickSpecialBook(group, excluded);
            Enchantment enchant = special.getEnchant();
            int level = special.getLevel();
            int price = specialPrice(special);

            PluginConfig.DualBooks dual = plugin.getConfigData().dualBooks();
            if (dual.enabled && random.nextInt(100) < dual.chancePercent) {
                Enchantment second = randomCompatibleCommon(group, enchant);
                if (second != null) {
                    int secondMax = Math.max(1, Math.min(dual.secondaryMaxLevel, second.getMaxLevel()));
                    int secondLevel = 1 + random.nextInt(secondMax);
                    int dualPrice = Math.min(64, Math.max(1, (int) Math.round(price * dual.priceMultiplier)));
                    return makeBookRecipe(enchant, level, dualPrice, second, secondLevel,
                            Math.max(randomSpecialUses(enchant, level), dual.maxUses));
                }
            }
            PluginConfig.CursedDualBooks cursedDual = plugin.getConfigData().cursedDualBooks();
            if (cursedDual.enabled && random.nextInt(100) < cursedDual.chancePercent) {
                Enchantment curse = randomCompatibleCurse(group, enchant, excluded);
                if (curse != null) {
                    int discountedPrice = Math.max(1, (int) Math.round(price * cursedPriceMultiplier(curse)));
                    return makeBookRecipe(enchant, level, discountedPrice, curse, 1,
                            cursedMaxUses(curse), price);
                }
            }
            return makeBookRecipe(enchant, level, price, null, 0, randomSpecialUses(enchant, level));
        }

        if (!group.getCommon().isEmpty()) {
            List<Enchantment> commons = new ArrayList<>();
            for (Enchantment c : group.getCommon()) {
                if (!c.isCursed() && (excluded == null || !excluded.contains(c))) {
                    commons.add(c);
                }
            }
            if (commons.isEmpty()) {
                for (Enchantment c : group.getCommon()) {
                    if (!c.isCursed()) {
                        commons.add(c);
                    }
                }
            }
            if (commons.isEmpty()) {
                return master && !group.getSpecial().isEmpty()
                        ? makeSpecialBookRecipe(group, excluded)
                        : null;
            }
            Enchantment enchant = commons.get(random.nextInt(commons.size()));
            int level = 1 + random.nextInt(enchant.getMaxLevel());
            int price = randomPrice(enchant, level);
            PluginConfig.CursedDualBooks cursedDual = plugin.getConfigData().cursedDualBooks();
            if (cursedDual.enabled && !enchant.isCursed()
                    && random.nextInt(100) < cursedDual.chancePercent) {
                Enchantment curse = randomCompatibleCurse(group, enchant, excluded);
                if (curse != null) {
                    int discountedPrice = Math.max(1, (int) Math.round(price * cursedPriceMultiplier(curse)));
                    return makeBookRecipe(enchant, level, discountedPrice, curse, 1,
                            cursedMaxUses(curse), price);
                }
            }
            return makeBookRecipe(enchant, level, price);
        }

        return master && !group.getSpecial().isEmpty()
                ? makeSpecialBookRecipe(group, excluded)
                : null;
    }

    private MerchantRecipe makeSpecialBookRecipe(BookGroup group, Set<Enchantment> excluded) {
        BookGroup.SpecialBook special = pickSpecialBook(group, excluded);
        return makeBookRecipe(special.getEnchant(), special.getLevel(), specialPrice(special),
                null, 0, randomSpecialUses(special.getEnchant(), special.getLevel()));
    }

    private int randomSpecialUses(Enchantment enchant, int level) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        int mergeOperations = enchant == null ? 0 : Math.max(0, enchant.getMaxLevel() - level);
        int min = mergeOperations >= 2 ? bb.multiMergeSpecialMaxUsesMin
                : mergeOperations == 1 ? bb.singleMergeSpecialMaxUsesMin : bb.specialMaxUsesMin;
        int max = mergeOperations >= 2 ? bb.multiMergeSpecialMaxUsesMax
                : mergeOperations == 1 ? bb.singleMergeSpecialMaxUsesMax : bb.specialMaxUsesMax;
        min = Math.max(1, min);
        max = Math.max(min, max);
        return min + random.nextInt(max - min + 1);
    }

    private int randomSpecialUses(Villager villager, MerchantRecipe recipe) {
        Enchantment curse = EnchantUtil.getStoredEnchants(recipe.getResult()).keySet().stream()
                .filter(Enchantment::isCursed).findFirst().orElse(null);
        if (curse != null) {
            return cursedMaxUses(curse);
        }
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        int mergeOperations = specialMergeOperations(villager, recipe);
        int min = mergeOperations >= 2 ? bb.multiMergeSpecialMaxUsesMin
                : mergeOperations == 1 ? bb.singleMergeSpecialMaxUsesMin : bb.specialMaxUsesMin;
        int max = mergeOperations >= 2 ? bb.multiMergeSpecialMaxUsesMax
                : mergeOperations == 1 ? bb.singleMergeSpecialMaxUsesMax : bb.specialMaxUsesMax;
        min = Math.max(1, min);
        max = Math.max(min, max);
        return min + random.nextInt(max - min + 1);
    }

    private int specialMergeOperations(Villager villager, MerchantRecipe recipe) {
        if (recipe == null) {
            return 0;
        }
        BookGroup group = resolveGroup(villager);
        if (group == null) {
            return 0;
        }
        Map<Enchantment, Integer> stored = EnchantUtil.getStoredEnchants(recipe.getResult());
        for (BookGroup.SpecialBook special : group.getSpecial()) {
            Integer level = stored.get(special.getEnchant());
            if (level != null) {
                return Math.max(0, special.getEnchant().getMaxLevel() - level);
            }
        }
        return 0;
    }

    private BookGroup.SpecialBook pickSpecialBook(BookGroup group, Set<Enchantment> excluded) {
        List<BookGroup.SpecialBook> specials = new ArrayList<>();
        for (BookGroup.SpecialBook sb : group.getSpecial()) {
            if (excluded == null || !excluded.contains(sb.getEnchant())) {
                specials.add(sb);
            }
        }
        if (specials.isEmpty()) {
            specials.addAll(group.getSpecial());
        }
        return specials.get(random.nextInt(specials.size()));
    }

    public MerchantRecipe makeBookRecipe(Enchantment enchant, int level, int price) {
        return makeBookRecipe(enchant, level, price, null, 0, plugin.getConfigData().biomeBooks().maxUses);
    }

    private Enchantment randomCompatibleCommon(BookGroup group, Enchantment primary) {
        List<Enchantment> candidates = plugin.getConfigData().biomeBooks().compatibilityIndex
                .dualCandidates(group.getName(), primary);
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    private Enchantment randomCompatibleCurse(BookGroup group, Enchantment primary, Set<Enchantment> excluded) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        List<Enchantment> candidates = new ArrayList<>();
        for (Enchantment enchant : bb.compatibilityIndex.cursedCandidates(group.getName(), primary)) {
            if (excluded == null || !excluded.contains(enchant)) {
                candidates.add(enchant);
            }
        }
        if (candidates.isEmpty() && excluded != null && !excluded.isEmpty()) {
            for (Enchantment enchant : bb.compatibilityIndex.cursedCandidates(null, primary)) {
                if (!excluded.contains(enchant)) {
                    candidates.add(enchant);
                }
            }
        }
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    public MerchantRecipe makeBookRecipe(Enchantment enchant, int level, int price, Enchantment second, int secondLevel, int uses) {
        return makeBookRecipe(enchant, level, price, second, secondLevel, uses, 0);
    }

    private MerchantRecipe makeBookRecipe(Enchantment enchant, int level, int price, Enchantment second,
                                          int secondLevel, int uses, int primaryBasePrice) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        ItemStack book = EnchantUtil.makeBook(enchant, level, second, secondLevel);
        if (primaryBasePrice > 0 && second != null && second.isCursed()) {
            org.bukkit.inventory.meta.ItemMeta meta = book.getItemMeta();
            meta.getPersistentDataContainer().set(primaryBookBasePriceKey, PersistentDataType.INTEGER, primaryBasePrice);
            book.setItemMeta(meta);
        }
        int clampedPrice = Math.min(64, Math.max(1, price));

        MerchantRecipe recipe = new MerchantRecipe(book, 0, Math.max(1, uses), true, Math.max(0, bb.experienceReward), bb.priceMultiplier);
        recipe.addIngredient(new ItemStack(Material.EMERALD, clampedPrice));
        recipe.addIngredient(new ItemStack(Material.BOOK, 1));
        return recipe;
    }

    public int primaryBookBasePrice(ItemStack book) {
        if (book == null || !book.hasItemMeta()) {
            return 0;
        }
        return book.getItemMeta().getPersistentDataContainer()
                .getOrDefault(primaryBookBasePriceKey, PersistentDataType.INTEGER, 0);
    }

    private double cursedPriceMultiplier(Enchantment curse) {
        PluginConfig.CursedDualBooks cfg = plugin.getConfigData().cursedDualBooks();
        return curse.getKey().getKey().equals("binding_curse")
                ? cfg.bindingPriceMultiplier : cfg.vanishingPriceMultiplier;
    }

    private int cursedMaxUses(Enchantment curse) {
        PluginConfig.CursedDualBooks cfg = plugin.getConfigData().cursedDualBooks();
        return curse.getKey().getKey().equals("binding_curse")
                ? cfg.bindingMaxUses : cfg.vanishingMaxUses;
    }

    public boolean isBookAllowed(Villager villager, Enchantment stored) {
        if (stored == null || stored.isCursed()) {
            return false;
        }
        BookGroup group = resolveGroup(villager);
        if (group == null) {
            return true;
        }
        if (group.getCommon().contains(stored)) {
            return true;
        }
        if (villager.getVillagerLevel() >= 5) {
            for (BookGroup.SpecialBook special : group.getSpecial()) {
                if (special.getEnchant() == stored) {
                    return true;
                }
            }
        }
        return false;
    }

    public BookGroup resolveGroup(Villager villager) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        if (!bb.enabled) {
            return null;
        }
        Biome biome = resolveBiome(villager);
        BookGroup group = matchGroup(biome, bb);
        if (group != null && !group.isEmpty()) {
            return group;
        }
        if (bb.defaultGroup != null && !bb.defaultGroup.isEmpty()) {
            return bb.defaultGroup;
        }
        return bb.groups.getOrDefault("plains", bb.defaultGroup);
    }

    public Biome resolveBiome(Villager villager) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        if (bb.freezeHomeBiome) {
            String stored = villager.getPersistentDataContainer().get(homeBiomeKey, PersistentDataType.STRING);
            if (stored != null && !stored.isEmpty()) {
                String withNs = stored.contains(":") ? stored : "minecraft:" + stored;
                NamespacedKey key = NamespacedKey.fromString(withNs.toLowerCase(Locale.ROOT));
                if (key != null) {
                    Biome biome = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(key);
                    if (biome != null) {
                        return biome;
                    }
                }
            }
            Biome biome = villager.getLocation().getBlock().getBiome();
            villager.getPersistentDataContainer().set(homeBiomeKey, PersistentDataType.STRING, biome.getKey().asString());
            return biome;
        }
        return villager.getLocation().getBlock().getBiome();
    }

    private BookGroup matchGroup(Biome biome, PluginConfig.BiomeBooks bb) {
        String value = biome.getKey().getKey();
        String full = biome.getKey().asString();
        for (BookGroup group : bb.groups.values()) {
            if (group.getBiomes().isEmpty()) {
                continue;
            }
            if (group.matches(value, full)) {
                return group;
            }
        }
        return null;
    }

    public String groupNameForBiome(Biome biome) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        BookGroup group = matchGroup(biome, bb);
        if (group == null || group.isEmpty()) {
            group = (bb.defaultGroup != null && !bb.defaultGroup.isEmpty())
                    ? bb.defaultGroup
                    : bb.groups.getOrDefault("plains", bb.defaultGroup);
        }
        return group != null ? group.getName() : "—";
    }

    public void infoFor(Player player) {
        PluginConfig cfg = plugin.getConfigData();
        if (!cfg.biomeBooks().enabled) {
            player.sendMessage(TextUtil.color(plugin.getMessages().get(player, "books-disabled")));
            return;
        }
        Biome biome = player.getLocation().getBlock().getBiome();
        String msg = plugin.getMessages().get(player, "biome-info")
                .replace("%biome%", biome.getKey().getKey())
                .replace("%group%", groupNameForBiome(biome));
        player.sendMessage(TextUtil.color(msg));
    }

    private int specialPrice(BookGroup.SpecialBook special) {
        int base = special.getPrice() > 0 ? special.getPrice() : randomPrice(special.getEnchant(), special.getLevel());
        double multiplier = plugin.getConfigData().biomeBooks().specialPriceMultiplier;
        return Math.min(64, Math.max(1, (int) Math.round(base * multiplier)));
    }

    private int randomPrice(Enchantment enchant, int level) {
        PluginConfig.BiomeBooks bb = plugin.getConfigData().biomeBooks();
        int lvl = Math.max(1, level);
        if (bb.priceFixed > 0) {
            return Math.min(64, Math.max(1, bb.priceFixed * lvl));
        }
        int min = Math.max(1, bb.priceMin + (lvl - 1) * 3);
        int max = Math.max(min, bb.priceMax + (lvl - 1) * 11);
        int price = min + random.nextInt(max - min + 1);
        if (EnchantUtil.isTreasure(enchant)) {
            price = (int) Math.round(price * 1.5);
        }
        return Math.min(64, Math.max(1, price));
    }
}
