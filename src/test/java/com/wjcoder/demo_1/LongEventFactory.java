package com.wjcoder.demo_1;

import com.lmax.disruptor.EventFactory;

/**
 * @Classname LongEventFactory
 * @Date 11/20/25
 * @Created by ywj
 */
public class LongEventFactory implements EventFactory<LongEvent>
{
    @Override
    public LongEvent newInstance()
    {
        return new LongEvent();
    }
}