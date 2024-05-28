package org.opentripplanner.ext.flex.template;

import gnu.trove.set.TIntSet;
import java.time.LocalDate;

/**
 * This class contains information used in a flex router, and depends on the date the search was
 * made on.
 */
public class FlexServiceDate {

  /** The local date */
  private final LocalDate serviceDate;

  /**
   * How many seconds does this date's "midnight" (12 hours before noon) differ from the "midnight"
   * of the date for the search.
   */
  private final int secondsFromStartOfTime;

  /** Which services are running on the date. */
  private final TIntSet servicesRunning;

  public FlexServiceDate(
    LocalDate serviceDate,
    int secondsFromStartOfTime,
    TIntSet servicesRunning
  ) {
    this.serviceDate = serviceDate;
    this.secondsFromStartOfTime = secondsFromStartOfTime;
    this.servicesRunning = servicesRunning;
  }

  LocalDate serviceDate() {
    return serviceDate;
  }

  int secondsFromStartOfTime() {
    return secondsFromStartOfTime;
  }

  public boolean isTripServiceRunning(int serviceCode) {
    return (servicesRunning != null && servicesRunning.contains(serviceCode));
  }
}
