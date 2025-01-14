package io.smallrye.reactive.messaging.kafka.impl;

import java.util.*;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.UnsatisfiedResolutionException;
import javax.enterprise.inject.literal.NamedLiteral;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.eclipse.microprofile.reactive.messaging.spi.ConnectorFactory;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.reactive.messaging.i18n.ProviderLogging;
import io.smallrye.reactive.messaging.kafka.i18n.KafkaExceptions;

public class ConfigHelper {

    public static final String KAFKA_CONFIGURATION_NAME_ATTRIBUTE = "kafka-configuration";
    public static final String DEFAULT_KAFKA_BROKER = "default-kafka-broker";

    private ConfigHelper() {
        // Avoid direct instantiation.
    }

    /**
     * Computes the channel configuration
     * The channel configuration is extracted from 3 places (from the most important to the less important):
     * <ol>
     * <li>The channel configuration in the application configuration (the
     * <em>mp.messaging.[incoming|outgoing].channel.attr=value</em></li>
     * <li>From a bean exposing a Map%lt;String, Object&gt; exposed with <em>@Identifier(channel)</em>. The content
     * of this map is generated by the runtime. The name can be configured using the
     * <em>mp.messaging.[incoming|outgoing].channel.kafka-configuration</em> attribute</li>
     * <li>The default Kafka configuration (generally the <em>kafka.attr=value</em> properties), also exposed as an identified
     * Map%lt;String, Object&gt;.</li>
     * </ol>
     *
     * @param config the received config
     * @return the computed configuration
     */
    public static Config retrieveChannelConfiguration(Instance<Map<String, Object>> instances, Config config) {
        // Retrieve the default kafka configuration (3)
        Map<String, Object> defaultKafkaConfig = ConfigHelper.retrieveDefaultKafkaConfig(instances);
        // Retrieve the channel kafka configuration (2)
        Map<String, Object> channelSpecificConfig = ConfigHelper.getChannelSpecificConfig(instances, config);

        return ConfigHelper.merge(config, channelSpecificConfig, defaultKafkaConfig);
    }

    public static Config merge(Config passedCfg, Map<String, Object> defaultKafkaCfg) {
        return new Config() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T getValue(String propertyName, Class<T> propertyType) {
                T passedCgfValue = passedCfg.getOptionalValue(propertyName, propertyType).orElse(null);
                if (passedCgfValue == null) {
                    Object o = defaultKafkaCfg.get(propertyName);
                    if (o == null) {
                        throw KafkaExceptions.ex.missingProperty(propertyName);
                    }
                    if (propertyType.isInstance(o)) {
                        return (T) o;
                    }
                    if (o instanceof String) {
                        Optional<Converter<T>> converter = passedCfg.getConverter(propertyType);
                        return converter.map(conv -> conv.convert(o.toString()))
                                .orElseThrow(() -> new NoSuchElementException(propertyName));
                    }
                    throw KafkaExceptions.ex.cannotConvertProperty(propertyName, o.getClass(), propertyType);
                } else {
                    return passedCgfValue;
                }
            }

            @Override
            public ConfigValue getConfigValue(String propertyName) {
                return passedCfg.getConfigValue(propertyName);
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
                Optional<T> passedCfgValue = passedCfg.getOptionalValue(propertyName, propertyType);
                if (!passedCfgValue.isPresent()) {
                    Object o = defaultKafkaCfg.get(propertyName);
                    if (o == null) {
                        return Optional.empty();
                    }
                    if (propertyType.isInstance(o)) {
                        return Optional.of((T) o);
                    }
                    if (o instanceof String) {
                        Optional<Converter<T>> converter = passedCfg.getConverter(propertyType);
                        return converter.map(conv -> conv.convert(o.toString()));
                    }
                    return Optional.empty();
                } else {
                    return passedCfgValue;
                }
            }

            @Override
            public Iterable<String> getPropertyNames() {
                Iterable<String> names = passedCfg.getPropertyNames();
                Set<String> result = new HashSet<>();
                names.forEach(result::add);
                result.addAll(defaultKafkaCfg.keySet());
                return result;
            }

            @Override
            public Iterable<ConfigSource> getConfigSources() {
                return passedCfg.getConfigSources();
            }

            @Override
            public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
                return passedCfg.getConverter(forType);
            }

