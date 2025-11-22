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
 * Coordination barrier for tracking the cursor for publishers and sequence of
 * dependent {@link EventProcessor}s for processing a data structure
 */

/**
 * 这个接口是消费者端的核心协调组件,其作用是：控制消费者何时可以消费事件，以及如何等待新事件的到达
 * 相当于餐厅的“叫号器”，当前的叫号显示屏上显示的是42，那么顾客(消费者)看到自己手里的号是<42的，那么就代表可以去取餐了
 * 反之,则不能,需要继续等待
 * 核心职责：
 *  - 1.告诉消费者,生产者已经发布到哪个序列号了
 *  - 2.如果事件没有装备好，消费者应该如何等待
 *  - 3.确保消费者能够按照正确的顺序处理事件
 */
public interface SequenceBarrier
{
    /**
     * Wait for the given sequence to be available for consumption.
     *
     * @param sequence to wait for
     * @return the sequence up to which is available
     * @throws AlertException       if a status change has occurred for the Disruptor
     * @throws InterruptedException if the thread needs awaking on a condition variable.
     * @throws TimeoutException     if a timeout occurs while waiting for the supplied sequence.
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /**
     * Get the current cursor value that can be read.
     *
     * @return value of the cursor for entries that have been published.
     */
    long getCursor();

    /**
     * The current alert status for the barrier.
     *
     * @return true if in alert otherwise false.
     */
    boolean isAlerted();

    /**
     * alert() / clearAlert() / checkAlert() - 警报机制
     * 用于优雅的关闭消费者，避免消费者永久的阻塞在waitFor()方法中
     * Alert the {@link EventProcessor}s of a status change and stay in this status until cleared.
     */
    void alert();

    /**
     * Clear the current alert status.
     */
    void clearAlert();

    /**
     * Check if an alert has been raised and throw an {@link AlertException} if it has.
     *
     * @throws AlertException if alert has been raised.
     */
    void checkAlert() throws AlertException;
}
