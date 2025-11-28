package com.wjcoder.demo_1;

import com.lmax.disruptor.EventTranslatorOneArg;
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

    private static final EventTranslatorOneArg<LongEvent, Long> TRANSLATOR = new EventTranslatorOneArg<LongEvent, Long>()
    {
        @Override
        public void translateTo(final LongEvent event, final long sequence, final Long arg0)
        {
            event.set(arg0);
        }
    };

    public void onData(long value)
    {
        ringBuffer.publishEvent(TRANSLATOR, value);
    }
}
