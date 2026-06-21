package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class AdmissionPriorSlotsBuilder {

    private final AdmissionOntologyRegistry ontologyRegistry;

    public AdmissionPriorSlotsBuilder(AdmissionOntologyRegistry ontologyRegistry) {
        this.ontologyRegistry = ontologyRegistry;
    }

    public AdmissionSlotsIr build(List<String> priorUserMessagesNewestFirst) {
        if (priorUserMessagesNewestFirst == null || priorUserMessagesNewestFirst.isEmpty()) {
            return AdmissionSlotsIr.empty();
        }
        List<String> chronological = new ArrayList<>(priorUserMessagesNewestFirst);
        Collections.reverse(chronological);

        AdmissionSlotsIr merged = AdmissionSlotsIr.empty();
        for (String message : chronological) {
            if (message == null || message.isBlank()) {
                continue;
            }
            AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(message);
            AdmissionSlotsIr fromMessage = slotsFromParsed(parsed);
            for (AdmissionRegionIr region : ontologyRegistry.matchRegions(message)) {
                fromMessage = withRegionProvinces(fromMessage, region);
            }
            if (!fromMessage.provincesOrEmpty().isEmpty()) {
                merged = fromMessage.mergedWith(merged.withoutProvinces());
            }
            else {
                merged = fromMessage.mergedWith(merged);
            }
        }
        return merged;
    }

    private static AdmissionSlotsIr slotsFromParsed(AdmissionInputParser.ParsedAdmissionInput parsed) {
        List<String> provinces = new ArrayList<>();
        if (parsed.province() != null && !parsed.province().isBlank()) {
            provinces.add(parsed.province());
        }
        return new AdmissionSlotsIr(
                parsed.score(),
                parsed.rank(),
                provinces,
                parsed.subjectGroup(),
                parsed.year(),
                parsed.admissionType());
    }

    private static AdmissionSlotsIr withRegionProvinces(AdmissionSlotsIr slots, AdmissionRegionIr region) {
        List<String> provinces = new ArrayList<>(slots.provincesOrEmpty());
        for (String province : region.provinces()) {
            if (!provinces.contains(province)) {
                provinces.add(province);
            }
        }
        return new AdmissionSlotsIr(
                slots.score(),
                slots.rank(),
                provinces,
                slots.subjectGroup(),
                slots.year(),
                slots.admissionType());
    }
}
