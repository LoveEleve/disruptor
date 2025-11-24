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
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 */

/*
* Sequencer,这个类(或者说的Sequencer接口)的核心职责是：消费者端的核心协调控制器
* 比如:
*  1.告诉消费者,生产者已经发布到了哪个序列号了？
*  2.如果没有可以消费的事件,那么消费者应该如何阻塞? {waitStrategy}
*  3.确保消费者能够按照正常的顺序事件
*
*/
final class ProcessingSequenceBarrier implements SequenceBarrier
{
    private final WaitStrategy waitStrategy; // 决定消费者在等待新事件时的具体行为
    /*
    * 依赖序列的引用,表示当前消费者依赖的上游序列,当前消费者必须等待这个序列推进后才能消费,有两种场景：
    *  - 无依赖场景：指向生产者的cursor
    *  - 有依赖场景: 指向上游消费者的Sequence(消费者链)
    */
    private final Sequence dependentSequence;
    // 用于优雅的关闭消费者
    private volatile boolean alerted = false;
    /*
    * 指向生产者的Sequence,可以获取生产者对应的序列号信息(生产者已经发布的最新序号),用于标识RingBuffer中已发布事件的最大序号
    * 用途：
    *  - 在waitFor()中判断是否有新事件可以消费
    *  - 与dependentSequence配合使用,确定可以消费的范围
    *
    */
    private final Sequence cursorSequence;
    /*
    *  前面说过,Sequencer才是disruptor的核心(而不是RingBuffer),它的核心功能是用来协调生产者和消费者的
    *  在这里持有的是SingleProducerSequencer / MultiProducerSequencer 的引用,职责是提供序号管理的核心功能
    *  用途：
    *  - 调用sequencer.getHighestPublishedSequence()来获取已经发布的最高序号
    *  - 在多生产者模式下,处理序号不连续的问题
    *    - 在单生产者下,cursor就是最高的已发布的序列号
    *    - 在多生产者下,cursor可能跳跃
    */
    private final Sequencer sequencer;

    ProcessingSequenceBarrier(
        final Sequencer sequencer,
        final WaitStrategy waitStrategy,
        final Sequence cursorSequence,
        final Sequence[] dependentSequences)
    {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;
        // 如果当前消费者没有依赖的上游消费者,那么依赖序列就是生产者序列
        if (0 == dependentSequences.length)
        {
            dependentSequence = cursorSequence;
        }
        else // 否则创建一个FixedSequenceGroup对象(内部有一个数组对象,用于存储依赖序列)
        {
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    public long waitFor(final long sequence)
        throws AlertException, InterruptedException, TimeoutException
    {
        checkAlert();

        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);

        if (availableSequence < sequence)
        {
            return availableSequence;
        }

        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    @Override
    public long getCursor()
    {
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted()
    {
        return alerted;
    }

    @Override
    public void alert()
    {
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert()
    {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException
    {
        if (alerted)
        {
            throw AlertException.INSTANCE;
        }
    }
}