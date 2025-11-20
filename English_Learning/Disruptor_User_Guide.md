# LMAX Disruptor User Guide


The LMAX Disruptor is a high performance inter-thread messaging library.
It grew out of LMAX’s research into concurrency, performance and non-blocking algorithms
and today forms a core part of their Exchange’s infrastructure.

```text
inter-thread ：线程之间 / 线程间

messaging ：传递消息

library：库

grew out of ：源于

research into:对...的研究

forms a core part of：构成核心组成部分

Exchange’s infrastructure ： 交易所的基础设施
```



## Using the Disruptor
### Introduction

The Disruptor is a library that provides a concurrent ring buffer data structure.
```text
that:定语从句,用于修饰 library 的功能
```

It is designed to provide a low-latency, high-throughput work queue in asynchronous event processing architectures.

```text
主干 ：It is designed to provide a work queue:  它被设计来提供一个工作队列.
修饰部分：low-latency, high-throughput 低延迟，高吞吐.
修饰部分：in asynchronous event processing architectures 在异步事件处理框架中.
```



To understand the benefits of the Disruptor we can compare it to something well understood and quite similar in purpose.
```text
something well understood : 不能翻译为 容易理解，通常指“在社区中广为人知，已经有了共识,成熟的技术”
quite similar in purpose：目的非常相似
```
In the case of the Disruptor this would be Java’s BlockingQueue.
```text
In the case of...: 对于...而言
this would be...：这里的would不代表过去将来时,而是一种非常客气的语气,相当于中文的也就是
```
Like a queue the purpose of the Disruptor is to move data (e.g. messages or events) between threads within the same process.
```text
purpose:目的
within：之内
```
However, there are some key features that the Disruptor provides that distinguish it from a queue.
```text
key features:主要(关键的,本质上的)特点(特征)
that the Disruptor provides
that distinguish(区分) it from a queue：将其与队列区分开来
    这两个that都是用来修饰features的,这是一个英语句式,用一个名词后根着多个定语从句来描述
```
They are:
Multicast events to consumers, with consumer dependency graph.
Pre-allocate memory for events.
Optionally lock-free.


#### Core Concepts
Before we can understand how the Disruptor works,
it is worthwhile defining a number of terms that will be used throughout the documentation and the code.
```text
it is worthwhile: 做...是值得的(值得一试)
defining：定义
terms:条款 「词或短语，用于描述事物或表达概念，尤其是在特定类型的语言或研究领域中」
will be used throughout...：将会在整个过程中使用（将会在文档和代码中广泛使用）
    throughout：自始至终
```
For those with a DDD bent, think of this as the ubiquitous language of the Disruptor domain.
```text
with a ... bent: 这里的bent不是弯曲的意思,在这里代表天赋,爱好，倾向等意思，「对于那些倾向DDD的人来说」
think of this as... :可以把(我们正在做的事情)理解为...
ubiquitous language:通用语言

```
- Ring Buffer:
The Ring Buffer is often considered the main aspect of the Disruptor.
```text
is often considered:通常被认为
the main aspect：最核心的特征
```
However, from 3.0 onwards,
```text
onwards:版本
```
the Ring Buffer is only responsible for the storing and updating of the data (Events) that move through the Disruptor.
```text
is only responsible for：仅负责
that move through the Disruptor: 修饰data，流经 Disruptor
```
For some advanced use cases, it can even be completely replaced by the user.
```text
advanced:高级的
use cases：用例
even：表示递进和强调，有出乎意料的语气
```


- Sequence
The Disruptor uses Sequences as a means to identify where a particular component is up to.
```text
uses ... as a means to: 使用....作为一种手段/工具 (地道表达) 使用
identify：确认/识别
where ... is up to: ...已经进行到哪儿了「is up to表示的是进度」
a particular：特定的
```
Each consumer (Event Processor) maintains a Sequence as does the Disruptor itself.
```text
maintains a Sequence:维护着一个Sequence
as does ...: 非常常见,用于避免重复, 代表 ...也一样
```
The majority of the concurrent code relies on the movement of these Sequence values,
```text
relies on the ...: 依赖于
movement of these Sequence values：这些Sequence value的移动
The majority of：大多数
the concurrent code：并发代码
```
hence the Sequence supports many of the current features of an AtomicLong.
```text
hence:因此
supports many of the current features of an AtomicLong：支持许多AtomicLong的现代特性
```
In fact the only real difference between the two is that the Sequence contains additional functionality to prevent false sharing between Sequences and other values.
```text
两者真正的区别在于
prevent false sharing：防止伪共享（false sharing）
between Sequences and other values：在Sequences和其他值之间
```

- Sequencer
The Sequencer is the real core of the Disruptor.
```text
这里可以与上文中的Dispurator做对应，因为在disruptor的介绍中说到：disruptor 常会被认为是核心
但是在这里说明了,Sequencer才是真正的核心
```
The two implementations (single producer, multi producer) of this interface implement all the concurrent algorithms for fast, correct passing of data between producers and consumers.
```text
fast:高性能，低延迟
correct：正确的
passing of data:传递数据
该接口的两种实现方式（单生产者、多生产者）实现了生产者和消费者之间快速、正确地传递数据的所有并发算法
```

- Sequence Barrier:
The Sequencer produces a Sequence Barrier that contains references to the main published Sequence from the Sequencer and the Sequences of any dependent consumer.
It contains the logic to determine if there are any events available for the consumer to process.