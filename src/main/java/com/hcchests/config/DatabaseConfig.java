package com.hcchests.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

public class DatabaseConfig {
    private String url = "jdbc:postgresql://postgres:5432/factionwars";
    private String username = "factionwars";
    private String password = "factionwars_secret";
    private int poolSize = 3;

    public static DatabaseConfig load(File modFolder) {
        DatabaseConfig config = new DatabaseConfig();
        File propsFile = new File(modFolder, "database.properties");

        if (propsFile.exists()) {
            try (var fis = new FileInputStream(propsFile)) {
                Properties props = new Properties();
                props.load(fis);
                config.url = props.getProperty("url", config.url);
                config.username = props.getProperty("username", config.username);
                config.password = props.getProperty("password", config.password);
                config.poolSize = Integer.parseInt(props.getProperty("poolSize", String.valueOf(config.poolSize)));
            } catch (Exception ignored) {}
        } else {
            modFolder.mkdirs();
            try (var fos = new FileOutputStream(propsFile)) {
                Properties props = new Properties();
                props.setProperty("url", config.url);
                props.setProperty("username", config.username);
                props.setProperty("password", config.password);
                props.setProperty("poolSize", String.valueOf(config.poolSize));
                props.store(fos, "HC_Chests Database Configuration");
            } catch (Exception ignored) {}
        }
        return config;
    }

    public String getUrl() { return url; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public int getPoolSize() { return poolSize; }
}
