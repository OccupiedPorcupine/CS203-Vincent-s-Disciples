package com.csd.salesforecast.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "model_versions")
public class ModelVersionEntity {
    @Id public UUID id;
    @Column(nullable=false, length=100) public String name;
    @Column(name="artifact_path", nullable=false, length=1024) public String artifactPath;
    @Column(nullable=false) public boolean active;
    @Column(name="training_run_id", nullable=false) public UUID trainingRunId;
    @Column(name="created_at", nullable=false) public OffsetDateTime createdAt;

    public ModelVersionEntity() {}
}
