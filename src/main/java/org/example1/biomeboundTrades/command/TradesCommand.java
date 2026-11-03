package org.example1.biomeboundTrades.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.example1.biomeboundTrades.BiomeboundTrades;
import org.example1.biomeboundTrades.util.TextUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class TradesCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "reroll", "info");
    private static final String ADMIN_PERMISSION = "biomeboundtrades.admin";

    private final BiomeboundTrades plugin;

    public TradesCommand(BiomeboundTrades plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(TextUtil.color(plugin.getMessages().get(sender, "unknown-command")));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }
                plugin.reloadConfigData();
                sender.sendMessage(TextUtil.color(plugin.getMessages().get(sender, "reloaded")));
            }
            case "reroll" -> {
                if (!admin(sender)) {
                    return true;
                }
                plugin.getTradeManager().rerollAll();
                sender.sendMessage(TextUtil.color(plugin.getMessages().get(sender, "rerolled")));
            }
            case "info" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(TextUtil.color(plugin.getMessages().get(sender, "players-only")));
                    return true;
                }
                plugin.getTradeManager().infoFor(player);
            }
            default -> sender.sendMessage(TextUtil.color(plugin.getMessages().get(sender, "unknown-command")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            List<String> allowed = sender.hasPermission(ADMIN_PERMISSION)
                    ? SUBCOMMANDS
                    : List.of("info");
            return allowed.stream().filter(s -> s.startsWith(typed)).toList();
        }
        return Collections.emptyList();
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        sender.sendMessage(TextUtil.color(plugin.getMessages().get(sender, "no-permission")));
        return false;
    }
}
