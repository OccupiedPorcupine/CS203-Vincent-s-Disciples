package app.service;

import app.holiday.PublicHoliday;
import app.holiday.PublicHolidayRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PublicHolidaySyncService {

    private static final String RESOURCE_ID = "d_8ef23381f9417e4d4254ee8b4dcdb176";

    private final PublicHolidayRepository repository;
    private final RestClient restClient;

    public PublicHolidaySyncService(PublicHolidayRepository repository) {
        this.repository = repository;
        this.restClient = RestClient.builder().baseUrl("https://data.gov.sg").build();
    }

    public void syncHolidays() {
        ApiResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/action/datastore_search")
                        .queryParam("resource_id", RESOURCE_ID)
                        .queryParam("limit", 500)
                        .build())
                .retrieve()
                .body(ApiResponse.class);

        if (response == null || response.result() == null || response.result().records() == null) {
            throw new IllegalStateException("Public holiday data unavailable");
        }

        int currentYear = LocalDate.now().getYear();

        Map<LocalDate, PublicHoliday> existing = repository.findAll().stream()
                .collect(Collectors.toMap(PublicHoliday::getDate, holiday -> holiday));

        List<PublicHoliday> holidays = response.result().records().stream()
                .map(record -> new HolidayData(LocalDate.parse(record.date()), record.holiday()))
                .filter(record -> record.date().getYear() >= currentYear)
                .map(record -> {
                    PublicHoliday holiday = existing.get(record.date());

                    if (holiday == null) {
                        holiday = new PublicHoliday(record.date(), record.name());
                    }

                    holiday.setName(record.name());
                    return holiday;
                })
                .toList();

        repository.saveAll(holidays);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ApiResponse(Result result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Result(List<HolidayRecord> records) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HolidayRecord(String date, String day, String holiday) {}

    private record HolidayData(LocalDate date, String name) {}
}