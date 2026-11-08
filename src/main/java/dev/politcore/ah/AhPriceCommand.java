package dev.politcore.ah;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class AhPriceCommand {
    private static final String TAG = "§6[Politcore AH] ";
    private AhPriceCommand() {}

    private static void say(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(TAG + msg));
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(literal("ahprice")
                        .executes(ctx -> showStats(ctx.getSource()))
                        .then(literal("help").executes(ctx -> help(ctx.getSource())))
                        .then(literal("deals").executes(ctx -> deals(ctx.getSource())))
                        .then(literal("scan").executes(ctx -> scan(ctx.getSource())))
                        .then(literal("export").executes(ctx -> export(ctx.getSource())))
                        .then(literal("reload").executes(ctx -> {
                            PolitcoreAh.reload();
                            say(ctx.getSource(), "§aКонфиг перезагружен");
                            return 1;
                        }))
                        .then(literal("save").executes(ctx -> {
                            PolitcoreAh.data().saveNow();
                            say(ctx.getSource(), "§aБаза сохранена");
                            return 1;
                        }))
                        .then(literal("clear")
                                .executes(ctx -> {
                                    say(ctx.getSource(), "§cЭто удалит ВСЮ базу цен. Подтвердите: §e/ahprice clear confirm");
                                    return 0;
                                })
                                .then(literal("confirm").executes(ctx -> {
                                    PolitcoreAh.data().clear();
                                    say(ctx.getSource(), "§aБаза очищена");
                                    return 1;
                                })))));
    }

    private static int help(FabricClientCommandSource src) {
        say(src, "§fКоманды:");
        src.sendFeedback(Component.literal("§e/ahprice §7— статистика и цена продажи предмета в руке"));
        src.sendFeedback(Component.literal("§e/ahprice deals §7— самые выгодные лоты из недавно виденных"));
        src.sendFeedback(Component.literal("§e/ahprice scan §7— отчёт последнего скана (диагностика)"));
        src.sendFeedback(Component.literal("§e/ahprice export §7— выгрузка базы в CSV"));
        src.sendFeedback(Component.literal("§e/ahprice reload §7— перечитать config.json"));
        src.sendFeedback(Component.literal("§e/ahprice save §7— сохранить базу сейчас"));
        src.sendFeedback(Component.literal("§e/ahprice clear confirm §7— очистить базу"));
        return 1;
    }

    private static int showStats(FabricClientCommandSource src) {
        ItemStack st = src.getPlayer().getMainHandItem();
        if (st.isEmpty()) {
            say(src, "§cВозьмите предмет в руку. Все команды: §e/ahprice help");
            return 0;
        }
        String name = st.getHoverName().getString();
        DataManager.ItemStats s = PolitcoreAh.data().stats(DataManager.keyOf(st));
        if (s == null) {
            say(src, "§7Нет данных по §f" + name + "§7. Откройте §e/ah§7, чтобы собрать цены.");
            return 0;
        }
        long mins = Math.max(0, (System.currentTimeMillis() - s.lastSeen()) / 60_000L);
        say(src, "§f" + name + " §7(цены за 1 шт.)");
        src.sendFeedback(Component.literal("§7Средняя цена: §a" + ItemTooltipHandler.fmt(s.avg())));
        src.sendFeedback(Component.literal("§7Мин. цена: §a" + ItemTooltipHandler.fmt(s.minCurrent())
                + (s.currentIsFresh() ? " §8(актуальных лотов: " + s.freshCount() + ")" : " §8(давно не видели)")));
        src.sendFeedback(Component.literal("§7Предложений в базе: §e" + s.count()));
        if (!Double.isNaN(s.trend())) {
            src.sendFeedback(Component.literal("§7Тренд: " + (s.trend() >= 0 ? "§c↑ +" : "§a↓ ")
                    + String.format(Locale.ROOT, "%.1f", s.trend()) + "%"));
        }
        String sell = "§7Цена для продажи: §6" + ItemTooltipHandler.fmt(s.sellPrice());
        if (st.getCount() > 1) sell += " §8(за " + st.getCount() + " шт.: " + ItemTooltipHandler.fmt(s.sellPrice() * st.getCount()) + ")";
        src.sendFeedback(Component.literal(sell));
        src.sendFeedback(Component.literal("§7Последний раз виден: §e" + mins + " мин. назад"));
        return 1;
    }

    private static int deals(FabricClientCommandSource src) {
        List<DataManager.Deal> list = PolitcoreAh.data().deals();
        AhConfig c = PolitcoreAh.config();
        if (list.isEmpty()) {
            say(src, "§7Выгодных лотов (от −" + (int) c.dealThresholdPercent + "% к средней) за последние "
                    + c.dealsWindowMinutes + " мин. не найдено. Пролистайте §e/ah§7 и повторите.");
            return 0;
        }
        say(src, "§fВыгодные лоты (виденные за " + c.dealsWindowMinutes + " мин.):");
        for (DataManager.Deal d : list) {
            src.sendFeedback(Component.literal("§a−" + String.format(Locale.ROOT, "%.0f", d.discountPct()) + "% §f" + d.name()
                    + (d.stack() > 1 ? " §7×" + d.stack() : "")
                    + " §7— §e" + ItemTooltipHandler.fmt(d.unitPrice()) + "§7/шт. §8(ср. " + ItemTooltipHandler.fmt(d.avg())
                    + ") §7профит §a+" + ItemTooltipHandler.fmt(d.totalProfit())
                    + (d.seller().isEmpty() ? "" : " §8" + d.seller())));
        }
        return 1;
    }

    private static int scan(FabricClientCommandSource src) {
        List<String> rep = GuiListener.lastReport();
        if (rep.isEmpty()) {
            say(src, "§7Сканирования ещё не было. Откройте §e/ah§7.");
            return 0;
        }
        say(src, "§fОтчёт последнего скана:");
        for (String line : rep) src.sendFeedback(Component.literal("§7" + line));
        return 1;
    }

    private static int export(FabricClientCommandSource src) {
        try {
            Path out = PolitcoreAh.DIR.resolve("export.csv");
            int rows = PolitcoreAh.data().exportCsv(out);
            say(src, "§aЭкспортировано предметов: §e" + rows + "§a. Файл: §f" + out);
            return 1;
        } catch (Exception e) {
            say(src, "§cНе удалось записать CSV: " + e.getMessage());
            return 0;
        }
    }
}
