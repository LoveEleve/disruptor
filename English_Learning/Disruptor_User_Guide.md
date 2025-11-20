# LMAX Disruptor User Guide


The LMAX Disruptor is a high performance inter-thread messaging library.
=
It grew out of LMAX’s research into concurrency, performance and non-blocking algorithms
=
and today forms a core part of their Exchange’s infrastructure.
=

inter-thread ：线程之间 / 线程间
messaging ：传递消息
library：库

grew out of ：源于

research into:对...的研究

forms a core part of：构成核心组成部分

Exchange’s infrastructure ： 交易所的基础设施


## Using the Disruptor
### Introduction

The Disruptor is a library that provides a concurrent ring buffer data structure.
=
that:定语从句,用于修饰 library 的功能

It is designed to provide a low-latency, high-throughput work queue in asynchronous event processing architectures.
=
主干 ：It is designed to provide a work queue:  它被设计来提供一个工作队列
修饰部分：low-latency, high-throughput 低延迟，高吞吐
修饰部分：in asynchronous event processing architectures 在异步事件处理框架中


To understand the benefits of the Disruptor we can compare it to something well understood and quite similar in purpose.
=
In the case of the Disruptor this would be Java’s BlockingQueue.
=
Like a queue the purpose of the Disruptor is to move data (e.g. messages or events) between threads within the same process.
=
However, there are some key features that the Disruptor provides that distinguish it from a queue.
=
They are:
=
test token















