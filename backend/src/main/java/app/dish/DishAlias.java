package app.dish;

import app.user.AppUser;
import jakarta.persistence.*;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(
        name = "dish_aliases",
        uniqueConstraints = @UniqueConstraint(
                name = "dish_aliases_owner_alias_unique",
                columnNames = {"owner_id", "normalized_alias"}
        )
)
public class DishAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false)
    private Dish dish;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, length = 100)
    private String alias;

    @Column(nullable = false, length = 100)
    private String normalizedAlias;

    @Column(nullable = false)
    private Instant createdAt;

    protected DishAlias() {
    }

    public DishAlias(Dish dish, String alias) {
        this.dish = dish;
        this.owner = dish.getOwner();
        this.alias = alias.strip();
        this.normalizedAlias = normalize(alias);
        this.createdAt = Instant.now();
    }

    // "  Chicken  RICE " and "chicken rice" are the same name; NFKC also folds full-width
    // characters (e.g. from Chinese keyboards) into their ordinary forms.
    public static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public Dish getDish() {
        return dish;
    }

    public String getAlias() {
        return alias;
    }

    public String getNormalizedAlias() {
        return normalizedAlias;
    }
}
