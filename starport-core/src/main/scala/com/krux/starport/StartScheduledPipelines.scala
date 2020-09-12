package com.krux.starport

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import com.krux.starport.cli.{SchedulerOptionParser, SchedulerOptions}
import com.krux.starport.db.record.{Pipeline, SchedulerMetric}
import com.krux.starport.db.table.{Pipelines, SchedulerMetrics}
import com.krux.starport.metric.{ConstantValueGauge, MetricSettings, SimpleTimerGauge}
import com.krux.starport.system.{RemoteScheduleService, ScheduleService}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration._

object StartScheduledPipelines extends StarportActivity {

  val metrics = new MetricRegistry()

  lazy val reportingEngine: MetricSettings = conf.metricsEngine

  val scheduleTimer = metrics.timer("timers.pipeline_scheduling_time")

  def pendingPipelineRecords(scheduledEnd: LocalDateTime): Seq[Pipeline] = {
    logger.info("Retriving pending pipelines..")

    // get all jobs to be scheduled
    val query = Pipelines()
      .filter { p =>
        p.nextRunTime < scheduledEnd &&
        (p.end.isEmpty || p.end > p.nextRunTime) &&
        p.isActive
      }
      .sortBy(_.nextRunTime.asc)
      .take(conf.maxPipelines)

    val result = db.run(query.result).waitForResult

    logger.info(s"Retrieved ${result.size} pending pipelines")

    result
  }

  def run(options: SchedulerOptions): Unit = {
    logger.info(s"run with options: $options")

    val actualStart = options.actualStart
    db.run(DBIO.seq(SchedulerMetrics() += SchedulerMetric(actualStart))).waitForResult

    val pipelineModels = pendingPipelineRecords(options.scheduledEnd)
    db.run(
      DBIO.seq(
        SchedulerMetrics()
          .filter(_.startTime === actualStart)
          .map(_.pipelineCount)
          .update(Option(pipelineModels.size))
      )
    ).waitForResult
    metrics.register("gauges.pipeline_count", new ConstantValueGauge(pipelineModels.size))

    if (conf.isRemoteSchedulerEnabled) {

      RemoteScheduleService.schedule(options, pipelineModels.toList, conf)

    } else {
      Await.result(
        ScheduleService.schedule(
          options,
          parallel * Runtime.getRuntime.availableProcessors,
          pipelineModels.toList,
          conf,
          scheduleTimer
        ),
        1.hour
      )
    }

    db.run(
      DBIO.seq(
        SchedulerMetrics()
          .filter(_.startTime === actualStart)
          .map(_.endTime)
          .update(Option(currentTimeUTC().toLocalDateTime))
      )
    ).waitForResult

    ScheduleService.terminate
  }

  /**
   * @param args Extra envs e.g. (ENV1=x ENV2=y ...)
   */
  def main(args: Array[String]): Unit = {

    val start = System.nanoTime
    val mainTimer = new SimpleTimerGauge(TimeUnit.MINUTES)
    metrics.register("gauges.runtime", mainTimer)

    val reporter = reportingEngine.getReporter(metrics)

    try {
      SchedulerOptionParser.parse(args) match {
        case Some(options) => run(options)
        case None => ErrorExit.invalidCommandlineArguments(logger)
      }

      val timeSpan = (System.nanoTime - start) / 1e9
      logger.info(s"All pipelines scheduled in $timeSpan seconds")
    } finally {
      mainTimer.stop()
      reporter.report()
      reporter.close()
    }
  }

}
