package com.example.javaagentmvp.web;

import com.example.javaagentmvp.admission.AdmissionQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admission")
public class AdmissionController {

    private static final Logger log = LoggerFactory.getLogger(AdmissionController.class);

    private final AdmissionQueryService admissionQueryService;

    public AdmissionController(AdmissionQueryService admissionQueryService) {
        this.admissionQueryService = admissionQueryService;
    }

    @PostMapping("/query")
    public AdmissionResponse query(@RequestBody AdmissionRequest request) {
        if (request == null
                || request.province() == null || request.province().isBlank()
                || request.subjectType() == null || request.subjectType().isBlank()
                || request.admissionType() == null || request.admissionType().isBlank()
                || request.year() < 2000
                || request.score() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid query params");
        }

        log.info("admission query score={} province={} subjectType={} year={} admissionType={} campus={}",
                request.score(),
                request.province(),
                request.subjectType(),
                request.year(),
                request.admissionType(),
                request.campusPreference());

        AdmissionQueryService.AdmissionResult result = admissionQueryService.query(
                new AdmissionQueryService.AdmissionRequest(
                        request.score(),
                        request.province().strip(),
                        request.subjectType().strip(),
                        request.year(),
                        request.admissionType().strip(),
                        request.campusPreference()
                )
        );
        log.info("admission query matched {} rows", result.rows().size());
        return new AdmissionResponse(result.summary(), result.columns(), result.rows());
    }

    public record AdmissionRequest(
            int score,
            String province,
            String subjectType,
            int year,
            String admissionType,
            String campusPreference) {
    }

    public record AdmissionResponse(
            String summary,
            java.util.List<String> columns,
            java.util.List<java.util.List<Object>> rows) {
    }
}
