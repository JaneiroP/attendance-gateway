package com.ohmdevs.attendance.ingest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.ohmdevs.attendance.device.AuthenticatedDevice;
import com.ohmdevs.attendance.ingest.PunchClassifier.Accepted;
import com.ohmdevs.attendance.ingest.PunchClassifier.Outcome;
import com.ohmdevs.attendance.ingest.PunchClassifier.Rejected;
import com.ohmdevs.attendance.ingest.PunchStore.AcceptedPunch;
import com.ohmdevs.attendance.ingest.PunchStore.PendingQuarantine;
import com.ohmdevs.attendance.ingest.PunchStore.RejectedPunch;
import com.ohmdevs.attendance.mapping.EmployeeMappings;

@Service
public class PunchIngestService {

    private static final int REPROCESS_CHUNK = 1000;

    /**
     * Every transaction inserts its punches in this order. Two overlapping
     * batches then take their row locks in the same sequence, so they wait for
     * each other instead of deadlocking.
     */
    private static final Comparator<AcceptedPunch> LOCK_ORDER = Comparator
            .comparing(AcceptedPunch::deviceId)
            .thenComparing(AcceptedPunch::userCode)
            .thenComparing(AcceptedPunch::punchedAtLocal)
            .thenComparing(AcceptedPunch::type);

    private final PunchStore store;
    private final EmployeeMappings mappings;
    private final IngestProperties properties;
    private final Clock clock;

    PunchIngestService(PunchStore store, EmployeeMappings mappings, IngestProperties properties, Clock clock) {
        this.store = store;
        this.mappings = mappings;
        this.properties = properties;
        this.clock = clock;
    }

    public record RawPunch(String userCode, LocalDateTime punchedAt, PunchType type) {
    }

    /**
     * @param received    punches in the request
     * @param accepted    stored for the first time
     * @param duplicates  valid, but already stored by an earlier request
     * @param quarantined set aside with a reason instead of being stored
     */
    public record IngestResult(int received, int accepted, int duplicates, int quarantined) {
    }

    public record ReprocessResult(int examined, int resolved) {
    }

    /**
     * Stores a batch from one device. Sending the same batch again changes
     * nothing and reports its punches as duplicates, which is what lets a clock
     * retry blindly after a timeout.
     */
    @Transactional
    public IngestResult ingest(AuthenticatedDevice device, List<RawPunch> punches) {
        if (punches.size() > properties.maxBatchSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A batch may carry at most " + properties.maxBatchSize() + " punches");
        }
        Instant now = clock.instant();
        Set<String> userCodes = punches.stream().map(RawPunch::userCode).collect(Collectors.toSet());
        Map<String, String> employeeByCode = mappings.resolve(device.siteCode(), userCodes);

        List<AcceptedPunch> accepted = new ArrayList<>();
        List<RejectedPunch> rejected = new ArrayList<>();
        for (RawPunch punch : punches) {
            Outcome outcome = PunchClassifier.classify(punch.punchedAt(), device.zone(),
                    employeeByCode.get(punch.userCode()), now, properties.futureTolerance());
            switch (outcome) {
                case Accepted ok -> accepted.add(new AcceptedPunch(device.id(), device.siteCode(), punch.userCode(),
                        ok.employeeId(), punch.punchedAt(), ok.punchedAtUtc(), punch.type()));
                case Rejected no -> rejected.add(new RejectedPunch(device.id(), device.siteCode(), punch.userCode(),
                        punch.punchedAt(), punch.type(), no.reason()));
            }
        }
        accepted.sort(LOCK_ORDER);

        int inserted = store.insertPunches(accepted, now);
        store.insertQuarantined(rejected, now);
        store.markDeviceSeen(device.id(), now);
        return new IngestResult(punches.size(), inserted, accepted.size() - inserted, rejected.size());
    }

    /**
     * Gives quarantined punches another chance, typically after an employee
     * mapping was added. Entries that still fail stay pending.
     */
    @Transactional
    public ReprocessResult reprocess() {
        Instant now = clock.instant();
        List<PendingQuarantine> pending = store.lockPending(REPROCESS_CHUNK);

        Map<String, Set<String>> codesBySite = pending.stream().collect(Collectors.groupingBy(
                PendingQuarantine::siteCode, Collectors.mapping(PendingQuarantine::userCode, Collectors.toSet())));
        Map<String, Map<String, String>> employeeBySiteAndCode = codesBySite.entrySet().stream().collect(
                Collectors.toMap(Map.Entry::getKey, entry -> mappings.resolve(entry.getKey(), entry.getValue())));

        List<AcceptedPunch> accepted = new ArrayList<>();
        List<UUID> resolved = new ArrayList<>();
        for (PendingQuarantine entry : pending) {
            String employeeId = employeeBySiteAndCode.get(entry.siteCode()).get(entry.userCode());
            Outcome outcome = PunchClassifier.classify(entry.punchedAtLocal(), entry.zone(), employeeId,
                    now, properties.futureTolerance());
            if (outcome instanceof Accepted ok) {
                accepted.add(new AcceptedPunch(entry.deviceId(), entry.siteCode(), entry.userCode(),
                        ok.employeeId(), entry.punchedAtLocal(), ok.punchedAtUtc(), entry.type()));
                resolved.add(entry.id());
            }
        }
        accepted.sort(LOCK_ORDER);

        store.insertPunches(accepted, now);
        store.markResolved(resolved, now);
        return new ReprocessResult(pending.size(), resolved.size());
    }
}
