package app.recipe;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngredientDefaultsTest {

    @Test
    void meatGetsUsdasYieldForHowItIsCooked() {
        assertEquals("75 USDA cooking yield for whole chicken with skin, simmered", usda("Chicken", "g"));
        assertEquals("78 USDA cooking yield for whole chicken with skin, roasted", usda("Roast chicken", "kg"));
        assertEquals("69 USDA cooking yield for chicken thigh with skin, roasted", usda("Roasted chicken thigh", "g"));
        assertEquals("68 USDA cooking yield for chicken thigh with skin, braised", usda("braised chicken thighs", "g"));
        // USDA has no simmered drumstick, so the roasted one.
        assertEquals("76 USDA cooking yield for chicken drumstick with skin, roasted", usda("Drumsticks", "g"));
        assertEquals("75 USDA cooking yield for pork blade roast, roasted", usda("Char siew", "g"));
        assertEquals("67 USDA cooking yield for ground pork, medium fat, pan-browned", usda("Minced pork", "g"));
    }

    @Test
    void onlyTheMeatItselfByWeight() {
        assertEquals("none", usda("Chicken stock", "g"));
        assertEquals("none", usda("Char siew sauce", "g"));
        assertEquals("none", usda("Fish balls", "g"));
        assertEquals("none", usda("Chicken", "pcs"));
        assertEquals("none", usda("Cucumber", "g"));
    }

    @Test
    void staplesKeepAndFreshFoodIsPerishable() {
        assertFalse(IngredientDefaults.perishable("Rice"));
        assertFalse(IngredientDefaults.perishable("Dark soy sauce"));
        assertFalse(IngredientDefaults.perishable("Bee hoon"));
        assertTrue(IngredientDefaults.perishable("Chicken"));
        assertTrue(IngredientDefaults.perishable("Kway teow"));
        assertTrue(IngredientDefaults.perishable("Cucumber"));
    }

    private static String usda(String name, String unit) {
        Optional<IngredientDefaults.Yield> found = IngredientDefaults.yield(name, unit);
        return found.map(yield -> yield.percent().stripTrailingZeros().toPlainString() + " " + yield.basis()).orElse("none");
    }

}
