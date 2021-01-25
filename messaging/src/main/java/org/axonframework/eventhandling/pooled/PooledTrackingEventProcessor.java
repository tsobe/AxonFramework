package org.axonframework.eventhandling.pooled;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.AbstractEventProcessor;
import org.axonframework.eventhandling.ErrorHandler;
import org.axonframework.eventhandling.EventHandlerInvoker;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.EventProcessor;
import org.axonframework.eventhandling.EventTrackerStatus;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventhandling.PropagatingErrorHandler;
import org.axonframework.eventhandling.Segment;
import org.axonframework.eventhandling.SegmentedEventProcessor;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackerStatus;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.lifecycle.Phase;
import org.axonframework.lifecycle.ShutdownHandler;
import org.axonframework.lifecycle.StartHandler;
import org.axonframework.messaging.StreamableMessageSource;
import org.axonframework.messaging.unitofwork.RollbackConfiguration;
import org.axonframework.messaging.unitofwork.RollbackConfigurationType;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.BuilderUtils.assertStrictPositive;

/**
 * A special type of Event Processor that tracks events from a {@link StreamableMessageSource}, similar to the {@link
 * org.axonframework.eventhandling.TrackingEventProcessor}, but that does all processing.
 *
 * @author Allard Buijze
 * @since 4.5
 */
