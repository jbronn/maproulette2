// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.jobs

import javax.inject.{Inject, Singleton}

import akka.actor.{Actor, Props}
import play.api.{Application, Logger}
import play.api.db.Database
import anorm._
import org.maproulette.Config
import org.maproulette.jobs.SchedulerActor.RunJob
import org.maproulette.metrics.Metrics
import org.maproulette.models.Task.STATUS_CREATED

/**
  * The main actor that handles all scheduled activities
  *
  * @author cuthbertm
  * @author davis_20
  */
@Singleton
class SchedulerActor @Inject() (config:Config, application:Application, db:Database) extends Actor {
  val appConfig = application.configuration

  // cleanOldTasks configuration
  lazy val oldTasksStatusFilter = appConfig.getIntSeq(Config.KEY_SCHEDULER_CLEAN_TASKS_STATUS_FILTER).getOrElse(
    Seq(new Integer(STATUS_CREATED))
  )

  override def receive: Receive = {
    case RunJob("cleanLocks", action) => this.cleanLocks(action)
    case RunJob("runChallengeSchedules", action) => this.runChallengeSchedules(action)
    case RunJob("updateLocations", action) => this.updateLocations(action)
    case RunJob("cleanOldTasks", action) => this.cleanOldTasks(action)
  }

  /**
    * This job will remove all stale locks from the system. A stale lock is a lock that has maintained
    * the lock for over an hour. To enable, set:
    *    osm.scheduler.cleanLocks.interval=FiniteDuration
    */
  def cleanLocks(action:String) : Unit = {
    Logger.info(action)
    this.db.withTransaction { implicit c =>
      val locksDeleted = SQL"""DELETE FROM locked WHERE AGE(NOW(), locked_time) > '1 hour'""".executeUpdate()
      Logger.info(s"$locksDeleted were found and deleted.")
    }
  }

  /**
    * This job will update the challenges from remote geojson or overpass query based on the supplied
    * schedules in the challenge. To enable, set:
    *    osm.scheduler.runChallengeSchedules.interval=FiniteDuration
    */
  def runChallengeSchedules(action:String) : Unit = {
    Logger.info(action)
  }


  /**
    * This job will update the locations of all the challenges periodically. To enable, set:
    *    osm.scheduler.updateLocations.interval=FiniteDuration
    */
  def updateLocations(action:String) : Unit = {
    Logger.info(action)
    db.withTransaction { implicit c =>
      val query = """DO $$
                      DECLARE
                        rec RECORD;
                      BEGIN
                        FOR rec IN SELECT id, modified, last_updated FROM challenges LOOP
                          UPDATE challenges SET location = (SELECT ST_Centroid(ST_Collect(ST_Makevalid(location)))
                                  FROM tasks
                                  WHERE parent_id = rec.id),
                                bounding = (SELECT ST_Envelope(ST_Buffer((ST_SetSRID(ST_Extent(location), 4326))::geography,2)::geometry)
                                  FROM tasks
                                  WHERE parent_id = rec.id)
                          WHERE id = rec.id AND rec.modified > rec.last_updated;
                          UPDATE challenges SET last_updated = NOW()
                          WHERE id = rec.id AND rec.modified > rec.last_updated;
                        END LOOP;
                      END$$;"""

      SQL(query).executeUpdate()
    }
    Logger.info("Completed updating challenge locations.")
  }

  /**
    * This job will delete old tasks, filtered by the statusFilter. To enable, set:
    *    osm.scheduler.cleanOldTasks.interval=FiniteDuration
    *    osm.scheduler.cleanOldTasks.olderThan=FiniteDuration
    */
  def cleanOldTasks(action:String) : Unit = {
    config.withFiniteDuration(Config.KEY_SCHEDULER_CLEAN_TASKS_OLDER_THAN) { duration =>
      Metrics.timer("Cleaning old challenge tasks") { () =>
        db.withTransaction { implicit c =>
          Logger.info(s"Cleaning old challenge tasks older than $duration with status [$oldTasksStatusFilter]...")
          val tasksDeleted =
            SQL("""DELETE FROM tasks t USING challenges c
                    WHERE t.parent_id = c.id AND c.updateTasks = true AND t.status IN ({statuses})
                     AND AGE(NOW(), c.modified) > {duration}::INTERVAL
                     AND AGE(NOW(), t.modified) > {duration}::INTERVAL""").on(
              'duration -> ParameterValue.toParameterValue(String.valueOf(duration)),
              'statuses -> ParameterValue.toParameterValue(oldTasksStatusFilter)
            ).executeUpdate()
          Logger.info(s"$tasksDeleted old challenge tasks were found and deleted.")
        }
      }
    }
  }
}

object SchedulerActor {
  def props = Props[SchedulerActor]

  case class RunJob(name:String, action:String="")
}
