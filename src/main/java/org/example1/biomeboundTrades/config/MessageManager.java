package org.example1.biomeboundTrades.config;

import com.destroystokyo.paper.ClientOption;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.example1.biomeboundTrades.BiomeboundTrades;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MessageManager {

    public static final String DEFAULT_LOCALE = "en_us";

    private static final List<String> BUNDLED = List.of(
            "en_us", "ru_ru", "de_de", "fr_fr", "es_es", "pt_br", "it_it",
            "pl_pl", "uk_ua", "tr_tr", "ar_sa", "cs_cz", "hu_hu", "ro_ro", "sv_se");

    private final BiomeboundTrades plugin;
    private final Map<String, Map<String, String>> messages = new HashMap<>();
    private final Map<String, String> defaults = new HashMap<>();

    public MessageManager(BiomeboundTrades plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        messages.clear();
        defaults.clear();
        for (String locale : BUNDLED) {
            Map<String, String> map = loadBundled(locale);
            messages.put(locale, map);
            if (locale.equals(DEFAULT_LOCALE)) {
                defaults.putAll(map);
            }
        }
        File dir = new File(plugin.getDataFolder(), "lang");
        if (dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
            if (files != null) {
                for (File file : files) {
                    String locale = file.getName().substring(0, file.getName().length() - 4)
                            .toLowerCase(Locale.ROOT);
                    Map<String, String> map = new HashMap<>();
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                    for (String key : yaml.getKeys(false)) {
                        map.put(key, yaml.getString(key));
                    }
                    if (!map.isEmpty()) {
                        messages.computeIfAbsent(locale, k -> new HashMap<>()).putAll(map);
                    }
                }
            }
        }
    }

    public String get(CommandSender sender, String key) {
        if (sender instanceof Player player) {
            return get(locale(player), key);
        }
        return get((String) null, key);
    }

    public String get(Player player, String key) {
        return get(locale(player), key);
    }

    private String locale(Player player) {
        String locale = player.getClientOption(ClientOption.LOCALE);
        return locale == null ? DEFAULT_LOCALE : locale;
    }

    public String get(String locale, String key) {
        String supported = resolve(locale);
        if (supported != null) {
            String text = messages.get(supported).get(key);
            if (text != null) {
                return text;
            }
        }
        return defaults.getOrDefault(key, key);
    }

    private String resolve(String locale) {
        if (locale == null || locale.isEmpty()) {
            return DEFAULT_LOCALE;
        }
        String normalized = locale.toLowerCase(Locale.ROOT).replace('-', '_');
        if (messages.containsKey(normalized)) {
            return normalized;
        }
        String base = normalized.split("_")[0];
        switch (base) {
            case "en": return "en_us";
            case "ru": return "ru_ru";
            case "de": return "de_de";
            case "fr": return "fr_fr";
            case "es": return "es_es";
            case "pt": return "pt_br";
            case "it": return "it_it";
            case "pl": return "pl_pl";
            case "uk": return "uk_ua";
            case "tr": return "tr_tr";
            case "ar": return "ar_sa";
            case "cs": return "cs_cz";
            case "hu": return "hu_hu";
            case "ro": return "ro_ro";
            case "sv": return "sv_se";
            default: return DEFAULT_LOCALE;
        }
    }

    private Map<String, String> loadBundled(String locale) {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = plugin.getResource("lang/" + locale + ".yml")) {
            if (in == null) {
                return map;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String key : yaml.getKeys(false)) {
                String value = yaml.getString(key);
                if (value != null) {
                    map.put(key, value);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось прочитать встроенный язык '" + locale + "': " + e.getMessage());
        }
        return map;
    }
}
