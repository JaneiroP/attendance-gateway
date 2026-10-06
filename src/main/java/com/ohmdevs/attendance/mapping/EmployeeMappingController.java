package com.ohmdevs.attendance.mapping;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ohmdevs.attendance.mapping.EmployeeMappings.Mapping;

/** Administration API for the clock code to employee id table of a site. */
@RestController
@RequestMapping("/api/v1/sites/{siteCode}/mappings")
class EmployeeMappingController {

    private final EmployeeMappings mappings;

    EmployeeMappingController(EmployeeMappings mappings) {
        this.mappings = mappings;
    }

    @GetMapping
    List<Mapping> list(@PathVariable String siteCode) {
        return mappings.list(siteCode);
    }

    @PutMapping("/{userCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void put(@PathVariable String siteCode, @PathVariable String userCode,
             @Valid @RequestBody PutMappingRequest request) {
        mappings.put(siteCode, userCode, request.employeeId());
    }

    @DeleteMapping("/{userCode}")
    ResponseEntity<Void> remove(@PathVariable String siteCode, @PathVariable String userCode) {
        return mappings.remove(siteCode, userCode)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    record PutMappingRequest(@NotBlank @Size(max = 64) String employeeId) {
    }
}
