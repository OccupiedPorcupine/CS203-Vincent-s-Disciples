package app.recipe;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Locale;

// Something a dish is made from, in one unit so amounts across dishes add up. See V17__recipes.sql.
@Entity
@Table(
        name = "ingredients",
        uniqueConstraints = @UniqueConstraint(
                name = "ingredients_owner_name_unique",
                columnNames = {"owner_id", "name_key"}
        )
)
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, length = 60)
    private String name;

    // The name compared ignoring case and spacing, so "Chicken" and " chicken " are one ingredient.
    @Column(nullable = false, length = 60)
    private String nameKey;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(nullable = false)
    private Instant createdAt;

    protected Ingredient() {
    }

    public Ingredient(AppUser owner, String name, String unit, Instant createdAt) {
        this.owner = owner;
        this.name = name;
        this.nameKey = key(name);
        this.unit = unit;
        this.createdAt = createdAt;
    }

    public static String key(String name) {
        return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}
