package dev.politcore.ah;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Парсер цен. Два этапа:
 *  1) настраиваемые regex из конфига находят «хвост» с ценой / имя продавца в строке лора;
 *  2) parsePriceToken превращает текст вида "1.000.000$", "500,000 монет", "1.5M", "2кк" в число.
 */
public final class PriceRegexParser {

    public record Lot(double price, String seller) {}

    private static final Pattern NUM = Pattern.compile(
            "(\\d[\\d.,\\s\\u00A0\\u202F]*)\\s*(?:(ккк|кк|kkk|kk|млрд|млн|тыс|[kкmмb])(?![\\p{L}]))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final List<Pattern> pricePatterns = new ArrayList<>();
    private final List<Pattern> sellerPatterns = new ArrayList<>();
    private final Pattern ahTitle;
    private final Pattern ahCommand;
    private final Pattern ignoredName;
    private final Pattern ignoredTitle;

    public PriceRegexParser(AhConfig c) {
        AhConfig def = new AhConfig();
        compileAll(c.pricePatterns, pricePatterns);
        if (pricePatterns.isEmpty()) compileAll(def.pricePatterns, pricePatterns);
        compileAll(c.sellerPatterns, sellerPatterns);
        ahTitle = compileOr(c.ahTitlePattern, def.ahTitlePattern);
        ahCommand = compileOr(c.ahCommandPattern, def.ahCommandPattern);
        ignoredName = compileOr(c.ignoredNamePattern, def.ignoredNamePattern);
        ignoredTitle = compileOr(c.ignoredTitlePattern, def.ignoredTitlePattern);
    }

    private static void compileAll(List<String> src, List<Pattern> out) {
        if (src == null) return;
        for (String re : src) {
            try { out.add(Pattern.compile(re)); }
            catch (PatternSyntaxException e) { PolitcoreAh.LOG.error("Неверный regex '{}': {}", re, e.getMessage()); }
        }
    }

    private static Pattern compileOr(String re, String fallback) {
        try { return Pattern.compile(re); }
        catch (Exception e) {
            PolitcoreAh.LOG.error("Неверный regex '{}', использую значение по умолчанию", re);
            return Pattern.compile(fallback);
        }
    }

    public boolean isAhTitle(String title) { return ahTitle.matcher(title).find(); }
    public boolean isAhCommand(String cmd) { return ahCommand.matcher(cmd.trim()).find(); }
    public boolean isIgnoredName(String name) { return ignoredName.matcher(name).find(); }
    public boolean isIgnoredTitle(String title) { return ignoredTitle.matcher(title).find(); }

    /** Ищет цену (обязательно) и продавца (опционально) в строках лора. */
    public Optional<Lot> parse(List<String> lore) {
        Double price = null;
        String seller = null;
        for (String raw : lore) {
            String line = raw.replaceAll("§.", "").trim();
            if (price == null) {
                for (Pattern p : pricePatterns) {
                    Matcher m = p.matcher(line);
                    if (!m.find()) continue;
                    String tok = group(m, "price");
                    if (tok == null) continue;
                    OptionalDouble v = parsePriceToken(tok);
                    if (v.isPresent() && v.getAsDouble() > 0) { price = v.getAsDouble(); break; }
                }
            }
            if (seller == null) {
                for (Pattern p : sellerPatterns) {
                    Matcher m = p.matcher(line);
                    if (m.find()) { seller = group(m, "seller"); if (seller != null) break; }
                }
            }
        }
        return price == null ? Optional.empty() : Optional.of(new Lot(price, seller == null ? "" : seller));
    }

    private static String group(Matcher m, String name) {
        try { return m.group(name); }
        catch (IllegalArgumentException e) { return m.groupCount() > 0 ? m.group(1) : m.group(); }
    }

    /** "1.000.000$" → 1e6; "500,000" → 5e5; "1.5M" → 1.5e6; "2кк" → 2e6; "1 234,56" → 1234.56 */
    public static OptionalDouble parsePriceToken(String s) {
        Matcher m = NUM.matcher(s);
        if (!m.find()) return OptionalDouble.empty();
        String n = m.group(1).replaceAll("[\\s\\u00A0\\u202F]", "").replaceAll("[.,]+$", "");
        if (n.isEmpty()) return OptionalDouble.empty();
        double mult = multiplier(m.group(2));

        boolean dot = n.indexOf('.') >= 0, comma = n.indexOf(',') >= 0;
        if (dot && comma) {
            int last = Math.max(n.lastIndexOf('.'), n.lastIndexOf(','));      // последний разделитель — десятичный
            n = n.substring(0, last).replaceAll("[.,]", "") + "." + n.substring(last + 1);
        } else if (dot || comma) {
            char sep = dot ? '.' : ',';
            int count = (int) n.chars().filter(ch -> ch == sep).count();
            int last = n.lastIndexOf(sep);
            int after = n.length() - last - 1;
            if (count > 1 || (after == 3 && mult == 1.0)) {
                n = n.replace(String.valueOf(sep), "");                         // разделитель тысяч: 1.000 / 500,000
            } else {
                n = n.replace(sep, '.');                                         // десятичный: 1.5M, 12,5
            }
        }
        try {
            return OptionalDouble.of(new BigDecimal(n).multiply(BigDecimal.valueOf(mult)).doubleValue());
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static double multiplier(String suf) {
        if (suf == null) return 1.0;
        return switch (suf.toLowerCase()) {
            case "k", "к", "тыс" -> 1e3;
            case "m", "м", "kk", "кк", "млн" -> 1e6;
            case "b", "kkk", "ккк", "млрд" -> 1e9;
            default -> 1.0;
        };
    }
}
