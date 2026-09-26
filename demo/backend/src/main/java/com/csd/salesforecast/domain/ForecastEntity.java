package com.csd.salesforecast.domain;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "forecasts")
public class ForecastEntity {
    @Id public UUID id;
    @Column(name="target_date", nullable=false) public LocalDate targetDate;
    @Column(nullable=false, length=32) public String weather;
    @Column(nullable=false) public boolean holiday;
    @Column(name="stall_open", nullable=false) public boolean stallOpen;
    @Column(name="predicted_revenue", nullable=false) public double predictedRevenue;
    @Column(name="predicted_orders", nullable=false) public int predictedOrders;
    @Column(name="lower_bound", nullable=false) public double lowerBound;
    @Column(name="upper_bound", nullable=false) public double upperBound;
    @Column(name="model_version_id") public UUID modelVersionId;
    @Column(name="created_at", nullable=false) public OffsetDateTime createdAt;

    public ForecastEntity() {}
}
