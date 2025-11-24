/*
 * Copyright 2023 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lmax.disruptor;

/**
 * 该接口是专门负责处理RewindableException异常的,并且决定了如何进行批次重试
 */
public interface RewindHandler
{
    /**
     *
     * @param e 触发重试的异常对象
     * @param startOfBatchSequence 当前批次的起始序号
     * @return 返回下一个要处理的序列号，也即决定重试的起始位置
     * @throws RewindableException
     */
    long attemptRewindGetNextSequence(RewindableException e, long startOfBatchSequence) throws RewindableException;
}
