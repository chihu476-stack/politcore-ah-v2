package dev.politcore.ah;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Конфиг: config/politcore_ah/config.json. Всё меняется без пересборки (/ahprice reload). */
public class AhConfig {

    public static final int CURRENT_VERSION = 3;
    public int configVersion = 0;

    // --- Определение окна аукциона ---
    public String ahTitlePattern = "(?iu)(аукцион|auction|\\bah\\b)";
    public String ahCommandPattern = "(?iu)^/?(ah|auction|auc|аукцион)(\\s.*)?$";
    public int ahCommandWindowSeconds = 8;
    /** Окно, открытое в течение N секунд после аукциона, тоже считается аукционом (листание страниц). */
    public int ahSessionGraceSeconds = 4;
    /** Окна с таким заголовком не сканируются (свои лоты, подтверждения и т.п.). */
    public String ignoredTitlePattern = "(?iu)(мои лоты|мои предметы|истекш|expired|my items|my auctions|подтвержд|confirm)";
    /** Не учитывать свои собственные лоты в статистике. */
    public boolean ignoreOwnLots = true;

    // --- Парсинг лора ---
    public List<String> pricePatterns = new ArrayList<>(List.of(
            "(?iu)(?:цена|стоимость|price|cost)\\s*[:：\\-]?\\s*(?<price>.+)$",
            "(?iu)(?<price>\\d[\\d.,\\s\\u00A0]*(?:ккк|кк|kkk|kk|млрд|млн|тыс|[kкmмb])?\\s*(?:\\$|монет\\w*|coins?))"
    ));
    public List<String> sellerPatterns = new ArrayList<>(List.of(
            "(?iu)(?:продавец|seller|владелец|owner)\\s*[:：]?\\s*(?<seller>[\\p{L}\\p{N}_]+)"
    ));

    // --- Служебные предметы (пропускаются, только если в лоре нет продавца) ---
    public List<String> ignoredItems = new ArrayList<>(List.of("minecraft:barrier"));
    public String ignoredNamePattern =
            "(?iu)(следующая страница|предыдущая страница|страница\\s*\\d|next page|previous page|prev page|\\bback\\b|назад|вперед|вперёд|фильтр|filter|сортировк|sorting|обновить|refresh|закрыть|\\bclose\\b|мои лоты|история)";

    // --- Обработка данных ---
    public boolean priceIsForWholeStack = true;
    public int maxHistoryPerItem = 300;
    public int dedupeMinutes = 180;
    /** «Актуальные» лоты — виденные за последние N минут. */
    public int currentWindowMinutes = 60;
    public double outlierIqrFactor = 1.5;
    public int minSamplesForOutlier = 4;
    public int saveIntervalSeconds = 30;

    // --- Средняя цена ---
    /** "recent" — среднее с затуханием старых лотов, "trimmed" — обычное без выбросов, "median" — медиана. */
    public String avgMode = "recent";
    public double recencyHalfLifeHours = 48.0;

    // --- Рекомендуемая цена продажи ---
    public boolean showSellPrice = true;
    public double sellUndercutPercent = 3.0;
    public double sellMinPercentOfAvg = 70.0;
    public double sellMaxPercentOfAvg = 100.0;
    public int sellMinSamples = 3;
    public int sellRoundSignificantDigits = 3;

    // --- Выгодные лоты и тренд ---
    public boolean showDealInfo = true;
    /** Лот «выгодный», если дешевле средней минимум на N%. */
    public double dealThresholdPercent = 15.0;
    /** Для /ahprice deals: учитывать лоты, виденные за последние N минут. */
    public int dealsWindowMinutes = 30;
    public int dealsMaxResults = 10;
    public boolean showTrend = true;
    public int trendWindowHours = 24;

    public boolean debug = false;

    // ------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static AhConfig loadOrCreate(Path file) {
        AhConfig cfg = null;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AhConfig.class);
            }
        } catch (Exception e) {
            PolitcoreAh.LOG.error("Не удалось прочитать config.json, использую значения по умолчанию", e);
        }
        if (cfg == null) cfg = new AhConfig();

        if (cfg.configVersion < 2) {                          // v2: старые фильтры были слишком жадными
            AhConfig d = new AhConfig();
            cfg.ignoredItems = d.ignoredItems;
            cfg.ignoredNamePattern = d.ignoredNamePattern;
        }
        if (cfg.configVersion < CURRENT_VERSION) {
            cfg.configVersion = CURRENT_VERSION;              // новые поля добавятся со значениями по умолчанию
            PolitcoreAh.LOG.info("config.json обновлён до версии {}", CURRENT_VERSION);
        }
        try {
            Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (Exception e) {
            PolitcoreAh.LOG.error("Не удалось записать config.json", e);
        }
        return cfg;
    }
}
