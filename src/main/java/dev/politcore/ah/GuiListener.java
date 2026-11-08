package dev.politcore.ah;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.*;

/**
 * Перехват /ah без миксинов. Содержимое окна приходит от сервера уже после открытия,
 * поэтому сканируем в тике при смене хэша слотов (ловит и листание страниц).
 */
public final class GuiListener {
    private static long lastAhCommandMs = 0;
    private static long lastAhSeenMs = 0;
    private static Screen lastScreen;
    private static boolean lastIsAh;
    private static int lastHash;
    private static String lastTitle = "";
    private static final List<String> report = new ArrayList<>();

    private GuiListener() {}

    public static void register() {
        ClientSendMessageEvents.COMMAND.register(cmd -> {
            if (PolitcoreAh.parser().isAhCommand(cmd)) lastAhCommandMs = System.currentTimeMillis();
        });
        ClientTickEvents.END_CLIENT_TICK.register(GuiListener::tick);
    }

    /** Отчёт последнего скана — для /ahprice scan. */
    public static List<String> lastReport() { return new ArrayList<>(report); }

    private static void tick(Minecraft mc) {
        PolitcoreAh.data().tickSave();

        if (mc.gui == null) return;                 // в 26.2+ экран хранится в Gui, а не в Minecraft
        Screen s = mc.gui.screen();
        if (!(s instanceof ContainerScreen cs)) { lastScreen = null; return; }

        long now = System.currentTimeMillis();
        if (s != lastScreen) {
            lastScreen = s;
            lastHash = 0;
            lastTitle = s.getTitle().getString();
            AhConfig c = PolitcoreAh.config();
            boolean byTitle = PolitcoreAh.parser().isAhTitle(lastTitle);
            boolean byCmd = now - lastAhCommandMs <= c.ahCommandWindowSeconds * 1000L;
            boolean bySession = now - lastAhSeenMs <= c.ahSessionGraceSeconds * 1000L;   // листание страниц
            boolean skip = PolitcoreAh.parser().isIgnoredTitle(lastTitle);
            lastIsAh = !skip && (byTitle || byCmd || bySession);
            if (c.debug) PolitcoreAh.LOG.info("[debug] Открыто окно '{}', аукцион={} (title={}, cmd={}, session={})",
                    lastTitle, lastIsAh, byTitle, byCmd, bySession);
        }
        if (!lastIsAh) return;
        lastAhSeenMs = now;

        ChestMenu menu = cs.getMenu();
        int h = hash(menu);
        if (h == lastHash) return;
        lastHash = h;
        scan(menu, mc);
    }

    private static int hash(ChestMenu menu) {
        int h = 1;
        int n = menu.getRowCount() * 9;
        for (int i = 0; i < n; i++) {
            ItemStack st = menu.getSlot(i).getItem();
            if (st.isEmpty()) { h *= 31; continue; }
            h = 31 * h + st.getItem().hashCode();
            h = 31 * h + st.getCount();
            h = 31 * h + st.getComponents().hashCode();
        }
        return h;
    }

    private static void scan(ChestMenu menu, Minecraft mc) {
        AhConfig c = PolitcoreAh.config();
        PriceRegexParser parser = PolitcoreAh.parser();
        DataManager data = PolitcoreAh.data();
        long now = System.currentTimeMillis();
        int slots = menu.getRowCount() * 9;
        int nonEmpty = 0, lots = 0, added = 0, noPrice = 0, service = 0, own = 0;
        String me = "";
        try { me = mc.getUser().getName(); } catch (Throwable ignored) { }
        Map<String, Integer> occurrences = new HashMap<>();
        List<String> samples = new ArrayList<>();

        for (int i = 0; i < slots; i++) {                        // только слоты контейнера, не инвентарь игрока
            ItemStack st = menu.getSlot(i).getItem();
            if (st.isEmpty()) continue;
            nonEmpty++;
            String name = st.getHoverName().getString();
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            List<String> lore = readLore(st);

            Optional<PriceRegexParser.Lot> lot = parser.parse(lore);
            if (c.debug && !lore.isEmpty() && i < 12) PolitcoreAh.LOG.info("[debug] slot {} {} lore={}", i, name, lore);
            if (lot.isEmpty()) {                                 // нет цены в лоре
                noPrice++;
                if (!lore.isEmpty() && samples.size() < 6) {
                    samples.add("Без цены: " + name + " | " + String.join(" / ", lore.subList(0, Math.min(4, lore.size()))));
                }
                continue;
            }
            String seller = lot.get().seller();
            // Кнопки пропускаем, только если это не похоже на лот (нет продавца)
            if ((c.ignoredItems.contains(id) || parser.isIgnoredName(name)) && seller.isEmpty()) { service++; continue; }

            if (c.ignoreOwnLots && !me.isEmpty() && seller.equalsIgnoreCase(me)) { own++; continue; }

            int count = Math.max(1, st.getCount());
            double unit = c.priceIsForWholeStack ? lot.get().price() / count : lot.get().price();
            String key = DataManager.keyOf(st);
            // одинаковые лоты на странице получают разные номера и не склеиваются
            int occ = occurrences.merge(key + "|" + unit + "|" + seller + "|" + count, 1, Integer::sum) - 1;
            lots++;
            if (data.record(key, name, unit, seller, count, occ, now)) added++;
        }

        report.clear();
        report.add("Окно: '" + lastTitle + "', рядов: " + menu.getRowCount());
        report.add("Предметов: " + nonEmpty + ", лотов: " + lots + " (новых: " + added + "), без цены: " + noPrice + ", служебных: " + service + ", своих: " + own);
        report.addAll(samples);
        if (c.debug) report.forEach(l -> PolitcoreAh.LOG.info("[debug] {}", l));
    }

    /** Лор — компонент DataComponents.LORE (NBT у предметов с 1.20.5 больше нет). */
    public static List<String> readLore(ItemStack st) {
        List<String> out = new ArrayList<>();
        ItemLore lore = st.get(DataComponents.LORE);
        if (lore == null) return out;
        for (Component line : lore.lines()) out.add(line.getString());
        return out;
    }
}
