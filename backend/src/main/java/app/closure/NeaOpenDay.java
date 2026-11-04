package app.closure;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.LocalDate;

// A day NEA closes the stall's hawker centre but the owner says the stall opens anyway.
@Entity
@Table(
        name = "nea_open_days",
        uniqueConstraints = @UniqueConstraint(name = "nea_open_days_owner_date_unique", columnNames = {"owner_id", "open_date"})
)
public class NeaOpenDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false)
    private LocalDate openDate;

    protected NeaOpenDay() {
    }

    public NeaOpenDay(AppUser owner, LocalDate openDate) {
        this.owner = owner;
        this.openDate = openDate;
    }

    public LocalDate getOpenDate() {
        return openDate;
    }
}
