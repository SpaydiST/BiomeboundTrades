package org.example1.biomeboundTrades.config;

import org.bukkit.enchantments.Enchantment;

import java.util.List;
import java.util.Set;

public final class BookGroup {

    public static final class SpecialBook {
        private final Enchantment enchant;
        private final int level;
        private final int price;

        public SpecialBook(Enchantment enchant, int level, int price) {
            this.enchant = enchant;
            this.level = level;
            this.price = price;
        }

        public Enchantment getEnchant() {
            return enchant;
        }

        public int getLevel() {
            return level;
        }

        public int getPrice() {
            return price;
        }
    }

    private final String name;
    private final Set<String> biomes;
    private final List<SpecialBook> special;
    private final Set<Enchantment> common;

    public BookGroup(String name, Set<String> biomes, List<SpecialBook> special, Set<Enchantment> common) {
        this.name = name;
        this.biomes = biomes;
        this.special = special;
        this.common = common;
    }

    public String getName() {
        return name;
    }

    public Set<String> getBiomes() {
        return biomes;
    }

    public List<SpecialBook> getSpecial() {
        return special;
    }

    public Set<Enchantment> getCommon() {
        return common;
    }

    public boolean matches(String biomeName, String keyName) {
        for (String b : biomes) {
            if (b.equalsIgnoreCase(biomeName) || b.equalsIgnoreCase(keyName)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return special.isEmpty() && common.isEmpty();
    }
}
