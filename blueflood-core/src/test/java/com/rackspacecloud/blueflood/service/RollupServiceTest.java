package com.rackspacecloud.blueflood.service;

import com.rackspacecloud.blueflood.rollup.Granularity;
import com.rackspacecloud.blueflood.rollup.SlotKey;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.*;

public class RollupServiceTest {

    ScheduleContext context;
    ShardStateManager shardStateManager;
    ThreadPoolExecutor locatorFetchExecutors;
    ThreadPoolExecutor rollupReadExecutors;
    ThreadPoolExecutor rollupWriteExecutors;
    ThreadPoolExecutor enumValidatorExecutor;
    long rollupDelayMillis;
    long delayedMetricRollupDelayMillis;
    long pollerPeriod;
    long configRefreshInterval;

    RollupService service;

    @Before
    public void setUp() {

        context = mock(ScheduleContext.class);
        shardStateManager = mock(ShardStateManager.class);
        locatorFetchExecutors = mock(ThreadPoolExecutor.class);
        rollupReadExecutors = mock(ThreadPoolExecutor.class);
        rollupWriteExecutors = mock(ThreadPoolExecutor.class);
        enumValidatorExecutor = mock(ThreadPoolExecutor.class);
        rollupDelayMillis = 300000;
        delayedMetricRollupDelayMillis = 300000;
        pollerPeriod = 0;
        configRefreshInterval = 10000;

        service = new RollupService(context, shardStateManager,
                locatorFetchExecutors, rollupReadExecutors,
                rollupWriteExecutors, enumValidatorExecutor, rollupDelayMillis,
                delayedMetricRollupDelayMillis, pollerPeriod,
                configRefreshInterval);
    }

    @Test
    public void pollSchedulesEligibleSlots() {

        // when
        service.poll();

        // then
        verify(context).scheduleEligibleSlots(anyLong(), anyLong());
        verifyNoMoreInteractions(context);

        verifyZeroInteractions(shardStateManager);
        verifyZeroInteractions(locatorFetchExecutors);
        verifyZeroInteractions(rollupReadExecutors);
        verifyZeroInteractions(rollupWriteExecutors);
        verifyZeroInteractions(enumValidatorExecutor);
    }

