package org.destroyermob.modqualitypicker.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record EffectiveQualitySelection(
        QualitySelection selection,
        QualityProfile profile,
        Map<String, String> effectiveChoices
) {
    public EffectiveQualitySelection {
        selection = selection == null ? QualitySelection.forBase("balanced") : selection;
        profile = profile == null ? QualityProfile.empty(selection.baseProfileId(), selection.baseProfileId()) : profile;
        effectiveChoices = effectiveChoices == null || effectiveChoices.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(effectiveChoices));
    }
}
