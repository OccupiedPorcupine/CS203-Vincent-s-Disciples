package app.price;

import app.dto.RevenueResponse;
import app.dto.WasteResponse;
import app.dto.WeeklySummaryResponse;
import app.dto.WeeklySummaryResponse.DishCount;
import app.sale.DailySaleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// A short weekly summary to read or hear (issue #56): portions against the week before, revenue,
// the best seller, leftovers and sell-outs. The figures come from RevenueService, so they match the
// dashboard, and the sentences are filled in here rather than written by a language model.
@Service
public class WeeklySummaryService {

    private static final DateTimeFormatter ENGLISH_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter CHINESE_DAY = DateTimeFormatter.ofPattern("M月d日EEEE", Locale.SIMPLIFIED_CHINESE);

    private final DailySaleRepository saleRepository;
    private final RevenueService revenueService;

    public WeeklySummaryService(DailySaleRepository saleRepository, RevenueService revenueService) {
        this.saleRepository = saleRepository;
        this.revenueService = revenueService;
    }

    // The 7 days ending on the latest recorded sale, as on the dashboard.
    @Transactional(readOnly = true)
    public WeeklySummaryResponse weekly(Long ownerId) {
        Optional<LocalDate> latest = saleRepository.findFirstByDishOwnerIdOrderBySaleDateDesc(ownerId)
                .map(sale -> sale.getSaleDate());
        if (latest.isEmpty()) {
            return new WeeklySummaryResponse(null, null, 0, 0, null, null, null, false, 0, null, null,
                    new WeeklySummaryResponse.Text("No sales are recorded yet, so there is no week to sum up.",
                            "还没有销售记录，所以没有每周总结。"));
        }

        LocalDate to = latest.get();
        LocalDate from = to.minusDays(6);
        RevenueResponse week = revenueService.summary(ownerId, from, to);
        RevenueResponse weekBefore = revenueService.summary(ownerId, from.minusDays(7), from.minusDays(1));
        WasteResponse waste = revenueService.waste(ownerId, from, to);

        Integer changePercent = weekBefore.portions() == 0 ? null
                : BigDecimal.valueOf(week.portions() - weekBefore.portions()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(weekBefore.portions()), 0, RoundingMode.HALF_UP).intValue();
        DishCount topDish = week.dishes().stream()
                .filter(dish -> dish.portions() > 0)
                .min(Comparator.comparing(RevenueResponse.DishRevenue::portions, Comparator.reverseOrder())
                        .thenComparing(RevenueResponse.DishRevenue::name))
                .map(dish -> new DishCount(dish.name(), dish.portions()))
                .orElse(null);
        DishCount mostSoldOut = waste.dishes().stream()
                .filter(dish -> dish.soldOutDays() > 0)
                .min(Comparator.comparing(WasteResponse.DishWaste::soldOutDays, Comparator.reverseOrder())
                        .thenComparing(WasteResponse.DishWaste::name))
                .map(dish -> new DishCount(dish.name(), dish.soldOutDays()))
                .orElse(null);
        boolean wasteRecorded = waste.daysRecorded() > 0;
        // Only said aloud when every leftover dish has a cost; otherwise it would sound like the whole amount.
        BigDecimal spokenLeftoverCost = waste.leftoversWithoutCost() == 0 ? waste.leftoverCost() : null;

        Facts facts = new Facts(to, week.portions(), changePercent, week.revenue(), topDish, wasteRecorded,
                waste.leftoverPortions(), spokenLeftoverCost, mostSoldOut);
        return new WeeklySummaryResponse(from, to, week.portions(), weekBefore.portions(), changePercent, week.revenue(),
                topDish, wasteRecorded, waste.leftoverPortions(), waste.leftoverCost(), mostSoldOut,
                new WeeklySummaryResponse.Text(english(facts), chinese(facts)));
    }

    private record Facts(LocalDate to, int portions, Integer changePercent, BigDecimal revenue, DishCount topDish,
                         boolean wasteRecorded, int leftoverPortions, BigDecimal leftoverCost, DishCount mostSoldOut) {}

    private static String english(Facts facts) {
        List<String> sentences = new ArrayList<>();
        String change = facts.changePercent() == null ? ""
                : facts.changePercent() > 0 ? ", up " + facts.changePercent() + "% on the week before"
                : facts.changePercent() < 0 ? ", down " + -facts.changePercent() + "% on the week before"
                : ", the same as the week before";
        sentences.add("In the 7 days to " + ENGLISH_DAY.format(facts.to()) + ", you sold "
                + count(facts.portions(), "portion") + change + ".");
        if (facts.revenue() != null) sentences.add("Revenue was " + dollars(facts.revenue()) + ".");
        if (facts.topDish() != null) {
            sentences.add(facts.topDish().name() + " sold the most, " + count(facts.topDish().count(), "portion") + ".");
        }
        if (facts.wasteRecorded()) {
            if (facts.leftoverPortions() == 0) sentences.add("Nothing was left over.");
            else sentences.add("You had " + count(facts.leftoverPortions(), "portion") + " left over"
                    + (facts.leftoverCost() == null ? "." : ", about " + cents(facts.leftoverCost()) + " of food."));
        }
        if (facts.mostSoldOut() != null) {
            sentences.add(facts.mostSoldOut().name() + " sold out on " + count(facts.mostSoldOut().count(), "day") + ".");
        }
        return String.join(" ", sentences);
    }

    private static String chinese(Facts facts) {
        StringBuilder text = new StringBuilder();
        String change = facts.changePercent() == null ? ""
                : facts.changePercent() > 0 ? "，比前一周多" + facts.changePercent() + "%"
                : facts.changePercent() < 0 ? "，比前一周少" + -facts.changePercent() + "%"
                : "，和前一周一样";
        text.append("截至").append(CHINESE_DAY.format(facts.to())).append("的7天，你卖了")
                .append(number(facts.portions())).append("份").append(change).append("。");
        if (facts.revenue() != null) text.append("营业额是").append(number(wholeDollars(facts.revenue()))).append("元。");
        if (facts.topDish() != null) {
            text.append("卖得最多的是").append(facts.topDish().name()).append("，").append(number(facts.topDish().count())).append("份。");
        }
        if (facts.wasteRecorded()) {
            if (facts.leftoverPortions() == 0) text.append("没有剩下的。");
            else text.append("剩下").append(number(facts.leftoverPortions())).append("份")
                    .append(facts.leftoverCost() == null ? "。" : "，大约" + plainCents(facts.leftoverCost()) + "元的食物。");
        }
        if (facts.mostSoldOut() != null) {
            text.append(facts.mostSoldOut().name()).append("有").append(facts.mostSoldOut().count()).append("天卖完。");
        }
        return text.toString();
    }

    private static String count(int count, String unit) {
        return number(count) + " " + unit + (count == 1 ? "" : "s");
    }

    private static String number(long value) {
        return NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value);
    }

    // Whole dollars, as the dashboard shows revenue.
    private static long wholeDollars(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String dollars(BigDecimal amount) {
        return "$" + number(wholeDollars(amount));
    }

    // Cents as the dashboard shows leftovers, but "$40" rather than "$40.00", which sounds odd aloud.
    private static String cents(BigDecimal amount) {
        return "$" + plainCents(amount);
    }

    private static String plainCents(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        if (rounded.stripTrailingZeros().scale() <= 0) return number(rounded.longValue());
        NumberFormat format = NumberFormat.getNumberInstance(Locale.ENGLISH);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(rounded);
    }
}
