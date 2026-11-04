package app.service;

import app.closure.HawkerCentre;
import app.closure.HawkerCentreClosure;
import app.closure.HawkerCentreClosure.Kind;
import app.closure.HawkerCentreClosureRepository;
import app.closure.HawkerCentreRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// Stores NEA's hawker centres and the days each closes, from data.gov.sg's "Dates of Hawker Centres
// Closure". Each row is one centre with its four quarterly cleanings and any other works; dates are
// written d/M/yyyy, and NEA writes "NA" or "TBC" when there is no date (yet).
@Service
public class HawkerCentreSyncService {

    private static final Logger log = LoggerFactory.getLogger(HawkerCentreSyncService.class);

    static final String RESOURCE_ID = "d_bda4baa634dd1cc7a6c7cad5f19e2d68";
    // Strict, so an impossible date like 31/2 is rejected rather than moved to 28/2.
    private static final DateTimeFormatter NEA_DATE = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private final HawkerCentreRepository centreRepository;
    private final HawkerCentreClosureRepository closureRepository;
    private final RestClient restClient;
    private final Clock clock;

    @Autowired
    public HawkerCentreSyncService(HawkerCentreRepository centreRepository, HawkerCentreClosureRepository closureRepository,
                                   Clock clock) {
        this(centreRepository, closureRepository, RestClient.builder().baseUrl("https://data.gov.sg").build(), clock);
    }

    HawkerCentreSyncService(HawkerCentreRepository centreRepository, HawkerCentreClosureRepository closureRepository,
                            RestClient restClient, Clock clock) {
        this.centreRepository = centreRepository;
        this.closureRepository = closureRepository;
        this.restClient = restClient;
        this.clock = clock;
    }

    public record SyncResult(int centres, int closures) {}

    // When the centres were last stored, so a restart doesn't fetch them again within a day.
    public Instant lastSyncedAt() {
        return centreRepository.lastSyncedAt().orElse(Instant.EPOCH);
    }

    // Replaces each centre's closures with NEA's current list. Centres are never deleted, because
    // stalls may be linked to them; one NEA drops just stops getting new closures.
    @Transactional
    public SyncResult syncCentres() {
        ApiResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/action/datastore_search")
                        .queryParam("resource_id", RESOURCE_ID)
                        .queryParam("limit", 1000)
                        .build())
                .retrieve()
                .body(ApiResponse.class);

        if (response == null || response.result() == null || response.result().records() == null
                || response.result().records().isEmpty()) {
            throw new IllegalStateException("Hawker centre closure data unavailable");
        }

        Instant now = clock.instant();
        Map<String, HawkerCentre> existing = centreRepository.findAll().stream()
                .collect(Collectors.toMap(HawkerCentre::getName, Function.identity()));
        int centres = 0;
        int closures = 0;

        for (CentreRecord record : response.result().records()) {
            String name = text(record.name());
            if (name == null) continue;

            HawkerCentre centre = existing.get(name);
            if (centre == null) {
                centre = centreRepository.save(new HawkerCentre(name, truncate(text(record.address()), 300), now));
            } else {
                centre.synced(truncate(text(record.address()), 300), now);
            }
            closureRepository.deleteAll(closureRepository.findByCentreId(centre.getId()));

            List<HawkerCentreClosure> parsed = closures(centre, record);
            closureRepository.saveAll(parsed);
            centres++;
            closures += parsed.size();
        }
        log.info("Hawker centre sync: {} centres, {} closures", centres, closures);
        return new SyncResult(centres, closures);
    }

    private static List<HawkerCentreClosure> closures(HawkerCentre centre, CentreRecord record) {
        List<HawkerCentreClosure> closures = new ArrayList<>();
        add(closures, centre, Kind.CLEANING, record.q1Start(), record.q1End(), record.q1Remarks());
        add(closures, centre, Kind.CLEANING, record.q2Start(), record.q2End(), record.q2Remarks());
        add(closures, centre, Kind.CLEANING, record.q3Start(), record.q3End(), record.q3Remarks());
        add(closures, centre, Kind.CLEANING, record.q4Start(), record.q4End(), record.q4Remarks());
        add(closures, centre, Kind.WORKS, record.worksStart(), record.worksEnd(), record.worksRemarks());
        return closures;
    }

    private static void add(List<HawkerCentreClosure> closures, HawkerCentre centre, Kind kind,
                            String start, String end, String remarks) {
        LocalDate startDate = date(start);
        LocalDate endDate = date(end);
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) return;
        closures.add(new HawkerCentreClosure(centre, startDate, endDate, kind, truncate(remark(remarks), 300)));
    }

    // Null for "NA", "TBC", blanks and anything else that isn't a d/M/yyyy date.
    static LocalDate date(String value) {
        String text = text(value);
        if (text == null) return null;
        try {
            return LocalDate.parse(text, NEA_DATE);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String remark(String value) {
        String text = text(value);
        return text == null || text.equalsIgnoreCase("nil") || text.equalsIgnoreCase("NA") ? null : text;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip().replaceAll("\\s+", " ");
    }

    private static String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResponse(Result result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(List<CentreRecord> records) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CentreRecord(
            @JsonProperty("name") String name,
            @JsonProperty("address_myenv") String address,
            @JsonProperty("q1_cleaningstartdate") String q1Start,
            @JsonProperty("q1_cleaningenddate") String q1End,
            @JsonProperty("remarks_q1") String q1Remarks,
            @JsonProperty("q2_cleaningstartdate") String q2Start,
            @JsonProperty("q2_cleaningenddate") String q2End,
            @JsonProperty("remarks_q2") String q2Remarks,
            @JsonProperty("q3_cleaningstartdate") String q3Start,
            @JsonProperty("q3_cleaningenddate") String q3End,
            @JsonProperty("remarks_q3") String q3Remarks,
            @JsonProperty("q4_cleaningstartdate") String q4Start,
            @JsonProperty("q4_cleaningenddate") String q4End,
            @JsonProperty("remarks_q4") String q4Remarks,
            @JsonProperty("other_works_startdate") String worksStart,
            @JsonProperty("other_works_enddate") String worksEnd,
            @JsonProperty("remarks_other_works") String worksRemarks
    ) {}
}
