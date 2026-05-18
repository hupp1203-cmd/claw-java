package com.claw.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for {@link Provider} implementations.
 *
 * <p>Providers are registered by name and can be looked up at runtime.
 * The registry also tracks a default provider (the first one registered
 * unless explicitly changed).
 *
 * <p>Usage:
 * <pre>{@code
 * ProviderRegistry.register(new AnthropicProvider());
 * Provider p = ProviderRegistry.get("anthropic");
 * }</pre>
 */
public final class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);
    private static final Map<String, Provider> providers = new LinkedHashMap<>();
    private static String defaultProviderName;

    private ProviderRegistry() {
        // utility class
    }

    /**
     * Register a provider. If no default is set, the first registered
     * provider becomes the default.
     */
    public static void register(Provider provider) {
        providers.put(provider.name(), provider);
        if (defaultProviderName == null) {
            defaultProviderName = provider.name();
        }
        log.info("Registered provider: {}", provider.name());
    }

    /**
     * Look up a provider by name.
     *
     * @throws IllegalArgumentException if no provider with the given name is registered
     */
    public static Provider get(String name) {
        Provider p = providers.get(name);
        if (p == null) {
            throw new IllegalArgumentException(
                    "No provider registered with name '%s'. Available: %s"
                            .formatted(name, providers.keySet()));
        }
        return p;
    }

    /**
     * Look up a provider by name, returning an empty {@link Optional} if not found.
     */
    public static Optional<Provider> find(String name) {
        return Optional.ofNullable(providers.get(name));
    }

    /**
     * Return the default provider.
     *
     * @throws IllegalStateException if no providers have been registered
     */
    public static Provider defaultProvider() {
        if (defaultProviderName == null) {
            throw new IllegalStateException("No providers registered");
        }
        return providers.get(defaultProviderName);
    }

    /**
     * Set the default provider by name. The provider must already be registered.
     */
    public static void setDefault(String name) {
        if (!providers.containsKey(name)) {
            throw new IllegalArgumentException("No provider registered with name: " + name);
        }
        defaultProviderName = name;
    }

    /** Return an unmodifiable list of all registered provider names. */
    public static List<String> listAll() {
        return List.copyOf(providers.keySet());
    }

    /** Remove all registered providers. */
    public static void clear() {
        providers.clear();
        defaultProviderName = null;
    }
}
