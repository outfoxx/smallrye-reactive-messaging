package io.smallrye.reactive.messaging;

import static io.smallrye.reactive.messaging.i18n.ProviderExceptions.ex;
import static io.smallrye.reactive.messaging.i18n.ProviderLogging.log;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.streams.operators.SubscriberBuilder;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.helpers.ClassUtils;
import io.smallrye.reactive.messaging.helpers.IgnoringSubscriber;
import io.smallrye.reactive.messaging.helpers.MultiUtils;

public class SubscriberMediator extends AbstractMediator {

    private Multi<? extends Message<?>> source;
    private Subscriber<Message<?>> subscriber;

    private Function<Multi<? extends Message<?>>, Multi<? extends Message<?>>> function;

    /**
     * Keep track of the subscription to cancel it once the scope is terminated.
     */
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();

    // Supported signatures:
    // 1. Subscriber<Message<I>> method()
    // 2. Subscriber<I> method()
    // 3. CompletionStage<?> method(Message<I> m) + Uni variant
    // 4. CompletionStage<?> method(I i) - + Uni variant
    // 5. void/? method(Message<I> m) - The support of this method has been removed (CES - Reactive Hangout 2018/09/11).
    // 6. void/? method(I i)

    public SubscriberMediator(MediatorConfiguration configuration) {
        super(configuration);
        if (configuration.shape() != Shape.SUBSCRIBER) {
            throw ex.illegalArgumentForSubscriberShape(configuration.shape());
        }
    }

    @Override
    public void initialize(Object bean) {
        super.initialize(bean);
        switch (configuration.consumption()) {
            case STREAM_OF_MESSAGE: // 1
            case STREAM_OF_PAYLOAD: // 2
                processMethodReturningASubscriber();
                break;
            case MESSAGE: // 3  (5 being dropped)
            case PAYLOAD: // 4 or 6
                if (ClassUtils.isAssignable(configuration.getReturnType(), CompletionStage.class)) {
                    // Case 3, 4
                    processMethodReturningACompletionStage();
                } else if (ClassUtils.isAssignable(configuration.getReturnType(), Uni.class)) {
                    // Case 3, 4 - Uni Variant
                    processMethodReturningAUni();
                } else {
                    // Case 6 (5 being dropped)
                    processMethodReturningVoid();
                }
                break;
            default:
                throw ex.illegalArgumentForUnexpectedConsumption(configuration.consumption());
        }

        assert this.subscriber != null;
    }

    @Override
    public Subscriber<Message<?>> getComputedSubscriber() {
        return subscriber;
    }

    @Override
    public boolean isConnected() {
        return source != null;
    }

    @Override
    public void connectToUpstream(Multi<? extends Message<?>> publisher) {
        this.source = convert(publisher);
    }

    @SuppressWarnings({ "ReactiveStreamsSubscriberImplementation" })
    @Override
    public void run() {
        assert this.source != null;
        assert this.function != null;
        assert this.subscriber != null;

        AtomicReference<Throwable> syncErrorCatcher = new AtomicReference<>();
        Subscriber<Message<?>> delegate = this.subscriber;
        Subscriber<Message<?>> delegating = new Subscriber<Message<?>>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscription.set(s);
                delegate.onSubscribe(new Subscription() {
                    @Override
                    public void request(long n) {
                        s.request(n);
                    }

                    @Override
                    public void cancel() {
                        s.cancel();
                    }
                });
            }

            @Override
            public void onNext(Message<?> o) {
                try {
                    delegate.onNext(o);
                } catch (Exception e) {
                    log.messageProcessingException(e);
                    syncErrorCatcher.set(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.messageProcessingException(t);
                syncErrorCatcher.set(t);
                delegate.onError(t);
            }

            @Override
            public void onComplete() {
                delegate.onComplete();
            }
        };

        function.apply(this.source).subscribe(delegating);
        // Check if a synchronous error has been caught
        Throwable throwable = syncErrorCatcher.get();
        if (throwable != null) {
            throw ex.weavingForIncoming(configuration.getIncoming(), throwable);
        }
    }

    private void processMethodReturningVoid() {
        this.subscriber = IgnoringSubscriber.INSTANCE;
        if (configuration.isBlocking()) {
            if (configuration.isBlockingExecutionOrdered()) {
                this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                        .onItem().transformToUniAndConcatenate(msg -> invokeBlocking(msg.getPayload())
                                .onItemOrFailure().transformToUni(handleInvocationResult(msg)))
                        .onFailure()
                        .invoke(failure -> health.reportApplicationFailure(configuration.methodAsString(), failure));
            } else {
                this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                        .onItem().transformToUniAndMerge(msg -> invokeBlocking(msg.getPayload())
                                .onItemOrFailure().transformToUni(handleInvocationResult(msg)))
                        .onFailure()
                        .invoke(failure -> health.reportApplicationFailure(configuration.methodAsString(), failure));
            }
        } else {
            this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                    .onItem().transformToUniAndConcatenate(msg -> Uni.createFrom().item(() -> invoke(msg.getPayload()))
                            .onItemOrFailure().transformToUni(handleInvocationResult(msg)))
                    .onFailure().invoke(failure -> health.reportApplicationFailure(configuration.methodAsString(), failure));
        }
    }

