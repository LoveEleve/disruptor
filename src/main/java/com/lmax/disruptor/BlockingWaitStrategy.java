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

/**
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 *
 *
 * <p>This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public final class BlockingWaitStrategy implements WaitStrategy
{
    private final Object mutex = new Object();

    /**
     * @param sequence          消费者想要消费的起始序列号
     * @param cursorSequence    生产者已经发布的序列号
     * @param dependentSequence 消费者依赖的序列号
     * @param barrier           消费者的barrier
     * @return
     * @throws AlertException
     * @throws InterruptedException
     */
    @Override
    public long waitFor(final long sequence, final Sequence cursorSequence, final Sequence dependentSequence, final SequenceBarrier barrier)
            throws AlertException, InterruptedException
    {
        /*
            消费者想要消费某个序列号(sequence)，通过前面的学习,应该是要满足两个条件的
             - 生产者已经发布的序列号应该要大于等于sequence -> cursorSequence.get() >= sequence
             - 消费者依赖的上游消费者已经消费过的序列号应该要大于等于sequence -> dependentSequence.get() >= sequence
             但是在 BusySpinWaitStrategy 中 只判断了 dependentSequence.get() >= sequence,这是为什么呢？
        */
        long availableSequence;
        /*
            1. 生产者已经发布的序列号 < 当前消费者想要消费的序列号
              那么说明当前消费者还需要等待生产者发布数据
        */
        if (cursorSequence.get() < sequence)
        {
            /*
                这里为什么要加锁呢？多个消费者存在竞争关系吗？
                 - 没有,在这里加锁,是为了保证BlockingWaitStrategy的语言
                   因为是阻塞等待,那么就必须能够被正常唤醒,在这里使用ReentrantLock + Condition也是可以的
                   如果去看其他类型的WaitStrategy,会看到不同的实现会有不同的做法
            */
            synchronized (mutex)
            {
                while (cursorSequence.get() < sequence) // 需要使用while循环,直到条件满足(生产者已经发布了对应的序列号了)
                {
                    barrier.checkAlert();
                    mutex.wait(); // 阻塞
                }
            }
        } // end if
        /*
            2. 当前消费者依赖的上游消费者(这里应该是getMin())已经消费过的序列号 < 当前消费者想要消费的序列号
                     - 这里.get(),最终调用的就是getMinimumSequence(sequences){sequences是一个数组,保存了当前消费者所依赖的上游消费者的序列号}
                        - 如何找到数组中最小的数呢？有什么好的算法吗？
               也即上游消费者还没有处理到目标序列号,那么当前消费者进行到自旋等待
        */
        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
            Thread.onSpinWait();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        synchronized (mutex)
        {
            mutex.notifyAll();
        }
    }

    @Override
    public String toString()
    {
        return "BlockingWaitStrategy{" +
                "mutex=" + mutex +
                '}';
    }
}
