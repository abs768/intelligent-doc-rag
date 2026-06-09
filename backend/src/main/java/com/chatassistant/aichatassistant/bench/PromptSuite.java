package com.chatassistant.aichatassistant.bench;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PromptSuite {

    private PromptSuite() {}

    public static List<Prompt> load(Path overridePath) throws IOException {
        YAMLMapper yaml = new YAMLMapper();
        if (overridePath != null) {
            return yaml.readValue(Files.newInputStream(overridePath), Wrapper.class).prompts();
        }
        try (InputStream in = PromptSuite.class.getResourceAsStream("/bench/prompts.yaml")) {
            if (in == null) {
                throw new IOException("Default prompt suite /bench/prompts.yaml not found on classpath");
            }
            return yaml.readValue(in, Wrapper.class).prompts();
        }
    }

    private record Wrapper(@JsonProperty("prompts") List<Prompt> prompts) {}
}
