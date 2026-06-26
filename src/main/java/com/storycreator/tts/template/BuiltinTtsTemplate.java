package com.storycreator.tts.template;

import java.util.List;

public record BuiltinTtsTemplate(String id, String name, String description, List<TtsReplacementRule> rules) {
}
