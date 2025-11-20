package com.wjcoder.demo_1;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.Executors;

/**
 * @Classname Main
 * @Date 11/20/25
 * @Created by ywj
 */
public class Main
{
    public static void main(String[] args) throws InterruptedException
    {
// 创建Disruptor
        Disruptor<LongEvent> disruptor = new Disruptor<>(
                new LongEventFactory(),
                1024,  // 缓冲区大小
                Executors.defaultThreadFactory(),
                ProducerType.MULTI,  // 多生产者
                new BlockingWaitStrategy()  // 等待策略
        );

        // 连接处理器
        disruptor.handleEventsWith(new LongEventHandler());

        // 启动Disruptor
        disruptor.start();

        // 发布事件
        RingBuffer<LongEvent> ringBuffer = disruptor.getRingBuffer();
        LongEventProducer producer = new LongEventProducer(ringBuffer);

        for (long i = 0; i < 100; i++)
        {
            producer.onData(i);
            Thread.sleep(10);
        }

        // 关闭Disruptor
        disruptor.shutdown();
    }
}