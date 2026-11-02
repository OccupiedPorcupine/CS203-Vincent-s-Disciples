package app.price;

import app.dish.Dish;
import app.user.AppUser;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

// A dish's price and unit cost from effectiveFrom onward. See V11__dish_prices.sql.
@Entity
@Table(
        name = "dish_prices",
        uniqueConstraints = @UniqueConstraint(
                name = "dish_prices_dish_date_unique",
                columnNames = {"dish_id", "effective_from"}
        )
)
public class DishPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false)
    private Dish dish;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(precision = 10, scale = 2)
    private BigDecimal unitCost;

    @Column(nullable = false)
    private Instant createdAt;

    protected DishPrice() {
    }

    public DishPrice(Dish dish, LocalDate effectiveFrom, BigDecimal price, BigDecimal unitCost, Instant createdAt) {
        this.dish = dish;
        this.owner = dish.getOwner();
        this.effectiveFrom = effectiveFrom;
        this.price = price;
        this.unitCost = unitCost;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Dish getDish() {
        return dish;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public void change(BigDecimal price, BigDecimal unitCost) {
        this.price = price;
        this.unitCost = unitCost;
    }
}
