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
package com.lmax.disruptor;

import com.lmax.disruptor.util.Util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;


/**
 * Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Suitable for use for sequencing across multiple publisher threads.
 *
 * <p>Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#next()}, to determine the highest available sequence that can be read, then
 * {@link Sequencer#getHighestPublishedSequence(long, long)} should be used.
 */
public final class MultiProducerSequencer extends AbstractSequencer
{
    private static final VarHandle AVAILABLE_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);

    private final Sequence gatingSequenceCache = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    // availableBuffer tracks the state of each ringbuffer slot
    // see below for more details on the approach
    private final int[] availableBuffer;
    private final int indexMask;
    private final int indexShift;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public MultiProducerSequencer(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
        /*
            多生产者需要一个availableBuffer数组，这个数组的作用是什么
            这个是多生产者中的核心数据结构,用来避免发布乱序
            时间 T1: 生产者 P1 获取序列号 10
            时间 T2: 生产者 P2 获取序列号 11
            时间 T3: P2 先完成，发布序列号 11  ✓
            时间 T4: P1 还在处理，序列号 10 未发布  ✗
            此时 cursor = 11，但位置 10 的数据还未就绪！
            消费者不能直接读取位置 10 和 11，必须等待 P1 发布。

            ---

            而这个availableBuffer[]就是解决方案，它为ringBuffer的每一个位置维护了一个发布标记,
            当生产者发布数据时,需要设置对应位置的标记
            消费者读取前,检查标记
            初始值为-1
        */
        availableBuffer = new int[bufferSize];
        Arrays.fill(availableBuffer, -1);
        // 用于快速计算序列号在ringBuffer中的实际位置
        indexMask = bufferSize - 1;
        /*
            用于计算序列号的轮次
            indexShift = log2(bufferSize){32 -> 2^5}
            seq = 0~31, seq >>> 5 = 0 - 第一轮
            seq = 32~63, seq >>> 5 = 1 - 第二轮
            ....
        */
        indexShift = Util.log2(bufferSize);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(gatingSequences, requiredCapacity, cursor.get());
    }

    private boolean hasAvailableCapacity(final Sequence[] gatingSequences, final int requiredCapacity, final long cursorValue)
    {
        long wrapPoint = (cursorValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = gatingSequenceCache.get();

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > cursorValue)
        {
            long minSequence = Util.getMinimumSequence(gatingSequences, cursorValue);
            gatingSequenceCache.set(minSequence);

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(final long sequence)
    {
        cursor.set(sequence);
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    // 这里是 MultiProducerSequencer 的next()方法,和 SingleProducerSequencer 的next()有很大区别
    @Override
    public long next(final int n)
    {
        // 边界处理
        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }
        // 原子性的获取当前生产者(注意,在这里虽然是多个生产者(用户),但是这里的MultiProducerSequencer是所有生产者共享的)
        // 多个生产者调用onData()发布数据时,首先会调用next()来获取可以使用的序列号,所以这里的并发是开发者的多个producer 可能会同时调用next()方法来获取下一个能操作的序列号
        // 这里必须保证线程安全,也即多个producer获取到的sequence必须是不同的,否则会出现数据覆盖
        // 所以在这里每个生产者都会获取到属于自己的sequence(这里的current是old cursor，但是此时cursor 已经增加了,别的生产者获取的就是new cursor)
        long current = cursor.getAndAdd(n);
        // 计算本次申请的最后一个序列号
        /*
            这里我有个问题,那就是next(n),也即生产者可以一次性获取多个序列号吗？
            答案是可以的,开发者创建的(producer)是持有ringBuffer的,所以可以主动调用next(n)来预分配序列号
            比如此时RingBuffer中对消费者可见的cursor.seq = 10
            那么这里外部生产者调用next(10) - getAndAdd(10)
            在这里获取的就是10，但是此时的cursor已经变为20了,也就是说[10 - 20]这个区间的位置都是由当前生产者来发布事件的
            其他生产者也是同理

            - 所以这样就能够理解了，nextSequence是当前生产者此次操作的(其实是生产者当前处理批次的最后一个序列号,感觉叫做lastBatchSequence更好理解一点)
        */
        long nextSequence = current + n;
        // 计算回绕点(指向最后一个)
        long wrapPoint = nextSequence - bufferSize;
        // 获取缓存的gatingSequence(消费者中消费最慢的消费进度 - 可能已经过期)
        long cachedGatingSequence = gatingSequenceCache.get();
        // 这里用最后一个判断是没问题的,不能用起始序列号(起始序列号 - bufferSize)来判断是否大于cachedGatingSequence,
        // 如果满足条件,那么说明当前生产者可能已经会覆盖数据(消费者还没有消费)
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > current)
        {
            long gatingSequence;
            /*
                gatingSequence = Util.getMinimumSequence(gatingSequences, current))
                    - gatingSequences：所有的消费者序列
                    - current：目前最旧的生产者序列号
                我感觉这里存在一个性能问题,这里需要注意：wrapPoint = nextSequence - bufferSize; 这里指向的是最后一个不能被覆盖的序列号
                其实当前生产者等待的序列号范围是[current - bufferSize , wrapPoint = nextSequence - bufferSize]
                但是在这里用的wrapPoint > gatingSequence,那么当消费者消费了某些数据后,在这里生产者依旧要等待
                直到消费者消费到了当前生产者等待的最后一个序列号
            */
            while (wrapPoint > (gatingSequence = Util.getMinimumSequence(gatingSequences, current)))
            {
                LockSupport.parkNanos(1L); // TODO, should we spin based on the wait strategy?
            }
            // 走到这里说明,生产者已经消费到了wrapPoint了,那么缓存gatingSequence
            gatingSequenceCache.set(gatingSequence);
        }
        // 返回nextSequence
        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(final int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long current;
        long next;

        do
        {
            current = cursor.get();
            next = current + n;

            if (!hasAvailableCapacity(gatingSequences, n, current))
            {
                throw InsufficientCapacityException.INSTANCE;
            }
        }
        while (!cursor.compareAndSet(current, next));

        return next;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long consumed = Util.getMinimumSequence(gatingSequences, cursor.get());
        long produced = cursor.get();
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {
        setAvailable(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(final long lo, final long hi)
    {
        for (long l = lo; l <= hi; l++)
        {
            setAvailable(l);
        }
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * The below methods work on the availableBuffer flag.
     *
     * <p>The prime reason is to avoid a shared sequence object between publisher threads.
     * (Keeping single pointers tracking start and end would require coordination
     * between the threads).
     *
     * <p>--  Firstly we have the constraint that the delta between the cursor and minimum
     * gating sequence will never be larger than the buffer size (the code in
     * next/tryNext in the Sequence takes care of that).
     * -- Given that; take the sequence value and mask off the lower portion of the
     * sequence as the index into the buffer (indexMask). (aka modulo operator)
     * -- The upper portion of the sequence becomes the value to check for availability.
     * ie: it tells us how many times around the ring buffer we've been (aka division)
     * -- Because we can't wrap without the gating sequences moving forward (i.e. the
     * minimum gating sequence is effectively our last available position in the
     * buffer), when we have new data and successfully claimed a slot we can simply
     * write over the top.
     */
    private void setAvailable(final long sequence)
    {
        /*
            1.calculateIndex(sequence):计算在数组中的索引位置
            2.calculateAvailabilityFlag(sequence):计算在availableBuffer数组中的标记值(也即当前事件的轮次)
        */
        setAvailableBufferValue(calculateIndex(sequence), calculateAvailabilityFlag(sequence));
    }

    private void setAvailableBufferValue(final int index, final int flag)
    {
        AVAILABLE_ARRAY.setRelease(availableBuffer, index, flag);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(final long sequence)
    {
        // 获取序列号所在ringBuffer中的下标
        int index = calculateIndex(sequence);
        // 获取当前序列号所对应的轮次
        int flag = calculateAvailabilityFlag(sequence);
        // 当前序列号的轮次 是否和 availableBuffer[index]中的下标一致
        return (int) AVAILABLE_ARRAY.getAcquire(availableBuffer, index) == flag;
    }

    /*
        lowerBound: 消费者想要消费的序列号
        availableSequence:真正可以消费的序列号
        availableSequence >= lowerBound
     */
    @Override
    public long getHighestPublishedSequence(final long lowerBound, final long availableSequence)
    {
        // 从 lowerBound开始,逐个检查每个序列号是否可以真正消费
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++)
        {
            /*
                focus‼️ 需要判断每个序列号是否可以真正的消费
                 - true:可以消费
                 - false:不可以消费,那么返回前一个序列号(当前消费者应该还是不会消费,依旧重新调用waitFor())
            */
            if (!isAvailable(sequence))
            {
                return sequence - 1;
            }
        }
        // 否则,可以消费
        return availableSequence;
    }

    private int calculateAvailabilityFlag(final long sequence)
    {
        return (int) (sequence >>> indexShift);
    }

    private int calculateIndex(final long sequence)
    {
        return ((int) sequence) & indexMask;
    }

    @Override
    public String toString()
    {
        return "MultiProducerSequencer{" + "bufferSize=" + bufferSize + ", waitStrategy=" + waitStrategy + ", cursor=" + cursor + ", gatingSequences=" + Arrays.toString(gatingSequences) + '}';
    }
}
