package dev.politcore.ah;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ItemTooltipHandler {
    private ItemTooltipHandler() {}

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipFlag, lines) -> {
            if (stack.isEmpty()) return;
            DataManager.ItemStats st = PolitcoreAh.data().stats(DataManager.keyOf(stack));
            if (st == null) return;
            AhConfig c = PolitcoreAh.config();

            lines.add(Component.literal("§7Средняя цена Politcore: §a" + fmt(st.avg())));
            lines.add(Component.literal("§7Мин. цена: §a" + fmt(st.minCurrent())));
            lines.add(Component.literal("§7Предложений в базе: §e" + st.count()));

            if (c.showTrend && !Double.isNaN(st.trend())) {
                String arrow = st.trend() >= 0 ? "§c↑ +" : "§a↓ ";
                lines.add(Component.literal("§7Тренд " + c.trendWindowHours + "ч: " + arrow + String.format(Locale.ROOT, "%.1f", st.trend()) + "%"));
            }
            if (c.showSellPrice) {
                String sell = "§7Цена для продажи: §6" + fmt(st.sellPrice());
                if (stack.getCount() > 1) sell += " §8(×" + stack.getCount() + " = " + fmt(st.sellPrice() * stack.getCount()) + ")";
                lines.add(Component.literal(sell));
            }
            if (c.showDealInfo) dealLines(stack, st, c, lines);
        });
    }

    /** Если предмет — лот на аукционе: показывает, насколько он выгоден относительно средней цены. */
    private static void dealLines(ItemStack stack, DataManager.ItemStats st, AhConfig c, List<Component> lines) {
        if (st.count() < c.sellMinSamples || st.avg() <= 0) return;
        Optional<PriceRegexParser.Lot> lot = PolitcoreAh.parser().parse(GuiListener.readLore(stack));
        if (lot.isEmpty()) return;
        int n = Math.max(1, stack.getCount());
        double unit = c.priceIsForWholeStack ? lot.get().price() / n : lot.get().price();
        double diff = (st.avg() - unit) / st.avg() * 100.0;
        if (diff >= c.dealThresholdPercent) {
            lines.add(Component.literal("§a★ Выгодно: §f−" + String.format(Locale.ROOT, "%.0f", diff) + "% §7от средней"));
            double profit = st.resalePrice() - unit;
            if (profit > 0) {
                lines.add(Component.literal("§7Профит при перепродаже: §a+" + fmt(profit * n)
                        + (n > 1 ? " §8(" + fmt(profit) + "/шт.)" : "")));
            }
        } else if (diff <= -10.0) {
            lines.add(Component.literal("§c✖ Дороже средней на " + String.format(Locale.ROOT, "%.0f", -diff) + "%"));
        }
    }

    /** 1234567.5 → "1.234.567,5$" */
    public static String fmt(double v) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator('.');
        sym.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", sym).format(v) + "$";
    }
}
