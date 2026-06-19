package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurn;
import com.example.javaagentmvp.admissionworkflow.intent.SlotDelta;

/** Bridges compiled IR into legacy {@link ResolvedTurn} for MCP and RAG components. */
public final class AdmissionQueryIrBridge {

    private AdmissionQueryIrBridge() {
    }

    public static ResolvedTurn toResolvedTurn(AdmissionQueryIr query, boolean inheritedFromPrior) {
        if (query == null) {
            return ResolvedTurn.unknown("");
        }
        AdmissionIntent intent = query.toIntent();
        AdmissionSlotsIr slots = query.slots();
        AdmissionInputParser.ParsedAdmissionInput parsed = new AdmissionInputParser.ParsedAdmissionInput(
                slots.score(),
                slots.primaryProvince(),
                slots.subjectGroup(),
                slots.year(),
                slots.admissionType());
        return new ResolvedTurn(intent, parsed, SlotDelta.NONE, inheritedFromPrior);
    }
}
