package app.closure;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

// A single day the stall is closed, e.g. for hawker centre cleaning or a holiday.
@Entity
@Table(
        name = "closed_dates",
        uniqueConstraints = @UniqueConstraint(
                name = "closed_dates_owner_date_unique",
                columnNames = {"owner_id", "closed_date"}
        )
)
public class ClosedDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false)
    private LocalDate closedDate;

    @Column(length = 100)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected ClosedDate() {
    }

    public ClosedDate(AppUser owner, LocalDate closedDate, String reason) {
        this.owner = owner;
        this.closedDate = closedDate;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public LocalDate getClosedDate() {
        return closedDate;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