    private BiFunction<Object, Throwable, Uni<? extends Message<?>>> handleInvocationResult(
            Message<?> m) {
        return (success, failure) -> {
            if (failure != null) {
                if (configuration.getAcknowledgment() == Acknowledgment.Strategy.POST_PROCESSING) {
                    return Uni.createFrom().completionStage(m.nack(failure).thenApply(x -> m));
                } else {
                    // Invocation failed, but the message may have been already acknowledged (PRE or MANUAL), so
                    // we cannot nack. We propagate the failure downstream.
                    return Uni.createFrom().failure(failure);
                }
            } else {
                if (configuration.getAcknowledgment() == Acknowledgment.Strategy.POST_PROCESSING) {
                    return Uni.createFrom().completionStage(m.ack().thenApply(x -> m));
                } else {
                    return Uni.createFrom().item(m);
                }
            }
        };
    }

    private void processMethodReturningACompletionStage() {
        this.subscriber = IgnoringSubscriber.INSTANCE;
        boolean invokeWithPayload = MediatorConfiguration.Consumption.PAYLOAD == configuration.consumption();
        if (configuration.isBlocking()) {
            if (configuration.isBlockingExecutionOrdered()) {
                this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                        .onItem().transformToUniAndConcatenate(msg -> invokeBlockingAndHandleOutcome(invokeWithPayload, msg))
                        .onFailure().invoke(this::reportFailure);
            } else {
                this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                        .onItem().transformToUniAndMerge(msg -> invokeBlockingAndHandleOutcome(invokeWithPayload, msg))
                        .onFailure().invoke(this::reportFailure);
            }
        } else {
            this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                    .onItem().transformToUniAndConcatenate(msg -> {
                        Uni<?> uni;
                        if (invokeWithPayload) {
                            uni = Uni.createFrom().completionStage(() -> invoke(msg.getPayload()));
                        } else {
                            uni = Uni.createFrom().completionStage(() -> invoke(msg));
                        }
                        return uni.onItemOrFailure().transformToUni(handleInvocationResult(msg));
                    })
                    .onFailure().invoke(this::reportFailure);
        }
    }

    private Uni<? extends Message<?>> invokeBlockingAndHandleOutcome(boolean invokeWithPayload, Message<?> msg) {
        Uni<?> uni;
        if (invokeWithPayload) {
            uni = invokeBlocking(msg.getPayload());
        } else {
            uni = invokeBlocking(msg);
        }
        return uni.onItemOrFailure().transformToUni(handleInvocationResult(msg));
    }

    private void reportFailure(Throwable failure) {
        log.messageProcessingException(failure);
        health.reportApplicationFailure(configuration.methodAsString(), failure);
    }

    private void processMethodReturningAUni() {
        this.subscriber = IgnoringSubscriber.INSTANCE;
        boolean invokeWithPayload = MediatorConfiguration.Consumption.PAYLOAD == configuration.consumption();
        if (configuration.isBlocking()) {
            if (configuration.isBlockingExecutionOrdered()) {
                this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                        .onItem().transformToUniAndConcatenate(msg -> invokeBlockingAndHandleOutcome(invokeWithPayload, msg))
                        .onFailure().invoke(this::reportFailure);
            } else {
                this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                        .onItem().transformToUniAndMerge(msg -> invokeBlockingAndHandleOutcome(invokeWithPayload, msg))
                        .onFailure().invoke(this::reportFailure);
            }
        } else {
            this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration)
                    .onItem().transformToUniAndConcatenate(msg -> {
                        Uni<?> uni;
                        if (invokeWithPayload) {
                            uni = invoke(msg.getPayload());
                        } else {
                            uni = invoke(msg);
                        }
                        return uni.onItemOrFailure().transformToUni(handleInvocationResult(msg));
                    })
                    .onFailure().invoke(this::reportFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private void processMethodReturningASubscriber() {
        Object result = invoke();
        if (!(result instanceof Subscriber) && !(result instanceof SubscriberBuilder)) {
            throw ex.illegalStateExceptionForSubscriberOrSubscriberBuilder(result.getClass().getName());
        }

        if (configuration.consumption() == MediatorConfiguration.Consumption.STREAM_OF_PAYLOAD) {
            Subscriber<Object> userSubscriber;
            if (result instanceof Subscriber) {
                userSubscriber = (Subscriber<Object>) result;
            } else {
                userSubscriber = ((SubscriberBuilder<Object, Void>) result).build();
            }

            SubscriberWrapper<?, Message<?>> wrapper = new SubscriberWrapper<>(userSubscriber, Message::getPayload,
                    (m, t) -> {
                        if (configuration.getAcknowledgment() == Acknowledgment.Strategy.POST_PROCESSING) {
                            if (t != null) {
                                return m.nack(t);
                            } else {
                                return m.ack();
                            }
                        } else {
                            CompletableFuture<Void> future = new CompletableFuture<>();
                            if (t != null) {
                                future.completeExceptionally(t);
                            } else {
                                future.complete(null);
                            }
                            return future;
                        }
                    });

            this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration);
            this.subscriber = wrapper;
        } else {
            Subscriber<Message<?>> sub;
            if (result instanceof Subscriber) {
                sub = (Subscriber<Message<?>>) result;
            } else {
                sub = ((SubscriberBuilder<Message<?>, Void>) result).build();
            }
            this.function = upstream -> MultiUtils.handlePreProcessingAcknowledgement(upstream, configuration);
            this.subscriber = sub;
        }
    }
}