            @Override
            public <T> T unwrap(Class<T> type) {
                return passedCfg.unwrap(type);
            }
        };
    }

    /**
     * Returns a {@code Config} instance merging the values from the 3 sources (the channel configuration,
     * a map specific to the channel and the global Kafka configuration).
     *
     * @param passedCfg the channel configuration (high priority)
     * @param namedConfig the channel specific configuration (medium priority)
     * @param defaultKafkaCfg the default Kafka configuration (low priority)
     * @return the computed config.
     */
    public static Config merge(Config passedCfg,
            Map<String, Object> namedConfig,
            Map<String, Object> defaultKafkaCfg) {

        if (namedConfig.isEmpty() && defaultKafkaCfg.isEmpty()) {
            return passedCfg;
        }

        return new Config() {

            private <T> T extractValue(String name, Class<T> clazz, boolean failIfMissing) {
                Object value = passedCfg.getOptionalValue(name, clazz).orElse(null);
                if (value != null) {
                    //noinspection unchecked
                    return (T) value;
                }

                value = namedConfig.getOrDefault(name, defaultKafkaCfg.get(name));
                if (value == null) {
                    if (failIfMissing) {
                        throw KafkaExceptions.ex.missingProperty(name);
                    }
                    return null;
                }

                if (clazz.isInstance(value)) {
                    //noinspection unchecked
                    return (T) value;
                }

                // Attempt a conversion
                if (value instanceof String) {
                    String v = (String) value;
                    Optional<Converter<T>> converter = passedCfg.getConverter(clazz);
                    if (converter.isPresent()) {
                        return converter.get().convert(v);
                    }
                    if (failIfMissing) {
                        throw KafkaExceptions.ex.missingProperty(name);
                    }
                    return null;
                }

                if (failIfMissing) {
                    throw KafkaExceptions.ex.cannotConvertProperty(name, value.getClass(), clazz);
                } else {
                    return null;
                }
            }

            @Override
            public <T> T getValue(String propertyName, Class<T> propertyType) {
                return extractValue(propertyName, propertyType, true);
            }

            @Override
            public ConfigValue getConfigValue(String propertyName) {
                // We only compute ConfigValue for the original config.
                return passedCfg.getConfigValue(propertyName);
            }

            @Override
            public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
                T value = extractValue(propertyName, propertyType, false);
                return Optional.ofNullable(value);
            }

            @Override
            public Iterable<String> getPropertyNames() {
                Set<String> result = new HashSet<>();

                // First global
                result.addAll(defaultKafkaCfg.keySet());

                // Configured name
                result.addAll(namedConfig.keySet());

                // Channel
                Iterable<String> names = passedCfg.getPropertyNames();
                names.forEach(result::add);

                return result;
            }

            @Override
            public Iterable<ConfigSource> getConfigSources() {
                return passedCfg.getConfigSources();
            }

            @Override
            public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
                return passedCfg.getConverter(forType);
            }

            @Override
            public <T> T unwrap(Class<T> type) {
                return passedCfg.unwrap(type);
            }
        };
    }

    /**
     * Retrieves the default Kafka configuration if any. It looks for a {@code Map%lt;String, Object&gt;} identified by
     * {@link #DEFAULT_KAFKA_BROKER}.
     *
     * @param instances the instances of map exposed as bean
     * @return the map, empty if the lookup fails
     */
    public static Map<String, Object> retrieveDefaultKafkaConfig(Instance<Map<String, Object>> instances) {
        Instance<Map<String, Object>> defaultKafkaConfigurationInstance = instances
                .select(Identifier.Literal.of(DEFAULT_KAFKA_BROKER));
        if (defaultKafkaConfigurationInstance.isUnsatisfied()) {
            // Try with @Named, this will be removed when @Named support will be removed.
            defaultKafkaConfigurationInstance = instances.select(NamedLiteral.of(DEFAULT_KAFKA_BROKER));
            if (!defaultKafkaConfigurationInstance.isUnsatisfied()) {
                ProviderLogging.log.deprecatedNamed();
            }
        }

        Map<String, Object> defaultKafkaConfig = Collections.emptyMap();
        if (!defaultKafkaConfigurationInstance.isUnsatisfied()) {
            defaultKafkaConfig = defaultKafkaConfigurationInstance.get();
        }
        return defaultKafkaConfig;
    }

    /**
     * Looks for a {@code Map%lt;String, Object&gt;} for the given channel. The map is identified using {@link Identifier}.
     * The identifier value is either configured in the channel configuration, or is the channel name.
     *
     * @param instances the instances of map exposed as bean
     * @param config the channel configuration
     * @return the map, empty if the lookup fails.
     */
    public static Map<String, Object> getChannelSpecificConfig(Instance<Map<String, Object>> instances, Config config) {
        Optional<String> name = config.getOptionalValue(KAFKA_CONFIGURATION_NAME_ATTRIBUTE, String.class);
        Optional<String> channel = config.getOptionalValue(ConnectorFactory.CHANNEL_NAME_ATTRIBUTE, String.class);
        String channelName = channel.orElse(null);
        Map<String, Object> channelSpecificConfig = Collections.emptyMap();
        if (name.isPresent()) {
            channelSpecificConfig = lookupForIdentifiedConfiguration(instances, name.get(), false);
        } else if (channelName != null) {
            channelSpecificConfig = lookupForIdentifiedConfiguration(instances, channelName, true);
        }
        return channelSpecificConfig;
    }

    /**
     * Looks for a CDI bean of type {@code Map} identified using the given named.
     * If the lookup fails and {@code optional} is {@code true}, an {@link UnsatisfiedResolutionException} is thrown.
     * Otherwise, an empty {@code Map} is returned.
     *
     * @param identifier the identifier
     * @param optional whether the lookup is optional
     * @return the result
     */
    public static Map<String, Object> lookupForIdentifiedConfiguration(Instance<Map<String, Object>> instances,
            String identifier, boolean optional) {
        Instance<Map<String, Object>> instance = instances.select(Identifier.Literal.of(identifier));
        if (instance.isUnsatisfied()) {
            if (!optional) {
                throw new UnsatisfiedResolutionException("Cannot find the Kafka configuration: " + identifier);
            } else {
                return Collections.emptyMap();
            }
        } else {
            return instance.get();
        }
    }
}
