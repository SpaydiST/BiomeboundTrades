package org.example1.biomeboundTrades.listener;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.example1.biomeboundTrades.BiomeboundTrades;
import org.example1.biomeboundTrades.config.PluginConfig;
import org.example1.biomeboundTrades.util.EnchantUtil;
import org.example1.biomeboundTrades.util.ProgressionAdvancement;
import org.example1.biomeboundTrades.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TradeInflationListener implements Listener {

    private static final long REPUTATION_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    private final BiomeboundTrades plugin;
    private final Random random = new Random();

    private final Map<MarketKey, Map<NamespacedKey, Counter>> marketCounters = new HashMap<>();
    private final Map<UUID, Map<NamespacedKey, Counter>> legacyCounters = new HashMap<>();
    private final Map<UUID, Map<VillageKey, Integer>> reputation = new HashMap<>();
    private final Map<UUID, Map<SpatialCell, Set<VillageKey>>> reputationIndex = new HashMap<>();
    private final Map<UUID, Long> reputationSeen = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> purchases = new HashMap<>();
    private final Map<UUID, Map<UUID, OfferEntry>> offeredDays = new HashMap<>();
    private final Map<UUID, SaleEntry> dailySales = new HashMap<>();
    private final Map<UUID, PriceSession> priceSessions = new HashMap<>();
    private final Map<UUID, SeasonalEntry> seasonalStocks = new HashMap<>();
    private final NamespacedKey seasonalBaseUsesKey;
    private final NamespacedKey seasonalRecipeKey;
    private final AtomicBoolean dataDirty = new AtomicBoolean();
    private final ExecutorService dataWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BiomeboundTrades-data-writer");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);

    public TradeInflationListener(BiomeboundTrades plugin) {
        this.plugin = plugin;
        this.seasonalBaseUsesKey = new NamespacedKey(plugin, "seasonal_base_uses");
        this.seasonalRecipeKey = new NamespacedKey(plugin, "seasonal_recipe_key");
        loadData();
    }

    public void stopGossip() {
        // Legacy lifecycle hook; village counters no longer need a gossip scan.
    }

    public void restoreActivePrices() {
        for (Map.Entry<UUID, PriceSession> entry : priceSessions.entrySet()) {
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entity instanceof Villager villager) {
                restorePrices(villager, entry.getValue());
            }
        }
        priceSessions.clear();
    }

    public void restartGossip() { }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof AbstractVillager || entity instanceof ZombieVillager) {
            UUID id = entity.getUniqueId();
            migrateLegacyCounters(id, entity.getWorld(), entity.getLocation(),
                    entity.getWorld().getFullTime() / 24000L);
            purchases.remove(id);
            offeredDays.remove(id);
            dailySales.remove(id);
            priceSessions.remove(id);
            markDataDirty();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (!(event.getVillager() instanceof Villager villager)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        PluginConfig cfg = plugin.getConfigData();
        MerchantRecipe traded = event.getTrade();
        ItemStack result = traded.getResult();
        if (!EnchantUtil.isStoredBook(result)) {
            return;
        }
        plugin.getTradeManager().onBookTraded(villager, traded);
        plugin.trackVillager(villager);
        // Ordinary dual books are exempt; cursed pairs use their primary enchantment.
        Enchantment enchant = inflationEnchant(result);
        NamespacedKey enchantKey = enchant != null ? enchant.getKey() : null;
        long day = villager.getWorld().getFullTime() / 24000L;

        if (cfg.inflation().enabled && enchantKey != null) {
            migrateLegacyCounters(villager.getUniqueId(), villager.getWorld(), villager.getLocation(), day);
            MarketKey market = marketKey(villager, cfg.inflation().regionSize);
            Counter counter = marketCounters.computeIfAbsent(market, k -> new HashMap<>())
                    .computeIfAbsent(enchantKey, k -> new Counter());
            if (decayCounter(counter, day, cfg.deflation())) {
                markDataDirty();
            }
            int maxCount = 1 + (int) Math.ceil(cfg.inflation().maxPercent
                    / (double) cfg.inflation().percentPerRepeat);
            counter.count = Math.min(maxCount, counter.count + 1);
            counter.lastPurchaseDay = day;
            counter.lastDeflationDay = day;
            if (counter.count >= 2 && cfg.inflation().percentPerRepeat > 0 && cfg.inflation().maxPercent > 0) {
                ProgressionAdvancement.award(player, "biomebound_trades_market");
            }
            markDataDirty();
        }

        if (cfg.reputation().enabled) {
            Map<VillageKey, Integer> playerRep = reputation.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
            VillageKey key = resolveVillageKey(player.getUniqueId(), playerRep, villager, cfg.reputation().radius);
            if (!playerRep.containsKey(key)) {
                indexReputation(player.getUniqueId(), key);
            }
            playerRep.merge(key, 1, Integer::sum);
            reputationSeen.put(player.getUniqueId(), System.currentTimeMillis());
            markDataDirty();
        }

        if (cfg.specialOffer().enabled) {
            purchases.computeIfAbsent(villager.getUniqueId(), k -> new HashMap<>())
                    .merge(player.getUniqueId(), 1, Integer::sum);
            markDataDirty();
        }

    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void awardSpecialBookAdvancement(PlayerTradeEvent event) {
        if (!(event.getVillager() instanceof Villager villager)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }

        MerchantRecipe traded = event.getTrade();
        ItemStack result = traded.getResult();
        boolean specialBook = plugin.getTradeManager().isSpecialBookRecipe(villager, traded);
        boolean seasonalBook = result.hasItemMeta()
                && result.getItemMeta().getPersistentDataContainer()
                .has(seasonalRecipeKey, org.bukkit.persistence.PersistentDataType.STRING);
        Map<Enchantment, Integer> storedEnchants = EnchantUtil.getStoredEnchants(result);
        boolean cursedDualBook = storedEnchants.size() > 1
                && storedEnchants.keySet().stream().anyMatch(Enchantment::isCursed);
        boolean regularDualBook = storedEnchants.size() > 1 && !cursedDualBook;
        boolean biomeBook = plugin.getConfigData().biomeBooks().enabled
                && storedEnchants.keySet().stream()
                .anyMatch(enchantment -> plugin.getTradeManager().isBookAllowed(villager, enchantment));

        if (specialBook || seasonalBook || cursedDualBook || biomeBook) {
            ProgressionAdvancement.award(player);
        }
        if (seasonalBook) {
            ProgressionAdvancement.award(player, "biomebound_trades_seasonal");
        }
        if (cursedDualBook) {
            ProgressionAdvancement.award(player, "biomebound_trades_cursed");
        }
        if (regularDualBook) {
            ProgressionAdvancement.award(player, "biomebound_trades_dual");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void awardProfessionRebalance(PlayerTradeEvent event) {
        if (!(event.getVillager() instanceof Villager villager)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        PluginConfig cfg = plugin.getConfigData();
        if (cfg.professionRebalance().enabled
                && plugin.getTradeManager().hasProfessionRebalance(villager)) {
            ProgressionAdvancement.award(player, "biomebound_trades_profession");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory merchantInventory)) {
            return;
        }
        if (!(merchantInventory.getMerchant() instanceof Villager villager)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID villagerId = villager.getUniqueId();
        PriceSession active = priceSessions.get(villagerId);
        if (active != null) {
            if (!active.playerId.equals(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(TextUtil.color(plugin.getMessages().get(player, "merchant-busy")));
                return;
            }
            restorePrices(villager, active);
            priceSessions.remove(villagerId);
        }
        plugin.getTradeManager().checkPendingBookRotation(villager);
        applySeasonalStock(villager);
        plugin.trackVillager(villager);
        PluginConfig cfg = plugin.getConfigData();
        List<MerchantRecipe> recipes = villager.getRecipes();
        if (recipes.isEmpty()) {
            return;
        }

        List<Integer> basePrices = new ArrayList<>(recipes.size());
        List<ItemStack> results = new ArrayList<>(recipes.size());
        for (MerchantRecipe recipe : recipes) {
            basePrices.add(recipe.getSpecialPrice());
            results.add(recipe.getResult().clone());
        }

        if (cfg.reputation().enabled) {
            applyReputation(player, villager, recipes, cfg.reputation());
        }
        if (cfg.dailySale().enabled) {
            applyDailySale(player, villager, recipes, cfg.dailySale());
        }
        if (cfg.specialOffer().enabled) {
            grantSpecialOffer(player, villager, recipes, cfg.specialOffer());
        }
        if (cfg.inflation().enabled) {
            applyInflation(villager, recipes, cfg.inflation());
        }
        capTotalDiscount(recipes);

        villager.setRecipes(recipes);
        List<Integer> adjustedPrices = new ArrayList<>(recipes.size());
        for (MerchantRecipe recipe : recipes) {
            adjustedPrices.add(recipe.getSpecialPrice());
        }
        priceSessions.put(villagerId, new PriceSession(player.getUniqueId(), basePrices, adjustedPrices, results));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory merchantInventory)
                || !(merchantInventory.getMerchant() instanceof Villager villager)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        PriceSession session = priceSessions.get(villager.getUniqueId());
        if (session != null && session.playerId.equals(player.getUniqueId())) {
            restorePrices(villager, session);
            priceSessions.remove(villager.getUniqueId());
        }
    }

    private void restorePrices(Villager villager, PriceSession session) {
        if (!villager.isValid()) {
            return;
        }
        List<MerchantRecipe> recipes = villager.getRecipes();
        if (recipes.size() != session.basePrices.size()) {
            return;
        }
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            if (recipe.getResult().isSimilar(session.results.get(i))
                    && recipe.getSpecialPrice() == session.adjustedPrices.get(i)) {
                recipe.setSpecialPrice(session.basePrices.get(i));
            }
        }
        villager.setRecipes(recipes);
    }

    private void capTotalDiscount(List<MerchantRecipe> recipes) {
        for (MerchantRecipe recipe : recipes) {
            if (!EnchantUtil.isStoredBook(recipe.getResult())) {
                continue;
            }
            int base = basePrice(recipe);
            if (base <= 0) {
                continue;
            }
            int maxDiscount = plugin.getConfigData().biomeBooks().maxTotalDiscountPercent;
            int minimumPrice = Math.max(1, (int) Math.ceil(base * (100.0 - maxDiscount) / 100.0));
            int currentPrice = ingredientPrice(recipe) + recipe.getSpecialPrice();
            if (currentPrice < minimumPrice) {
                recipe.setSpecialPrice(recipe.getSpecialPrice() + minimumPrice - currentPrice);
            }
        }
    }

    private void applyReputation(Player player, Villager villager, List<MerchantRecipe> recipes, PluginConfig.Reputation rep) {
        Map<VillageKey, Integer> playerRep = reputation.get(player.getUniqueId());
        if (playerRep == null || playerRep.isEmpty()) {
            return;
        }
        int repCount = lookupReputation(player.getUniqueId(), playerRep, villager, rep.radius);
        int discount = Math.min(repCount * rep.percentPerBook, rep.maxPercent);
        if (discount <= 0) {
            return;
        }
        for (MerchantRecipe recipe : recipes) {
            if (!EnchantUtil.isStoredBook(recipe.getResult())) {
                continue;
            }
            int base = basePrice(recipe);
            if (base <= 0) {
                continue;
            }
            int cut = (int) Math.round(base * discount / 100.0);
            if (cut > 0) {
                recipe.setSpecialPrice(Math.max(-base + 1, recipe.getSpecialPrice() - cut));
                ProgressionAdvancement.award(player, "biomebound_trades_reputation");
            }
        }
    }

    private void applyInflation(Villager villager, List<MerchantRecipe> recipes, PluginConfig.Inflation in) {
        long day = villager.getWorld().getFullTime() / 24000L;
        migrateLegacyCounters(villager.getUniqueId(), villager.getWorld(), villager.getLocation(), day);
        Map<NamespacedKey, Counter> counts = marketCounters.get(marketKey(villager, in.regionSize));
        if (counts == null || counts.isEmpty()) {
            return;
        }
        for (MerchantRecipe recipe : recipes) {
            Enchantment stored = inflationEnchant(recipe.getResult());
            if (stored == null) {
                continue;
            }
            Counter counter = counts.get(stored.getKey());
            if (counter != null && decayCounter(counter, day, plugin.getConfigData().deflation())) {
                markDataDirty();
            }
            if (counter == null || counter.count < 2) {
                continue;
            }
            int maxCount = 1 + (int) Math.ceil(in.percentPerRepeat == 0
                    ? 0 : in.maxPercent / (double) in.percentPerRepeat);
            if (counter.count > maxCount) {
                counter.count = maxCount;
                markDataDirty();
            }
            int totalPercent = Math.min((counter.count - 1) * in.percentPerRepeat, in.maxPercent);
            if (totalPercent <= 0) {
                continue;
            }
            int base = basePrice(recipe);
            if (base <= 0) {
                continue;
            }
            int markup = (int) Math.round(base * totalPercent / 100.0);
            if (markup > 0) {
                recipe.setSpecialPrice(recipe.getSpecialPrice() + markup);
            }
        }
    }

    private void applyDailySale(Player player, Villager villager, List<MerchantRecipe> recipes,
                                PluginConfig.DailySale sale) {
        SaleEntry entry = dailySales.get(villager.getUniqueId());
        if (entry == null || !entry.worldUid.equals(villager.getWorld().getUID())) {
            return;
        }
        for (MerchantRecipe recipe : recipes) {
            Enchantment stored = EnchantUtil.getStoredEnchant(recipe.getResult());
            if (stored == null || !stored.getKey().equals(entry.enchant)) {
                continue;
            }
            int base = basePrice(recipe);
            if (base <= 0) {
                continue;
            }
            int cut = (int) Math.round(base * sale.discountPercent / 100.0);
            if (cut > 0) {
                recipe.setSpecialPrice(Math.max(-base + 1, recipe.getSpecialPrice() - cut));
                ProgressionAdvancement.award(player, "biomebound_trades_sale");
            }
        }
    }

    private void grantSpecialOffer(Player player, Villager villager, List<MerchantRecipe> recipes, PluginConfig.SpecialOffer offer) {
        UUID villagerId = villager.getUniqueId();
        UUID playerId = player.getUniqueId();
        long day = villager.getWorld().getFullTime() / 24000L;

        Map<UUID, OfferEntry> villagerOffers = offeredDays.get(villagerId);
        OfferEntry existing = villagerOffers != null ? villagerOffers.get(playerId) : null;
        if (existing != null && existing.day == day) {
            if (existing.recipeIndex >= 0 && existing.recipeIndex < recipes.size()) {
                MerchantRecipe target = recipes.get(existing.recipeIndex);
                if (EnchantUtil.isStoredBook(target.getResult())) {
                    if (applySpecialOfferDiscount(target, offer.discountPercent)) {
                        ProgressionAdvancement.award(player, "biomebound_trades_offer");
                    }
                }
            }
            return;
        }

        int count = purchases.getOrDefault(villagerId, Collections.emptyMap()).getOrDefault(playerId, 0);
        if (count < offer.purchasesRequired) {
            return;
        }

        List<Integer> bookIndices = new ArrayList<>();
        for (int i = 0; i < recipes.size(); i++) {
            if (EnchantUtil.isStoredBook(recipes.get(i).getResult())) {
                bookIndices.add(i);
            }
        }
        if (bookIndices.isEmpty()) {
            return;
        }

        purchases.computeIfAbsent(villagerId, k -> new HashMap<>()).put(playerId, 0);
        if (random.nextInt(100) >= offer.chancePercent) {
            offeredDays.computeIfAbsent(villagerId, k -> new HashMap<>()).put(playerId, new OfferEntry(day, -1));
            return;
        }

        int chosenIndex = bookIndices.get(random.nextInt(bookIndices.size()));
        if (applySpecialOfferDiscount(recipes.get(chosenIndex), offer.discountPercent)) {
            ProgressionAdvancement.award(player, "biomebound_trades_offer");
        }
        offeredDays.computeIfAbsent(villagerId, k -> new HashMap<>()).put(playerId, new OfferEntry(day, chosenIndex));
    }

    private boolean applySpecialOfferDiscount(MerchantRecipe target, int discountPercent) {
        int base = basePrice(target);
        if (base > 0) {
            int cut = (int) Math.round(base * discountPercent / 100.0);
            if (cut > 0) {
                target.setSpecialPrice(Math.max(-base + 1, target.getSpecialPrice() - cut));
                return true;
            }
        }
        return false;
    }

    public void ensureDailySale(World world) {
        if (!plugin.getConfigData().dailySale().enabled) {
            return;
        }
        for (SaleEntry entry : dailySales.values()) {
            if (entry.worldUid.equals(world.getUID())) {
                return;
            }
        }
        selectDailySale(world);
    }

    public void ensureSeasonalStock(World world) {
        if (!plugin.getConfigData().seasonalStock().enabled) {
            return;
        }
        seasonalStocks.computeIfAbsent(world.getUID(), ignored -> {
            SeasonalEntry created = new SeasonalEntry();
            created.lastRollDay = world.getFullTime() / 24000L;
            markDataDirty();
            return created;
        });
        advanceSeason(world);
    }

    private void advanceSeason(World world) {
        PluginConfig.SeasonalStock cfg = plugin.getConfigData().seasonalStock();
        if (!cfg.enabled) {
            return;
        }
        long day = world.getFullTime() / 24000L;
        SeasonalEntry entry = seasonalStocks.computeIfAbsent(world.getUID(), ignored -> {
            SeasonalEntry created = new SeasonalEntry();
            created.lastRollDay = day;
            markDataDirty();
            return created;
        });
        if (entry.enchant != null && day >= entry.endDay) {
            entry.group = null;
            entry.enchant = null;
            entry.startDay = -1L;
            entry.endDay = -1L;
            markDataDirty();
        }
        if (entry.enchant != null || day - entry.lastRollDay < cfg.intervalDays) {
            return;
        }
        entry.lastRollDay = day;
        markDataDirty();
        if (random.nextInt(100) >= cfg.chancePercent) {
            return;
        }

        List<SeasonCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, org.example1.biomeboundTrades.config.BookGroup> groupEntry
                : plugin.getConfigData().biomeBooks().groups.entrySet()) {
            org.example1.biomeboundTrades.config.BookGroup group = groupEntry.getValue();
            Set<Enchantment> specialEnchants = new HashSet<>();
            group.getSpecial().forEach(special -> specialEnchants.add(special.getEnchant()));
            for (Enchantment enchant : group.getCommon()) {
                if (!enchant.isCursed() && !specialEnchants.contains(enchant)) {
                    candidates.add(new SeasonCandidate(groupEntry.getKey(), enchant));
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        SeasonCandidate selected = candidates.get(random.nextInt(candidates.size()));
        entry.group = selected.group;
        entry.enchant = selected.enchant.getKey();
        entry.startDay = day;
        entry.endDay = day + cfg.durationDays;
        markDataDirty();
        plugin.getLogger().info("Сезонный ассортимент: " + entry.group + " — "
                + entry.enchant + " (" + cfg.durationDays + " игровых дней).");
    }

    private void applySeasonalStock(Villager villager) {
        SeasonalEntry season = seasonalStocks.get(villager.getWorld().getUID());
        long day = villager.getWorld().getFullTime() / 24000L;
        boolean active = plugin.getConfigData().seasonalStock().enabled
                && season != null && season.enchant != null
                && day >= season.startDay && day < season.endDay;
        org.example1.biomeboundTrades.config.BookGroup group = active
                ? plugin.getTradeManager().resolveGroup(villager) : null;
        boolean groupMatches = active && group != null && group.getName().equalsIgnoreCase(season.group);
        List<MerchantRecipe> recipes = villager.getRecipes();
        boolean changed = false;
        String targetSignature = null;
        MerchantRecipe targetRecipe = null;
        if (groupMatches) {
            for (MerchantRecipe recipe : recipes) {
                Map<Enchantment, Integer> enchants = EnchantUtil.getStoredEnchants(recipe.getResult());
                if (enchants.size() == 1 && enchants.keySet().iterator().next().getKey().equals(season.enchant)) {
                    targetRecipe = recipe;
                    targetSignature = recipeSignature(recipe.getResult());
                    break;
                }
            }
        }

        for (MerchantRecipe recipe : recipes) {
            if (!EnchantUtil.isStoredBook(recipe.getResult())) {
                continue;
            }
            org.bukkit.persistence.PersistentDataContainer pdc = recipe.getResult().getItemMeta()
                    .getPersistentDataContainer();
            String storedSignature = pdc.get(seasonalRecipeKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (storedSignature == null || (recipe == targetRecipe
                    && targetSignature != null && targetSignature.equals(storedSignature))) {
                continue;
            }
            Integer baseUses = pdc.get(seasonalBaseUsesKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (baseUses != null) {
                recipe.setMaxUses(baseUses);
                if (recipe.getUses() > baseUses) {
                    recipe.setUses(baseUses);
                }
            }
            org.bukkit.inventory.meta.ItemMeta meta = recipe.getResult().getItemMeta();
            meta.getPersistentDataContainer().remove(seasonalRecipeKey);
            meta.getPersistentDataContainer().remove(seasonalBaseUsesKey);
            recipe.getResult().setItemMeta(meta);
            changed = true;
        }

        if (targetRecipe != null) {
            org.bukkit.inventory.meta.ItemMeta meta = targetRecipe.getResult().getItemMeta();
            org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();
            String storedSignature = pdc.get(seasonalRecipeKey, org.bukkit.persistence.PersistentDataType.STRING);
            Integer baseUses = pdc.get(seasonalBaseUsesKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (!targetSignature.equals(storedSignature) || baseUses == null) {
                baseUses = targetRecipe.getMaxUses();
                pdc.set(seasonalRecipeKey, org.bukkit.persistence.PersistentDataType.STRING, targetSignature);
                pdc.set(seasonalBaseUsesKey, org.bukkit.persistence.PersistentDataType.INTEGER, baseUses);
                targetRecipe.getResult().setItemMeta(meta);
            }
            int desiredUses = Math.min(64, baseUses + plugin.getConfigData().seasonalStock().bonusUses);
            if (targetRecipe.getMaxUses() != desiredUses) {
                targetRecipe.setMaxUses(desiredUses);
                changed = true;
            }
        }
        if (changed) {
            villager.setRecipes(recipes);
        }
    }

    private String recipeSignature(ItemStack result) {
        List<String> entries = new ArrayList<>();
        EnchantUtil.getStoredEnchants(result).forEach((enchant, level) ->
                entries.add(enchant.getKey().asString() + ":" + level));
        Collections.sort(entries);
        return String.join("|", entries);
    }

    private List<Villager> loadedVillagers(World world) {
        if (plugin.getRerollTask() != null) {
            return plugin.getRerollTask().loadedVillagers(world);
        }
        return world.getEntitiesByClass(Villager.class).stream().toList();
    }

    public void onNewDay(World world) {
        selectDailySale(world);
        advanceSeason(world);
        cleanupStale();
    }

    private void cleanupStale() {
        boolean removedMarket = marketCounters.entrySet().removeIf(entry -> {
            entry.getValue().values().removeIf(counter -> counter.count <= 0);
            return entry.getValue().isEmpty();
        });
        if (removedMarket) {
            markDataDirty();
        }
        purchases.keySet().removeIf(uuid -> {
            Entity entity = plugin.getServer().getEntity(uuid);
            return entity != null && entity.isDead();
        });
        offeredDays.keySet().removeIf(uuid -> {
            Entity entity = plugin.getServer().getEntity(uuid);
            return entity != null && entity.isDead();
        });
        pruneOldPlayers();
    }

    private void pruneOldPlayers() {
        long cutoff = System.currentTimeMillis() - REPUTATION_RETENTION_MILLIS;
        Set<UUID> expired = new HashSet<>();
        reputationSeen.entrySet().removeIf(e -> {
            if (e.getValue() < cutoff) {
                expired.add(e.getKey());
                return true;
            }
            return false;
        });
        reputation.keySet().retainAll(reputationSeen.keySet());
        for (UUID playerId : expired) {
            reputationIndex.remove(playerId);
        }
        if (!expired.isEmpty()) {
            markDataDirty();
        }
    }

    private boolean decayCounter(Counter counter, long currentDay, PluginConfig.Deflation def) {
        if (!def.enabled || counter.count <= 0 || counter.lastPurchaseDay < 0) {
            return false;
        }
        long startDay = Math.max(counter.lastPurchaseDay + def.graceDays,
                counter.lastDeflationDay >= 0 ? counter.lastDeflationDay : counter.lastPurchaseDay);
        long elapsedDays = currentDay - startDay;
        if (elapsedDays > 0) {
            int previous = counter.count;
            counter.count = (int) Math.max(0L, counter.count - elapsedDays * def.perDay);
            counter.lastDeflationDay = currentDay;
            return previous != counter.count;
        }
        return false;
    }

    private void selectDailySale(World world) {
        PluginConfig.DailySale sale = plugin.getConfigData().dailySale();
        dailySales.entrySet().removeIf(e -> e.getValue().worldUid.equals(world.getUID()));
        if (!sale.enabled) {
            return;
        }
        List<Villager> librarians = new ArrayList<>();
        for (Villager villager : loadedVillagers(world)) {
            if (villager.getProfession() == Villager.Profession.LIBRARIAN && !villager.isDead()) {
                librarians.add(villager);
            }
        }
        if (librarians.isEmpty()) {
            return;
        }
        Villager chosen = librarians.get(random.nextInt(librarians.size()));
        List<MerchantRecipe> books = new ArrayList<>();
        for (MerchantRecipe recipe : chosen.getRecipes()) {
            if (EnchantUtil.getStoredEnchant(recipe.getResult()) != null) {
                books.add(recipe);
            }
        }
        if (books.isEmpty()) {
            return;
        }
        Enchantment stored = EnchantUtil.getStoredEnchant(books.get(random.nextInt(books.size())).getResult());
        dailySales.put(chosen.getUniqueId(), new SaleEntry(world.getUID(), stored.getKey()));
        if (sale.showEffects) {
            Location loc = chosen.getLocation().add(0, 1.0, 0);
            world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 20, 0.5, 0.5, 0.5, 0.01);
            world.playSound(loc, Sound.ENTITY_VILLAGER_YES, 0.8f, 1.0f);
        }
    }

    private VillageKey resolveVillageKey(UUID playerId, Map<VillageKey, Integer> playerRep,
                                         Villager villager, int radius) {
        migrateLegacyReputation(playerId, playerRep, villager, radius);
        Location loc = villager.getLocation();
        UUID worldUid = villager.getWorld().getUID();
        VillageKey nearest = null;
        long nearestDistance = (long) radius * radius + 1;
        for (VillageKey candidate : nearbyReputationKeys(playerId, worldUid, loc, radius)) {
            long distance = candidate.distanceSquared(worldUid, loc);
            if (distance <= (long) radius * radius && distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest != null ? nearest : new VillageKey(worldUid,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private int lookupReputation(UUID playerId, Map<VillageKey, Integer> playerRep,
                                 Villager villager, int radius) {
        migrateLegacyReputation(playerId, playerRep, villager, radius);
        Location loc = villager.getLocation();
        UUID worldUid = villager.getWorld().getUID();
        int max = 0;
        for (VillageKey candidate : nearbyReputationKeys(playerId, worldUid, loc, radius)) {
            Integer value = playerRep.get(candidate);
            if (value != null && candidate.distanceSquared(worldUid, loc) <= (long) radius * radius) {
                max = Math.max(max, value);
            }
        }
        return max;
    }

    private Set<VillageKey> nearbyReputationKeys(UUID playerId, UUID worldUid, Location loc, int radius) {
        Set<VillageKey> result = new HashSet<>();
        Map<SpatialCell, Set<VillageKey>> index = reputationIndex.get(playerId);
        if (index == null) {
            return result;
        }
        int reach = Math.max(1, (radius + SpatialCell.SIZE - 1) / SpatialCell.SIZE);
        int cellX = Math.floorDiv(loc.getBlockX(), SpatialCell.SIZE);
        int cellY = Math.floorDiv(loc.getBlockY(), SpatialCell.SIZE);
        int cellZ = Math.floorDiv(loc.getBlockZ(), SpatialCell.SIZE);
        for (int x = cellX - reach; x <= cellX + reach; x++) {
            for (int y = cellY - reach; y <= cellY + reach; y++) {
                for (int z = cellZ - reach; z <= cellZ + reach; z++) {
                    Set<VillageKey> keys = index.get(new SpatialCell(worldUid, x, y, z));
                    if (keys != null) {
                        result.addAll(keys);
                    }
                }
            }
        }
        return result;
    }

    private void migrateLegacyReputation(UUID playerId, Map<VillageKey, Integer> playerRep,
                                         Villager villager, int radius) {
        Location loc = villager.getLocation();
        int cell = Math.max(32, radius * 2);
        VillageKey oldKey = VillageKey.legacy(villager.getWorld().getUID(),
                Math.floorDiv(loc.getBlockX(), cell), Math.floorDiv(loc.getBlockZ(), cell));
        Integer oldValue = playerRep.remove(oldKey);
        if (oldValue != null) {
            VillageKey actual = new VillageKey(villager.getWorld().getUID(),
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            playerRep.merge(actual, oldValue, Math::max);
            indexReputation(playerId, actual);
            markDataDirty();
        }
    }

    private void indexReputation(UUID playerId, VillageKey key) {
        if (key.legacy) {
            return;
        }
        SpatialCell cell = SpatialCell.forKey(key);
        reputationIndex.computeIfAbsent(playerId, ignored -> new HashMap<>())
                .computeIfAbsent(cell, ignored -> new HashSet<>()).add(key);
    }

    private void rebuildReputationIndex() {
        reputationIndex.clear();
        for (Map.Entry<UUID, Map<VillageKey, Integer>> playerEntry : reputation.entrySet()) {
            for (VillageKey key : playerEntry.getValue().keySet()) {
                indexReputation(playerEntry.getKey(), key);
            }
        }
    }

    private Enchantment inflationEnchant(ItemStack book) {
        Map<Enchantment, Integer> stored = EnchantUtil.getStoredEnchants(book);
        if (stored.size() == 1) {
            return stored.keySet().iterator().next();
        }
        if (stored.size() == 2) {
            Enchantment primary = null;
            boolean hasCurse = false;
            for (Enchantment enchant : stored.keySet()) {
                if (enchant.isCursed()) {
                    hasCurse = true;
                } else {
                    primary = enchant;
                }
            }
            return hasCurse ? primary : null;
        }
        return null;
    }

    private MarketKey marketKey(Villager villager, int regionSize) {
        Location loc = villager.getLocation();
        int size = Math.max(16, regionSize);
        return new MarketKey(villager.getWorld().getUID(),
                Math.floorDiv(loc.getBlockX(), size), Math.floorDiv(loc.getBlockZ(), size));
    }

    private void migrateLegacyCounters(UUID villagerId, World world, Location location, long day) {
        Map<NamespacedKey, Counter> legacy = legacyCounters.remove(villagerId);
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        int size = Math.max(16, plugin.getConfigData().inflation().regionSize);
        MarketKey market = new MarketKey(world.getUID(), Math.floorDiv(location.getBlockX(), size),
                Math.floorDiv(location.getBlockZ(), size));
        Map<NamespacedKey, Counter> current = marketCounters.computeIfAbsent(market, ignored -> new HashMap<>());
        for (Map.Entry<NamespacedKey, Counter> entry : legacy.entrySet()) {
            Counter candidate = new Counter(entry.getValue());
            decayCounter(candidate, day, plugin.getConfigData().deflation());
            Counter existing = current.get(entry.getKey());
            if (existing == null || candidate.count > existing.count) {
                current.put(entry.getKey(), candidate);
            } else if (candidate.count == existing.count
                    && candidate.lastPurchaseDay > existing.lastPurchaseDay) {
                existing.lastPurchaseDay = candidate.lastPurchaseDay;
                existing.lastDeflationDay = candidate.lastDeflationDay;
            }
        }
        markDataDirty();
    }

    private void markDataDirty() {
        dataDirty.set(true);
    }

    private int basePrice(MerchantRecipe recipe) {
        int primaryBase = plugin.getTradeManager().primaryBookBasePrice(recipe.getResult());
        if (primaryBase > 0) {
            return primaryBase;
        }
        return ingredientPrice(recipe);
    }

    private int ingredientPrice(MerchantRecipe recipe) {
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient.getType() == Material.EMERALD) {
                return ingredient.getAmount();
            }
        }
        return 0;
    }

    public void migrateVillager(UUID oldId, UUID newId) {
        Map<NamespacedKey, Counter> movedCounters = legacyCounters.remove(oldId);
        if (movedCounters != null) {
            Map<NamespacedKey, Counter> existing = legacyCounters.computeIfAbsent(newId, ignored -> new HashMap<>());
            for (Map.Entry<NamespacedKey, Counter> entry : movedCounters.entrySet()) {
                Counter prior = existing.get(entry.getKey());
                if (prior == null || entry.getValue().count > prior.count) {
                    existing.put(entry.getKey(), entry.getValue());
                }
            }
        }
        Map<UUID, Integer> movedPurchases = purchases.remove(oldId);
        if (movedPurchases != null) {
            purchases.put(newId, movedPurchases);
        }
        Map<UUID, OfferEntry> movedOffers = offeredDays.remove(oldId);
        if (movedOffers != null) {
            offeredDays.put(newId, movedOffers);
        }
        SaleEntry sale = dailySales.remove(oldId);
        if (sale != null) {
            dailySales.put(newId, sale);
        }
        markDataDirty();
    }

    public void clearCounters() {
        marketCounters.clear();
        legacyCounters.clear();
        reputation.clear();
        reputationIndex.clear();
        reputationSeen.clear();
        purchases.clear();
        offeredDays.clear();
        dailySales.clear();
        seasonalStocks.clear();
        markDataDirty();
    }

    public void saveData() {
        if (!dataDirty.get()) {
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();

        for (Map.Entry<MarketKey, Map<NamespacedKey, Counter>> e : marketCounters.entrySet()) {
            MarketKey market = e.getKey();
            String vKey = "market-counters." + market.worldUid + "_" + market.cellX + "_" + market.cellZ;
            for (Map.Entry<NamespacedKey, Counter> ce : e.getValue().entrySet()) {
                writeCounter(yaml, vKey, ce.getKey(), ce.getValue());
            }
        }
        for (Map.Entry<UUID, Map<NamespacedKey, Counter>> e : legacyCounters.entrySet()) {
            String vKey = "legacy-counters." + e.getKey();
            for (Map.Entry<NamespacedKey, Counter> ce : e.getValue().entrySet()) {
                writeCounter(yaml, vKey, ce.getKey(), ce.getValue());
            }
        }
        for (Map.Entry<UUID, Long> e : reputationSeen.entrySet()) {
            yaml.set("reputation-seen." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Map<VillageKey, Integer>> e : reputation.entrySet()) {
            String pKey = "reputation." + e.getKey();
            for (Map.Entry<VillageKey, Integer> ve : e.getValue().entrySet()) {
                VillageKey vk = ve.getKey();
                String suffix = vk.legacy ? vk.worldUid + "_" + vk.x + "_" + vk.z
                        : vk.worldUid + "_" + vk.x + "_" + vk.y + "_" + vk.z;
                yaml.set(pKey + "." + suffix, ve.getValue());
            }
        }
        for (Map.Entry<UUID, Map<UUID, Integer>> e : purchases.entrySet()) {
            String vKey = "purchases." + e.getKey();
            for (Map.Entry<UUID, Integer> pe : e.getValue().entrySet()) {
                if (pe.getValue() > 0) {
                    yaml.set(vKey + "." + pe.getKey(), pe.getValue());
                }
            }
        }
        for (Map.Entry<UUID, SeasonalEntry> e : seasonalStocks.entrySet()) {
            SeasonalEntry season = e.getValue();
            String path = "seasonal-stocks." + e.getKey();
            yaml.set(path + ".last-roll-day", season.lastRollDay);
            yaml.set(path + ".group", season.group);
            yaml.set(path + ".enchant", season.enchant == null ? null : season.enchant.asString());
            yaml.set(path + ".start-day", season.startDay);
            yaml.set(path + ".end-day", season.endDay);
        }
        String snapshot = yaml.saveToString();
        dataDirty.set(false);
        pendingSave = pendingSave.handle((ignored, error) -> null).thenRunAsync(
                () -> writeDataSnapshot(snapshot), dataWriter);
    }

    public void flushAndCloseData() {
        saveData();
        try {
            pendingSave.join();
            if (dataDirty.get()) {
                saveData();
                pendingSave.join();
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Не удалось дождаться сохранения data.yml: " + ex.getMessage());
        } finally {
            dataWriter.shutdown();
        }
    }

    private void writeCounter(org.bukkit.configuration.file.YamlConfiguration yaml, String parent,
                              NamespacedKey key, Counter counter) {
        if (counter.count <= 0) {
            return;
        }
        String path = parent + "." + key.asString().replace(':', '_');
        yaml.set(path + ".key", key.asString());
        yaml.set(path + ".count", counter.count);
        yaml.set(path + ".day", counter.lastPurchaseDay);
        yaml.set(path + ".decay-day", counter.lastDeflationDay);
    }

    private void writeDataSnapshot(String snapshot) {
        try {
            java.nio.file.Path dataFolder = plugin.getDataFolder().toPath();
            java.nio.file.Files.createDirectories(dataFolder);
            java.nio.file.Path file = dataFolder.resolve("data.yml");
            java.nio.file.Path tempFile = dataFolder.resolve("data.yml.tmp");
            java.nio.file.Files.writeString(tempFile, snapshot, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
            try {
                java.nio.file.Files.move(tempFile, file,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                java.nio.file.Files.move(tempFile, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить data.yml: " + ex.getMessage());
            dataDirty.set(true);
        }
    }

    private void loadData() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "data.yml");
        if (!file.isFile()) {
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);

        org.bukkit.configuration.ConfigurationSection marketSec = yaml.getConfigurationSection("market-counters");
        if (marketSec != null) {
            for (String region : marketSec.getKeys(false)) {
                String[] parts = region.split("_");
                if (parts.length != 3) {
                    continue;
                }
                UUID worldId = parseUuid(parts[0]);
                try {
                    if (worldId != null) {
                        Map<NamespacedKey, Counter> map = readCounters(marketSec.getConfigurationSection(region));
                        if (!map.isEmpty()) {
                            marketCounters.put(new MarketKey(worldId, Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2])), map);
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        org.bukkit.configuration.ConfigurationSection legacySec = yaml.getConfigurationSection("legacy-counters");
        boolean oldCounterFormat = false;
        if (legacySec == null) {
            legacySec = yaml.getConfigurationSection("counters");
            oldCounterFormat = legacySec != null;
        }
        if (legacySec != null) {
            for (String villagerId : legacySec.getKeys(false)) {
                UUID id = parseUuid(villagerId);
                Map<NamespacedKey, Counter> map = readCounters(legacySec.getConfigurationSection(villagerId));
                if (id != null && !map.isEmpty()) {
                    legacyCounters.put(id, map);
                }
            }
        }
        if (oldCounterFormat) {
            markDataDirty();
        }

        org.bukkit.configuration.ConfigurationSection seenSec = yaml.getConfigurationSection("reputation-seen");
        if (seenSec != null) {
            for (String pStr : seenSec.getKeys(false)) {
                UUID pId = parseUuid(pStr);
                if (pId != null) {
                    reputationSeen.put(pId, seenSec.getLong(pStr));
                }
            }
        }

        org.bukkit.configuration.ConfigurationSection repSec = yaml.getConfigurationSection("reputation");
        if (repSec != null) {
            for (String pStr : repSec.getKeys(false)) {
                UUID pId = parseUuid(pStr);
                org.bukkit.configuration.ConfigurationSection ps = repSec.getConfigurationSection(pStr);
                if (pId == null || ps == null) {
                    continue;
                }
                Map<VillageKey, Integer> map = new HashMap<>();
                for (String vkStr : ps.getKeys(false)) {
                    String[] parts = vkStr.split("_");
                    if (parts.length == 3 || parts.length == 4) {
                        UUID wId = parseUuid(parts[0]);
                        try {
                            int x = Integer.parseInt(parts[1]);
                            int val = ps.getInt(vkStr, 0);
                            if (wId != null && val > 0) {
                                VillageKey key = parts.length == 3
                                        ? VillageKey.legacy(wId, x, Integer.parseInt(parts[2]))
                                        : new VillageKey(wId, x, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                                map.put(key, val);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (!map.isEmpty()) {
                    reputation.put(pId, map);
                }
            }
        }

        org.bukkit.configuration.ConfigurationSection purSec = yaml.getConfigurationSection("purchases");
        if (purSec != null) {
            for (String vStr : purSec.getKeys(false)) {
                UUID vId = parseUuid(vStr);
                org.bukkit.configuration.ConfigurationSection vs = purSec.getConfigurationSection(vStr);
                if (vId == null || vs == null) {
                    continue;
                }
                Map<UUID, Integer> map = new HashMap<>();
                for (String pStr : vs.getKeys(false)) {
                    UUID pId = parseUuid(pStr);
                    int val = vs.getInt(pStr, 0);
                    if (pId != null && val > 0) {
                        map.put(pId, val);
                    }
                }
                if (!map.isEmpty()) {
                    purchases.put(vId, map);
                }
            }
        }
        org.bukkit.configuration.ConfigurationSection seasonSec = yaml.getConfigurationSection("seasonal-stocks");
        if (seasonSec != null) {
            for (String worldKey : seasonSec.getKeys(false)) {
                UUID worldId = parseUuid(worldKey);
                org.bukkit.configuration.ConfigurationSection section = seasonSec.getConfigurationSection(worldKey);
                if (worldId == null || section == null) {
                    continue;
                }
                SeasonalEntry entry = new SeasonalEntry();
                entry.lastRollDay = section.getLong("last-roll-day", -1L);
                entry.group = section.getString("group");
                String rawEnchant = section.getString("enchant");
                entry.enchant = rawEnchant == null ? null : NamespacedKey.fromString(rawEnchant);
                entry.startDay = section.getLong("start-day", -1L);
                entry.endDay = section.getLong("end-day", -1L);
                seasonalStocks.put(worldId, entry);
            }
        }
        rebuildReputationIndex();
        pruneOldPlayers();
    }

    private Map<NamespacedKey, Counter> readCounters(
            org.bukkit.configuration.ConfigurationSection section) {
        Map<NamespacedKey, Counter> map = new HashMap<>();
        if (section == null) {
            return map;
        }
        for (String key : section.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection item = section.getConfigurationSection(key);
            if (item == null) {
                continue;
            }
            String rawKey = item.getString("key");
            NamespacedKey enchant = rawKey == null ? null : NamespacedKey.fromString(rawKey);
            int count = item.getInt("count", 0);
            if (enchant == null || count <= 0) {
                continue;
            }
            Counter counter = new Counter();
            counter.count = count;
            counter.lastPurchaseDay = item.getLong("day", -1L);
            counter.lastDeflationDay = item.getLong("decay-day", -1L);
            map.put(enchant, counter);
        }
        return map;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class Counter {
        int count;
        long lastPurchaseDay = -1;
        long lastDeflationDay = -1;

        Counter() {
        }

        Counter(Counter other) {
            this.count = other.count;
            this.lastPurchaseDay = other.lastPurchaseDay;
            this.lastDeflationDay = other.lastDeflationDay;
        }
    }

    private static final class SeasonalEntry {
        long lastRollDay = -1L;
        String group;
        NamespacedKey enchant;
        long startDay = -1L;
        long endDay = -1L;
    }

    private static final class SeasonCandidate {
        final String group;
        final Enchantment enchant;

        SeasonCandidate(String group, Enchantment enchant) {
            this.group = group;
            this.enchant = enchant;
        }
    }

    private record MarketKey(UUID worldUid, int cellX, int cellZ) { }

    private record SpatialCell(UUID worldUid, int cellX, int cellY, int cellZ) {
        private static final int SIZE = 32;

        static SpatialCell forKey(VillageKey key) {
            return new SpatialCell(key.worldUid, Math.floorDiv(key.x, SIZE),
                    Math.floorDiv(key.y, SIZE), Math.floorDiv(key.z, SIZE));
        }
    }

    private static final class OfferEntry {
        final long day;
        final int recipeIndex;

        OfferEntry(long day, int recipeIndex) {
            this.day = day;
            this.recipeIndex = recipeIndex;
        }
    }

    private static final class SaleEntry {
        private final UUID worldUid;
        private final NamespacedKey enchant;

        SaleEntry(UUID worldUid, NamespacedKey enchant) {
            this.worldUid = worldUid;
            this.enchant = enchant;
        }
    }

    private static final class PriceSession {
        final UUID playerId;
        final List<Integer> basePrices;
        final List<Integer> adjustedPrices;
        final List<ItemStack> results;

        PriceSession(UUID playerId, List<Integer> basePrices, List<Integer> adjustedPrices,
                     List<ItemStack> results) {
            this.playerId = playerId;
            this.basePrices = basePrices;
            this.adjustedPrices = adjustedPrices;
            this.results = results;
        }
    }

    private static final class VillageKey {
        private final UUID worldUid;
        private final int x;
        private final int y;
        private final int z;
        private final boolean legacy;

        VillageKey(UUID worldUid, int x, int y, int z) {
            this(worldUid, x, y, z, false);
        }

        private VillageKey(UUID worldUid, int x, int y, int z, boolean legacy) {
            this.worldUid = worldUid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.legacy = legacy;
        }

        static VillageKey legacy(UUID worldUid, int x, int z) {
            return new VillageKey(worldUid, x, 0, z, true);
        }

        long distanceSquared(UUID world, Location location) {
            if (legacy || !worldUid.equals(world)) {
                return Long.MAX_VALUE;
            }
            long dx = (long) x - location.getBlockX();
            long dy = (long) y - location.getBlockY();
            long dz = (long) z - location.getBlockZ();
            return dx * dx + dy * dy + dz * dz;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof VillageKey other)) {
                return false;
            }
            return x == other.x && y == other.y && z == other.z
                    && legacy == other.legacy && worldUid.equals(other.worldUid);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(worldUid, x, y, z, legacy);
        }
    }
}
