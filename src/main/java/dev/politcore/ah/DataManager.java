package dev.politcore.ah;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** История цен: config/politcore_ah/prices.json. Все цены хранятся за 1 штуку. */
public final class DataManager {

    public static final class Entry {
        public double price;
        public String seller = "";
        public long time;   // первый раз виден (мс)
        public long seen;   // последний раз виден (мс)
        public int n;       // размер стопки в лоте (0 = неизвестно)
        public int occ;     // номер одинакового лота на странице
        public String name = "";
    }

    public record ItemStats(double minCurrent, boolean currentIsFresh, double avg, int count, long lastSeen,
                            double sellPrice, int freshCount, double trend, double resalePrice) {}

    public record Deal(String name, String key, double unitPrice, double avg, double discountPct,
                       double profitUnit, int stack, String seller, double totalProfit) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Map<String, List<Entry>> data = new HashMap<>();
    private boolean dirty;
    private long lastSaveMs = System.currentTimeMillis();

    public DataManager(Path file) {
        this.file = file;
        load();
    }

    public static String keyOf(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (stack.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments ench = stack.get(DataComponents.STORED_ENCHANTMENTS);
            if (ench != null && !ench.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (var e : ench.entrySet()) {
                    parts.add(e.getKey().getRegisteredName() + ":" + e.getIntValue());
                }
                Collections.sort(parts);
                id += "|" + String.join(",", parts);
            }
        }
        return id;
    }

    static String shortName(String key) {
        int c = key.indexOf(':');
        String s = c >= 0 ? key.substring(c + 1) : key;
        int bar = s.indexOf('|');
        return bar >= 0 ? s.substring(0, bar) : s;
    }

    /** true — новая запись; false — лот уже известен, обновлено только время «виден». */
    public synchronized boolean record(String key, String name, double unitPrice, String seller,
                                       int stackCount, int occ, long now) {
        if (!(unitPrice > 0) || Double.isInfinite(unitPrice)) return false;
        AhConfig c = PolitcoreAh.config();
        String s = seller == null ? "" : seller;
        List<Entry> list = data.computeIfAbsent(key, k -> new ArrayList<>());
        long dedupe = c.dedupeMinutes * 60_000L;

        for (Entry e : list) {
            if (now - e.seen > dedupe) continue;
            if (Math.abs(e.price - unitPrice) > 1e-9 || !s.equals(e.seller) || e.occ != occ) continue;
            if (e.n == 0) e.n = stackCount;
            if (e.n != stackCount) continue;
            e.seen = now;
            dirty = true;
            return false;
        }
        Entry e = new Entry();
        e.price = unitPrice; e.seller = s; e.time = now; e.seen = now; e.n = stackCount; e.occ = occ;
        e.name = name == null ? "" : name;
        list.add(e);
        while (list.size() > Math.max(1, c.maxHistoryPerItem)) list.remove(0);
        dirty = true;
        return true;
    }

    public synchronized ItemStats stats(String key) {
        List<Entry> list = data.get(key);
        if (list == null || list.isEmpty()) return null;
        AhConfig c = PolitcoreAh.config();
        long now = System.currentTimeMillis();
        long window = c.currentWindowMinutes * 60_000L;

        double[] sorted = list.stream().mapToDouble(e -> e.price).sorted().toArray();
        double lowFence = Double.NEGATIVE_INFINITY, highFence = Double.POSITIVE_INFINITY;
        if (sorted.length >= c.minSamplesForOutlier) {
            double q1 = percentile(sorted, 0.25), q3 = percentile(sorted, 0.75);
            double iqr = q3 - q1;
            lowFence = q1 - c.outlierIqrFactor * iqr;
            highFence = q3 + c.outlierIqrFactor * iqr;
        }
        double avg = averagePrice(list, c, now, lowFence, highFence);
        double trend = trend(list, c, now, lowFence, highFence);

        double minCur = Double.POSITIVE_INFINITY, robustMin = Double.POSITIVE_INFINITY;
        long lastSeen = 0;
        int fresh = 0;
        for (Entry e : list) {
            lastSeen = Math.max(lastSeen, e.seen);
            if (now - e.seen <= window) {
                fresh++;
                if (e.price < minCur) minCur = e.price;
                if (e.price >= lowFence && e.price < robustMin) robustMin = e.price;
            }
        }
        boolean isFresh = fresh > 0;
        if (!isFresh) minCur = sorted[0];
        double freshMin = !isFresh ? Double.NaN : (Double.isInfinite(robustMin) ? minCur : robustMin);

        double sell = computeSell(c, avg, freshMin, sorted.length);
        double resale = avg * (1.0 - c.sellUndercutPercent / 100.0);
        return new ItemStats(minCur, isFresh, avg, list.size(), lastSeen, sell, fresh, trend, resale);
    }

