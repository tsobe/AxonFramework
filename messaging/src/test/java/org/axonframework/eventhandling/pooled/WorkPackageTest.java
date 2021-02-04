package org.axonframework.eventhandling.pooled;

import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventhandling.GenericTrackedEventMessage;
import org.axonframework.eventhandling.GlobalSequenceTrackingToken;
import org.axonframework.eventhandling.Segment;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackerStatus;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.axonframework.utils.AssertUtils.assertWithin;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link WorkPackage}.
 *
 * @author Steven van Beelen
 */
class WorkPackageTest {

    private static final String PROCESSOR_NAME = "test";

    private TokenStore tokenStore;
    private ScheduledExecutorService scheduledExecutorService;
    private TestEventValidator eventValidator;
    private TestBatchProcessor batchProcessor;
    private Segment segment;
    private TrackingToken initialTrackingToken;

    private WorkPackage testSubject;

    private TrackerStatus trackerStatus;
    private List<TrackerStatus> trackerStatusUpdates;
    private Predicate<TrackedEventMessage<?>> eventValidatorPredicate;
    private Predicate<List<? extends EventMessage<?>>> batchProcessorPredicate;

    @BeforeEach
    void setUp() {
        tokenStore = spy(new InMemoryTokenStore());
        scheduledExecutorService = spy(Executors.newScheduledThreadPool(1));
        eventValidator = new TestEventValidator();
        batchProcessor = new TestBatchProcessor();
        segment = Segment.ROOT_SEGMENT;
        initialTrackingToken = new GlobalSequenceTrackingToken(0L);

        trackerStatus = new TrackerStatus(segment, initialTrackingToken);
        trackerStatusUpdates = new ArrayList<>();
        eventValidatorPredicate = event -> true;
        batchProcessorPredicate = event -> true;


        testSubject = new WorkPackage(
                PROCESSOR_NAME, tokenStore, NoTransactionManager.instance(), scheduledExecutorService, eventValidator,
                batchProcessor, segment, initialTrackingToken, 5000,
                op -> {
                    TrackerStatus update = op.apply(trackerStatus);
                    trackerStatusUpdates.add(update);
                    trackerStatus = update;
                }
        );
    }

    @AfterEach
    void tearDown() {
        scheduledExecutorService.shutdown();
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
    }

    @Test
    void testScheduleEventDoesNotScheduleAnythingIfTheEventsTokenIsAlreadyCovered() {
        TrackedEventMessage<String> testEvent = new GenericTrackedEventMessage<>(
                initialTrackingToken, GenericEventMessage.asEventMessage("some-event")
        );

        testSubject.scheduleEvent(testEvent);

        verifyNoInteractions(scheduledExecutorService);
    }

    @Test
    void testScheduleEventUpdatesLastDeliveredToken() {
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        TrackedEventMessage<String> testEvent =
                new GenericTrackedEventMessage<>(expectedToken, GenericEventMessage.asEventMessage("some-event"));

        testSubject.scheduleEvent(testEvent);

        assertEquals(expectedToken, testSubject.lastDeliveredToken());
    }

    @Test
    void testScheduleEventFailsOnEventValidator() throws ExecutionException, InterruptedException {
        TrackingToken testToken = new GlobalSequenceTrackingToken(1L);
        TrackedEventMessage<String> testEvent =
                new GenericTrackedEventMessage<>(testToken, GenericEventMessage.asEventMessage("some-event"));
        eventValidatorPredicate = event -> {
            if (event.equals(testEvent)) {
                throw new IllegalStateException("Some exception");
            }
            return true;
        };

        testSubject.scheduleEvent(testEvent);

        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertNull(trackerStatus));
        assertEquals(2, trackerStatusUpdates.size());
        assertTrue(trackerStatusUpdates.get(0).isErrorState());
        assertNull(trackerStatusUpdates.get(1));

