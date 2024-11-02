package com.artillexstudios.axvouchers.command;

import com.artillexstudios.axapi.items.WrappedItemStack;
import com.artillexstudios.axapi.items.component.DataComponents;
import com.artillexstudios.axapi.utils.ContainerUtils;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axvouchers.AxVouchersPlugin;
import com.artillexstudios.axvouchers.command.argument.Arguments;
import com.artillexstudios.axvouchers.config.Config;
import com.artillexstudios.axvouchers.config.Messages;
import com.artillexstudios.axvouchers.gui.VoucherGUI;
import com.artillexstudios.axvouchers.gui.VoucherLogGUI;
import com.artillexstudios.axvouchers.utils.FileUtils;
import com.artillexstudios.axvouchers.voucher.Voucher;
import com.artillexstudios.axvouchers.voucher.Vouchers;
import com.zaxxer.hikari.HikariDataSource;
import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIBukkitConfig;
import dev.jorel.commandapi.CommandTree;
import dev.jorel.commandapi.arguments.IntegerArgument;
import dev.jorel.commandapi.arguments.LiteralArgument;
import dev.jorel.commandapi.arguments.MapArgumentBuilder;
import dev.jorel.commandapi.arguments.OfflinePlayerArgument;
import dev.jorel.commandapi.arguments.PlayerArgument;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public enum VoucherCommand {
    INSTANCE;

    public void load(JavaPlugin plugin) {
        CommandAPI.onLoad(new CommandAPIBukkitConfig(plugin).skipReloadDatapacks(true).setNamespace("axvouchers"));
    }

    public void register() {
        new CommandTree("voucher")
                .withAliases("vouchers", "axvoucher", "axvouchers")
                .then(new LiteralArgument("give")
                        .withPermission("axvouchers.command.give")
                        .then(new PlayerArgument("player")
                                .then(Arguments.INSTANCE.voucher("voucher")
                                        .then(new IntegerArgument("amount")
                                                .then(new MapArgumentBuilder<String, String>("placeholders")
                                                        .withKeyMapper(s -> s)
                                                        .withValueMapper(s -> s)
                                                        .withKeyList(Vouchers.placeholders())
                                                        .withoutValueList(true)
                                                        .build()
                                                        .executes(context -> {
                                                            give(context.sender(), context.args().getUnchecked("player"), context.args().getUnchecked("voucher"), context.args().getUnchecked("amount"), context.args().getUnchecked("placeholders"));
                                                        })
                                                )
                                                .executes(context -> {
                                                    give(context.sender(), context.args().getUnchecked("player"), context.args().getUnchecked("voucher"), context.args().getUnchecked("amount"), null);
                                                })
                                        )
                                        .executes(context -> {
                                            give(context.sender(), context.args().getUnchecked("player"), context.args().getUnchecked("voucher"), 1, null);
                                        })
                                )
                        )
                )
                .then(new LiteralArgument("reload")
                        .withPermission("axvouchers.command.reload")
                        .executes(context -> {
                            long start = System.currentTimeMillis();
                            List<File> failed = new ArrayList<>();

                            Vouchers.placeholders().clear();
                            if (!Config.reload()) {
                                failed.add(FileUtils.PLUGIN_DIRECTORY.resolve("config.yml").toFile());
                            }
                            if (!Messages.reload()) {
                                failed.add(FileUtils.PLUGIN_DIRECTORY.resolve("messages.yml").toFile());
                            }

                            Vouchers.loadAll();
                            failed.addAll(Vouchers.getFailed());

                            for (Voucher voucher : Vouchers.getVouchers()) {
                                Vouchers.placeholders().addAll(voucher.placeholders());
                            }

                            if (failed.isEmpty()) {
                                context.sender().sendMessage(StringUtils.formatToString(Messages.PREFIX + Messages.RELOAD_SUCCESS, Placeholder.parsed("time", String.valueOf(System.currentTimeMillis() - start))));
                            } else {
                                context.sender().sendMessage(StringUtils.formatToString(Messages.PREFIX + Messages.RELOAD_FAIL, Placeholder.parsed("time", String.valueOf(System.currentTimeMillis() - start)), Placeholder.parsed("files", String.join(", ", failed.stream().map(File::getName).toList()))));
                            }

                            for (Player player : Bukkit.getOnlinePlayers()) {
                                player.updateInventory();
                            }
                        })
                )
                .then(new LiteralArgument("list")
                        .withPermission("axvouchers.command.list")
                        .executes(context -> {
                            context.sender().sendMessage(StringUtils.formatToString(Messages.PREFIX + Messages.LIST, Placeholder.parsed("vouchers", String.join(", ", Vouchers.getVoucherNames()))));
                        })
                )
                .then(new LiteralArgument("logs")
                        .withPermission("axvouchers.command.logs")
                        .then(new OfflinePlayerArgument("player")
                                .executesPlayer(context -> {
                                    Player sender = context.sender();
                                    OfflinePlayer user = context.args().getUnchecked("player");

                                    VoucherLogGUI.INSTANCE.open(sender, user);
                                })
                        )
                )
                .then(new LiteralArgument("gui")
                        .withPermission("axvouchers.command.gui")
                        .executesPlayer(context -> {
                            Player sender = context.sender();

                            VoucherGUI.INSTANCE.open(sender);
                        })
                )
                .then(new LiteralArgument("perf")
                        .executes((sender, args) -> {
                            Connection[] conn = new Connection[10];
                            ExecutorService service = Executors.newFixedThreadPool(5);
                            try {
                                Class.forName("org.sqlite.JDBC");
                                conn[0] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[1] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[2] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[3] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[4] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[5] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[6] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[7] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[8] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                                conn[9] = DriverManager.getConnection(String.format("jdbc:sqlite:%s/data4.db", AxVouchersPlugin.getInstance().getDataFolder()));
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }

                            try (PreparedStatement statement = conn[0].prepareStatement("PRAGMA journal_mode=WAL;")) {
                                statement.executeQuery();
                            } catch (Exception exception) {
                                exception.printStackTrace();
                            }

                            try (PreparedStatement statement = conn[0].prepareStatement("PRAGMA synchronous = normal;")) {
                                statement.executeUpdate();
                            } catch (Exception exception) {
                                exception.printStackTrace();
                            }

                            try (PreparedStatement statement = conn[0].prepareStatement("PRAGMA page_size = 32768;")) {
                                statement.executeUpdate();
                            } catch (Exception exception) {
                                exception.printStackTrace();
                            }

                            try (PreparedStatement statement = conn[0].prepareStatement("PRAGMA mmap_size = 30000000000;")) {
                                statement.executeQuery();
                            } catch (Exception exception) {
                                exception.printStackTrace();
                            }

                            final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS test (abc VARCHAR(36) NOT NULL,a41 VARCHAR(36) NOT NULL, num INT NOT NULL, a53 VARCHAR(36) NOT NULL,a5315bc VARCHAR(36) NOT NULL,a11bc VARCHAR(36) NOT NULL);";

                            try (PreparedStatement stmt = conn[0].prepareStatement(CREATE_TABLE)) {
                                stmt.executeUpdate();
                            } catch (SQLException exception) {
                                exception.printStackTrace();
                            }

                            long time = System.currentTimeMillis();

                            ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(100000);
                            for (int i = 0; i < 100000; i++) {
                                final int finalI = i;
                                CompletableFuture<Void> future = new CompletableFuture<>();
                                service.submit(() -> {
                                    try (PreparedStatement stmt = conn[ThreadLocalRandom.current().nextInt(conn.length)].prepareStatement("INSERT INTO test (abc, a41, num, a53, a5315bc, a11bc) VALUES (?,?,?,?,?,?);")){
                                        stmt.setString(1, UUID.randomUUID().toString());
                                        stmt.setString(2, UUID.randomUUID().toString());
                                        stmt.setInt(3, finalI);
                                        stmt.setString(4, UUID.randomUUID().toString());
                                        stmt.setString(5, UUID.randomUUID().toString());
                                        UUID last = UUID.randomUUID();
                                        stmt.setString(6, last.toString());
                                        stmt.executeUpdate();
                                        future.complete(null);
                                    } catch (Exception ex) {
                                        ex.printStackTrace();
                                    }
                                });
                                futures.add(future);
                            }

                            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
                                System.out.println("insert: " + (System.currentTimeMillis() - time));

                                long time2 = System.currentTimeMillis();
                                try (PreparedStatement stmt = conn[0].prepareStatement("SELECT * FROM test;")){
                                    stmt.executeQuery().close();
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                                System.out.println("find all: " + (System.currentTimeMillis() - time2));

//                            long time3 = System.currentTimeMillis();
//                            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM test WHERE a11bc = ?;")){
//                                stmt.setObject(1, last);
//                                stmt.executeQuery().close();
//                            } catch (Exception ex) {
//                                ex.printStackTrace();
//                            }
//                            System.out.println("find (last uuid): " + (System.currentTimeMillis() - time3));

                                long time4 = System.currentTimeMillis();
                                try (PreparedStatement stmt = conn[0].prepareStatement("SELECT * FROM test WHERE a11bc = ?;")){
                                    stmt.setObject(1, UUID.randomUUID());
                                    stmt.executeQuery().close();
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                                System.out.println("find (new uuid): " + (System.currentTimeMillis() - time4));

                                try {
                                    for (Connection connection : conn) {
                                        connection.close();
                                    }
                                } catch (SQLException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                        })
                )
                .register();
    }

    public void give(CommandSender sender, Player player, Voucher voucher, Integer amount, LinkedHashMap<String, String> placeholders) {
        if ((placeholders == null && !voucher.placeholders().isEmpty()) || (placeholders != null && placeholders.size() < voucher.placeholders().size())) {
            List<String> required = new ArrayList<>(voucher.placeholders());
            if (placeholders != null) {
                required.removeAll(placeholders.values());
            }
            sender.sendMessage(StringUtils.formatToString(Messages.PREFIX + Messages.PLACEHOLDERS_NOT_SET, Placeholder.unparsed("placeholders", String.join(", ", required))));
            return;
        }

        ItemStack itemStack = voucher.getItemStack(amount, placeholders);
        TagResolver[] tagResolvers = null;
        if (!voucher.placeholders().isEmpty()) {
            tagResolvers = Vouchers.tagResolvers(Vouchers.placeholderString(WrappedItemStack.wrap(itemStack).get(DataComponents.customData())));
        }

        TagResolver.Single amountPlaceholder = Placeholder.parsed("amount", String.valueOf(amount));
        TagResolver.Single playerPlaceholder = Placeholder.parsed("player", player.getName());
        TagResolver.Single name = Placeholder.parsed("name", MiniMessage.miniMessage().serialize(StringUtils.format(voucher.getName(), tagResolvers == null ? new TagResolver[0] : tagResolvers)));

        ContainerUtils.INSTANCE.addOrDrop(player.getInventory(), List.of(itemStack), player.getLocation());
        sender.sendMessage(StringUtils.formatToString(Messages.PREFIX + Messages.GIVE, amountPlaceholder, playerPlaceholder, name));

        if (!Messages.RECEIVE.isBlank()) {
            player.sendMessage(StringUtils.formatToString(Messages.PREFIX + Messages.RECEIVE, amountPlaceholder, name));
        }
    }
}
