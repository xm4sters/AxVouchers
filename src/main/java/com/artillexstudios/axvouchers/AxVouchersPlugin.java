package com.artillexstudios.axvouchers;

import com.artillexstudios.axapi.AxPlugin;
import com.artillexstudios.axapi.items.PacketItemModifier;
import com.artillexstudios.axapi.utils.*;
import com.artillexstudios.axapi.utils.featureflags.FeatureFlags;
import com.artillexstudios.axvouchers.command.VoucherCommand;
import com.artillexstudios.axvouchers.config.Config;
import com.artillexstudios.axvouchers.config.Messages;
import com.artillexstudios.axvouchers.database.DataHandler;
import com.artillexstudios.axvouchers.database.impl.H2DataHandler;
import com.artillexstudios.axvouchers.database.impl.MySQLDataHandler;
import com.artillexstudios.axvouchers.database.impl.SQLiteDataHandler;
import com.artillexstudios.axvouchers.listeners.CraftListener;
import com.artillexstudios.axvouchers.listeners.FireworkListener;
import com.artillexstudios.axvouchers.listeners.PlayerListener;
import com.artillexstudios.axvouchers.listeners.VoucherUseListener;
import com.artillexstudios.axvouchers.voucher.Voucher;
import com.artillexstudios.axvouchers.voucher.VoucherItemModifier;
import com.artillexstudios.axvouchers.voucher.Vouchers;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import revxrsal.zapper.Dependency;
import revxrsal.zapper.DependencyManager;
import revxrsal.zapper.classloader.URLClassLoaderWrapper;
import revxrsal.zapper.repository.MavenRepository;
import revxrsal.commands.bukkit.BukkitCommandActor;
import revxrsal.commands.bukkit.BukkitCommandHandler;
import revxrsal.commands.exception.CommandErrorException;

import java.io.File;
import java.net.URLClassLoader;

public class AxVouchersPlugin extends AxPlugin {
    private static AxVouchersPlugin INSTANCE;
    private DataHandler dataHandler;

    public static AxVouchersPlugin getInstance() {
        return INSTANCE;
    }

    public DataHandler getDataHandler() {
        return dataHandler;
    }

    @Override
    public void updateFlags() {
        FeatureFlags.PACKET_ENTITY_TRACKER_ENABLED.set(true);
        FeatureFlags.ENABLE_PACKET_LISTENERS.set(true);
        FeatureFlags.DEBUG.set(false);
    }

    @Override
    public void enable() {
        INSTANCE = this;

        loadLibraries();

        reload();

        dataHandler = switch (Config.DATABASE_TYPE) {
            case H2 -> new H2DataHandler();
            case SQLite -> new SQLiteDataHandler();
            case MySQL -> new MySQLDataHandler();
        };
        dataHandler.setup();

        Bukkit.getPluginManager().registerEvents(new FireworkListener(), this);
        Bukkit.getPluginManager().registerEvents(new VoucherUseListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);

        if (Config.PREVENT_CRAFTS) {
            Bukkit.getPluginManager().registerEvents(new CraftListener(), this);
        }

        PacketItemModifier.registerModifierListener(new VoucherItemModifier());

        loadCommands();
    }

    private void loadCommands() {
        BukkitCommandHandler handler = BukkitCommandHandler.create(this);

        handler.registerValueResolver(Voucher.class, resolver -> {
            String voucherName = resolver.popForParameter();
            Voucher voucher = Vouchers.parse(voucherName);
            if (voucher == null) {
                resolver.actor().as(BukkitCommandActor.class).getSender().sendMessage(StringUtils.formatToString(Messages.PREFIX + Messages.VOUCHER_NOT_FOUND, Placeholder.parsed("name", voucherName)));
                throw new CommandErrorException();
            }

            return voucher;
        });

        handler.getAutoCompleter().registerParameterSuggestions(Voucher.class, (args, sender, command) -> Vouchers.getVoucherNames());

        handler.register(new VoucherCommand());

        if (handler.isBrigadierSupported()) {
            handler.registerBrigadier();
        }
    }

    private void loadLibraries() {
        DependencyManager dependencyManager = new DependencyManager(
                getDescription(),
                new File(getDataFolder(), "libs"),
                URLClassLoaderWrapper.wrap((URLClassLoader) getClassLoader())
        );
        dependencyManager.repository(MavenRepository.mavenCentral());
        dependencyManager.dependency(new Dependency("org.xerial", "sqlite-jdbc", "3.51.1.0"));
        dependencyManager.dependency(new Dependency("com.h2database", "h2", "2.4.240"));
        dependencyManager.load();
    }

    @Override
    public void reload() {
        Config.reload();
        FeatureFlags.DEBUG.set(Config.DEBUG);
        Messages.reload();

        Vouchers.loadAll();
    }

    @Override
    public void disable() {
        if (dataHandler != null) {
            dataHandler.disable();
            DataHandler.DATA_THREAD.stop();
        }
    }
}
