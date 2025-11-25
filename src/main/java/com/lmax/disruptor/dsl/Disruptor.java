/*
 * Copyright 2011 LMAX Ltd.
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
package com.lmax.disruptor.dsl;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.BatchEventProcessorBuilder;
import com.lmax.disruptor.BatchRewindStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventHandlerIdentity;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.EventTranslatorThreeArg;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RewindableEventHandler;
import com.lmax.disruptor.RewindableException;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.util.Util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A DSL-style API for setting up the disruptor pattern around a ring buffer
 * (aka the Builder pattern).
 *
 * <p>A simple example of setting up the disruptor with two event handlers that
 * must process events in order:
 *
 * <pre>
 * <code>Disruptor&lt;MyEvent&gt; disruptor = new Disruptor&lt;MyEvent&gt;(MyEvent.FACTORY, 32, Executors.newCachedThreadPool());
 * EventHandler&lt;MyEvent&gt; handler1 = new EventHandler&lt;MyEvent&gt;() { ... };
 * EventHandler&lt;MyEvent&gt; handler2 = new EventHandler&lt;MyEvent&gt;() { ... };
 * disruptor.handleEventsWith(handler1);
 * disruptor.after(handler1).handleEventsWith(handler2);
 *
 * RingBuffer ringBuffer = disruptor.start();</code>
 * </pre>
 *
 * @param <T> the type of event used.
 */
public class Disruptor<T>
{
    private final RingBuffer<T> ringBuffer;
    private final ThreadFactory threadFactory;
    private final ConsumerRepository consumerRepository = new ConsumerRepository();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExceptionHandler<? super T> exceptionHandler = new ExceptionHandlerWrapper<>();

    /**
     * Create a new Disruptor. Will default to {@link com.lmax.disruptor.BlockingWaitStrategy} and
     * {@link ProducerType}.MULTI
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer.
     * @param threadFactory  a {@link ThreadFactory} to create threads to for processors.
     */
    public Disruptor(final EventFactory<T> eventFactory, final int ringBufferSize, final ThreadFactory threadFactory)
    {
        this(RingBuffer.createMultiProducer(eventFactory, ringBufferSize), threadFactory);
    }

    /**
     * Create a new Disruptor.
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer, must be power of 2.
     * @param threadFactory  a {@link ThreadFactory} to create threads for processors.
     * @param producerType   the claim strategy to use for the ring buffer.
     * @param waitStrategy   the wait strategy to use for the ring buffer.
     */
    public Disruptor(
            final EventFactory<T> eventFactory,
            final int ringBufferSize,
            final ThreadFactory threadFactory,
            final ProducerType producerType,
            final WaitStrategy waitStrategy)
    {
        this(
            RingBuffer.create(producerType, eventFactory, ringBufferSize, waitStrategy), // 首先创建一个RingBuffer
            threadFactory);
    }

    /**
     * Private constructor helper
     */
    private Disruptor(final RingBuffer<T> ringBuffer, final ThreadFactory threadFactory)
    {
        this.ringBuffer = ringBuffer;
        this.threadFactory = threadFactory;
    }

