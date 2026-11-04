package app.closure;

import jakarta.persistence.*;

import java.time.LocalDate;

// Days NEA closes a hawker centre, from start to end inclusive.
@Entity
@Table(name = "hawker_centre_closures")
public class HawkerCentreClosure {

    public enum Kind { CLEANING, WORKS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hawker_centre_id", nullable = false)
    private HawkerCentre centre;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(length = 300)
    private String remarks;

    protected HawkerCentreClosure() {
    }

    public HawkerCentreClosure(HawkerCentre centre, LocalDate startDate, LocalDate endDate, Kind kind, String remarks) {
        this.centre = centre;
        this.startDate = startDate;
        this.endDate = endDate;
        this.kind = kind;
        this.remarks = remarks;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Kind getKind() {
        return kind;
    }

    public String getRemarks() {
        return remarks;
    }
}
