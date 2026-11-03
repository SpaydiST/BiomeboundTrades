package org.example1.biomeboundTrades.listener;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.example1.biomeboundTrades.BiomeboundTrades;
import org.example1.biomeboundTrades.config.PluginConfig;
import org.example1.biomeboundTrades.util.ProgressionAdvancement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class WanderingTraderListener implements Listener {

    private final BiomeboundTrades plugin;
    private final Random random = new Random();
    private final NamespacedKey customTradesKey;

    public WanderingTraderListener(BiomeboundTrades plugin) {
        this.plugin = plugin;
        this.customTradesKey = new NamespacedKey(plugin, "biomebound_trades");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomTrade(PlayerTradeEvent event) {
        if (!(event.getVillager() instanceof WanderingTrader trader)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (trader.getPersistentDataContainer().has(customTradesKey, PersistentDataType.BYTE)) {
            ProgressionAdvancement.award(player, "biomebound_trades_wandering");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        PluginConfig.WanderingTrader wt = plugin.getConfigData().wanderingTrader();
        if (!wt.enabled) {
            return;
        }
        if (isManualSpawn(event.getSpawnReason())) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (trader.isValid()) {
                applyTrades(trader, wt);
            }
        }, 2L);
    }

    private boolean isManualSpawn(CreatureSpawnEvent.SpawnReason reason) {
        return reason == CreatureSpawnEvent.SpawnReason.COMMAND
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || reason == CreatureSpawnEvent.SpawnReason.EGG
                || reason == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG;
    }

    private void applyTrades(WanderingTrader trader, PluginConfig.WanderingTrader wt) {
        PluginConfig.WTGroup own = resolveGroup(trader, wt);
        Map<PluginConfig.WTGroup, List<PluginConfig.WTTrade>> pools = new LinkedHashMap<>();
        for (PluginConfig.WTGroup group : wt.groups.values()) {
            if (group.getTrades().isEmpty()) {
                continue;
            }
            List<PluginConfig.WTTrade> copy = new ArrayList<>(group.getTrades());
            Collections.shuffle(copy, random);
            pools.put(group, copy);
        }
        if (pools.isEmpty()) {
            return;
        }

        int count = Math.min(wt.tradeCount, totalTrades(pools));
        List<MerchantRecipe> recipes = new ArrayList<>();
        java.util.Set<Material> addedTypes = new java.util.HashSet<>();
        while (recipes.size() < count) {
            PluginConfig.WTGroup chosen = pickGroup(own, pools, wt.foreignTradeChancePercent);
            if (chosen == null) {
                break;
            }
            List<PluginConfig.WTTrade> pool = pools.get(chosen);
            PluginConfig.WTTrade trade = pool.remove(pool.size() - 1);
            if (random.nextInt(100) >= trade.getChancePercent()) {
                continue;
            }
            MerchantRecipe recipe = buildTrade(trade, wt);
            if (recipe != null) {
                recipes.add(recipe);
                addedTypes.add(recipe.getResult().getType());
            }
        }
        if (!recipes.isEmpty()) {
            for (MerchantRecipe vanilla : trader.getRecipes()) {
                if (!addedTypes.contains(vanilla.getResult().getType())) {
                    recipes.add(vanilla);
                }
            }
            trader.setRecipes(recipes);
            trader.getPersistentDataContainer().set(customTradesKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    private PluginConfig.WTGroup pickGroup(PluginConfig.WTGroup own,
                                           Map<PluginConfig.WTGroup, List<PluginConfig.WTTrade>> pools,
                                           int foreignChance) {
        List<PluginConfig.WTGroup> available = new ArrayList<>();
        for (Map.Entry<PluginConfig.WTGroup, List<PluginConfig.WTTrade>> e : pools.entrySet()) {
            if (!e.getValue().isEmpty()) {
                available.add(e.getKey());
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        boolean ownAvailable = own != null && pools.containsKey(own) && !pools.get(own).isEmpty();
        if (ownAvailable && random.nextInt(100) >= foreignChance) {
            return own;
        }
        if (!ownAvailable) {
            return available.get(random.nextInt(available.size()));
        }
        List<PluginConfig.WTGroup> others = new ArrayList<>(available);
        others.remove(own);
        if (others.isEmpty()) {
            return own;
        }
        return others.get(random.nextInt(others.size()));
    }

    private int totalTrades(Map<PluginConfig.WTGroup, List<PluginConfig.WTTrade>> pools) {
        int total = 0;
        for (List<PluginConfig.WTTrade> list : pools.values()) {
            total += list.size();
        }
        return total;
    }

    private MerchantRecipe buildTrade(PluginConfig.WTTrade trade, PluginConfig.WanderingTrader wt) {
        Material material = Material.matchMaterial(trade.getItem());
        if (material == null || material == Material.AIR) {
            return null;
        }
        int maxStack = Math.max(1, material.getMaxStackSize());
        ItemStack result = new ItemStack(material, Math.min(maxStack, Math.max(1, trade.getAmount())));
        MerchantRecipe recipe = new MerchantRecipe(result, 0, Math.max(1, trade.getMaxUses()), true, 0, 0.05f);
        int price = Math.max(1, (int) Math.round(trade.getPrice() * wt.priceMultiplier / 100.0));
        if (price <= 64) {
            recipe.addIngredient(new ItemStack(Material.EMERALD, price));
        } else if (price <= 128) {
            recipe.addIngredient(new ItemStack(Material.EMERALD, 64));
            recipe.addIngredient(new ItemStack(Material.EMERALD, price - 64));
        } else {
            int blocks = Math.min(64, price / 9);
            int rem = Math.min(64, Math.max(0, price - blocks * 9));
            recipe.addIngredient(new ItemStack(Material.EMERALD_BLOCK, Math.max(1, blocks)));
            if (rem > 0) {
                recipe.addIngredient(new ItemStack(Material.EMERALD, rem));
            }
        }
        return recipe;
    }

    private PluginConfig.WTGroup resolveGroup(WanderingTrader trader, PluginConfig.WanderingTrader wt) {
        org.bukkit.NamespacedKey biomeKey = trader.getLocation().getBlock().getBiome().getKey();
        String key = biomeKey.getKey();
        String full = biomeKey.asString();
        for (PluginConfig.WTGroup group : wt.groups.values()) {
            for (String b : group.getBiomes()) {
                if (b.equalsIgnoreCase(key) || b.equalsIgnoreCase(full)) {
                    return group;
                }
            }
        }
        return wt.groups.get("default");
    }
}