        CompletableFuture<Exception> abortResult = testSubject.abort(null);
        assertTrue(abortResult.isDone());
        assertTrue(abortResult.get().getClass().isAssignableFrom(IllegalStateException.class));
    }

    @Test
    void testScheduleEventFailsOnBatchProcessor() throws ExecutionException, InterruptedException {
        TrackingToken testToken = new GlobalSequenceTrackingToken(1L);
        TrackedEventMessage<String> testEvent =
                new GenericTrackedEventMessage<>(testToken, GenericEventMessage.asEventMessage("some-event"));
        batchProcessorPredicate = event -> {
            if (event.contains(testEvent)) {
                throw new IllegalStateException("Some exception");
            }
            return true;
        };

        testSubject.scheduleEvent(testEvent);

        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertNull(trackerStatus));
        assertEquals(2, trackerStatusUpdates.size());
        assertTrue(trackerStatusUpdates.get(0).isErrorState());
        assertNull(trackerStatusUpdates.get(1));

        CompletableFuture<Exception> abortResult = testSubject.abort(null);
        assertTrue(abortResult.isDone());
        assertTrue(abortResult.get().getClass().isAssignableFrom(IllegalStateException.class));
    }

    /**
     * This means an event was scheduled, was validated to be handled by the EventValidator, processed by the
     * BatchProcessor and the updated token stored.
     */
    @Test
    void testScheduleEventRunsSuccessfully() {
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        TrackedEventMessage<String> expectedEvent =
                new GenericTrackedEventMessage<>(expectedToken, GenericEventMessage.asEventMessage("some-event"));

        testSubject.scheduleEvent(expectedEvent);

        List<EventMessage<?>> validatedEvents = eventValidator.getValidatedEvents();
        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertEquals(1, validatedEvents.size()));
        assertEquals(expectedEvent, validatedEvents.get(0));

        List<EventMessage<?>> processedEvents = batchProcessor.getProcessedEvents();
        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertEquals(1, processedEvents.size()));
        assertEquals(expectedEvent, processedEvents.get(0));

        ArgumentCaptor<TrackingToken> tokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);
        verify(tokenStore).storeToken(tokenCaptor.capture(), eq(PROCESSOR_NAME), eq(segment.getSegmentId()));
        assertEquals(expectedToken, tokenCaptor.getValue());

        assertEquals(1, trackerStatusUpdates.size());
        OptionalLong resultPosition = trackerStatusUpdates.get(0).getCurrentPosition();
        assertTrue(resultPosition.isPresent());
        assertEquals(1L, resultPosition.getAsLong());
    }

    @Test
    void testScheduleEventExtendsTokenClaimAfterClaimThresholdExtension() {
        // The short threshold ensures the packages assume the token should be reclaimed.
        int extremelyShortClaimExtensionThreshold = 1;
        WorkPackage testSubjectWithShortThreshold = new WorkPackage(
                PROCESSOR_NAME, tokenStore, NoTransactionManager.instance(), scheduledExecutorService, eventValidator,
                batchProcessor, segment, initialTrackingToken, extremelyShortClaimExtensionThreshold,
                op -> {
                    TrackerStatus update = op.apply(trackerStatus);
                    trackerStatusUpdates.add(update);
                    trackerStatus = update;
                }
        );

        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        TrackedEventMessage<String> expectedEvent =
                new GenericTrackedEventMessage<>(expectedToken, GenericEventMessage.asEventMessage("some-event"));
        testSubjectWithShortThreshold.scheduleEvent(expectedEvent);

        // Should have handled one event, so a subsequent run of WorkPackage#processEvents will extend the claim.
        List<EventMessage<?>> processedEvents = batchProcessor.getProcessedEvents();
        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertEquals(1, processedEvents.size()));
        assertEquals(expectedEvent, processedEvents.get(0));
        // We  need to verify the TokenStore#storeToken operation, otherwise the extendClaim verify will not succeed.
        ArgumentCaptor<TrackingToken> tokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);
        verify(tokenStore).storeToken(tokenCaptor.capture(), eq(PROCESSOR_NAME), eq(segment.getSegmentId()));
        assertEquals(expectedToken, tokenCaptor.getValue());

        // Consciously trigger the WorkPackage again, to force it through WorkPackage#processEvents
        testSubjectWithShortThreshold.scheduleWorker();

        assertWithin(
                500, TimeUnit.MILLISECONDS,
                () -> verify(tokenStore).extendClaim(PROCESSOR_NAME, segment.getSegmentId())
        );
    }

    /**
     * This requires the WorkPackage to have received events which it should not handle.
     */
    @Test
    void testScheduleEventUpdatesTokenAfterClaimThresholdExtension() {
        // The short threshold ensures the packages assume the token should be reclaimed.
        int extremelyShortClaimExtensionThreshold = 1;
        WorkPackage testSubjectWithShortThreshold = new WorkPackage(
                PROCESSOR_NAME, tokenStore, NoTransactionManager.instance(), scheduledExecutorService, eventValidator,
                batchProcessor, segment, initialTrackingToken, extremelyShortClaimExtensionThreshold,
                op -> {
                    TrackerStatus update = op.apply(trackerStatus);
                    trackerStatusUpdates.add(update);
                    trackerStatus = update;
                }
        );
        // Adjust the EventValidator to reject all events
        eventValidatorPredicate = event -> false;

        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        TrackedEventMessage<String> expectedEvent =
                new GenericTrackedEventMessage<>(expectedToken, GenericEventMessage.asEventMessage("some-event"));
        testSubjectWithShortThreshold.scheduleEvent(expectedEvent);

        List<EventMessage<?>> validatedEvents = eventValidator.getValidatedEvents();
        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertEquals(1, validatedEvents.size()));
        assertEquals(expectedEvent, validatedEvents.get(0));

        ArgumentCaptor<TrackingToken> tokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);
        assertWithin(
                500, TimeUnit.MILLISECONDS,
                () -> verify(tokenStore).storeToken(tokenCaptor.capture(), eq(PROCESSOR_NAME), eq(segment.getSegmentId()))
        );
        assertEquals(expectedToken, tokenCaptor.getValue());
    }

    @Test
    void testScheduleWorkerForAbortedPackage() throws ExecutionException, InterruptedException {
        CompletableFuture<Exception> result = testSubject.abort(null);

        testSubject.scheduleWorker();

        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertNull(trackerStatus));
        assertTrue(result.isDone());
        assertNull(result.get());
    }

    @Test
    void testHasRemainingCapacityReturnsTrueForWorkPackageWithoutScheduledEvents() {
        assertTrue(testSubject.hasRemainingCapacity());
    }

    @Test
    void testSegment() {
        assertEquals(segment, testSubject.segment());
    }

    @Test
    void testLastDeliveredTokenEqualsInitialTokenWhenNoEventsHaveBeenScheduled() {
        assertEquals(initialTrackingToken, testSubject.lastDeliveredToken());
    }

    @Test
    void testIsAbortTriggeredReturnsFalseInAbsenceOfAbort() {
        assertFalse(testSubject.isAbortTriggered());
    }

    @Test
    void testIsAbortTriggeredReturnsTrueAfterAbortInvocation() {
        testSubject.abort(null);
        assertTrue(testSubject.isAbortTriggered());
    }

    @Test
    void testAbortReturnsAbortReason() throws ExecutionException, InterruptedException {
        Exception expectedResult = new IllegalStateException();

        CompletableFuture<Exception> result = testSubject.abort(expectedResult);

        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertTrue(result.isDone()));
        assertEquals(expectedResult, result.get());
    }

    @Test
    void testAbortReturnsOriginalAbortReason() throws ExecutionException, InterruptedException {
        Exception originalAbortReason = new IllegalStateException();
        Exception otherAbortReason = new IllegalArgumentException();
        testSubject.abort(originalAbortReason);

        CompletableFuture<Exception> result = testSubject.abort(otherAbortReason);

        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertTrue(result.isDone()));
        assertEquals(originalAbortReason, result.get());
    }

    @Test
    void testStopPackageReturnsLastProcessedEventsToken() {
        // Make sure we have at least let it store something once.
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(1L);
        TrackedEventMessage<String> expectedEvent =
                new GenericTrackedEventMessage<>(expectedToken, GenericEventMessage.asEventMessage("some-event"));
        testSubject.scheduleEvent(expectedEvent);
        // Make sure it has been processed...
        List<EventMessage<?>> processedEvents = batchProcessor.getProcessedEvents();
        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertEquals(1, processedEvents.size()));
        assertEquals(expectedEvent, processedEvents.get(0));

        // ...prior to stopping the package.
        CompletableFuture<TrackingToken> result = testSubject.stopPackage();

        assertWithin(500, TimeUnit.MILLISECONDS, () -> {
            try {
                assertEquals(expectedToken, result.get());
            } catch (InterruptedException | ExecutionException e) {
                // Shouldn't happen at all...
            }
        });
    }

    private class TestEventValidator implements WorkPackage.EventValidator {

        private final List<EventMessage<?>> validatedEvents = new ArrayList<>();

        @Override
        public boolean shouldHandle(TrackedEventMessage<?> eventMessage, Segment segment) {
            validatedEvents.add(eventMessage);
            return eventValidatorPredicate.test(eventMessage);
        }

        public List<EventMessage<?>> getValidatedEvents() {
            return validatedEvents;
        }
    }

    private class TestBatchProcessor implements WorkPackage.BatchProcessor {

        private final List<EventMessage<?>> processedEvents = new ArrayList<>();

        @Override
        public void processBatch(List<? extends EventMessage<?>> eventMessages,
                                 UnitOfWork<? extends EventMessage<?>> unitOfWork,
                                 Collection<Segment> processingSegments) {
            if (batchProcessorPredicate.test(eventMessages)) {
                unitOfWork.executeWithResult(() -> {
                    unitOfWork.commit();
                    processedEvents.addAll(eventMessages);
                    // We don't care about the result to perform our tests. Just return null.
                    return null;
                });
            }
        }

        public List<EventMessage<?>> getProcessedEvents() {
            return processedEvents;
        }
    }
}