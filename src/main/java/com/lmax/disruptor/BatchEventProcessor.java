/*
 * Copyright 2022 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.atomic.AtomicInteger;

import static com.lmax.disruptor.RewindAction.REWIND;
import static java.lang.Math.min;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
        implements EventProcessor
{
    /*
     * 状态常量,定义处理器的三种生命状态
     *  - IDLE: 空闲状态, 处理器未运行
     *  - HALTED: 已停止状态, 处理器被主动停止
     *  - RUNNING: 运行状态, 处理器正在运行
     */
    private static final int IDLE = 0;
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    private final AtomicInteger running = new AtomicInteger(IDLE); // 初始值为IDLE
    private ExceptionHandler<? super T> exceptionHandler; // 事件处理过程中发生的异常由该异常处理器处理
    private final DataProvider<T> dataProvider; // 通常是ringBuffer,需要通过该dataProvider获取(然后才是处理事件)
    private final SequenceBarrier sequenceBarrier; // 协调消费者与生产者(上游消费者)之间的同步关系
    private final EventHandlerBase<? super T> eventHandler; // 用户提供事件处理器
    private final int batchLimitOffset; // 限制单批次处理的最大事件数量
    /*
        记录当前消费者已处理的最大序号
        同时作为生产者的gating sequence，防止覆盖未处理的事件
        协调依赖:下游消费者可以通过此序列来判断是否可以处理
    */
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final RewindHandler rewindHandler; // 处理可重试异常的回退逻辑,用来支持事件处理失败后的重试
    private int retriesAttempted = 0; // 记录当前事件的重试次数

    BatchEventProcessor(
            final DataProvider<T> dataProvider,
            final SequenceBarrier sequenceBarrier,
            final EventHandlerBase<? super T> eventHandler,
            final int maxBatchSize,
            final BatchRewindStrategy batchRewindStrategy
    )
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (maxBatchSize < 1)
        {
            throw new IllegalArgumentException("maxBatchSize must be greater than 0");
        }
        this.batchLimitOffset = maxBatchSize - 1;

        this.rewindHandler = eventHandler instanceof RewindableEventHandler
                ? new TryRewindHandler(batchRewindStrategy)
                : new NoRewindHandler();
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        running.set(HALTED);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get() != IDLE;
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}.
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    // core logic
    @Override
    public void run()
    {
        // cas的将当前处理器的状态从IDLE转换为RUNNING,并且返回old status
        int witnessValue = running.compareAndExchange(IDLE, RUNNING);
        if (witnessValue == IDLE) // Successful CAS this is normal logic ‼️
        {
            // 清楚SequenceBarrier中的中断标记，确保消费者可以正常的等待新事件，不会因为之前残留的中断信号而立即退出
            // 如果不清除,那么消费者在waitFor()时会立即抛出AlertException异常
            sequenceBarrier.clearAlert();
            // call back onStart()
            notifyStart();
            try
            {
                if (running.get() == RUNNING)
                {
                    processEvents(); // todo
                }
            }
            finally
            {
                notifyShutdown();
                running.set(IDLE);
            }
        }
        else // old status != IDLE
        {
            if (witnessValue == RUNNING) // old status == RUNNING，那么代表线程已经在运行中了，抛出异常
            {
                throw new IllegalStateException("Thread is already running");
            }
            else // status != idle & != running，代表处理器在被启动前就已经被停止了
            {
                earlyExit();  // call back
            }
        }
    }
    /*
        真正处理事件的逻辑 ‼️‼️
        注意这里是在消费者中(也即是在EventProcessor中)
    */
    private void processEvents()
    {
        T event = null;
        // 这里的sequence代表的是消费者的已经处理的最大序列号(初始值为-1)
        // 在这里获取下一个序列号(也就是待处理的序列号) - 1
        long nextSequence = sequence.get() + 1L;

        while (true) // 死循环
        {
            final long startOfBatchSequence = nextSequence; // 记录当前批次的起始序列号(因为消费者可以一次性处理多个事件,而非单个)
            try
            {
                try
                {
                    /*
                        前面说过,sequenceBarrier是协调消费者与生产者(上游消费者)之间的同步关系
                        在这里消费者想要去消费事件，就必须知道此时nextSequence对应的事件是否被正常消费，
                        这就是由sequenceBarrier来完成的(waitFor(s))
                        如果不能消费nextSequence对应的事件，那么就需要阻塞等待
                        否则返回可消费的序列号
                            - 在这里有个问题：nextSequence == availableSequence 吗?
                            - 答案是不一定的,但是 availableSequence >= nextSequence 是一定成立的
                                - 比如生产者已经生产到了150,而消费者请求消费100，那么这里返回的就是150
                    */
                    final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                    /*
                        计算批次结束位置
                        单个消费者一次性能够消费的事件是有限制的(通过batchLimitOffset来控制 - 假设为10)
                        而此次消费者想要消费的序列号为50，那么此批次的对应的事件范围为[50,60]
                           - nextSequence + batchLimitOffset
                           - availableSequence ：下一个可用的序列号
                           最终当前批次为两者最小值
                    */
                    final long endOfBatchSequence = min(nextSequence + batchLimitOffset, availableSequence);
                    // 批次开始通知 - 首先是生命周期回调
                    if (nextSequence <= endOfBatchSequence)
                    {
                        /*
                            这里传入的两个参数:
                             - endOfBatchSequence - nextSequence + 1 : 本批次将处理的事件数量
                             - availableSequence - nextSequence + 1 : 当前总共可用的事件数量
                             onBatchStart(10,50) - 本批次将处理10个事件,当前总共可用50个事件
                        */
                        eventHandler.onBatchStart(endOfBatchSequence - nextSequence + 1, availableSequence - nextSequence + 1);
                    }
                    // 在一个while循环里,依次处理当前批次中的所有事件
                    while (nextSequence <= endOfBatchSequence)
                    {
                        // 获取事件(这个就是用户创建的数据)
                        event = dataProvider.get(nextSequence);
                        /*
                            回调消费者的onEvent()方法(也是用户提供的)
                            - 这里的参数:
                                - event - 事件对象
                                - nextSequence - 序列号
                                - endOfBatchSequence == nextSequence - 是否是当前批次中的最后一个事件
                        */
                        eventHandler.onEvent(event, nextSequence, nextSequence == endOfBatchSequence);
                        nextSequence++; // 下一个消费的序列号
                    }
                    retriesAttempted = 0; // 重置重试计数器
                    // 更新当前消费者的已处理的最大序列号
                    sequence.set(endOfBatchSequence);
                }
                catch (final RewindableException e)
                {
                    nextSequence = rewindHandler.attemptRewindGetNextSequence(e, startOfBatchSequence);
                }
            }
            catch (final TimeoutException e)
            {
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                if (running.get() != RUNNING)
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    private void earlyExit()
    {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            eventHandler.onTimeout(availableSequence);
        }
        catch (Throwable e)
        {
            handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up.
     */
    private void notifyStart()
    {
        try
        {
            eventHandler.onStart();
        }
        catch (final Throwable ex)
        {
            handleOnStartException(ex);
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down.
     */
    private void notifyShutdown()
    {
        try
        {
            eventHandler.onShutdown();
        }
        catch (final Throwable ex)
        {
            handleOnShutdownException(ex);
        }
    }

    /**
     * Delegate to {@link ExceptionHandler#handleEventException(Throwable, long, Object)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleEventException(final Throwable ex, final long sequence, final T event)
    {
        getExceptionHandler().handleEventException(ex, sequence, event);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnStartException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnStartException(final Throwable ex)
    {
        getExceptionHandler().handleOnStartException(ex);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnShutdownException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnShutdownException(final Throwable ex)
    {
        getExceptionHandler().handleOnShutdownException(ex);
    }

    private ExceptionHandler<? super T> getExceptionHandler()
    {
        ExceptionHandler<? super T> handler = exceptionHandler;
        return handler == null ? ExceptionHandlers.defaultHandler() : handler;
    }

    private class TryRewindHandler implements RewindHandler
    {
        private final BatchRewindStrategy batchRewindStrategy;

        TryRewindHandler(final BatchRewindStrategy batchRewindStrategy)
        {
            this.batchRewindStrategy = batchRewindStrategy;
        }

        @Override
        public long attemptRewindGetNextSequence(final RewindableException e, final long startOfBatchSequence) throws RewindableException
        {
            if (batchRewindStrategy.handleRewindException(e, ++retriesAttempted) == REWIND)
            {
                return startOfBatchSequence;
            }
            else
            {
                retriesAttempted = 0;
                throw e;
            }
        }
    }

    private static class NoRewindHandler implements RewindHandler
    {
        @Override
        public long attemptRewindGetNextSequence(final RewindableException e, final long startOfBatchSequence)
        {
            throw new UnsupportedOperationException("Rewindable Exception thrown from a non-rewindable event handler", e);
        }
    }
}