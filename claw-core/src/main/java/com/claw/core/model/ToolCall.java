package com.claw.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.Objects;

/**
 * A tool invocation request issued by the model.
 *
 * <p>Tool calls appear inside assistant {@link Message} objects when the model
 * decides it needs to execute a tool to fulfill the user's request.</p>
 *
 * @param id        unique identifier for this call (used to correlate results)
 * @param name      the name of the tool to invoke
 * @param arguments the arguments to pass to the tool, as key-value pairs
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments) {

    public ToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (arguments == null) arguments = Map.of();
        arguments = Map.copyOf(arguments);
    }
}
