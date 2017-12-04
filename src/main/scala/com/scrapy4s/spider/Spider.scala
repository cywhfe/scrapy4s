package com.scrapy4s.spider

import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

import com.scrapy4s.http.proxy.ProxyResource
import com.scrapy4s.http.{Request, RequestConfig, Response}
import com.scrapy4s.pipeline.{MultiThreadPipeline, Pipeline, RequestPipeline}
import com.scrapy4s.scheduler.{HashSetScheduler, Scheduler}
import org.slf4j.LoggerFactory


/**
  * 爬虫核心类，用于组装爬虫
  */
case class Spider(
                   threadCount: Int = Runtime.getRuntime.availableProcessors() * 2,
                   requestConfig: RequestConfig = RequestConfig.default,
                   startUrl: Seq[Request] = Seq.empty[Request],
                   pipelines: Seq[Pipeline] = Seq.empty[Pipeline],
                   scheduler: Scheduler = HashSetScheduler(),
                   currentThreadPool: Option[ThreadPoolExecutor] = None
                 ) {
  val logger = LoggerFactory.getLogger(classOf[Spider])

  lazy private val threadPool = {
    currentThreadPool match {
      case Some(tp) =>
        tp
      case _ =>
        new ThreadPoolExecutor(threadCount, threadCount,
          0L, TimeUnit.MILLISECONDS,
          new LinkedBlockingQueue[Runnable](),
          new CallerRunsPolicy())
    }
  }

  def setThreadPool(tp: ThreadPoolExecutor) = {
    new Spider(
      threadCount = threadCount,
      requestConfig = requestConfig,
      startUrl = startUrl,
      pipelines = pipelines,
      scheduler = scheduler,
      currentThreadPool = Option(tp)
    )
  }

  def setRequestConfig(rc: RequestConfig) = {
    new Spider(
      threadCount = threadCount,
      requestConfig = rc,
      startUrl = startUrl,
      pipelines = pipelines,
      scheduler = scheduler,
      currentThreadPool = currentThreadPool
    )
  }

  def setTestFunc(test_func: Response => Boolean) = {
    setRequestConfig(requestConfig.withTestFunc(test_func))
  }

  def setTimeOut(timeOut: Int) = {
    setRequestConfig(requestConfig.withTimeOut(timeOut))
  }

  def setTryCount(tryCount: Int) = {
    setRequestConfig(requestConfig.withTryCount(tryCount))
  }

  def setProxyResource(proxyResource: ProxyResource) = {
    setRequestConfig(requestConfig.withProxyResource(proxyResource))
  }

  def setStartUrl(urls: Seq[Request]): Spider = {
    new Spider(
      threadCount = threadCount,
      requestConfig = requestConfig,
      startUrl = startUrl ++ urls,
      pipelines = pipelines,
      scheduler = scheduler,
      currentThreadPool = currentThreadPool
    )
  }

  def setStartUrl(url: Request): Spider = {
    setStartUrl(Seq(url))
  }

  def setStartUrl(url: String): Spider = {
    setStartUrl(Request(url))
  }

  def setThreadCount(count: Int) = {
    new Spider(
      threadCount = count,
      requestConfig = requestConfig,
      startUrl = startUrl,
      pipelines = pipelines,
      scheduler = scheduler,
      currentThreadPool = currentThreadPool
    )
  }

  /**
    * 添加数据管道
    *
    * @param pipeline 添加新的数据管道
    * @return
    */
  def pipe(pipeline: Pipeline): Spider = {
    new Spider(
      threadCount = threadCount,
      requestConfig = requestConfig,
      startUrl = startUrl,
      pipelines = pipelines :+ pipeline,
      scheduler = scheduler,
      currentThreadPool = currentThreadPool
    )
  }

  def pipeForRequest(request: Response => Seq[Request]): Spider = {
    pipe(RequestPipeline(request))
  }

  /**
    * 将数据丢入一个新的线程池处理
    *
    * @param pipeline    执行的数据操作
    * @param threadCount 池大小线程数
    * @return
    */
  def fork(pipeline: Pipeline)(implicit threadCount: Int = Runtime.getRuntime.availableProcessors() * 2): Spider = {
    pipe(MultiThreadPipeline(pipeline)(threadCount))
  }

  def setScheduler(s: Scheduler) = {
    new Spider(
      threadCount = threadCount,
      requestConfig = requestConfig,
      startUrl = startUrl,
      pipelines = pipelines,
      scheduler = s,
      currentThreadPool = currentThreadPool
    )
  }


  def start() = {
    run()
    waitForShop()
    this
  }

  /**
    * 初始化爬虫设置，并将初始url倒入任务池中
    */
  def run() = {
    startUrl.foreach(request => {
      execute(request)
    })
    this
  }

  def waitForShop() = {
    threadPool.shutdown()
    while (!threadPool.awaitTermination(1, TimeUnit.SECONDS)) {
      logger.debug("wait for spider done ...")
    }
    pipelines.foreach(p => {
      p.close()
    })
    logger.info("spider done !")
  }

  /**
    * 提交请求任务到线程池
    *
    * @param request 等待执行的请求
    */
  def execute(request: Request): Unit = {
    threadPool.execute(() => {
      try {
        /**
          * 判断是否已经爬取过
          */
        if (scheduler.check(request)) {
          val response = request.execute(this)

          /**
            * 执行数据操作
            */
          pipelines.foreach(p => {
            try {
              p.pipeForRequest(response).foreach(request => this.execute(request))
            } catch {
              case e: Exception =>
                logger.error(s"pipe error, pipe: $p, request: ${request.url}", e)
            }
          })
        } else {
          logger.debug(s"$request has bean spider !")
        }
      } catch {
        case e: Exception =>
          logger.error(s"request: ${request.url} error", e)
      }
    })
  }
}

object Spider {
  def apply(): Spider = new Spider()
}