    /**
     * <p>Set up event handlers to handle events from the ring buffer. These handlers will process events
     * as soon as they become available, in parallel.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * @param handlers the event handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    /*
       在这里最终会返回一个EventHandlerGroup对象
       作用：
        1.支持链式调用,disruptor.handleEventsWith(A).then(B).then(C)...
        2.封装依赖关系信息(disruptor,consumerRepository,sequences[]{消费者序列号数组})
        3.作为依赖屏障的标识,当调用handleEventsWith()/then()时,EventHandlerGroup中的sequences[]会作为barrierSequences参数传递给下一批消费者
          - 换句话说,也就是作为下一批消费者的上游依赖
    */
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventHandler<? super T>... handlers)
    {
        return createEventProcessors(new Sequence[0], handlers);
    }

    /**
     * <p>Set up event handlers to handle events from the ring buffer. These handlers will process events
     * as soon as they become available, in parallel.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * @param batchRewindStrategy a {@link BatchRewindStrategy} for customizing how to handle a {@link RewindableException}.
     * @param handlers            the rewindable event handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final BatchRewindStrategy batchRewindStrategy,
                                                       final RewindableEventHandler<? super T>... handlers)
    {
        return createEventProcessors(new Sequence[0], batchRewindStrategy, handlers);
    }

    /**
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start these processors when {@link #start()} is called.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>Since this is the start of the chain, the processor factories will always be passed an empty <code>Sequence</code>
     * array, so the factory isn't necessary in this case. This method is provided for consistency with
     * {@link EventHandlerGroup#handleEventsWith(EventProcessorFactory...)} and {@link EventHandlerGroup#then(EventProcessorFactory...)}
     * which do have barrier sequences to provide.</p>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * @param eventProcessorFactories the event processor factories to use to create the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventProcessorFactory<T>... eventProcessorFactories)
    {
        final Sequence[] barrierSequences = new Sequence[0];
        return createEventProcessors(barrierSequences, eventProcessorFactories);
    }

    /**
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start this processors when {@link #start()} is called.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the processor <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * @param processors the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    public EventHandlerGroup<T> handleEventsWith(final EventProcessor... processors)
    {
        for (final EventProcessor processor : processors)
        {
            consumerRepository.add(processor);
        }

        final Sequence[] sequences = Util.getSequencesFor(processors);

        ringBuffer.addGatingSequences(sequences);

        return new EventHandlerGroup<>(this, consumerRepository, sequences);
    }


    /**
     * <p>Specify an exception handler to be used for any future event handlers.</p>
     *
     * <p>Note that only event handlers set up after calling this method will use the exception handler.</p>
     *
     * @param exceptionHandler the exception handler to use for any future {@link EventProcessor}.
     * @deprecated This method only applies to future event handlers. Use setDefaultExceptionHandler instead which applies to existing and new event handlers.
     */
    @Deprecated
    public void handleExceptionsWith(final ExceptionHandler<? super T> exceptionHandler)
    {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * <p>Specify an exception handler to be used for event handlers and worker pools created by this Disruptor.</p>
     *
     * <p>The exception handler will be used by existing and future event handlers and worker pools created by this Disruptor instance.</p>
     *
     * @param exceptionHandler the exception handler to use.
     */
    @SuppressWarnings("unchecked")
    public void setDefaultExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        checkNotStarted();
        if (!(this.exceptionHandler instanceof ExceptionHandlerWrapper))
        {
            throw new IllegalStateException("setDefaultExceptionHandler can not be used after handleExceptionsWith");
        }
        ((ExceptionHandlerWrapper<T>) this.exceptionHandler).switchTo(exceptionHandler);
    }

    /**
     * Override the default exception handler for a specific handler.
     * <pre>disruptorWizard.handleExceptionsIn(eventHandler).with(exceptionHandler);</pre>
     *
     * @param eventHandler the event handler to set a different exception handler for.
     * @return an ExceptionHandlerSetting dsl object - intended to be used by chaining the with method call.
     */
    public ExceptionHandlerSetting<T> handleExceptionsFor(final EventHandlerIdentity eventHandler)
    {
        return new ExceptionHandlerSetting<>(eventHandler, consumerRepository);
    }

    /**
     * <p>Create a group of event handlers to be used as a dependency.
     * For example if the handler <code>A</code> must process events before handler <code>B</code>:</p>
     *
     * <pre><code>dw.after(A).handleEventsWith(B);</code></pre>
     *
     * @param handlers the event handlers, previously set up with {@link #handleEventsWith(EventHandler[])},
     *                 that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a dependency barrier over the specified event handlers.
     */
    public final EventHandlerGroup<T> after(final EventHandlerIdentity... handlers)
    {
        final Sequence[] sequences = new Sequence[handlers.length];
        for (int i = 0, handlersLength = handlers.length; i < handlersLength; i++)
        {
            sequences[i] = consumerRepository.getSequenceFor(handlers[i]);
        }

        return new EventHandlerGroup<>(this, consumerRepository, sequences);
    }

    /**
     * Create a group of event processors to be used as a dependency.
     *
     * @param processors the event processors, previously set up with {@link #handleEventsWith(com.lmax.disruptor.EventProcessor...)},
     *                   that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a {@link SequenceBarrier} over the specified event processors.
     * @see #after(EventHandlerIdentity[])
     */
    public EventHandlerGroup<T> after(final EventProcessor... processors)
    {
        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param eventTranslator the translator that will load data into the event.
     */
    public void publishEvent(final EventTranslator<T> eventTranslator)
    {
        ringBuffer.publishEvent(eventTranslator);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param <A>             Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg             A single argument to load into the event
     */
    public <A> void publishEvent(final EventTranslatorOneArg<T, A> eventTranslator, final A arg)
    {
        ringBuffer.publishEvent(eventTranslator, arg);
    }

    /**
     * Publish a batch of events to the ring buffer.
     *
     * @param <A>             Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg             An array single arguments to load into the events. One Per event.
     */
    public <A> void publishEvents(final EventTranslatorOneArg<T, A> eventTranslator, final A[] arg)
    {
        ringBuffer.publishEvents(eventTranslator, arg);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param <A>             Class of the user supplied argument.
     * @param <B>             Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg0            The first argument to load into the event
     * @param arg1            The second argument to load into the event
     */
    public <A, B> void publishEvent(final EventTranslatorTwoArg<T, A, B> eventTranslator, final A arg0, final B arg1)
    {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param eventTranslator the translator that will load data into the event.
     * @param <A>             Class of the user supplied argument.
     * @param <B>             Class of the user supplied argument.
     * @param <C>             Class of the user supplied argument.
     * @param arg0            The first argument to load into the event
     * @param arg1            The second argument to load into the event
     * @param arg2            The third argument to load into the event
     */
    public <A, B, C> void publishEvent(final EventTranslatorThreeArg<T, A, B, C> eventTranslator, final A arg0, final B arg1, final C arg2)
    {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1, arg2);
    }

    /**
     * <p>Starts the event processors and returns the fully configured ring buffer.</p>
     *
     * <p>The ring buffer is set up to prevent overwriting any entry that is yet to
     * be processed by the slowest event processor.</p>
     *
     * <p>This method must only be called once after all event processors have been added.</p>
     *
     * @return the configured ring buffer.
     */
    public RingBuffer<T> start()
    {
        checkOnlyStartedOnce(); // 确保disruptor只能启动一次
        // 这里调用的是消费者仓库的startAll()方法，因为该仓库中保存了所有消费者的信息
        consumerRepository.startAll(threadFactory);

        return ringBuffer;
    }

    /**
     * Calls {@link com.lmax.disruptor.EventProcessor#halt()} on all of the event processors created via this disruptor.
     */
    public void halt()
    {
        consumerRepository.haltAll();
    }

    /**
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.  It is critical that publishing to the ring buffer has stopped
     * before calling this method, otherwise it may never return.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     */
    public void shutdown()
    {
        try
        {
            shutdown(-1, TimeUnit.MILLISECONDS);
        }
        catch (final TimeoutException e)
        {
            exceptionHandler.handleOnShutdownException(e);
        }
    }

    /**
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     *
     * @param timeout  the amount of time to wait for all events to be processed. <code>-1</code> will give an infinite timeout
     * @param timeUnit the unit the timeOut is specified in
     * @throws TimeoutException if a timeout occurs before shutdown completes.
     */
    public void shutdown(final long timeout, final TimeUnit timeUnit) throws TimeoutException
    {
        final long timeOutAt = System.nanoTime() + timeUnit.toNanos(timeout);
        while (hasBacklog())
        {
            if (timeout >= 0 && System.nanoTime() > timeOutAt)
            {
                throw TimeoutException.INSTANCE;
            }
            // Busy spin
        }
        halt();
    }

    /**
     * The {@link RingBuffer} used by this Disruptor.  This is useful for creating custom
     * event processors if the behaviour of {@link BatchEventProcessor} is not suitable.
     *
     * @return the ring buffer used by this Disruptor.
     */
    public RingBuffer<T> getRingBuffer()
    {
        return ringBuffer;
    }

    /**
     * Get the value of the cursor indicating the published sequence.
     *
     * @return value of the cursor for events that have been published.
     */
    public long getCursor()
    {
        return ringBuffer.getCursor();
    }

    /**
     * The capacity of the data structure to hold entries.
     *
     * @return the size of the RingBuffer.
     * @see com.lmax.disruptor.Sequencer#getBufferSize()
     */
    public long getBufferSize()
    {
        return ringBuffer.getBufferSize();
    }

    /**
     * Get the event for a given sequence in the RingBuffer.
     *
     * @param sequence for the event.
     * @return event for the sequence.
     * @see RingBuffer#get(long)
     */
    public T get(final long sequence)
    {
        return ringBuffer.get(sequence);
    }

    /**
     * Get the {@link SequenceBarrier} used by a specific handler. Note that the {@link SequenceBarrier}
     * may be shared by multiple event handlers.
     *
     * @param handler the handler to get the barrier for.
     * @return the SequenceBarrier used by <i>handler</i>.
     */
    public SequenceBarrier getBarrierFor(final EventHandlerIdentity handler)
    {
        return consumerRepository.getBarrierFor(handler);
    }

    /**
     * Gets the sequence value for the specified event handlers.
     *
     * @param handler eventHandler to get the sequence for.
     * @return eventHandler's sequence
     */
    public long getSequenceValueFor(final EventHandlerIdentity handler)
    {
        return consumerRepository.getSequenceFor(handler).get();
    }

    /**
     * Confirms if all messages have been consumed by all event processors
     */
    private boolean hasBacklog()
    {
        final long cursor = ringBuffer.getCursor();

        return consumerRepository.hasBacklog(cursor, false);
    }

    /**
     * Checks if disruptor has been started
     *
     * @return true when start has been called on this instance; otherwise false
     */
    public boolean hasStarted()
    {
        return started.get();
    }

    EventHandlerGroup<T> createEventProcessors(
            final Sequence[] barrierSequences, // 当前消费者所依赖的上游消费者序列(可能没有)
            final EventHandler<? super T>[] eventHandlers) // 用户提供的事件处理器数组
    {
        checkNotStarted(); // 确保Disruptor还没有启动

        /*
            创建序列数组,为每个EventHandle创建对应的Sequence存储空间(用来存储每一个handler的sequence)
        */
        final Sequence[] processorSequences = new Sequence[eventHandlers.length];
         /*
            创建消费者序列屏障,这个是共享的(共享指的是同一批通过handleEventsWith()注册的消费者{eventHandlers会共享一个SequenceBarrier})
        */
        final SequenceBarrier barrier = ringBuffer.newBarrier(barrierSequences);

        /*
            循环创建BatchEventProcessor(也就是消费者)

        */
        for (int i = 0, eventHandlersLength = eventHandlers.length; i < eventHandlersLength; i++)
        {
            // 5.1 获取到用户提供的事件处理器(handler)
            final EventHandler<? super T> eventHandler = eventHandlers[i];
            // 5.2 构建BatchEventProcessor(消费者)
            final BatchEventProcessor<T> batchEventProcessor =
                    new BatchEventProcessorBuilder().build(ringBuffer, barrier, eventHandler);
            // 5.3 设置异常处理器(如果有的话)
            if (exceptionHandler != null)
            {
                batchEventProcessor.setExceptionHandler(exceptionHandler);
            }
            // 5.4 将BatchEventProcessor(消费者)添加到消费者仓库中
            consumerRepository.add(batchEventProcessor, eventHandler, barrier);
            // 5.5 将BatchEventProcessor(消费者)的Sequence添加到processorSequences数组中
            processorSequences[i] = batchEventProcessor.getSequence();
        }

        updateGatingSequencesForNextInChain(barrierSequences, processorSequences);

        return new EventHandlerGroup<>(this, consumerRepository, processorSequences);
    }

    EventHandlerGroup<T> createEventProcessors(
            final Sequence[] barrierSequences,
            final BatchRewindStrategy batchRewindStrategy,
            final RewindableEventHandler<? super T>[] eventHandlers)
    {
        checkNotStarted();

        final Sequence[] processorSequences = new Sequence[eventHandlers.length];
        final SequenceBarrier barrier = ringBuffer.newBarrier(barrierSequences);

        for (int i = 0, eventHandlersLength = eventHandlers.length; i < eventHandlersLength; i++)
        {
            final RewindableEventHandler<? super T> eventHandler = eventHandlers[i];

            final BatchEventProcessor<T> batchEventProcessor =
                    new BatchEventProcessorBuilder().build(ringBuffer, barrier, eventHandler, batchRewindStrategy);

            if (exceptionHandler != null)
            {
                batchEventProcessor.setExceptionHandler(exceptionHandler);
            }

            consumerRepository.add(batchEventProcessor, eventHandler, barrier);
            processorSequences[i] = batchEventProcessor.getSequence();
        }

        updateGatingSequencesForNextInChain(barrierSequences, processorSequences);

        return new EventHandlerGroup<>(this, consumerRepository, processorSequences);
    }

    /*
        该方法负责更新ringBuffer的gatingSequences
         processorSequences：所有消费者的Sequence数组
         barrierSequences:消费者所依赖的上游消费者的Sequence数组
     */
    private void updateGatingSequencesForNextInChain(final Sequence[] barrierSequences, final Sequence[] processorSequences)
    {
        // 保证至少创建了一个新的消费者
        if (processorSequences.length > 0)
        {
            // 将新创建的所有消费者的Sequence添加到ringBuffer的gatingSequences中 - 这些新消费者现在成为了生产者需要等待的gating
            ringBuffer.addGatingSequences(processorSequences);
            /*
                移除上游消费者的序列,遍历barrierSequences数组,将它们从ringBuffer的gatingSequences中移除
                逻辑优化:
                假设生产者的gating Sequences[]为[handle-1.sequence, handle-2.sequence, handle-3.sequence]
                 - 生产者在发布新事件时,必须确保所有消费者都处理完了该槽位的旧数据,在这里需要获取 minSeq = min(h1.seq, h2.seq, h3.seq)
                   - 如果 currentSeq(生产者当前想要发布的位置) > minSeq, 那么说明还有消费者没有消费到该currentSeq数据
                   - 此时消费者应该阻塞
                而同时handle-3.sequence依赖于handle-1.sequence和handle-2.sequence,(也即barrierSequences[])

                在这种情况下,如果没有优化,那么在计算min(h1.seq, h2.seq, h3.seq),也即需要从3个seq中找到最小的,
                但是其实当消费者之间也存在依赖关系时，比如上面的h3.sequence依赖于h1.sequence和h2.sequence,
                那么其实生产者要等待的最小seq,就是h3.seq(因为h3.seq需要等待h2.seq,h2.seq需要等待h1.seq)

                *************************
                这里还需要注意一个点：当h3.seq依赖于h2.seq时,为什么h3.seq <= h2.seq(这里为什么是<=呢？)
                因为seqence序列号代表的是每个组件已经处理完成的位置,
                对于消费者来说：它代表该消费者已经处理完成的最大序列号,
                当存在等待关系时,比如h3依赖h2，意味着h3在处理某个事件之前,必须确保h2已经处理了该事件
                [这里有个核心概念：依赖通常指的是不同消费者对于同一个事件的处理顺序]
                *************************

                所以在这里,会remove掉h1.seq和h2.seq,只保留处理链末端的消费者序列

             */
            for (final Sequence barrierSequence : barrierSequences)
            {
                ringBuffer.removeGatingSequence(barrierSequence);
            }
            // 并且标记h1和h2不再是处理器末端(这里的barrierSequences是依赖的上游消费者的Sequence数组)
            consumerRepository.unMarkEventProcessorsAsEndOfChain(barrierSequences);
        }
    }

    EventHandlerGroup<T> createEventProcessors(
            final Sequence[] barrierSequences, final EventProcessorFactory<T>[] processorFactories)
    {
        final EventProcessor[] eventProcessors = new EventProcessor[processorFactories.length];
        for (int i = 0; i < processorFactories.length; i++)
        {
            eventProcessors[i] = processorFactories[i].createEventProcessor(ringBuffer, barrierSequences);
        }

        return handleEventsWith(eventProcessors);
    }

    private void checkNotStarted()
    {
        if (started.get())
        {
            throw new IllegalStateException("All event handlers must be added before calling starts.");
        }
    }

    private void checkOnlyStartedOnce()
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException("Disruptor.start() must only be called once.");
        }
    }

    @Override
    public String toString()
    {
        return "Disruptor{" +
                "ringBuffer=" + ringBuffer +
                ", started=" + started +
                ", threadFactory=" + threadFactory +
                '}';
    }
}