    @Test
    public void runSingleSlotDequeuesAndExecutes() {

        // given
        when(context.hasScheduled()).thenReturn(true).thenReturn(false);

        SlotKey slotkey = SlotKey.of(Granularity.MIN_20, 2, 0);
        doReturn(slotkey).when(context).getNextScheduled();

        final Runnable[] capture = new Runnable[1];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                capture[0] = (Runnable)invocationOnMock.getArguments()[0];

                // now that the runnable has been queued, stop the outer loop
                service.setShouldKeepRunning(false);

                return null;
            }
        }).when(locatorFetchExecutors).execute(Matchers.<Runnable>any());


        // when
        service.run();

        // then
        verify(context).scheduleEligibleSlots(anyLong(), anyLong());  // from poll
        verify(context, times(2)).hasScheduled();
        verify(context).getNextScheduled();
        verify(context, times(2)).getCurrentTimeMillis();  // one from LocatorFetchRunnable ctor, one from run
        verifyNoMoreInteractions(context);

        verifyZeroInteractions(shardStateManager);

        verify(locatorFetchExecutors).execute(Matchers.<Runnable>any());
        assertSame(LocatorFetchRunnable.class, capture[0].getClass());
        verifyNoMoreInteractions(locatorFetchExecutors);

        verifyZeroInteractions(rollupReadExecutors);
        verifyZeroInteractions(rollupWriteExecutors);
        verifyZeroInteractions(enumValidatorExecutor);
    }

    @Test
    public void ifTheExecutionIsRejectedThenTheSlotKeyIsPushedBack() {

        // given
        when(context.hasScheduled()).thenReturn(true).thenReturn(false);

        SlotKey slotkey = SlotKey.of(Granularity.MIN_20, 2, 0);
        doReturn(slotkey).when(context).getNextScheduled();

        final RejectedExecutionException cause = new RejectedExecutionException("exception for testing purposes");
        doAnswer(new Answer() {
             @Override
             public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                 // now we're inside the loop, stop the outer loop
                 service.setShouldKeepRunning(false);

                 throw cause;
             }
        }).when(locatorFetchExecutors).execute(Matchers.<Runnable>any());

        // when
        service.run();

        // then
        verify(context).scheduleEligibleSlots(anyLong(), anyLong());  // from poll
        verify(context, times(2)).hasScheduled();
        verify(context).getNextScheduled();
        verify(context, times(2)).getCurrentTimeMillis();  // one from LocatorFetchRunnable ctor, one from run
        verify(context).pushBackToScheduled(Matchers.<SlotKey>any(), anyBoolean());
        verifyNoMoreInteractions(context);

        verifyZeroInteractions(shardStateManager);

        verify(locatorFetchExecutors).execute(Matchers.<Runnable>any());
        verifyNoMoreInteractions(locatorFetchExecutors);

        verifyZeroInteractions(rollupReadExecutors);
        verifyZeroInteractions(rollupWriteExecutors);
        verifyZeroInteractions(enumValidatorExecutor);
    }

    @Test
    public void setServerTimeSetsContextTime() {

        //when
        service.setServerTime(1234L);

        // then
        verify(context).setCurrentTimeMillis(anyLong());
        verifyNoMoreInteractions(context);

        verifyZeroInteractions(shardStateManager);
        verifyZeroInteractions(locatorFetchExecutors);
        verifyZeroInteractions(rollupReadExecutors);
        verifyZeroInteractions(rollupWriteExecutors);
        verifyZeroInteractions(enumValidatorExecutor);
    }

    @Test
    public void getServerTimeGetsContextTime() {

        // given
        long expected = 1234L;
        doReturn(expected).when(context).getCurrentTimeMillis();

        // when
        long actual = service.getServerTime();

        // then
        assertEquals(expected, actual);

        verify(context).getCurrentTimeMillis();
        verifyNoMoreInteractions(context);

        verifyZeroInteractions(shardStateManager);
        verifyZeroInteractions(locatorFetchExecutors);
        verifyZeroInteractions(rollupReadExecutors);
        verifyZeroInteractions(rollupWriteExecutors);
        verifyZeroInteractions(enumValidatorExecutor);
    }

    @Test
    public void getKeepingServerTimeGetsKeepingServerTime() {

        // expect
        assertEquals(true, service.getKeepingServerTime());
    }

    @Test
    public void setKeepingServerTimeSetsKeepingServerTime() {

        // precondition
        assertEquals(true, service.getKeepingServerTime());

        // when
        service.setKeepingServerTime(false);

        // then
        assertEquals(false, service.getKeepingServerTime());
    }

    @Test
    public void getPollerPeriodGetsPollerPeriod() {

        // expect
        assertEquals(0, service.getPollerPeriod());
    }

    @Test
    public void setPollerPeriodSetsPollerPeriod() {

        // precondition
        assertEquals(0, service.getPollerPeriod());

        // when
        service.setPollerPeriod(1234L);

        // then
        assertEquals(1234L, service.getPollerPeriod());
    }

    @Test
    public void getScheduledSlotCheckCountGetsCount() {

        // given
        int expected = 3;
        doReturn(expected).when(context).getScheduledCount();

        // when
        int actual = service.getScheduledSlotCheckCount();

        // then
        assertEquals(expected, actual);

        verify(context).getScheduledCount();
        verifyNoMoreInteractions(context);
    }

    @Test
    public void testGetSlotCheckConcurrency() {

        // given
        int expected = 12;
        doReturn(expected).when(locatorFetchExecutors).getMaximumPoolSize();

        // when
        int actual = service.getSlotCheckConcurrency();

        // then
        assertEquals(expected, actual);

        verify(locatorFetchExecutors).getMaximumPoolSize();
        verifyNoMoreInteractions(locatorFetchExecutors);
    }

    @Test
    public void testSetSlotCheckConcurrency() {

        // when
        service.setSlotCheckConcurrency(3);

        // then
        verify(locatorFetchExecutors).setCorePoolSize(anyInt());
        verify(locatorFetchExecutors).setMaximumPoolSize(anyInt());
        verifyNoMoreInteractions(locatorFetchExecutors);
    }

    @Test
    public void testGetRollupConcurrency() {

        // given
        int expected = 12;
        doReturn(expected).when(rollupReadExecutors).getMaximumPoolSize();

        // when
        int actual = service.getRollupConcurrency();

        // then
        assertEquals(expected, actual);

        verify(rollupReadExecutors).getMaximumPoolSize();
        verifyNoMoreInteractions(rollupReadExecutors);
    }

    @Test
    public void testSetRollupConcurrency() {

        // when
        service.setRollupConcurrency(3);

        // then
        verify(rollupReadExecutors).setCorePoolSize(anyInt());
        verify(rollupReadExecutors).setMaximumPoolSize(anyInt());
        verifyNoMoreInteractions(rollupReadExecutors);
    }

    @Test
    public void getQueuedRollupCountReturnsQueueSize() {

        //given
        BlockingQueue<Runnable> queue = mock(BlockingQueue.class);
        int expected1 = 123;
        int expected2 = 45;
        when(queue.size()).thenReturn(expected1).thenReturn(expected2);
        when(rollupReadExecutors.getQueue()).thenReturn(queue);

        // when
        int count = service.getQueuedRollupCount();

        // then
        assertEquals(expected1, count);

        // when
        count = service.getQueuedRollupCount();

        // then
        assertEquals(expected2, count);
    }

    @Test
    public void testGetInFlightRollupCount() {

        //given
        int expected1 = 123;
        int expected2 = 45;
        when(rollupReadExecutors.getActiveCount())
                .thenReturn(expected1)
                .thenReturn(expected2);

        // when
        int count = service.getInFlightRollupCount();

        // then
        assertEquals(expected1, count);

        // when
        count = service.getInFlightRollupCount();

        // then
        assertEquals(expected2, count);
    }

    @Test
    public void getActiveGetsActiveFlag() {

        // expect
        assertEquals(true, service.getActive());
    }

    @Test
    public void setActiveSetsActiveFlag() {

        // precondition
        assertEquals(true, service.getActive());

        // when
        service.setActive(false);

        // then
        assertEquals(false, service.getActive());
    }

    @Test
    public void addShardDoesNotAddShardsAlreadyManaged() {

        // given
        HashSet<Integer> managedShards = new HashSet<Integer>();
        managedShards.add(0);
        doReturn(managedShards).when(shardStateManager).getManagedShards();

        // when
        service.addShard(0);

        // then
        verifyZeroInteractions(context);
    }

    @Test
    public void addShardAddsShardsNotYetManaged() {

        // given
        HashSet<Integer> managedShards = new HashSet<Integer>();
        doReturn(managedShards).when(shardStateManager).getManagedShards();

        // when
        service.addShard(0);

        // then
        verify(context).addShard(anyInt());
        verifyNoMoreInteractions(context);
    }

    @Test
    public void removeShardRemovesManagedShards() {

        // given
        HashSet<Integer> managedShards = new HashSet<Integer>();
        managedShards.add(0);
        doReturn(managedShards).when(shardStateManager).getManagedShards();

        // when
        service.removeShard(0);

        // then
        verify(context).removeShard(anyInt());
        verifyNoMoreInteractions(context);
    }

    @Test
    public void removeShardDoesNotRemovesShardsNotManaged() {

        // given
        HashSet<Integer> managedShards = new HashSet<Integer>();
        doReturn(managedShards).when(shardStateManager).getManagedShards();

        // when
        service.removeShard(0);

        // then
        verifyZeroInteractions(context);
    }

    @Test
    public void getManagedShardsGetsCollectionFromManager() {

        // given
        HashSet<Integer> managedShards = new HashSet<Integer>();
        managedShards.add(0);
        managedShards.add(1);
        managedShards.add(2);
        doReturn(managedShards).when(shardStateManager).getManagedShards();

        // when
        Collection<Integer> actual = service.getManagedShards();

        // then
        assertEquals(managedShards.size(), actual.size());
        assertTrue(actual.containsAll(managedShards));

        verify(shardStateManager).getManagedShards();
        verifyNoMoreInteractions(shardStateManager);
    }

    @Test
    public void getRecentlyScheduledShardsGetsFromContext() {

        // when
        Collection<Integer> recent = service.getRecentlyScheduledShards();

        // then
        assertNotNull(recent);

        verify(context).getRecentlyScheduledShards();
        verifyNoMoreInteractions(context);

        verifyZeroInteractions(shardStateManager);
    }
}
