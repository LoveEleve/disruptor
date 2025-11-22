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
 * Implementors of this interface must provide a single long value
 * that represents their current cursor value.  Used during dynamic
 * add/remove of Sequences from a
 * {@link SequenceGroups#addSequences(Object, java.util.concurrent.atomic.AtomicReferenceFieldUpdater, Cursored, Sequence...)}.
 */

/**
 * 提供序号查询能力,其唯一职责就是：让外部能够查询当前组件的序号位置
 * 抽象 - “位置”的概念,在disruptor中,不同的组件都有"当前位置"的概念,比如:
 *  - 生产者：当前已经发布到了哪个序列号
 *  - 消费者：当前已经处理到了哪个序列号
 *  - RingBuffer:当前最新的序号是多少
 * 而Cursored接口则统一了这个概念(翻译为“光标”)
 *
 */
public interface Cursored
{
    /**
     * Get the current cursor value.
     *
     * @return current cursor value
     */
    long getCursor();
}
