/*
 * Decompiled with CFR 0.152.
 */
package api;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

public class ApiClient {
    private static final String CONFIG_FILE = "config.properties";
    private static String serverUrl = "https://bkr-fastfood-backend-6snv.onrender.com/api";
    private static String serverHost = "bkr-fastfood-backend-6snv.onrender.com";
    private static int serverPort = 443;
    private static volatile boolean offlineMode;
    private static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6L)).build();

    public static synchronized void loadConfig() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (FileInputStream fileInputStream = new FileInputStream(file);){
                Properties properties = new Properties();
                properties.load(fileInputStream);
                offlineMode = Boolean.parseBoolean(properties.getProperty("offline.mode", "false"));
                String string = properties.getProperty("server.url");
                if (string != null && !string.isBlank()) {
                    ApiClient.setServerUrl(string);
                } else {
                    serverHost = properties.getProperty("server.host", "localhost").trim();
                    serverPort = Integer.parseInt(properties.getProperty("server.port", "8080").trim());
                    ApiClient.updateUrlFromHostAndPort();
                }
            }
            catch (Exception exception) {
                System.err.println("Could not load config.properties: " + exception.getMessage());
                ApiClient.updateUrlFromHostAndPort();
            }
        } else {
            offlineMode = false;
            ApiClient.updateUrlFromHostAndPort();
        }
    }

    public static synchronized void saveConfig(String string, int n) {
        String string2 = string != null ? string.trim() : "localhost";
        Properties properties = new Properties();
        properties.setProperty("offline.mode", Boolean.toString(offlineMode));
        if (string2.startsWith("http://") || string2.startsWith("https://") || string2.contains("/")) {
            ApiClient.setServerUrl(string2);
            properties.setProperty("server.url", serverUrl);
            properties.setProperty("server.host", serverHost);
            properties.setProperty("server.port", String.valueOf(serverPort));
        } else {
            serverHost = string2;
            serverPort = n > 0 ? n : 8080;
            ApiClient.updateUrlFromHostAndPort();
            properties.setProperty("server.url", serverUrl);
            properties.setProperty("server.host", serverHost);
            properties.setProperty("server.port", String.valueOf(serverPort));
        }
        try (FileOutputStream fileOutputStream = new FileOutputStream(CONFIG_FILE);){
            properties.store(fileOutputStream, "BKR Fast Food Client Configuration");
        }
        catch (Exception exception) {
            System.err.println("Could not save config.properties: " + exception.getMessage());
        }
    }

    private static void setServerUrl(String string) {
        String object = string.trim();
        if (!object.startsWith("http://") && !object.startsWith("https://")) {
            object = "http://" + object;
        }
        if (object.endsWith("/")) {
            object = object.substring(0, object.length() - 1);
        }
        if (!object.endsWith("/api")) {
            object = object + "/api";
        }
        serverUrl = object;
        try {
            URI uRI = URI.create(serverUrl);
            String string2 = serverHost = uRI.getHost() != null ? uRI.getHost() : "localhost";
            serverPort = uRI.getPort() != -1 ? uRI.getPort() : (uRI.getScheme().equalsIgnoreCase("https") ? 443 : 80);
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private static void updateUrlFromHostAndPort() {
        String string = serverHost.trim();
        if (string.startsWith("http://") || string.startsWith("https://")) {
            ApiClient.setServerUrl(string);
        } else {
            serverUrl = serverPort == 80 || serverPort == 443 || serverPort <= 0 ? "http://" + string + "/api" : "http://" + string + ":" + serverPort + "/api";
        }
    }

    public static String getBaseUrl() {
        return serverUrl;
    }

    public static String getServerHost() {
        return serverHost;
    }

    public static int getServerPort() {
        return serverPort;
    }

    public static String get(String string) throws Exception {
        String path = normalizePath(string);
        if (offlineMode) return OfflineStore.handle("GET", path, "");
        return send("GET", path, "");
    }

    public static String post(String string, String string2) throws Exception {
        String path = normalizePath(string);
        if (offlineMode) return OfflineStore.handle("POST", path, string2);
        return send("POST", path, string2);
    }

    public static String put(String string, String string2) throws Exception {
        String path = normalizePath(string);
        if (offlineMode) return OfflineStore.handle("PUT", path, string2);
        return send("PUT", path, string2);
    }

    public static String delete(String string) throws Exception {
        String path = normalizePath(string);
        if (offlineMode) return OfflineStore.handle("DELETE", path, "");
        return send("DELETE", path, "");
    }

    public static String getOnline(String path) throws Exception {
        return send("GET", normalizePath(path), "");
    }

    public static String postOnline(String path, String body) throws Exception {
        return send("POST", normalizePath(path), body);
    }

    public static String putOnline(String path, String body) throws Exception {
        return send("PUT", normalizePath(path), body);
    }

    public static String deleteOnline(String path) throws Exception {
        return send("DELETE", normalizePath(path), "");
    }

    public static synchronized boolean isOfflineMode() {
        return offlineMode;
    }

    public static synchronized void setOfflineMode(boolean offline) throws Exception {
        if (offline && !OfflineStore.isReady()) {
            throw new IllegalStateException("Prepare offline data while connected to the server before switching to Offline mode.");
        }
        Properties properties = new Properties();
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (FileInputStream input = new FileInputStream(file)) {
                properties.load(input);
            }
        }
        properties.putIfAbsent("server.url", serverUrl);
        properties.putIfAbsent("server.host", serverHost);
        properties.putIfAbsent("server.port", String.valueOf(serverPort));
        properties.setProperty("offline.mode", Boolean.toString(offline));
        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, "BKR Fast Food Client Configuration");
        }
        offlineMode = offline;
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String send(String method, String path, String body) throws Exception {
        String url = serverUrl + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(Duration.ofSeconds(method.equals("POST") ? 15L : 12L))
            .header("Accept", "application/json");
        if ("GET".equals(method)) builder.GET();
        else if ("POST".equals(method)) builder.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
        else if ("PUT".equals(method)) builder.header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
        else builder.DELETE();
        HttpResponse<String> httpResponse = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() >= 400) {
            throw new RuntimeException("Server Error (" + httpResponse.statusCode() + "): " + httpResponse.body());
        }
        return httpResponse.body();
    }

    public static boolean testConnection() {
        try {
            ApiClient.getOnline("/inventory");
            return true;
        }
        catch (Exception exception) {
            return false;
        }
    }

    static {
        ApiClient.loadConfig();
    }
}