    /** Лоты, дешевле средней минимум на dealThresholdPercent, виденные в последние dealsWindowMinutes. */
    public synchronized List<Deal> deals() {
        AhConfig c = PolitcoreAh.config();
        long now = System.currentTimeMillis();
        long win = c.dealsWindowMinutes * 60_000L;
        List<Deal> out = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> me : data.entrySet()) {
            List<Entry> l = me.getValue();
            if (l.size() < Math.max(2, c.sellMinSamples)) continue;
            ItemStats st = stats(me.getKey());
            if (st == null || st.avg() <= 0) continue;
            for (Entry e : l) {
                if (now - e.seen > win) continue;
                double disc = (st.avg() - e.price) / st.avg() * 100.0;
                if (disc < c.dealThresholdPercent) continue;
                double profit = st.resalePrice() - e.price;
                if (profit <= 0) continue;
                int n = Math.max(1, e.n);
                String name = e.name == null || e.name.isEmpty() ? shortName(me.getKey()) : e.name;
                out.add(new Deal(name, me.getKey(), e.price, st.avg(), disc, profit, n, e.seller, profit * n));
            }
        }
        out.sort(Comparator.comparingDouble(Deal::totalProfit).reversed());
        return out.size() > c.dealsMaxResults ? new ArrayList<>(out.subList(0, c.dealsMaxResults)) : out;
    }

    static double computeSell(AhConfig c, double avg, double freshMin, int samples) {
        double price = Double.isNaN(freshMin) ? avg : freshMin * (1.0 - c.sellUndercutPercent / 100.0);
        if (samples >= c.sellMinSamples) {
            double lo = avg * c.sellMinPercentOfAvg / 100.0;
            double hi = avg * c.sellMaxPercentOfAvg / 100.0;
            price = Math.max(lo, Math.min(hi, price));
        }
        if (c.sellRoundSignificantDigits > 0 && price > 0) {
            price = new BigDecimal(price).round(new MathContext(c.sellRoundSignificantDigits, RoundingMode.FLOOR)).doubleValue();
        }
        return price;
    }

    private static double averagePrice(List<Entry> list, AhConfig c, long now, double lo, double hi) {
        List<Entry> use = new ArrayList<>();
        for (Entry e : list) if (e.price >= lo && e.price <= hi) use.add(e);
        if (use.isEmpty()) use = list;
        String mode = c.avgMode == null ? "recent" : c.avgMode.toLowerCase(Locale.ROOT);

        if (mode.equals("median")) {
            double[] p = use.stream().mapToDouble(e -> e.price).sorted().toArray();
            int n = p.length;
            return n % 2 == 1 ? p[n / 2] : (p[n / 2 - 1] + p[n / 2]) / 2.0;
        }
        if (mode.equals("trimmed")) {
            return use.stream().mapToDouble(e -> e.price).average().orElse(0);
        }
        double halfLife = Math.max(0.1, c.recencyHalfLifeHours) * 3_600_000.0;
        double sw = 0, sp = 0;
        for (Entry e : use) {
            double w = Math.pow(0.5, Math.max(0, now - e.seen) / halfLife);
            sw += w;
            sp += w * e.price;
        }
        return sw > 0 ? sp / sw : use.stream().mapToDouble(e -> e.price).average().orElse(0);
    }

    /** Изменение цены, %: лоты за последние trendWindowHours против более старых. NaN — мало данных. */
    private static double trend(List<Entry> list, AhConfig c, long now, double lo, double hi) {
        long win = c.trendWindowHours * 3_600_000L;
        double rs = 0, os = 0;
        int rn = 0, on = 0;
        for (Entry e : list) {
            if (e.price < lo || e.price > hi) continue;
            if (now - e.time <= win) { rs += e.price; rn++; } else { os += e.price; on++; }
        }
        if (rn < 2 || on < 2) return Double.NaN;
        double r = rs / rn, o = os / on;
        return o > 0 ? (r - o) / o * 100.0 : Double.NaN;
    }

    private static double percentile(double[] sorted, double p) {
        double pos = p * (sorted.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    // ---------------- Сервис ----------------

    public synchronized int itemCount() { return data.size(); }

    public synchronized void clear() {
        data.clear();
        dirty = true;
        saveNow();
    }

    /** CSV для Excel: предмет;средняя;мин;продажа;записей. Возвращает число строк. */
    public synchronized int exportCsv(Path out) throws java.io.IOException {
        StringBuilder sb = new StringBuilder("item;avg;min;sell;offers\n");
        List<String> keys = new ArrayList<>(data.keySet());
        Collections.sort(keys);
        int rows = 0;
        for (String k : keys) {
            ItemStats s = stats(k);
            if (s == null) continue;
            sb.append(k).append(';')
              .append(String.format(Locale.ROOT, "%.2f", s.avg())).append(';')
              .append(String.format(Locale.ROOT, "%.2f", s.minCurrent())).append(';')
              .append(String.format(Locale.ROOT, "%.2f", s.sellPrice())).append(';')
              .append(s.count()).append('\n');
            rows++;
        }
        Files.createDirectories(out.getParent());
        Files.writeString(out, "\uFEFF" + sb, StandardCharsets.UTF_8);
        return rows;
    }

    // ---------------- IO ----------------

    private void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, List<Entry>> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, List<Entry>>>() {}.getType());
            if (loaded != null) data.putAll(loaded);
        } catch (Exception e) {
            PolitcoreAh.LOG.error("Не удалось прочитать prices.json", e);
        }
    }

    public synchronized void tickSave() {
        long now = System.currentTimeMillis();
        if (dirty && now - lastSaveMs >= PolitcoreAh.config().saveIntervalSeconds * 1000L) saveNow();
    }

    public synchronized void saveNow() {
        if (!dirty) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            lastSaveMs = System.currentTimeMillis();
        } catch (Exception e) {
            PolitcoreAh.LOG.error("Не удалось сохранить prices.json", e);
        }
    }
}
