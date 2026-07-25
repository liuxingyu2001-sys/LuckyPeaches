package com.luckypeaches.proxy.command;

import com.luckypeaches.proxy.LuckyPeachesProxy;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.*;

public class ProxyCommand implements SimpleCommand {
    private final LuckyPeachesProxy plugin;

    public ProxyCommand(LuckyPeachesProxy plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        CommandSource source = invocation.source();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                handleReload(source);
                break;
            default:
                sendHelp(source);
                break;
        }
    }

    private void handleReload(CommandSource source) {
        boolean synced = plugin.getPluginConfig().reload();
        if (synced) {
            source.sendMessage(net.kyori.adventure.text.Component.text("§a配置已重新加载并同步到 MySQL"));
        } else {
            source.sendMessage(net.kyori.adventure.text.Component.text("§e配置已重新加载，§c但同步到 MySQL 失败"));
        }
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(net.kyori.adventure.text.Component.text("§6§lLuckyPeaches-Proxy 命令§r"));
        source.sendMessage(net.kyori.adventure.text.Component.text("§e/lp-proxy reload§r - 重新加载配置"));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 0) {
            return Arrays.asList("reload");
        }

        if (args.length == 1) {
            return Arrays.asList("reload").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
        }

        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("luckypeaches.proxy.admin");
    }
}
