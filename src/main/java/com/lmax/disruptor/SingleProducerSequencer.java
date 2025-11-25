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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

abstract class SingleProducerSequencerPad extends AbstractSequencer
{
    protected byte
        p10, p11, p12, p13, p14, p15, p16, p17,
        p20, p21, p22, p23, p24, p25, p26, p27,
        p30, p31, p32, p33, p34, p35, p36, p37,
        p40, p41, p42, p43, p44, p45, p46, p47,
        p50, p51, p52, p53, p54, p55, p56, p57,
        p60, p61, p62, p63, p64, p65, p66, p67,
        p70, p71, p72, p73, p74, p75, p76, p77;

    SingleProducerSequencerPad(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    SingleProducerSequencerFields(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    long nextValue = Sequence.INITIAL_VALUE; // 下一个要分配的序列号
    long cachedValue = Sequence.INITIAL_VALUE; // 缓存的最小的gating Sequence
}

/**
 * Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.
 *
 * <p>* Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    protected byte
        p10, p11, p12, p13, p14, p15, p16, p17,
        p20, p21, p22, p23, p24, p25, p26, p27,
        p30, p31, p32, p33, p34, p35, p36, p37,
        p40, p41, p42, p43, p44, p45, p46, p47,
        p50, p51, p52, p53, p54, p55, p56, p57,
        p60, p61, p62, p63, p64, p65, p66, p67,
        p70, p71, p72, p73, p74, p75, p76, p77;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(requiredCapacity, false);
    }

    private boolean hasAvailableCapacity(final int requiredCapacity, final boolean doStore)
    {
        long nextValue = this.nextValue;

        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            if (doStore)
            {
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }

            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
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
    // 这里是SingleProducerSequencer中的方法,也就是单生产者模式下的方法 -- 单生产者(没有竞争,看下是如何操作的~)
    // 该方法的作用：生产者用于申请 n 个可用的序列号
    @Override
    public long next(final int n)
    {
        assert sameThread() : "Accessed by two threads - use ProducerType.MULTI!";
        // 参数校验
        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }
        // 获取下一个要发布的序列号,默认初始值为-1
        long nextValue = this.nextValue;
        // 下一个操作的序列号(如果是1,那么下一个要操作的序列号就是0,这是符合的,数组下标就是从0开始的)
        long nextSequence = nextValue + n;
        // 环形缓冲区回绕点, -1024
        long wrapPoint = nextSequence - bufferSize;
        // 缓存的消费最慢的消费者的gating Sequence
        long cachedGatingSequence = this.cachedValue;
        /*
            容量检查与等待逻辑 - 满足任何一个条件,都会进入到该分支中
                - wrapPoint > cachedGatingSequence : 生产者申请的位置会覆盖还未被消费的数据(最慢消费者会丢失数据)
                    - 那么需要进入到if分支中,此时必须检查消费者的实际进度,确保不会覆盖还未消费的数据
                - cachedGatingSequence > nextValue: 缓存的消费者位置已经过期,正常情况下,消费者是不会超过生产者的
                    - 这种情况通常发生在
                        - 首次调用 (hit)
                        - 消费者快速消费后,缓存值滞后
             这里有个问题:
                cachedGatingSequence的类型是long,也即其缓存的是最慢消费者的sequence中的value
                那么该‘最慢’消费者如果更新了自己的sequence，那么这里的值不就失效了吗?会有什么问题吗？
             从这里的设计来看：cachedGatingSequence 缓存的是“某个时刻”最慢消费者的消费进度
             这里什么时候会进行第二个判断呢？只有 wrapPoint > cachedGatingSequence = false的时候才会进行判断
             如果 wrapPoint > cachedGatingSequence = false，这代表什么呢？
             代表生产者申请的位置不会覆盖还未被消费的数据(最慢消费者不会丢失数据)
             那么其实直接去更新nextValue就可以了,没有问题,nextValue位置是可以放数据的
             那么是否会出现前面为false,后面为true的情况呢？
                - 我认为是不会出现的,因为:
                    - minSequence = Util.getMinimumSequence(gatingSequences, nextValue)) {minSequence <= nextValue}
                    - this.cachedValue = minSequence
                - 还是说有另外的场景?
        */
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            // 更新生产者的sequence(具有volatile语言,保证可见性)
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            /*
                (minSequence = Util.getMinimumSequence(gatingSequences, nextValue))
                这个是用于获取消费者中最慢的消费者的gating Sequence(重新获取,并且以nextValue作为最开始的比较值)
                如果wrapPoint > minSequence,那么代表生产者可能会覆盖掉还未消费的数据,
                所以在这里需要循环等待,直到wrapPoint <= minSequence时,才代表可以覆盖数据
            */
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }
            // 缓存最慢的消费者的gating Sequence
            this.cachedValue = minSequence;
        }
        // 更新nextValue
        this.nextValue = nextSequence;

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

        if (!hasAvailableCapacity(n, true))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(final long sequence)
    {
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {
        cursor.set(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(final long lo, final long hi)
    {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(final long sequence)
    {
        final long currentSequence = cursor.get();
        return sequence <= currentSequence && sequence > currentSequence - bufferSize;
    }

    @Override
    public long getHighestPublishedSequence(final long lowerBound, final long availableSequence)
    {
        return availableSequence;
    }

    @Override
    public String toString()
    {
        return "SingleProducerSequencer{" +
                "bufferSize=" + bufferSize +
                ", waitStrategy=" + waitStrategy +
                ", cursor=" + cursor +
                ", gatingSequences=" + Arrays.toString(gatingSequences) +
                '}';
    }

    private boolean sameThread()
    {
        return ProducerThreadAssertion.isSameThreadProducingTo(this);
    }

    /**
     * Only used when assertions are enabled.
     */
    private static class ProducerThreadAssertion
    {
        /**
         * Tracks the threads publishing to {@code SingleProducerSequencer}s to identify if more than one
         * thread accesses any {@code SingleProducerSequencer}.
         * I.e. it helps developers detect early if they use the wrong
         * {@link com.lmax.disruptor.dsl.ProducerType}.
         */
        private static final Map<SingleProducerSequencer, Thread> PRODUCERS = new HashMap<>();

        public static boolean isSameThreadProducingTo(final SingleProducerSequencer singleProducerSequencer)
        {
            synchronized (PRODUCERS)
            {
                final Thread currentThread = Thread.currentThread();
                if (!PRODUCERS.containsKey(singleProducerSequencer))
                {
                    PRODUCERS.put(singleProducerSequencer, currentThread);
                }
                return PRODUCERS.get(singleProducerSequencer).equals(currentThread);
            }
        }
    }
}
