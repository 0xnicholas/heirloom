package com.heirloom.security.validation;

import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.*;
import com.heirloom.security.domain.Action;
import com.heirloom.security.domain.ActionInput;
import com.heirloom.security.domain.StateGate;
import org.springframework.stereotype.Component;

/**
 * Validates an Action definition against ADR-007's three rules:
 * <ol>
 *   <li>Ability gate — requiredAbility must be declared on the target type</li>
 *   <li>State gate — stateGate must reference valid states/transitions</li>
 *   <li>Type consistency — input fields must match target type's fields</li>
 * </ol>
 */
@Component
public class ActionValidator {

    private final TypeRepository typeRepo;

    public ActionValidator(TypeRepository typeRepo) {
        this.typeRepo = typeRepo;
    }

    public void validate(Action action) {
        ResourceType targetType = typeRepo.findByName(action.resolveTargetType())
                .orElseThrow(() -> new ActionValidationException(
                        "Target type '" + action.resolveTargetType()
                        + "' not found in Schema Registry"));

        checkAbilityGate(action, targetType);
        checkStateGate(action, targetType);
        checkInputSchema(action, targetType);
    }

    private void checkAbilityGate(Action action, ResourceType targetType) {
        Ability required = action.resolveRequiredAbility();
        if (required == null) return;
        if (!targetType.getAbilities().contains(required)) {
            throw new ActionValidationException(
                    "Action '" + action.getName() + "' requires '" + required
                    + "' but type '" + targetType.getName()
                    + "' does not declare it. Declared: " + targetType.getAbilities());
        }
    }

    private void checkStateGate(Action action, ResourceType targetType) {
        StateGate gate = action.resolveStateGate();
        if (gate == null) return;

        if (!StateMachine.isValidState(targetType, gate.fromState())) {
            throw new ActionValidationException(
                    "StateGate fromState '" + gate.fromState()
                    + "' is not a valid state for type '" + targetType.getName()
                    + "'. Valid states: " + StateMachine.allStates(targetType));
        }

        if (gate.toState() != null
                && !StateMachine.isValidTransition(targetType, gate.fromState(), gate.toState())) {
            throw new ActionValidationException(
                    "StateGate transition '" + gate.fromState() + " → " + gate.toState()
                    + "' is not defined in type '" + targetType.getName()
                    + "' state machine. Valid from '" + gate.fromState() + "': "
                    + StateMachine.transitionsFrom(targetType, gate.fromState()));
        }
    }

    private void checkInputSchema(Action action, ResourceType targetType) {
        java.util.List<ActionInput> inputs = action.resolveInputSchema();
        if (inputs == null || inputs.isEmpty()) return;

        for (ActionInput input : inputs) {
            Field declared = targetType.getFields().stream()
                    .filter(f -> f.name().equals(input.fieldName()))
                    .findFirst()
                    .orElseThrow(() -> new ActionValidationException(
                            "Input field '" + input.fieldName()
                            + "' not declared on type '" + targetType.getName() + "'"));

            if (input.fieldType() != declared.type()) {
                throw new ActionValidationException(
                        "Input field '" + input.fieldName()
                        + "' type mismatch: declared " + declared.type()
                        + ", action expects " + input.fieldType());
            }
        }
    }
}
