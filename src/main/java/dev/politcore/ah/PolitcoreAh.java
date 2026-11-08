package dev.politcore.ah;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class PolitcoreAh implements ClientModInitializer {
    public static final String MOD_ID = "politcore_ah";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

    private static AhConfig config;
    private static PriceRegexParser parser;
    private static DataManager data;

    public static AhConfig config() { return config; }
    public static PriceRegexParser parser() { return parser; }
    public static DataManager data() { return data; }

    @Override
    public void onInitializeClient() {
        reload();
        GuiListener.register();
        ItemTooltipHandler.register();
        AhPriceCommand.register();
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> data.saveNow());
        LOG.info("Politcore AH Prices загружен. Конфиг: {}", DIR);
    }

    /** Перечитывает config.json и пересобирает regex-парсер (prices.json не трогается). */
    public static synchronized void reload() {
        config = AhConfig.loadOrCreate(DIR.resolve("config.json"));
        parser = new PriceRegexParser(config);
        if (data == null) {
            data = new DataManager(DIR.resolve("prices.json"));
        }
    }
}
