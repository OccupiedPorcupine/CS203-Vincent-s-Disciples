package com.csd.salesforecast.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "training_run_sources", uniqueConstraints = @UniqueConstraint(columnNames = {"training_run_id", "data_source_id"}))
public class TrainingRunSourceEntity {
    @Id public UUID id;
    @Column(name="training_run_id", nullable=false) public UUID trainingRunId;
    @Column(name="data_source_id", nullable=false) public UUID dataSourceId;
    @Column(name="source_sha256", nullable=false, length=64) public String sourceSha256;
    @Column(name="created_at", nullable=false) public OffsetDateTime createdAt;

    public TrainingRunSourceEntity() {}
}
