package com.ohmdevs.attendance.mapping;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Translates the enrollment code a clock uses for a person into the employee
 * id the rest of the company uses. Codes are only unique within a site.
 */
@Repository
public class EmployeeMappings {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    EmployeeMappings(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Returns user code to employee id for the codes that have a mapping; unmapped codes are absent. */
    public Map<String, String> resolve(String siteCode, Collection<String> userCodes) {
        Map<String, String> resolved = new HashMap<>();
        if (userCodes.isEmpty()) {
            return resolved;
        }
        jdbc.query("""
                        SELECT user_code, employee_id
                          FROM employee_mapping
                         WHERE site_code = :site AND user_code IN (:codes)
                        """,
                new MapSqlParameterSource().addValue("site", siteCode).addValue("codes", userCodes),
                row -> {
                    resolved.put(row.getString("user_code"), row.getString("employee_id"));
                });
        return resolved;
    }

    /** Creates the mapping or repoints it; safe when two requests set the same code at once. */
    public void put(String siteCode, String userCode, String employeeId) {
        jdbc.update("""
                        INSERT INTO employee_mapping (id, site_code, user_code, employee_id, created_at)
                        VALUES (:id, :site, :code, :employee, :now)
                        ON CONFLICT (site_code, user_code) DO UPDATE SET employee_id = EXCLUDED.employee_id
                        """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("site", siteCode)
                        .addValue("code", userCode)
                        .addValue("employee", employeeId)
                        .addValue("now", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)));
    }

    public boolean remove(String siteCode, String userCode) {
        return jdbc.update("DELETE FROM employee_mapping WHERE site_code = :site AND user_code = :code",
                new MapSqlParameterSource().addValue("site", siteCode).addValue("code", userCode)) > 0;
    }

    public List<Mapping> list(String siteCode) {
        return jdbc.query("""
                        SELECT user_code, employee_id
                          FROM employee_mapping
                         WHERE site_code = :site
                         ORDER BY user_code
                        """,
                new MapSqlParameterSource("site", siteCode),
                (row, n) -> new Mapping(row.getString("user_code"), row.getString("employee_id")));
    }

    public record Mapping(String userCode, String employeeId) {
    }
}
