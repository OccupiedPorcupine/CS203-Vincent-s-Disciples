package app.sale;

import app.dish.Dish;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "daily_sales",
        uniqueConstraints = {
                @UniqueConstraint(
                        columnNames = {
                                "dish_id",
                                "sale_date"
                        }
                )
        }
)
public class DailySale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false)
    private Dish dish;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(name = "quantity_sold", nullable = false)
    private int quantitySold;

    // When the dish ran out; null if it didn't, or it wasn't recorded.
    @Column(name = "sold_out_at")
    private LocalTime soldOutAt;

    // Portions prepared but not sold; null if not recorded.
    @Column(name = "leftover_quantity")
    private Integer leftoverQuantity;

    protected DailySale() {
    }

    public DailySale(
            Dish dish,
            LocalDate saleDate,
            int quantitySold
    ) {
        this.dish = dish;
        this.saleDate = saleDate;
        this.quantitySold = quantitySold;
    }

    public Long getId() {
        return id;
    }

    public Dish getDish() {
        return dish;
    }

    public LocalDate getSaleDate() {
        return saleDate;
    }

    public int getQuantitySold() {
        return quantitySold;
    }

    public void setQuantitySold(int quantitySold) {
        this.quantitySold = quantitySold;
    }

    public LocalTime getSoldOutAt() {
        return soldOutAt;
    }

    public Integer getLeftoverQuantity() {
        return leftoverQuantity;
    }

    public void setOutcome(LocalTime soldOutAt, Integer leftoverQuantity) {
        this.soldOutAt = soldOutAt;
        this.leftoverQuantity = leftoverQuantity;
    }
}
