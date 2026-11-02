package app.recipe;

import app.dish.Dish;
import app.user.AppUser;
import jakarta.persistence.*;

import java.math.BigDecimal;

// How much of an ingredient one portion of a dish uses, in the ingredient's unit.
@Entity
@Table(
        name = "dish_ingredients",
        uniqueConstraints = @UniqueConstraint(
                name = "dish_ingredients_dish_ingredient_unique",
                columnNames = {"dish_id", "ingredient_id"}
        )
)
public class DishIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false)
    private Dish dish;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    protected DishIngredient() {
    }

    public DishIngredient(Dish dish, Ingredient ingredient, BigDecimal quantity) {
        this.dish = dish;
        this.ingredient = ingredient;
        this.owner = dish.getOwner();
        this.quantity = quantity;
    }

    public Dish getDish() {
        return dish;
    }

    public Ingredient getIngredient() {
        return ingredient;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
}
