package com.luckypeaches.license;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class LicenseManager {
    
    private final JavaPlugin plugin;
    private final File licenseFile;
    private YamlConfiguration licenseConfig;
    private String licenseKey;
    
    private static final String API_URL = "https://mcsk.mc99.top/api/verify";
    
    public LicenseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.licenseFile = new File(plugin.getDataFolder(), "license.yml");
        setupLicenseFile();
    }
    
    private void setupLicenseFile() {
        if (!licenseFile.exists()) {
            licenseFile.getParentFile().mkdirs();
            try {
                licenseFile.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("无法创建 license.yml 文件");
            }
        }
        licenseConfig = YamlConfiguration.loadConfiguration(licenseFile);
        if (!licenseConfig.contains("key")) {
            licenseConfig.set("key", "");
            saveLicenseConfig();
        }
    }
    
    private void saveLicenseConfig() {
        try {
            licenseConfig.save(licenseFile);
        } catch (Exception e) {
            plugin.getLogger().severe("无法保存 license.yml 文件");
        }
    }
    
    public void loadConfig() {
        licenseConfig = YamlConfiguration.loadConfiguration(licenseFile);
        this.licenseKey = licenseConfig.getString("key", "");
    }
    
    public void reloadConfig() {
        loadConfig();
    }
    
    public String getLicenseKey() {
        return licenseKey;
    }
    
    private String getServerIp() {
        String[] ipServices = {
            "https://api.ipify.org",
            "https://icanhazip.com",
            "https://ifconfig.me/ip",
            "https://ipecho.net/plain"
        };
        
        for (String service : ipServices) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(service).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                String ip = new Scanner(conn.getInputStream()).useDelimiter("\\A").next().trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            } catch (Exception ignored) {}
        }
        return "unknown";
    }
    
    private String getMachineId() {
        try {
            File serverProperties = new File("server.properties");
            if (serverProperties.exists()) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(serverProperties)) {
                    props.load(fis);
                    String levelName = props.getProperty("level-name", "world");
                    String serverPort = props.getProperty("server-port", "25565");
                    String motd = props.getProperty("motd", "");
                    
                    String combined = levelName + ":" + serverPort + ":" + motd;
                    return Integer.toHexString(combined.hashCode());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("获取机器码失败: " + e.getMessage());
        }
        return "unknown";
    }
    
    public CompletableFuture<LicenseResult> verifyLicense() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (licenseKey == null || licenseKey.isEmpty()) {
                    return new LicenseResult(false, "NO_LICENSE_KEY", "未配置授权密钥，请在 license.yml 中填写");
                }
                
                String serverIp = getServerIp();
                String machineId = getMachineId();
                String serverName = plugin.getServer().getName();
                
                String jsonInputString = String.format(
                    "{\"license_key\":\"%s\",\"server_ip\":\"%s\",\"server_name\":\"%s\",\"machine_id\":\"%s\"}",
                    licenseKey, serverIp, serverName, machineId
                );
                
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
                
                int responseCode = conn.getResponseCode();
                java.io.InputStream inputStream = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();
                String responseBody = new Scanner(inputStream).useDelimiter("\\A").next();
                
                boolean success = responseBody.contains("\"success\":true");
                
                if (success) {
                    return new LicenseResult(true, null, null);
                } else {
                    String error = "UNKNOWN_ERROR";
                    if (responseBody.contains("\"error\":\"")) {
                        error = responseBody.split("\"error\":\"")[1].split("\"")[0];
                    }
                    return new LicenseResult(false, error, getErrorMessage(error));
                }
            } catch (Exception e) {
                return new LicenseResult(false, "NETWORK_ERROR", "网络错误: " + e.getMessage());
            }
        });
    }
    
    private String getErrorMessage(String errorCode) {
        switch (errorCode) {
            case "MISSING_LICENSE_KEY":
                return "缺少授权密钥";
            case "MISSING_SERVER_IP":
                return "缺少服务器IP";
            case "MISSING_MACHINE_ID":
                return "缺少机器码";
            case "LICENSE_NOT_FOUND":
                return "密钥不存在";
            case "LICENSE_DISABLED":
                return "密钥已禁用";
            case "LICENSE_EXPIRED":
                return "密钥已过期";
            case "LICENSE_ALREADY_BOUND":
                return "密钥已绑定其他服务器，请联系管理员解绑";
            default:
                return "验证失败: " + errorCode;
        }
    }
    
    public static class LicenseResult {
        private final boolean success;
        private final String errorCode;
        private final String message;
        
        public LicenseResult(boolean success, String errorCode, String message) {
            this.success = success;
            this.errorCode = errorCode;
            this.message = message;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
        
        public String getMessage() {
            return message;
        }
    }
}
