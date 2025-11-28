package com.wjcoder.demo_1;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
        SingleProducer();
    }

    public static void SingleProducer() throws InterruptedException
    {
        // 创建Disruptor
        Disruptor<LongEvent> disruptor = new Disruptor<>(
                new LongEventFactory(),
                32,  // 缓冲区大小
                Executors.defaultThreadFactory(),
                ProducerType.MULTI,
                new BlockingWaitStrategy()  // 等待策略
        );

        disruptor.handleEventsWith(new LongEventHandler());

        // 启动Disruptor
        disruptor.start();

        // 发布事件
        RingBuffer<LongEvent> ringBuffer = disruptor.getRingBuffer();
        // 创建生产者
        LongEventProducer producer = new LongEventProducer(ringBuffer);
        // 生产数据
        for (long i = 0; i < 100; i++)
        {
            producer.onData(i + 996);
            Thread.sleep(10);
        }

        // 关闭Disruptor
        disruptor.shutdown();
    }


    public static void MultiProducer() throws InterruptedException
    {
        // 创建Disruptor
        Disruptor<LongEvent> disruptor = new Disruptor<>(
                new LongEventFactory(),
                32,  // 缓冲区大小，必须是2的幂
                Executors.defaultThreadFactory(),
                ProducerType.MULTI,  // 多生产者模式
                new BlockingWaitStrategy()  // 等待策略
        );

        // 注册事件处理器
        disruptor.handleEventsWith(new LongEventHandler());

        // 启动Disruptor
        disruptor.start();

        // 获取RingBuffer
        RingBuffer<LongEvent> ringBuffer = disruptor.getRingBuffer();

        // 创建线程池用于多个生产者
        ExecutorService executorService = Executors.newFixedThreadPool(3);

        // 用于等待所有生产者完成
        CountDownLatch latch = new CountDownLatch(3);

        // 创建3个生产者线程
        for (int producerId = 0; producerId < 1; producerId++)
        {
            final int id = producerId;
            executorService.submit(() ->
            {
                try
                {
                    LongEventProducer producer = new LongEventProducer(ringBuffer);
                    // 每个生产者生产100个数据
                    for (long i = 0; i < 100; i++)
                    {
                        // 使用生产者ID和序号组成唯一数据
                        long data = id * 1000 + i;
                        producer.onData(data);
                        System.out.println("生产者-" + id + " 发布数据: " + data);
                        Thread.sleep(10);
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    latch.countDown();
                }
            });
        }

        // 等待所有生产者完成
        latch.await();

        // 等待一段时间确保所有事件都被消费
        Thread.sleep(1000);

        // 关闭线程池
        executorService.shutdown();

        // 关闭Disruptor
        disruptor.shutdown();

        System.out.println("所有生产者和消费者已完成");
    }


    public static void NSequence()
    {
        // 创建Disruptor
        Disruptor<LongEvent> disruptor = new Disruptor<>(
                new LongEventFactory(),
                32,  // 缓冲区大小，必须是2的幂
                Executors.defaultThreadFactory(),
                ProducerType.MULTI,  // 多生产者模式
                new BlockingWaitStrategy()  // 等待策略
        );

        // 注册事件处理器
        disruptor.handleEventsWith(new LongEventHandler());

        // 启动Disruptor
        disruptor.start();

        // 获取RingBuffer
        RingBuffer<LongEvent> ringBuffer = disruptor.getRingBuffer();

        final long next = ringBuffer.next(10); // 在这里可以主动调用next(10)方法
        ringBuffer.publish(next, next + 10);
    }
}