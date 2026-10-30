package app.assistant;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.Instant;

// One message to the assistant and its outcome. See V8__assistant_interactions.sql.
@Entity
@Table(name = "assistant_interactions")
public class AssistantInteraction {

    public enum Outcome { NONE, PENDING, CONFIRMED, EDITED, CANCELLED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, length = 64)
    private String conversationId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 64)
    private String page;

    @Column(nullable = false, length = 16)
    private String inputMode;

    @Column(nullable = false, length = 2000)
    private String userText;

    @Column(length = 64)
    private String detectedLanguages;

    @Column(length = 4000)
    private String reply;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(nullable = false)
    private int latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Outcome outcome;

    // SALES or PRICE when there is a proposal; see V16.
    @Column(length = 16)
    private String proposalType;

    @Column(length = 20000)
    private String proposalJson;

    @Column(length = 20000)
    private String finalJson;

    private Instant resolvedAt;

    protected AssistantInteraction() {
    }

    public AssistantInteraction(AppUser owner, String conversationId, String page, String inputMode, String userText,
                                String detectedLanguages, String reply, String model, int latencyMs,
                                String proposalType, String proposalJson, Instant createdAt) {
        this.owner = owner;
        this.conversationId = conversationId;
        this.page = page;
        this.inputMode = inputMode;
        this.userText = userText;
        this.detectedLanguages = detectedLanguages;
        this.reply = reply;
        this.model = model;
        this.latencyMs = latencyMs;
        this.proposalType = proposalType;
        this.proposalJson = proposalJson;
        this.outcome = proposalJson == null ? Outcome.NONE : Outcome.PENDING;
        this.createdAt = createdAt;
    }

    public void resolve(Outcome outcome, String finalJson, Instant resolvedAt) {
        this.outcome = outcome;
        this.finalJson = finalJson;
        this.resolvedAt = resolvedAt;
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    // Proposals saved before V16 have no type and are sales.
    public boolean isPriceProposal() {
        return "PRICE".equals(proposalType);
    }

    public String getProposalJson() {
        return proposalJson;
    }

    public String getFinalJson() {
        return finalJson;
    }

    public String getDetectedLanguages() {
        return detectedLanguages;
    }
}
