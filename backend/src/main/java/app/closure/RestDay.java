package app.closure;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.DayOfWeek;

// A day of the week the stall is always closed, e.g. a Tuesday rest day.
@Entity
@Table(
        name = "rest_days",
        uniqueConstraints = @UniqueConstraint(
                name = "rest_days_owner_day_unique",
                columnNames = {"owner_id", "day_of_week"}
        )
)
public class RestDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    // ISO-8601: 1 = Monday ... 7 = Sunday.
    @Column(nullable = false)
    private int dayOfWeek;

    protected RestDay() {
    }

    public RestDay(AppUser owner, DayOfWeek day) {
        this.owner = owner;
        this.dayOfWeek = day.getValue();
    }

    public DayOfWeek getDay() {
        return DayOfWeek.of(dayOfWeek);
    }
}
