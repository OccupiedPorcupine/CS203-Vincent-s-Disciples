package com.csd.salesforecast.domain;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "data_sources")
public class DataSourceEntity {
    @Id public UUID id;
    @Column(name="original_file_name", nullable=false) public String originalFileName;
    @Column(name="stored_path", nullable=false, length=1024) public String storedPath;
    @Column(nullable=false, unique=true, length=64) public String sha256;
    @Column(nullable=false, length=32) public String status;
    @Column(nullable=false) public boolean included;
    @Column(name="date_start") public LocalDate dateStart;
    @Column(name="date_end") public LocalDate dateEnd;
    @Column(name="row_count", nullable=false) public int rowCount;
    @Column(name="sheet_names", length=1000) public String sheetNames;
    @Column(name="validation_message", length=2000) public String validationMessage;
    @Column(name="created_at", nullable=false) public OffsetDateTime createdAt;

    public DataSourceEntity() {}
}
