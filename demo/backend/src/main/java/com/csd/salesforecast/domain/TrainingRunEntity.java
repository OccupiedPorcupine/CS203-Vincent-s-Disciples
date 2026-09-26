package com.csd.salesforecast.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "training_runs")
public class TrainingRunEntity {
    @Id public UUID id;
    @Column(nullable=false, length=32) public String status;
    @Column(name="selected_model", length=100) public String selectedModel;
    public Double mae;
    public Double wape;
    @Column(name="source_count", nullable=false) public int sourceCount;
    @Column(length=2000) public String message;
    @Column(name="started_at", nullable=false) public OffsetDateTime startedAt;
    @Column(name="finished_at") public OffsetDateTime finishedAt;

    public TrainingRunEntity() {}
}
