package app.actions;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.Instant;

// A dashboard action card the user dismissed. See V21__dismissed_actions.sql.
@Entity
@Table(
        name = "dismissed_actions",
        uniqueConstraints = @UniqueConstraint(
                name = "dismissed_actions_owner_key_unique",
                columnNames = {"owner_id", "action_key"}
        )
)
public class DismissedAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, length = 100)
    private String actionKey;

    @Column(nullable = false)
    private Instant dismissedAt;

    protected DismissedAction() {
    }

    public DismissedAction(AppUser owner, String actionKey, Instant dismissedAt) {
        this.owner = owner;
        this.actionKey = actionKey;
        this.dismissedAt = dismissedAt;
    }

    public String getActionKey() {
        return actionKey;
    }
}
