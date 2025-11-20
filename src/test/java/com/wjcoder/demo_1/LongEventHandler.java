package com.wjcoder.demo_1;

import com.lmax.disruptor.EventHandler;

/**
 * @Classname LongEventHandler
 * @Date 11/20/25
 * @Created by ywj
 */
public class LongEventHandler implements EventHandler<LongEvent>
{
    @Override
    public void onEvent(final LongEvent event, final long sequence, final boolean endOfBatch) throws Exception
    {
        System.out.println("Event: " + event.get() + ", sequence: " + sequence + ", endOfBatch: " + endOfBatch);
    }
}
