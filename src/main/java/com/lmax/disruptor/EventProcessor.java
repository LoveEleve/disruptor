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
 * An EventProcessor needs to be an implementation of a runnable that will poll for events from the {@link RingBuffer}
 * using the appropriate wait strategy.  It is unlikely that you will need to implement this interface yourself.
 * Look at using the {@link EventHandler} interface along with the pre-supplied BatchEventProcessor in the first
 * instance.
 *
 * <p>An EventProcessor will generally be associated with a Thread for execution.
 */

/**
 * EventProcessor本质上一个可在独立线程中运行的一个任务.
 * 一个EventProcessor通常和一个线程绑定
 * 其核心特点为：从RingBuffer中轮询事件
 */
public interface EventProcessor extends Runnable
{
    /**
     * Get a reference to the {@link Sequence} being used by this {@link EventProcessor}.
     * 返回当前EventProcessor使用的Sequence对象
     * Sequence用来表示处理器当前消费到的位置,
     * 因为生产者需要知道最慢的消费者位置
     * @return reference to the {@link Sequence} for this {@link EventProcessor}
     */
    Sequence getSequence();

    /**
     * Signal that this EventProcessor should stop when it has finished consuming at the next clean break.
     * It will call {@link SequenceBarrier#alert()} to notify the thread to check status.
     */
    // 通知EventProcessor停止运行
    void halt();

    /**
     * @return whether this event processor is running or not
     * Implementations should ideally return false only when the associated thread is idle.
     */
    // 返回EventProcessor是否正在运行
    boolean isRunning();
}
