package io.smallrye.reactive.messaging.kafka.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.TracingMetadata;
import io.smallrye.reactive.messaging.kafka.KafkaConnector;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.smallrye.reactive.messaging.kafka.KafkaRecordBatch;
import io.smallrye.reactive.messaging.kafka.base.KafkaMapBasedConfig;
import io.smallrye.reactive.messaging.kafka.base.KafkaTestBase;

public class BatchTracingPropagationTest extends KafkaTestBase {

    private InMemorySpanExporter testExporter;
    private SpanProcessor spanProcessor;

    @BeforeEach
    public void setup() {
        GlobalOpenTelemetry.resetForTest();

        testExporter = InMemorySpanExporter.create();
        spanProcessor = SimpleSpanProcessor.create(testExporter);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanProcessor)
                .setSampler(Sampler.alwaysOn())
                .build();

        OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }

    @AfterEach
    public void cleanup() {
        if (testExporter != null) {
            testExporter.shutdown();
        }
        if (spanProcessor != null) {
            spanProcessor.shutdown();
        }
    }

    @AfterAll
    static void shutdown() {
        GlobalOpenTelemetry.resetForTest();
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testFromAppToKafka() {
        List<Map.Entry<String, Integer>> messages = new CopyOnWriteArrayList<>();
        List<Context> contexts = new CopyOnWriteArrayList<>();
        usage.consumeIntegersWithTracing(topic, 10, 1, TimeUnit.MINUTES, null,
                (key, value) -> messages.add(entry(key, value)),
                contexts::add);
        runApplication(getKafkaSinkConfigForMyAppGeneratingData(), MyAppGeneratingData.class);

        await().until(() -> messages.size() >= 10);
        List<Integer> values = new ArrayList<>();
        assertThat(messages).allSatisfy(entry -> {
            assertThat(entry.getValue()).isNotNull();
            values.add(entry.getValue());
        });
        assertThat(values).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        assertThat(contexts).hasSize(10);
        assertThat(contexts).doesNotContainNull().doesNotHaveDuplicates();

        List<String> spanIds = contexts.stream()
                .map(context -> Span.fromContextOrNull(context).getSpanContext().getSpanId())
                .collect(Collectors.toList());
        assertThat(spanIds).doesNotContainNull().doesNotHaveDuplicates().hasSize(10);

        List<String> traceIds = contexts.stream()
                .map(context -> Span.fromContextOrNull(context).getSpanContext().getTraceId())
                .collect(Collectors.toList());
        assertThat(traceIds).doesNotContainNull().doesNotHaveDuplicates().hasSize(10);

        for (SpanData data : testExporter.getFinishedSpanItems()) {
            assertThat(data.getSpanId()).isIn(spanIds);
            assertThat(data.getSpanId()).isNotEqualTo(data.getParentSpanId());
            assertThat(data.getTraceId()).isIn(traceIds);
            assertThat(data.getKind()).isEqualByComparingTo(SpanKind.PRODUCER);
        }
    }

    @Test
    public void testFromKafkaToAppWithParentSpan() {
        String parentTopic = topic + "-parent";
        String stuffTopic = topic + "-stuff";
        MyAppReceivingData bean = runApplication(getKafkaSinkConfigForMyAppReceivingData(parentTopic),
                MyAppReceivingData.class);

        AtomicInteger count = new AtomicInteger();
        List<SpanContext> producedSpanContexts = new CopyOnWriteArrayList<>();

        usage.produceIntegers(10, null,
                () -> new ProducerRecord<>(parentTopic, null, null, "a-key", count.getAndIncrement(),
                        createTracingSpan(producedSpanContexts, stuffTopic)));

        await().until(() -> bean.list().size() >= 10);
        assertThat(bean.list()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        List<String> producedTraceIds = producedSpanContexts.stream()
                .map(SpanContext::getTraceId)
                .collect(Collectors.toList());
        assertThat(producedTraceIds).hasSize(10);

        assertThat(bean.tracing()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(bean.tracing()).doesNotContainNull().doesNotHaveDuplicates();

        List<String> receivedTraceIds = bean.tracing().stream()
                .map(tracingMetadata -> Span.fromContext(tracingMetadata.getCurrentContext()).getSpanContext().getTraceId())
                .collect(Collectors.toList());
        assertThat(receivedTraceIds).doesNotContainNull().doesNotHaveDuplicates().hasSize(10);
        assertThat(receivedTraceIds).containsExactlyInAnyOrderElementsOf(producedTraceIds);

        List<String> spanIds = new ArrayList<>();

        for (TracingMetadata tracing : bean.tracing()) {
            spanIds.add(Span.fromContext(tracing.getCurrentContext()).getSpanContext().getSpanId());

            assertThat(tracing.getPreviousContext()).isNotNull();
            Span previousSpan = Span.fromContextOrNull(tracing.getPreviousContext());
            assertThat(previousSpan).isNotNull();
            assertThat(previousSpan.getSpanContext().getTraceId())
                    .isEqualTo(Span.fromContext(tracing.getCurrentContext()).getSpanContext().getTraceId());
            assertThat(previousSpan.getSpanContext().getSpanId())
                    .isNotEqualTo(Span.fromContext(tracing.getCurrentContext()).getSpanContext().getSpanId());
        }

        assertThat(spanIds).doesNotContainNull().doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(10);

        List<String> parentIds = bean.tracing().stream()
                .map(tracingMetadata -> Span.fromContextOrNull(tracingMetadata.getPreviousContext())
                        .getSpanContext().getSpanId())
                .collect(Collectors.toList());

        assertThat(producedSpanContexts.stream()
                .map(SpanContext::getSpanId)).containsExactlyElementsOf(parentIds);

        for (SpanData data : testExporter.getFinishedSpanItems()) {
            assertThat(data.getSpanId()).isIn(spanIds);
            assertThat(data.getSpanId()).isNotEqualTo(data.getParentSpanId());
            assertThat(data.getKind()).isEqualByComparingTo(SpanKind.CONSUMER);
            assertThat(data.getParentSpanId()).isNotNull();
            assertThat(data.getParentSpanId()).isIn(parentIds);
        }
    }

    @Test
    public void testFromKafkaToAppWithNoParent() {
        MyAppReceivingData bean = runApplication(
                getKafkaSinkConfigForMyAppReceivingData(topic), MyAppReceivingData.class);

        AtomicInteger count = new AtomicInteger();

        usage.produceIntegers(10, null,
                () -> new ProducerRecord<>(topic, null, null, "a-key", count.getAndIncrement()));

        await().until(() -> bean.list().size() >= 10);
        assertThat(bean.list()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        assertThat(bean.tracing()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(bean.tracing()).doesNotContainNull().doesNotHaveDuplicates();
        List<String> spanIds = new ArrayList<>();

        for (TracingMetadata tracing : bean.tracing()) {
            spanIds.add(Span.fromContext(tracing.getCurrentContext()).getSpanContext().getSpanId());
            assertThat(Span.fromContextOrNull(tracing.getPreviousContext())).isNull();
        }

        assertThat(spanIds).doesNotContainNull().doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(10);

        for (SpanData data : testExporter.getFinishedSpanItems()) {
            assertThat(data.getSpanId()).isIn(spanIds);
            assertThat(data.getSpanId()).isNotEqualTo(data.getParentSpanId());
            assertThat(data.getKind()).isEqualByComparingTo(SpanKind.CONSUMER);
        }
    }

    private Iterable<Header> createTracingSpan(List<SpanContext> spanContexts, String topic) {
        RecordHeaders proposedHeaders = new RecordHeaders();
        final Span span = KafkaConnector.TRACER.spanBuilder(topic).setSpanKind(SpanKind.PRODUCER).startSpan();
        final Context context = span.storeInContext(Context.current());
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(context, proposedHeaders, (headers, key, value) -> {
                    if (headers != null) {
                        headers.remove(key).add(key, value.getBytes(StandardCharsets.UTF_8));
                    }
                });
        spanContexts.add(span.getSpanContext());
        return proposedHeaders;
    }

    private KafkaMapBasedConfig getKafkaSinkConfigForMyAppGeneratingData() {
        KafkaMapBasedConfig.Builder builder = KafkaMapBasedConfig.builder("mp.messaging.outgoing.kafka", true);
        builder.put("value.serializer", IntegerSerializer.class.getName());
        builder.put("topic", topic);
        builder.put("batch", true);
        return builder.build();
    }

    private KafkaMapBasedConfig getKafkaSinkConfigForMyAppReceivingData(String topic) {
        KafkaMapBasedConfig.Builder builder = KafkaMapBasedConfig.builder("mp.messaging.incoming.stuff", true);
        builder.put("value.deserializer", IntegerDeserializer.class.getName());
        builder.put("key.deserializer", StringDeserializer.class.getName());
        builder.put("topic", topic);
        builder.put("auto.offset.reset", "earliest");
        builder.put("batch", true);

        return builder.build();
    }

    @ApplicationScoped
    public static class MyAppGeneratingData {

        @Outgoing("kafka")
        public Publisher<Integer> source() {
            return Multi.createFrom().range(0, 10);
        }
    }

    @ApplicationScoped
    public static class MyAppReceivingData {
        private final List<TracingMetadata> tracingMetadata = new ArrayList<>();
        private final List<Integer> results = new CopyOnWriteArrayList<>();

        @Incoming("stuff")
        public CompletionStage<Void> consume(KafkaRecordBatch<String, Integer> batchInput) {
            results.addAll(batchInput.getPayload());
            for (KafkaRecord<String, Integer> record : batchInput) {
                tracingMetadata.add(record.getMetadata(TracingMetadata.class).orElse(TracingMetadata.empty()));
            }
            return batchInput.ack();
        }

        public List<Integer> list() {
            return results;
        }

        public List<TracingMetadata> tracing() {
            return tracingMetadata;
        }
    }

}
