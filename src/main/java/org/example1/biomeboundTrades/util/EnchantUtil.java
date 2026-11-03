package org.example1.biomeboundTrades.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.set.RegistryKeySet;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EnchantUtil {

    private EnchantUtil() {
    }

    public static Enchantment parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String withNamespace = s.contains(":") ? s : "minecraft:" + s;
        NamespacedKey key = NamespacedKey.fromString(withNamespace.toLowerCase(Locale.ROOT));
        if (key == null) {
            return null;
        }
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
    }

    public static Enchantment getStoredEnchant(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) {
            return null;
        }
        if (!(item.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
            return null;
        }
        Map<Enchantment, Integer> stored = meta.getStoredEnchants();
        if (stored.isEmpty()) {
            return null;
        }
        return stored.keySet().iterator().next();
    }

    public static Map<Enchantment, Integer> getStoredEnchants(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) {
            return java.util.Collections.emptyMap();
        }
        if (!(item.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
            return java.util.Collections.emptyMap();
        }
        return meta.getStoredEnchants();
    }

    public static boolean isStoredBook(ItemStack item) {
        return !getStoredEnchants(item).isEmpty();
    }

    public static ItemStack makeBook(Enchantment enchant, int level, Enchantment second, int secondLevel) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(enchant, level, true);
        if (second != null) {
            meta.addStoredEnchant(second, secondLevel, true);
        }
        book.setItemMeta(meta);
        return book;
    }

    public static boolean isToolExcluded(Enchantment enchant, Set<String> excludedItemKeys) {
        if (enchant == null || excludedItemKeys.isEmpty()) {
            return false;
        }

        RegistryKeySet<?> applicableItems = enchant.getPrimaryItems();
        if (applicableItems == null || applicableItems.isEmpty()) {
            applicableItems = enchant.getSupportedItems();
        }
        if (applicableItems == null || applicableItems.isEmpty()) {
            return false;
        }

        // Exclude only enchantments whose applicable items are all excluded.
        // A shared enchantment such as Mending or Unbreaking should remain
        // available when it also applies to non-excluded equipment.
        for (TypedKey<?> typedKey : applicableItems.values()) {
            if (!excludedItemKeys.contains(typedKey.key().value())) {
                return false;
            }
        }
        return true;
    }

    public static boolean areCompatibleForDualBook(Enchantment first, Enchantment second) {
        if (first == null || second == null || first.getKey().equals(second.getKey())) {
            return false;
        }
        if (first.isCursed() || second.isCursed()) {
            return false;
        }
        if (first.conflictsWith(second) || second.conflictsWith(first)) {
            return false;
        }
        if (isMiningOnly(first) != isMiningOnly(second) && (isWeaponOnly(first) || isWeaponOnly(second))) {
            return false;
        }
        RegistryKeySet<?> p1 = first.getPrimaryItems();
        RegistryKeySet<?> p2 = second.getPrimaryItems();
        if (p1 != null && p2 != null && !p1.isEmpty() && !p2.isEmpty()) {
            return hasOverlap(p1, p2);
        }
        RegistryKeySet<?> s1 = first.getSupportedItems();
        RegistryKeySet<?> s2 = second.getSupportedItems();
        if (s1 != null && s2 != null && !s1.isEmpty() && !s2.isEmpty()) {
            return hasOverlap(s1, s2);
        }
        return true;
    }

    public static boolean areCompatibleForCursedDualBook(Enchantment enchant, Enchantment curse) {
        if (enchant == null || curse == null || !curse.isCursed() || enchant.isCursed()
                || enchant.getKey().equals(curse.getKey())
                || enchant.conflictsWith(curse) || curse.conflictsWith(enchant)) {
            return false;
        }
        RegistryKeySet<?> first = enchant.getPrimaryItems();
        if (first == null || first.isEmpty()) {
            first = enchant.getSupportedItems();
        }
        RegistryKeySet<?> second = curse.getPrimaryItems();
        if (second == null || second.isEmpty()) {
            second = curse.getSupportedItems();
        }
        return first == null || first.isEmpty() || second == null || second.isEmpty()
                || hasOverlap(first, second);
    }

    private static boolean hasOverlap(RegistryKeySet<?> a, RegistryKeySet<?> b) {
        Set<String> keysA = new java.util.HashSet<>();
        for (TypedKey<?> tk : a.values()) {
            keysA.add(tk.key().asString());
        }
        for (TypedKey<?> tk : b.values()) {
            if (keysA.contains(tk.key().asString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMiningOnly(Enchantment e) {
        String k = e.getKey().getKey();
        return k.equals("efficiency") || k.equals("silk_touch") || k.equals("fortune");
    }

    private static boolean isWeaponOnly(Enchantment e) {
        String k = e.getKey().getKey();
        return k.equals("sharpness") || k.equals("smite") || k.equals("bane_of_arthropods")
                || k.equals("knockback") || k.equals("fire_aspect") || k.equals("looting")
                || k.equals("sweeping_edge");
    }

    public static boolean isTreasure(Enchantment e) {
        if (e == null) {
            return false;
        }
        String k = e.getKey().getKey();
        return k.equals("mending") || k.equals("frost_walker") || k.equals("soul_speed")
                || k.equals("swift_sneak") || k.equals("wind_burst")
                || k.equals("binding_curse") || k.equals("vanishing_curse");
    }
}
