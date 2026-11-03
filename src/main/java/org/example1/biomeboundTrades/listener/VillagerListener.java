package org.example1.biomeboundTrades.listener;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.block.Biome;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.inventory.MerchantRecipe;
import org.example1.biomeboundTrades.BiomeboundTrades;
import org.example1.biomeboundTrades.config.BookGroup;
import org.example1.biomeboundTrades.config.PluginConfig;
import org.example1.biomeboundTrades.util.EnchantUtil;

public final class VillagerListener implements Listener {

    private final BiomeboundTrades plugin;

    public VillagerListener(BiomeboundTrades plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerBreed(EntityBreedEvent event) {
        if (!(event.getEntity() instanceof Villager baby)) {
            return;
        }

        Villager.Type birthType = villagerTypeForBiome(baby.getLocation().getBlock().getBiome());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (baby.isValid() && !baby.isDead()) {
                baby.setVillagerType(birthType);
            }
        });
    }

    private Villager.Type villagerTypeForBiome(Biome biome) {
        return switch (biome.getKey().getKey()) {
            case "desert", "badlands", "eroded_badlands", "wooded_badlands" -> Villager.Type.DESERT;
            case "jungle", "sparse_jungle", "bamboo_jungle" -> Villager.Type.JUNGLE;
            case "savanna", "savanna_plateau", "windswept_savanna" -> Villager.Type.SAVANNA;
            case "snowy_plains", "snowy_taiga", "snowy_beach", "ice_spikes",
                    "frozen_river", "frozen_ocean", "deep_frozen_ocean", "grove",
                    "snowy_slopes", "jagged_peaks", "frozen_peaks" -> Villager.Type.SNOW;
            case "swamp", "mangrove_swamp" -> Villager.Type.SWAMP;
            case "taiga", "old_growth_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga",
                    "windswept_forest", "windswept_hills", "windswept_gravelly_hills" -> Villager.Type.TAIGA;
            default -> Villager.Type.PLAINS;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReplenishTrade(VillagerReplenishTradeEvent event) {
        PluginConfig cfg = plugin.getConfigData();
        if (!cfg.biomeBooks().enabled) {
            return;
        }
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        MerchantRecipe recipe = event.getRecipe();
        if (plugin.getTradeManager().isSpecialBookRecipe(villager, recipe)) {
            if (!plugin.getTradeManager().tryReplenishSpecialBook(villager, recipe)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        PluginConfig cfg = plugin.getConfigData();
        if (!cfg.biomeBooks().enabled) {
            return;
        }
        if (!(event.getEntity() instanceof Villager villager)
                || villager.getProfession() != Villager.Profession.LIBRARIAN) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getTradeManager().handleTradeAcquired(villager);
        }, 1L);

        MerchantRecipe recipe = event.getRecipe();
        if (recipe == null) {
            return;
        }
        java.util.Map<Enchantment, Integer> storedEnchants = EnchantUtil.getStoredEnchants(recipe.getResult());
        if (storedEnchants.keySet().stream().anyMatch(Enchantment::isCursed)) {
            event.setRecipe(plugin.getTradeManager().buildFallbackNonBookRecipe(villager));
            return;
        }
        Enchantment stored = EnchantUtil.getStoredEnchant(recipe.getResult());
        if (stored == null) {
            return;
        }
        BookGroup group = plugin.getTradeManager().resolveGroup(villager);
        if (group == null || group.isEmpty()) {
            return;
        }
        int existingBooks = plugin.getTradeManager().countBooks(villager);
        int targetBooks = plugin.getTradeManager().getTargetBookCount(villager, group);
        if (existingBooks >= targetBooks) {
            event.setRecipe(plugin.getTradeManager().buildFallbackNonBookRecipe(villager));
            return;
        }

        boolean master = villager.getVillagerLevel() >= 5;
        boolean needsSpecial = master && !plugin.getTradeManager().hasSpecialBook(villager, group)
                && (cfg.biomeBooks().specialBookChancePercent >= 100 || group.getCommon().isEmpty());
        boolean unexpectedSpecial = master && !needsSpecial
                && plugin.getTradeManager().isSpecialBookRecipe(villager, recipe);
        if (!needsSpecial
                && !unexpectedSpecial
                && plugin.getTradeManager().isBookAllowed(villager, stored)
                && !plugin.getTradeManager().hasEnchant(villager, stored)) {
            return;
        }
        MerchantRecipe replacement = plugin.getTradeManager().buildBookRecipe(
                group, master && needsSpecial, needsSpecial,
                plugin.getTradeManager().collectExistingEnchants(villager));
        event.setRecipe(replacement != null ? replacement
                : plugin.getTradeManager().buildFallbackNonBookRecipe(villager));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        EntityTransformEvent.TransformReason reason = event.getTransformReason();
        if (reason != EntityTransformEvent.TransformReason.INFECTION
                && reason != EntityTransformEvent.TransformReason.CURED) {
            return;
        }
        Entity from = event.getEntity();
        Entity to = event.getTransformedEntity();
        if (from instanceof Villager infected && to instanceof ZombieVillager zombified) {
            plugin.getTradeManager().copyPersistentData(infected, zombified);
            plugin.getInflationListener().migrateVillager(infected.getUniqueId(), zombified.getUniqueId());
        } else if (from instanceof ZombieVillager zombie && to instanceof Villager cured) {
            plugin.getTradeManager().copyPersistentData(zombie, cured);
            plugin.getInflationListener().migrateVillager(zombie.getUniqueId(), cured.getUniqueId());
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (cured.isValid() && cured.getProfession() == Villager.Profession.LIBRARIAN) {
                    plugin.getTradeManager().applyBiomeBooks(cured, false);
                    plugin.trackVillager(cured);
                }
            }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        PluginConfig cfg = plugin.getConfigData();
        if (!cfg.biomeBooks().enabled && !cfg.professionRebalance().enabled) {
            return;
        }
        Villager villager = event.getEntity();
        if (villager == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!villager.isValid()) {
                return;
            }
            if (villager.getProfession() == Villager.Profession.LIBRARIAN) {
                if (cfg.biomeBooks().enabled) {
                    plugin.getTradeManager().applyBiomeBooks(villager);
                }
            } else {
                plugin.getTradeManager().clearRebalance(villager);
                plugin.getTradeManager().applyProfessionRebalance(villager);
            }
            plugin.trackVillager(villager);
        }, 1L);
    }
}
