package nl.vroste.rezilience

import nl.vroste.rezilience.RateLimiterPlatformSpecificObj.RateLimiterMetricsInternal
import org.HdrHistogram.{ AbstractHistogram, IntCountsHistogram }
import zio.clock.Clock
import zio.duration.{ durationInt, Duration }
import zio.{ clock, Ref, UIO, ZIO, ZManaged }

import java.time.Instant

trait RateLimiterPlatformSpecificObj {

  /**
   * Create a RateLimiter with metrics
   *
   * Metrics are emitted at a regular interval. When the RateLimiter is released, metrics for the
   * final interval are emitted.
   *
   * @param max
   * @param interval
   * @param onMetrics
   * @param metricsInterval
   * @param latencyHistogramSettings
   * @return
   */
  def makeWithMetrics(
    max: Int,
    interval: Duration = 1.second,
    onMetrics: RateLimiterMetrics => UIO[Any],
    metricsInterval: Duration = 10.seconds,
    latencyHistogramSettings: HistogramSettings[Duration] = HistogramSettings(1.milli, 2.minutes)
  ): ZManaged[Clock, Nothing, RateLimiter] = {

    def makeNewMetrics = clock.instant.map(now => RateLimiterMetricsInternal.empty(now, latencyHistogramSettings))

    def collectMetrics(currentMetrics: Ref[RateLimiterMetricsInternal]) =
      for {
        newMetrics  <- makeNewMetrics
        lastMetrics <-
          currentMetrics.getAndUpdate(metrics => newMetrics.copy(currentlyEnqueued = metrics.currentlyEnqueued))
        interval     = java.time.Duration.between(lastMetrics.start, newMetrics.start)
        _           <- onMetrics(lastMetrics.toUserMetrics(interval))
      } yield ()

    for {
      inner   <- RateLimiter.make(max, interval)
      now     <- clock.instant.toManaged_
      metrics <- makeNewMetrics.flatMap(Ref.make).toManaged_
      _       <- MetricsUtil.runCollectMetricsLoop(metrics, metricsInterval)(collectMetrics)
      env     <- ZManaged.environment[Clock]
    } yield new RateLimiter {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A] = for {
        enqueueTime <- clock.instant.provide(env)
        // Keep track of whether the task was started to have correct statistics under interruption
        started     <- Ref.make(false)
        result      <- metrics
                         .update(_.enqueueTask)
                         .toManaged(_ => metrics.update(_.taskInterrupted).unlessM(started.get))
                         .use_ {
                           inner.apply {
                             for {
                               startTime <- clock.instant.provide(env)
                               latency    = java.time.Duration.between(enqueueTime, startTime)
                               _         <- metrics.update(_.taskStarted(latency)).ensuring(started.set(true))
                               result    <- task
                             } yield result
                           }
                         }
      } yield result
    }
  }
}

private[rezilience] object RateLimiterPlatformSpecificObj extends RateLimiterPlatformSpecificObj {
  final case class RateLimiterMetricsInternal(
    start: Instant,
    latency: AbstractHistogram,
    tasksEnqueued: Long,
    currentlyEnqueued: Long
  ) {
    import HistogramUtil._

    def toUserMetrics(interval: Duration): RateLimiterMetrics =
      RateLimiterMetrics(interval, latency, tasksEnqueued, currentlyEnqueued)

    def taskStarted(latencySample: Duration): RateLimiterMetricsInternal = copy(
      latency = addToHistogram(latency, Seq(Math.max(0, latencySample.toMillis))),
      currentlyEnqueued = currentlyEnqueued - 1
    )

    def taskInterrupted = copy(currentlyEnqueued = currentlyEnqueued - 1)

    def enqueueTask: RateLimiterMetricsInternal =
      copy(tasksEnqueued = tasksEnqueued + 1, currentlyEnqueued = currentlyEnqueued + 1)
  }

  object RateLimiterMetricsInternal {
    def empty(now: Instant, settings: HistogramSettings[Duration]) =
      RateLimiterMetricsInternal(
        start = now,
        latency = new IntCountsHistogram(settings.min.toMillis, settings.max.toMillis, settings.significantDigits),
        tasksEnqueued = 0,
        currentlyEnqueued = 0
      )
  }

}
