package org.enso.projectmanager.service

import java.time.OffsetDateTime

import org.enso.projectmanager.model.Project

/** Defines ordering for `project/listRecent` command. The precedence in
  * defining the order has `lastOpened` time, if both times are equal then the
  * creation time is taken into account.
  */
object RecentlyUsedProjectsOrdering extends Ordering[Project] {

  private val Greater = 1

  private val Lesser = -1

  private val Equal = 0

  override def compare(x: Project, y: Project): Int = {
    val xOpenedOrCreated = x.lastOpened.getOrElse(x.created)
    val yOpenedOrCreated = y.lastOpened.getOrElse(y.created)
    val result           = compareDates(xOpenedOrCreated, yOpenedOrCreated)
    if (result == Equal) compareDates(x.created, y.created)
    else result
  }

  private def compareDates(x: OffsetDateTime, y: OffsetDateTime): Int =
    if (x isEqual y) Equal
    else if (x isAfter y) Lesser
    else Greater

}
