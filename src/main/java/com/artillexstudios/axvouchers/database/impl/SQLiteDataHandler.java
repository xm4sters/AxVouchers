package com.artillexstudios.axvouchers.database.impl;

import com.artillexstudios.axapi.utils.Pair;
import com.artillexstudios.axvouchers.config.Config;
import com.artillexstudios.axvouchers.database.DataHandler;
import com.artillexstudios.axvouchers.database.log.VoucherLog;
import com.artillexstudios.axvouchers.utils.FileUtils;
import com.artillexstudios.axvouchers.voucher.Voucher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;

public class SQLiteDataHandler implements DataHandler {
    private static final Logger log = LoggerFactory.getLogger(SQLiteDataHandler.class);
    private HikariDataSource dataSource;

    @Override
    public void setup() {
        closeDataSource();

        HikariConfig config = new HikariConfig();
        config.setPoolName("axvouchers-sqlite");
        applyPoolSettings(config, 1, 1);

        String dbPath = new File(FileUtils.PLUGIN_DIRECTORY.toFile(), "data.db").getAbsolutePath();
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setConnectionTestQuery("SELECT 1");
        config.setConnectionInitSql("PRAGMA journal_mode=WAL;");

        dataSource = createDataSource(config);
        if (!isReady()) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            createTables(connection);
        } catch (SQLException exception) {
            log.error("An unexpected error occurred while setting up database!", exception);
            closeDataSource();
        }
    }

    @Override
    public void insertAntidupe(UUID uuid, int amount) {
        if (!isReady()) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO `axvouchers_vouchers`(`uuid`, `amount`, `used`) VALUES (?,?,?);")) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, amount);
            statement.setInt(3, 0);
            statement.executeUpdate();
        } catch (SQLException exception) {
            log.error("An error occurred while inserting antidupe uuid into the database!", exception);
        }
    }

    @Override
    public void incrementUsed(UUID uuid) {
        if (!isReady()) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE `axvouchers_vouchers` SET `used` = `used` + 1 WHERE `uuid` = ?;")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            log.error("An error occurred while inserting antidupe uuid into the database!", exception);
        }
    }

    @Override
    public boolean isDuped(UUID uuid) {
        if (!isReady()) {
            return false;
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT `amount`, `used` FROM `axvouchers_vouchers` WHERE `uuid` = ?;")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    int amount = resultSet.getInt("amount");
                    int used = resultSet.getInt("used");
                    return amount <= used;
                }
            }
        } catch (SQLException exception) {
            log.error("An error occurred while checking dupe status!", exception);
        }

        return false;
    }

    @Override
    public void insertLog(Player player, Voucher voucher, UUID uuid, String removeReason) {
        if (!isReady()) {
            return;
        }

        Pair<UUID, Integer> userId = getUserId(player.getName());
        if (userId == null) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO `axvouchers_logs`(`voucher_type`, `voucher_uuid`, `user_id`, `time`, `remove_reason`) VALUES (?,?,?,CURRENT_TIMESTAMP,?);")) {
            statement.setString(1, voucher.getId());
            statement.setString(2, uuid.toString());
            statement.setInt(3, userId.second());
            statement.setString(4, removeReason);
            statement.executeUpdate();
        } catch (SQLException exception) {
            log.error("An error occurred while inserting log into the database!", exception);
        }
    }

    @Override
    public VoucherLog getLogs(String name) {
        if (!isReady()) {
            return null;
        }

        Pair<UUID, Integer> userId = getUserId(name);
        if (userId == null) {
            return null;
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM `axvouchers_logs` WHERE `user_id` = ?;")) {
            statement.setInt(1, userId.second());
            try (ResultSet resultSet = statement.executeQuery()) {
                VoucherLog log = new VoucherLog(userId.first(), name);
                while (resultSet.next()) {
                    int id = resultSet.getInt("id");
                    String type = resultSet.getString("voucher_type");
                    UUID voucherUUID = UUID.fromString(resultSet.getString("voucher_uuid"));
                    Timestamp time = resultSet.getTimestamp("time");
                    String removeReason = resultSet.getString("remove_reason");
                    log.add(new VoucherLog.Entry(id, type, time, voucherUUID, removeReason));
                }
                return log;
            }
        } catch (SQLException exception) {
            log.error("An error occurred while getting logs from database!", exception);
        }
        return null;
    }

    @Override
    public Pair<UUID, Integer> getUserId(String name) {
        if (!isReady()) {
            return null;
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM `axvouchers_users` WHERE `name` = ?;")) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Pair.of(UUID.fromString(resultSet.getString("uuid")), resultSet.getInt("id"));
                }
            }

            Player player = Bukkit.getPlayer(name);
            if (player == null) {
                return null;
            }

            try (PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO `axvouchers_users`(`uuid`, `name`) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
                insertStatement.setString(1, player.getUniqueId().toString());
                insertStatement.setString(2, name);
                insertStatement.executeUpdate();
                try (ResultSet generated = insertStatement.getGeneratedKeys()) {
                    if (generated.next()) {
                        return Pair.of(player.getUniqueId(), generated.getInt(1));
                    }
                }
            }
        } catch (SQLException exception) {
            log.error("An error occurred while inserting user into the database!", exception);
        }

        return null;
    }

    @Override
    public void disable() {
        closeDataSource();
    }

    private void createTables(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CREATE TABLE IF NOT EXISTS `axvouchers_users`(`id` INTEGER PRIMARY KEY AUTOINCREMENT, `name` VARCHAR(16), `uuid` VARCHAR(36));")) {
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("CREATE TABLE IF NOT EXISTS `axvouchers_vouchers`(`uuid` VARCHAR(36) PRIMARY KEY, `amount` INT, `used` INT);")) {
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("CREATE TABLE IF NOT EXISTS `axvouchers_logs`(`id` INTEGER PRIMARY KEY AUTOINCREMENT, `user_id` INT, `time` TIMESTAMP, `voucher_type` VARCHAR(128), `voucher_uuid` VARCHAR(36), `remove_reason` VARCHAR(256));")) {
            statement.executeUpdate();
        }
    }

    private void applyPoolSettings(HikariConfig config, int maxPoolSize, int minIdle) {
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setMaxLifetime(Config.DATABASE_MAXIMUM_LIFETIME);
        config.setKeepaliveTime(Config.DATABASE_KEEPALIVE_TIME);
        config.setConnectionTimeout(Config.DATABASE_CONNECTION_TIMEOUT);
    }

    private HikariDataSource createDataSource(HikariConfig config) {
        try {
            return new HikariDataSource(config);
        } catch (RuntimeException exception) {
            log.error("An unexpected error occurred while setting up database!", exception);
            return null;
        }
    }

    private void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private boolean isReady() {
        return dataSource != null && !dataSource.isClosed();
    }
}
