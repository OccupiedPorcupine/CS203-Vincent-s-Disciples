package app.closure;

import jakarta.persistence.*;

import java.time.Instant;

// A hawker centre on NEA's list (see V31__hawker_centre_closures.sql).
@Entity
@Table(name = "hawker_centres")
public class HawkerCentre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200, unique = true)
    private String name;

    @Column(length = 300)
    private String address;

    // When NEA's list last included this centre.
    @Column(nullable = false)
    private Instant syncedAt;

    protected HawkerCentre() {
    }

    public HawkerCentre(String name, String address, Instant syncedAt) {
        this.name = name;
        this.address = address;
        this.syncedAt = syncedAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public void synced(String address, Instant syncedAt) {
        this.address = address;
        this.syncedAt = syncedAt;
    }
}
