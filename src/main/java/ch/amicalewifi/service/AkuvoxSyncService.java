package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AkuvoxSyncService {

    private final AccessEventRepository eventRepo;
    private final MemberRepository      memberRepo;
    private final ObjectMapper          objectMapper;
    private final RestTemplate          restTemplate;

    @Value("${amicale.akuvox.device-url}") private String deviceUrl;
    @Value("${amicale.akuvox.device-user}") private String deviceUser;
    @Value("${amicale.akuvox.device-pass}") private String devicePass;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void syncAccessLog() {
        try {
            String token = login();
            if (token == null) return;

            String today = LocalDate.now().format(DATE_FMT);
            String url = deviceUrl + "/web/accesslog/get?page=1&search=&logstatus=3"
                    + "&starttime=" + today + "&endtime=" + today
                    + "&session=" + token + "&web=1";

            String json = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(json);
            if (root.path("retcode").asInt() != 0) return;

            JsonNode items = root.path("data").path("accesslogList");
            for (JsonNode item : items) {
                int akuvoxId = item.path("id").asInt();
                if (eventRepo.existsByAkuvoxId(akuvoxId)) continue;

                String badge    = item.path("code").asText().toUpperCase();
                String name     = item.path("name").asText();
                boolean granted = item.path("status").asInt() == 0;

                LocalDate date       = LocalDate.parse(item.path("date").asText(), DATE_FMT);
                LocalTime time       = LocalTime.parse(item.path("time").asText(), TIME_FMT);
                LocalDateTime occurredAt = LocalDateTime.of(date, time);

                if (eventRepo.existsByBadgeUidAndOccurredAt(badge, occurredAt)) continue;

                Member member = memberRepo.findByBadgeUid(badge.toUpperCase()).orElse(null);

                eventRepo.save(AccessEvent.builder()
                        .member(member)
                        .badgeUid(badge)
                        .eventType(granted ? AccessEventType.ENTRY_GRANTED : AccessEventType.ENTRY_DENIED)
                        .occurredAt(occurredAt)
                        .akuvoxId(akuvoxId)
                        .deniedReason(granted ? null : "akuvox_denied")
                        .build());

                log.debug("Akuvox sync — {} {} ({})", occurredAt, name, badge);
            }
        } catch (Exception e) {
            log.warn("Akuvox sync failed: {}", e.getMessage());
        }
    }

    private String login() {
        try {
            Map<String, Object> payload = Map.of(
                "action", "login",
                "target", "login",
                "session", "AMICALE01",
                "web", "1",
                "data", Map.of("userName", deviceUser, "password", devicePass)
            );
            String body = objectMapper.writeValueAsString(payload);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            String resp = restTemplate.postForObject(deviceUrl + "/web", entity, String.class);
            JsonNode node = objectMapper.readTree(resp);
            if (node.path("retcode").asInt() != 0) return null;
            return node.path("data").path("token").asText();
        } catch (Exception e) {
            log.warn("Akuvox login failed: {}", e.getMessage());
            return null;
        }
    }
}
