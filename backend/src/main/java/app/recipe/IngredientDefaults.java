package app.recipe;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

// Starting values for an ingredient the owner hasn't set (issue #89), worked out from its name.
//
// Yields are from the USDA Table of Cooking Yields for Meat and Poultry, Release 2 (2014): cooked
// weight ÷ raw weight, bone and skin included. They only cover weight lost in cooking, so a stall
// that cuts the meat off the bone should lower them. A name that says how it's cooked ("roast
// chicken") gets that row; otherwise the simmered row where USDA has one, since poached chicken is
// the usual hawker cut. Ingredients USDA has no row for stay at 100%.
//
// Everything is perishable (bought for each day) unless its name is a dry good, sauce or other
// staple that keeps, which is bought for the week instead.
final class IngredientDefaults {

    static final BigDecimal FULL = BigDecimal.valueOf(100);

    record Yield(BigDecimal percent, String basis) {}

    private enum Method { ROASTED, SIMMERED, BRAISED }

    private record Cut(List<String> names, String usda, Integer roasted, Integer simmered, Integer braised, Integer other,
                       String otherMethod) {
        static Cut cooked(String usda, Integer roasted, Integer simmered, Integer braised, String... names) {
            return new Cut(List.of(names), usda, roasted, simmered, braised, null, null);
        }

        static Cut only(String usda, int percent, String method, String... names) {
            return new Cut(List.of(names), usda, null, null, null, percent, method);
        }
    }

    // Most specific first: "chicken thigh" is a thigh, not a whole chicken.
    private static final List<Cut> CUTS = List.of(
            Cut.only("chicken liver", 62, "pan-fried", "chicken liver", "liver"),
            Cut.only("chicken gizzard", 55, "simmered", "gizzard", "gizzards"),
            Cut.cooked("chicken breast with skin", 72, 77, null, "chicken breast", "breast"),
            Cut.cooked("chicken thigh with skin", 69, 74, 68, "chicken thigh", "chicken thighs", "thigh", "thighs"),
            Cut.cooked("chicken drumstick with skin", 76, null, 77, "drumstick", "drumsticks"),
            Cut.cooked("chicken wing with skin", 78, 86, null, "chicken wing", "chicken wings", "wing", "wings"),
            Cut.cooked("whole chicken with skin", 78, 75, null, "chicken", "whole chicken"),
            Cut.only("pork blade roast", 75, "roasted", "char siew", "char siu", "cha siu"),
            Cut.only("pork spareribs", 76, "roasted", "spare ribs", "spareribs", "pork ribs"),
            Cut.only("pork shoulder (arm picnic)", 74, "braised", "pork shoulder"),
            Cut.only("ground pork, medium fat", 67, "pan-browned", "minced pork", "pork mince", "ground pork"),
            Cut.only("ground beef, medium fat", 67, "pan-fried", "minced beef", "beef mince", "ground beef"),
            Cut.only("beef brisket", 69, "braised", "beef brisket", "brisket")
    );

    // A name with one of these words isn't the meat itself: chicken stock, fish balls, char siew sauce.
    private static final Set<String> NOT_MEAT = Set.of("stock", "broth", "powder", "cube", "cubes", "sauce", "essence",
            "floss", "sausage", "sausages", "ball", "balls", "nugget", "nuggets", "egg", "eggs", "oil", "fat", "skin",
            "bone", "bones", "feet", "paste", "seasoning", "rice", "chilli", "chili");

    private static final Set<String> WEIGHT_UNITS = Set.of("g", "gram", "grams", "kg", "kgs", "kilogram", "kilograms",
            "oz", "lb", "lbs", "catty", "kati", "tael");

    private static final List<String> KEEPS = List.of("rice", "oil", "salt", "sugar", "sauce", "soy", "kicap", "flour",
            "vinegar", "pepper", "msg", "cornstarch", "corn starch", "cornflour", "tapioca", "starch", "bee hoon",
            "beehoon", "vermicelli", "dried", "spice", "spices", "star anise", "cinnamon", "ketchup", "coffee", "tea",
            "condensed milk", "evaporated milk", "sesame", "peanuts", "garlic", "shallots", "fried shallots",
            "belacan", "stock cube", "stock cubes", "seasoning", "powder", "honey", "maltose", "cooking wine",
            "shaoxing", "rock sugar");

    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private IngredientDefaults() {
    }

    // The USDA yield for this name, when it's measured by weight and USDA has a row for it.
    static Optional<Yield> yield(String name, String unit) {
        if (!WEIGHT_UNITS.contains(unit.strip().toLowerCase(Locale.ROOT))) return Optional.empty();
        String words = words(name);
        if (Set.of(words.split(" ")).stream().anyMatch(NOT_MEAT::contains)) return Optional.empty();
        Method method = words.matches(".*\\b(roast|roasted|baked)\\b.*") ? Method.ROASTED
                : words.matches(".*\\b(braised|braise|stewed|lor)\\b.*") ? Method.BRAISED
                : Method.SIMMERED;

        for (Cut cut : CUTS) {
            if (cut.names().stream().noneMatch(phrase -> contains(words, phrase))) continue;
            if (cut.other() != null) {
                return Optional.of(usda(cut.other(), cut.usda() + ", " + cut.otherMethod()));
            }
            // A cut USDA didn't measure that way gets the simmered row, then the roasted one.
            for (Method tried : List.of(method, Method.SIMMERED, Method.ROASTED, Method.BRAISED)) {
                Integer percent = switch (tried) {
                    case ROASTED -> cut.roasted();
                    case BRAISED -> cut.braised();
                    case SIMMERED -> cut.simmered();
                };
                if (percent != null) {
                    return Optional.of(usda(percent, cut.usda() + ", " + tried.name().toLowerCase(Locale.ROOT)));
                }
            }
        }
        return Optional.empty();
    }

    static boolean perishable(String name) {
        String words = words(name);
        return KEEPS.stream().noneMatch(phrase -> contains(words, phrase));
    }

    private static Yield usda(int percent, String basis) {
        return new Yield(BigDecimal.valueOf(percent), "USDA cooking yield for " + basis);
    }

    private static String words(String name) {
        return WORD_SPLIT.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static boolean contains(String words, String phrase) {
        return (" " + words + " ").contains(" " + phrase + " ");
    }
}
