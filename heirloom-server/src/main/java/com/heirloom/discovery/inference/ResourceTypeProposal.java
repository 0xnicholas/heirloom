package com.heirloom.discovery.inference;

import com.heirloom.discovery.model.RawSchema;
import com.heirloom.schema.domain.*;
import java.util.*;

public record ResourceTypeProposal(String proposedTypeName, String sourceTable,
    List<Field> fields, List<Relationship> relationships, List<Ability> abilities,
    List<StateTransition> stateMachine, String description,
    InferenceRule.Confidence fieldsConfidence, InferenceRule.Confidence relationshipsConfidence,
    InferenceRule.Confidence abilitiesConfidence, InferenceRule.Confidence stateMachineConfidence) {

    public ResourceTypeProposal merge(ResourceTypeProposal incoming) {
        List<Field> mergedFields = new ArrayList<>(fields != null ? fields : List.of());
        if (incoming.fields != null) mergedFields.addAll(incoming.fields);
        List<Relationship> mergedRels = new ArrayList<>(relationships != null ? relationships : List.of());
        if (incoming.relationships != null) mergedRels.addAll(incoming.relationships);
        List<Ability> mergedAbilities = new ArrayList<>(abilities != null ? abilities : List.of());
        if (incoming.abilities != null) mergedAbilities.addAll(incoming.abilities);
        List<StateTransition> mergedSM = new ArrayList<>(stateMachine != null ? stateMachine : List.of());
        if (incoming.stateMachine != null) mergedSM.addAll(incoming.stateMachine);
        return new ResourceTypeProposal(proposedTypeName, sourceTable, mergedFields, mergedRels,
            mergedAbilities, mergedSM, description != null ? description : incoming.description,
            min(fieldsConfidence, incoming.fieldsConfidence),
            min(relationshipsConfidence, incoming.relationshipsConfidence),
            min(abilitiesConfidence, incoming.abilitiesConfidence),
            min(stateMachineConfidence, incoming.stateMachineConfidence));
    }

    public boolean isHighConfidence() {
        return fieldsConfidence != InferenceRule.Confidence.LOW
            && abilitiesConfidence == InferenceRule.Confidence.NONE;
    }

    private static InferenceRule.Confidence min(InferenceRule.Confidence a, InferenceRule.Confidence b) {
        if (a == null) return b; if (b == null) return a;
        return a.ordinal() < b.ordinal() ? a : b;
    }

    public ResourceType toResourceType() {
        ResourceType rt = new ResourceType(proposedTypeName);
        if (fields != null) rt.setFields(fields);
        if (abilities != null) rt.setAbilities(abilities);
        if (stateMachine != null) rt.setStateMachine(stateMachine);
        if (relationships != null) rt.setRelationships(relationships);
        if (description != null) rt.setDescription(description);
        return rt;
    }
}
