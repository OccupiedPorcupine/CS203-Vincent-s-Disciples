package app.closure;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.LocalTime;

// When the stall trades on an open day. One row per owner.
@Entity
@Table(
        name = "trading_hours",
        uniqueConstraints = @UniqueConstraint(
                name = "trading_hours_owner_unique",
                columnNames = "owner_id"
        )
)
public class TradingHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false)
    private LocalTime opensAt;

    @Column(nullable = false)
    private LocalTime closesAt;

    protected TradingHours() {
    }

    public TradingHours(AppUser owner, Hours hours) {
        this.owner = owner;
        set(hours);
    }

    public Hours getHours() {
        return new Hours(opensAt, closesAt);
    }

    public void set(Hours hours) {
        this.opensAt = hours.opensAt();
        this.closesAt = hours.closesAt();
    }
}
