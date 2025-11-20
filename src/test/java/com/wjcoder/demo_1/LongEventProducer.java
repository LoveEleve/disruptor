package com.wjcoder.demo_1;

import com.lmax.disruptor.RingBuffer;

/**
 * @Classname LongEventProducer
 * @Date 11/20/25
 * @Created by ywj
 */
public class LongEventProducer
{
    private final RingBuffer<LongEvent> ringBuffer;

    public LongEventProducer(RingBuffer<LongEvent> ringBuffer)
    {
        this.ringBuffer = ringBuffer;
    }

    public void onData(long value)
    {
        long sequence = ringBuffer.next();  // 获取下一个可用的序列号
        try
        {
            LongEvent event = ringBuffer.get(sequence);  // 获取事件对象
            event.set(value);  // 填充数据
        }
        finally
        {
            ringBuffer.publish(sequence);  // 发布事件
        }
    }
}