public class PooledTrackingEventProcessor extends AbstractEventProcessor implements SegmentedEventProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PooledTrackingEventProcessor.class);

    private final String name;
    private final StreamableMessageSource<TrackedEventMessage<?>> messageSource;
    private final TokenStore tokenStore;
    private final TransactionManager transactionManager;
    private final int initialSegmentCount;
    private final Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialToken;
    private final ScheduledExecutorService workerExecutor;
    private final Coordinator coordinator;

    private final AtomicReference<String> tokenStoreIdentifier = new AtomicReference<>();
    private final Map<Integer, TrackerStatus> processingStatus = new ConcurrentHashMap<>();

    /**
     * Instantiate a Builder to be able to create a {@link PooledTrackingEventProcessor}.
     * <p>
     * Upon initialization of this builder, the following fields are defaulted:
     * <ul>
     *     <li>The {@link RollbackConfigurationType} defaults to a {@link RollbackConfigurationType#ANY_THROWABLE}.</li>
     *     <li>The {@link ErrorHandler} is defaulted to a {@link PropagatingErrorHandler}.</li>
     *     <li>The {@link MessageMonitor} defaults to a {@link NoOpMessageMonitor}.</li>
     *     <li>The {@code initialSegmentCount} defaults to {@code 32}.</li>
     *     <li>The {@code initialToken} function defaults to {@link StreamableMessageSource#createTailToken()}.</li>
     * </ul>
     * The following fields of this builder are <b>hard requirements</b> and as such should be provided:
     * <ul>
     *     <li>The name of this {@link EventProcessor}.</li>
     *     <li>An {@link EventHandlerInvoker} which will be given the events handled by this processor</li>
     *     <li>A {@link StreamableMessageSource} used to retrieve events.</li>
     *     <li>A {@link TokenStore} to store the progress of this processor in.</li>
     *     <li>A {@link TransactionManager} to perform all event handling inside transactions.</li>
     *     <li>A {@link ScheduledExecutorService} used by the {@link Coordinator} of this processor.</li>
     *     <li>A {@link ScheduledExecutorService} given to the {@link WorkPackage}s created by this processor.</li>
     * </ul>
     *
     * @return a Builder to be able to create a {@link PooledTrackingEventProcessor}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Instantiate a {@link PooledTrackingEventProcessor} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert the following for their presence prior to constructing this processor:
     * <ul>
     *     <li>The Event Processor' {@code name}.</li>
     *     <li>An {@link EventHandlerInvoker}.</li>
     *     <li>A {@link StreamableMessageSource}.</li>
     *     <li>A {@link TokenStore}.</li>
     *     <li>A {@link TransactionManager}.</li>
     *     <li>A {@link ScheduledExecutorService} for the {@link Coordinator}.</li>
     *     <li>A {@link ScheduledExecutorService} for the created {@link WorkPackage}s.</li>
     * </ul>
     * If any of these is not present or does no comply to the requirements an {@link AxonConfigurationException} is thrown.
     *
     * @param builder the {@link Builder} used to instantiate a {@link PooledTrackingEventProcessor} instance
     */
    protected PooledTrackingEventProcessor(PooledTrackingEventProcessor.Builder builder) {
        super(builder);
        this.name = builder.name();
        this.messageSource = builder.messageSource;
        this.tokenStore = builder.tokenStore;
        this.transactionManager = builder.transactionManager;
        this.initialSegmentCount = builder.initialSegmentCount;
        this.initialToken = builder.initialToken;
        this.workerExecutor = builder.workerExecutor;
        this.coordinator = new Coordinator(
                name, messageSource, tokenStore, transactionManager, this::spawnWorker, builder.coordinatorExecutor,
                (i, up) -> processingStatus.compute(i, (s, ts) -> up.apply(ts))
        );
    }

    @StartHandler(phase = Phase.INBOUND_EVENT_CONNECTORS)
    @Override
    public void start() {
        logger.info("PooledTrackingEventProcessor {} starting", name);
        transactionManager.executeInTransaction(() -> {
            int[] ints = tokenStore.fetchSegments(name);
            if (ints == null || ints.length == 0) {
                logger.info("Initializing segments for {} ({} segments)", name, 8);
                tokenStore.initializeTokenSegments(name, initialSegmentCount, initialToken.apply(messageSource));
            }
        });
        coordinator.start();
    }

    @Override
    public boolean isRunning() {
        return coordinator.isRunning();
    }

    @Override
    public boolean isError() {
        return coordinator.isError();
    }

    @Override
    public void shutDown() {
        shutdownAsync().join();
    }

    @ShutdownHandler(phase = Phase.INBOUND_EVENT_CONNECTORS)
    @Override
    public CompletableFuture<Void> shutdownAsync() {
        return coordinator.stop();
    }

    @Override
    public String getTokenStoreIdentifier() {
        return tokenStoreIdentifier.updateAndGet(i -> i != null ? i : calculateIdentifier());
    }

    private String calculateIdentifier() {
        return transactionManager.fetchInTransaction(
                () -> tokenStore.retrieveStorageIdentifier().orElse("--unknown--")
        );
    }

    @Override
    public void releaseSegment(int segmentId) {
        // TODO - Use twice the claim interval
        releaseSegment(segmentId, 5000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void releaseSegment(int segmentId, long releaseDuration, TimeUnit unit) {
        coordinator.releaseUntil(
                segmentId, GenericEventMessage.clock.instant().plusMillis(unit.toMillis(releaseDuration))
        );
    }

    // TODO: 22-01-21 implement
    @Override
    public CompletableFuture<Boolean> splitSegment(int segmentId) {
        return CompletableFuture.completedFuture(false);
    }

    // TODO: 22-01-21 implement
    @Override
    public CompletableFuture<Boolean> mergeSegment(int segmentId) {
        return CompletableFuture.completedFuture(false);
    }

    // TODO: 22-01-21 implement
    @Override
    public boolean supportsReset() {
        return false;
    }

    @Override
    public void resetTokens() {
        // TODO - implement
    }

    @Override
    public <R> void resetTokens(R resetContext) {
        // TODO - implement
    }

    @Override
    public void resetTokens(
            Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialTrackingTokenSupplier) {
        // TODO - implement
    }

    @Override
    public <R> void resetTokens(
            Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialTrackingTokenSupplier,
            R resetContext
    ) {
        // TODO - implement
    }

    @Override
    public void resetTokens(TrackingToken startPosition) {
        // TODO - implement
    }

    @Override
    public <R> void resetTokens(TrackingToken startPosition, R resetContext) {
        // TODO - implement
    }

    @Override
    public int maxCapacity() {
        return Short.MAX_VALUE;
    }

    @Override
    public Map<Integer, EventTrackerStatus> processingStatus() {
        return Collections.unmodifiableMap(processingStatus);
    }

    private WorkPackage spawnWorker(Segment segment, TrackingToken initialToken) {
        processingStatus.putIfAbsent(segment.getSegmentId(), new TrackerStatus(segment, initialToken));
        return new WorkPackage(name,
                               segment,
                               initialToken,
                               this::processInUnitOfWork,
                               this::canHandle,
                               workerExecutor,
                               tokenStore,
                               transactionManager,
                               u -> processingStatus.compute(segment.getSegmentId(), (s, status) -> u.apply(status)));
    }

    /**
     * Builder class to instantiate a {@link PooledTrackingEventProcessor}.
     * <p>
     * Upon initialization of this builder, the following fields are defaulted:
     * <ul>
     *     <li>The {@link RollbackConfigurationType} defaults to a {@link RollbackConfigurationType#ANY_THROWABLE}.</li>
     *     <li>The {@link ErrorHandler} is defaulted to a {@link PropagatingErrorHandler}.</li>
     *     <li>The {@link MessageMonitor} defaults to a {@link NoOpMessageMonitor}.</li>
     *     <li>The {@code initialSegmentCount} defaults to {@code 32}.</li>
     *     <li>The {@code initialToken} function defaults to {@link StreamableMessageSource#createTailToken()}.</li>
     * </ul>
     * The following fields of this builder are <b>hard requirements</b> and as such should be provided:
     * <ul>
     *     <li>The name of this {@link EventProcessor}.</li>
     *     <li>An {@link EventHandlerInvoker} which will be given the events handled by this processor</li>
     *     <li>A {@link StreamableMessageSource} used to retrieve events.</li>
     *     <li>A {@link TokenStore} to store the progress of this processor in.</li>
     *     <li>A {@link TransactionManager} to perform all event handling inside transactions.</li>
     *     <li>A {@link ScheduledExecutorService} used by the {@link Coordinator} of this processor.</li>
     *     <li>A {@link ScheduledExecutorService} given to the {@link WorkPackage}s created by this processor.</li>
     * </ul>
     */
    public static class Builder extends AbstractEventProcessor.Builder {

        private StreamableMessageSource<TrackedEventMessage<?>> messageSource;
        private TokenStore tokenStore;
        private TransactionManager transactionManager;
        private int initialSegmentCount = 32;
        private Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialToken =
                StreamableMessageSource::createTailToken;
        // TODO: 22-01-21 Do we want a default executor service for both?
        private ScheduledExecutorService coordinatorExecutor;
        private ScheduledExecutorService workerExecutor;

        protected Builder() {
            rollbackConfiguration(RollbackConfigurationType.ANY_THROWABLE);
        }

        @Override
        public Builder name(String name) {
            super.name(name);
            return this;
        }

        @Override
        public Builder eventHandlerInvoker(EventHandlerInvoker eventHandlerInvoker) {
            super.eventHandlerInvoker(eventHandlerInvoker);
            return this;
        }

        @Override
        public Builder rollbackConfiguration(RollbackConfiguration rollbackConfiguration) {
            super.rollbackConfiguration(rollbackConfiguration);
            return this;
        }

        @Override
        public Builder errorHandler(ErrorHandler errorHandler) {
            super.errorHandler(errorHandler);
            return this;
        }

        @Override
        public Builder messageMonitor(MessageMonitor<? super EventMessage<?>> messageMonitor) {
            super.messageMonitor(messageMonitor);
            return this;
        }

        /**
         * Sets the {@link StreamableMessageSource} (e.g. the {@code EventStore}) which this {@link EventProcessor} will
         * track.
         *
         * @param messageSource the {@link StreamableMessageSource} (e.g. the {@code EventStore}) which this {@link
         *                      EventProcessor} will track
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder messageSource(StreamableMessageSource<TrackedEventMessage<?>> messageSource) {
            assertNonNull(messageSource, "StreamableMessageSource may not be null");
            this.messageSource = messageSource;
            return this;
        }

        /**
         * Sets the {@link TokenStore} used to store and fetch event tokens that enable this {@link EventProcessor} to
         * track its progress.
         *
         * @param tokenStore the {@link TokenStore} used to store and fetch event tokens that enable this {@link
         *                   EventProcessor} to track its progress
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder tokenStore(TokenStore tokenStore) {
            assertNonNull(tokenStore, "TokenStore may not be null");
            this.tokenStore = tokenStore;
            return this;
        }

        /**
         * Sets the {@link TransactionManager} used when processing {@link EventMessage}s.
         *
         * @param transactionManager the {@link TransactionManager} used when processing {@link EventMessage}s
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder transactionManager(TransactionManager transactionManager) {
            assertNonNull(transactionManager, "TransactionManager may not be null");
            this.transactionManager = transactionManager;
            return this;
        }

        /**
         * Sets the initial segment count used to create segments on start up. Only used whenever there are not segments
         * stored in the configured {@link TokenStore} upon start up of this {@link SegmentedEventProcessor}. The given
         * value should at least be {@code 1}. Defaults to {@code 32}.
         *
         * @param initialSegmentCount an {@code int} specifying the initial segment count used to create segments on
         *                            start up
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder initialSegmentCount(int initialSegmentCount) {
            assertStrictPositive(initialSegmentCount, "The initialSegmentCount should be a higher valuer than zero");
            this.initialSegmentCount = initialSegmentCount;
            return this;
        }

        /**
         * Specifies the {@link Function} used to generate the initial {@link TrackingToken}s. The function will be
         * given the configured {@link StreamableMessageSource}' so that its methods can be invoked for token creation.
         * Defaults to {@link StreamableMessageSource#createTailToken()}.
         *
         * @param initialToken a {@link Function} generating the initial {@link TrackingToken} based on a given {@link
         *                     StreamableMessageSource}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder initialToken(
                Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialToken
        ) {
            assertNonNull(initialToken, "The initial token builder Function may not be null");
            this.initialToken = initialToken;
            return this;
        }

        /**
         * Specifies the {@link ScheduledExecutorService} used by the {@link Coordinator} of this {@link
         * PooledTrackingEventProcessor}.
         *
         * @param coordinatorExecutor a {@link ScheduledExecutorService} to be used by the {@link Coordinator} of this
         *                            {@link PooledTrackingEventProcessor}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder coordinatorExecutor(ScheduledExecutorService coordinatorExecutor) {
            assertNonNull(coordinatorExecutor, "The Coordinator's ScheduledExecutorService may not be null");
            this.coordinatorExecutor = coordinatorExecutor;
            return this;
        }

        /**
         * Specifies the {@link ScheduledExecutorService} provided to {@link WorkPackage}s created by this {@link
         * PooledTrackingEventProcessor}.
         *
         * @param workerExecutor a {@link ScheduledExecutorService} provided to {@link WorkPackage}s created  by this
         *                       {@link PooledTrackingEventProcessor}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder workerExecutorService(ScheduledExecutorService workerExecutor) {
            assertNonNull(workerExecutor, "The Worker's ScheduledExecutorService may not be null");
            this.workerExecutor = workerExecutor;
            return this;
        }

        /**
         * Initializes a {@link PooledTrackingEventProcessor} as specified through this Builder.
         *
         * @return a {@link PooledTrackingEventProcessor} as specified through this Builder
         */
        public PooledTrackingEventProcessor build() {
            return new PooledTrackingEventProcessor(this);
        }

        @Override
        protected void validate() throws AxonConfigurationException {
            super.validate();
            assertNonNull(messageSource, "The StreamableMessageSource is a hard requirement and should be provided");
            assertNonNull(tokenStore, "The TokenStore is a hard requirement and should be provided");
            assertNonNull(transactionManager, "The TransactionManager is a hard requirement and should be provided");
            assertNonNull(
                    coordinatorExecutor,
                    "The Coordinator's ScheduledExecutorService is a hard requirement and should be provided"
            );
            assertNonNull(
                    workerExecutor, "The Worker's ScheduledExecutorService is a hard requirement and should be provided"
            );
        }

        /**
         * Returns the name of this {@link PooledTrackingEventProcessor}.
         *
         * @return the name of this {@link PooledTrackingEventProcessor}
         */
        protected String name() {
            return name;
        }
    }
}