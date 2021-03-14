package misk.cron

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.squareup.moshi.Moshi
import misk.jobqueue.JobQueue
import misk.jobqueue.QueueName
import misk.logging.getLogger
import misk.moshi.adapter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CronManager @Inject constructor(moshi: Moshi) {
  @Inject private lateinit var clock: Clock
  @Inject private lateinit var jobQueue: JobQueue
  @Inject @ForMiskCron private lateinit var queueName: QueueName
  @Inject @ForMiskCron private lateinit var zoneId: ZoneId

  private val cronJobAdapter = moshi.adapter<CronJob>()

  data class CronEntry(
    val name: String,
    val cronTab: String,
    val executionTime: ExecutionTime,
    val runnable: Runnable
  )

  private val cronEntries = mutableMapOf<String, CronEntry>()

  internal fun addCron(name: String, crontab: String, cron: Runnable) {
    require(name.isNotEmpty()) { "Expecting a valid cron name" }
    require(cronEntries[name] == null) { "Cron $name is already registered" }
    logger.info { "Adding cron entry $name, crontab=$crontab" }

    val cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
    val executionTime = ExecutionTime.forCron(CronParser(cronDefinition).parse(crontab))
    val cronEntry = CronEntry(name, crontab, executionTime, cron)

    cronEntries[name] = cronEntry
  }

  internal fun removeAllCrons() {
    logger.info { "Removing all cron entries" }
    cronEntries.clear()
  }

  fun runReadyCrons(lastRun: Instant) {
    val now = clock.instant()
    val previousTime = ZonedDateTime.ofInstant(lastRun, zoneId)

    logger.info {
      "Last execution was at $previousTime, now=${ZonedDateTime.ofInstant(now, zoneId)}"
    }
    cronEntries.values.forEach { cronEntry ->
      val nextExecutionTime = cronEntry.executionTime.nextExecution(previousTime).orElseThrow()
        .withSecond(0)
        .withNano(0)

      if (nextExecutionTime.toInstant() <= now) {
        logger.info {
          "CronJob ${cronEntry.name} was ready at $nextExecutionTime"
        }
        enqueueCronJob(cronEntry)
      }
    }
  }

  private fun enqueueCronJob(cronEntry: CronEntry) {
    logger.info { "Enqueueing cronjob ${cronEntry.name}" }
    jobQueue.enqueue(
      queueName = queueName,
      body = cronJobAdapter.toJson(CronJob(cronEntry.name))
    )
  }

  internal fun runJob(name: String) {
    logger.info { "Executing cronjob $name" }
    val cronEntry = cronEntries[name] ?: return

    try {
      cronEntry.runnable.run()
    } catch (t: Throwable) {
      logger.warn { "Exception on cronjob $name: ${t.stackTraceToString()}" }
    } finally {
      logger.info { "Executing cronjob $name complete" }
    }
  }

  companion object {
    private val logger = getLogger<CronManager>()
  }
}
