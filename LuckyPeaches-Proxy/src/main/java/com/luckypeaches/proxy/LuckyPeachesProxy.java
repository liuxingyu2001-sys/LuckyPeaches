package com.luckypeaches.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.luckypeaches.proxy.config.ProxyConfig;
import com.luckypeaches.proxy.database.ProxyDatabase;
import com.luckypeaches.proxy.command.ProxyCommand;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
    id = "luckypeaches-proxy",
    name = "LuckyPeaches-Proxy",
    version = "1.0",
    description = "LuckyPeaches proxy plugin for Velocity",
    authors = {"liuxingyu2001"}
)
public class LuckyPeachesProxy {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private ProxyConfig config;
    private ProxyDatabase database;

    @Inject
    public LuckyPeachesProxy(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("LuckyPeaches-Proxy 正在初始化...");

        // 加载配置
        this.config = new ProxyConfig(this);
        config.load();

        // 初始化数据库
        this.database = new ProxyDatabase(this);
        database.initialize();

        // 注册命令
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("lp-proxy").build(),
            new ProxyCommand(this)
        );

        // 同步配置到 MySQL
        if (database.isAvailable()) {
            database.syncConfigToDatabase();
        } else {
            logger.warn("数据库不可用，跳过配置同步");
        }

        logger.info("LuckyPeaches-Proxy 已启用！");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("LuckyPeaches-Proxy 正在关闭...");

        if (database != null) {
            database.close();
        }

        logger.info("LuckyPeaches-Proxy 已关闭！");
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public ProxyConfig getPluginConfig() {
        return config;
    }

    public ProxyDatabase getDatabase() {
        return database;
    }
}
