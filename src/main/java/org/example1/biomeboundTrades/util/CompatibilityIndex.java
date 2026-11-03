package org.example1.biomeboundTrades.util;

import org.bukkit.enchantments.Enchantment;
import org.example1.biomeboundTrades.config.BookGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Precomputed compatible secondary enchantments for ordinary and cursed
 * double-book offers.
 */
public final class CompatibilityIndex {

    private static final List<Enchantment> EMPTY = List.of();

    private final Map<String, Map<Enchantment, List<Enchantment>>> dualByGroup;
    private final Map<String, Map<Enchantment, List<Enchantment>>> cursedByGroup;
    private final Map<Enchantment, List<Enchantment>> globalDual;
    private final Map<Enchantment, List<Enchantment>> globalCursed;

    public CompatibilityIndex(Map<String, BookGroup> groups) {
        Map<String, Set<Enchantment>> commonsByGroup = new LinkedHashMap<>();
        Set<Enchantment> allCommons = new LinkedHashSet<>();
        Set<Enchantment> primaries = new LinkedHashSet<>();

        if (groups != null) {
            for (Map.Entry<String, BookGroup> entry : groups.entrySet()) {
                BookGroup group = entry.getValue();
                if (group == null) {
                    continue;
                }

                Set<Enchantment> groupCommons = new LinkedHashSet<>();
                Set<Enchantment> common = group.getCommon();
                if (common != null) {
                    for (Enchantment enchantment : common) {
                        if (enchantment != null) {
                            groupCommons.add(enchantment);
                            allCommons.add(enchantment);
                            primaries.add(enchantment);
                        }
                    }
                }

                List<BookGroup.SpecialBook> specialBooks = group.getSpecial();
                if (specialBooks != null) {
                    for (BookGroup.SpecialBook specialBook : specialBooks) {
                        if (specialBook != null && specialBook.getEnchant() != null) {
                            primaries.add(specialBook.getEnchant());
                        }
                    }
                }

                String groupName = normalize(entry.getKey());
                if (groupName != null) {
                    commonsByGroup.computeIfAbsent(groupName, ignored -> new LinkedHashSet<>())
                            .addAll(groupCommons);
                }
            }
        }

        Map<String, Map<Enchantment, List<Enchantment>>> dualGroups = new LinkedHashMap<>();
        Map<String, Map<Enchantment, List<Enchantment>>> cursedGroups = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Enchantment>> entry : commonsByGroup.entrySet()) {
            Map<Enchantment, List<Enchantment>> dualCandidates = new LinkedHashMap<>();
            Map<Enchantment, List<Enchantment>> cursedCandidates = new LinkedHashMap<>();
            for (Enchantment primary : primaries) {
                dualCandidates.put(primary, compatibleCandidates(primary, entry.getValue(), false));
                cursedCandidates.put(primary, compatibleCandidates(primary, entry.getValue(), true));
            }
            dualGroups.put(entry.getKey(), immutableIndex(dualCandidates));
            cursedGroups.put(entry.getKey(), immutableIndex(cursedCandidates));
        }

        Map<Enchantment, List<Enchantment>> allDualCandidates = new LinkedHashMap<>();
        Map<Enchantment, List<Enchantment>> allCursedCandidates = new LinkedHashMap<>();
        for (Enchantment primary : primaries) {
            allDualCandidates.put(primary, compatibleCandidates(primary, allCommons, false));
            allCursedCandidates.put(primary, compatibleCandidates(primary, allCommons, true));
        }

        this.dualByGroup = immutableGroupIndex(dualGroups);
        this.cursedByGroup = immutableGroupIndex(cursedGroups);
        this.globalDual = immutableIndex(allDualCandidates);
        this.globalCursed = immutableIndex(allCursedCandidates);
    }

    /**
     * Returns compatible ordinary candidates from the requested group, falling
     * back to the global candidates when that group has none.
     */
    public List<Enchantment> dualCandidates(String groupName, Enchantment primary) {
        return candidates(dualByGroup, globalDual, groupName, primary);
    }

    /**
     * Returns compatible cursed candidates from the requested group, falling
     * back to the global candidates when that group has none.
     */
    public List<Enchantment> cursedCandidates(String groupName, Enchantment primary) {
        return candidates(cursedByGroup, globalCursed, groupName, primary);
    }

    private static List<Enchantment> candidates(
            Map<String, Map<Enchantment, List<Enchantment>>> byGroup,
            Map<Enchantment, List<Enchantment>> global,
            String groupName,
            Enchantment primary) {
        if (primary == null) {
            return EMPTY;
        }

        String normalizedGroup = normalize(groupName);
        if (normalizedGroup != null) {
            Map<Enchantment, List<Enchantment>> groupCandidates = byGroup.get(normalizedGroup);
            if (groupCandidates != null) {
                List<Enchantment> candidates = groupCandidates.get(primary);
                if (candidates != null && !candidates.isEmpty()) {
                    return candidates;
                }
            }
        }
        return global.getOrDefault(primary, EMPTY);
    }

    private static List<Enchantment> compatibleCandidates(
            Enchantment primary,
            Iterable<Enchantment> pool,
            boolean cursed) {
        List<Enchantment> candidates = new ArrayList<>();
        for (Enchantment candidate : pool) {
            if (candidate == null || candidate.isCursed() != cursed) {
                continue;
            }

            boolean compatible = cursed
                    ? EnchantUtil.areCompatibleForCursedDualBook(primary, candidate)
                    : EnchantUtil.areCompatibleForDualBook(primary, candidate);
            if (compatible) {
                candidates.add(candidate);
            }
        }
        return candidates.isEmpty() ? EMPTY : List.copyOf(candidates);
    }

    private static Map<Enchantment, List<Enchantment>> immutableIndex(
            Map<Enchantment, List<Enchantment>> index) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    private static Map<String, Map<Enchantment, List<Enchantment>>> immutableGroupIndex(
            Map<String, Map<Enchantment, List<Enchantment>>> index) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    private static String normalize(String groupName) {
        return groupName == null ? null : groupName.toLowerCase(Locale.ROOT);
    }
}
