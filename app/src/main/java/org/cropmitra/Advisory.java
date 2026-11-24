package org.cropmitra;

import java.util.List;

final class Advisory {
    enum Finding { HEALTHY, BLIGHT, PEST_DAMAGE, LARVA, UNKNOWN }
    enum Stage { EARLY, LATE, UNKNOWN }
    enum Severity { UNKNOWN, MILD, MODERATE, SEVERE }
    enum Action {
        RETAKE("Retake a sharper photo of the relevant plant part"),
        MONITOR("Monitor visibly healthy plants"),
        WHORL_PHOTO("Take the first close-up of the central whorl"),
        CHECK_NEARBY("Scout nearby plants for similar symptoms"),
        RECHECK("Return later and compare visible symptoms"),
        EXTENSION("Contact a local agricultural extension worker");

        final String description;

        Action(String description) {
            this.description = description;
        }
    }

    record Evidence(Finding finding, Stage stage, Severity severity, boolean followUp) {}

    static List<Action> eligible(Evidence input) {
        return switch (input.finding()) {
            case HEALTHY -> List.of(Action.MONITOR);
            case PEST_DAMAGE -> List.of(Action.WHORL_PHOTO);
            case UNKNOWN -> input.followUp()
                    ? List.of(Action.RETAKE, Action.EXTENSION)
                    : List.of(Action.RETAKE);
            case BLIGHT, LARVA -> switch (input.severity()) {
                case SEVERE -> List.of(Action.EXTENSION, Action.CHECK_NEARBY);
                case MODERATE, UNKNOWN -> List.of(Action.CHECK_NEARBY, Action.EXTENSION);
                case MILD -> input.stage() == Stage.EARLY && input.finding() == Finding.LARVA
                        ? List.of(Action.CHECK_NEARBY, Action.RECHECK)
                        : List.of(Action.RECHECK, Action.CHECK_NEARBY);
            };
        };
    }

    static String prompt(Evidence input, List<Action> options) {
        StringBuilder text = new StringBuilder();
        text.append("<|start_header_id|>system<|end_header_id|>\n\n")
                .append("Choose the best next action for a maize farmer in Tamil Nadu. ")
                .append("Return exactly one eligible ID. Prioritize contacting an extension worker ")
                .append("if damage is severe. Prefer scouting nearby plants when an early crop has ")
                .append("a likely larva or when the visible damage is moderate. For a first unclear photo, ")
                .append("prefer retaking the relevant plant part. Do not infer facts that are missing.")
                .append("<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n")
                .append("Finding: ").append(input.finding()).append("\n")
                .append("Crop stage: ").append(input.stage()).append("\n")
                .append("Visible damage on this plant: ").append(input.severity()).append("\n")
                .append("Follow-up photo: ").append(input.followUp()).append("\nEligible actions:\n");
        for (Action action : options) {
            text.append(action.name()).append(" = ").append(action.description).append("\n");
        }
        return text.append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n").toString();
    }

    static Action parse(String response, List<Action> options) {
        String id = response.trim();
        for (Action action : options) {
            if (action.name().equals(id)) {
                return action;
            }
        }
        return null;
    }
}
