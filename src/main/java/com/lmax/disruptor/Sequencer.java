/*
 * Copyright 2012 LMAX Ltd.
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
 * Coordinates claiming sequences for access to a data structure while tracking dependent {@link Sequence}s
 * 该组件可以被认为是充当了生产者的角色
 */

/**
 * 在这里回顾一下Cursored和Sequenced接口的功能(接口定义行为)：
 * - Cursored:定义了获取当前组件的序号位置 - 位置查询
 * - Sequenced:最基础的序列号管理功能(序号发布,申请,容量控制) - 序号管理
 * 那么看下该接口在上述两个接口之上,又增加了什么新的功能
 * - 协调管理
 * <p>
 * 该接口是连接生产者和消费者的纽带,为什么需要这个接口呢?
 * - 在上面的Cursored和Sequenced,这两个接口，提供的都是单一的功能，比如获取组件当前的序列号，申请序列号，发布序列号
 * 但是在生产者消费者模型中(队列)，这两者不是孤立的，生产者需要put数据到容器中，消费者需要从容器中take数据
 * 并且很自然的,当场景衍生出多个生产者和消费者时，就需要一个功能来协调：协调什么呢?
 * - 生产者能否put数据？
 * - 消费者能够take数据？
 * <p>
 * 所以正如官网的介绍一样：
 * Sequencer: The Sequencer is the real core of the Disruptor.
 * The two implementations (single producer, multi producer) of this interface implement all the concurrent algorithms for fast,
 * correct passing of data between producers and consumers.
 * <p>
 * Sequencer才是Disruptor的核心，这个接口的两个实现(单生产者，多生产者)实现了所有生产者和消费者之间快速，正确传递数据的并发算法。
 * <p>
 * 在这里又会接触到另外3个核心的概念：下面就分别看下这些组件的功能
 * - Sequence(gating)
 * - SequenceBarrier
 * - EventPoller
 */
public interface Sequencer extends Cursored, Sequenced
{
    /**
     * Set to -1 as sequence starting point
     */
    long INITIAL_CURSOR_VALUE = -1L;

    /**
     * Claim a specific sequence.  Only used if initialising the ring buffer to
     * a specific value.
     * 声明(占用)一个特殊的序列号,而不是通过next()来获取下一个可用序号
     *
     * @param sequence The sequence to initialise too.
     */
    void claim(long sequence);

    /**
     * Confirms if a sequence is published and the event is available for use; non-blocking.
     * 检查指定的sequence的事件是否已经被发布(也即可以被消费)
     *
     * @param sequence of the buffer to check
     * @return true if the sequence is available for use, false if not
     */
    boolean isAvailable(long sequence);

    /**
     * Add the specified gating sequences to this instance of the Disruptor.  They will
     * safely and atomically added to the list of gating sequences.
     * 作用：添加门控序列(gating Sequence)，这些序列会影响生产者的发布速度
     * 生产者不能超过最慢的消费者太多,Sequencer会追踪所有的gating Sequence，找到最小值
     * 生产者申请新序号时,必须确保不会覆盖最慢的消费者
     * 使用场景：
     * - 注册消费者：启动消费者时注册其序列
     * - 动态添加消费者：运行时增加新的消费者
     * - 建立依赖关系：确保某些消费者的进度被考虑
     *
     * @param gatingSequences The sequences to add.
     */
    void addGatingSequences(Sequence... gatingSequences);

    /**
     * Remove the specified sequence from this sequencer.
     * 移除不再需要追踪的消费者序列
     *
     * @param sequence to be removed.
     * @return <code>true</code> if this sequence was found, <code>false</code> otherwise.
     */
    boolean removeGatingSequence(Sequence sequence);

    /**
     * Create a new SequenceBarrier to be used by an EventProcessor to track which messages
     * are available to be read from the ring buffer given a list of sequences to track.
     * 作用：创建一个序列屏障,用于协调消费者之间的依赖关系,这里引入了一个新的概念：SequenceBarrier
     * 消费者可以通过膨胀来等待新事件,
     * 屏障也可以追踪其他消费者的进度，建立依赖关系
     *
     * @param sequencesToTrack All of the sequences that the newly constructed barrier will wait on.
     * @return A sequence barrier that will track the specified sequences.
     * @see SequenceBarrier
     */
    SequenceBarrier newBarrier(Sequence... sequencesToTrack);

    /**
     * Get the minimum sequence value from all of the gating sequences
     * added to this ringBuffer.
     * <p>
     * 获取所有gating Sequence的最小值,也即最慢消费者的位置
     *
     * @return The minimum gating sequence or the cursor sequence if
     * no sequences have been added.
     */
    long getMinimumSequence();

    /**
     * Get the highest sequence number that can be safely read from the ring buffer.  Depending
     * on the implementation of the Sequencer this call may need to scan a number of values
     * in the Sequencer.  The scan will range from nextSequence to availableSequence.  If
     * there are no available values <code>&gt;= nextSequence</code> the return value will be
     * <code>nextSequence - 1</code>.  To work correctly a consumer should pass a value that
     * is 1 higher than the last sequence that was successfully processed.
     * <p>
     * 获取在指定范围内已经发布的最高连续序号,用于多生产者场景，
     * 用于解决一个关键的问题：找出哪些序号是可以真正被消费的
     * 场景：
     * t-1:生产者A申请序号10，生产者B申请序号11
     * t-2:生产者B先完成，发布序号11
     * t-3:生产者A还在处理，序号10未发布
     * 这里的一个问题就是,消费者不能跳过序号10直接消费序号11，必须保证顺序消费
     * 所以该方法的作用就是：获取从 nextSequence「生产者期望处理的下一个序号」开始的最高连续已发布序号
     * availableSequence：生产者声称已经发布的最高序号（搜索上限）
     *
     * @param nextSequence      The sequence to start scanning from.
     * @param availableSequence The sequence to scan to.
     * @return The highest value that can be safely read, will be at least <code>nextSequence - 1</code>.
     */
    long getHighestPublishedSequence(long nextSequence, long availableSequence);

    /**
     * Creates an event poller from this sequencer
     * <p>
     * 创建一个事件轮询器,用于主动拉去事件
     *
     * @param provider        from which events are drawn
     * @param gatingSequences sequences to be gated on
     * @param <T>             the type of the event
     * @return the event poller
     */
    <T> EventPoller<T> newPoller(DataProvider<T> provider, Sequence... gatingSequences);
}