package app.closure;

import app.user.AppUser;
import jakarta.persistence.*;

// The hawker centre a stall is in, whose NEA closures count as the stall's closed days.
@Entity
@Table(
        name = "stall_hawker_centres",
        uniqueConstraints = @UniqueConstraint(name = "stall_hawker_centres_owner_unique", columnNames = "owner_id")
)
public class StallHawkerCentre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hawker_centre_id", nullable = false)
    private HawkerCentre centre;

    protected StallHawkerCentre() {
    }

    public StallHawkerCentre(AppUser owner, HawkerCentre centre) {
        this.owner = owner;
        this.centre = centre;
    }

    public HawkerCentre getCentre() {
        return centre;
    }

    public void setCentre(HawkerCentre centre) {
        this.centre = centre;
    }
}
